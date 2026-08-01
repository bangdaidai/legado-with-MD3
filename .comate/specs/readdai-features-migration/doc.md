# readdai → legado-with-MD3 特有功能移植需求说明

> 功能名（feature_name）：`readdai-features-migration`
> 源项目：`c:\Users\bangdaidai\Desktop\新建文件夹\readdai`
> 目标项目：`c:\Users\bangdaidai\Desktop\新建文件夹\legado-with-MD3`
> 目标项目当前 `AppDatabase` 版本：v85（`app/src/main/java/io/legado/app/data/AppDatabase.kt`）
> 目标项目分层约定：`data / domain(gateway+usecase+model) / ui(Compose+Navigation3)` + Koin 注入（详见 [`AGENTS.md`](../../../AGENTS.md)）

---

## 0. 总览

### 0.1 对比结论

在把 readdai（35 张表，AppDatabase v109）与 legado-with-MD3（55 张表，AppDatabase v85，功能重心为 AI 翻译 / 漫画 / 知识图谱 / 首页模块）对齐分析后，识别出 **12 项 readdai 独有 / 领先** 的能力，本次需求需要将其移植到 MD3：

| 编号 | 需求 | MD3 现状 | 复杂度 |
| --- | --- | --- | --- |
| R1 | 藏书票（Bookplate） HTML 模板系统 | ABSENT | 高 |
| R2 | 阅读凭证（ReadingTicket） | ABSENT | 中 |
| R3 | 独立的书评（BookReview）实体与管理 | PARTIAL（仅作 `ReadingMemory.review` 字段） | 中 |
| R4 | 独立的书摘 / 批注（BookAnnotation）实体与全书浏览 | PARTIAL（嵌入 `ReadingMemory.excerptsJson`） | 中 |
| R5 | 数据可见性设置（DataVisibilitySettings） | ABSENT | 低 |
| R6 | RAG 向量检索（Vector / Chunk / Embedding） | ABSENT | 高 |
| R7 | AI Skills 技能系统 | PARTIAL（仅设置页占位） | 高 |
| R8 | MCP（Model Context Protocol）客户端 | ABSENT | 高 |
| R9 | Tavily 联网搜索工具 | ABSENT | 中 |
| R10 | AI 日志（AiLogManager / AiLogActivity） | ABSENT | 低 |
| R11 | 视频播放器 + 弹幕（GSYVideoPlayer + DanmakuFlameMaster） | ABSENT | 高 |
| R12 | TV 观看记录接入（WatchRecord 广播 + Provider） | ABSENT | 低 |
| R13 | 阅读刷次（ReadIteration）与阅读状态分组 | ABSENT | 中 |
| R14 | 阅读热力图缓存 & 高级统计（HeatmapCache / 覆盖日历 / 时段分布） | PARTIAL（有基础热力图组件，缺封面日历、时段分布、作者/书籍 Top 榜、缓存管理） | 中 |

> 注：R13、R14 与 R2、R5 强耦合（藏书票依赖阅读凭证与统计数据），因此归入统一批次评估。

### 0.2 移植总策略

- **代码风格**：全面适配 MD3 架构（Compose UI + `domain/gateway/usecase` + Koin），不保留 readdai 中的 `object` 单例式 Helper；将其重写为 `interface + Impl + @Single` 注入。
- **数据库**：本文档暂不锁定 Migration 版本号，仅列出必须新增的 Entity / DAO。实施阶段再决定"一次跳版"或"分版本递增"。
- **UI 迁移**：readdai 的 XML+Activity 全部改写为 Compose Screen + Navigation3 Key，接入 `MainNavGraph.kt` / `MainNavKey.kt`。
- **触发点**：readdai 中散落在 ReadBookActivity / ReadingMemoryDetailActivity 的触发流程，在 MD3 中需要在 `ReadBookViewModel`、`ReadingMemoryDetailViewModel` 里通过 `UseCase` 触发。

### 0.3 影响面清单

主目录（新增 / 修改的顶层文件）：

