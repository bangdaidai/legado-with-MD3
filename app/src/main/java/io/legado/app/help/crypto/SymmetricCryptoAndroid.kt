package io.legado.app.help.crypto

import androidx.annotation.Keep
import io.legado.app.help.JsProbe
import io.legado.app.utils.isHex
import java.io.InputStream
import java.nio.charset.Charset
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Keep
open class SymmetricCryptoAndroid(
    private val algorithm: String,
    key: ByteArray?,
) {

    private val transformation = if (algorithm.contains('/')) algorithm else "$algorithm/ECB/PKCS5Padding"
    private val keyAlgorithm = algorithm.substringBefore('/')
    private val secretKey = key?.let { SecretKeySpec(normalizedKey(it), keyAlgorithm) }
        ?: KeyGenerator.getInstance(keyAlgorithm).generateKey()
    private var iv: ByteArray? = null

    private fun normalizedKey(key: ByteArray): ByteArray = when {
        // 与 Hutool KeyUtil 一致：DES/DESede 走 DESKeySpec/DESedeKeySpec，超长密钥只取前 8/24 字节；
        // 其余算法（含 AES）原样交给 SecretKeySpec，密钥长度非法时由 provider 抛异常。
        keyAlgorithm.equals("DES", true) && key.size > 8 -> key.copyOf(8)
        keyAlgorithm.equals("DESede", true) && key.size > 24 -> key.copyOf(24)
        else -> key
    }

    fun setIv(iv: ByteArray): SymmetricCryptoAndroid {
        this.iv = iv.copyOf()
        return this
    }

    private fun cipher(mode: Int): Cipher = try {
        Cipher.getInstance(transformation).apply {
            val iv = this@SymmetricCryptoAndroid.iv
            if (iv == null || transformation.contains("/ECB/", true)) {
                init(mode, secretKey)
            } else {
                init(mode, secretKey, IvParameterSpec(iv))
            }
        }
    } catch (e: Throwable) {
        // 临时探针：书源会吞掉这里的异常，定位后删除本 try/catch
        JsProbe.stepError("cipher($transformation,mode=$mode)", e)
        throw e
    }

    fun encrypt(data: ByteArray): ByteArray = cipher(Cipher.ENCRYPT_MODE).doFinal(data)

    fun encrypt(data: String): ByteArray = encrypt(data.toByteArray())

    fun encrypt(data: String, charset: String?): ByteArray =
        encrypt(data.toByteArray(charset?.let(Charset::forName) ?: Charsets.UTF_8))

    fun encrypt(data: String, charset: Charset?): ByteArray =
        encrypt(data.toByteArray(charset ?: Charsets.UTF_8))

    fun encrypt(data: InputStream): ByteArray = encrypt(data.readBytes())

    fun decrypt(data: ByteArray): ByteArray {
        val cipher = cipher(Cipher.DECRYPT_MODE)
        return try {
            cipher.doFinal(data)
        } catch (e: Throwable) {
            // 临时探针：密钥错误时这里抛 BadPadding，书源同样会吞掉
            JsProbe.stepError("decryptDoFinal(${data.size}B)", e)
            throw e
        }
    }

    fun encryptBase64(data: ByteArray): String {
        return encrypt(data).toBase64()
    }

    fun encryptBase64(data: String, charset: String?): String {
        return encrypt(data, charset).toBase64()
    }

    fun encryptBase64(data: String, charset: Charset?): String {
        return encrypt(data, charset).toBase64()
    }

    fun encryptBase64(data: String): String {
        return encrypt(data).toBase64()
    }

    fun encryptBase64(data: InputStream): String {
        return encrypt(data).toBase64()
    }

    fun decrypt(data: String): ByteArray {
        // 与 Hutool 一致：全 hex 字符就按 hex 解（奇数长度由 hexToByteArray 补 0），否则按 base64 解
        val bytes = if (data.isHex()) {
            data.hexToByteArray()
        } else {
            data.base64ToByteArray()
        }
        return decrypt(bytes)
    }

    fun decrypt(data: InputStream): ByteArray = decrypt(data.readBytes())

    fun decryptStr(data: ByteArray): String = String(decrypt(data), Charsets.UTF_8)

    fun decryptStr(data: String): String = String(decrypt(data), Charsets.UTF_8)

    fun decryptStr(data: InputStream): String = String(decrypt(data), Charsets.UTF_8)

    fun encryptHex(data: ByteArray): String = encrypt(data).toHexString()

    fun encryptHex(data: String): String = encrypt(data).toHexString()

    fun encryptHex(data: InputStream): String = encrypt(data).toHexString()

    fun decryptHex(data: String): ByteArray = decrypt(data.hexToByteArray())

    fun decryptBase64(data: String): ByteArray = decrypt(data.base64ToByteArray())

    fun encryptStr(data: ByteArray): String = String(encrypt(data), Charsets.UTF_8)

    fun encryptStr(data: String): String = String(encrypt(data), Charsets.UTF_8)

}
