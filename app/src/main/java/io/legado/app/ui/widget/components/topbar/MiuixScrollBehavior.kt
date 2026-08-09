package io.legado.app.ui.widget.components.topbar

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import top.yukonga.miuix.kmp.basic.ScrollBehavior


interface GlassTopAppBarScrollBehavior {
    // 滚动连接器
    val nestedScrollConnection: NestedScrollConnection

    // 折叠进度：0f 表示完全展开，1f 表示完全折叠。
    val collapsedFraction: Float

    // 复位到完全展开态。内容区被整体替换（如首页切换混合/分页模式）时调用，
    // 否则残留的折叠偏移会让标题停在半透明的折叠过渡态。
    fun reset() {}
}

@OptIn(ExperimentalMaterial3Api::class)
class M3GlassScrollBehavior(
    val m3Behavior: TopAppBarScrollBehavior
) : GlassTopAppBarScrollBehavior {
    override val nestedScrollConnection: NestedScrollConnection
        get() = m3Behavior.nestedScrollConnection

    override val collapsedFraction: Float
        get() = m3Behavior.state.collapsedFraction

    override fun reset() {
        m3Behavior.state.heightOffset = 0f
        m3Behavior.state.contentOffset = 0f
    }
}

class MiuixGlassScrollBehavior(
    val miuixBehavior: ScrollBehavior
) : GlassTopAppBarScrollBehavior {
    override val nestedScrollConnection: NestedScrollConnection
        get() = miuixBehavior.nestedScrollConnection

    override val collapsedFraction: Float
        get() = miuixBehavior.state.collapsedFraction
}