| 修改类型 | 路径 |
| --- | --- |
| ADD | `app/src/main/java/io/legado/app/data/entities/` 若干新增 Entity |
| ADD | `app/src/main/java/io/legado/app/data/dao/` 若干新增 DAO |
| MOD | `app/src/main/java/io/legado/app/data/AppDatabase.kt`（entities 数组 + version + Migration） |
| MOD | `app/src/main/java/io/legado/app/data/DatabaseMigrations.kt` |
| ADD | `app/src/main/java/io/legado/app/domain/gateway/` 若干 gateway |
| ADD | `app/src/main/java/io/legado/app/domain/usecase/` 若干 UseCase |
| ADD | `app/src/main/java/io/legado/app/data/repository/` 若干 Repository 实现 |
| ADD | `app/src/main/java/io/legado/app/ui/book/bookplate/`、`ui/book/vector/`、`ui/book/review/`、`ui/book/annotation/`、`ui/ai/`、`ui/video/` 等 Compose 目录 |
| MOD | `app/src/main/java/io/legado/app/di/appModule.kt`、`appDatabaseModule.kt`（注入新组件） |
| MOD | `app/src/main/java/io/legado/app/ui/main/MainNavKey.kt`、`MainNavGraph.kt`（新路由） |
| MOD | `app/src/main/AndroidManifest.xml`（新增 Activity/Service/Receiver/Provider） |
| MOD | `app/src/main/java/io/legado/app/constant/PreferKey.kt` |
| MOD | `gradle/libs.versions.toml` + `app/build.gradle.kts`（新增视频/弹幕依赖） |

---

## R1 藏书票（Bookplate）HTML 模板系统

### 1.1 场景与处理逻辑

用户读完一本书 / 打开阅读记忆详情 / 保存一条书摘时，系统生成一张"藏书票"图片（Bitmap），包含书名、作者、评分、阅读时长、书摘、主角、标签、书源等信息，供用户预览、保存、分享到相册或对外发送。

- 触发点 A：阅读页 `ReadBookActivity` 标记完结 → 弹出评分 + 藏书票预览。
- 触发点 B：`ReadingMemoryDetailScreen` 菜单"生成藏书票"。
- 触发点 C：`BookAnnotationDialog` / 书摘详情菜单"生成书摘票"。
- 触发点 D：`ReadRecordOverviewScreen`（统计页）菜单"生成统计藏书票"。
- 触发点 E：`MyScreen` → "藏书票模板管理" 入口。

模板系统：内置 5 套 HTML 模板（默认 / 极简 / 古典 / 现代 / 统计款），支持用户创建自定义模板（≥ 50 个）。模板存储在 Room 表 `bookplateTemplates`。渲染通过隐藏 WebView 离屏加载 HTML → `capturePicture()` → Bitmap。

### 1.2 架构与技术方案

层次 | 组件（MD3 命名） | 职责
--- | --- | ---
data | `BookplateTemplate`（Room 实体）、`BookplateTemplateDao` | 模板持久化
data | `BookplateData`（纯 VO，60+ 字段） | 渲染入参 DTO
data | `BookplateTemplateRepositoryImpl` | DAO 封装
domain | `gateway/BookplateTemplateGateway` | 模板 CRUD 接口
domain | `gateway/BookplateRenderGateway` | HTML→Bitmap 渲染接口
domain | `usecase/GenerateBookplateUseCase` | 组合数据构建 + 渲染
domain | `usecase/EnsureBuiltinBookplateTemplatesUseCase` | 冷启动确保内置模板存在
domain | `model/BookplateData`、`BookplateVisibility` | 数据可见性开关的模型
ui | `ui/book/bookplate/BookplateManageScreen.kt`（Compose） | 模板列表 + Tab 分组
ui | `ui/book/bookplate/BookplateEditScreen.kt` | HTML 编辑 + 变量点击插入
ui | `ui/widget/bookplate/BookplatePreviewSheet.kt` | 预览 / 分享 BottomSheet
ui | `ui/book/read/page/provider/BookplateCanvasDrawer.kt` | 保留经典 Canvas 版本（离屏 fallback）

### 1.3 受影响文件

**新增：**

