package io.legado.app.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.uuid.Uuid

@Entity(tableName = "highlightRules")
data class HighlightRule(
    @PrimaryKey
    var id: String = Uuid.random().toString(),
    var name: String = "",
    var pattern: String = "",
    var sampleText: String = "",
    var targetScope: Int = TARGET_ALL,
    var enabled: Boolean = true,
    var position: Int = 0,
    var textColor: Int? = null,
    var textColorNight: Int? = null,
    var bgColor: Int? = null,
    var bgColorNight: Int? = null,
    var underlineMode: Int = 0,
    var underlineColor: Int? = null,
    var underlineColorNight: Int? = null,
    var underlineWidth: Float = 1f,
    var underlineOffset: Float = 2f,
    var underlineSvgPath: String? = null,
    var bgImage: String? = null,
    var bgImageFit: Int = 0,
    var bgImageScale: Float = 1f,
    /**
     * 边框大小：只缩放九宫格四角与上下左右四条边条的厚度，不动中间的文字带。
     * 1.0=跟随整张图的等比倍率（原样）；<1 四角与边条一起变细（四角宽高同比缩、边条只缩厚度），
     * 中间文字带始终等于行盒 × 图片大小，不随它变。仅 [bgImageFit] == 3 生效。
     */
    @ColumnInfo(defaultValue = "1")
    var bgBorderScale: Float = 1f,
    var configName: String? = null,
    var fontPath: String? = null,
    var fontWeight: Int = 400,
    var isItalic: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    var fontSizeOffset: Int = 0,
    var npLeft: Float = 0.5f,
    var npRight: Float = 0.5f,
    var npTop: Float = 0.5f,
    var npBottom: Float = 0.5f,
    // 上游字段：true=手动切分（用 np* 值），false=引擎自动取中线。
    // 代码默认 false（新规则自动切，在九宫格编辑器里保存过切分线才转手动）；
    // 列默认 '1' 与上游 105 快照一致，老库升级来的既有规则维持手动、行为不变。
    // 本 fork 不暴露开关 UI。
    @ColumnInfo(defaultValue = "1")
    var manualNineSlice: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    var useProtagonist: Boolean = false,
    // 角色筛选：null=按主角标记取人；指定 "male_lead"/"female_lead"/"male_supporting"/"female_supporting"
    // 时按 role 取人（配角也算，不再要求是主角）
    var characterRole: String? = null,
    // 内边距：文字在背景图内部的边距（背景图相对文字四周向外扩多少 dp）
    @ColumnInfo(defaultValue = "0")
    var bgPaddingStart: Float = 0f,
    @ColumnInfo(defaultValue = "0")
    var bgPaddingEnd: Float = 0f,
    @ColumnInfo(defaultValue = "0")
    var bgPaddingTop: Float = 0f,
    @ColumnInfo(defaultValue = "0")
    var bgPaddingBottom: Float = 0f,
    // 外边距：背景图与相邻文字/行之间的距离（dp）
    @ColumnInfo(defaultValue = "0")
    var bgMarginStart: Float = 0f,
    @ColumnInfo(defaultValue = "0")
    var bgMarginEnd: Float = 0f,
    @ColumnInfo(defaultValue = "0")
    var bgMarginTop: Float = 0f,
    @ColumnInfo(defaultValue = "0")
    var bgMarginBottom: Float = 0f,
    // 虚线段长度（dp）
    @ColumnInfo(defaultValue = "8")
    var underlineDashLen: Float = 8f,
    // 虚线间隔长度（dp）
    @ColumnInfo(defaultValue = "5")
    var underlineDashGap: Float = 5f,
    // 波浪实际峰高（dp）。渲染时控制点取 2 倍：quad 中点只到控制点的一半
    @ColumnInfo(defaultValue = "1.5")
    var underlineWavePeak: Float = DEFAULT_WAVE_PEAK_DP,
    // 波浪波长（dp，一个完整「上-下」周期）
    @ColumnInfo(defaultValue = "24")
    var underlineWaveLength: Float = DEFAULT_WAVE_LENGTH_DP,
    // 遗留字段：端点圆角已取消，所有线型统一收 1dp 小圆角（READER_UNDERLINE_CORNER_DP），
    // 渲染层不再读这一列。数据库列不能删——改已有表结构会让覆盖安装的库在 Room
    // 打开时 identity hash 校验失败直接崩溃，故保留待上游提版本时清理。
    @ColumnInfo(defaultValue = "0")
    var underlineRoundCap: Boolean = false,
    // 下划线羽化半径（dp），0=不羽化
    @ColumnInfo(defaultValue = "0")
    var underlineFeather: Float = 0f,
    // 下划线层级：true=文字下方（被文字笔画遮挡），false=文字上方（默认）
    @ColumnInfo(defaultValue = "0")
    var underlineBelowText: Boolean = false,
    /** 命中字距（dp）：命中段与相邻字之间让出的空白，0 表示不让。只落在段首/段尾那一个字上。 */
    @ColumnInfo(defaultValue = "0")
    var letterSpacingBefore: Float = 0f,
    /** 命中字距（dp）：见 [letterSpacingBefore]。 */
    @ColumnInfo(defaultValue = "0")
    var letterSpacingAfter: Float = 0f,
    /** 命中行行距（dp）：把包含命中的那一行整行往下抬，行盒高度不变。 */
    @ColumnInfo(defaultValue = "0")
    var lineSpacingTop: Float = 0f,
    /** 命中行行距（dp）：这一行之后多留的空白，九宫格上下两条边就有多大的地方画。 */
    @ColumnInfo(defaultValue = "0")
    var lineSpacingBottom: Float = 0f,
    /**
     * 九宫格左偏移（dp）：字数不变时把中间那一格往左多拉（负值往回收）这么多，
     * 左边那两条边跟着平移。拆成左右两项是因为气泡两端要分别对齐，一个对称偏移调不动。
     */
    @ColumnInfo(defaultValue = "0")
    var bgLengthOffsetLeft: Float = 0f,
    /** 九宫格右偏移（dp）：见 [bgLengthOffsetLeft]，方向相反。 */
    @ColumnInfo(defaultValue = "0")
    var bgLengthOffsetRight: Float = 0f,
    /** 边框颜色：null = 不描边。粗细与圆角只在颜色非空时参与绘制。 */
    var borderColor: Int? = null,
    /** 边框粗细（dp）。 */
    @ColumnInfo(defaultValue = "1")
    var borderWidth: Float = 1f,
    /** 边框圆角（dp）：0 = 直角。色块填充跟随同一个圆角，色块与描边才对得上。 */
    @ColumnInfo(defaultValue = "4")
    var borderRadius: Float = 4f,
    /** 深色模式边框颜色：null = 跟随 [borderColor]（本 fork 的编辑页不设夜间边框，列保留为空即回落日间值）。 */
    var borderColorNight: Int? = null,
) {

    /**
     * 一条规则在**指定阅读模式**下真正生效的四栏颜色。
     *
     * 深色那一栏为空就跟随浅色：老规则没有夜间色，读回来的颜色与加这个能力之前逐字节一致。
     * 墨水屏走浅色那一套（白底），不要按系统深浅色误判成夜间。
     */
    fun colorsFor(isNightTheme: Boolean): HighlightRuleColors {
        if (!isNightTheme) {
            return HighlightRuleColors(
                textColor = textColor,
                bgColor = bgColor,
                borderColor = borderColor,
                underlineColor = underlineColor,
            )
        }
        return HighlightRuleColors(
            textColor = textColorNight ?: textColor,
            bgColor = bgColorNight ?: bgColor,
            borderColor = borderColorNight ?: borderColor,
            underlineColor = underlineColorNight ?: underlineColor,
        )
    }

    fun styleSummary(): String {
        val parts = ArrayList<String>(4)
        parts.add(targetScopeLabel())
        if (useProtagonist) {
            parts.add("跟随人物")
        }
        summaryColor("字色", textColor, textColorNight)?.let(parts::add)
        summaryColor("背景色", bgColor, bgColorNight)?.let(parts::add)
        summaryColor("边框", borderColor, borderColorNight)?.let(parts::add)
        if (underlineMode != 0) {
            val underlineLabel = when (underlineMode) {
                1 -> "实线下划线"
                2 -> "虚线下划线"
                3 -> "波浪下划线"
                4 -> "双实线"
                5 -> "自定义SVG"
                6 -> "删除线"
                7 -> "荧光"
                else -> "下划线"
            }
            parts.add(
                underlineLabel + summaryColor("", underlineColor, underlineColorNight)
                    ?.let { " $it" }
                    .orEmpty()
            )
            // 波浪的形状参数：只有偏离默认才写出来，避免每行都挂一串数字
            if (underlineMode == 3 &&
                (underlineWavePeak != DEFAULT_WAVE_PEAK_DP || underlineWaveLength != DEFAULT_WAVE_LENGTH_DP)
            ) {
                parts.add("峰高${trimDp(underlineWavePeak)}dp 波长${trimDp(underlineWaveLength)}dp")
            }
        }
        if (!bgImage.isNullOrBlank()) {
            parts.add(
                when (bgImageFit) {
                    1 -> "背景图(拉伸)"
                    2 -> "背景图(裁剪)"
                    3 -> "背景图(九宫格)"
                    else -> "背景图(平铺)"
                }
            )
        }
        if (letterSpacingBefore > 0f || letterSpacingAfter > 0f) {
            parts.add("命中字距 ${letterSpacingBefore.toInt()} / ${letterSpacingAfter.toInt()}dp")
        }
        if (lineSpacingTop > 0f || lineSpacingBottom > 0f) {
            parts.add("命中行行距 ${lineSpacingTop.toInt()} / ${lineSpacingBottom.toInt()}dp")
        }
        if (!fontPath.isNullOrBlank()) {
            parts.add("自定义字体")
        }
        if (fontWeight != 400) {
            parts.add(
                when {
                    fontWeight >= 700 -> "加粗"
                    fontWeight <= 300 -> "细体"
                    else -> "字重 $fontWeight"
                }
            )
        }
        if (isItalic) {
            parts.add("斜体")
        }
        if (fontSizeOffset != 0) {
            parts.add("字号${if (fontSizeOffset > 0) "+" else ""}${fontSizeOffset}")
        }
        if (parts.isEmpty()) {
            parts.add("无样式")
        }
        return parts.joinToString(" / ")
    }

    fun targetScopeLabel(): String {
        return when (targetScope) {
            TARGET_TITLE -> "作用于标题"
            TARGET_BODY -> "作用于正文"
            else -> "作用于全部"
        }
    }

    fun displayPattern(): String {
        if (useProtagonist) return "(跟随人物名称)"
        return pattern.ifBlank { ".*" }
    }

    fun normalizedSampleText(): String {
        return sampleText.ifBlank { DEFAULT_SAMPLE_TEXT }
    }

    /**
     * 预览里被上样的那一段在示例句中的 [start, endExclusive)。
     *
     * 用户把示例句改成不含 [SAMPLE_HIT_TEXT] 的内容时返回 null：这时没有「那一段」可钉，
     * 预览退回按正则命中。
     */
    fun previewHitRange(): Pair<Int, Int>? {
        val start = normalizedSampleText().indexOf(SAMPLE_HIT_TEXT)
        return if (start < 0) null else start to start + SAMPLE_HIT_TEXT.length
    }

    /**
     * 摘要里的颜色：白天色永远显示；夜间色与白天不同才补一段「夜 #...」，
     * 两者一致时不重复占位。
     */
    private fun summaryColor(label: String, day: Int?, night: Int?): String? {
        if (day == null) return null
        val nightPart = night?.takeIf { it != day }?.let { " / 夜 ${it.toHexColor()}" }.orEmpty()
        val prefix = if (label.isEmpty()) "" else "$label "
        return "$prefix${day.toHexColor()}$nightPart"
    }

    fun copyWithNewId(): HighlightRule {
        return copy(id = Uuid.random().toString())
    }

    companion object {
        const val TARGET_ALL = 0
        const val TARGET_TITLE = 1
        const val TARGET_BODY = 2

        /**
         * 波浪实际峰高（dp），默认保持迁移前的观感。
         * 渲染时二次贝塞尔的控制点取 2 倍（[ReaderUnderlineGeometry.READER_WAVE_CONTROL_OFFSET_DP]
         * = 本值 × 2），因为 quad 中点只到控制点的一半。
         */
        const val DEFAULT_WAVE_PEAK_DP = 1.5f

        /** 波浪波长（dp，一个完整「上-下」周期）。 */
        const val DEFAULT_WAVE_LENGTH_DP = 24f

        /**
         * 预览示例句：固定这一句，不再按正则换着迁就。引号外留着「张三：」和「他惊了！」，
         * 命中字距、命中行行距、背景图四周一圈都有相邻的字可以参照，调一个参数就能看出它
         * 到底只作用在中间那一段上。后面再补两小句，让示例句在预览里能折成多行：
         * 边框、命中行行距这类跨行才看得全的参数，一行字看不出它们各管哪一行。
         */
        const val DEFAULT_SAMPLE_TEXT =
            "张三：我是李四。他惊了！他从没想过会在这里碰上老同学，一时竟不知道说什么好，" +
                "旁边的王五看得直乐，这一幕他可等了很久了。"

        /**
         * [DEFAULT_SAMPLE_TEXT] 里被上样的那一段。规则编辑的每一项改动都保证落在它上面，
         * 与用户填的正则命没命中无关（见 `LegacyReaderStyleRangeMapper.rangesForLiteralRange`）。
         */
        const val SAMPLE_HIT_TEXT = "我是李四。"

        fun Int.toHexColor(): String = String.format("#%08X", this)

        /** dp 值展示用：去掉无意义的尾零（1.50 → 1.5，2.00 → 2）。 */
        private fun trimDp(value: Float): String {
            val rounded = Math.round(value * 10f) / 10f
            return if (rounded == rounded.toInt().toFloat()) {
                rounded.toInt().toString()
            } else {
                String.format("%.1f", rounded)
            }
        }
    }
}

/**
 * [HighlightRule.colorsFor] 的产物：已按阅读模式解析过的四栏颜色。
 *
 * 独立成类型是为了让「深色跟随浅色」这条回落只存在一处；渲染侧拿到的永远是最终值。
 */
data class HighlightRuleColors(
    val textColor: Int?,
    val bgColor: Int?,
    val borderColor: Int?,
    val underlineColor: Int?,
)
