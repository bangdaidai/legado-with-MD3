package io.legado.app.help.coil

import android.util.Base64
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.utils.ImageUtils
import io.legado.app.utils.isWifiConnect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import splitties.init.appCtx
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class CoverFetcher(
    private val url: String,
    private val options: Options,
    private val callFactory: Call.Factory,
    private val loadOnlyWifi: Boolean,
) : Fetcher {

    companion object {
        /** Tag applied to cover requests so [cacheControlInterceptor] can identify them. */
        val COVER_REQUEST_TAG = Unit

        /** 4xx（如 404 死链、403 鉴权失败）：地址大概率长期不可用，拉黑久一点省得反复打。 */
        private const val FAIL_TTL_CLIENT_MS = 5 * 60 * 1000L // 5 minutes
        /** 超时 / 网络抖动 / 5xx：临时性问题，很快就能重试，只短暂拉黑避免瞬时风暴。 */
        private const val FAIL_TTL_TRANSIENT_MS = 30 * 1000L // 30 seconds

        /** URL -> 拉黑到期时间戳。到期即视为可重试。 */
        private val failCache = ConcurrentHashMap<String, Long>()

        fun isFailed(url: String): Boolean {
            val deadline = failCache[url] ?: return false
            if (System.currentTimeMillis() > deadline) {
                failCache.remove(url)
                return false
            }
            return true
        }

        fun markFailed(url: String, ttlMs: Long) {
            failCache[url] = System.currentTimeMillis() + ttlMs
        }

        fun clearFailure(url: String) {
            failCache.remove(url)
        }

        fun clearFailCache() {
            failCache.clear()
        }

        /**
         * 在途下载去重：key 为 "$url|$isManga"，value 为共享的下载任务。
         *
         * 同一张封面会被多处同时请求（详情页主封面与背景大图用不同的 memoryCacheKey，
         * 内存都 miss 时并发打同一个 URL），而 OkHttp 不做在途请求合并，结果同一张图
         * 下载两遍。这里按地址合流，只下载一次。
         */
        private val inFlight = ConcurrentHashMap<String, Deferred<Pair<ByteArray, Boolean>>>()

        /**
         * 下载跑在这个独立作用域里，而不是调用方的协程里：某个调用方被取消（封面滑出
         * 屏幕）不应该连带取消其它等待者的下载。SupervisorJob 保证单次失败不影响后续。
         */
        private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override suspend fun fetch(): FetchResult {
        val source = options.extras[CoverExtras.Source]
        val isManga = options.extras[CoverExtras.Manga] == true
        val mangaBook = options.extras[CoverExtras.MangaBookUrl]
            ?.let { bookUrl -> withContext(Dispatchers.IO) { appDb.bookDao.getBook(bookUrl) } }

        if (url.startsWith("data:", true)) {
            val base64Data = url.substringAfter("base64,", "")
            if (base64Data.isEmpty()) {
                throw IOException("Invalid data URI")
            }
            val bytes = Base64.decode(base64Data, Base64.DEFAULT)
            return SourceFetchResult(
                source = ImageSource(
                    source = Buffer().write(bytes),
                    fileSystem = options.fileSystem
                ),
                mimeType = null,
                dataSource = DataSource.MEMORY
            )
        }

        if (loadOnlyWifi && !appCtx.isWifiConnect) {
            throw IOException("WiFi not available, loadOnlyWifi enabled")
        }

        if (isFailed(url)) {
            throw IOException("URL previously failed, skipping: $url")
        }

        val requestHeaders = options.extras[CoverExtras.Headers]
        val (rawBytes, fromCache) = try {
            awaitSharedDownload(url, isManga, source, requestHeaders)
        } catch (e: CancellationException) {
            // 滚动书架时 Coil 会取消在途请求，取消不代表这个封面地址有问题，不能拉黑
            throw e
        } catch (e: Exception) {
            markFailed(
                url,
                if (e is CoverHttpException && e.code in 400..499) {
                    FAIL_TTL_CLIENT_MS
                } else {
                    FAIL_TTL_TRANSIENT_MS
                }
            )
            throw e
        }

        // Decrypt if needed (applies to both cached and network bytes)
        val decodedBytes = if (ImageUtils.skipDecode(source, !isManga)) {
            rawBytes
        } else {
            // rawBytes 可能被多个等待者共享，解密规则是用户 JS，不能保证不原地改数组，
            // 所以这里传副本。
            val ownBytes = rawBytes.copyOf()
            withContext(Dispatchers.IO) {
                if (isManga) {
                    ImageUtils.decode(url, ownBytes, false, source, mangaBook)
                } else {
                    ImageUtils.decode(url, ownBytes, true, source)
                }
            } ?: throw IOException("图片解密失败")
        }

        clearFailure(url)
        return SourceFetchResult(
            source = ImageSource(
                source = Buffer().write(decodedBytes),
                fileSystem = options.fileSystem
            ),
            mimeType = null,
            dataSource = if (fromCache) DataSource.DISK else DataSource.NETWORK
        )
    }

    /** 取得该地址的共享下载任务并等待结果，没有在途任务时才真正发起下载。 */
    private suspend fun awaitSharedDownload(
        url: String,
        isManga: Boolean,
        source: BaseSource?,
        requestHeaders: Map<String, String>?,
    ): Pair<ByteArray, Boolean> {
        val key = "$url|$isManga"
        val deferred = inFlight.computeIfAbsent(key) {
            fetchScope.async { download(url, source, requestHeaders) }
        }
        // 任务结束即从表中摘除，后续请求走 OkHttp 缓存或重新发起。
        // 注册在 computeIfAbsent 之外，避免回调在 ConcurrentHashMap 计算过程中改表。
        deferred.invokeOnCompletion { inFlight.remove(key, deferred) }
        return deferred.await()
    }

    /** 先探 OkHttp 缓存（FORCE_CACHE 命中即返回，miss 返回 504），未命中再走网络。 */
    private suspend fun download(
        url: String,
        source: BaseSource?,
        requestHeaders: Map<String, String>?,
    ): Pair<ByteArray, Boolean> = withContext(Dispatchers.IO) {
        val cacheRequest = Request.Builder()
            .url(url)
            .tag(BaseSource::class.java, source)
            .apply { requestHeaders?.forEach { (key, value) -> addHeader(key, value) } }
            .cacheControl(CacheControl.FORCE_CACHE)
            .build()
        val cacheResponse = callFactory.newCall(cacheRequest).execute()
        if (cacheResponse.isSuccessful) {
            cacheResponse.body.use { it.bytes() } to true
        } else {
            cacheResponse.close()
            // Cache miss, fetch from network
            val networkRequest = Request.Builder()
                .url(url)
                .tag(BaseSource::class.java, source)
                .apply { requestHeaders?.forEach { (key, value) -> addHeader(key, value) } }
                .tag(COVER_REQUEST_TAG)
                .cacheControl(
                    CacheControl.Builder()
                        .maxAge(30, TimeUnit.DAYS)
                        .build()
                )
                .build()
            val networkResponse = callFactory.newCall(networkRequest).execute()
            val body = networkResponse.body
            if (!networkResponse.isSuccessful) {
                body.close()
                throw CoverHttpException(networkResponse.code)
            }
            body.use { it.bytes() } to false
        }
    }

    /** 带状态码的失败，供上层区分「地址坏了」和「网络抖动」以决定拉黑时长。 */
    private class CoverHttpException(val code: Int) : IOException("HTTP $code")

    class Factory(
        private val okHttpClient: OkHttpClient,
        private val okHttpClientManga: OkHttpClient,
    ) : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val scheme = data.scheme
            if (scheme != "http" && scheme != "https" && scheme != "data") return null

            val isManga = options.extras[CoverExtras.Manga] == true
            val loadOnlyWifi = options.extras[CoverExtras.LoadOnlyWifi] == true
            val client = if (isManga) okHttpClientManga else okHttpClient

            return CoverFetcher(data.toString(), options, client, loadOnlyWifi)
        }
    }
}
