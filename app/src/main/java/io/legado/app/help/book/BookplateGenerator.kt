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

    /** 默认内置 HTML 模板 — 尽可能覆盖大部分可用字段，起到示范作用 */
    private val DEFAULT_TEMPLATE_HTML = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, "Noto Sans SC", sans-serif; background: linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%); color: #e0e0e0; padding: 20px; }
.card { background: rgba(255,255,255,0.05); border-radius: 16px; padding: 20px; backdrop-filter: blur(10px); border: 1px solid rgba(255,255,255,0.1); }
.header { display: flex; gap: 16px; margin-bottom: 16px; }
.cover { width: 90px; height: 130px; object-fit: cover; border-radius: 10px; flex-shrink: 0; }
.info { flex: 1; display: flex; flex-direction: column; justify-content: center; }
.title { font-size: 20px; font-weight: 700; color: #fff; margin-bottom: 4px; }
.author { font-size: 13px; color: #aaa; margin-bottom: 6px; }
.kind { font-size: 11px; color: #888; margin-bottom: 4px; }
.tags { font-size: 11px; color: #6ec6ff; }
.rating { font-size: 16px; color: #ffd700; margin-bottom: 12px; }
.intro { font-size: 12px; color: #bbb; line-height: 1.5; margin-bottom: 14px; max-height: 60px; overflow: hidden; text-overflow: ellipsis; }
.meta { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; margin-bottom: 14px; }
.meta-item { background: rgba(255,255,255,0.08); padding: 8px 10px; border-radius: 8px; }
.meta-label { font-size: 10px; color: #888; }
.meta-value { font-size: 13px; color: #fff; margin-top: 2px; }
.section-title { font-size: 12px; color: #6ec6ff; margin-bottom: 6px; font-weight: 600; }
.stats { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 14px; }
.stat-item { background: rgba(255,255,255,0.06); padding: 6px 10px; border-radius: 6px; }
.stat-label { font-size: 10px; color: #888; }
.stat-value { font-size: 12px; color: #ddd; margin-top: 1px; }
.review { font-size: 12px; color: #ccc; line-height: 1.6; margin-bottom: 12px; padding: 10px; background: rgba(255,255,255,0.05); border-radius: 8px; border-left: 3px solid #4a9eff; }
.annotation { font-size: 11px; color: #bbb; line-height: 1.5; margin-bottom: 12px; padding: 10px; background: rgba(255,255,255,0.04); border-radius: 8px; border-left: 3px solid #ffd700; }
.annotation-chapter { font-size: 10px; color: #888; margin-top: 4px; }
.progress-bar { height: 4px; background: rgba(255,255,255,0.1); border-radius: 2px; margin-bottom: 14px; overflow: hidden; }
.progress-fill { height: 100%; background: linear-gradient(90deg, #4a9eff, #6ec6ff); border-radius: 2px; }
.footer { font-size: 10px; color: #666; text-align: center; margin-top: 14px; padding-top: 10px; border-top: 1px solid rgba(255,255,255,0.06); }
.footer-source { font-size: 10px; color: #555; }
</style>
</head>
<body>
<div class="card">
  <!-- 头部：封面 + 基本信息 -->
  <div class="header">
    <img class="cover" src="{{coverUrl}}" onerror="this.style.display='none'">
    <div class="info">
      <div class="title">{{bookName}}</div>
      <div class="author">{{author}}</div>
      <div class="kind">{{kind}} · {{wordCount}}</div>
      <div class="tags">{{tags}}</div>
    </div>
  </div>

  <!-- 评分 -->
  <div class="rating">{{ratingStars}}</div>

  <!-- 简介 -->
  <div class="intro">{{intro}}</div>

  <!-- 进度条 -->
  <div class="progress-bar"><div class="progress-fill" style="width:{{readingProgress}}"></div></div>

  <!-- 阅读状态网格 -->
  <div class="meta">
    <div class="meta-item"><div class="meta-label">阅读状态</div><div class="meta-value">{{readingStatusText}}</div></div>
    <div class="meta-item"><div class="meta-label">进度</div><div class="meta-value">{{readingProgress}}</div></div>
    <div class="meta-item"><div class="meta-label">当前章节</div><div class="meta-value">{{durChapterTitle}}</div></div>
    <div class="meta-item"><div class="meta-label">已读</div><div class="meta-value">{{readChapters}}</div></div>
    <div class="meta-item"><div class="meta-label">总章节</div><div class="meta-value">{{totalChapterNum}}</div></div>
    <div class="meta-item"><div class="meta-label">重读</div><div class="meta-value">{{readIterationText}}</div></div>
  </div>

  <!-- 阅读统计 -->
  <div class="section-title">阅读统计</div>
  <div class="stats">
    <div class="stat-item"><div class="stat-label">累计时长</div><div class="stat-value">{{totalReadTime}}</div></div>
    <div class="stat-item"><div class="stat-label">阅读天数</div><div class="stat-value">{{readingDays}} 天</div></div>
    <div class="stat-item"><div class="stat-label">单日最长</div><div class="stat-value">{{maxDayReadTime}}</div></div>
    <div class="stat-item"><div class="stat-label">最长日期</div><div class="stat-value">{{maxDayReadDate}}</div></div>
    <div class="stat-item"><div class="stat-label">已读字数</div><div class="stat-value">{{totalReadWords}}</div></div>
    <div class="stat-item"><div class="stat-label">剩余字数</div><div class="stat-value">{{remainingWords}}</div></div>
  </div>

  <!-- 时间线 -->
  <div class="section-title">时间线</div>
  <div class="stats">
    <div class="stat-item"><div class="stat-label">首次阅读</div><div class="stat-value">{{firstReadTime}}</div></div>
    <div class="stat-item"><div class="stat-label">最近阅读</div><div class="stat-value">{{lastReadTime}}</div></div>
    <div class="stat-item"><div class="stat-label">读完时间</div><div class="stat-value">{{finishReadTime}}</div></div>
    <div class="stat-item"><div class="stat-label">加入书架</div><div class="stat-value">{{addBookshelfTime}}</div></div>
  </div>

  <!-- 书评 -->
  <div class="review" style="display:block">{{reviewContent}}</div>

  <!-- 最新书摘 -->
  <div class="annotation">
    {{latestAnnotation}}
    <div class="annotation-chapter">—— {{latestAnnotationChapter}}</div>
  </div>

  <!-- 底栏 -->
  <div class="footer">
    — 藏书票 —
    <div class="footer-source">{{bookSourceName}} · {{originName}}</div>
  </div>
</div>
</body>
</html>
""".trimIndent()
}
