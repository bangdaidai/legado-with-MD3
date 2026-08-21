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

private val BASE64_DECODE_TABLE = IntArray(128) { -1 }.also { table ->
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".forEachIndexed { index, c ->
        table[c.code] = index
    }
    table['+'.code] = 62
    table['/'.code] = 63
    // URL-safe 字符集与标准字符集共用一张表，允许两者混用
    table['-'.code] = 62
    table['_'.code] = 63
}

/**
 * 对齐 Hutool `Base64Decoder.decode`：直接跳过非 base64 字符，允许缺失 padding、
 * 允许标准与 URL-safe 字符集混用，任何输入都不抛异常。
 *
 * 书源（尤其是先解密再 base64 解码做校验的那类）依赖这种宽松行为，严格解码会让
 * 书源走进自身的异常兜底分支。
 */
internal fun String.base64ToByteArray(): ByteArray {
    val out = ByteArrayOutputStream(length * 3 / 4 + 3)
    var buffer = 0
    var bits = 0
    for (c in this) {
        if (c == '=') break
        val value = if (c.code < 128) BASE64_DECODE_TABLE[c.code] else -1
        if (value < 0) continue
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.write((buffer ushr bits) and 0xFF)
        }
    }
    return out.toByteArray()
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
