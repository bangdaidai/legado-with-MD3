package io.legado.app.ui.browser

import android.app.Application
import android.content.Intent
import android.util.Base64
import android.webkit.URLUtil
import android.webkit.WebView
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.constant.SourceType
import io.legado.app.data.repository.BookSourceRepository
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.source.SourceHelp
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.help.webView.WebJsExtensions.Companion.JS_INJECTION2
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.ACache
import io.legado.app.utils.ImageSaveUtils
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.toastOnUi
import org.apache.commons.text.StringEscapeUtils

class WebViewModel(
    application: Application,
    private val bookSourceRepository: BookSourceRepository,
) : BaseViewModel(application) {
    var intent: Intent? = null
    var baseUrl: String = ""
    var html: String? = null
    val headerMap: HashMap<String, String> = hashMapOf()
    var sourceVerificationEnable: Boolean = false
    var refetchAfterSuccess: Boolean = true
    var sourceName: String = ""
    var sourceOrigin: String = ""
    var sourceType = SourceType.book

    /** 本地 html（书源通过 startBrowser 传进来的页面），只有这种页面注入 java/cache 桥 */
    var localHtml: Boolean = false
    var source: BaseSource? = null

    fun initData(
        intent: Intent,
        success: () -> Unit
    ) {
        execute {
            this@WebViewModel.intent = intent
            val url = intent.getStringExtra("url")
                ?: throw NoStackTraceException("url不能为空")
            sourceName = intent.getStringExtra("sourceName") ?: ""
            sourceOrigin = intent.getStringExtra("sourceOrigin") ?: ""
            sourceType = intent.getIntExtra("sourceType", SourceType.book)
            sourceVerificationEnable = intent.getBooleanExtra("sourceVerificationEnable", false)
            refetchAfterSuccess = intent.getBooleanExtra("refetchAfterSuccess", true)
            html = intent.getStringExtra("html")?.let { injectJsBridge(it) }
            source = SourceHelp.getSource(sourceOrigin, sourceType)
            val analyzeUrl = AnalyzeUrl(url, source = source, coroutineContext = coroutineContext)
            baseUrl = analyzeUrl.url
            headerMap.putAll(analyzeUrl.headerMap)
            if (html.isNullOrEmpty() && analyzeUrl.isPost()) {
                html = analyzeUrl.getStrResponseAwait(useWebView = false).body
            }
        }.onSuccess {
            success.invoke()
        }.onError {
            context.toastOnUi("error\n${it.localizedMessage}")
            it.printOnDebug()
        }
    }

    /**
     * 与 R 项目一致：本地 html 头部插入 [JS_INJECTION2]，页面里才有 java / cache 对象。
     * 书源的授权页靠 cache.put 回写授权信息，少了这一步授权就存不下来。
     */
    private fun injectJsBridge(html: String): String {
        localHtml = true
        val script = "<script>$JS_INJECTION2</script>"
        val headIndex = html.indexOf("<head", ignoreCase = true)
        if (headIndex < 0) return "<head>$script</head>$html"
        val closingHeadIndex = html.indexOf('>', startIndex = headIndex)
        if (closingHeadIndex < 0) return "<head>$script</head>$html"
        return StringBuilder(html).insert(closingHeadIndex + 1, script).toString()
    }

    fun saveImage(webPic: String?) {
        webPic ?: return
        execute {
            val byteArray = webData2bitmap(webPic) ?: throw Throwable("NULL")

            val success = ImageSaveUtils.saveImageToGallery(
                context,
                byteArray,
                folderName = "Legado"
            )

            if (!success) throw Throwable("保存到相册失败")
        }.onError {
            ACache.get().remove(imagePathKey)
            context.toastOnUi("保存图片失败: ${it.localizedMessage}")
        }.onSuccess {
            context.toastOnUi("已保存到相册")
        }
    }

    private suspend fun webData2bitmap(data: String): ByteArray? {
        return if (URLUtil.isValidUrl(data)) {
            okHttpClient.newCallResponseBody {
                url(data)
            }.bytes()
        } else {
            Base64.decode(data.split(",").toTypedArray()[1], Base64.DEFAULT)
        }
    }

    fun saveVerificationResult(webView: WebView, success: () -> Unit) {
        if (!sourceVerificationEnable) {
            return success.invoke()
        }
        if (refetchAfterSuccess) {
            execute {
                val url = intent!!.getStringExtra("url")!!
                val source = bookSourceRepository.getBookSource(sourceOrigin)
                if (html == null) {
                    html = AnalyzeUrl(
                        url,
                        headerMapF = headerMap,
                        source = source,
                        coroutineContext = coroutineContext
                    ).getStrResponseAwait(useWebView = false).body
                }
                SourceVerificationHelp.setResult(sourceOrigin, html ?: "", baseUrl)
            }.onSuccess {
                success.invoke()
            }
        } else {
            webView.evaluateJavascript("document.documentElement.outerHTML") {
                val pageUrl = webView.url ?: ""
                execute {
                    html = StringEscapeUtils.unescapeJson(it).trim('"')
                    SourceVerificationHelp.setResult(sourceOrigin, html ?: "", pageUrl)
                }.onSuccess {
                    success.invoke()
                }
            }
        }
    }

    fun disableSource(block: () -> Unit) {
        execute {
            SourceHelp.enableSource(sourceOrigin, sourceType, false)
        }.onSuccess {
            block.invoke()
        }
    }

    fun deleteSource(block: () -> Unit) {
        execute {
            SourceHelp.deleteSource(sourceOrigin, sourceType)
        }.onSuccess {
            block.invoke()
        }
    }

}
