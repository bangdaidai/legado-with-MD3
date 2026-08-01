package io.legado.app.help.book

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.util.Base64
import android.graphics.BitmapFactory
import android.webkit.WebView
import android.webkit.WebViewClient
import io.legado.app.data.entities.BookplateData
import io.legado.app.data.entities.BookplateTemplate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI

/**
 * 藏书票 HTML→Bitmap 离屏渲染器。
 * 在主线程创建 WebView 加载 HTML，等待渲染完成后截图。
 */
object BookplateHtmlRenderer {

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
     * 渲染藏书票
     * @return Bitmap or null if failed/timeout
     */
    suspend fun render(
        ctx: Context,
        template: BookplateTemplate,
        data: BookplateData,
    ): Bitmap? {
        val w = getRenderWidth(ctx)
        val key = "${data.bookName}_${data.author}_${template.id}_${template.htmlContent.hashCode()}_$w"
        synchronized(bitmapCache) { bitmapCache[key]?.takeIf { !it.isRecycled }?.let { return it } }

        return withContext(Dispatchers.Main) {
            val coverDataUri = coverUrlToDataUri(data.coverUrl)
            val resolvedData = if (coverDataUri != null) data.copy(coverUrl = coverDataUri) else data
            val html = replaceVariables(template.htmlContent, resolvedData)
            if (html.isBlank()) return@withContext null
            val finalHtml = ensureViewportMeta(html, w)
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
        val defaultVars = buildVariableMap(BookplateData())
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
            val loaded = CompletableDeferred<Unit>()
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { loaded.complete(Unit) }
            }
            wv.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
            withTimeoutOrNull(RENDER_TIMEOUT_MS) { loaded.await() } ?: return null
            delay(200) // let layout settle
            wv.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            )
            val h = wv.measuredHeight.coerceAtLeast(100)
            wv.layout(0, 0, width, h)
            delay(100)
            Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888).also {
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

    private fun replaceVariables(html: String, data: BookplateData): String {
        val map = buildVariableMap(data)
        return VARIABLE_REGEX.replace(html) { map[it.groupValues[1]] ?: it.value }
    }

    internal fun buildVariableMap(d: BookplateData): Map<String, String> = mapOf(
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
