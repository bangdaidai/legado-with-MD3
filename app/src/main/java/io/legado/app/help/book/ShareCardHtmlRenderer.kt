package io.legado.app.help.book

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import io.legado.app.data.entities.ShareCardData
import io.legado.app.data.entities.ShareCardTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI

/**
 * 分享卡片渲染契约（**唯一一条**）。
 *
 * 预览和保存共用同一个实时 WebView 实例、同一份 HTML、同一个 layout viewport。
 * 保存不再走离屏 WebView 重跑一遍——直接从预览 WebView `draw()` 截图。
 * 因此预览显示什么，导出的图就是什么，逐像素一致。
 */
object ShareCardHtmlRenderer {

    private val VARIABLE_REGEX = Regex("\\{\\{(\\w+)\\}\\}")
    private val HEAD_TAG_REGEX = Regex("<head>", RegexOption.IGNORE_CASE)

    /** 实时预览用：注入的 accent 变量 style 标签 id，供 JS 动态替换内容实现秒切换。 */
    private const val ACCENT_STYLE_ID = "__bpAccentVars__"

    /**
     * 默认主色：内置粉色模板的设计主色（约 #fbc4d6）。
     * 未选色时注入这套 --bp-*，保证所有模板（含管理页预览，它根本不调用 accentApplyJs）
     * 在「默认态」也有完整配色，不依赖 CSS 的 var() 兜底。
     * 部分 WebView 对 `rgba(var(--bp-accent-rgb, 251,200,216), 0.45)` 这类「带逗号 fallback
     * 嵌套在 rgba() 里」的写法解析失败，会让整条 background 声明失效、卡片背景变透明；
     * 直接注入变量可彻底绕开它。
     */
    private const val DEFAULT_ACCENT = 0xFFFBC4D6.toInt()


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

    // ==================== 实时预览 HTML（预览 + 保存共用） ====================

    /**
     * 构建喂给「预览/保存 WebView」的 HTML：变量已替换、封面转 data URI、
     * 带一个空的 accent style 占位（id=[ACCENT_STYLE_ID]），后续换色只需 JS 改这个标签的内容。
     *
     * 保存不再单独跑一遍——直接从这个已渲染好的 WebView `draw()` 截图，保证预览 == 保存。
     */
    suspend fun buildPreviewHtml(
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
        htmlContent: String,
        variables: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val merged = buildVariableMap(ShareCardData()) + variables
        val html = VARIABLE_REGEX.replace(htmlContent) { merged[it.groupValues[1]] ?: it.value }
        injectPreviewHead(html)
    }

    /**
     * 给预览 HTML 注入空的 accent style 占位 + 横向溢出兜底；HTML 为空则返回 ""。
     *
     * 宽度 / 高度的正确性由 WebView 自身设置保证（`useWideViewPort=false`，Kotlin 用
     * `MeasureSpec.EXACTLY` 钉死宽度），不依赖 viewport meta。viewport meta 不注入——
     * 否则模板里自己的 viewport 可能覆盖它、或它落在 `<!DOCTYPE>` 之前变成无效标记，
     * 都会让 WebView 的行为变得不可控。
     *
     * 注入 `html,body{overflow-x:hidden}` 是因为活 WebView 不像 Image 那样天然裁掉越界内容，
     * 绝对定位装饰元素（`left:89%` 之类）会撑大可滚动区域导致横滑；这里统一兜住。
     */
    private fun injectPreviewHead(html: String): String {
        if (html.isBlank()) return ""
        // 默认就注入一份主色变量（DEFAULT_ACCENT），让所有模板在「未选色」时也有完整 --bp-*
        // 配色，不依赖 CSS 的 var() 兜底（部分 WebView 对 rgba(var(--x, a,b,c), d) 这类带逗号
        // fallback 的写法解析失败，会导致整条 background 声明失效、卡片背景变透明）。
        // 选色时由 accentApplyJs 用同一套变量覆盖这里。
        val defaultAccentVars = buildAccentStyle(DEFAULT_ACCENT).cssVars
        val head =
            """<style>html,body{overflow-x:hidden;}</style>""" +
                """<style id="$ACCENT_STYLE_ID">$defaultAccentVars</style>"""
        return if (HEAD_TAG_REGEX.containsMatchIn(html))
            HEAD_TAG_REGEX.replaceFirst(html, "<head>\n$head\n")
        else "$head\n$html"
    }

    /**
     * 生成一段 JS：把 [ACCENT_STYLE_ID] 这个 style 的内容换成新的变量块，并同步 `bp-dark` class。
     * 页面原地重绘，无需 reload —— 这是「换色瞬间生效」的关键。
     * @param accent null 表示「默认（模板自带）」：注入 [DEFAULT_ACCENT] 派生变量，回到初始配色，
     *               而不是清空（清空会让 var() fallback 在某些 WebView 失效、背景透明）。
     */
    fun accentApplyJs(@ColorInt accent: Int?, forceDark: Boolean? = null): String {
        // accent == null → 用默认主色派生，保证「默认态」也有完整 --bp-*；
        // 否则才套用用户选的主色 + 强制亮暗。
        val effectiveAccent = accent ?: DEFAULT_ACCENT
        val effectiveForceDark = if (accent == null) null else forceDark
        val style = buildAccentStyle(effectiveAccent, effectiveForceDark)
        val css = jsEscape(style.cssVars)
        val dark = style.isDark
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
