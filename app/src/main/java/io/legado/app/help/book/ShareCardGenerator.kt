package io.legado.app.help.book

import io.legado.app.data.appDb
import io.legado.app.data.entities.ShareCardTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * 分享卡片生成入口。
 * - 管理内置模板的创建与恢复
 *
 * 内置模板 HTML 存放在 `assets/shareCard/` 下（每个 .html 一套皮肤），冷启动时读入写库。
 * 独立成 assets 文件后：模板可直接用浏览器打开预览（文件自带 mock 脚本），也方便增删皮肤。
 */
object ShareCardGenerator {

    const val SELECTED_TEMPLATE_KEY = "selectedShareCardTemplateId"

    /** 内置模板清单：展示名 → assets 路径。新增皮肤只需在这里加一行 + 放一个 .html。 */
    private val BUILTIN_TEMPLATES = listOf(
        "默认模板" to "shareCard/default.html",
    )

    /**
     * 确保内置模板存在（App 冷启动 / 清除数据后调用）
     */
    suspend fun getOrCreateBuiltinTemplates(): List<ShareCardTemplate> = withContext(Dispatchers.IO) {
        val existing = appDb.shareCardTemplateDao.getBuiltinsByGroupName(ShareCardTemplate.DEFAULT_GROUP_BOOK)
        if (existing.isNotEmpty()) return@withContext existing

        val now = System.currentTimeMillis()
        val templates = BUILTIN_TEMPLATES.map { (name, assetPath) ->
            ShareCardTemplate(
                name = name,
                htmlContent = readAsset(assetPath),
                isBuiltin = true,
                groupName = ShareCardTemplate.DEFAULT_GROUP_BOOK,
                createTime = now,
                updateTime = now,
            )
        }
        templates.forEach { appDb.shareCardTemplateDao.insert(it) }
        appDb.shareCardTemplateDao.getBuiltinsByGroupName(ShareCardTemplate.DEFAULT_GROUP_BOOK)
    }

    private fun readAsset(path: String): String =
        appCtx.assets.open(path).bufferedReader().use { it.readText() }
}
