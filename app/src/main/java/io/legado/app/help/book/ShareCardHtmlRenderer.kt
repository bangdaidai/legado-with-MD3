package io.legado.app.help.book

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.util.Base64
import android.graphics.BitmapFactory
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import io.legado.app.data.entities.ShareCardData
import io.legado.app.data.entities.ShareCardTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI

/**
 * 分享卡片 HTML→Bitmap 离屏渲染器。
 * 在主线程创建 WebView 加载 HTML，等待渲染完成后截图。
 */
object ShareCardHtmlRenderer {

    private const val RENDER_TIMEOUT_MS = 5000L
    private val VARIABLE_REGEX = Regex("\\{\\{(\\w+)\\}\\}")
    private val HEAD_TAG_REGEX = Regex("<head>", RegexOption.IGNORE_CASE)
    private val HTML_OPEN_TAG_REGEX = Regex("<html([^>]*)>", RegexOption.IGNORE_CASE)
    private val CLASS_ATTR_REGEX = Regex("""class\s*=\s*["']""", RegexOption.IGNORE_CASE)

    /** 实时预览用：注入的 accent 变量 style 标签 id，供 JS 动态替换内容实现秒切换。 */
    private const val ACCENT_STYLE_ID = "__bpAccentVars__"

    /**
     * 渲染分享卡片为 Bitmap —— 只用于「长按保存到相册」这一条路径。
     * 预览走 [buildPreviewHtml] 喂实时 WebView，不再出图，所以这里不做缓存：
     * 同一张卡片重复保存的收益远低于常驻多张全尺寸 bitmap 的内存代价。
     *
     * @param accentColor 用户临时选择的主题色（ARGB int）。非 null 时会向 HTML 注入
     *        一组 `--bp-*` CSS 变量，模板中用 `var(--bp-accent, 默认色)` 等即可整体换色。
     *        为 null 时不注入，模板走自身默认配色。
     * @return Bitmap or null if failed/timeout
     */
    suspend fun render(
        ctx: Context,
        template: ShareCardTemplate,
        data: ShareCardData,
        accentColor: Int? = null,
        forceDark: Boolean? = null,
    ): Bitmap? {
        val w = getRenderWidth(ctx)
        return withContext(Dispatchers.Main) {
            val coverDataUri = coverUrlToDataUri(data.coverUrl)
            val resolvedData = if (coverDataUri != null) data.copy(coverUrl = coverDataUri) else data
            val html = replaceVariables(template.htmlContent, resolvedData)
            if (html.isBlank()) return@withContext null
            val themed = if (accentColor != null) injectAccentVariables(html, accentColor, forceDark) else html
            renderHtml(ctx, ensureViewportMeta(themed, w), w)
        }
    }

    private suspend fun renderHtml(ctx: Context, html: String, width: Int): Bitmap? {
        val wv = WebView(ctx.applicationContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
                setSupportZoom(false)
                builtInZoomControls = false
                blockNetworkLoads = false
                blockNetworkImage = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                    mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            setBackgroundColor(Color.TRANSPARENT)
        }
        return try {
            // 先给 WebView "预热" 一次 layout，否则 loadDataWithBaseURL 之后测量拿不到内容高度
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            wv.layout(0, 0, width, wv.measuredHeight.coerceAtLeast(100))

            var pageFinished = false
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { pageFinished = true }
            }
            wv.loadDataWithBaseURL("about:blank", html, "text/html", "UTF-8", null)

            // 等 onPageFinished（最长 RENDER_TIMEOUT_MS）
            withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                while (!pageFinished) delay(30)
            }

            // 首次测量，读取内容高度
            delay(100)
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val contentHeight = wv.measuredHeight
            if (contentHeight <= 100) return null

            // 再等 300ms 让图片/字体加载完成
            delay(300)
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val finalHeight = wv.measuredHeight.coerceAtLeast(contentHeight)
            if (finalHeight <= 0) return null

            wv.layout(0, 0, width, finalHeight)
            delay(100)

