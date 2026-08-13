package io.legado.app.help.book

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import io.legado.app.data.entities.ShareCardData
import io.legado.app.data.entities.ShareCardTemplate
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * 分享卡片渲染器：HTML → Bitmap 离屏出图。
 *
 * 预览显示的是**已渲好的一张 Bitmap**（不是活的 WebView），所以没有测量、没有二段跳、
 * 没有 Sheet 重排。慢的老问题（每次重建 WebView + 固定 delay 猜完成 + 封面每次重解码）
 * 三处都拔掉了：
 * - 复用一个常驻热 WebView（[warmWebView]），只建一次
 * - 用页面内 JS 桥（[HEIGHT_BRIDGE] / [MEASURE_JS] / [DRAW_READY_JS]）分两阶段拿信号：
 *   先等「封面解码 + 字体就绪 + 布局落定」量出真实高度，再把 WebView 撑到满高、等它
 *   真正重绘一帧后才 draw，因此不会只截到背景、底部缺一节或高度忽高忽低
 * - 封面 data URI 缓存（[coverCache]），同一本书只解码一次
 */
object ShareCardHtmlRenderer {

    private const val RENDER_TIMEOUT_MS = 4000L
    private val VARIABLE_REGEX = Regex("\\{\\{(\\w+)\\}\\}")
    private val HEAD_TAG_REGEX = Regex("<head>", RegexOption.IGNORE_CASE)

    /** 换色注入的 accent 变量 style 标签 id。 */
    private const val ACCENT_STYLE_ID = "__bpAccentVars__"

    /**
     * 量高桥接对象名：注入脚本调 `window.<HEIGHT_BRIDGE>.onHeight(cssPx)` 把内容高度报回来。
     * 由页面内 JS 报高度、而不是 Kotlin 侧 View.measure——只有页面内的 JS 知道排版何时结算完，
     * 让它主动报一次，一次即准，不猜时机、不轮询。
     */
    const val HEIGHT_BRIDGE = "__bpHeightBridge__"




    /** 主色 HSL 明度 < 0.55 时走暗方案。供预览 UI 判断亮/暗切换按钮当前朝向。 */
    fun isDarkByDefault(@ColorInt accent: Int): Boolean {
        val r = (accent shr 16) and 0xFF
        val g = (accent shr 8) and 0xFF
        val b = accent and 0xFF
        val lightness = (maxOf(r, g, b) + minOf(r, g, b)) / 2f / 255f
        return lightness < 0.55f
    }

