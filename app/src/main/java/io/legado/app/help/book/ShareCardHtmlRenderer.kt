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
 * - 用页面内 JS 桥（[HEIGHT_BRIDGE] / [MEASURE_JS]）拿"渲染完成 + 内容高度"信号，
 *   渲染一完成立刻出图，不再干等固定 delay
 * - 封面 data URI 缓存（[coverCache]），同一本书只解码一次
 */
object ShareCardHtmlRenderer {

    private const val RENDER_TIMEOUT_MS = 4000L

    /**
     * 出图逻辑宽度（CSS px）。钉死不跟屏幕宽走，保证同一模板在任何机型上排版一致
     * （原来按 `屏幕宽 * 0.92` 算，1080p 得 361 CSS px、720p 只有 331 CSS px，排版会飘）。
     */
    private const val CSS_WIDTH = 375

    /** 目标「物理像素 / CSS px」。4 表示 375 CSS px 出 1500px 宽的图，够朋友圈级别清晰度。 */
    private const val TARGET_PX_PER_CSS = 4f

    /** 超采样后的像素上限（约 48MB ARGB_8888）。超了按比例降倍数，防超长图 OOM。 */
    private const val MAX_OUTPUT_PIXELS = 12_000_000f

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

        @JavascriptInterface
        fun onMeasured(docHeight: Int, x: Int, y: Int, w: Int, h: Int) {
            if (docHeight <= 0) return
            handler.post { callback?.invoke(docHeight, x, y, w, h) }
        }
    }

    private val measureSink = MeasureSink()


    /** 常驻热 WebView：只建一次反复用，省掉每次建 WebView 的开销。仅主线程访问。 */
    private var warmWebView: WebView? = null

    /** 串行化渲染，避免快速切模板/换色时并发用同一个 WebView。 */
    private val renderMutex = Mutex()

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
        // 封面解码（decodeFile + JPEG.compress）挪到 IO，避免首次未命中缓存时阻塞主线程。
        val coverUri = withContext(Dispatchers.IO) { coverUrlToDataUri(data.coverUrl) }
        val resolved = if (coverUri != null) data.copy(coverUrl = coverUri) else data
        val body = replaceVariables(template.htmlContent, resolved)
        if (body.isBlank()) return null
        return renderHtmlToBitmap(context, injectRenderHead(body, accent, forceDark))
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
        return renderHtmlToBitmap(context, injectRenderHead(body, null, null))
    }

    private suspend fun renderHtmlToBitmap(context: Context, html: String): Bitmap? =
        renderMutex.withLock {
            withContext(Dispatchers.Main.immediate) {
                val density = context.resources.displayMetrics.density
                // layout 宽度按 density 反推，让 CSS viewport 正好是 CSS_WIDTH
                // （useWideViewPort=false 下 1 CSS px == 1 dp），排版结果与屏幕宽度无关。
                val widthPx = (CSS_WIDTH * density).roundToInt().coerceAtLeast(360)
                val wv = ensureWebView(context)

                // 用 JS 桥拿"渲染完成 + 内容高度 + 裁图区域"，不再靠固定 delay 猜。超时兜底防永久挂起。
                val m = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                    suspendCancellableCoroutine { cont ->
                        measureSink.callback = { docH, x, y, w, h ->
                            if (cont.isActive) cont.resume(intArrayOf(docH, x, y, w, h))
                        }
                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                view?.evaluateJavascript(MEASURE_JS, null)
                            }
                        }
                        // 先按目标宽度 layout，让 loadDataWithBaseURL 的 layout viewport 宽度就绪。
                        wv.measure(
                            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        )
                        wv.layout(0, 0, widthPx, wv.measuredHeight.coerceAtLeast(1))
                        wv.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)
                    }
                }
                measureSink.callback = null
                if (m == null || m[0] <= 0) return@withContext null

                val docHCss = m[0]
                val capXCss = m[1]
                val capYCss = m[2]
                val capWCss = m[3]
                val capHCss = m[4]
                // 先把 WebView layout 到整份文档高度，保证裁图区域内的内容全部已排版渲染。
                val fullHeightPx = ((maxOf(docHCss, capYCss + capHCss)) * density)
                    .roundToInt().coerceAtLeast(1)
                wv.measure(
                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(fullHeightPx, View.MeasureSpec.EXACTLY),
                )
                wv.layout(0, 0, widthPx, fullHeightPx)

                // capWCss<=0 表示模板没标 [data-bp-capture]，退回"整幅不裁"。
                val cropped = capWCss > 0 && capHCss > 0
                val outX = if (cropped) (capXCss * density).roundToInt() else 0
                val outY = if (cropped) (capYCss * density).roundToInt() else 0
                val outW = if (cropped) {
                    (capWCss * density).roundToInt().coerceIn(1, widthPx - outX)
                } else widthPx
                val outH = if (cropped) {
                    (capHCss * density).roundToInt().coerceIn(1, fullHeightPx - outY)
                } else fullHeightPx

                // 超采样：把 layout 出的图（1 CSS px == density 物理像素）放大到 TARGET_PX_PER_CSS，
                // canvas.scale 让 WebView.draw 直接以更高分辨率光栅化文字/矢量，成图更清晰。
                // 超长图会触顶 MAX_OUTPUT_PIXELS，按比例回退倍数防 OOM。
                var superScale = TARGET_PX_PER_CSS / density
                val finalWf = outW * superScale
                val finalHf = outH * superScale
                if (finalWf * finalHf > MAX_OUTPUT_PIXELS) {
                    superScale *= kotlin.math.sqrt(MAX_OUTPUT_PIXELS / (finalWf * finalHf))
                }
                val finalW = (outW * superScale).roundToInt().coerceAtLeast(1)
                val finalH = (outH * superScale).roundToInt().coerceAtLeast(1)

                try {
                    Bitmap.createBitmap(finalW, finalH, Bitmap.Config.ARGB_8888).also { bmp ->
                        val canvas = Canvas(bmp)
                        canvas.scale(superScale, superScale)
                        // 平移画布把裁图区域挪到原点，一次 draw 直接出裁好的图，不建大中间位图。
                        if (outX != 0 || outY != 0) canvas.translate(-outX.toFloat(), -outY.toFloat())
                        wv.draw(canvas)
                    }
                } catch (_: Throwable) {
                    null
                }
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
     * 裁图区域由模板标注的 `[data-bp-capture]`（海报根节点）决定：
     * **横向取满宽、纵向取「卡片上留白 + 卡片 + 等量下留白」**。
     * 这样 body 上的渐变彩底和浮动装饰照样保留（它们是设计的一部分），
     * 但总高度被钉在卡片实际高度上，不会再被 `body.scrollHeight`（min-height:100vh、
     * 绝对定位装饰溢出）带出上下一大截空背景。
     *
     * 没标 `[data-bp-capture]` 的用户模板 fallback 到「内容并集高度」整幅不裁。
     *
     * 内容高度一律走**可见后代 rect 的包围盒下边 + body 下内/外边距**，不用 `scrollHeight`：
     * 后者会把 `min-height`、0 尺寸的绝对定位空节点、浮动装饰的溢出统统算进去，
     * 尾巴上拖出一大截空背景（这套 bug 反复修过好几轮）。
     */
    private fun heightReportScript(): String =
        "<style>html,body{height:auto!important;min-height:0!important;overflow:visible!important;}</style>" +
            "<script>(function(){" +
            "function num(v){var n=parseFloat(v);return isFinite(n)?n:0;}" +
            "window.__bpMeasure__=function(){" +
            "var b=window.$HEIGHT_BRIDGE;if(!b)return;" +
            "var d=document.body;if(!d)return;" +
            "var bs=window.getComputedStyle(d);" +
            // body 自身 rect 已含 padding；只有子元素溢出到 padding 之外时才需要把下内边距补回来
            "var bodyBottom=d.getBoundingClientRect().bottom,kid=-Infinity;" +
            "var all=d.querySelectorAll('*');" +
            "for(var i=0;i<all.length;i++){var kr=all[i].getBoundingClientRect();" +
            "if(kr.width>0&&kr.height>0&&kr.bottom>kid)kid=kr.bottom;}" +
            "var doc=Math.max(1,Math.ceil(" +
            "Math.max(bodyBottom,kid+num(bs.paddingBottom))+num(bs.marginBottom)));" +
            "var t=document.querySelector('[data-bp-capture]');" +
            "if(t){var r=t.getBoundingClientRect();" +
            "var w=Math.ceil(document.documentElement.clientWidth||d.clientWidth);" +
            "var h=Math.ceil(r.top+r.bottom);" +
            "if(w>0&&h>0){b.onMeasured(Math.max(doc,h),0,0,w,h);return;}}" +
            "b.onMeasured(doc,0,0,0,0);" +
            "};" +
            "})();</script>"

    /** 由 Kotlin 侧在 onPageFinished 后 evaluateJavascript 调用，触发一次量高。 */
    private const val MEASURE_JS = "if(window.__bpMeasure__)window.__bpMeasure__();"






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

    internal fun buildVariableMap(d: ShareCardData): Map<String, String> = mapOf(
        "bookName" to d.bookName, "author" to d.author, "coverUrl" to d.coverUrl,
        "intro" to escapeHtml(d.intro), "kind" to d.kind, "wordCount" to d.wordCount,
        "originName" to d.originName, "totalChapterNum" to d.totalChapterNum.toString(),
        "latestChapterTitle" to d.latestChapterTitle, "typeText" to d.typeText, "charset" to d.charset,
        "readingStatusText" to d.readingStatusText, "readingProgress" to d.readingProgress,
        "readChapters" to d.readChapters, "unreadChapters" to d.unreadChapters.toString(),
        "readIteration" to d.readIteration.toString(), "readIterationText" to d.readIterationText,
        "durChapterTitle" to d.durChapterTitle, "totalReadTime" to d.totalReadTime,
        "totalReadHours" to d.totalReadHours.toString(), "totalReadMinutes" to d.totalReadMinutes.toString(),
        "readingDays" to d.readingDays.toString(), "maxDayReadTime" to d.maxDayReadTime,
        "maxDayReadDate" to d.maxDayReadDate, "totalReadWords" to d.totalReadWords,
        "remainingWords" to d.remainingWords, "firstReadTime" to d.firstReadTime,
        "lastReadTime" to d.lastReadTime, "finishReadTime" to d.finishReadTime,
        "addBookshelfTime" to d.addBookshelfTime, "lastCheckTime" to d.lastCheckTime,
        "lastReadTimeRelative" to d.lastReadTimeRelative, "rating" to d.rating.toString(),
        "ratingStars" to d.ratingStars, "ratingMax" to d.ratingMax.toString(),
        "reviewContent" to escapeHtml(d.reviewContent), "annotationCount" to d.annotationCount.toString(),
        "thoughtCount" to d.thoughtCount.toString(), "latestAnnotation" to escapeHtml(d.latestAnnotation),
        "latestAnnotationNote" to escapeHtml(d.latestAnnotationNote),
        "latestAnnotationChapter" to d.latestAnnotationChapter, "protagonists" to d.protagonists,
        "tags" to d.tags, "tagCount" to d.tagCount.toString(),
        "bookSourceName" to d.bookSourceName, "bookSourceGroup" to d.bookSourceGroup,
        "readTimeRank" to d.readTimeRank,
    )

    private fun escapeHtml(t: String): String {
        if (t.isEmpty()) return t
        val sb = StringBuilder(t.length + 8)
        for (c in t) when (c) {
            '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;"); else -> sb.append(c)
        }
        return sb.toString()
    }

    // ==================== 封面 data URI 缓存 ====================

    private val coverCache = ConcurrentHashMap<String, String>(8)

    fun clearCoverCache() = coverCache.clear()

    private fun coverUrlToDataUri(url: String): String? {
        if (url.isBlank() || url.startsWith("http") || url.startsWith("data:")) return url.ifBlank { null }
        coverCache[url]?.let { return it }

        return try {
            val f = if (url.startsWith("file://")) File(URI(url)) else File(url)
            if (f.exists()) {
                val baos = ByteArrayOutputStream()
                BitmapFactory.decodeFile(f.absolutePath)?.let {
                    it.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                    it.recycle()
                    val dataUri = "data:image/jpeg;base64," +
                        Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    coverCache[url] = dataUri
                    dataUri
                }
            } else null
        } catch (_: Exception) { null }
    }
}
