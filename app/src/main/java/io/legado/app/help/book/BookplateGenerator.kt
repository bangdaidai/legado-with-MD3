package io.legado.app.help.book

import android.content.Context
import android.graphics.Bitmap
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookplateData
import io.legado.app.data.entities.BookplateTemplate
import io.legado.app.data.entities.ReadingMemory
import io.legado.app.help.config.AppConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 藏书票生成入口。
 * - 管理内置模板的创建与恢复
 * - 提供从 ReadingMemory 生成 Bitmap 的统一入口
 */
object BookplateGenerator {

    const val SELECTED_TEMPLATE_KEY = "selectedBookplateTemplateId"

    /**
     * 从阅读记忆生成藏书票
     */
    suspend fun generate(context: Context, memory: ReadingMemory): Bitmap? = withContext(Dispatchers.IO) {
        val data = BookplateDataBuilder.build(memory)
        val template = resolveTemplate() ?: getOrCreateBuiltinTemplates().firstOrNull() ?: return@withContext null
        BookplateHtmlRenderer.render(context, template, data)
    }

    /**
     * 从自定义 BookplateData 生成（供外部调用传入已组装好的数据）
     */
    suspend fun generate(context: Context, data: BookplateData, templateId: Long = 0): Bitmap? = withContext(Dispatchers.IO) {
        val template = if (templateId > 0) {
            appDb.bookplateTemplateDao.getById(templateId)
        } else {
            resolveTemplate() ?: getOrCreateBuiltinTemplates().firstOrNull()
        } ?: return@withContext null
        BookplateHtmlRenderer.render(context, template, data)
    }

    /**
     * 确保内置模板存在（App 冷启动 / 清除数据后调用）
     */
    suspend fun getOrCreateBuiltinTemplates(): List<BookplateTemplate> = withContext(Dispatchers.IO) {
        val existing = appDb.bookplateTemplateDao.getBuiltinsByGroupName(BookplateTemplate.DEFAULT_GROUP_BOOK)
        if (existing.isNotEmpty()) return@withContext existing

        val templates = listOf(
            BookplateTemplate(
                name = "默认模板",
                htmlContent = DEFAULT_TEMPLATE_HTML,
                isBuiltin = true,
                groupName = BookplateTemplate.DEFAULT_GROUP_BOOK,
                createTime = System.currentTimeMillis(),
                updateTime = System.currentTimeMillis(),
            ),
        )
        templates.forEach { appDb.bookplateTemplateDao.insert(it) }
        appDb.bookplateTemplateDao.getBuiltinsByGroupName(BookplateTemplate.DEFAULT_GROUP_BOOK)
    }

    private suspend fun resolveTemplate(): BookplateTemplate? {
        val savedId = AppConfigStore.getLong(SELECTED_TEMPLATE_KEY) ?: 0L
        if (savedId > 0L) {
            appDb.bookplateTemplateDao.getById(savedId)?.let { return it }
        }
        return appDb.bookplateTemplateDao.getBuiltinsByGroupName(BookplateTemplate.DEFAULT_GROUP_BOOK).firstOrNull()
    }

    /** 默认内置 HTML 模板 */
    private val DEFAULT_TEMPLATE_HTML = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, sans-serif; background: linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%); color: #e0e0e0; padding: 24px; }
.card { background: rgba(255,255,255,0.05); border-radius: 16px; padding: 24px; backdrop-filter: blur(10px); border: 1px solid rgba(255,255,255,0.1); }
.title { font-size: 22px; font-weight: 700; color: #fff; margin-bottom: 8px; }
.author { font-size: 14px; color: #aaa; margin-bottom: 16px; }
.cover { width: 100%; max-height: 200px; object-fit: cover; border-radius: 12px; margin-bottom: 16px; }
.meta { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 16px; }
.meta-item { background: rgba(255,255,255,0.08); padding: 8px 12px; border-radius: 8px; }
.meta-label { font-size: 11px; color: #888; }
.meta-value { font-size: 14px; color: #fff; margin-top: 2px; }
.rating { font-size: 18px; color: #ffd700; margin-bottom: 12px; }
.review { font-size: 13px; color: #ccc; line-height: 1.6; margin-bottom: 12px; padding: 12px; background: rgba(255,255,255,0.05); border-radius: 8px; border-left: 3px solid #4a9eff; }
.footer { font-size: 11px; color: #666; text-align: center; margin-top: 16px; }
</style>
</head>
<body>
<div class="card">
  <div class="title">{{bookName}}</div>
  <div class="author">{{author}}</div>
  <img class="cover" src="{{coverUrl}}" onerror="this.style.display='none'">
  <div class="rating">{{ratingStars}}</div>
  <div class="meta">
    <div class="meta-item"><div class="meta-label">阅读状态</div><div class="meta-value">{{readingStatusText}}</div></div>
    <div class="meta-item"><div class="meta-label">阅读进度</div><div class="meta-value">{{readingProgress}}</div></div>
    <div class="meta-item"><div class="meta-label">累计时长</div><div class="meta-value">{{totalReadTime}}</div></div>
    <div class="meta-item"><div class="meta-label">阅读天数</div><div class="meta-value">{{readingDays}}天</div></div>
    <div class="meta-item"><div class="meta-label">首次阅读</div><div class="meta-value">{{firstReadTime}}</div></div>
    <div class="meta-item"><div class="meta-label">最近阅读</div><div class="meta-value">{{lastReadTime}}</div></div>
  </div>
  <div class="review" style="display:{{reviewContent}}?'block':'none'">{{reviewContent}}</div>
  <div class="footer">— 藏书票 —</div>
</div>
</body>
</html>
""".trimIndent()
}
