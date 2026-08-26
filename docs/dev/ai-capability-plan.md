# AI 能力收口与扩展计划

本文件是 AI 相关工作的单一事实来源。阶段顺序不可换，每阶段独立可验收、可回滚。

## 参考来源

代码级调研过四个 fork，结论已并入本文，不在此展开：

- `joestar817/legado_NG`：技能 capability 授权闭环、工具副作用分级、内置 MCP 通道、token 用量采集。
- `Rimchars/legado`（阅读Archive）：技能渐进披露（先目录后全文）、世界书注入位置、阅读页 AI 浮层。
- `bangdaidai/readdai`：技能入口路由（划词/工具栏/详情页）、RAG 三模式检索、独立 AI 数据库。
- `codedogQBY/ReadAny`：RAG 的正确工程做法（Float32 BLOB、provenance 模型漂移防护、段落定位与可点引用）。

## 硬约束

- 主库 `AppDatabase.version` 保持 103。AI 新表进独立 `AiDatabase`，版本自管。
- 新表需在 `Backup.kt` 单独加 JSON 条目；apiKey 沿用现有加密路径（`Backup.kt:392`）。
- 改到 `io/legado/app/ui/` 下文件后，收尾核对 `build.gradle.kts` 三张基线表（双向棘轮）。
- Agent 不跑构建；每阶段末尾给出用户侧验证命令。

## P0 接线（无新表、无新页面）

已实现但用户不可达的能力，逐条接通：

1. 死入口：`AiModelEditScreen.kt:137` 无供应商项接跳新建供应商；`AiConfigScreen.kt:163` 技能组在 P1 落地前隐藏。
2. `AiProtocol.GOOGLE_TRANSLATE` 无 handler，选中即 `error()` → 从枚举移除。
3. `AiCapability.TOOLS` 零读取方 → 按能力过滤工具下发，不支持 function call 的模型无工具降级而非报错。
4. `AiGenerationParams.webSearch` 无 UI 开关 → 接 `WebSearchSettingsGateway`，让三个 handler 已写好的供应商原生联网可达。
5. `AiTaskType.EXPLAIN_SELECTION` 全仓零引用 → 补划词「讲解」入口。
6. `AiTaskRuntimeOptions` 的 `concurrentRequests`/`retryCount`/`targetLanguage` 只写不读 → 接上或删除，不留幽灵配置。
7. `AiTaskManager.snapshots` 只增不减 → 终态清理。
8. `AiReasoningLevel.effortFor` 白名单外静默丢弃档位 → 模型编辑页显式提示不支持。

验证：`.\gradlew.bat :app:compileAppDebugKotlin`、`verifyConfigArchitecture`。

## P0.5 对话体验细节

1. **token 用量**：三个 handler 解析 `usage.prompt_tokens/completion_tokens/total_tokens`（当前零解析），流式按供应商开关请求 `stream_options.include_usage`（不是所有兼容端点都支持，硬开会报错）。每条回答底部显示输入/输出/合计。
2. **上下文占用**：结合 `AiModelProfile.contextWindow` 显示「当前上下文 / 窗口上限」。
3. **划词引用到对话**：阅读器选中文本带入对话，输入框上方显示可移除的引用条（截断 80 字）。
4. 不做首页 AI 悬浮球：会污染 Navigation 3 导航层，收益低于上面三条。

书籍结果卡片已具备（`AiChatContract.kt:42/49` + `onOpenBookInfo`），无需补。

## P1 技能体系

技能 = 用户可定义的一条 AI 动作：名称 + 指令 + 输入范围 + 入口位置 + 输出处置（+ 可选绑定模型、声明工具权限）。

- 格式：frontmatter markdown（`name`/`description`/`version`/`capabilities`），可导入导出、进备份。
- 入口路由：划词菜单 / 阅读菜单 / 书籍详情 / 对话（借 readdai 的 `showIn`）。
- 输入范围：选中文本 / 当前章节 / 书籍元信息。
- 输出：浮层展示 / 替换正文 / 存为 artifact / 复制。
- 版本钉住：`revision` + `contentHash`，会话固定版本、过期拒读（借 NG）。
- 渐进披露：对话入口下 system 只放技能目录，模型按需调 `load_skill` 取全文（借 archive）。
- 存储：新表 `ai_skills` 进 `AiDatabase`；内置技能放 `assets/skills/*.md`，首次运行灌库，按 frontmatter `version` 只升级未被用户改过的。
- 执行复用 `AiTextFactoryUseCase`，`TEXT_FACTORY` 由此获得真实入口。管理 UI 照 `AiRewritePresetConfigSheet.kt` 形态搬到设置页；现有重写预设保持原样，不做数据迁移。

