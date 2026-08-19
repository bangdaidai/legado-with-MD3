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
    // 下划线端点圆角
    @ColumnInfo(defaultValue = "0")
    var underlineRoundCap: Boolean = false,
    // 下划线羽化半径（dp），0=不羽化
    @ColumnInfo(defaultValue = "0")
    var underlineFeather: Float = 0f,
    // 下划线层级：true=文字下方（被文字笔画遮挡），false=文字上方（默认）
    @ColumnInfo(defaultValue = "0")
    var underlineBelowText: Boolean = false,
) {

    fun styleSummary(): String {
        val parts = ArrayList<String>(4)
        parts.add(targetScopeLabel())
        if (useProtagonist) {
            parts.add("跟随人物")
        }
        textColor?.let {
            parts.add("字色 ${it.toHexColor()}")
        }
        bgColor?.let {
            parts.add("背景色 ${it.toHexColor()}")
        }
        if (underlineMode != 0) {
            parts.add(
                when (underlineMode) {
                    1 -> "实线下划线"
                    2 -> "虚线下划线"
                    3 -> "波浪下划线"
                    4 -> "双下划线"
                    5 -> "自定义SVG"
                    else -> "下划线"
                } + underlineColor?.let { " ${it.toHexColor()}" }.orEmpty()
            )
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
        return sampleText.ifBlank {
            "她轻声说：“今晚就出发。”\n最近在重读《百年孤独》（纪念版），节奏依然很稳。"
        }
    }

    fun copyWithNewId(): HighlightRule {
        return copy(id = Uuid.random().toString())
    }

    companion object {
        const val TARGET_ALL = 0
        const val TARGET_TITLE = 1
        const val TARGET_BODY = 2

        fun Int.toHexColor(): String = String.format("#%08X", this)
    }
}
