package io.legado.app.ui.browser

import android.app.Application
import android.content.Intent
import android.util.Base64
import android.webkit.URLUtil
import android.webkit.WebView
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.AppConst.imagePathKey
import io.legado.app.constant.AppLog
import io.legado.app.constant.SourceType
import io.legado.app.data.repository.BookSourceRepository
import io.legado.app.data.entities.BaseSource
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.http.CookieStore
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
import android.webkit.CookieManager as AndroidCookieManager

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
        diagCookieState(webView)
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
                diagRefetchBody(html)
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

    /**
     * 【临时诊断，定位完请整段删除】
     * 起点这类站点的人机验证只取决于 WebView 跑完挑战脚本后写入的那一枚 Cookie（实测是 w_tsfp，
     * 单它一枚就够，UA 不参与校验）。点了 √ 仍然搜不到书时，要区分两种病：
     * 「根本没领到 Cookie」还是「领到了但没带上后续请求」。这里把链路三层一次性写进 AppLog 一条：
     * 页内＝WebView 自己的 Cookie 罐；库·源键＝按书源地址取；库·页键＝按页面地址取
     * （两者不一致＝域名键错位，写进去的键和读出来的键不是同一个）。
     * 查看入口：书源编辑页右上角溢出菜单 → 日志。
     */
    private fun diagCookieState(webView: WebView) {
        val pageUrl = webView.url ?: baseUrl
        val inPage = runCatching { AndroidCookieManager.getInstance().getCookie(pageUrl) }.getOrNull()
        val bySource = runCatching { CookieStore.getCookie(sourceOrigin) }
            .getOrElse { "读取失败:" + it.message.toString() }
        val byPage = runCatching { CookieStore.getCookie(baseUrl) }
            .getOrElse { "读取失败:" + it.message.toString() }
        // 一条日志装下判定行 + 三层原文，去「书源编辑 → 右上角溢出菜单 → 日志」整段可复制
        AppLog.put(
            "验证诊断·点√时 页内" + diagMark(inPage) + " 库按源键" + diagMark(bySource) +
                    " 库按页键" + diagMark(byPage) +
                    (if (bySource != byPage) " 【两键取到不同结果＝域名键错位】" else "") +
                    "\npageUrl=" + pageUrl +
                    "\n页内原文=" + (inPage ?: "(null)") +
                    "\n库·源键(" + sourceOrigin + ")=" + bySource +
                    "\n库·页键(" + baseUrl + ")=" + byPage +
                    "\n开页定格headerMap=" + headerMap.entries.joinToString("; ")
        )
    }

    /** 【临时诊断】复查回来的正文是不是还是那张拦截页；顺带看定格 headerMap 里有没有 Cookie 头 */
    private fun diagRefetchBody(body: String?) {
        val len = body?.length ?: -1
        val blocked = body?.contains("var buid") == true
        val head = body?.take(200)
        AppLog.put(
            "验证诊断·复查回来 " + len + "字节 仍拦截=" + blocked +
                    "（=是 表示这次 OkHttp 请求依然没过签，规则拿到的就是这张空壳页）" +
                    "\n正文前200字=" + head
        )
    }

    /** 【临时诊断】把 Cookie 串压成一句判定：有没有那枚过签标、共几枚 */
    private fun diagMark(cookie: String?): String {
        val ck = cookie ?: return "取不到"
        val names = ck.split(";").map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
        val hasKeyCookie = names.any { it.contains("tsfp") || it.contains("captcha") || it.contains("waf") }
        return (if (hasKeyCookie) "有" else "无") + "过签标/" + names.size.toString() + "枚"
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
