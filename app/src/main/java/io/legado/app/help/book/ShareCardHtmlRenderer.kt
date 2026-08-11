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
        // 关键：卡片表面（--bp-surface）直接等于用户所选纯色，不再用 scheme.surface——
        // 因为 scheme.surface 对同一颗种子只可能是「近白或近黑」两块面板，不是用户选的色，
        // 用它会让卡片和你选的色脱节。所以整张卡片（背景+面板）都是你选的那个色，选啥就是啥。
        // dynamicColorScheme 对同一种子永远生成「浅色系 + 深色系」两套，必须传 isDark 选其一。
        // 这里 isDark 的唯一依据就是【你选的底色明暗】：底色亮→浅色系（深字），底色暗→深色系（浅字），
        // 正好让文字/强调与你选的底色协调对比——这就是「适配背景色」，文字全是 scheme 的 on 协调色，非硬编码黑/白。
        val isDark = ColorUtils.calculateLuminance(accent) < 0.5
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
        // 背景 + 卡片面板：都是用户所选纯色（选什么色卡片就是什么色，不被 M3 固定 tone 抹平）
        val bg = cssRgba(accent, 1f)
        // 表面（卡片面板）：从种子色派生的同色系「容器色」——保持色相、降饱和、明度相对底色偏移，
        // 与背景同色系但可区分（亮底压暗、暗底提亮），随选色变化，不用 scheme.surface 的中性灰面板。
        val surface = cssRgba(deriveSurfaceColor(accent, isDark), 1f)
        // 关键修复：文字必须与「表面」明度相反，否则表面亮→文字也亮、表面暗→文字也暗，对比崩溃看不清。
        // 旧逻辑让 text 跟随 isDark 与 surface 同向变化，是切换颜色后文字糊掉的根因。
        // 这里以「表面实际明度」为准反向取字色：表面暗→用亮字，表面亮→用暗字。
        val surfaceLum = ColorUtils.calculateLuminance(surface)
        val textOnDarkSurface = surfaceLum < 0.5f
        // 文字/副文/分隔线：从表面同色相派生，保证随选色变化且始终与表面对比（非硬编码黑/白）。
        // 次要文字降对比、分隔线更低对比。
        val text = cssRgba(deriveOnColor(surface, textOnDarkSurface, if (textOnDarkSurface) 0.90f else 0.16f, 0.85f), 1f)
        val textMuted = cssRgba(deriveOnColor(surface, textOnDarkSurface, if (textOnDarkSurface) 0.72f else 0.34f, 0.60f), 1f)
        val divider = cssRgba(deriveOnColor(surface, textOnDarkSurface, if (textOnDarkSurface) 0.66f else 0.42f, 0.50f), 0.5f)
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

    // 从底色同色相派生「压在上面的文字/分隔线色」：保持色相与饱和度，只调明度与饱和，
    // 让文字真正随用户所选颜色变化（而非主题系统的中性灰黑）。
    // 亮底(isDark=false)→压暗成深字，暗底(isDark=true)→提亮成浅字。
    private fun deriveOnColor(@ColorInt base: Int, isDark: Boolean, targetValue: Float, satScale: Float): Int {
        val hsv = FloatArray(3)
        Color.RGBToHSV((base shr 16) and 0xFF, (base shr 8) and 0xFF, base and 0xFF, hsv)
        hsv[2] = targetValue
        hsv[1] = (hsv[1] * satScale).coerceIn(0f, 1f)
        return Color.HSVToColor(hsv)
    }

    // 从种子色派生「卡片面板容器色」：保持色相，降低饱和，明度相对底色偏移形成层次
    // （亮底压暗一档、暗底提亮一档），让卡片与背景同色系但可区分，且随选色变化（非中性灰面板）。
    private fun deriveSurfaceColor(@ColorInt base: Int, isDark: Boolean): Int {
        val hsv = FloatArray(3)
        Color.RGBToHSV((base shr 16) and 0xFF, (base shr 8) and 0xFF, base and 0xFF, hsv)
        hsv[1] = (hsv[1] * 0.7f).coerceIn(0f, 1f)
        val delta = if (isDark) 0.10f else -0.10f
        hsv[2] = (hsv[2] + delta).coerceIn(0.10f, 0.95f)
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
