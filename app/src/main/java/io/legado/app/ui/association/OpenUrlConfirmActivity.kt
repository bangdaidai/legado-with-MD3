package io.legado.app.ui.association

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.base.BaseComposeActivity
import io.legado.app.constant.AppLog
import io.legado.app.constant.SourceType
import io.legado.app.utils.toastOnUi
import org.koin.androidx.viewmodel.ext.android.viewModel

class OpenUrlConfirmActivity : BaseComposeActivity(transparent = true, imageBg = false) {

    private val viewModel by viewModel<OpenUrlConfirmViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.getStringExtra("uri")
        if (uri.isNullOrBlank()) {
            finish()
            return
        }
        viewModel.onIntent(
            OpenUrlConfirmIntent.Init(
                uri = uri,
                mimeType = intent.getStringExtra("mimeType"),
                sourceOrigin = intent.getStringExtra("sourceOrigin") ?: "",
                sourceName = intent.getStringExtra("sourceName") ?: "",
                sourceType = intent.getIntExtra("sourceType", SourceType.book)
            )
        )
    }

    @Composable
    override fun Content() {
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        OpenUrlConfirmScreen(
            state = state,
            onIntent = viewModel::onIntent,
            effects = viewModel.effects,
            onOpenUrl = { uri, mimeType -> openUrl(uri, mimeType) },
            onFinish = { finish() }
        )
    }

    private fun openUrl(uri: String, mimeType: String?) {
        try {
            val targetUri = uri.toUri()
            // 创建目标 Intent 并设置类型
            val targetIntent = Intent(Intent.ACTION_VIEW).apply {
                // 同时设置 Data 和 Type
                if (!mimeType.isNullOrBlank()) {
                    setDataAndType(targetUri, mimeType)
                } else {
                    data = targetUri
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // 验证是否有应用可以处理
            if (targetIntent.resolveActivity(packageManager) != null) {
                startActivity(targetIntent)
            } else {
                toastOnUi(R.string.can_not_open)
            }
        } catch (e: Exception) {
            AppLog.put("打开链接失败", e, true)
        }
        finish()
    }

}
