package io.legado.app.ui.association

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.ui.main.MainActivity
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.progressIndicator.AppCircularProgressIndicator
import org.koin.androidx.compose.koinViewModel

/**
 * 网络一键导入
 * 格式: legado://import/{path}?src={url}
 *
 * 从 [VMBaseActivity] + DialogFragment 迁移为 [BaseComposeActivity] + 直接 Compose 渲染，
 * 将 Import*Screen 直接渲染在 Activity 的 Compose 层级中，不再通过 showDialogFragment 弹出。
 */
class OnLineImportActivity :
    BaseComposeActivity(transparent = true, imageBg = false) {

    private val viewModel by viewModels<OnLineImportViewModel>()

    private var errorTitle by mutableStateOf("")
    private var errorMsg by mutableStateOf("")
    private var showError by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        viewModel.successLive.observe(this) {
            when (it.first) {
                "bookSource" -> openComposeBookSourceImport(it.second)
                "theme" -> showThemeImportDialog(it.second)
                else -> {
                    currentImportType = it.first
                    currentImportUrl = it.second
                }
            }
        }
        viewModel.errorLive.observe(this) {
            errorTitle = getString(R.string.error)
            errorMsg = it
            showError = true
        }

        intent.data?.let { uri ->
            val url = uri.getQueryParameter("src")
            if (url.isNullOrEmpty()) {
                finish()
                return
            }
            when (uri.path) {
                "/bookSource" -> openComposeBookSourceImport(url)
                "/rssSource" -> {
                    currentImportType = "rssSource"
                    currentImportUrl = url
                }
                "/replaceRule" -> {
                    currentImportType = "replaceRule"
                    currentImportUrl = url
                }
                "/textTocRule" -> {
                    currentImportType = "txtRule"
                    currentImportUrl = url
                }
                "/httpTTS" -> {
                    currentImportType = "httpTts"
                    currentImportUrl = url
                }
                "/dictRule" -> {
                    currentImportType = "dictRule"
                    currentImportUrl = url
                }
                "/theme" -> showThemeImportDialog(url)
                "/readConfig" -> viewModel.readConfig(url) { title, msg ->
                    errorTitle = title
                    errorMsg = msg
                    showError = true
                }
                "/addToBookshelf" -> showAddToBookshelfDialog(url)
                "/importonline" -> when (uri.host) {
                    "booksource" -> openComposeBookSourceImport(url)
                    "rsssource" -> {
                        currentImportType = "rssSource"
                        currentImportUrl = url
                    }
                    "replace" -> {
                        currentImportType = "replaceRule"
                        currentImportUrl = url
                    }
                    else -> {
                        viewModel.determineType(url) { title, msg ->
                            errorTitle = title
                            errorMsg = msg
                            showError = true
                        }
                    }
                }
                else -> {
                    viewModel.determineType(url) { title, msg ->
                        errorTitle = title
                        errorMsg = msg
                        showError = true
                    }
                }
            }
        } ?: finish()
    }

    private var currentImportType by mutableStateOf<String?>(null)
    private var currentImportUrl by mutableStateOf<String?>(null)

    @Composable
    override fun Content() {
        val importType = currentImportType
        val importUrl = currentImportUrl

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (importType != null && importUrl != null) {
                ImportScreen(
                    type = importType,
                    url = importUrl,
                    onDismiss = { finish() },
                )
            } else {
                AppCircularProgressIndicator()
            }
        }

        val titleStr = errorTitle
        val msgStr = errorMsg
        AppAlertDialog(
            show = showError,
            onDismissRequest = { showError = false; finish() },
            title = titleStr,
            text = msgStr,
            confirmText = stringResource(R.string.ok),
            onConfirm = { showError = false; finish() },
        )
    }

    @Composable
    private fun ImportScreen(type: String, url: String, onDismiss: () -> Unit) {
        when (type) {
            "rssSource" -> {
                val viewModel = koinViewModel<ImportRssSourceViewModel>()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { viewModel.importSource(url) }
                LaunchedEffect(Unit) {
                    viewModel.effects.collect { onDismiss() }
                }
                ImportRssSourceScreen(
                    state = uiState,
                    onIntent = { intent ->
                        if (intent is ImportRssSourceIntent.Dismiss) onDismiss()
                        else viewModel.onIntent(intent)
                    },
                )
            }
            "replaceRule" -> {
                val viewModel = koinViewModel<ImportReplaceRuleViewModel>()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { viewModel.import(url) }
                LaunchedEffect(Unit) {
                    viewModel.effects.collect { onDismiss() }
                }
                ImportReplaceRuleScreen(
                    state = uiState,
                    onIntent = { intent ->
                        if (intent is ImportReplaceRuleIntent.Dismiss) onDismiss()
                        else viewModel.onIntent(intent)
                    },
                )
            }
            "httpTts" -> {
                val viewModel = koinViewModel<ImportHttpTtsViewModel>()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { viewModel.importSource(url) }
                LaunchedEffect(Unit) {
                    viewModel.effects.collect { onDismiss() }
                }
                ImportHttpTtsScreen(
                    state = uiState,
                    onIntent = { intent ->
                        if (intent is ImportHttpTtsIntent.Dismiss) onDismiss()
                        else viewModel.onIntent(intent)
                    },
                )
            }
            "dictRule" -> {
                val viewModel = koinViewModel<ImportDictRuleViewModel>()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { viewModel.importSource(url) }
                LaunchedEffect(Unit) {
                    viewModel.effects.collect { onDismiss() }
                }
                ImportDictRuleScreen(
                    state = uiState,
                    onIntent = { intent ->
                        if (intent is ImportDictRuleIntent.Dismiss) onDismiss()
                        else viewModel.onIntent(intent)
                    },
                )
            }
            "txtRule" -> {
                val viewModel = koinViewModel<ImportTxtTocRuleViewModel>()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { viewModel.importSource(url) }
                LaunchedEffect(Unit) {
                    viewModel.effects.collect { onDismiss() }
                }
                ImportTxtTocRuleScreen(
                    state = uiState,
                    onIntent = { intent ->
                        if (intent is ImportTxtTocRuleIntent.Dismiss) onDismiss()
                        else viewModel.onIntent(intent)
                    },
                )
            }
        }
    }

    private fun showThemeImportDialog(url: String) {
        ImportThemeDialog(url, true).show(supportFragmentManager, "importTheme")
    }

    private fun showAddToBookshelfDialog(url: String) {
        AddToBookshelfDialog(url, true).show(supportFragmentManager, "addToBookshelf")
    }

    private fun openComposeBookSourceImport(source: String) {
        startActivity(MainActivity.createBookSourceManageIntent(this, source))
        finish()
    }

}
