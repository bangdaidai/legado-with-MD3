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
    private const val MAX_CACHE_SIZE = 8
    private val VARIABLE_REGEX = Regex("\\{\\{(\\w+)\\}\\}")
    private val HEAD_TAG_REGEX = Regex("<head>", RegexOption.IGNORE_CASE)

    private val bitmapCache = object : LinkedHashMap<String, Bitmap>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            val shouldRemove = size > MAX_CACHE_SIZE
            if (shouldRemove && eldest != null) eldest.value.recycle()
            return shouldRemove
        }
    }

    fun clearCache() {
        synchronized(bitmapCache) { bitmapCache.values.forEach { it.recycle() }; bitmapCache.clear() }
    }

    /**
     * 渲染分享卡片
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
        val key = "${data.bookName}_${data.author}_${template.id}_${template.htmlContent.hashCode()}_${accentColor ?: 0}_${forceDark ?: "auto"}_$w"
        synchronized(bitmapCache) { bitmapCache[key]?.takeIf { !it.isRecycled }?.let { return it } }

        return withContext(Dispatchers.Main) {
            val coverDataUri = coverUrlToDataUri(data.coverUrl)
            val resolvedData = if (coverDataUri != null) data.copy(coverUrl = coverDataUri) else data
            val html = replaceVariables(template.htmlContent, resolvedData)
            if (html.isBlank()) return@withContext null
            val themed = if (accentColor != null) injectAccentVariables(html, accentColor, forceDark) else html
            val finalHtml = ensureViewportMeta(themed, w)
            renderHtml(ctx, finalHtml, w)?.also {
                synchronized(bitmapCache) { bitmapCache[key] = it }
            }
        }
    }

    /**
     * 自定义 HTML 渲染（供模板预览）
     */
    suspend fun renderCustom(ctx: Context, htmlContent: String, variables: Map<String, String> = emptyMap()): Bitmap? {
        val w = getRenderWidth(ctx)
        val defaultVars = buildVariableMap(ShareCardData())
        val merged = defaultVars + variables
        val html = VARIABLE_REGEX.replace(htmlContent) { merged[it.groupValues[1]] ?: it.value }
        if (html.isBlank()) return null
        return withContext(Dispatchers.Main) { renderHtml(ctx, ensureViewportMeta(html, w), w) }
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
     * 由用户选中的主色，派生出一整套 `--bp-*` CSS 变量并注入 HTML。
     *
     * 纯派生规则（方案 C）：8 个变量全部从主色衍生，不依赖主题系统、不硬编码黑/白。
     * 同色系 + 卡片表面始终被「提亮链」拉到近白 → 选浅色不发黑、文字永远压暗保证对比、
     * 主色只做点缀不大面积铺色 → 怎么选都和谐可读。
     *
     * 变量含义（模板通过 `var(--bp-*, 默认)` 引用，未注入时走默认色）：
     * - `--bp-accent`        主色（用户选色原样）：爱心/星/分割线/进度条核心色
     * - `--bp-accent-light`  主色提亮+降饱和：背景渐变主力、卡片内装饰
     * - `--bp-accent-fade`   主色半透明：阴影/光晕/半透明层
     * - `--bp-bg`            同色系渐变（accent-light 的明暗四段）：整体氛围
     * - `--bp-surface`       accent-light 再提亮+高透明：卡片本体（毛玻璃）
     * - `--bp-text`          主色降饱和+压暗：标题/数值（必须可读）
     * - `--bp-text-muted`    主文字再提亮：作者/标签/时间
     * - `--bp-divider`       主色低透明：虚线边框/内分割
     */
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

    private fun injectAccentVariables(html: String, @ColorInt accent: Int, forceDark: Boolean? = null): String {
        // 8 个变量全部由主色衍生，按【所选色明暗】分两套方案：
        // - 亮方案（选浅色）：浅表面 + 深字，适合浅色模板（默认）
        // - 暗方案（选深色）：深表面（近固定深灰带主色相）+ 浅字，保持暗色氛围且可读
        // 两者都只用纯派生、不依赖主题系统，主色只做点缀不大面积铺色。
        // 判断「所选色本身是亮是暗」用 HSL 明度 L=(max+min)/2（0–1），比线性加权更贴合人眼。
        // 阈值取中点附近 0.55：<0.55 走暗方案，否则亮方案。与模板无关，模板只认 8 个变量。
        // forceDark=null 时自动判定；非 null 时强制亮(false)/暗(true)方案，便于预览另一种效果。
        val r = (accent shr 16) and 0xFF
        val g = (accent shr 8) and 0xFF
        val b = accent and 0xFF
        val lightness = (maxOf(r, g, b) + minOf(r, g, b)) / 2f / 255f
        val isDark = forceDark ?: (lightness < 0.55f)
        val accentColor: Int
        val accentLight: Int
        val accentFade: String
        val bg: String
        val surface: String
        val text: Int
        val textMuted: Int
        val divider: String
        if (!isDark) {
            // —— 亮方案 ——
            accentColor = accent
            accentLight = adjust(accent, +0.20f, 0.85f)
            accentFade = cssRgba(accent, 0.15f)
            bg = buildString {
                append("linear-gradient(145deg,")
                append(cssRgba(adjust(accentLight, +0.04f), 1f)).append(',')
                append(cssRgba(accentLight, 1f)).append(',')
                append(cssRgba(adjust(accentLight, -0.04f), 1f)).append(',')
                append(cssRgba(adjust(accentLight, -0.08f), 1f))
                append(')')
            }
            surface = cssRgba(adjust(accentLight, +0.10f), 0.88f)
            val textBase = adjust(adjust(accent, satScale = 0.60f), -0.30f)
            text = if (ColorUtils.calculateLuminance(textBase) > 0.30f) {
                adjust(textBase, -0.32f)
            } else {
                textBase
            }
            textMuted = adjust(text, +0.25f)
            divider = cssRgba(accent, 0.25f)
        } else {
            // —— 暗方案 ——（深表面 + 浅字，保持暗色氛围）
            accentColor = tone(accent, 0.78f, 0.70f)        // 主色提亮发光（暗底上可见）
            accentLight = tone(accent, 0.23f, 0.30f)        // 主色压暗成深薄荷灰（装饰浅块）
            accentFade = cssRgba(accentColor, 0.12f)        // 发光色低透明（阴影/光晕在暗底可见）
            bg = cssRgba(tone(accent, 0.086f, 0.25f), 1f)   // 深灰带主色相（整体氛围）
            surface = cssRgba(tone(accent, 0.13f, 0.30f), 0.92f) // 深灰半透明（毛玻璃）
            text = tone(accent, 0.90f, 0.12f)               // 灰白（必须可读）
            textMuted = tone(accent, 0.68f, 0.25f)          // 中灰（弱化信息）
            divider = "rgba(255,255,255,0.08)"              // 白透明（关键：暗底上可见，accent 派生会消失）
        }
        val vars = buildString {
            append(":root{")
            append("--bp-accent:").append(cssRgba(accentColor, 1f)).append(';')
            append("--bp-accent-light:").append(cssRgba(accentLight, 1f)).append(';')
            append("--bp-accent-fade:").append(accentFade).append(';')
            append("--bp-bg:").append(bg).append(';')
            append("--bp-surface:").append(surface).append(';')
            append("--bp-text:").append(cssRgba(text, 1f)).append(';')
            append("--bp-text-muted:").append(cssRgba(textMuted, 1f)).append(';')
            append("--bp-divider:").append(divider).append(';')
            append('}')
        }
        val styleTag = "<style>$vars</style>"
        return if (HEAD_TAG_REGEX.containsMatchIn(html))
            HEAD_TAG_REGEX.replaceFirst(html, "<head>\n$styleTag\n")
        else "$styleTag\n$html"
    }

    private fun cssRgba(@ColorInt color: Int, alpha: Float): String {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return "rgba($r,$g,$b,${alpha.coerceIn(0f, 1f)})"
    }

    // HSV 工具：从主色纯派生同色系变体。保持色相不变，只调明度与饱和度。
    // valueDelta 正=提亮 / 负=压暗（绝对值加减），satScale<1=降饱和。
    private fun adjust(@ColorInt color: Int, valueDelta: Float = 0f, satScale: Float = 1f): Int {
        val hsv = FloatArray(3)
        Color.RGBToHSV((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF, hsv)
        hsv[2] = (hsv[2] + valueDelta).coerceIn(0f, 1f)
        hsv[1] = (hsv[1] * satScale).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    // 保留主色色相，绝对设定明度(value)与饱和度：用于暗方案里「近固定深灰/灰白带主色相」的变量。
    private fun tone(@ColorInt color: Int, value: Float, satScale: Float): Int {
        val hsv = FloatArray(3)
        Color.RGBToHSV((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF, hsv)
        hsv[2] = value.coerceIn(0f, 1f)
        hsv[1] = (hsv[1] * satScale).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
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
