package io.legado.app.domain.model.settings

import androidx.compose.runtime.Stable

data class ReadStyleState(
    val items: List<ReadStyleItem> = emptyList(),
    val selectedIndex: Int = 0,
    val shareLayout: Boolean = false,
    /**
     * 排版配置的变更序号，每次发布递增。
     *
     * 排版底座 `ReadBookConfig.Config` 是可变全局、无 flow，本状态只投影了
     * items/selectedIndex/shareLayout 三项。没有本字段时，「只改了字号/行距/下划线」这类
     * 变更不会让 data class 的相等性发生变化，`StateFlow` 会把发射当成重复值丢掉，
     * 订阅方永远收不到通知。
     */
    val revision: Long = 0L,
)

@Stable
data class ReadStyleItem(
    val id: String,
    val name: String,
    val bgType: Int,
    val bgValue: String,
    val bgTypeNight: Int,
    val bgValueNight: String,
    val bgTypeEInk: Int,
    val bgValueEInk: String,
    val textColor: Int,
    val textColorNight: Int,
    val textColorEInk: Int,
)

// 内置预设改为按**稳定 id**识别（资产 readConfig.json 里的 preset-N），
// 预设可删可勾选，不再有"前 N 份钉死"的下标边界；判定与守卫在排版配置的唯一所有者（data 层 store）里。
// `applyPreset(index)` 读的本来就是内置默认值，与列表顺序无关。