    /** accent 派生结果：`cssVars` 是 `:root{...}` 变量块，`isDark` 决定要不要挂 `bp-dark` class。 */
    data class AccentStyle(val cssVars: String, val isDark: Boolean)
    /**
     * 由主色派生 14 个 `--bp-*` 变量的 CSS 文本。
     *
     * 规则一句话：**色相永远跟随主色；明度按固定阶梯钉死（保证分层 + 文字对比度）；
     * 饱和度按「离视觉中心越远越浓」递增。**
     *
     * 用 HSL 而非 HSV：HSV 的 V 接近 1.0 会被封顶，导致 bg / surface / accent 塌陷成同一个色
     * （主色 V≥0.80 时必然发生）；HSL 给每个变量钉死绝对 L，三层表面明度恒定分离。
     *
     * 三层表面（亮 / 暗，明度走高位或低位，结构同构）：
     * - `surface`         卡片本体（视觉中心）L 0.955 / 0.17
     * - `surface-variant` 卡内次级块           L 0.910 / 0.23
     * - `bg`              最外层彩底           L 0.850 / 0.11
     *
     * 变量含义（模板通过 `var(--bp-*, 兜底色)` 引用，未注入时走兜底色）：
     * - `--bp-accent`           归一化主色（亮 L∈[45%,72%]）：爱心/图标/进度条/实心装饰
     * - `--bp-accent-light`     主色极浅版：背景渐变端、外框
     * - `--bp-accent-rgb`       主色裸三元组 `r,g,b`：配 `rgba(var(--bp-accent-rgb), α)` 控透明度
     * - `--bp-accent-fade`      旧模板兼容：主色 15% 透明淡色
     * - `--bp-star`             星级/评分色（跟随主色色相，和谐优先）
     * - `--bp-on-accent`        压在 --bp-accent 之上的文字色（按主色明度自动取深/浅）
     * - `--bp-bg`               外层彩底——纯色，渐变交给模板自己拼
     * - `--bp-surface`          内层卡片，仍保留色相（不是纯白）
     * - `--bp-surface-rgb`      卡片色裸三元组：毛玻璃半透明卡片用
     * - `--bp-surface-variant`  卡片内次级块
     * - `--bp-text`             最深字（亮 22% / 暗 92%）：标题/数值
     * - `--bp-text-muted`       中等字（亮 42% / 暗 68%）：标签/badge
     * - `--bp-text-subtle`      最浅字（亮 58% / 暗 52%）：作者/label/底栏
     * - `--bp-text-rgb`         文字色裸三元组：投影/低透明文字用
     * - `--bp-divider`          分隔线/边框（带 alpha，同样跟随色相，不硬编码白）
     *
     * 暗方案额外给 `<html>` 挂 `class="bp-dark"`，模板可写 `.bp-dark .xxx {}` 做亮暗分支
     * （CSS 变量无法做条件判断，语义状态色如「在读=绿/读完=蓝」需要这个机制翻色）。
     *
     * forceDark=null 时按主色明度自动判定；非 null 时强制亮(false)/暗(true)，供预览切换。
     */
    fun buildAccentStyle(@ColorInt accent: Int, forceDark: Boolean? = null): AccentStyle {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(accent, hsl)
        val h = hsl[0]
        val s = hsl[1]
        val l = hsl[2]
        val isDark = forceDark ?: (l < 0.55f)

        val accentColor: Int
        val accentLight: Int
        val star: Int
        val onAccent: Int
        val bg: Int
        val surface: Int
        val surfaceVariant: Int
        val text: Int
        val textMuted: Int
        val textSubtle: Int
        val divider: String

        if (!isDark) {
            // 外层彩底 + 内层淡色卡：三层都保留色相，靠明度阶梯分层，不出现纯白/无彩块
            val accentL = l.coerceIn(0.45f, 0.72f)
            accentColor = hslColor(h, s * 0.95f, accentL)
            accentLight = hslColor(h, s * 0.35f, 0.92f)
            star = hslColor(h, s * 0.50f, 0.55f)
            bg = hslColor(h, s * 0.75f, 0.85f)
            surface = hslColor(h, s * 0.55f, 0.955f)
            surfaceVariant = hslColor(h, s * 0.65f, 0.91f)
            text = hslColor(h, s * 0.30f, 0.22f)
            textMuted = hslColor(h, s * 0.25f, 0.42f)
            textSubtle = hslColor(h, s * 0.20f, 0.58f)
            divider = cssHsla(h, s * 0.30f, 0.45f, 0.40f)
            onAccent = if (accentL > 0.60f) hslColor(h, s * 0.30f, 0.15f) else hslColor(h, s * 0.10f, 0.96f)
        } else {
            // 同样保留色相，饱和度整体上调，避免「纯黑灰」观感
            val darkAccentL = (l + 0.18f).coerceIn(0.55f, 0.88f)
            accentColor = hslColor(h, s * 0.90f, darkAccentL)
            accentLight = hslColor(h, s * 0.45f, 0.20f)
            star = hslColor(h, s * 0.60f, (l + 0.20f).coerceIn(0.58f, 0.88f))
            bg = hslColor(h, s * 0.50f, 0.11f)
            surface = hslColor(h, s * 0.45f, 0.17f)
            surfaceVariant = hslColor(h, s * 0.55f, 0.23f)
            text = hslColor(h, s * 0.25f, 0.92f)
            textMuted = hslColor(h, s * 0.22f, 0.68f)
            textSubtle = hslColor(h, s * 0.20f, 0.52f)
            divider = cssHsla(h, s * 0.50f, 0.75f, 0.14f)
            onAccent = if (darkAccentL > 0.60f) hslColor(h, s * 0.30f, 0.15f) else hslColor(h, s * 0.10f, 0.96f)
        }

        val vars = buildString {
            append(":root{")
            append("--bp-accent:").append(cssRgb(accentColor)).append(';')
            append("--bp-accent-light:").append(cssRgb(accentLight)).append(';')
            append("--bp-accent-rgb:").append(rgbTriple(accentColor)).append(';')
            append("--bp-accent-fade:rgba(").append(rgbTriple(accentColor)).append(",0.15);")
            append("--bp-star:").append(cssRgb(star)).append(';')
            append("--bp-on-accent:").append(cssRgb(onAccent)).append(';')
            append("--bp-bg:").append(cssRgb(bg)).append(';')
            append("--bp-surface:").append(cssRgb(surface)).append(';')
            append("--bp-surface-rgb:").append(rgbTriple(surface)).append(';')
            append("--bp-surface-variant:").append(cssRgb(surfaceVariant)).append(';')
            append("--bp-text:").append(cssRgb(text)).append(';')
            append("--bp-text-muted:").append(cssRgb(textMuted)).append(';')
            append("--bp-text-subtle:").append(cssRgb(textSubtle)).append(';')
            append("--bp-text-rgb:").append(rgbTriple(text)).append(';')
            append("--bp-divider:").append(divider).append(';')
            append('}')
        }
        return AccentStyle(vars, isDark)
    }

