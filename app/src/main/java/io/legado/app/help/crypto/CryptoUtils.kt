package io.legado.app.help.crypto

import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

private const val HEX_CHARS = "0123456789abcdef"

internal fun ByteArray.toHexString(): String {
    val builder = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        builder.append(HEX_CHARS[v ushr 4])
        builder.append(HEX_CHARS[v and 0x0F])
    }
    return builder.toString()
}

internal fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex input must contain an even number of characters" }
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun ByteArray.toBase64(): String = Base64.Default.encode(this)

internal fun String.base64ToByteArray(): ByteArray {
    val normalized = replace("\\s".toRegex(), "")
    if (normalized.isEmpty()) return ByteArray(0)
    // 兼容书源中常见的非规范 base64 输入：
    // 1. 缺失 padding（RFC 4648 §3.2 允许省略末尾 '='，解码前按剩余字符数补 1~2 个）
    // 2. URL-safe 字符集（'-' '_'）与标准字符集混用
    val stripped = normalized.trimEnd('=')
    val padded = when (stripped.length % 4) {
        2 -> "$stripped=="
        3 -> "$stripped="
        else -> stripped
    }
    val candidates = listOf(
        normalized,                                    // 原样：标准/URL-safe 且 padding 完整
        padded,                                        // 补 padding 后
        padded.replace('-', '+').replace('_', '/'),    // URL-safe 字符还原为标准字符
    )
    for (base64 in listOf(Base64.Default, Base64.UrlSafe)) {
        for (candidate in candidates) {
            runCatching { base64.decode(candidate) }.getOrNull()?.let { return it }
        }
    }
    throw IllegalArgumentException("Invalid base64 input: $normalized")
}

internal fun digest(algorithm: String, data: ByteArray): ByteArray =
    MessageDigest.getInstance(algorithm).digest(data)

internal fun digest(algorithm: String, input: InputStream): ByteArray {
    val messageDigest = MessageDigest.getInstance(algorithm)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read > 0) messageDigest.update(buffer, 0, read)
    }
    return messageDigest.digest()
}

internal fun hmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance(algorithm)
    mac.init(SecretKeySpec(key, algorithm))
    return mac.doFinal(data)
}
