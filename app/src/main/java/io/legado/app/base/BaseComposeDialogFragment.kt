package io.legado.app.base

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.DialogFragment
import io.legado.app.ui.theme.AppTheme
import org.koin.android.ext.android.inject
import io.legado.app.domain.gateway.AppUiConfigurationGateway

/**
 * 用于承载 Compose 内容的 DialogFragment。
 *
 * 自身窗口完全透明且不响应外部取消，可见的 UI 由内部 Compose 组件
 * （如 [io.legado.app.ui.widget.components.alert.AppAlertDialog]）自行创建窗口呈现，
 * 从而避免与宿主窗口互相干扰。子类只需实现 [Content]。
 */
abstract class BaseComposeDialogFragment : DialogFragment() {

    private val uiConfigurationGateway by inject<AppUiConfigurationGateway>()

    @Composable
    abstract fun Content()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setContent {
            AppTheme(configuration = uiConfigurationGateway.currentConfiguration) {
                Content()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isCancelable = false
        dialog?.apply {
            setCanceledOnTouchOutside(false)
            window?.let { window ->
                window.setBackgroundDrawableResource(android.R.color.transparent)
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.setDimAmount(0f)
            }
        }
    }
}
