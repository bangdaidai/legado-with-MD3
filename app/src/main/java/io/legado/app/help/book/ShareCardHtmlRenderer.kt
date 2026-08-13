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
import kotlin.math.abs
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

    /**
     * 出图高度上限（px）。坏模板（`min-height:900vh`、失控的长简介等）会算出巨大高度，
     * 不钳制的话 `Bitmap.createBitmap` 直接 OOM；即使分配成功，超过部分设备 GPU 最大纹理尺寸后
     * Compose 也画不出来。超限时截断，宁可少一截也不崩。
     */
    private const val MAX_CAPTURE_HEIGHT_PX = 12000

    /** 出图缓存上限。每张都是全尺寸 ARGB_8888 长图（可达十几 MB），低端机放不下太多。 */
    private const val PREVIEW_CACHE_MAX = 3

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
     * 页面内 JS → Kotlin 的量高回调桥。
     *
     * 单位是 CSS px（`useWideViewPort=false` 下 1 CSS px == 1 dp）。
     * 高度由页面内 JS 一次算齐（见 [heightReportScript]），Kotlin 侧不再自己拼裁图区域。
     */
    private class MeasureSink {
        private val handler = Handler(Looper.getMainLooper())
        @Volatile
        var callback: ((heightCss: Int) -> Unit)? = null
        @Volatile
        var drawReadyCallback: ((heightCss: Int) -> Unit)? = null
        /**
         * 每次「等重绘」的令牌。并发换色/切日夜时，先发的 [recolorInPlace] 会在 suspend 期间释放
         * renderMutex，后发的会覆盖 [drawReadyCallback]；令牌不匹配的 [onReadyToDraw] 直接丢弃，
         * 避免先发因回调被覆盖而永远等不到 resume、超时返回 null（表现为「切不回/卡一个颜色」）。
         */
        @Volatile
        var drawToken: Long = 0

        @JavascriptInterface
        fun onMeasured(heightCss: Int) {
            if (heightCss <= 0) return
            handler.post { callback?.invoke(heightCss) }
        }

        /**
         * 第二阶段信号：WebView 改完高度并重排、画出一帧后由页面内 JS 调它，
         * 并**回报当前真实高度**供 Kotlin 侧复核（撑高后可能触发二次 reflow，见 [captureAfterMeasure]）。
         * Kotlin 侧收到后才真正 [WebView.draw]，避免截到旧高度 / 半渲染帧
         * （底部缺一节、只有背景）。
         */
        @JavascriptInterface
        fun onReadyToDraw(token: Long, heightCss: Int) {
            if (token != drawToken) return
            handler.post { drawReadyCallback?.invoke(heightCss) }
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

    /**
     * 释放常驻 WebView 与出图缓存（必须在主线程调用）。
     * 常驻 WebView 约占 30~50MB 原生内存，长期不用时应该还给系统；由 `App.onTrimMemory` 触发。
     * 已经交给 Compose 显示的 Bitmap 不 recycle（还在用），这里只丢引用交给 GC。
     *
     * 渲染进行中直接跳过：此时正在 measure/layout/draw 这个 WebView，销毁它会让渲染崩在半路。
     */
    fun release() {
        if (renderMutex.isLocked) return
        warmWebView?.let { wv ->
            wv.stopLoading()
            wv.removeJavascriptInterface(HEIGHT_BRIDGE)
            wv.destroy()
        }
        warmWebView = null
        currentContentKey = null
        synchronized(heightCacheLock) { heightCache.clear() }
        synchronized(cacheLock) { previewCache.clear() }
    }


    /** 常驻热 WebView：只建一次反复用，省掉每次建 WebView 的开销。仅主线程访问。 */
    @Volatile
    private var warmWebView: WebView? = null

    /** 串行化渲染，避免快速切模板/换色时并发用同一个 WebView。 */
    private val renderMutex = Mutex()

    /**
     * 已 loadData 进 warmWebView 的「不含 accent 的 body」哈希。用于判断换色/日夜时能否走
     * [recolorInPlace] 增量路径（复用同一 WebView，不重新解析 HTML）。null 表示当前内容未知，
     * 下次渲染必走完整 [loadAndRender]。仅主线程（Dispatchers.Main）访问。
     */
    @Volatile
    private var currentContentKey: Int? = null

    /**
     * 高度缓存：key = 内容哈希。**约定：换色/切日夜都不改变布局**（accent 只换 CSS 变量值；
     * `.bp-dark` 分支按约定只翻颜色，不动 display/font-size/padding 等——见模板帮助），
     * 所以高度只取决于内容，同一份内容量一次高存下、之后换色切日夜全部复用（对齐 Reeden
     * 「高度算一次存着」）。带 contentKey 也顺带隔离了不同内容，不会跨内容串高度。有界 LRU。
     */
    private val heightCache = LinkedHashMap<Int, Int>(4, 0.75f, true)
    private val heightCacheLock = Any()

    private fun getCachedHeight(contentKey: Int): Int? =
        synchronized(heightCacheLock) { heightCache[contentKey] }

    private fun putCachedHeight(contentKey: Int, h: Int) =
        synchronized(heightCacheLock) {
            while (heightCache.size >= 8) {
                heightCache.keys.firstOrNull()?.let { heightCache.remove(it) }
            }
            heightCache[contentKey] = h
        }

    /**
     * 出图结果缓存：key 由「最终 HTML + 主题色 + 明暗」决定，对相同输入稳定命中，
     * 开门即秒出、不碰 WebView。有界 LRU（[PREVIEW_CACHE_MAX] 张），防止长列表切模板撑爆内存。
     */
    private val previewCache = LinkedHashMap<Int, Bitmap>(PREVIEW_CACHE_MAX, 0.75f, true)
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
        while (previewCache.size >= PREVIEW_CACHE_MAX) {
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

        // sameContent 判断与实际渲染都在 renderMutex + 主线程内串行执行，避免并发下读到过期的
        // currentContentKey / warmWebView（否则可能误走增量路径出错图）。
        val bmp = loadAndRender(context, body, contentKey, accent, forceDark)
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
        val key = cacheKeyOf(injectRenderHead(body, null, null), null, null)
        getCached(key)?.let { return it }
        val bmp = loadAndRender(context, body, body.hashCode(), null, null)
        if (bmp != null) putCached(key, bmp)
        return bmp
    }

    /**
     * 渲染主入口（串行 + 主线程）。同内容仅 accent/明暗变化时走 [recolorInPlace] 增量路径，
     * 否则整段重新 loadData。sameContent 判断放在锁内，避免并发读到过期 [currentContentKey]。
     */
    private suspend fun loadAndRender(
        context: Context,
        body: String,
        contentKey: Int,
        accent: Int?,
        forceDark: Boolean?,
    ): Bitmap? = renderMutex.withLock {
        withContext(Dispatchers.Main.immediate) {
            val sameContent = warmWebView != null && currentContentKey == contentKey
            if (sameContent) {
                val bmp = recolorInPlace(contentKey, accent, forceDark)
                if (bmp == null) currentContentKey = null
                return@withContext bmp
            }
            currentContentKey = null
            val bmp = fullRender(context, injectRenderHead(body, null, null), contentKey, accent, forceDark)
            currentContentKey = if (bmp != null) contentKey else null
            bmp
        }
    }

    /**
     * 完整渲染：loadData 加载（不含 accent 的）HTML，onPageFinished 后再注入 accent 变量，
     * 再量高 + draw。accent 不烘进 HTML，统一走 [accentApplyJs] 注入，
     * 以便后续同内容换色/日夜走 [recolorInPlace] 增量路径（不重新 loadData，主线程占用小、不卡）。
     * 调用方已持有 renderMutex 并在主线程。
     */
    private suspend fun fullRender(
        context: Context,
        htmlNoAccent: String,
        contentKey: Int,
        accent: Int?,
        forceDark: Boolean?,
    ): Bitmap? {
        val density = context.resources.displayMetrics.density
        val widthPx = (context.resources.displayMetrics.widthPixels * 0.92f)
            .toInt().coerceAtLeast(360)
        val wv = ensureWebView(context)

        // 用 JS 桥拿"渲染完成 + 内容高度"，不再靠固定 delay 猜。超时兜底防永久挂起。
        val measuredH = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                measureSink.callback = { h ->
                    if (cont.isActive) cont.resume(h)
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
        if (measuredH == null || measuredH <= 0) return null
        return captureAfterMeasure(wv, density, widthPx, measuredH, contentKey)
    }

    /**
     * 增量换色/日夜：复用已 loadData 的同内容 WebView，只 [accentApplyJs] 改 CSS 变量，
     * 不重新解析 HTML、不重新全量布局，主线程只做"改样式 + 重绘 + draw"，丝滑不卡。
     * 前置：调用方（[loadAndRender]）已确认 warmWebView 当前内容正是同一 body。
     *
     * 高度按 contentKey 复用：换色/切日夜按约定都不改布局（见模板帮助），命中缓存瞬时出图；
     * 缓存缺失（首次或被 LRU 淘汰）才量一次。
     */
    private suspend fun recolorInPlace(
        contentKey: Int,
        accent: Int?,
        forceDark: Boolean?,
    ): Bitmap? {
        val wv = warmWebView ?: return null
        wv.evaluateJavascript(accentApplyJs(accent, forceDark), null)
        val density = wv.context.resources.displayMetrics.density
        val widthPx = (wv.context.resources.displayMetrics.widthPixels * 0.92f)
            .toInt().coerceAtLeast(360)
        val h = getCachedHeight(contentKey) ?: run {
            val m = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    measureSink.callback = { measured ->
                        if (cont.isActive) cont.resume(measured)
                    }
                    wv.evaluateJavascript(MEASURE_JS, null)
                }
            }
            measureSink.callback = null
            (m ?: return null).also { putCachedHeight(contentKey, it) }
        }
        return captureAfterMeasure(wv, density, widthPx, h, contentKey)
    }

    /**
     * 阶段二（改高度后等重排 + 画出一帧）并截图。fullRender / recolorInPlace 共用。
     *
     * 撑高本身可能触发二次 reflow（百分比高度、sticky、图片 aspect-ratio 等），[DRAW_READY_JS]
     * 会回报撑高后的真实高度；若与已 layout 的高度不一致，按新高度再撑一次并再等一帧（至多一次），
     * 覆盖延迟 reflow，避免底部缺一节 / 多一块空白。
     */
    private suspend fun captureAfterMeasure(
        wv: WebView,
        density: Float,
        widthPx: Int,
        heightCss: Int,
        contentKey: Int,
    ): Bitmap? {
        var targetCss = heightCss
        var fullHeightPx = layoutToHeight(wv, widthPx, targetCss, density)

        // 撑高后等一帧并复核真实高度；不一致则按新高度再撑一次（至多一次），吸收二次 reflow。
        val reportedCss = awaitDrawReady(wv)
        if (reportedCss != null && reportedCss > 0 && abs(reportedCss - targetCss) > 1) {
            targetCss = reportedCss
            fullHeightPx = layoutToHeight(wv, widthPx, targetCss, density)
            awaitDrawReady(wv)
        }

        // 记录本次量到的高度，供同内容换色/日夜的增量路径复用。
        putCachedHeight(contentKey, targetCss)

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

    /**
     * 等 WebView 真正重排 + 画出一帧，返回页面回报的当前内容高度（CSS px），超时返回 null。
     * 每次用唯一令牌，并发换色/切日夜时只唤醒本次 continuation（见 [MeasureSink.drawToken]）。
     */
    private suspend fun awaitDrawReady(wv: WebView): Int? {
        val result = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
            val token = ++measureSink.drawToken
            suspendCancellableCoroutine { cont ->
                measureSink.drawReadyCallback = { measured ->
                    if (cont.isActive) cont.resume(measured)
                }
                wv.evaluateJavascript(DRAW_READY_JS.replace("%TOKEN%", token.toString()), null)
            }
        }
        measureSink.drawReadyCallback = null
        return result
    }

    /** 按内容高度（CSS px）把 WebView 撑到满高并 layout，返回钳制后的实际像素高度。 */
    private fun layoutToHeight(wv: WebView, widthPx: Int, heightCss: Int, density: Float): Int {
        val fullHeightPx = (heightCss * density).roundToInt()
            .coerceIn(1, MAX_CAPTURE_HEIGHT_PX)
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(fullHeightPx, View.MeasureSpec.EXACTLY),
        )
        wv.layout(0, 0, widthPx, fullHeightPx)
        return fullHeightPx
    }


    /** 主线程创建/复用常驻 WebView。 */
    private fun ensureWebView(context: Context): WebView {
        warmWebView?.let { return it }
        return WebView(context.applicationContext).apply {
            settings.apply {
                javaScriptEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                setSupportZoom(false)
                builtInZoomControls = false
                // 出图 WebView 全程离线：封面等图片已在 Kotlin 侧预取成 data URI 注入
                // （见 coverUrlToDataUri）。模板来自用户自建/互相分享，禁网可断掉
                // 「恶意模板把数据发出去」和「远程图拖慢出图」两条路，配合 CSP_META 形成纵深防御。
                blockNetworkLoads = true
                blockNetworkImage = true
            }
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addJavascriptInterface(measureSink, HEIGHT_BRIDGE)
            warmWebView = this
        }
    }

    /**
     * 内容安全策略：纵深防御的第二道闸（第一道是 [buildVariableMap] 的全量转义）。
     *
     * - `default-src 'none'`：默认什么都不许加载。
     * - `img-src data:` / `font-src data:`：封面已在 Kotlin 侧预取成 data URI（见 [coverUrlToDataUri]），
     *   模板不需要联网取图；配合 [ensureWebView] 的 `blockNetworkLoads=true`，WebView 全程离线。
     * - `style-src 'unsafe-inline'` / `script-src 'unsafe-inline'`：模板本就是内联 CSS，
     *   渲染器也注入内联 `<script>`（量高脚本），且内置模板用了 `onerror=` 内联事件，必须放开内联。
     * - **`connect-src 'none'`**：这是关键——即使有 XSS 漏网，也无法 fetch/XHR 外发数据。
     */
    private const val CSP_META =
        "<meta http-equiv=\"Content-Security-Policy\" content=\"" +
            "default-src 'none'; img-src data:; font-src data:; " +
            "style-src 'unsafe-inline'; script-src 'unsafe-inline'; " +
            "connect-src 'none'; frame-src 'none'; object-src 'none'; " +
            "base-uri 'none'; form-action 'none'\">"

    /**
     * 给出图 HTML 注入 head：CSP + 横向溢出兜底 + accent 变量（若有）+ 量高脚本。
     * accent 直接烘进 HTML（一次性出图，无需 JS 动态切换），复用 [accentApplyJs] 的注入逻辑。
     */
    private fun injectRenderHead(html: String, accent: Int?, forceDark: Boolean?): String {
        if (html.isBlank()) return ""
        val accentScript =
            if (accent != null) "<script>${accentApplyJs(accent, forceDark)}</script>" else ""
        val head =
            CSP_META +
                """<style>html,body{overflow-x:hidden;}</style>""" +
                """<style id="$ACCENT_STYLE_ID"></style>""" +
                accentScript +
                heightReportScript()
        return if (HEAD_TAG_REGEX.containsMatchIn(html))
            HEAD_TAG_REGEX.replaceFirst(html, "<head>\n$head\n")
        else "$head\n$html"
    }

    /**
     * 量高脚本：定义 `window.__bpHeight__()`（同步算一次高度）和 `window.__bpMeasure__()`
     * （等就绪后回报一次），由 Kotlin 侧在 onPageFinished 后 evaluateJavascript 调用。
     *
     * **不在 onPageFinished 就量高**，而是先等「封面图 decode 完 + 字体 ready + 双 rAF」再量。
     * 否则封面未解码 / 字体未落定时就量高并截图，会出现「只有背景没内容」「底部缺一节」
     * 「多次生成高度不一致」。
     *
     * 高度取四个来源的最大值，再补 margin：
     * 1. `body.getBoundingClientRect().height` —— 正常文档流；
     * 2. `body.scrollHeight` —— 常规溢出；
     * 3. `documentElement.scrollHeight` —— body 之外的溢出；
     * 4. **全后代节点 rect 并集** `maxBottom - min(0, minTop)` —— 绝对定位装饰、负 margin、
     *    `transform: translateY()` 这类元素，前三项都抓不到，只有穷举节点几何才能覆盖；
     * 再加 `marginBottom`（CSS margin 折叠不计入任何 height 属性，否则底部留白被吃掉）。
     * 最后与 `[data-bp-capture]`（海报根节点）底边取大，保证标注节点整体进图。
     *
     * 横向取满宽，因此 body 上的渐变彩底和浮动装饰（设计的一部分）都会保留。
     */
    private fun heightReportScript(): String =
        "<style>html,body{height:auto!important;min-height:0!important;" +
            "overflow-x:hidden!important;overflow-y:visible!important;}</style>" +
            "<script>(function(){" +
            "var b=window.$HEIGHT_BRIDGE;" +
            "function cssNum(v){var n=parseFloat(v||'0');return isFinite(n)?n:0;}" +
            "function measureH(){" +
            " var d=document.body; if(!d)return 0;" +
            " var html=document.documentElement;" +
            " var rect=d.getBoundingClientRect();" +
            " var h=Math.max(rect.height,d.scrollHeight||0,html?(html.scrollHeight||0):0);" +
            " var minTop=Infinity,maxBottom=-Infinity,has=false;" +
            " var all=d.querySelectorAll('*');" +
            " for(var i=0;i<all.length;i++){" +
            "  var r=all[i].getBoundingClientRect();" +
            "  if(!r||r.width<=0||r.height<=0)continue;" +
            "  if(r.top<minTop)minTop=r.top;" +
            "  if(r.bottom>maxBottom)maxBottom=r.bottom;" +
            "  has=true;" +
            " }" +
            " if(has)h=Math.max(h,maxBottom-Math.min(0,minTop));" +
            " h+=cssNum(window.getComputedStyle(d).marginBottom);" +
            " var t=document.querySelector('[data-bp-capture]');" +
            " if(t){var tr=t.getBoundingClientRect();if(tr)h=Math.max(h,tr.bottom);}" +
            " return Math.max(1,Math.ceil(h));" +
            "}" +
            "window.__bpHeight__=measureH;" +
            "function report(){ if(!b)return; var h=measureH(); if(h>0)b.onMeasured(h); }" +
            "function whenReady(cb){" +
            " var imgs=document.images;" +
            " var pending=0;" +
            " for(var i=0;i<imgs.length;i++){ if(!imgs[i].complete||imgs[i].naturalWidth===0) pending++; }" +
            " var done=false;" +
            " var proceed=function(){ if(done)return; done=true;" +
            // 双 rAF：单帧只能保证样式已计算，第二帧才保证上一帧真正排版/绘制完成，
            // 与 DRAW_READY_JS 的等待强度保持一致，避免量到中间态高度。
            "   var afterFonts=function(){ requestAnimationFrame(function(){requestAnimationFrame(cb);}); };" +
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
     *
     * 同时把**撑高后重新量到的高度**一起回报：把 WebView 撑到满高本身可能触发二次 reflow
     * （百分比高度、`position:sticky`、图片 `aspect-ratio` 等），Kotlin 侧据此复核一次。
     */
    private const val DRAW_READY_JS =
        "(function(){var b=window.$HEIGHT_BRIDGE;if(!b)return;" +
            "requestAnimationFrame(function(){requestAnimationFrame(function(){" +
            "b.onReadyToDraw(%TOKEN%,window.__bpHeight__?window.__bpHeight__():0);});});})();"






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
     * **所有字符串字段一律 [escapeHtml]**：这些值大多来自书源网页抓取（书名/作者/章节/标签/简介…），
     * 攻击者可控。按字段挑着转义迟早漏，统一转义最省心。数字字段（toString 结果）不含特殊字符，
     * 无害；`coverUrl` 正常是 data URI（base64 无特殊字符，转义等于原样），异常时（封面预取失败、
     * 残留原始 URL）转义可中和 `src="x" onerror=..."` 这类注入。
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
     * HTML 文本转义。**所有**模板变量都必须过这里：书名/作者/章节名/标签等字段来自书源网页抓取，
     * 是攻击者可控的，未转义直接拼进 HTML 等于把任意 JS 注入到一个开着
     * [addJavascriptInterface] 的 WebView 里。
     *
     * 单引号也要转义——模板可能用 `alt='{{intro}}'` 这种单引号属性。
     * 无需转义时直接返回原串，避免为大字符串（封面 data URI）做无意义的整串复制。
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