- `app/src/main/java/io/legado/app/data/entities/BookplateTemplate.kt`
- `app/src/main/java/io/legado/app/data/entities/BookplateData.kt`（POKO，非 @Entity）
- `app/src/main/java/io/legado/app/data/dao/BookplateTemplateDao.kt`
- `app/src/main/java/io/legado/app/data/repository/BookplateTemplateRepositoryImpl.kt`
- `app/src/main/java/io/legado/app/domain/gateway/BookplateTemplateGateway.kt`
- `app/src/main/java/io/legado/app/domain/gateway/BookplateRenderGateway.kt`
- `app/src/main/java/io/legado/app/domain/usecase/GenerateBookplateUseCase.kt`
- `app/src/main/java/io/legado/app/domain/usecase/EnsureBuiltinBookplateTemplatesUseCase.kt`
- `app/src/main/java/io/legado/app/help/book/BookplateHtmlRenderer.kt`（WebView 渲染基础设施）
- `app/src/main/java/io/legado/app/help/book/BookplateDataBuilder.kt`
- `app/src/main/java/io/legado/app/ui/book/bookplate/BookplateManageScreen.kt`
- `app/src/main/java/io/legado/app/ui/book/bookplate/BookplateEditScreen.kt`
- `app/src/main/java/io/legado/app/ui/book/bookplate/BookplateManageViewModel.kt`
- `app/src/main/java/io/legado/app/ui/widget/bookplate/BookplatePreviewSheet.kt`
- `app/src/main/assets/bookplate/template_default.html`（迁移自 readdai）
- `app/src/main/assets/bookplate/template_minimal.html`
- `app/src/main/assets/bookplate/template_classic.html`
- `app/src/main/assets/bookplate/template_modern.html`
- `app/src/main/assets/bookplate/template_statistics.html`

**修改：**

- `AppDatabase.kt`（entities 增加 `BookplateTemplate::class`，`abstract val bookplateTemplateDao`）
- `MainNavKey.kt`（新增 `MainRouteBookplateManage`、`MainRouteBookplateEdit(id)`）
- `MainNavGraph.kt`（挂接路由）
- `di/appModule.kt`、`di/appDatabaseModule.kt`（Repository / Gateway / UseCase Koin 注册）
- `PreferKey.kt`（新增 `selectedBookplateTemplateId`、`bpTemplatesInitialized`、`bpShow*` 系列）
- `AndroidManifest.xml`（如保留 Activity 版本；纯 Compose 则无需）

### 1.4 实现细节

**HTML 变量替换规则**（41 个变量，共 9 类）：

```
类别            变量示例                        默认占位符
基本信息(11)    {bookName} {author} {intro}     "未知"
进度状态(7)     {progress} {status}             0% / "阅读中"
阅读统计(8)     {readTime} {readCount}          "0 分钟"
日期时间(6)     {finishDate}                    "____/__/__"
评分书评(4)     {rating} {reviewContent}        "☆☆☆☆☆"
书摘想法(5)     {excerpts}                      "暂无书摘"
主角(1)         {protagonists}                  "未提取"
标签(2)         {tags} {tagCount}               "无"
书源(2)         {sourceName} {sourceUrl}        "本地"
```

**渲染流程（BookplateHtmlRenderer）**：

```kotlin
suspend fun render(
    context: Context,
    template: BookplateTemplate,
    data: BookplateData,
    visibility: BookplateVisibility
): Bitmap = withContext(Dispatchers.Main) {
    val html = template.htmlContent
        .replaceVariables(data, visibility)   // 变量替换 + 可见性过滤
    val webView = obtainOffscreenWebView(context)
    webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null)
    webView.awaitLoad(timeoutMs = 5000)       // 超时 5s
    webView.width = 640                       // 固定宽度 640px
    val picture = webView.capturePicture()
    picture.toBitmap()
}
```

失败 fallback：切回 `BookplateCanvasDrawer` 绘制经典风格。

### 1.5 边界与异常

- 模板 id = 0 表示"经典风格"，不走 HTML 渲染。
- 变量大小写敏感；未识别变量原样保留。
- 简介截断 500 字，书摘条目 200 字上限。
- Bitmap 缓存：LRU 5 条，key = `templateId + bookUrl + updateTime`。
- 渲染必须放到主线程（WebView 限制），但外层 UseCase 在 `Dispatchers.IO` suspend 调度。
- WebView 需在 Application 冷启动时预热一次以规避首屏渲染卡顿。

### 1.6 期望结果

- 用户在阅读完结时可以立刻预览、保存、分享藏书票。
- 用户可以在"我的 → 藏书票模板管理"里增删改自定义模板并选择当前模板。
- 卸载或清除数据后再次启动，内置模板自动重建。

---

## R2 阅读凭证（ReadingTicket）

### 2.1 场景

对每本书维护一张"阅读凭证"记录（完结时间、总时长、评分、第几刷、章节完成度），供藏书票 / 阅读记忆使用；同时作为"多刷"、"完成书籍列表"的数据基础。

### 2.2 影响面

