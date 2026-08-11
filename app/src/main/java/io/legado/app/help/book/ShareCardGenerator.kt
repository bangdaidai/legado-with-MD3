package io.legado.app.help.book

import io.legado.app.data.appDb
import io.legado.app.data.entities.ShareCardTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 分享卡片生成入口。
 * - 管理内置模板的创建与恢复
 *
 * 出图不再从这里走：预览由 [ShareCardHtmlRenderer.buildPreviewHtml] 喂给实时 WebView，
 * 保存时才调 [ShareCardHtmlRenderer.render] 做一次离屏渲染。
 */
object ShareCardGenerator {

    const val SELECTED_TEMPLATE_KEY = "selectedShareCardTemplateId"

    /**
     * 确保内置模板存在（App 冷启动 / 清除数据后调用）
     */
    suspend fun getOrCreateBuiltinTemplates(): List<ShareCardTemplate> = withContext(Dispatchers.IO) {
        val existing = appDb.shareCardTemplateDao.getBuiltinsByGroupName(ShareCardTemplate.DEFAULT_GROUP_BOOK)
        if (existing.isNotEmpty()) return@withContext existing

        val templates = listOf(
            ShareCardTemplate(
                name = "默认模板",
                htmlContent = DEFAULT_TEMPLATE_HTML,
                isBuiltin = true,
                groupName = ShareCardTemplate.DEFAULT_GROUP_BOOK,
                createTime = System.currentTimeMillis(),
                updateTime = System.currentTimeMillis(),
            ),
        )
        templates.forEach { appDb.shareCardTemplateDao.insert(it) }
        appDb.shareCardTemplateDao.getBuiltinsByGroupName(ShareCardTemplate.DEFAULT_GROUP_BOOK)
    }



    /** 默认内置 HTML 模板 — 尽可能覆盖大部分可用字段，起到示范作用 */
    private val DEFAULT_TEMPLATE_HTML = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, "Noto Sans SC", sans-serif; background: var(--bp-bg, linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%)); color: var(--bp-text, #e0e0e0); padding: 20px; }
.card { background: var(--bp-surface, rgba(255,255,255,0.05)); border-radius: 16px; padding: 20px; backdrop-filter: blur(10px); border: 1px solid var(--bp-divider, rgba(255,255,255,0.1)); }
.header { display: flex; gap: 16px; margin-bottom: 16px; }
.cover { width: 90px; height: 130px; object-fit: cover; border-radius: 10px; flex-shrink: 0; }
.info { flex: 1; display: flex; flex-direction: column; justify-content: center; }
.title { font-size: 20px; font-weight: 700; color: var(--bp-text, #fff); margin-bottom: 4px; }
.author { font-size: 13px; color: var(--bp-text-subtle, #aaa); margin-bottom: 6px; }
.kind { font-size: 11px; color: var(--bp-text-subtle, #888); margin-bottom: 4px; }
.tags { font-size: 11px; color: var(--bp-accent-light, #6ec6ff); }
.rating { font-size: 16px; color: var(--bp-star, #ffd700); margin-bottom: 12px; }
.intro { font-size: 12px; color: var(--bp-text-muted, #bbb); line-height: 1.5; margin-bottom: 14px; max-height: 60px; overflow: hidden; text-overflow: ellipsis; }
.meta { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 8px; margin-bottom: 14px; }
.meta-item { background: var(--bp-surface-variant, rgba(255,255,255,0.08)); padding: 8px 10px; border-radius: 8px; }
.meta-label { font-size: 10px; color: var(--bp-text-muted, #888); }
.meta-value { font-size: 13px; color: var(--bp-text, #fff); margin-top: 2px; }
.section-title { font-size: 12px; color: var(--bp-accent-light, #6ec6ff); margin-bottom: 6px; font-weight: 600; }
.stats { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 14px; }
.stat-item { background: var(--bp-surface-variant, rgba(255,255,255,0.06)); padding: 6px 10px; border-radius: 6px; }
.stat-label { font-size: 10px; color: var(--bp-text-muted, #888); }
.stat-value { font-size: 12px; color: var(--bp-text, #ddd); margin-top: 1px; }
.review { font-size: 12px; color: var(--bp-text, #ccc); line-height: 1.6; margin-bottom: 12px; padding: 10px; background: var(--bp-surface, rgba(255,255,255,0.05)); border-radius: 8px; border-left: 3px solid var(--bp-accent, #4a9eff); }
.annotation { font-size: 11px; color: var(--bp-text-muted, #bbb); line-height: 1.5; margin-bottom: 12px; padding: 10px; background: var(--bp-surface, rgba(255,255,255,0.04)); border-radius: 8px; border-left: 3px solid var(--bp-star, #ffd700); }
.annotation-chapter { font-size: 10px; color: var(--bp-text-muted, #888); margin-top: 4px; }
.progress-bar { height: 4px; background: var(--bp-divider, rgba(255,255,255,0.1)); border-radius: 2px; margin-bottom: 14px; overflow: hidden; }
.progress-fill { height: 100%; background: linear-gradient(90deg, var(--bp-accent, #4a9eff), var(--bp-accent-light, #6ec6ff)); border-radius: 2px; }
.footer { font-size: 10px; color: var(--bp-text-subtle, #666); text-align: center; margin-top: 14px; padding-top: 10px; border-top: 1px solid var(--bp-divider, rgba(255,255,255,0.06)); }
.footer-source { font-size: 10px; color: var(--bp-text-subtle, #555); }
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
    — 分享卡片 —
    <div class="footer-source">{{bookSourceName}} · {{originName}}</div>
  </div>
</div>
</body>
</html>
""".trimIndent()
}
