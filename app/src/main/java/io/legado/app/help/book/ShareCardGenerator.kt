package io.legado.app.help.book

import io.legado.app.data.appDb
import io.legado.app.data.entities.ShareCardTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 分享卡片生成入口。
 * - 管理内置模板的创建与恢复
 *
 * 出图不再从这里走：预览和保存共用一份 [ShareCardHtmlRenderer.buildPreviewHtml] 喂给实时
 * WebView，保存时直接 `draw()` 那个 WebView 截图，保证预览 == 保存。
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



    /** 默认内置 HTML 模板 — 少女心粉色卡（浮动装饰 + 玻璃拟态），配色全走 --bp-* 变量 */
    private val DEFAULT_TEMPLATE_HTML = """
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8">
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body {
  width: 100%;
  max-width: 100%;
  padding: 28px 18px;
  /* 底色必须自己拼渐变：--bp-bg 注入的是纯色，靠 var() 兜底渐变的话「选了色」就退化成平色。
     三个 radial 也要用三个不同变量，否则注入后三层同色、层次全没了。 */
  background:
    radial-gradient(ellipse at 10% 20%, var(--bp-surface, #ffe2ea) 0%, transparent 55%),
    radial-gradient(ellipse at 90% 80%, var(--bp-accent-light, #fccfdf) 0%, transparent 55%),
    radial-gradient(ellipse at 50% 110%, rgba(var(--bp-accent-rgb, 251, 200, 216), 0.45) 0%, transparent 50%),
    linear-gradient(145deg,
      var(--bp-accent-light, #ffe4ec) 0%,
      var(--bp-bg, #fdd6e2) 55%,
      rgba(var(--bp-accent-rgb, 247, 194, 212), 0.55) 100%);
  font-family: "Noto Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif;
  position: relative;
  overflow-x: hidden;
  color: var(--bp-text, #4d2e3a);
}
body::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 0;
  /* 格纹用 --bp-accent-rgb 自己配 alpha，不要用 --bp-accent-fade：
     后者 alpha 被 Kotlin 写死成 0.15，是这里兜底值的 3 倍，一选色格纹就糊满整张卡。 */
  background-image:
    repeating-linear-gradient(45deg, rgba(var(--bp-accent-rgb, 255, 205, 219), 0.06) 0px, transparent 2px, transparent 8px),
    repeating-linear-gradient(-45deg, rgba(var(--bp-accent-rgb, 255, 188, 207), 0.05) 0px, transparent 2px, transparent 8px);
}
.float-element { position: absolute; pointer-events: none; z-index: 0; }
.float-star {
  width: 36px; height: 36px;
  background: radial-gradient(circle at 35% 35%, #fff7d6, var(--bp-accent-light, #fce1a4));
  clip-path: polygon(50% 0%, 61% 38%, 99% 36%, 68% 60%, 79% 97%, 49% 73%, 19% 97%, 29% 59%, 1% 37%, 39% 39%);
  filter: drop-shadow(0 3px 10px var(--bp-accent-fade, rgba(252, 198, 138, 0.25)));
}
.float-heart {
  width: 38px; height: 35px;
  background: var(--bp-accent, #faaab9);
  transform: rotate(-10deg);
  border-radius: 19px 19px 0 0;
  position: relative;
  filter: drop-shadow(0 3px 12px var(--bp-accent-fade, rgba(250, 158, 179, 0.3)));
}
.float-heart::before,
.float-heart::after {
  content: '';
  position: absolute;
  width: 37px; height: 58px;
  background: var(--bp-accent, #faaab9);
  border-radius: 30px 30px 0 0;
  top: -9px;
}
.float-heart::before { left: -19px; transform: rotate(-42deg); }
.float-heart::after { right: -19px; transform: rotate(42deg); }
.float-diamond {
  width: 32px; height: 50px;
  background: linear-gradient(135deg, var(--bp-accent-light, #fcd1e0), var(--bp-accent, #fbc4d6));
  clip-path: polygon(48% 0%, 95% 46%, 49% 93%, 3% 47%);
  transform: scale(0.9) rotate(6deg);
  filter: drop-shadow(0 3px 10px var(--bp-accent-fade, rgba(233, 152, 178, 0.2)));
}
.float-circle {
  width: 34px; height: 34px;
  border: 3px solid var(--bp-accent, #fbbfcb);
  border-radius: 70% 30% 65% 35% / 55% 58% 41% 43%;
  background: var(--bp-accent-fade, rgba(253, 227, 237, 0.25));
}
.float-dot-group { display: flex; gap: 12px; }
.float-dot {
  width: 12px; height: 12px;
  border-radius: 80% 20% 74% 23% / 32% 69% 30% 66%;
  background: var(--bp-accent, #fbaec0);
}
.float-dot:nth-child(2) { background: var(--bp-accent-light, #fbc4d2); width: 16px; height: 16px; }
.float-dot:nth-child(3) { background: var(--bp-star, #fddaac); width: 10px; height: 10px; }
.fe-1 { top: 2%; left: 4%; }
.fe-2 { top: 5%; right: 6%; }
.fe-3 { bottom: 3%; left: 7%; }
.fe-4 { bottom: 8%; right: 4%; }
.fe-5 { top: 58%; left: 89%; }
.fe-6 { top: 81%; left: 76%; }
.fe-7 { top: 74%; left: 84%; }
.fe-8 { top: 90%; left: 82%; }
.fe-9 { top: 93%; left: 86%; }
.fe-10 { top: 45%; left: 2%; }
.fe-11 { top: 32%; right: 2%; }
.card-wrap { position: relative; z-index: 1; max-width: 420px; margin: 0 auto; }
.card-outer-glow {
  padding: 6px;
  border-radius: 52px 22px 52px 22px;
  background: linear-gradient(135deg,
    var(--bp-accent-light, #fcdae6),
    var(--bp-accent, #fbc1d2) 40%,
    var(--bp-accent, #f7b2c6) 70%,
    var(--bp-accent-light, #fcc8d8));
  box-shadow:
    0 0 30px var(--bp-accent-fade, rgba(243, 156, 182, 0.15)),
    0 0 60px var(--bp-accent-fade, rgba(239, 146, 174, 0.08));
  position: relative;
}
.card {
  background: var(--bp-surface, #fff8fb);
  border-radius: 48px 18px 48px 18px;
  padding: 28px 24px 32px;
  box-shadow:
    inset 0 0 0 1px var(--bp-divider, rgba(255, 228, 231, 0.6)),
    inset 0 0 30px var(--bp-accent-fade, rgba(255, 221, 229, 0.15)),
    0 4px 20px var(--bp-accent-fade, rgba(201, 129, 153, 0.08));
  position: relative;
  overflow: hidden;
}
.card::before {
  content: '';
  position: absolute;
  top: -20%; right: -15%;
  width: 220px; height: 280px;
  background: radial-gradient(circle at 30% 40%, var(--bp-accent-fade, rgba(254, 211, 221, 0.12)), transparent 75%);
  pointer-events: none;
}
.card::after {
  content: '';
  position: absolute;
  bottom: -18%; left: -12%;
  width: 180px; height: 240px;
  background: radial-gradient(circle at 50% 60%, var(--bp-accent-fade, rgba(249, 193, 210, 0.10)), transparent 70%);
  pointer-events: none;
}
.inner-border-pattern {
  position: absolute;
  inset: 10px;
  border-radius: 40px 12px 40px 12px;
  border: 1.5px dashed var(--bp-divider, rgba(226, 166, 183, 0.22));
  pointer-events: none;
}
.inner-border-pattern::before,
.inner-border-pattern::after {
  content: '✿ ✿ ✿';
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  font-size: 12px;
  color: var(--bp-divider, rgba(217, 154, 173, 0.25));
  letter-spacing: 8px;
  background: var(--bp-surface, #fff8fb);
  padding: 0 10px;
}
.inner-border-pattern::before { top: -9px; }
.inner-border-pattern::after { bottom: -9px; }
.top-section { display: flex; align-items: flex-start; gap: 18px; margin-bottom: 20px; position: relative; z-index: 1; }
.cover-wrapper {
  flex-shrink: 0;
  width: 88px; height: 118px;
  border-radius: 18px 6px 18px 6px;
  overflow: hidden;
  background: var(--bp-accent-light, #fde0e8);
  box-shadow:
    0 4px 16px var(--bp-accent-fade, rgba(194, 131, 157, 0.15)),
    inset 0 0 0 1px var(--bp-accent-light, rgba(255, 189, 204, 0.2));
  position: relative;
}
.cover-wrapper img { width: 100%; height: 100%; object-fit: cover; display: block; }
.cover-wrapper .placeholder-icon {
  display: flex; align-items: center; justify-content: center;
  height: 100%; font-size: 38px;
  color: var(--bp-accent, #f5bacb);
  background: var(--bp-accent-light, #fce8ef);
}
.book-meta { flex: 1; min-width: 0; padding-top: 4px; }
.book-name {
  font-size: 24px; font-weight: 720;
  color: var(--bp-text, #4d2e3a);
  letter-spacing: 0.06em; line-height: 1.25;
  word-break: break-word; margin-bottom: 3px;
}
.book-author { font-size: 13px; color: var(--bp-text-muted, #a37183); margin-bottom: 8px; letter-spacing: 0.04em; }
.book-author::before { content: '✎ '; font-size: 15px; color: var(--bp-accent, #d992a7); }
.status-tag {
  display: inline-block;
  font-size: 12px; font-weight: 550;
  padding: 4px 16px; border-radius: 56px;
  background: var(--bp-accent-light, #fad4dd);
  color: var(--bp-text, #723948);
  box-shadow: inset 0 1px 4px var(--bp-accent-fade, rgba(247, 184, 202, 0.2));
  letter-spacing: 0.06em; margin-top: 2px;
  border: 1px solid var(--bp-accent, rgba(245, 177, 196, 0.3));
}
.status-tag.在读 { background: #dbedd5; color: #41663b; }
.status-tag.读完 { background: #d3e2fa; color: #33507a; }
.status-tag.弃文 { background: #fbd3cf; color: #824743; }
.status-tag.待读 { background: #ece3e6; color: #796468; }
.bp-dark .status-tag.在读 { background: #2c4227; color: #b6d9ac; }
.bp-dark .status-tag.读完 { background: #24344f; color: #a8c4ec; }
.bp-dark .status-tag.弃文 { background: #4a2b28; color: #e8b0aa; }
.bp-dark .status-tag.待读 { background: #3a3134; color: #c4b3b8; }
.divider { display: flex; align-items: center; gap: 12px; margin: 18px 0 20px; position: relative; z-index: 1; }
.divider-line {
  flex: 1; height: 2px; border-radius: 4px;
  background: linear-gradient(90deg, transparent, var(--bp-accent, #fbcbd8) 15%, var(--bp-accent, #fbcbd8) 85%, transparent);
}
.divider-icon { font-size: 20px; color: var(--bp-accent, #eaa8bb); letter-spacing: 8px; }
.info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 16px; margin-bottom: 16px; position: relative; z-index: 1; }
.info-item {
  background: var(--bp-surface-variant, rgba(255, 238, 243, 0.45));
  border-radius: 18px 6px 18px 6px;
  padding: 12px 14px 12px 16px;
  border: 1px solid var(--bp-divider, rgba(246, 196, 209, 0.30));
  position: relative; overflow: hidden;
}
.info-item .label {
  font-size: 11px; color: var(--bp-text-muted, #af7f8e);
  letter-spacing: 0.06em; margin-bottom: 4px;
  display: flex; align-items: center; gap: 6px;
}
.info-item .value {
  font-size: 16px; font-weight: 630;
  color: var(--bp-text, #482c36);
  letter-spacing: 0.02em; word-break: break-word;
}
.info-item .value-small { font-size: 14px; font-weight: 540; }
.info-item.full { grid-column: span 2; }
.progress-area { margin: 8px 0 18px; position: relative; z-index: 1; }
.progress-header {
  display: flex; justify-content: space-between;
  font-size: 12px; color: var(--bp-text-muted, #967080);
  margin-bottom: 6px; letter-spacing: 0.02em;
}
.progress-track {
  width: 100%; height: 12px;
  background: var(--bp-surface-variant, #f1d7e0);
  border-radius: 64px; overflow: hidden;
  box-shadow: inset 0 2px 4px var(--bp-accent-fade, rgba(171, 119, 137, 0.08));
  border: 1px solid var(--bp-divider, rgba(238, 188, 202, 0.2));
}
.progress-fill {
  height: 100%; width: 0%;
  background: linear-gradient(90deg,
    var(--bp-accent-light, #f8b2c6),
    var(--bp-accent, #f590ab),
    var(--bp-accent, #f47ca0));
  border-radius: 64px;
  box-shadow: 0 0 10px var(--bp-accent-fade, rgba(241, 133, 170, 0.15));
}
.rating-block { display: flex; align-items: center; justify-content: center; gap: 12px; margin: 14px 0 10px; position: relative; z-index: 1; }
.rating-stars {
  font-size: 28px; letter-spacing: 4px; line-height: 1;
  color: var(--bp-star, #faaa7a);
  filter: drop-shadow(0 2px 8px var(--bp-accent-fade, rgba(244, 164, 116, 0.15)));
}
.rating-number {
  font-size: 18px; font-weight: 320;
  color: var(--bp-text-muted, #8a6270);
  background: var(--bp-surface-variant, rgba(237, 203, 214, 0.25));
  padding: 3px 14px 3px 12px; border-radius: 54px;
  border: 1px solid var(--bp-divider, #eac6d1);
  letter-spacing: 0.04em;
}
.quote-area {
  margin-top: 16px;
  padding: 18px 18px 14px;
  background: var(--bp-surface-variant, rgba(239, 212, 222, 0.14));
  border-radius: 32px 8px 32px 8px;
  border: 1.5px dashed var(--bp-divider, #edc3cf);
  position: relative; z-index: 1;
}
.quote-area::before {
  content: '❝';
  font-size: 32px; color: var(--bp-accent, #f1baca);
  position: absolute; top: -6px; left: 10px;
  opacity: 0.5; line-height: 1;
}
.quote-area::after {
  content: '❞';
  font-size: 32px; color: var(--bp-accent, #f1baca);
  position: absolute; bottom: -6px; right: 10px;
  opacity: 0.3; line-height: 1;
}
.quote-text {
  font-size: 13px; color: var(--bp-text, #5f424d);
  line-height: 1.7; padding-left: 10px;
  font-style: italic; letter-spacing: 0.025em;
  word-break: break-word;
}
.quote-chapter {
  font-size: 11px; color: var(--bp-text-muted, #ad7d8c);
  text-align: right; margin-top: 8px; padding-right: 6px;
}
.quote-chapter::before { content: '📖 '; font-size: 12px; }
.footer-note {
  display: flex; justify-content: space-between; align-items: center;
  margin-top: 22px; font-size: 11px;
  color: var(--bp-text-muted, #b68896);
  position: relative; z-index: 1; padding: 0 4px;
}
.footer-note .left { display: flex; align-items: center; gap: 10px; }
.footer-note .badge {
  background: var(--bp-accent-light, #fbdee8);
  padding: 3px 14px; border-radius: 51px;
  color: var(--bp-text, #764f5e);
  font-weight: 480; font-size: 10px; letter-spacing: 0.05em;
  box-shadow: inset 0 0 0 1px var(--bp-accent-light, #f6cad6);
}
.footer-note .date {
  background: var(--bp-surface-variant, rgba(234, 204, 214, 0.15));
  padding: 3px 14px; border-radius: 57px;
  border: 1px solid var(--bp-divider, rgba(214, 175, 189, 0.2));
}
</style>
</head>
<body>
  <div class="float-element fe-1"><div class="float-star"></div></div>
  <div class="float-element fe-2"><div class="float-heart"></div></div>
  <div class="float-element fe-3"><div class="float-diamond"></div></div>
  <div class="float-element fe-4"><div class="float-circle"></div></div>
  <div class="float-element fe-5"><div class="float-dot-group"><span class="float-dot"></span><span class="float-dot"></span><span class="float-dot"></span></div></div>
  <div class="float-element fe-6"><div class="float-star" style="width:24px;height:24px;opacity:0.5;"></div></div>
  <div class="float-element fe-7"><div class="float-heart" style="width:26px;height:24px;transform:rotate(18deg);opacity:0.4;"></div></div>
  <div class="float-element fe-8"><div class="float-diamond" style="width:22px;height:36px;opacity:0.4;"></div></div>
  <div class="float-element fe-9"><div class="float-circle" style="width:24px;height:24px;"></div></div>
  <div class="float-element fe-10"><div class="float-heart" style="width:20px;height:18px;transform:rotate(40deg);opacity:0.3;"></div></div>
  <div class="float-element fe-11"><div class="float-star" style="width:18px;height:18px;opacity:0.35;"></div></div>
  <div class="card-wrap" data-bp-capture>
    <div class="card-outer-glow">
      <div class="card">
        <div class="inner-border-pattern"></div>
        <div class="top-section">
          <div class="cover-wrapper">
            <img src="{{coverUrl}}" alt="封面" onerror="this.style.display='none'">
          </div>
          <div class="book-meta">
            <div class="book-name">{{bookName}}</div>
            <div class="book-author">{{author}}</div>
            <span class="status-tag {{readingStatusText}}">{{readingStatusText}}</span>
          </div>
        </div>
        <div class="divider">
          <span class="divider-line"></span>
          <span class="divider-icon">♡ ✿ ♡</span>
          <span class="divider-line"></span>
        </div>
        <div class="info-grid">
          <div class="info-item"><div class="label">🌟 进度</div><div class="value">{{readingProgress}}</div></div>
          <div class="info-item"><div class="label">🍡 已读</div><div class="value value-small">{{readChapters}} 章</div></div>
          <div class="info-item"><div class="label">⏳ 时长</div><div class="value value-small">{{totalReadTime}}</div></div>
          <div class="info-item"><div class="label">💬 书摘</div><div class="value value-small">{{annotationCount}} 条</div></div>
          <div class="info-item full"><div class="label">🏷️ 标签</div><div class="value value-small">{{tags}}</div></div>
        </div>
        <div class="progress-area">
          <div class="progress-header">
            <span>🌟 阅读进度</span>
            <span>{{readChapters}} / {{totalChapterNum}} 章</span>
          </div>
          <div class="progress-track">
            <div class="progress-fill" style="width:{{readingProgress}};"></div>
          </div>
        </div>
        <div class="rating-block">
          <span class="rating-stars">{{ratingStars}}</span>
          <span class="rating-number">{{rating}} / {{ratingMax}}</span>
        </div>
        <div class="quote-area">
          <div class="quote-text">{{reviewContent}}</div>
          <div class="quote-chapter">{{latestAnnotationChapter}}</div>
        </div>
        <div class="footer-note">
          <div class="left">
            <span class="badge">✦ {{readIterationText}}</span>
            <span>🧁 {{wordCount}}</span>
          </div>
          <div class="date">🌸 {{firstReadTime}}</div>
        </div>
      </div>
    </div>
  </div>
</body>
</html>
""".trimIndent()
}
