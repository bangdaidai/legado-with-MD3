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
import androidx.compose.ui.graphics.Color as ComposeColor
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import io.legado.app.ui.theme.ThemeColorSpec
import io.legado.app.ui.theme.ThemeResolver

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
    ): Bitmap? {
        val w = getRenderWidth(ctx)
        val key = "${data.bookName}_${data.author}_${template.id}_${template.htmlContent.hashCode()}_${accentColor ?: 0}_$w"
        synchronized(bitmapCache) { bitmapCache[key]?.takeIf { !it.isRecycled }?.let { return it } }

        return withContext(Dispatchers.Main) {
            val coverDataUri = coverUrlToDataUri(data.coverUrl)
            val resolvedData = if (coverDataUri != null) data.copy(coverUrl = coverDataUri) else data
            val html = replaceVariables(template.htmlContent, resolvedData)
            if (html.isBlank()) return@withContext null
            val themed = if (accentColor != null) injectAccentVariables(html, accentColor) else html
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
     * 由用户选中的主题色，派生出一整套 `--bp-*` CSS 变量并注入 HTML。
     *
     * 关键修复：背景直接使用用户所选颜色（选什么色就是什么色），并依据 accent 的感知
     * 亮度自动在「浅色方案 / 深色方案」间切换，而不是把所有模板强制压成深色：
     * - accent 亮度高（浅色） → 浅色背景 + 深色文字
     * - accent 亮度低（深色） → 深色背景 + 浅色文字
     * 这样无论选浅色还是深色，卡片都保持可读，且模板原有的深浅设计不会被破坏。
     *
     * 变量含义（模板通过 `var(--bp-*, 默认)` 引用，未注入时走默认色）：
     * - `--bp-accent`        主题色（原样）
     * - `--bp-accent-light`  提亮版（用于渐变另一端、浅色强调）
     * - `--bp-accent-fade`   主题色半透明（用于浅色块背景、分隔线）
     * - `--bp-bg`            用户所选颜色本身（纯色，渐变感由模板默认值决定）
     * - `--bp-surface`       卡片表面（浅色方案偏白、深色方案比背景略亮）
     * - `--bp-text`          正文色（随方案切换深/浅）
     * - `--bp-text-muted`    副文色
     * - `--bp-divider`       分隔线
     */
    private fun injectAccentVariables(html: String, @ColorInt accent: Int): String {
        // 用户所选颜色 = 卡片背景（纯色），同时作为主题系统的「种子色（seed）」。
        // 背景直接用用户色本身（选什么色背景就是什么色，不再被 M3 固定 tone 抹平）；
        // 文字/表面/分隔线这一套才交给 dynamicColorScheme 生成协调配色，
        // isDark 依据用户色感知亮度，保证浅色背景配深文字、深色背景配浅文字。
        val isDark = ColorUtils.calculateLuminance(accent) <= 0.5
        val scheme = dynamicColorScheme(
            seedColor = ComposeColor(accent),
            isDark = isDark,
            isAmoled = false,
            style = PaletteStyle.Fidelity,
            contrastLevel = ThemeResolver.resolveContrastLevel(),
            specVersion = ThemeResolver.resolveColorSpecVersion(ThemeColorSpec.SPEC_2021),
        )
        val white = 0xFFFFFFFF.toInt()
        // 强调色取自主题系统生成的 scheme.primary：与背景（用户纯色）拉开 tone，避免同色融合；
        // accent-light/fade 在 primary 上做提亮与淡化
        val primary = scheme.primary.value.toInt()
        val accentLight = ColorUtils.blendARGB(primary, white, 0.3f)
        val accentFade = cssRgba(primary, 0.18f)
        // 背景：用户所选纯色（选什么色背景就是什么色，不被 M3 固定 tone 抹平）
        val bg = cssRgba(accent, 1f)
        // 文字/表面/分隔线：取自主题系统生成的协调配色（随种子色变化，对比由 scheme 保证）
        val surface = cssRgba(scheme.surface.value.toInt(), 1f)
        val text = cssRgba(scheme.onBackground.value.toInt(), 1f)
        val textMuted = cssRgba(scheme.onSurfaceVariant.value.toInt(), 1f)
        val divider = cssRgba(scheme.outline.value.toInt(), 1f)
        val vars = buildString {
            append(":root{")
            append("--bp-accent:").append(cssRgba(primary, 1f)).append(';')
            append("--bp-accent-light:").append(cssRgba(accentLight, 1f)).append(';')
            append("--bp-accent-fade:").append(accentFade).append(';')
            append("--bp-bg:").append(bg).append(';')
            append("--bp-surface:").append(surface).append(';')
            append("--bp-text:").append(text).append(';')
            append("--bp-text-muted:").append(textMuted).append(';')
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
