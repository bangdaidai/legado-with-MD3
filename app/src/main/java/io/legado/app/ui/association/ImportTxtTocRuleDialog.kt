package io.legado.app.ui.association

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.domain.gateway.AppUiConfigurationGateway
import io.legado.app.ui.theme.AppTheme
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class ImportTxtTocRuleDialog() : DialogFragment() {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    private val viewModel by viewModel<ImportTxtTocRuleViewModel>()
    private val appUiConfigurationGateway by inject<AppUiConfigurationGateway>()

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (arguments?.getBoolean("finishOnDismiss") == true) {
            activity?.finish()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val uiConfiguration by appUiConfigurationGateway.configuration
                    .collectAsStateWithLifecycle(appUiConfigurationGateway.currentConfiguration)
                AppTheme(configuration = uiConfiguration) {
                    val state by viewModel.uiState.collectAsState()
                    ImportTxtTocRuleScreen(
                        state = state,
                        onIntent = viewModel::onIntent
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val source = arguments?.getString("source")
        if (source.isNullOrEmpty()) {
            dismiss()
            return
        }
        viewModel.importSource(source)
    }
}