- 新增 Entity：`ReadingTicket`（主键 `bookUrl`，字段：bookName / author / totalReadTime / readCount / rating / finishTime / firstReadTime / lastReadTime / completedChapters / totalChapters / createTime）。
- 新增 DAO：`ReadingTicketDao`（insert、getByBookUrl、addReadTime、setFinishTime、updateRating、getFinishedBooks、getMultiReadBooks）。
- 新增 UseCase：`UpdateReadingTicketUseCase`、`MarkBookAsFinishedUseCase`、`GetReadingTicketUseCase`。
- 修改 `ReadBookViewModel`：在阅读结束、章节推进、进度归零时联动调用 UseCase。
- 新增 Compose 组件：`ui/widget/bookplate/ReadingTicketView.kt`（票据样式，可作为藏书票组成元素单独展示）。
- 新增 PreferKey：`showReadingTicket`、`readingTicketPosition`。

---

## R3 独立的书评（BookReview）

### 3.1 场景

readdai 允许一本书拥有多条独立书评（区别于 `ReadingMemory.review` 只有一条），可编辑、删除、按时间排序展示。

### 3.2 影响面

- 新增 Entity：`BookReview`（id / bookUrl / bookName / bookAuthor / reviewContent / createTime / updateTime）。
- 新增 DAO：`BookReviewDao`（getByBook、insert、update、delete、flowAll、deleteByBook）。
- 新增 Repository：`BookReviewRepositoryImpl` + `domain/gateway/BookReviewGateway`。
- 新增 UseCase：`GetBookReviewsUseCase`、`SaveBookReviewUseCase`、`DeleteBookReviewUseCase`。
- 新增 UI：`ui/book/review/BookReviewSheet.kt`（BottomSheet 编辑）、`AllReviewsScreen.kt`（全书评浏览）。
- 触发点接入：`ReadingMemoryDetailScreen`、`ReadBookViewModel` 中"写书评"菜单。
- `ReadingMemory.review` 字段作为"最近一条书评"缓存保留，写入新书评时同步刷新。

---

## R4 独立的书摘 / 批注（BookAnnotation）

### 4.1 场景

readdai 将阅读页选中的段落 + 批注独立成 `bookAnnotations` 表，支持章节内跳转、跨书检索、生成"书摘藏书票"。当前 MD3 只把书摘放在 `ReadingMemory.excerptsJson`，无法跨书聚合、无独立编辑体验。

### 4.2 影响面

- 新增 Entity：`BookAnnotation`（time PK / bookName / bookAuthor / chapterIndex / chapterPos / chapterName / bookText / content / note，`Index(bookName, bookAuthor)`）。
- 新增 DAO：`BookAnnotationDao`（flowAll、flowByBook、getByBook、insert、update、delete、deleteByBook、getCount）。
- 新增 Gateway：`BookAnnotationGateway` + Repository。
- 新增 UseCase：`SaveAnnotationUseCase`、`DeleteAnnotationUseCase`、`GetAnnotationsByBookUseCase`、`SearchAnnotationsUseCase`。
- 新增 UI：
  - `ui/book/annotation/AllAnnotationsScreen.kt`（跨书聚合）
  - `ui/book/annotation/BookAnnotationsScreen.kt`（单书列表）
  - `ui/book/annotation/AnnotationEditSheet.kt`（新增/编辑）
- 触发点：阅读页选择文本 → "加入书摘"；`BookAnnotationsScreen` 点击条目 → 跳转 `ReadBookScreen(bookUrl, chapterIndex, chapterPos)`。
- `ReadingMemory.excerptsJson` 保留，作为快照缓存；生成藏书票时从 `BookAnnotationDao` 拉当前书最新条目。

---

## R5 数据可见性设置（DataVisibilitySettings）

### 5.1 场景

用户在设置里控制藏书票 / 阅读记忆展示哪些区块（基本信息、进度、统计、评分、书摘、主角、标签、书源、排名 共 9 个开关）。

### 5.2 影响面

- 新增 Compose 页面：`ui/config/DataVisibilityScreen.kt`。
- 新增 model：`domain/model/BookplateVisibility.kt`（data class + defaults）。
- 新增 gateway：`BookplateVisibilityGateway`（读写 SharedPreferences DataStore）。
- 新增 PreferKey：`bpShowBasicInfo`、`bpShowProgress`、`bpShowStatistics`、`bpShowRatingReview`、`bpShowAnnotation`、`bpShowProtagonist`、`bpShowTags`、`bpShowSource`、`bpShowRank`（默认全部 true）。
- 入口挂在 `SettingsScreen`（现有 "其它设置" 分组）。

