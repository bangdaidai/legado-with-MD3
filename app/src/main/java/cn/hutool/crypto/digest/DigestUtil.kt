package cn.hutool.crypto.digest

import io.legado.app.help.crypto.digest
import io.legado.app.help.crypto.toHexString

/**
 * Hutool `cn.hutool.crypto.digest.DigestUtil` 的兼容实现。
 *
 * 本分支已移除 Hutool 依赖，但部分书源 JS 通过 Rhino 的 `Packages.` 直接调用
 * `cn.hutool.crypto.digest.DigestUtil.md5(...)`。RhinoClassShutter 仅禁用
 * `cn.hutool.core.*`，`cn.hutool.crypto` 未被禁用，因此提供同名类即可被书源调用。
 *
 * 行为对齐 Hutool：
 * - [md5] 返回 32 位小写 hex 字符串
 */
object DigestUtil {

    @JvmStatic
    fun md5(data: String): String = digest("MD5", data.toByteArray()).toHexString()
}
