package io.legado.app.ui.association

import android.content.DialogInterface
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.base.BaseComposeDialogFragment
import io.legado.app.data.entities.HttpTTS
import io.legado.app.ui.widget.components.importComponents.ImportAssociationContent
import io.legado.app.ui.widget.components.importComponents.ImportStatus
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * 导入 HTTP TTS（Compose 版，沿用 BatchImportDialog 规范）。
 */
class ImportHttpTtsDialog() : BaseComposeDialogFragment() {

    constructor(source: String, finishOnDismiss: Boolean = false) : this() {
        arguments = Bundle().apply {
            putString("source", source)
            putBoolean("finishOnDismiss", finishOnDismiss)
        }
    }

    private val viewModel by viewModel<ImportHttpTtsViewModel>()
    private var finishOnDismiss = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finishOnDismiss = arguments?.getBoolean("finishOnDismiss") == true
        val source = arguments?.getString("source")
        if (source.isNullOrEmpty()) {
            dismissAllowingStateLoss()
        } else {
            viewModel.importSource(source)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (finishOnDismiss) {
            activity?.finish()
        }
    }

    @Composable
    override fun Content() {
        ImportAssociationContent(
            title = stringResource(R.string.import_tts),
            items = viewModel.allSources,
            existing = viewModel.checkSources,
            selected = viewModel.selectStatus,
            errorLiveData = viewModel.errorLiveData,
            successLiveData = viewModel.successLiveData,
            onImportSelect = { finally -> viewModel.importSelect(finally) },
            statusOf = { existing, incoming ->
                when {
                    existing == null -> ImportStatus.New
                    incoming.lastUpdateTime > existing.lastUpdateTime -> ImportStatus.Update
                    else -> ImportStatus.Existing
                }
            },
            itemTitle = { it.name },
            itemSubtitle = { it.url },
            onUpdateItem = { index, data ->
                (data as? HttpTTS)?.let { viewModel.allSources[index] = it }
            },
            onDismissRequest = { dismissAllowingStateLoss() }
        )
    }
}