---

## R6 RAG 向量检索

### 6.1 场景

对整本书进行分块（chunk）+ 嵌入（embedding）+ 存库，用户在 AI 聊天中问"书里的某某情节"时，先做语义检索 + BM25 混合召回（RRF 融合）→ 拼装 context → 传给 LLM。

支持 Embedding 提供商：OpenAI / SiliconFlow / 阿里云 / DeepSeek / Ollama。

### 6.2 架构

层次 | 组件 | 职责
--- | --- | ---
data | `VectorEntity` / `ChunkEntity` / `VectorizedBookEntity`（3 个 @Entity） | 向量、分块、已向量化书籍
data | `VectorDao` / `ChunkDao` / `VectorizedBookDao` | 数据库访问（在同一文件 `VectorDao.kt`）
domain | `gateway/VectorSearchGateway` | 混合检索接口
domain | `gateway/EmbeddingGateway` | 调用远端嵌入服务
domain | `model/VectorConfig`、`EmbeddingModel`、`TextChunk`、`SearchResult` | 领域模型
domain | `usecase/VectorizeBookUseCase`、`SemanticSearchUseCase`、`DeleteBookVectorsUseCase` | 用例
help | `help/ai/rag/TextChunker.kt` | 分块（保留 readdai 原逻辑，object → class + @Single） |
help | `help/ai/rag/VectorMath.kt` | cosine 相似度、RRF 融合 |
ui | `ui/book/vector/VectorBooksScreen.kt` | 已向量化书籍列表 + 进度 |
ui | `ui/config/VectorSettingsScreen.kt` | Provider / Model / apiKey / chunkSize 设置 |

### 6.3 影响面

- 新增 3 张表；`AppDatabase.entities` 增加对应类；新增 DAO abstract。
- 新增 `PreferKey`：`aiVectorEnabled` / `aiVectorProvider` / `aiVectorModel` / `aiVectorApiKey` / `aiVectorBaseUrl` / `aiVectorChunkSize` / `aiVectorChunkOverlap` / `aiVectorBatchSize`。
- 新增 Navigation Key：`MainRouteVectorBooks`、`MainRouteVectorSettings`。
- AI Chat 集成：`AiChatGenerationUseCase` 前置 `SemanticSearchUseCase`，把 topK chunks 注入 system prompt。

### 6.4 边界

- RagDatabase **不是独立 Room DB**，与 AppDatabase 共库；避免多库事务复杂度。
- Embedding 向量以字符串序列化存 `VectorEntity.embedding`（float[] JSON），加载时反序列化。
- 混合检索使用 RRF（Reciprocal Rank Fusion）：`score = 1/(k+rank_vec) + 1/(k+rank_bm25)`。
- 缓存：`VectorSearchService` 内建 LruCache（大小 32、TTL 5 分钟）。

---

## R7 AI Skills 技能系统

### 7.1 场景

MD3 设置里已有"技能"占位（`R.string.ai_skills`、`R.string.ai_new_skill`），点击无响应。需要落地 readdai 的 SkillManager：一个技能 = 触发词 + 指令模板 + 变量映射 + 是否使用工具。

用户在 AI 聊天窗口输入触发词（如 `/翻译`）→ SkillManager 解析并注入 prompt。

### 7.2 影响面

- 新增 Entity：`AiSkill`（id / name / triggerWord / instruction / variables / enabledTools / builtin / createTime / updateTime）。
- 新增 DAO：`AiSkillDao`。
- 新增 Gateway：`AiSkillGateway` + Repository。
- 新增 UseCase：`ResolveSkillUseCase`（把用户输入解析成实际 prompt）。
- 修改：`AiConfigScreen.kt` L150-156 空 `onClick={}` → 跳转 `MainRouteAiSkillManage`。
- 新增 Compose：`ui/config/ai/AiSkillManageScreen.kt`、`AiSkillEditScreen.kt`。
- 修改 `AiChatViewModel`：`sendMessage(text)` 前调用 `ResolveSkillUseCase(text)`。

---

## R8 MCP（Model Context Protocol）客户端

### 8.1 场景

允许接入符合 MCP 协议的外部工具服务器（stdio / SSE / HTTP transport），把远端工具暴露给 AI 作为 function-call 候选。

### 8.2 影响面

