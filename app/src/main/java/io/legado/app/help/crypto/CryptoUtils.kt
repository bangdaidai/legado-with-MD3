package io.legado.app.help.crypto

import java.io.ByteArrayOutputStream
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

/**
 * 对齐 Hutool `Base16Codec.decode`：忽略空白字符，长度为奇数时在前面补 0，
 * 遇到非 hex 字符仍然抛异常。
 */
internal fun String.hexToByteArray(): ByteArray {
    val cleaned = filterNot { it.isWhitespace() }
    val hex = if (cleaned.length % 2 == 0) cleaned else "0$cleaned"
    return ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

internal fun ByteArray.toBase64(): String = Base64.Default.encode(this)

internal fun String.base64ToByteArray(): ByteArray {
    // Hutool Base64.decode 对缺失 "=" 填充、url-safe(-/_)、空白等输入宽容，与 beta.17 之前
    // 应用内统一走 Hutool 解码的行为逐字节一致。Kotlin kotlin.io.encoding.Base64 为严格填充，
    // 会对未对齐 4 倍数的输入抛 "The padding option is set to PRESENT..."(见书源取章名回归)。
    return cn.hutool.core.codec.Base64.decode(replace("\\s".toRegex(), ""))
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