    // ==================== 离屏出图（预览 + 保存共用一张 Bitmap） ====================

    /**
     * 页面内 JS → Kotlin 的量高/裁图回调桥。
     *
     * 全部单位是 CSS px（`useWideViewPort=false` 下 1 CSS px == 1 dp）。
     * `w<=0` 表示模板没标 `[data-bp-capture]`，走"整幅 body、不裁"的兼容路径。
     */
    private class MeasureSink {
        private val handler = Handler(Looper.getMainLooper())
        @Volatile
        var callback: ((docHeight: Int, x: Int, y: Int, w: Int, h: Int) -> Unit)? = null
        @Volatile
        var drawReadyCallback: (() -> Unit)? = null
        /**
         * 每次「等重绘」的令牌。并发换色/切日夜时，先发的 [recolorInPlace] 会在 suspend 期间释放
         * renderMutex，后发的会覆盖 [drawReadyCallback]；令牌不匹配的 [onReadyToDraw] 直接丢弃，
         * 避免先发因回调被覆盖而永远等不到 resume、超时返回 null（表现为「切不回/卡一个颜色」）。
         */
        @Volatile
        var drawToken: Long = 0

        @JavascriptInterface
        fun onMeasured(docHeight: Int, x: Int, y: Int, w: Int, h: Int) {
            if (docHeight <= 0) return
            handler.post { callback?.invoke(docHeight, x, y, w, h) }
        }

        /**
         * 第二阶段信号：WebView 改完高度并重排、画出一帧后由页面内 JS 调它。
         * Kotlin 侧收到后才真正 [WebView.draw]，避免截到旧高度 / 半渲染帧
         * （底部缺一节、只有背景）。
         */
        @JavascriptInterface
        fun onReadyToDraw(token: Long) {
            if (token != drawToken) return
            handler.post { drawReadyCallback?.invoke() }
        }
    }

    private val measureSink = MeasureSink()

    /**
     * 预热常驻 WebView（必须在主线程调用）。分享面板/预览刚弹出时就建好，
     * 真正渲染时不再现建，少一次 ~80ms 的卡顿，图更快出来（对齐 Reeden 的常驻预热思路）。
     */
    fun warm(context: Context) {
        ensureWebView(context)
    }


    /** 常驻热 WebView：只建一次反复用，省掉每次建 WebView 的开销。仅主线程访问。 */
    private var warmWebView: WebView? = null

    /** 串行化渲染，避免快速切模板/换色时并发用同一个 WebView。 */
    private val renderMutex = Mutex()

    /**
     * 已 loadData 进 warmWebView 的「不含 accent 的 body」哈希。用于判断换色/日夜时能否走
     * [recolorInPlace] 增量路径（复用同一 WebView，不重新解析 HTML）。null 表示当前内容未知，
     * 下次渲染必走完整 [loadAndRender]。
     */
    private var currentContentKey: Int? = null

    /**
     * 上一次成功出图的内容高度（CSS px，= max(文档高度, 捕获节点底边)）。换色/日夜时复用，
     * 跳过重新量高（换色只改 CSS 变量、不改布局），对齐 Reeden「高度算一次存着」。
     * 仅在 [recolorInPlace] 增量路径使用；完整渲染会刷新它。null 时退回量高一次。
     */
    private var lastCaptureHeightCss: Int? = null

    /**
     * 出图结果缓存：key 由「最终 HTML + 主题色 + 明暗」决定，对相同输入稳定命中，
     * 开门即秒出、不碰 WebView。有界 LRU（最多 6 张），防止长列表切模板撑爆内存。
     */
    private val previewCache = LinkedHashMap<Int, Bitmap>(6, 0.75f, true)
    private val cacheLock = Any()

    private fun cacheKeyOf(html: String, accent: Int?, forceDark: Boolean?): Int {
        var h = html.hashCode()
        h = 31 * h + (accent ?: 0)
        h = 31 * h + (forceDark?.hashCode() ?: 0)
        return h
    }