- 新增 `help/ai/mcp/AiMcpClient.kt`（重写为 class + interface；suspend API）。
- 新增 model：`McpServerConfig`、`McpTool`、`McpTransport`。
- 新增 Entity + DAO：`McpServer`（存服务器配置：id / name / transport / command / url / envJson / enabled）。
- 新增 Gateway：`McpGateway`（listTools / callTool / connect / disconnect）。
- 新增 UseCase：`CallMcpToolUseCase`、`SyncMcpToolsUseCase`。
- AI 工具注册：`AiToolGateway` 在获取工具列表时叠加 `McpGateway.listTools()` 的结果。
- 新增 Compose：`ui/config/ai/McpServersScreen.kt`。

### 8.3 边界

- 至少支持 SSE 与 HTTP transport；stdio 可选（Android 上多数场景用不上）。
- 服务器连接失败不影响本地工具；错误以 log 上报 `AiLogManager`（R10）。

---

## R9 Tavily 联网搜索工具

### 9.1 场景

作为 AI 工具（function tool）之一，允许 LLM 主动调用 Tavily API 联网搜索。区别于 R8 中 OpenAI Responses API 自带的 `web_search_call`：Tavily 是可插拔的第三方工具，跨 provider 通用（Claude / Gemini 也能用）。

### 9.2 影响面

- 新增 `help/ai/tools/TavilyTool.kt`（实现 `AiTool` 接口）。
- 新增 PreferKey：`aiTavilyEnabled`、`aiTavilyApiKey`、`aiTavilyMaxResults`。
- 修改 `AiConfigScreen.kt` 新增开关 + apiKey 输入。
- 工具在 `AiToolGateway.getAllTools()` 中根据 `aiTavilyEnabled` 动态注入。
- API endpoint：`https://api.tavily.com/search`（POST JSON）。

---

## R10 AI 日志（AiLogManager / AiLogActivity）

### 10.1 场景

统一记录所有 AI 请求 / 响应 / 工具调用 / MCP 调用日志，供用户排障。当前 MD3 只有 `AiChatMessage`（聊天消息），无请求级日志。

### 10.2 影响面

- 新增 `help/ai/AiLogManager.kt`（内存环形缓冲区 + 可选文件落盘）。
- 新增 model：`AiLogEntry`（timestamp / level / tag / provider / model / prompt / response / durationMs / error）。
- 新增 Compose：`ui/ai/AiLogScreen.kt`（列表 + 过滤 + 清空 + 导出）。
- 新增 Navigation Key：`MainRouteAiLog`。
- 修改：所有 AI Handler（`OpenAiChatHandler`、`AnthropicHandler`、`GeminiHandler`、`OpenAiResponsesHandler`）在请求前后调 `AiLogManager.log(...)`。
- 入口：`AiConfigScreen` 增"AI 日志"入口。

---

## R11 视频播放器 + 弹幕

### 11.1 场景

支持视频类书源（漫画播放的进阶）：全屏视频播放、集列表、播放速度、悬浮小窗、弹幕加载与显示。

### 11.2 依赖引入

`gradle/libs.versions.toml`：

```toml
[versions]
gsyvideoplayer = "11.3.0"
danmaku = "0.9.25"

[libraries]
gsyVideoPlayer-java = { module = "io.github.carguo:gsyvideoplayer-java", version.ref = "gsyvideoplayer" }
gsyVideoPlayer-exo2 = { module = "io.github.carguo:gsyvideoplayer-exo2", version.ref = "gsyvideoplayer" }
danmakuFlameMaster = { module = "com.github.CarGuo.DanmakuFlameMaster:DanmakuFlameMaster", version.ref = "danmaku" }
```

`app/build.gradle.kts`：

```kotlin
implementation(libs.gsyVideoPlayer.java)
implementation(libs.gsyVideoPlayer.exo2)
implementation(libs.danmakuFlameMaster)
```

### 11.3 影响面

- 新增 `model/VideoPlay.kt`（视频播放状态单例 → 改造成 `class VideoPlaybackController` + Koin）。
- 新增 `service/VideoPlayService.kt`（前台 Service，`foregroundServiceType="mediaPlayback"`，悬浮窗 FloatingPlayer）。
- 新增 Activity：`ui/video/VideoPlayerActivity.kt`（保留 XML/View 版本，因 GSYVideoPlayer 是 View 实现）。
- 新增 XML 布局：12 个 layout 文件（见附录 A）。
- Manifest 新增 Activity + Service 注册（see doc §12.3.2）。
- 视频章节数据结构复用现有 `BookChapter`，通过 `BookType` 区分。

### 11.4 边界

