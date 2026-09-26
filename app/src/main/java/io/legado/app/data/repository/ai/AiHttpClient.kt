package io.legado.app.data.repository.ai

import io.legado.app.help.http.okHttpClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * AI generation can legitimately spend several minutes before the next token arrives.
 * Do not time out reads or the whole call. The write timeout is also lifted: speech
 * analysis uploads a whole chapter as the request body, and the shared client's 15s
 * write timeout aborts slow uploads before the server ever starts thinking. Connect
 * keeps a generous cap instead of infinity so a dead host fails fast enough to fall
 * back; coroutine cancellation still cancels the underlying OkHttp call.
 */
internal val aiOkHttpClient: OkHttpClient by lazy {
    okHttpClient.newBuilder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
}