            Bitmap.createBitmap(width, finalHeight, Bitmap.Config.ARGB_8888).also {
                val canvas = Canvas(it)
                canvas.drawColor(Color.WHITE)
                wv.draw(canvas)
            }
        } catch (_: Exception) { null }
        finally {
            try { wv.stopLoading(); wv.destroy() } catch (_: Exception) {}
        }
    }

    private fun getRenderWidth(ctx: Context): Int =
        (ctx.resources.displayMetrics.widthPixels * 0.92f).toInt().coerceAtLeast(360)

    private fun ensureViewportMeta(html: String, w: Int): String {
        val vp = """<meta name="viewport" content="width=$w, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">"""
        return if (HEAD_TAG_REGEX.containsMatchIn(html))
            HEAD_TAG_REGEX.replaceFirst(html, "<head>\n$vp\n")
        else "$vp\n$html"
    }

    /**
     * 给定主色，按 HSL 明度阈值 0.55 判定默认走暗方案(true)还是亮方案(false)。
     * 供预览 UI 确定「当前显示的是亮还是暗」，让亮/暗切换按钮能正确翻转。
     */
    fun isDarkByDefault(@ColorInt accent: Int): Boolean {
        val r = (accent shr 16) and 0xFF
        val g = (accent shr 8) and 0xFF
        val b = accent and 0xFF
        val lightness = (maxOf(r, g, b) + minOf(r, g, b)) / 2f / 255f
        return lightness < 0.55f
    }

    /**
     * 由用户选中的主色，派生出一整套 `--bp-*` CSS 变量并注入 HTML。
     *
     * 纯派生规则（方案 D）：在 **HSL** 空间用「绝对明度目标」派生，而非 HSV 相对加减。
     * 关键差别：HSV 的 V 会在接近 1.0 时被 coerceIn 封顶，导致 bg / surface / accent 一起
     * 塌陷成同一个色（主色 V≥0.80 时必然发生）；HSL 给每个变量钉死绝对 L，背景 / 卡片 /
     * 次级块三层明度恒定分离，主色无论多深多浅都不可能撞色。饱和度按变量各自缩放，
     * 色相始终跟随主色，不依赖主题系统、不硬编码黑/白。
     *
     * 变量含义（模板通过 `var(--bp-*, 兜底色)` 引用，未注入时走兜底色）：
     * - `--bp-accent`           归一化主色（亮 L∈[45%,72%]）：爱心/图标/进度条/实心装饰
     * - `--bp-accent-light`     主色极浅版（亮 92% / 暗 18%）：背景渐变端、外框
     * - `--bp-accent-rgb`       主色裸三元组 `r,g,b`：配 `rgba(var(--bp-accent-rgb), α)` 自由控透明度
     * - `--bp-star`             星级/评分色（跟随主色色相，和谐优先）
     * - `--bp-on-accent`        压在 --bp-accent 之上的文字色（按主色明度自动取深/浅）
     * - `--bp-bg`               整体背景（亮 94% / 暗 10%）——纯色，渐变交给模板自己拼
     * - `--bp-surface`          卡片本体（亮 98% / 暗 15%）
     * - `--bp-surface-rgb`      卡片色裸三元组：毛玻璃半透明卡片用
     * - `--bp-surface-variant`  卡片内次级块（亮 93% 凹陷 / 暗 20% 凸起）
     * - `--bp-text`             最深字（亮 22% / 暗 90%）：标题/数值
     * - `--bp-text-muted`       中等字（亮 42% / 暗 65%）：标签/badge
     * - `--bp-text-subtle`      最浅字（亮 58% / 暗 48%）：作者/label/底栏
     * - `--bp-text-rgb`         文字色裸三元组：投影/低透明文字用
     * - `--bp-divider`          分隔线/边框（带 alpha）
     *
     * 暗方案额外给 `<html>` 追加 `class="bp-dark"`，模板可写 `.bp-dark .xxx {}` 做亮暗分支
     * （CSS 变量无法做条件判断，语义状态色如「在读=绿/读完=蓝」需要这个机制翻色）。
     *
     * forceDark=null 时按主色明度自动判定；非 null 时强制亮(false)/暗(true)，供预览切换。
     */
    private fun injectAccentVariables(html: String, @ColorInt accent: Int, forceDark: Boolean? = null): String {
        val style = buildAccentStyle(accent, forceDark)
        val styleTag = "<style id=\"$ACCENT_STYLE_ID\">${style.cssVars}</style>"
        // 暗方案给 <html> 追加 bp-dark class（模板据此写 `.bp-dark .xxx {}` 做亮暗分支）
        val withClass = if (style.isDark) addDarkClass(html) else html
        return if (HEAD_TAG_REGEX.containsMatchIn(withClass))
            HEAD_TAG_REGEX.replaceFirst(withClass, "<head>\n$styleTag\n")
        else "$styleTag\n$withClass"
    }

    /** accent 派生结果：`cssVars` 是 `:root{...}` 变量块，`isDark` 决定要不要挂 `bp-dark` class。 */
    data class AccentStyle(val cssVars: String, val isDark: Boolean)

    /**
     * 由主色派生出 14 个 `--bp-*` 变量的 CSS 文本。离屏渲染与实时预览共用这一份计算。
     * 详见 [injectAccentVariables] 的文档。
     */
    fun buildAccentStyle(@ColorInt accent: Int, forceDark: Boolean? = null): AccentStyle {
        // HSL 解析主色
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(accent, hsl)
        val H = hsl[0]        // 0–360
        val S = hsl[1]        // 0–1
        val L = hsl[2]        // 0–1

        // 明度 < 0.55 默认暗方案
        val isDark = forceDark ?: (L < 0.55f)

        // —— 派生各变量（HSL 绝对目标） ——
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
            // —— 亮方案 ——
            accentColor = hslColor(H, S * 0.95f, L.coerceIn(0.45f, 0.72f))
            accentLight = hslColor(H, S * 0.35f, 0.92f)
            star = hslColor(H, S * 0.5f, 0.55f)
            bg = hslColor(H, S * 0.25f, 0.94f)
            surface = hslColor(H, S * 0.12f, 0.98f)
            surfaceVariant = hslColor(H, S * 0.18f, 0.93f)
            text = hslColor(H, S * 0.30f, 0.22f)
            textMuted = hslColor(H, S * 0.25f, 0.42f)
            textSubtle = hslColor(H, S * 0.20f, 0.58f)
            divider = cssHsla(H, S * 0.30f, 0.45f, 0.40f)
            // on-accent：accent L > 60% 则深字，否则浅字
            val accentL = L.coerceIn(0.45f, 0.72f)
            onAccent = if (accentL > 0.60f) hslColor(H, S * 0.30f, 0.15f) else hslColor(H, S * 0.10f, 0.96f)
        } else {
            // —— 暗方案 ——
            val darkAccentL = (L + 0.18f).coerceIn(0.55f, 0.88f)
            accentColor = hslColor(H, S * 0.90f, darkAccentL)
            accentLight = hslColor(H, S * 0.25f, 0.18f)
            star = hslColor(H, S * 0.60f, (L + 0.20f).coerceIn(0.58f, 0.88f))
            bg = hslColor(H, S * 0.20f, 0.10f)
            surface = hslColor(H, S * 0.18f, 0.15f)
            surfaceVariant = hslColor(H, S * 0.22f, 0.20f)
            text = hslColor(H, S * 0.15f, 0.90f)
            textMuted = hslColor(H, S * 0.15f, 0.65f)
            textSubtle = hslColor(H, S * 0.12f, 0.48f)
            divider = "rgba(255,255,255,0.08)"
            // on-accent：暗方案 accent 已提亮，L > 60% 则深字
            onAccent = if (darkAccentL > 0.60f) hslColor(H, S * 0.30f, 0.15f) else hslColor(H, S * 0.10f, 0.96f)
        }

        // 构建 CSS :root 变量块
        val vars = buildString {
            append(":root{")
            append("--bp-accent:").append(cssRgb(accentColor)).append(';')
            append("--bp-accent-light:").append(cssRgb(accentLight)).append(';')
            append("--bp-accent-rgb:").append(rgbTriple(accentColor)).append(';')
            // 旧模板兼容：--bp-accent-fade（主色 15% 透明淡色），新模板请用 rgba(var(--bp-accent-rgb), α)
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

    /** 给 `<html>` 开标签加上 `bp-dark` class；已有 class 属性时合并进去。 */
    private fun addDarkClass(html: String): String {
        val match = HTML_OPEN_TAG_REGEX.find(html) ?: return html
        val attrs = match.groupValues[1]
        val newTag = when {
            // 注意用 replace 而非 replaceFirst：Regex.replaceFirst 只有 (input, replacement: String)
            // 这一个重载，没有 transform lambda 版。一个开标签里最多一个 class 属性，二者等价。
            CLASS_ATTR_REGEX.containsMatchIn(attrs) ->
                "<html" + CLASS_ATTR_REGEX.replace(attrs) { "${it.value}bp-dark " } + ">"
            else -> "<html class=\"bp-dark\"$attrs>"
        }
        return html.replaceRange(match.range, newTag)
    }

    // ==================== 实时预览（避免每次换色都重跑离屏截图） ====================

    /**
     * 构建可直接喂给「实时预览 WebView」的 HTML：变量已替换、封面转 data URI、
     * 带一个空的 accent style 占位（id=[ACCENT_STYLE_ID]），后续换色只需 JS 改这个标签的内容。
     *
     * 与 [render] 的区别：不烘焙 accent、不锁定缩放（交给 WebView 的 loadWithOverviewMode
     * 缩放到控件宽度），因此**一次加载、无限次换色**，换色不再重新解析 HTML。
     */
    suspend fun buildPreviewHtml(
        ctx: Context,
        template: ShareCardTemplate,
        data: ShareCardData,
    ): String = withContext(Dispatchers.IO) {
        val coverDataUri = coverUrlToDataUri(data.coverUrl)
        val resolvedData = if (coverDataUri != null) data.copy(coverUrl = coverDataUri) else data
        injectPreviewHead(replaceVariables(template.htmlContent, resolvedData))
    }

    /**
     * 同 [buildPreviewHtml]，但吃「裸 HTML + 变量表」——供模板管理页用示例数据预览未保存的模板。
     */
    suspend fun buildCustomPreviewHtml(
        ctx: Context,
        htmlContent: String,
        variables: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val merged = buildVariableMap(ShareCardData()) + variables
        val html = VARIABLE_REGEX.replace(htmlContent) { merged[it.groupValues[1]] ?: it.value }
        injectPreviewHead(html)
    }

    /**
     * 给预览 HTML 加标准响应式 viewport + 空的 accent style 占位；HTML 为空则返回 ""。
     *
     * 注意：预览用 `width=device-width` 而非固定像素。live WebView 开了 `useWideViewPort`，
     * 会把 viewport meta 的 width **当作 CSS 像素**——如果给物理像素（如 993），layout viewport
     * 会被撑到 993 CSS px，模板里 20px padding 会变成极小的 ~7dp，卡片看上去像宽屏。
     * device-width 让 layout viewport 等于设备 dp 宽度（~360），模板按手机尺寸渲染。
     * 保存出图走 [render] 的另一条路径，用固定物理像素 viewport，不受影响。
     */
    private fun injectPreviewHead(html: String): String {
        if (html.isBlank()) return ""
        val head = """<meta name="viewport" content="width=device-width, initial-scale=1.0"><style id="$ACCENT_STYLE_ID"></style>"""
        return if (HEAD_TAG_REGEX.containsMatchIn(html))
            HEAD_TAG_REGEX.replaceFirst(html, "<head>\n$head\n")
        else "$head\n$html"
    }

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


    private fun cssRgba(@ColorInt color: Int, alpha: Float): String {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return "rgba($r,$g,$b,${alpha.coerceIn(0f, 1f)})"
    }

    // —— HSL 派生工具 ——
    // 保持主色色相不变，按【绝对明度目标】+【饱和度缩放】派生同色系变体。
    // 用 HSL 而非 HSV：HSL 的 L 单调可控（L=94% 无论什么色相都是接近白的浅色），
    // 而 HSV 的 V 在接近 1.0 时会被封顶，导致多个变量塌陷成同一个色。
    private fun hslColor(hue: Float, sat: Float, lightness: Float): Int =
        ColorUtils.HSLToColor(
            floatArrayOf(
                hue.coerceIn(0f, 360f),
                sat.coerceIn(0f, 1f),
                lightness.coerceIn(0f, 1f),
            )
        )

    /** 实心色输出为 `rgb(r,g,b)`。 */
    private fun cssRgb(@ColorInt color: Int): String =
        "rgb(${(color shr 16) and 0xFF},${(color shr 8) and 0xFF},${color and 0xFF})"

    /** 裸三元组 `r,g,b`，供模板写 `rgba(var(--bp-xxx-rgb), 0.06)` 自由控透明度。 */
    private fun rgbTriple(@ColorInt color: Int): String =
        "${(color shr 16) and 0xFF},${(color shr 8) and 0xFF},${color and 0xFF}"

    /** HSL 直接输出带 alpha 的 CSS 色。 */
    private fun cssHsla(hue: Float, sat: Float, lightness: Float, alpha: Float): String =
        cssRgba(hslColor(hue, sat, lightness), alpha)


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

    private fun coverUrlToDataUri(url: String): String? {
        if (url.isBlank() || url.startsWith("http") || url.startsWith("data:")) return url.ifBlank { null }
        return try {
            val f = if (url.startsWith("file://")) File(URI(url)) else File(url)
            if (f.exists()) {
                val baos = ByteArrayOutputStream()
                BitmapFactory.decodeFile(f.absolutePath)?.let {
                    it.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                    it.recycle()
                    "data:image/jpeg;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                }
            } else null
        } catch (_: Exception) { null }
    }
}