- GSYVideoPlayer 是传统 View 体系；在 Compose 主体中使用 `AndroidView` 包裹或独立 Activity。
- 弹幕文件（xml / json）通过书源规则获取，落 `book.durChapterIndex` 关联。
- 悬浮窗需要 `SYSTEM_ALERT_WINDOW` 权限；MD3 已在 Manifest 中，需复核。

---

## R12 TV 观看记录接入

### 12.1 场景

第三方 TV / 影视 App 通过广播把观看记录写入本 App（跨 App 集成，Provider + Receiver 双向）。

### 12.2 影响面

- 新增 `receiver/WatchRecordReceiver.kt`（`android.content.BroadcastReceiver`）。
- 新增 `api/WatchRecordProvider.kt`（`ContentProvider`）。
- Manifest 注册：

```xml
<provider
    android:name=".api.WatchRecordProvider"
    android:authorities="io.legado.app.watchRecordProvider"
    android:exported="true" />

<receiver android:name=".receiver.WatchRecordReceiver" android:exported="true">
    <intent-filter>
        <action android:name="io.legado.app.action.ADD_WATCH_RECORD" />
    </intent-filter>
</receiver>
```

- 数据写入到 `ReadRecord` / `ReadSession`（按 BookType.video / audio 分类）。

---

## R13 阅读刷次（ReadIteration）与阅读状态分组

### 13.1 场景

一本书可能被读多遍（一刷、二刷、三刷）。用户在阅读记忆里按状态分组浏览（未开始 / 阅读中 / 已完结 / 已弃读）。

### 13.2 影响面

- 新增 `constant/ReadingStatus.kt`（enum：NOT_STARTED / READING / FINISHED / ABANDONED）。
- 新增 `help/book/ReadIterationHelper.kt` → 重写为 `interface ReadIterationGateway` + Impl。
- 新增 `help/book/ReadingStatusGroupHelper.kt` → `ReadingStatusGroupUseCase`。
- 修改 `Book` 实体：追加 `readCount: Int`、`readStatus: Int`；或以 `ReadingTicket.readCount`（R2）为主。
- 修改 `ui/main/bookshelf/`：分组过滤器接入 status。

---

## R14 阅读热力图缓存与高级统计

### 14.1 场景

MD3 已有 Compose 版热力图组件（`ReadRecordHeatmap.kt` / `HeatmapCalendarComponents.kt`），但缺失：

1. **缓存管理**：readdai 使用 `StatisticsCacheManager` 缓存月度 / 年度热力图数据，避免每次进入统计页重复聚合大数据集。
2. **覆盖日历（Cover Calendar）**：每天显示当日阅读时长最长书籍的封面。
3. **时段分布图**：按周几 / 按月度 / 按小时聚合。
4. **书籍 / 作者 Top 榜**：`BookReadTimeRank` / `AuthorReadTime`。
5. **多类型区分**：按 BookType 分别统计（文字书 / 漫画 / 音频 / 视频）。

### 14.2 影响面

- 新增 Entity（纯 POKO，返回体，不是 @Entity）：`HeatmapDayData`、`CoverCalendarDayData`、`DailyLongestReadCover`、`TimeDistribution`、`AuthorReadTime`、`BookReadTimeRank`。
- 修改 `ReadRecordDao`（或新增 `ReadSessionDao`）：追加 30+ 聚合查询（对齐 readdai `ReadSessionDao.kt` 1139 行的 SQL）：
  - `getMonthlyReadHeatmapData(month)`、`getYearlyReadHeatmapData(year)`
  - `getMonthlyDailyLongestReadCovers(month)` / `ByType`
  - `getDayOfWeekDistribution()` / `getMonthDistributionInRange()`
  - `getAuthorReadTimeTop5()` / `getBookReadTimeTop10()` 等
- 新增 `help/statistics/StatisticsCacheManager.kt`（Koin `@Single`）。
- 新增 Compose 组件：`CoverCalendarSection`、`TimeDistributionBarChart`、`TopReadingListCard`（书籍 / 作者双榜）。
- 修改 `ReadRecordOverviewScreen.kt`、`ReadRecordOverviewViewModel.kt`：叠加新数据流。

### 14.3 边界

- 查询涉及大表（`readSession` 长期累积会到十万级），必须走索引 + 缓存。
- 缓存 key = 类型 + 月份 / 年份，过期策略：写入 `ReadSession` 时按 date 失效对应 key。

---