    private fun getCached(key: Int): Bitmap? = synchronized(cacheLock) {
        previewCache[key]?.takeIf { !it.isRecycled }
    }

    private fun putCached(key: Int, bmp: Bitmap) = synchronized(cacheLock) {
        while (previewCache.size >= 6) {
            previewCache.keys.firstOrNull()?.let { previewCache.remove(it) }
        }
        previewCache[key] = bmp
    }

    /**
     * 渲染真实数据的分享卡片为 Bitmap。
     * @param accent 用户临时选择的主题色（ARGB）。非 null 时注入一整套 `--bp-*` HSL 变量。
     * @param forceDark null=按主色明度自动判定；非 null=强制亮(false)/暗(true)。
     */
    suspend fun render(
        context: Context,
        template: ShareCardTemplate,
        data: ShareCardData,
        accent: Int? = null,
        forceDark: Boolean? = null,
    ): Bitmap? {
        val coverUri = coverUrlToDataUri(data.coverUrl)
        val resolved = if (coverUri != null) data.copy(coverUrl = coverUri) else data
        val body = replaceVariables(template.htmlContent, resolved)
        if (body.isBlank()) return null
        // contentKey 只看「模板正文 + 数据」，不含 accent/明暗——同内容换色时保持一致，
        // 从而走 recolorInPlace 增量路径（不重新 loadData）。
        val contentKey = body.hashCode()
        val key = cacheKeyOf(injectRenderHead(body, accent, forceDark), accent, forceDark)
        getCached(key)?.let { return it }

        val sameContent = warmWebView != null &&
            currentContentKey != null && currentContentKey == contentKey
        val bmp = if (sameContent) {
            recolorInPlace(accent, forceDark)
        } else {
            currentContentKey = null
            loadAndRender(context, injectRenderHead(body, null, null), accent, forceDark)
        }
        if (bmp == null) {
            currentContentKey = null
        } else if (!sameContent) {
            currentContentKey = contentKey
        }
        if (bmp != null) putCached(key, bmp)
        return bmp
    }

    /** 渲染裸 HTML + 变量表为 Bitmap（供模板管理页用示例数据预览未保存的模板）。 */
    suspend fun renderCustom(
        context: Context,
        htmlContent: String,
        variables: Map<String, String> = emptyMap(),
    ): Bitmap? {
        val merged = buildVariableMap(ShareCardData()) + variables
        val body = VARIABLE_REGEX.replace(htmlContent) { merged[it.groupValues[1]] ?: it.value }
        if (body.isBlank()) return null
        val finalHtml = injectRenderHead(body, null, null)
        val key = cacheKeyOf(finalHtml, null, null)
        getCached(key)?.let { return it }
        val bmp = loadAndRender(context, finalHtml, null, null)
        // 模板管理页内容与预览 body 不同，打破 currentContentKey，避免预览误走增量路径
        currentContentKey = null
        if (bmp != null) putCached(key, bmp)
        return bmp
    }

    /**
     * 完整渲染：loadData 加载（不含 accent 的）HTML，onPageFinished 后再注入 accent 变量，
     * 再量高 + draw。accent 不烘进 HTML，统一走 [accentApplyJs] 注入，
     * 以便后续同内容换色/日夜走 [recolorInPlace] 增量路径（不重新 loadData，主线程占用小、不卡）。
     */
    private suspend fun loadAndRender(
        context: Context,
        htmlNoAccent: String,
        accent: Int?,
        forceDark: Boolean?,
    ): Bitmap? = renderMutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val density = context.resources.displayMetrics.density
            val widthPx = (context.resources.displayMetrics.widthPixels * 0.92f)
                .toInt().coerceAtLeast(360)
            val wv = ensureWebView(context)