## P2 工具授权与注入防护

三个 fork 在此均有真实漏洞（archive 写工具零确认 + 256 轮循环；readdai 的 `AiPendingToolConfirmation` 从未赋值，`manage_tags` 可无声删改用户标签）。

1. 给现有 24 个工具打副作用等级 READ / WRITE / DESTRUCTIVE，把 `confirmationRequiredTools` 升级为分级，默认 fail-closed（未标注视为需确认）。
2. 工具按域分 capability 组：book / bookshelf / knowledge / memory / web。
3. 闭环：技能声明 capabilities → 导入时展示并要求授权 → 下发时按 `skillId@version@hash` 过滤。
4. **工具输出加来源标注 + 「工具输出是数据不是指令」的系统约束**。四个 fork 全都没做，恶意书源页面可直接指挥 agent 调写工具。这是我们能领先的一点。

## P3 记忆与产物管理

`AiMemoryGateway` 有 4 个方法零调用方（`observeByConversation`/`observeGlobal`/`getByConversation`/`deleteAllForConversation`）——界面设计过没做，用户只能靠模型自己调 `delete_memory`。补记忆管理页 + artifact 浏览页，纯 UI，无新表。

## P4 检索增强

### P4a 关键词检索

Room FTS + 中文 bigram：索引侧生成 bigram、查询侧不生成（借 ReadAny `tokenizer.ts:384-407`）。先让「能问全书」成立，零外部依赖。

### P4b 向量检索

落在 `AiDatabase`，WAL、不进备份。三条硬要求，缺一不可：

1. embedding 存 `ByteArray`（Float32 小端），反序列化校验 `size % 4`；**不存 JSON 字符串**（readdai 存 JSON，检索时全量反序列化）。
2. `vector_index_provenance` 表记录 bookId / modelId / dimension / endpoint / createdAt，检索前校验，不匹配明确报「需重新向量化」而非静默错检（readdai 无此保护）。维度冲突时跳过共享索引，不清库。
3. chunk 必须带定位字段：`chapterIndex` + 段落 index + 章内字符 offset。**这条决定「AI 引用可点回原文」能否实现，且后补要重建全部索引**，所以 P4b 验收标准写死：AI 回答里每条引用可点击跳回正文对应段落。

其余：段落边界切块（target 300 token / min 50 / overlap 0.2）；token 估算按中文≈1 字 1 token，不用 `len/4`（ReadAny 与 readdai 都在此偏差 4 倍）；hybrid + RRF k=60，双路各取 `topK*2`；向量不可用时结果标状态供 UI 提示，不静默降级；检索走 DAO 分页游标 + 固定 top-K 堆，不把整本 chunk 读进内存；结果作为 tool 返回值（现有架构已如此），带 token 预算与 truncated 标记。

必须有的单测：模型漂移拒绝检索、向量序列化往返、维度守卫（ReadAny 有，readdai 零测试）。

sqlite-vec 在 Android 需自带编译 SQLite 或 JNI，Room 默认路径加载不了，未验证，先不碰。

### P4c 端上 embedding（可选）

ONNX Runtime Mobile + NNAPI，bge-small-zh 512d，量化后 23–33MB。好处是免 key、正文不外发；代价是模型下载体积与新 native 依赖。排最后。

## P5 内置 MCP 通道（可选）

统一工具定义 + 副作用分级，App 内 AI 直接调，**不启动 HTTP 服务、不开局域网端口**。P2 完成后接近免费。

## 明确不做

- 外部 HTTP MCP 服务。NG 的 `McpServer.kt:84` 是 `BuildConfig.DEBUG || pref`，debug 恒开、绑全部本机 IP、零 token、外部 `tools/list` 不过滤，同网段任意设备可删书删源、读全部对话与网络日志。若要做：配对 token + 默认 loopback + 外部路径也走副作用分级。
- 关键词模板 planner（archive 的假计划）与无上限 agent 循环（GOAL 模式 256 轮 + 零确认）。
- 藏书票：readdai 已有完整 HTML 模板实现，与 AI 无关，单独排期。

## 进度

- [x] P0-1 死入口
- [x] P0-2 GOOGLE_TRANSLATE 清理
- [x] P0-3 工具能力降级
- [x] P0-4 原生联网开关
- [x] P0-5 划词讲解
- [x] P0-6 幽灵配置与快照清理
- [x] P0-7 推理档位提示


