package cn.hutool.crypto.digest

import io.legado.app.help.crypto.digest
import io.legado.app.help.crypto.toHexString
import java.io.InputStream

/**
 * Hutool `cn.hutool.crypto.digest.DigestUtil` 的兼容实现。
 *
 * 本分支已移除 Hutool 依赖，但部分书源 JS 通过 Rhino 的 `Packages.` 直接调用
 * `cn.hutool.crypto.digest.DigestUtil.md5(...)`。RhinoClassShutter 仅禁用
 * `cn.hutool.core.*`，`cn.hutool.crypto` 未被禁用，因此提供同名类即可被书源调用。
 *
 * 行为对齐 Hutool：
 * - [md5] 返回 16 字节摘要（Hutool 的 `md5(byte[])` / `md5(String)` 都返回 `byte[]`）
 * - [md5Hex] 返回 32 位小写 hex 字符串
 */
object DigestUtil {

    @JvmStatic
    fun md5(data: ByteArray): ByteArray = digest("MD5", data)

    @JvmStatic
    fun md5(data: String): ByteArray = digest("MD5", data.toByteArray())

    @JvmStatic
    fun md5(data: InputStream): ByteArray = digest("MD5", data)

    @JvmStatic
    fun md5Hex(data: ByteArray): String = md5(data).toHexString()

    @JvmStatic
    fun md5Hex(data: String): String = md5(data).toHexString()
}