## 附录 A：视频模块 layout 文件清单

`activity_video_player.xml`、`floating_video_player.xml`、`dialog_video_settings.xml`、`item_video_chapter_volume.xml`、`item_video_chapter.xml`、`switch_video_dialog_item.xml`、`switch_speed_video_dialog.xml`、`switch_episode_video_dialog.xml`、`video_player_control.xml`、`video_layout_floating.xml`、`video_layout_controller_full.xml`、`video_layout_controller.xml`。

---

## 附录 B：新增 PreferKey 一览

```
# 藏书票
selectedBookplateTemplateId, selectedStatisticsTemplateId
showBookplate, showReadingTicket, readingTicketPosition
bpTemplatesInitialized
bpShowBasicInfo, bpShowProgress, bpShowStatistics, bpShowRatingReview,
bpShowAnnotation, bpShowProtagonist, bpShowTags, bpShowSource, bpShowRank

# 向量检索
aiVectorEnabled, aiVectorProvider, aiVectorModel, aiVectorApiKey,
aiVectorBaseUrl, aiVectorChunkSize, aiVectorChunkOverlap, aiVectorBatchSize

# AI 扩展
aiTavilyEnabled, aiTavilyApiKey, aiTavilyMaxResults
aiMcpEnabled
aiLogEnabled, aiLogRetentionDays
```

---

## 附录 C：新增 Manifest 注册（若保留传统 Activity）

```xml
<!-- 视频播放器 -->
<activity
    android:name=".ui.video.VideoPlayerActivity"
    android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|uiMode"
    android:launchMode="singleTask" />
<service
    android:name=".service.VideoPlayService"
    android:foregroundServiceType="mediaPlayback" />

<!-- TV 观看记录 -->
<provider
    android:name=".api.WatchRecordProvider"
    android:authorities="${applicationId}.watchRecordProvider"
    android:exported="true" />
<receiver android:name=".receiver.WatchRecordReceiver" android:exported="true">
    <intent-filter>
        <action android:name="io.legado.app.action.ADD_WATCH_RECORD" />
    </intent-filter>
</receiver>
```

Compose 版本的功能（藏书票 / RAG / 书评 / 书摘 / AI 日志 / AI Skills / MCP）**无需** Manifest 新增，统一通过 `MainNavKey` 挂载。

---

## 附录 D：数据流路径示意

**藏书票生成**（触发点 A 为例）：

```
ReadBookScreen (Compose)
   ↓ user marks book finished
ReadBookViewModel.markFinished()
   ↓
MarkBookAsFinishedUseCase
   ↓ 1) UpdateReadingTicketUseCase
   ↓ 2) GenerateBookplateUseCase
        ↓
        BookplateDataBuilder.build(book)
          ├── BookAnnotationDao.getByBook(...)
          ├── BookReviewDao.getByBook(...)
          ├── ReadSessionDao.getBookReadTime(...)
          ├── BookProtagonistDao.getByBook(...)
          └── BookTagDao.getByBook(...)
        ↓
        BookplateHtmlRenderer.render(template, data, visibility)
   ↓
BookplatePreviewSheet.show(bitmap)
```

**AI 聊天 + RAG**：

```
User input → AiChatViewModel.sendMessage(text)
   ↓ ResolveSkillUseCase(text)         # R7
   ↓ SemanticSearchUseCase(bookUrl, query)  # R6
        ├── EmbeddingGateway.embed(query)
        ├── VectorDao.searchByCosine(...)
        ├── ChunkDao.searchByBm25(...)
        └── RRF 融合 → topK chunks
   ↓ build prompt with context
   ↓ AiChatGenerationUseCase
        ├── (tools) = local tools + Tavily(R9) + MCP(R8)
        ├── selected Handler.stream(...)
        └── AiLogManager.log(...)  # R10
   ↓ chat stream → UI
```

---

## 期望结果

按上述 14 个需求分批实施后，MD3 项目将获得 readdai 全部差异化能力，同时保持自身的 Clean Architecture + Compose + Navigation3 一致性。用户可以在同一个 App 内使用：

- 完整的藏书票 / 阅读凭证 / 独立书评 / 独立书摘体系；
- 数据可见性精细控制；
- AI 聊天叠加 RAG 检索、Skills、MCP、Tavily 联网搜索，且有完整调用日志；
- 视频类书源播放（含弹幕）；
- 外部 TV App 观看记录接入；
- 阅读刷次、状态分组，以及带缓存的高级阅读统计。