            // 用 JS 桥拿"渲染完成 + 内容高度 + 裁图区域"，不再靠固定 delay 猜。超时兜底防永久挂起。
            val m = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    measureSink.callback = { docH, x, y, w, h ->
                        if (cont.isActive) cont.resume(intArrayOf(docH, x, y, w, h))
                    }
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            // accent 烘进页面（若有），再量高；accent=null 时模板自带配色已正确
                            if (accent != null) {
                                view?.evaluateJavascript(accentApplyJs(accent, forceDark), null)
                            }
                            view?.evaluateJavascript(MEASURE_JS, null)
                        }
                    }
                    // 先按目标宽度 layout，让 loadDataWithBaseURL 的 layout viewport 宽度就绪。
                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    )
                    wv.layout(0, 0, widthPx, wv.measuredHeight.coerceAtLeast(1))
                    wv.loadDataWithBaseURL("about:blank", htmlNoAccent, "text/html", "UTF-8", null)
                }
            }
            measureSink.callback = null
            if (m == null || m[0] <= 0) return@withContext null
            captureAfterMeasure(wv, density, widthPx, m)
        }
    }

    /**
     * 增量换色/日夜：复用已 loadData 的同内容 WebView，只 [accentApplyJs] 改 CSS 变量，
     * 不重新解析 HTML、不重新全量布局，主线程只做"改样式 + 重绘 + draw"，丝滑不卡。
     * 前置：调用方（[render]）已确认 warmWebView 当前内容正是同一 body（currentContentKey 匹配）。
     */
    private suspend fun recolorInPlace(accent: Int?, forceDark: Boolean?): Bitmap? = renderMutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val wv = warmWebView ?: return@withContext null
            wv.evaluateJavascript(accentApplyJs(accent, forceDark), null)
            // 换色/日夜只改 CSS 变量、不改布局，直接复用上次量好的高度，跳过 MEASURE_JS 重测量
            // （省掉 whenReady + 字体 + 双 rAF + measure 异步链），对齐 Reeden「高度算一次存着」。
            // lastCaptureHeightCss 为 null 时（极端兜底）退回量高一次。
            val density = wv.context.resources.displayMetrics.density
            val widthPx = (wv.context.resources.displayMetrics.widthPixels * 0.92f)
                .toInt().coerceAtLeast(360)
            val h = lastCaptureHeightCss ?: run {
                val m = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        measureSink.callback = { docH, x, y, w, h ->
                            if (cont.isActive) cont.resume(intArrayOf(docH, x, y, w, h))
                        }
                        wv.evaluateJavascript(MEASURE_JS, null)
                    }
                }
                measureSink.callback = null
                (m?.let { maxOf(it[0], it[4]) } ?: return@withContext null).also {
                    lastCaptureHeightCss = it
                }
            }
            // 构造与 captureAfterMeasure 约定一致的量高数组：docH=capH=h（高度已知，无需再量）。
            captureAfterMeasure(wv, density, widthPx, intArrayOf(h, 0, 0, 0, h))
        }
    }

    /** 阶段二（改高度后等重排 + 画出一帧）并截图。loadAndRender / recolorInPlace 共用。 */
    private suspend fun captureAfterMeasure(
        wv: WebView,
        density: Float,
        widthPx: Int,
        m: IntArray,
    ): Bitmap? {
        val docHCss = m[0]
        val capHCss = m[4]
        // 满幅输出：横向取满宽，纵向取「文档高度 与 捕获节点底边」的较大值，
        // 保证 body 彩底 + 卡片 + 底部留白全部进图，且不会被绝对定位装饰带出多余空白。
        val fullHeightPx = (maxOf(docHCss, capHCss) * density)
            .roundToInt().coerceAtLeast(1)
        // 记录本次量到的高度，供同内容换色/日夜的增量路径复用，避免重复量高。
        lastCaptureHeightCss = maxOf(docHCss, capHCss)
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(fullHeightPx, View.MeasureSpec.EXACTLY),
        )
        wv.layout(0, 0, widthPx, fullHeightPx)

        // 阶段二：WebView 改了高度后必须等它真正重排 + 画出一帧再 draw，
        // 否则截到的是旧高度 / 半渲染帧（底部缺一节、只有背景）。
        // 每次用唯一令牌，并发换色/切日夜时只唤醒本次 continuation（见 [MeasureSink.drawToken]）。
        val drawToken = ++measureSink.drawToken
        withTimeoutOrNull(RENDER_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                measureSink.drawReadyCallback = { if (cont.isActive) cont.resume(Unit) }
                wv.evaluateJavascript(DRAW_READY_JS.replace("%TOKEN%", drawToken.toString()), null)
            }
        }
        measureSink.drawReadyCallback = null

        return try {
            Bitmap.createBitmap(widthPx, fullHeightPx, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                wv.invalidate()
                wv.draw(canvas)
            }
        } catch (_: Throwable) {
            null
        }
    }


    /** 主线程创建/复用常驻 WebView。 */
    private fun ensureWebView(context: Context): WebView {
        warmWebView?.let { return it }
        return WebView(context.applicationContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                setSupportZoom(false)
                builtInZoomControls = false
                blockNetworkLoads = false
                blockNetworkImage = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addJavascriptInterface(measureSink, HEIGHT_BRIDGE)
            warmWebView = this
        }
    }

    /**
     * 给出图 HTML 注入 head：横向溢出兜底 + accent 变量（若有）+ 量高脚本。
     * accent 直接烘进 HTML（一次性出图，无需 JS 动态切换），复用 [accentApplyJs] 的注入逻辑。
     */
    private fun injectRenderHead(html: String, accent: Int?, forceDark: Boolean?): String {
        if (html.isBlank()) return ""
        val accentScript =
            if (accent != null) "<script>${accentApplyJs(accent, forceDark)}</script>" else ""
        val head =
            """<style>html,body{overflow-x:hidden;}</style>""" +
                """<style id="$ACCENT_STYLE_ID"></style>""" +
                accentScript +
                heightReportScript()
        return if (HEAD_TAG_REGEX.containsMatchIn(html))
            HEAD_TAG_REGEX.replaceFirst(html, "<head>\n$head\n")
        else "$head\n$html"
    }

    /**
     * 量高/裁图脚本：定义 `window.__bpMeasure__()`，由 Kotlin 侧在 onPageFinished 后
     * evaluateJavascript 主动调用（此时宽度已就绪）。
     *
     * 关键修正（对照 Reeden 的 `window.Reeden.ready()`）：**不再在 onPageFinished 就量高**，
     * 而是先等「封面图 decode 完 + 字体 ready + 双 rAF」再量。否则封面未解码 / 字体未落定
     * 时就量高并截图，会出现「只有背景没内容」「底部缺一节」「多次生成高度不一致」。
     *
     * 裁图区域由模板标注的 `[data-bp-capture]`（海报根节点）决定：
     * **横向取满宽、纵向取「文档高度 与 捕获节点底边」的较大值**。
     * 这样既保留 body 上的渐变彩底和浮动装饰（设计的一部分），又把总高度钉在真实内容上，
     * 不会被 `body.scrollHeight`（min-height:100vh、绝对定位装饰溢出）带出上下一大截空背景，
     * 也不会因为旧公式 `r.top + r.bottom` 把上留白翻倍而多出一屏背景。
     *
     * 没标 `[data-bp-capture]` 的用户模板 fallback 到 `body.scrollHeight` 整幅不裁。
     */
    private fun heightReportScript(): String =
        "<style>html,body{height:auto!important;min-height:0!important;overflow:visible!important;}</style>" +
            "<script>(function(){" +
            "var b=window.$HEIGHT_BRIDGE;" +
            "function report(){" +
            " if(!b)return;" +
            " var d=document.body; if(!d)return;" +
            " var doc=Math.ceil(d.scrollHeight);" +
            " var t=document.querySelector('[data-bp-capture]');" +
            " var w=Math.ceil(document.documentElement.clientWidth||d.clientWidth);" +
            " var h=doc;" +
            " if(t){var r=t.getBoundingClientRect(); h=Math.ceil(Math.max(doc,r.bottom));}" +
            " if(w>0&&h>0){b.onMeasured(Math.max(doc,h),0,0,w,h);return;}" +
            " if(doc>0)b.onMeasured(doc,0,0,0,0);" +
            "}" +
            "function whenReady(cb){" +
            " var imgs=document.images;" +
            " var pending=0;" +
            " for(var i=0;i<imgs.length;i++){ if(!imgs[i].complete||imgs[i].naturalWidth===0) pending++; }" +
            " var done=false;" +
            " var proceed=function(){ if(done)return; done=true;" +
            "   var afterFonts=function(){ requestAnimationFrame(cb); };" +
            "   if(document.fonts&&document.fonts.ready&&document.fonts.ready.then){ document.fonts.ready.then(afterFonts,afterFonts); } else { afterFonts(); } };" +
            " if(pending===0){ proceed(); return; }" +
            " var to=setTimeout(proceed,1500);" +
            " for(var i=0;i<imgs.length;i++){(function(img){ if(img.complete&&img.naturalWidth>0)return;" +
            "   img.addEventListener('load',function(){ if(--pending<=0){clearTimeout(to);proceed();} });" +
            "   img.addEventListener('error',function(){ if(--pending<=0){clearTimeout(to);proceed();} });" +
            " })(imgs[i]); }" +
            "}" +
            "window.__bpMeasure__=function(){ whenReady(report); };" +
            "})();</script>"

    /** 由 Kotlin 侧在 onPageFinished 后 evaluateJavascript 调用，触发一次量高。 */
    private const val MEASURE_JS = "if(window.__bpMeasure__)window.__bpMeasure__();"

    /**
     * 第二阶段脚本：WebView 改完高度并重排后，由 Kotlin 侧 evaluateJavascript 调用，
     * 等双 rAF 保证真正画出一帧，再回调 [MeasureSink.onReadyToDraw] 才执行截图。
     * 不这样等，截到的是旧高度 / 半渲染帧（底部缺一节、只有背景）。
     */
    private const val DRAW_READY_JS =
        "(function(){var b=window.$HEIGHT_BRIDGE;if(!b)return;" +
            "requestAnimationFrame(function(){requestAnimationFrame(function(){b.onReadyToDraw(%TOKEN%);});});})();"






    /**
     * 生成一段 JS：把 [ACCENT_STYLE_ID] 这个 style 的内容换成新的变量块，并同步 `bp-dark` class。
     * 页面原地重绘，无需 reload —— 这是「换色瞬间生效」的关键。
     * @param accent null 表示清空变量，回到模板自带配色。
     */
    fun accentApplyJs(@ColorInt accent: Int?, forceDark: Boolean? = null): String {
        val css: String
        val dark: Boolean
        if (accent == null) {
            css = ""
            dark = false
        } else {
            val style = buildAccentStyle(accent, forceDark)
            css = jsEscape(style.cssVars)
            dark = style.isDark
        }
        return "(function(){var s=document.getElementById('$ACCENT_STYLE_ID');" +
            "if(!s){s=document.createElement('style');s.id='$ACCENT_STYLE_ID';document.head.appendChild(s);}" +
            "s.textContent='$css';" +
            "document.documentElement.classList.toggle('bp-dark',$dark);})();"
    }

    private fun jsEscape(s: String): String =
        s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")

    // ==================== 工具函数 ====================

    private fun hslColor(hue: Float, sat: Float, lightness: Float): Int =
        ColorUtils.HSLToColor(floatArrayOf(hue.coerceIn(0f, 360f), sat.coerceIn(0f, 1f), lightness.coerceIn(0f, 1f)))

    private fun cssRgb(@ColorInt color: Int): String =
        "rgb(${(color shr 16) and 0xFF},${(color shr 8) and 0xFF},${color and 0xFF})"

    private fun rgbTriple(@ColorInt color: Int): String =
        "${(color shr 16) and 0xFF},${(color shr 8) and 0xFF},${color and 0xFF}"

    private fun cssHsla(hue: Float, sat: Float, lightness: Float, alpha: Float): String {
        val c = hslColor(hue, sat, lightness)
        return "rgba(${(c shr 16) and 0xFF},${(c shr 8) and 0xFF},${c and 0xFF},${alpha.coerceIn(0f, 1f)})"
    }

    private fun replaceVariables(html: String, data: ShareCardData): String {
        val map = buildVariableMap(data)
        return VARIABLE_REGEX.replace(html) { map[it.groupValues[1]] ?: it.value }
    }

    /**
     * 构造 `{{var}}` 替换表。
     *
     * **所有字符串字段一律 [escapeHtml]**：这些值大多来自书源网页抓取，抓取规则稍宽就会带进
     * HTML 片段。未转义时 WebView 会把它们当标签解析并吞掉，例如章节名 `第一章 <上>` 会只剩
     * `第一章`。原先只挑了 4 个长文本字段转义，其余字段同样会中招，统一转义最省心。
     * 数字字段（toString 结果）不含特殊字符，转义等于原样。
     */
    internal fun buildVariableMap(d: ShareCardData): Map<String, String> = mapOf(
        "bookName" to escapeHtml(d.bookName), "author" to escapeHtml(d.author),
        "coverUrl" to escapeHtml(d.coverUrl),
        "intro" to escapeHtml(d.intro), "kind" to escapeHtml(d.kind),
        "wordCount" to escapeHtml(d.wordCount),
        "originName" to escapeHtml(d.originName), "totalChapterNum" to d.totalChapterNum.toString(),
        "latestChapterTitle" to escapeHtml(d.latestChapterTitle),
        "typeText" to escapeHtml(d.typeText), "charset" to escapeHtml(d.charset),
        "readingStatusText" to escapeHtml(d.readingStatusText),
        "readingProgress" to escapeHtml(d.readingProgress),
        "readChapters" to escapeHtml(d.readChapters), "unreadChapters" to d.unreadChapters.toString(),
        "readIteration" to d.readIteration.toString(),
        "readIterationText" to escapeHtml(d.readIterationText),
        "durChapterTitle" to escapeHtml(d.durChapterTitle),
        "totalReadTime" to escapeHtml(d.totalReadTime),
        "totalReadHours" to d.totalReadHours.toString(), "totalReadMinutes" to d.totalReadMinutes.toString(),
        "readingDays" to d.readingDays.toString(), "maxDayReadTime" to escapeHtml(d.maxDayReadTime),
        "maxDayReadDate" to escapeHtml(d.maxDayReadDate), "totalReadWords" to escapeHtml(d.totalReadWords),
        "remainingWords" to escapeHtml(d.remainingWords), "firstReadTime" to escapeHtml(d.firstReadTime),
        "lastReadTime" to escapeHtml(d.lastReadTime), "finishReadTime" to escapeHtml(d.finishReadTime),
        "addBookshelfTime" to escapeHtml(d.addBookshelfTime), "lastCheckTime" to escapeHtml(d.lastCheckTime),
        "lastReadTimeRelative" to escapeHtml(d.lastReadTimeRelative), "rating" to d.rating.toString(),
        "ratingStars" to escapeHtml(d.ratingStars), "ratingMax" to d.ratingMax.toString(),
        "reviewContent" to escapeHtml(d.reviewContent), "annotationCount" to d.annotationCount.toString(),
        "thoughtCount" to d.thoughtCount.toString(), "latestAnnotation" to escapeHtml(d.latestAnnotation),
        "latestAnnotationNote" to escapeHtml(d.latestAnnotationNote),
        "latestAnnotationChapter" to escapeHtml(d.latestAnnotationChapter),
        "protagonists" to escapeHtml(d.protagonists),
        "tags" to escapeHtml(d.tags), "tagCount" to d.tagCount.toString(),
        "bookSourceName" to escapeHtml(d.bookSourceName), "bookSourceGroup" to escapeHtml(d.bookSourceGroup),
        "readTimeRank" to escapeHtml(d.readTimeRank),
    )

    /**
     * HTML 文本转义。单引号也要转，模板可能用 `alt='{{intro}}'` 这种单引号属性。
     * 无需转义时直接返回原串，避免为大字符串（封面 data URI 是几百 KB base64）做无意义的整串复制。
     */
    private fun escapeHtml(t: String): String {
        if (t.isEmpty()) return t
        if (t.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return t
        val sb = StringBuilder(t.length + 8)
        for (c in t) when (c) {
            '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;"); '\'' -> sb.append("&#39;"); else -> sb.append(c)
        }
        return sb.toString()
    }

    // ==================== 封面 data URI 缓存 ====================

    private val coverCache = ConcurrentHashMap<String, String>(8)

    fun clearCoverCache() = coverCache.clear()

    /**
     * 封面 URL → data URI。统一走「Kotlin 侧把字节拿齐再注入」的确定路径：
     * - `data:` 直接返回；
     * - 本地文件（file:// 或路径）：解码后转 JPEG base64；
     * - 网络封面（http/https）：用 App 的 OkHttp 预取字节再转 data URI，
     *   **WebView 全程零网络**（对齐 Reeden 的 red-resource:// 直供思路），
     *   消除「WebView 渲染时自行联网」的异步竞态——弱网/离线也稳，不会只截到背景。
     * 同一 URL 走 [coverCache] 只取/转一次。
     */
    private suspend fun coverUrlToDataUri(url: String): String? {
        if (url.isBlank()) return null
        if (url.startsWith("data:")) return url
        coverCache[url]?.let { return it }

        val result = if (url.startsWith("http")) {
            withContext(Dispatchers.IO) {
                try {
                    val body = okHttpClient.newCallResponseBody { url(url) }
                    val bytes = body.bytes()
                    if (bytes.isEmpty()) null else decodeToDataUri(bytes, body.contentType()?.toString())
                } catch (_: Exception) { null }
            }
        } else {
            try {
                val f = if (url.startsWith("file://")) File(URI(url)) else File(url)
                if (f.exists()) {
                    BitmapFactory.decodeFile(f.absolutePath)?.let { bmp ->
                        val uri = bitmapToJpegDataUri(bmp)
                        bmp.recycle()
                        uri
                    }
                } else null
            } catch (_: Exception) { null }
        }
        if (result != null) coverCache[url] = result
        return result
    }

    /** 字节解码后转 JPEG base64 data URI；解码失败（SVG/动图等）按原 MIME 透传原始字节。 */
    private fun decodeToDataUri(bytes: ByteArray, mime: String? = null): String {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bmp != null) {
            val uri = bitmapToJpegDataUri(bmp)
            bmp.recycle()
            return uri
        }
        return "data:${mime ?: "image/*"};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun bitmapToJpegDataUri(bmp: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        return "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }
}
