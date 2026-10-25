# 朗读角色体系融合方案（2026-10 重核版）

本文取代此前那份未入库的「朗读 AI 角色体系合并梳理」。所有行号是**当前分支实测**，不是引用旧结论；
与旧结论不一致的地方单列在 §3，本轮新发现的隐性成本列在 §4。

**先读哪份**：想知道"用户在屏幕上看到什么、点了会发生什么"，读
[`readaloud-user-journey.md`](readaloud-user-journey.md)（纯用户视角，零行号）；
本文讲的是数据权威与施工顺序。两份互不重复。

只回答一件事：朗读侧「谁在说话 / 用什么声音 / 在哪设 / 什么时候算」这四个问题，
现在每个都有两处答案，怎么收成一处。不再区分「合并前 / 合并后」——以现在的代码为唯一基线。

---

## 0. 一页纸（先读这一页就够，下面全是开工时才查的证据）

**乱源一句话**：同一件事——"这句话是谁说的"——现在有两本账各记一遍，而播出时后一本还会把前一本擦掉。

**现在实际发生的事**：你点听书，AI 先猜一遍谁说话，写进**台词本**；另外还有一本**贴条簿**，播出时按贴条
去填台词本，**贴条没写到的那句，直接当旁白**。于是 token 花了、AI 也认出来了，你耳朵里还是一个人，
而且没有任何提示。演员名册同样有两本，互相抄，所以同一个人可以有两条记录、两个声音；
"给人物挑声音"也有两位算法，各挑各的。

**要变成的样子，只有四条**：

1. **谁说了哪句 → 只有台词本。** 你在正文里或分镜页改一句，就是改台词本那一行，另一边立刻跟着变；
   AI 重跑永远跳过你改过的行。
2. **这个人是谁 → 只有一本名册**，一人一行、一个声音。
3. **用哪个声音 → 只有一位算法**，并且写清"这个声音是你选的"还是"系统自动配的"。
4. **什么时候分析 → 你点听书的时候。** 设置页里没有"开始分析"按钮，它只管四件事：
   分析模式、推理强度、归因口径（一个不漏 / 宁缺毋滥）、要不要顺带认配乐场景。

**改完之后你直接能感觉到的差别**：AI 认出来的角色不再被无声抹掉；失败 / 冷却 / 纯规则这三种情况会分别
告诉你；你改过的那句不会被任何重跑冲掉；你给某个人物选音色就真的只改他（现在会顺手把整本书的旁白换掉）；
给人物改名、加别名、转正，不会引发整本书重新烧钱；**改划分方式 / 换模型 / 改提示词 / 切归因口径，
已经做好的那一章还是原来那一章的内容，不重切、不重烧，只有「重新分析」这一下才重做**；
朗读设置那一页里，声音 / AI / 切分 / 缓存各有各的小标题，不再穿插。

**开工顺序**：先做"让现象看得见 + 名字改对 + 修那个换错人的 bug"（不碰任何数据，随时可退）→
再做"只有一位选音算法" → 再做"改一句就是改台词本" → **最后才动"两本名册并一本"**。

下面 §1 起是证据（文件与行号）、去留清单、决策表和分期表。批复状态（2026-10-20）：
D2、D8 按推荐批下并已落地，D3 你改判不做（"200 个够够的"），D1/D4–D7 沿用旧结论；
**D9 由维护者 2026-10-21 口述提出并当场落完（见 §8 D9）——当前无待批复项**。

**进度（2026-10-21，S1–S9 全部落完）**：S1 / S2 / S6 / S5 安全切片 / D2 / N4 / N6 → S7 三个切片
（B/C/D，「AI 分配角色」入口从此只剩配乐识别）→ **S3（音色标签 + 池分组）、S4（选音合一）、
S5①（定稿写入）、S8（覆盖层收缩为效果层）、S9（身份合一的规则层）**。
四条目标（§0 那"要变成的样子"）在行为层全部达成：谁说了哪句只有台词本、一人一行一个声音、
选音只有一位算法、分析只在点听书的时候。
**唯 S9 只落了一半**：`cast_characters` 物理退役、档案表追加三列、两套主体 id 归一、
`book_cast_memory` 文本改结构化导入——这四项属结构收尾，本刀明确不动，分界与理由见 §7 的 S9 记录。
全部改动**未编译**，构建由维护者跑；每条记录的验收列都还在 §7 表格里。
孪生的用户视角文档 `readaloud-user-journey.md` 已同步到同一口径。

**同日补刀（2026-10-21，S5① 的收尾）**：原定稿只在出计划时写，"改完归属分镜页看不到"被我当成取舍写进
交付说明，维护者一句"这可以直接修改呀，又不涉及 AI 重新分析"把它否了——那句确实是挂点选择，不是技术限制
（取消分配那一路早就在用同一座桥当场重建）。现在点「确认／新建」的当下就走 `CastSegmentFinalizer.finalize`
把归属写进库里那份分段并锁住，**分镜页立刻可见**；`replay` 退成兜底，只管"这一章还没分析过"和
"正文换过了、那几段量不到现在这份正文"两种情况（后一条原判据是"不是当前设置算的"，随 D9 改掉）。
不变的那条是**耳朵**：正在播的本章队列不重建，仍要等本章重新准备。

**同日第三刀（2026-10-21，D9）**：维护者三条口述把"改设置 = 结果作废"这条底层逻辑否了
（"已经生成的还是按照之前的来，新的按新的来""划分方式改了跟 AI 分析说话人有啥关系"
"用户没主动点重新分析就没必要重新划分"）。读的时候从此只看**这一章有没有结果**，
判据换成与划分粒度无关的同源核对（章内起点 + 原文落在同一批字上）；
那枚「按以前的设置分析，听书没用上」徽标连同它的整条判据、字段、文案一起删除。
设计与代价见 §8 D9，落地清单见 §7 的 D9 记录，验收见 §10。

---

## 1. 准绳：一条用户体验线

下面八条来自你口述的目标体验，本文一切设计都以它为准，不按它成立的东西就是要删的东西。

| # | 用户预期 | 一句话判据 |
|---|---|---|
| L1 | 在朗读设置里选好：分析模式、推理强度、配音分配要求（归因口径） | 设置页**只声明**，没有"开始分析"按钮 |
| L2 | 点「朗读」，后台就开始这一章的说话人分析 | 执行入口 = 朗读本身，不是弹窗、不是结果页 |
| L3 | 分析过程中出现临时角色、自动分配的声音 | 新说话人自动建档 + 自动补音，且**标得清是自动还是我选的** |
| L4 | 这一章分析完，就用 AI 认出的说话人 + 分配好的音色来念 | 看到的和听到的来自同一份计划 |
| L5 | 分镜结果页给这一章做场景拆分 | 场景是分析收尾顺带跑的，不是"进页才补" |
| L6 | 正文显示说话人头像 = AI 分析的结果 | 正文胶囊读的就是 segments 那份真相 |
| L7 | 在正文改一句，分镜结果页同步跟着改 | **两边读写同一张表**，改哪边另一边立刻变（2026-10-21 达成：定稿当场写 segments，不再等本章重新准备） |
| L8 | 角色已提前绑好音色，AI 就只判文本，分音时直接用绑好的 | AI 不为已绑音色的角色要池，自动补音不覆盖手动 |

**验收方式**：把这八条当测试用例。任何一步让你觉得"这个我刚才在别处也设过一遍"，就是没做完。

---

## 2. 复杂性的真正来源：每个问题两处答案

不是"AI 效果不好"，是同一个问题被解了两遍，后一遍还覆盖前一遍，覆盖不到就**抹掉**而不是保留。

拿剧组打比方：场记的台词本（`chapter_speech_segments`）本来是唯一真相，现在另有一本"通告贴条"
（`chapter_role_assignments`），播出时先撕台词本上对应那行、再按贴条填，**贴条没写到的行就直接写上
"旁白"**。演员名册有两本（`cast_characters` / `book_character_profiles`），靠两个镜像脚本互相抄，
抄歪了就查无此人。选角导演有两位，按不同标准挑同一个声音。而"这个声音是男是女、多大年纪"这件事，
登记簿上压根没有给用户填的格子。

现在（`app/src/main/java/io/legado/app/` 下）：

| 概念 | 第一处 | 第二处 | 净后果 |
|---|---|---|---|
| 谁说了这句 | 规则分段 + AI 归因 → segments | 分配表按引号序号认领 → 覆盖 | 覆盖不到即降级旁白（`help/readaloud/cast/CastSpeechOverlay.kt:140-150`），官方结论**不可见** |
| 角色是谁 | `book_character_profiles`（唯一键 `bookUrl+name`，`data/entities/BookKnowledge.kt:11`） | `cast_characters`（唯一键 `bookUrl+name+poolLabel`，`data/entities/CastCharacter.kt:22`） | 同一人两行两个声音；靠 `CastProfileMirror`/`CastMemoryMirror` 双向抄 |
| 角色的性别/年龄/池 | 档案三列 | `book_cast_memory` 一整段纯文本（`名｜别名｜关系｜池`） | 双写镜像任一侧失败即背离，无校验 |
| 用哪个声音 | `domain/usecase/AutoAssignVoiceBindingsUseCase.kt:127-135`（性别→年龄分→风格→引擎→稳定序） | `help/readaloud/cast/CastVoicePicker.kt:31`（池内用得最少再 `random()`） | 同一次"给角色配声音"结果不可复现 |
| 主体的 id | 角色行 id | 档案 id | `bindingVoiceAny` 把两套全试一遍（`CastSpeechOverlay.kt:160-165`） |
| 推理强度 | DataStore `speechAnalysisReasoningLevel`，默认 **OFF**，UI 7 档（`domain/model/settings/ReadAloudSettings.kt:92`、`ui/book/read/sheet/ReadAloudConfigContent.kt:260-271`） | prefs `ai_cast/reasoningLevel`，默认 **AUTO**，UI 5 档（`help/readaloud/cast/AiCastPresetStore.kt:502-512`、`AiCastDialogSheet.kt:872-878`） | 同一枚举两处存储两个默认值 |
| 音色是男是女 | `traitsJson` + 名字启发猜（`help/readaloud/ReadAloudVoiceTraits.kt:78-89`） | 池的 `gender/ageBand`（落库但运行时**只取年龄不取性别**，`PrepareChapterSpeechPlanUseCase.kt:125-126`） | 用户没有任何地方能标音色 |

**好消息：地基已经有了。** 2026-10-18 新建的 `domain/usecase/ResolveEffectiveSpeechPlanUseCase.kt`
已经把自己命名为「这一章该怎么念」的唯一入口（分段→识别→选音色→套覆盖），起播、预合成、分镜页三条
链都从这里走（`service/BaseReadAloudService.kt:587`、`service/HttpReadAloudService.kt:979`、
`ui/book/readaloud/storyboard/SpeechStoryboardViewModel.kt:399-407`）。它自己的注释把病因写得很清楚：
历史上这段编排写在朗读服务里，分镜页只截中间两步，导致"界面上写着一个人、耳朵里是另一个人"。
**所以融合不是从零建架构，是把两套真相接进这个已经存在的入口。**

---

## 3. 与上一版分析不一致的地方（8 处，按当前代码校正）

旧文档以下结论**已失效**，照它施工会踩空：

1. **旧：分镜页零读分配表。** 现在读了：当前章走同一个用例，末尾套覆盖层
   （`SpeechStoryboardViewModel.kt:399-407` + `ResolveEffectiveSpeechPlanUseCase.kt:90-101`）。
   → L7 只剩一半没做：**非当前章仍不套覆盖**（走 `planFromSegments`，
   `ResolveEffectiveSpeechPlanUseCase.kt:113-123`；ViewModel `:454-458`、`:474`、`:559-565`）。
   正文改一句，当前章同步、翻到别章看就不同步。
2. **旧：关掉「多角色朗读」直接 `return emptyList()`，连分析都不跑。** 前半成立，后半要补：
   有生效正则角色时照样出计划，但把模式强制降成 `Rule`（`ResolveEffectiveSpeechPlanUseCase.kt:58-60`、
   `:68-70`）。→ "不想花 token = 关掉这个开关"仍然成立（Rule 时不调 AI，
   `PrepareChapterSpeechPlanUseCase.kt:91-93`）。
3. **旧：全仓没有面向用户的音色打标签入口。** 有一个窄的：Cloud TTS 里 `managedBy == 用户自建`
   的音色可编辑 gender/notes/personality/thoughtPerformance 写回 `traitsJson`
   （`ui/book/readaloud/cloudtts/CloudTtsViewModel.kt:866`、`:384-396`）。
   → 引擎目录同步来的音色仍然编不了，而且**下次同步会被逐字段重建打回**
   （`SyncReadAloudVoicesUseCase.kt:62-82` + `data/dao/ReadAloudVoiceDao.kt:59-66` 是 REPLACE）。
   用户标签必须放同步不碰的独立表，这条结论不变、理由更硬。
4. **旧：`useMultiSpeaker` 与 `multiRoleCast` 用户不可区分。** 其实可区分，只是名字没讲对：
   前者决定"换不换声音"（进合成计划，`BuildSpeechPlanUseCase.kt:58`、`ReadAloud.kt:115`、
   `BaseReadAloudService.kt:853`），后者决定"正文里能不能点着分"（注入标记 + 胶囊 + 分页签名，
   `help/readaloud/cast/CastRenderOptions.kt:32,55`、`feature/reader/.../ReadBook.kt:609`）。
   → 不必合并成一个开关，**改名即可**：「多角色朗读」/「显示角色」（2026-10-21 定稿，第一版
   「按角色分声朗读」/「正文显示角色名」被判定太长）。
5. **旧：「听书预加载数量」= 播放队列音频预取。** 错得更彻底：它是**章节正文预下载**
   `ReadSettings.preDownloadNum`（`ReadAloudConfigContent.kt:389`→`ReadBookViewModel.kt:257`），
   还和「下载与缓存」页共用同一个键（`ui/book/.../DownloadCacheConfigScreen.kt:131`）。
   真正的音频预下载 `audioPreDownloadNum`（默认 3，`ReadAloudSettings.kt:112`，
   消费点 `HttpReadAloudService.kt:1002,1300`）**在朗读设置页没有入口**。
   → 一个错名的项 + 一个缺失的项，比旧文档说的更该修。
6. **旧：朗读设置已是整页、栈语义天然正确。** 只覆盖了一半：阅读页入口是 Nav3 整页
   （`ui/.../MainNavGraph.kt:1742-1767`），但听书浮层里同一个 Content 仍包在
   `AppModalBottomSheet` 里（`ui/book/readaloud/ReadAloudPlayerMorphHost.kt:127-138`）。
   → 归纳一处两入口同时生效这条仍成立（共用 `ReadAloudConfigContent`），但形态不统一。
7. **旧：AI 失败一律无提示。** 首次失败是有提示的（`PrepareChapterSpeechPlanUseCase.kt:153-165` 发
   `EventBus.ALOUD_AI_FALLBACK`，`BaseReadAloudService.kt:383-386` 订阅）；**冷却期内那 10 分钟才是
   完全静默**（`RefineSpeechWithAiUseCase.kt:241` 命中即静默返回规则结果）。
   非播放态（在分镜页触发）仍没人接得住——这条没变。
8. **旧：转正由用户在人物页点。** VM 有实现（`BookVoiceCastingViewModel.kt:335-361`），
   **全仓找不到 UI 调用点** → AI 新建的草稿卡现在根本转不了正，只能永远停在"临时说话人"那一栏。

另有两处代码注释与现行语义矛盾，属清理项：`BookCastStore.kt:281-283` 称档案的 `voiceAgeBand` 列
"现在装池名"（现行是枚举，`BookKnowledge.kt:26-34`）；`AiCastAssignUseCase.kt:593-600` 描述的
LIKE 误改名路径在现行精确名兜底下已不成立。

---

## 4. 本轮新发现的隐性成本（旧文档没有，影响设计）

**N1 改一个角色 = 全书 AI 分析作废，而且扳机粗到"只改头像"也算。** 缓存键
`resolverVersion` 里含 `md5(所有非草稿档案的 id:updatedAt)`（`RefineSpeechWithAiUseCase.kt:158-165`、`:174`），
而 `analysisId = sha256(bookUrl + chapterIndex + contentHash + resolverVersion)`
（`domain/model/readaloud/SpeechIdentity.kt:17-22`）——名册里任何一行时间戳一变，**每一章的 analysisId
全部对不上**，缓存 miss 即重烧整本 token。它按"这一行的 `updatedAt` 动过"判，不按"AI 实际看到的
内容动过"判：走人物详情页保存是**整行重建**并刷 `updatedAt = now`
（`ui/book/knowledge/BookCharacterDetailViewModel.kt:189-209`），哪怕你只动了头像或简介（同在一张表单，
`:194`、`:202`）也照样引爆；而从配音页直接换头像那条走 `copy(avatarUri = …)`、不动 `updatedAt`
（`BookVoiceCastingViewModel.kt:455`）就不引爆。**同一个动作，换个入口，一个炸一本、一个不炸**——
这是"拿行级时间戳当口径代理"最直白的症状。
（旁边还有一个更讲究的 `SpeechIdentity.characterRevision()`（`:31-43`，含 name/aliases/role/gender/ageBand），
归因链没有用它，用的是自己那串只含 id+updatedAt 的拼接——同一件事两套算法，粒度还不一致。）
草稿卡被排除出去以防自循环（`:158-165` 注释：建卡→revision 变→再分析→又建卡），
但**正式卡的排除没做**。最刺眼的是转正：草稿态时该 id 不在串里，`STATUS_DRAFT → ACTIVE` 那一刻它
**进入**串，键必变 → 用户点一下"转正"这个最不该引发重烧的纯本地动作，成了全书重烧的扳机。
→ **角色候选是提示词上下文，不是解析口径**，应该从缓存键里整体移出（见 D2）。
**排期耦合**：转正目前查无 UI 入口（§3.8），所以这颗雷还没被踩到——S5 补转正入口时必须和 D2 同轮落，
否则第一次点转正就当场触发全书重烧。

**N2 候选清单两处截断，长篇必踩。** 喂给 AI 的档案上限 **200** 条
（`RefineSpeechWithAiUseCase.kt:978-979`），门面解析上限 **500** 条
（`CastSubjectResolver.kt:41`，常量在 `:63`）。超限的角色 AI 看不见、绑定查不全。
→ 合并前先修截断策略（按"本章出场 + 全书高频"取，而不是按 id 顺序砍）。
**维护者判定（2026-10-20）：不改。** "200 个已经够够的了"——一本书的角色量级用不到这个上限，
按出场重排候选是为此付出的复杂度不划算。本条与 D3 一并作废，别在后续切片里再提。
顺带记下一条与判定无关的事实：经 `BookKnowledgeGateway` 取的候选在仓储层被
`limit.coerceIn(1, 80)`（`data/repository/BookKnowledgeRepository.kt:36`）压过，
所以调用方写的 200/500 从来没有真的生效过、实际是 80；`help/readaloud/cast/` 里直连 DAO 的 500 才是 500。
将来真出现"角色认不全"，先看这一处，不要先怀疑 AI。

**N3 自动补音会伪装成用户绑定。** `CastVoicePicker.syncBinding` 首次新建分支默认
`locked = false` 但 `source` 用实体默认值 `"user"`（`:126-131` + `data/entities/ReadAloudSpeech.kt:62-65`）。
→ 界面无法区分"你选的"和"自动补的"（L3 要的就是这个区分）。`SOURCE_IMPORT` 定义无写入方，
`confidence` 无运行时消费者。

**N4 给「临时角色」选音色，实际改掉的是整本书的旁白。** 这条已核到完整代码路径，四步无分支缺口：
草稿档案 → `kind = TemporaryCharacter`（`BookVoiceCastingViewModel.kt:149-153`）→
`isCharacter` 为假（`BookVoiceCastingScreen.kt:469`）→ 走 `else` 分支的旁白编辑器并发
`SetNarratorVoice`（`:609-618`）→ VM 把 voiceId **硬绑到 `SUBJECT_NARRATOR`**
（`BookVoiceCastingViewModel.kt:388-392`）。
→ 后果是 L3 直接破：AI 刚认出来的新人，用户想给他挑个声音，结果把旁白换了，那个角色还是旁白音。
这是本轮唯一发现的**行为级 bug**（其余是重复与错名），不依赖任何合并动作，可单独修（见 S1）。
**已修（2026-10-20，随 S1⑦）**：意图改成携带主体的
`SetRowVoice(subjectType, subjectId, voiceId)`，面板只交 voiceId、由调用行把自己那一行的主体
传下去；VM 按这个主体 upsert/delete，并把"这次写的是哪个主体"记进 AppLog 作回归观察点。
面板同时改名 `CastVoiceRowEditorPanel`（它一直服务两类行，名字里的"旁白"就是这次写错主体的起因），
空值那一栏按行分开文案：旁白=「跟随朗读引擎」，草稿角色=「自动选音」。
**与原推荐的差别**：没有把草稿行改去走四栏角色编辑器。草稿此刻只有音色这一件事该定，
名字/声音池/角色级变声器要等转正之后——那三栏写的是 `cast_characters`，给还没确认身份的说话人
提前改名和改池，等于把 AI 的猜测当成用户的手工设定。写入语义与选音色弹层同一条
（`locked = true, source = SOURCE_USER, confidence = 1f`），不新增第二套绑定规则。

**N5 `searchCharacterProfiles` 不排草稿**（`data/dao/BookKnowledgeDao.kt:17-31` 只排 DELETED），
而列表查询排（`:43-44`）→ 搜索口径会把草稿漏进正式角色候选里。

**N6 分镜页那句「旧划分，朗读不会用这份」的判据本身不成立。** 徽标长在章节列表行上
（`ui/book/readaloud/storyboard/SpeechStoryboardScreen.kt:401`，文案 `res/values/strings.xml:3197`），
来源是 `usesCurrentConfig` ← `ChapterSpeechSummary.isCurrent`
（`data/repository/ChapterSpeechRepository.kt:88-102`）。它的判据是"这一章**最新落库的那一行**分析，
版本号等不等于当前设置算出来的版本号"——可"最新落库"并不等于"当前设置会用它"，仓库自己的注释就承认了这点
（`:57-63`）。两条具体的失真：

- **假阳性**：命中缓存时不写 `updatedAt`（`domain/usecase/AnalyzeChapterSpeechUseCase.kt:37-43` 直接
  `fromCache = true` 返回，走不到 `:93` 的 `saveAnalysis`）。所以"先用 AI 模式读过、再切回规则模式重读"的章节，
  最新那一行仍是 AI 那份，**明明正在用当前设置的那份，也照样标旧**。
- **全书齐刷刷标旧**：叠上 N1，版本号含角色库时间戳，改任意一个正式角色（哪怕走详情页只动头像）
  会让每一章的"当前版本"同时改变，而它们的最新行都还是改之前那份 → 整本书每行挂一个徽标。
  这是"总是有"的主要来源；换模型、换分析模式、换内容划分方式、改提示词是另外四个同样会全书点亮的扳机。

三处读侧还共用这句不可靠的判据（`data/dao/ChapterSpeechDao.kt:48-50`、`:79-83` 都写死 `max(updatedAt)`）。
→ 判据改为"这一章**有没有版本号等于当前设置的行**"，与详情页 exact 命中同源
（`ChapterSpeechRepository.kt:70-75`），三处一起收成一个；D2 落地后徽标才只表达"设置真的变了"。
→ 归 **S1** 可观察性清单：除判据修正外，文案要把内部概念"划分版本"换成用户能懂的话并给出动作
（现在是把缓存键的实现细节直接说给用户，且什么也不让他做）。
**已修（2026-10-20，与 D2 同轮）**：判据三处收成同一条"这一章存在版本号等于当前设置的行"
（`ChapterSpeechDao.getChapterCurrentAnalyses` 用 `order by (x.resolverVersion = :v) desc, x.updatedAt desc`
选出每章该看的那一行，统计行与徽标同源），`getBookAnalyses` 那份旧口径删除；
徽标文案换成「按以前的设置分析，听书没用上」，配置卡里补一句说明它只意味着要显式重跑
（点章节不会自动重跑，非当前章只是读库——这一点原方案写错过，已在
`docs/dev/readaloud-user-journey.md` 一并纠正）。上面第二条失真（角色库时间戳）随 D2 移出缓存键消失。
**这段结论已被 D9 作废（2026-10-21）**：徽标不是"判据不准"，是**这个问题本身不存在**——改了划分方式 /
分析模式 / 模型 / 提示词 / 归因口径，那一份结果照样是这一章的结果、照样念。于是判据、文案、字段
（`ChapterSpeechSummary.isCurrent` / `usesCurrentConfig`）整套删除，见 §8 的 D9 与 §7 的 D9 记录。

---

## 5. 五条简化律

这是"怎么让它简单"的全部答案，后文每个动作都能对应到其中一条：

1. **一个概念一张真相表。** 说话人只有一处（segments）、角色只有一处（profiles）。
   其它表要么不存在，要么只是**改写真相的记录**（定稿层），不是并行真相。
2. **一个决定只做一次。** "谁说话"在分析时定；"用什么声音"在选音时定。
   播出时的覆盖层**不允许再判一次谁说话**。
3. **一个旋钮一处存储一个入口。** 推理强度、预加载这类，两处存的一律收成一份，默认值明确写进界面小字。
4. **执行不进设置页。** 什么时候算是朗读（L2）和结果页的显式动作，设置块零运行按钮。
5. **界面零新增页面。** 朗读设置就是那一页那两个 Tab，只插小标题归纳。

对应到剧组的说法：台词本只有一本，贴条是用来**改台词本**的，不是播出时另读一本；
名册只有一本；选角导演只有一位，且挑过的结果要写清楚"是你点的还是我自己挑的"。

---

## 6. 目标模型

### 6.1 三张真相

```text
章级分段 chapter_speech_segments  = 谁说了这句（唯一真相）
      ↑ 写入者：AI 分析链（三档模式） + 定稿（正文/分镜页改一句**当场**写，出计划时只兜底）
      ↑ 定稿 = 写 characterId + userLocked=true，重跑永远跳过锁定段
角色档案 book_character_profiles  = 角色是谁（唯一真相，一人一行）
      ↑ 性别 / 年龄 / 池 / 状态（草稿↔正式）/ 头像 / 别名 / 关系都在这一张表
音色绑定 book_voice_bindings      = 这个主体在这本书用哪个声音（唯一权威）
      ↑ 主体九类：旁白 / 每个角色 / 男女主男女配（位置兜底）/ 路人男路人女路人（性别兜底）

声音池 = 音色的分组（互斥，一音色一组）+ 可编辑的默认性别年龄
音色标签 = 用户自己标的性别/年龄/风格（新表，引擎同步不碰）
选音 = 唯一算法（一处 UseCase，无 random）
```

### 6.2 运行时一条线

```text
设置块声明（模式 · 推理强度 · 归因口径 · 分镜场景 · 顺带往后几章）
   ↓ 点朗读 / 预合成（自动，本章）
规则分段 →【一次 AI 归因】说话人 + 角色类型 + 情绪（+ 未绑音色的新说话人建议池）
   ↓ 落 segments（AI 认出的新人 → 草稿卡，进"临时说话人"栏）
定稿回放（兜底，当场已写的这里空转）：quoteOrdinal → 覆盖该开引号的 segment → characterId + userLocked=true
   ↓
角色补齐：档案的性别/年龄/池（年龄必须来自**可编辑**的池或用户标注，不能只靠派生）
   ↓
选音（唯一算法）：已有绑定（locked 优先）> 池内按 性别→年龄→风格→引擎→稳定序 > 位置兜底 > 性别兜底 > 本书旁白 > 全局默认旁白 > 引擎默认
   ↓
ResolveEffectiveSpeechPlanUseCase → 同一份计划 → 播放 / 分镜页 / 正文胶囊
   ↓
CastSpeechOverlay 只剩：正则角色 · 不发声段 · 段级变声特效（**不再决定谁说话**）
```

与 L1–L8 的对应：L2/L4 由这条线的"自动触发 + 同一份计划"满足；L3 由"草稿卡 + 自动选音标注"满足；
L5 由场景拆分挂在分析收尾满足；L6/L7 由"正文与分镜页读写 segments 同一行"满足；L8 由
"契约只对未绑音色的新人要池"满足。

### 6.3 界面：只插四个小标题，零新增页面

`ui/book/read/sheet/ReadAloudConfigContent.kt` 的 Tab「引擎与音色」现在是 18 项一列到底、没有分组。
按 §7 的 S6 归纳：

```text
〔声音〕用哪些声音        :224 引擎与音色 · :375 角色声音池 · :380 背景音乐池 · :385 系统 TTS
〔多角色朗读（AI）〕谁用哪个声音、怎么判
        :347 多角色朗读（总闸） · :359 显示角色 · :239 分析模式 · :260 推理强度
        :367 顺带识别配乐场景 · 归因口径〔已落 2026-10-21，紧接推理强度〕 · 顺带往后分析几章（新并入） · prompt 入口
        :234 人物与角色配音[进入] · :255 分镜结果[进入]
〔切分与节奏〕一句多大、念多快、往后备多少
        :282 章节分片方式(+ :329 标点) · :412 段落间隔 · 音频预取（补入口，见 S2） · :399 预合成并发数
〔缓存〕存了什么、怎么清   :229 TTS 缓存 · :425 清理时间 · :439 清空缓存
```

- 块内顺序可调，**项不增不删、每项读写语义不动**；小标题不建公共组件，照
  `ui/book/readaloud/cloudtts/CloudTtsScreen.kt:717-724` 的 `EngineSectionTitle` 在本文件加 private
  composable（`AppText` + `labelMediumEmphasized`，水平/垂直 12dp）。
- 顺手清掉第三个 Tab 的死口径：`ReadBookContract.kt:330-336` 注释与 `readAloudConfigTab` 无读取方、
  `ui/book/read/ReadAloudDelegate.kt:297` 的 `coerceIn(0, 2)`、零引用字符串
  `read_aloud_settings_casting_tab`（`values/strings.xml:3303` 及三语变体）。

### 6.4 数据层去留

| 构件 | 去处 | 依据 |
|---|---|---|
| `chapter_speech_segments` | **保留为唯一说话人真相**——**写入方已落（2026-10-21 S5①）**：`CastSegmentFinalizer.finalize` 在用户点「确认／新建」当场写，`replay` 出计划时兜底，两处都写 `userLocked=true` | `userLocked` 列已在（`data/entities/ReadAloudSpeech.kt:126`），6 处"跳过锁定段"自 2026-10-21 起真正生效 |
| `chapter_role_assignments` | **已改造为定稿层（S5①）**：锚点仍 `(bookUrl,chapterIndex,quoteOrdinal)`，建行同时把结论写进 segments（不再"等回放"），不参与播出认领；剩下的职责是"用户建行入口 + 那一句的变声器" | 唯一建行出口 `CastAssignmentStore.assign()` → `CastSegmentFinalizer.finalize()`；取消分配前先放开定稿（`release`/`releaseChapter`） |
| `cast_characters` | **未退役（S9 只落了规则层）**：一人一名由"写入侧拒绝 + `CastIdentityFolder` 运行期折叠"保证，表本身与它的 `voiceId` 副本仍在。独有三列 `voiceEffect/sortOrder/bubbleRuleJson` **仍以追加列**进档案表这一步留给下一刀 | 唯一键粒度差异见 §2；行级折叠不改主键 → 非破坏性；追加列属结构变更，须与约 20 个文件的读写搬家同轮做 |
| `CastProfileMirror` / `CastMemoryMirror` | **暂留**（并表没做，两张表还在就得互抄）。2026-10-21 切片D：**记忆 → 档案**那一向已删（`applyMemoryToProfiles`/`reconcilePoolsWithCast`/`applyUserEdits`）；S9 折叠器新增一处 `syncCharacterRow` 调用（并档后把记忆那行的池跟着保留行） | 双写背离是 bug 源；反向已无产出方 |
| `book_cast_memory` | **退役**：别名→`aliasesJson`、池→`voicePoolId`、关系→`book_character_relations`。切片D 后这张表**只由人物详情页与配音页写**，分析链不再读它——结构化导入随 S9 下一刀做 | 文本行是图谱的弱化复制 |
| `CastSubjectResolver` | **保持现形**：一人一名让"两套主体 id"的分叉面变小，但档案 id 与角色行 id 仍可能不同（并档留下的是另一行的 id，档案主键不动），所以读写两侧仍走它取全集。500 上限**不改**（N2/D3 改判不做） | `:41`、`:63`；写入侧统一到 `bindUserVoice`/`syncBinding` 是 S4 落的 |
| `voice_pools/_members/_groups` | **保留 + 已收成互斥分组（2026-10-21 S3②③ 已落）**：`gender/ageBand` 有编辑入口、池性别不再被丢弃、存量成员按"一音色一组"折叠（不加唯一索引，D4） | 现复合主键天然多对多（`VoicePoolEntities.kt:109-120`），折叠器 `VoicePoolStore.foldDuplicateMembers()` |
| `read_aloud_voices` | **保留，不加列**；用户标签已放新表 `voice_labels(voiceId PK, gender, ageBand, tagsJson, createdAt, updatedAt)`（**S3① 2026-10-21 已落**，追加进 `migration_127_128`，备份/恢复已接线） | 同步逐字段 REPLACE 重建（§3.3） |
| `RegexCastRuleStore` / `RegexCastSplitter` | **保留**，优先级仍最高（关掉多角色也生效），UI 注明"正则命中会顶掉 AI 结论" | `CastSpeechOverlay.kt:59-61`、`:133` |
| `BgmSceneStore` + `AiSceneAssignUseCase` | **保留 + 改名** `BgmCue*`（与分镜场景解名） | 配乐场景与分镜场景零关系 |
| `AiCastAssignUseCase` 的 AI 调用 | **退役**；独有能力（称呼归并、池建议、续跑、范围）全部迁出后不再发请求。**2026-10-21 切片D：文件、Koin 绑定、VM/delegate 参数已删**，`castChapterPlan`/`AiCastStream`/`failureReason` 搬进 `AiSceneAssignUseCase.kt` | 其输出是官方链的子集；定稿价值不依赖 AI |
| `AutoAssignVoiceBindingsUseCase` + `CastVoicePicker.ensureVoice` | **合一为一个比较器（2026-10-21 S4 已落）**：两条链折成同一种候选 + `VoiceMatchScoring.order`，去 `random()`，**年龄进播放链** | 现两套算法见 §2 |
| `RefineSpeechWithAiUseCase` | **保留为唯一归因引擎**；prompt 取两套并集；缓存键移出角色 revision（D2） | `:150-176` |
| `IdentifyBookCharactersUseCase` + `ai_artifacts` | **保留独立**（产档案不产逐句归属，与归因不冲突） | 入口在知识页 `ui/book/knowledge/BookCharacterListViewModel.kt:251` |

---

## 7. 分期落地

顺序原则：**先让现象可见、再修错名与错绑定、然后做零权威变化的收敛、最后动数据权威。**
每步可独立回退；动权威的一步都排在"现象已能自证"之后。

| 步 | 内容 | 依赖 | 动权威 | 回退 | 可观察验证 |
|---|---|---|---|---|---|
| **S1 现象可见 + 一个行为级 bug**（7 项） | ①分镜页自建 `ALOUD_AI_FALLBACK` 订阅（现只有服务订，`BaseReadAloudService.kt:383-386`）；②冷却期命中给可区分提示（现静默，`RefineSpeechWithAiUseCase.kt:241`）；③场景拆分失败给提示（现 `catch(Throwable)→return plan` 不撤账不提示，`SpeechStoryboardViewModel.kt:546-548`）；④`ai_cast` 入口加 AI 模型门禁（现恒可用，`ReadAloudCastDelegate.kt:83`）；⑤每章标注"结论来自规则还是 AI、有没有被分配表覆盖"；⑥纠正 `CastSpeechOverlay.kt:76-79` 日志措辞（那条分支其实原样保留官方角色音）；⑦**修 N4**：草稿档案应走角色编辑器（按 `SUBJECT_CHARACTER` + 档案 id 绑），只有真旁白行才发 `SetNarratorVoice`——现两者共用面板、发同一 Intent（`BookVoiceCastingScreen.kt:609-618`），并配 AppLog 记下"这次写的是哪个主体"作回归观察点 | 无 | 否（纯新增 + 一处分支修正） | 直接 revert | 关掉播放再进分镜页，失败时看得见 toast；给临时角色选音色后旁白不变、那句角色音变 |
| **S2 旋钮合一** | ①推理强度只留 DataStore 那份（`AiCastPresetStore.kt:502-512` 退役，旧值作初值读出后删，**不静默丢**）；档位取全枚举 7 档（`domain/model/AiModels.kt:257-264`），AUTO=不发参数跟随服务商、OFF=显式不思考，差别写进小字；②「听书预加载数量」按真实语义改名（它是正文预下载），并给 `audioPreDownloadNum` 补一个入口（§3.5）；③两个开关改名：「多角色朗读」/「显示角色」（§3.4）；④清第三个 Tab 死口径（§6.3） | 无 | 否（设置介质） | revert；旧键未删即无损 | 弹窗里不再有强度 chips |
| **S3 音色标签 + 池分组**（四层接通，2026-10-21 **已全部落地**，含一处口径偏离，见下方记录） | ①新建 `voice_labels` 表承载用户标注（**不能加列到 `read_aloud_voices`**），标签为空时读取侧回落 `traitsJson` 预填，所以是加法不是切换；名字启发降为预填提示；未标注显示灰字而非硬猜；②池 `gender/ageBand` 补编辑入口（`VoicePoolStore.createPool/updatePool:363-403` 签名要加参数）、`voiceClassificationMap` 的性别不再被丢弃（`PrepareChapterSpeechPlanUseCase.kt:125-126`）；③`voice_pool_members` 写入侧收成 1:1 + 存量折叠报告（**不加唯一索引**，见 D4）；④定死"成员标签优先于池分类"；⑤备份补 `voice_labels` 字段 | 无 | 是（新表） | `voice_labels` 空即等同现状 | 同一角色两次进配音页，音色不再变 |
| **S4 选音合一**（2026-10-21 **已落**） | `CastVoicePicker.ensureVoice` 并入 `AutoAssignVoiceBindingsUseCase`，统一打分链，`random()`（`:31`）改稳定序，**年龄第一次真正进播放路径**；`syncBinding` 的 `source` 默认值按实际来源写（修 N3），界面据此显示"你选的 / 自动选音" | S3 | 是（选音结果可复现性） | revert 恢复旧补音 | 同书同池重跑给出同一个音色 |
| **S5 定稿写入打通**（2026-10-20 安全切片 + 2026-10-21 ①定稿写入，**全条已落**；①的落地方式与当初"要先补 segment 外键"的判断不同——**零 schema 变更**，见下方两条记录） | 正文胶囊分配（唯一出口 `assign():337-371`）在写分配表的同时，把结论写成 segments 的 `characterId + userLocked=true`；分镜页加"锁定/解锁此段"Intent（现无任何编辑/锁定动作，`SpeechStoryboardContract` 只有列表/重分析/试听/删除）；**非当前章也套覆盖**（`planFromSegments` 后补 `overlayOnSegments`，`ResolveEffectiveSpeechPlanUseCase.kt:132-144`），使 L7 完整；补「转正」UI 入口（§3.8）**——必须与 D2（角色库移出缓存键）同轮，否则第一次点转正就当场重烧全书（N1）**；并落 D2 的两条零 token 本地归并：转正＝纯 status 改写、新人入库后老章同名草稿卡按名字/别名重定向到正式档案（行级 `UPDATE characterId`） | 无阻塞 | 否（补写入方） | 无写入方时等同现状 | 正文改一句 → 分镜页那一行**当场**就是那个角色（2026-10-21 补刀，原先要等本章重新准备），让本章重新准备后实际发声也是那个人，且重跑分析不改它；转正一张卡之后，已分析章不重烧 |
| **S6 界面归纳** | 按 §6.3 给现有 18 项插四个小标题，「常规」Tab 两块；读写语义一律不动 | 无 | 否 | 逐项挪回原位 | 同一列里不再 AI 项与缓存项穿插 |
| **S7 prompt 并集 + 逐句 AI 退役 + 执行归位**（2026-10-21 **切片B、C、D 已全部落地**：B=①的两半（规则并集、归因口径两档、新人建议池）；C=②的范围/续跑/重试归位 + ③场景上提 + ④页面级记账作废；D=⑤吸收清单逐项打勾 + 入口与引擎退役，见下方三段记录） | ①两套判定规则并入 `ANALYZE_SPEECH` 契约（含归因口径两档单选、可选 `speakerPool` **只问未绑音色的新人**）；②`AiCastAssignUseCase` 请求侧删除，范围/续跑/重试归位到朗读侧档位「顺带往后分析几章」+ 结果页显式动作（补分析/重试失败章/重新分析），**设置块零运行按钮**；③场景拆分触发点从"进分镜页"上提到"该章分析收尾"，独立调用与 `SCENE_PROMPT` 不变（`RefineSpeechWithAiUseCase.kt:1152-1155` 的隔离保住）；④页面级记账 `sceneAttemptedChapters` 作废；⑤吸收清单逐项打勾，任何一项没落点就不许删调用 | S1/S5，且需先做调用面盘点 | 是（调用拓扑 + 执行入口） | **usecase 与弹窗那半已删**：revert 切片D 那一笔即恢复原状（旧预设行留在库里未删，无数据损失） | 一次朗读只看到一次归因调用日志 |
| **S8 覆盖层职责收缩**（2026-10-21 **已落**，四条行为后果见下方记录） | 删"分配表认领 + 认领不到降级旁白"（`CastSpeechOverlay.kt:140-150`），只留正则、静音、段级特效 | S7 | 是（呈现语义） | revert 即恢复覆盖 | 分镜页显示的与耳朵里的是同一个角色 |
| **S9 身份合一**（2026-10-21 **规则层已落、表结构未并表**，分界见下方记录） | **已落**：搜索口径（N5）→ 同名多池折叠成一行（keeper 按"有绑定/有音色"选，差异进报告）→ 三处 `characterId/subjectId` 重映射 → 写入侧一人一名（弹层创建、配音页新建/改名/官方档案同步、档案补建、备份恢复）→ `getByNameAndPool` 随调用点清零。**未落（下一刀）**：两处截断（N2，D3 已改判不做）、档案表追加 `voiceEffect/sortOrder/bubbleRuleJson` 三列、`cast_characters` 与两个镜像停写退役、`book_cast_memory` 文本改结构化导入 | S3、S8 | 是（身份权威），本刀**只行级改写**（`UPDATE/DELETE`），不追加列、不改主键、不改唯一索引 | 折叠器幂等、写入侧拒绝同名，revert 即恢复"同名可并存"；未追加列所以旧读路径零影响 | 一个人只有一行、只有一个声音 |

**本轮已落（2026-10-20，未编译，构建由维护者跑）**：

- **S1 全 7 项已落**：①分镜页自建 `ALOUD_AI_FALLBACK` 订阅（`LocalLifecycleOwner` + `Observer`，
  随页拆装，`onDispose` 里 `removeObserver`）；②冷却期命中写 AppLog 并在**同一轮冷却的第一次**
  弹 toast（`cooldownNoticeUntil` 按 `cooldownUntil` 去重）；③场景拆分失败 `catch` 里
  `sceneAttemptedChapters.remove(chapterIndex)` + toast + AppLog；④`ai_cast` 入口先查
  `AiProfileGateway.getTaskPreset(IDENTIFY_CHARACTERS ?: CHAT)`，没配模型就提示不发请求；
  ⑤结论来源标注经核实**早已存在**（分镜页每行已有"来自规则/来自 AI"），未重复造；
  ⑥`CastSpeechOverlay` 那条分支日志措辞纠正（它保留的是分析链的音色计划，不是"全降级旁白"）；
  ⑦N4 见 §4。
- **S2 全 4 项已落**：推理强度并成 DataStore 一份（旧 SharedPreferences 值 `takeLegacyReasoningLevel()`
  一次性搬进、弹窗里 chips 退役只读回显）；「正文章节预载数量」/「音频预取数量」按真实语义拆开命名，
  `audioPreDownloadNum` 补了滑块入口；两个开关改名，最终定为「多角色朗读」/「显示角色」
  （2026-10-21 第一版「按角色分声朗读」/「正文显示角色名」被判定太长，zh-rHK/rTW 原本就写作「多角色朗讀」）；
  第三个 Tab 的死口径清干净（`readAloudConfigTab` 只留 0 常规 / 1 引擎与音色，
  `read_aloud_settings_casting_tab` 从 zh-rTW/zh-rHK 一并删除）。
- **D2 的缓存键部分已落**（角色库时间戳移出 `resolverVersion`，顺带让版本号不再读库）；
  **归并② 与 S5 的转正入口同轮核实后判定不写**，见下方 S5 切片那条与 §8 D2。
- **N6 已落**，见 §4。
- **S6 界面归纳已落**（2026-10-20，D8 批复的"先只做归纳"）：「引擎与音色」页签 19 项插四个小标题
  〔声音〕〔多角色朗读（AI）〕〔切分与节奏〕〔缓存〕，项不增不删、每项读写语义一字未动，
  只做三次整块搬家（池与系统 TTS 上提到引擎那一项之后、两个总闸上提到本节开头、TTS 缓存下进缓存节）；
  小标题照听书引擎页的样式在本文件加 private `ReadAloudSectionTitle`，未抽公共组件。
- **D3（200/500 截断）已改判不做**：维护者判定"200 个已经够够的了"，见 §4 N2 与 §8 D3 的处置。
- **S5 的安全切片已落（2026-10-20），S5① 定稿写入随 2026-10-21 那一轮落地**：
  - 已落：①**非当前章也套覆盖**——分镜页缓存路径改成"当前章与非当前章同一条路"，段落重建从
    `readerChapterInputWindow.current` 扩到上/当前/下一章三份（`chapterParagraphs`），并在套之前加
    **同源闸门**：`SpeechIdentity.chapterContentHash(段落)` 必须等于那份分析的 `contentHash`
    （`ChapterSpeechSegments` 新增该字段，只有当前设置那份才带基准，退回旧划分时为空即跳过）。
    **这条闸门的粒度部分已被 D9 换掉**：现在比的是 `SpeechIdentity.alignsWithParagraphs`
    （章内起点 + 原文逐段核对），换划分方式不再判不同源，仍会拦住的只有正文真换过的情形。
    闸门是必需的：`ReaderChapterSourceParser` 在「显示角色」打开时往 `semanticContent` 注入
    `<<名字（池）>>` 标记，分析段的坐标就在带标记的空间里，标记一变整套坐标一起挪，
    不加判据套覆盖会把胶囊安到错的人身上。跳过时写 AppLog 说明原因。
    （**S8 之后这一层只做效果**——正则音色与那一句的变声器，不再改"谁说话"，所以分镜页与耳朵里的
    归属从"两份结论"变成"同一份"。）
    ②**分镜页「改归属」**（2026-10-25 由「锁定/解锁这一段」改判而来，锁被判定为多余入口已删净）：
    `OpenSpeakerPicker` / `AssignSegmentSpeaker` Intent → `CastSegmentFinalizer.reattribute`，
    一次点人 = 一次写入：走行级 `setAttribution`（只改归属四列 + `userLocked`，不整份回写分段，
    避免把库里的分析段换成覆盖切过的碎片），**同时**按 `CastQuoteTracker` 顺序量出这一段占的
    开引号序号，把本章已存在的分配行一起改名（改回旁白则删行）。为什么不新建分配行：注入标记会
    让正文坐标整体位移，见 `SpeechIdentity.alignsWithParagraphs`。改过的段 `userLocked = true`，
    现成的"跳过锁定段"判断让重新分析不再动它，归零动作是「重新分析本章」。不重启朗读、不重算当前
    播出声音，下一次出计划起用新的那一位；行右侧角标按钮与试听钮同款尺寸，有人时转主色。
    ③**场景拆分回写不再拿碎片顶掉分析段**：`withScenes` 过去把 `plan.map(SpeechPlanItem::segment)`
    整份 `replaceSegments`，而 plan 里可能是覆盖切过的碎片（正文/起止点被改过），先删后插等于
    用碎片换掉库里那条分析段；改成 `getSegments(analysisId)` 取回库自己的行、只搬场景两列再回写。
    ④**「转正」UI 入口**：配音页草稿角色行原来右侧什么都不给（`trailingContent = null`），
    现在挂进本页已有的「更多」菜单语法（`CastDraftRowActions`：转正 / 编辑），
    `BookVoiceCastingIntent.PromoteCharacter` 在 ViewModel 早已实现、全仓无调用点的状态结束。
  - 当时记下的两条核实结果（**第一条已在 2026-10-21 被推翻并落地，见下面 S5① 那条记录**）：
    - ~~**S5①「正文胶囊 → segments 定稿写入」不做位置匹配**~~：当时的理由是"分配表按 ordinal 锚定、
      segments 按 `start/end` 锚定，两者之间没有可依赖的桥，要落这条得先补 segment 外键"。
      **桥不需要建在库里**——分析刚跑完时，那一份带标记段落就在手上，按同源闸门验过再量一次即可，
      于是定稿写入以**零 schema 变更**落地。
    - **D2 的归并②（老章同名草稿卡重定向到正式档案）不需要写。**`book_character_profiles` 有
      `(bookUrl, name)` 唯一索引，同名草稿与正式档案无法共存；转正走的又是同 id 的 status 改写，
      老章分段引用的 `characterId` 本来就指向那张卡。真正要盯的是别处按名字 `REPLACE` 入库导致 id 漂移，
      那属于 S9 的身份合一，本轮不写投机 `UPDATE`。
- **S7 切片B 已落（2026-10-21，未编译，构建由维护者跑）**——旧「AI 分配角色」引擎的判定能力并进入
  说话人分析链，为切片D 的入口退役铺路：
  - **归因口径做成一档单选**（`SpeechAttributionStrictness`：`must_answer` 默认 / `evidence_only`，
    界面名「归因口径」，两档「必答（拿不准也给一个人）」/「宁缺毋滥（拿不准留空）」）。口径文本
    （`ATTRIBUTION_MUST_ANSWER` / `ATTRIBUTION_EVIDENCE_ONLY`）由代码追加在规则主体之后、输出契约之前，
    用户在提示词预设里存过内容只替换主体，改不掉口径与契约——口径现在是设置项，不再是预设的副产品。
    两档共用同一套解析器与同一份 JSON 契约：分段档漏答的段保留规则结果，原子档把没把握的句子归成
    `unknown` 组（漏引的原子同样按未识别走），所以不需要第二套 parser。
  - **口径进分析身份**：`resolverVersion` 串里追加 `strictness.storageValue`，`promptHash` 串的是
    主体 + 口径 + 契约。切档后已分析过的章在下次听到时各重跑一次 AI；这一档写入走
    `ReadAloudConfigOption.SpeechAttributionStrictness`，并按「分析模式」同口径重启朗读管线，
    免得出现"设置已切档、这一章听见的还是上一档判的归属"。
  - **规则主体并成 12 条**：并入旧引擎里与本链不冲突的判定——多重身份归同一人（只有性别或年龄阶段
    不同才当两个人）、档案把同人记成两个名字时用真名、第二人称称呼者=听者（「李元老，您这话太过了」
    不是李振富说的）、连着引号时说话人通常在交替、`speakerName` 禁写代词与叙述称谓（第一人称叙述者
    写姓名、查不到写「主角」，同称谓同写法、不编号「元老甲/乙」）、`speakerPool` 只给新人。
    「泛称是路人，不建卡」保留本链口径，没有照搬旧引擎的"按身份称谓建档"。
  - **新人建议声音池**：payload 新增 `pools`（整章取一次 `VoicePoolStore.enabledPoolNames()`），
    决策新增 `speakerPool`；只有 `characterId == null` 的新人才会带池进 `ensureDraftProfiles`，
    按名查池（`poolByName`）写 `voicePoolId` + 派生 `voiceAgeBand`，查无此池只写 AppLog 调试行、
    留空交给自动选音按性别兜底。已有角色的池一律不动——改池等于换声音。
  - **占位主名挡第二道**：`speakerName()` 追加纯数字与 `PLACEHOLDER_SPEAKER_NAMES`
    （我/你/您/他/她/它/咱/俺/本人/自己/人家/别人/旁白/叙述者/作者/读者/主播/系统/无/未知/说话人）
    过滤，从旧引擎的 `PlaceholderNames` 搬来。这类名字建卡正是"分错了还越改越乱"的放大器，
    挡下后该条决策仍生效，只是不建卡。
  - **顺带修一处历史红测**：`ReadAloudSettingsMappingTest.expectedPrefMap` 自 `4a64b4ac7` 起就缺
    `defaultNarratorVoiceId`，写映射那条按逐字段相等断言，本应早就是红的。本轮补上该键，
    同时补新键，并把测试名里的「29 键」计数去掉（计数与真实条数早已不符，留着只会误导）。

- **S7 切片C 已落（2026-10-21，未编译，构建由维护者跑）**——范围/续跑/重试归位 + 场景上提：
  - **场景拆分触发点从"进分镜页"上提到"该章分析收尾"**（③）：`RefineSpeechWithAiUseCase.analyzeExclusive`
    在说话人结论落库之后紧接着跑一次 `assignScenes`，**先落结论再落场景**——场景是追加层，拆坏了
    （超时、JSON 不合契约、这一块跳过）绝不能把已经付过 token 的结论顶掉，所以第二次 `saveAnalysis`
    只在真拆出场景时才发。`source` 沿用那一轮出处（朗读 / 预合成 / 顺带分析），AI 取消记录因此
    能定位到章。独立调用与 `SCENE_PROMPT` 一字未改，配乐场景（`AiSceneAssignUseCase`）不受影响。
  - **页面级记账全部作废**（④）：`sceneJob` / `sceneAttemptedChapters` / `sceneGeneration` / `sceneMutex` /
    `withScenes` / `attachScenes` / `analysisStillCurrent` 删除，分镜页**只读不算**场景；旧时代分析过、
    当时还没拆场景的章用详情页「重新分析本章」补。S1③ 那条"拆分失败给提示"的 toast 随之下线
    （`speech_storyboard_scene_failed` 字符串删除），失败改为在分析收尾写 AppLog。
  - **范围 = 朗读侧一档 + 结果页一处显式动作**（②）：新档「顺带往后分析几章」（`speechAheadAnalysisChapters`，
    默认 0，0..50），挂在朗读设置〔多角色朗读（AI）〕小节、紧跟归因口径；它**不进分析缓存身份**
    （只决定哪些章被分析，不改变这一章怎么判），改它不会让已分析的章作废，也不需要重启朗读管线。
    朗读服务两条预合成循环（文件与流式）在音频窗口跑完之后接 `analyzeAheadChapters`，
    窗口从当前章往后数 N 章，**音频窗口已经覆盖的那几章不重复跑**。
  - **续跑是免费的**：新 `help/readaloud/analysis/ChapterSpeechBatchAnalyzer` 是跑批的唯一实现，
    划分口径 / 版本号 / 段落重建 / 分析入口全部与实时朗读同源（旧弹窗自己抄了一套 policy）。
    库里已有当前设置那份结论的章返回 `AlreadyAnalyzed`，一个 token 不发——所以结果页「分析选中章」
    全选重跑一遍等于"补齐没跑成的"，这就是旧弹窗"续跑记账"的替代。
  - **重试** = `resetCache = true`（先 `deleteChapter` 再跑 + 解除本书 AI 冷却），落点是详情页那颗
    `Autorenew`「重新分析本章」。详情页重跑**不再只对正在读的那一章有效**：段落来源从"屏幕上的
    章节输入窗口"扩到"本地正文重建"，下载过正文的章都能重跑；两处都没有（正文未下载）返回空列表。
  - **设置块零运行按钮**：档位只做范围，跑批动作只在分镜结果页（管理态多选语法 + 一颗
    PlayArrow/Stop 复用钮，副标题显示 `已分析 x/y`）。
  - **顺带落一处 DAO 级单写者**：`ChapterSpeechDao.saveAnalysis` 改成事务内先 `deleteChapter`
    再 upsert，于是一章只剩一行，`getChapterSegments` 的 `order by updatedAt desc limit 1` 拿到的一定
    是发声那一份。代价见下方要点。

- **S7 切片D 已落（2026-10-21，未编译，构建由维护者跑）**——「AI 分配角色」入口与引擎退役：
  - **入口就地换含义，不改 id**：`ReadMenuActionDefs` 里那颗按钮的 id 仍是 `ai_cast`（它存在用户的
    顶栏/底栏按钮配置里，改 id 等于让已配好的按钮凭空消失），只把标签换成「AI 识别背景音乐」、
    intent 换成 `OpenAiSceneDialog`。不新增页面，与 §6.3「零新增页面」同口径。
  - **弹层收敛为纯配乐**（`AiCastDialogSheet`）：角色那三块（本章已分配数、分配要求预设 + 预设管理页、
    临时要求、本书角色记忆编辑器）整段下线，`page` 变量与两页结构删除；`sceneOnly` 参数失去意义，
    `ReadBookSheet.AiCastDialog` 改成 data object。头部两颗钮：左=清除本章场景
    （新 intent `DeleteChapterBgmScenes`，只清 `BgmSceneStore`，不再代删正文胶囊），右=开始/取消。
    续跑与重试两颗钮**留着**：它们读的是配乐这一趟的记账（`AiCastRunState`），不是角色那趟的遗产。
  - **引擎与文件删除**：`help/readaloud/cast/AiCastAssignUseCase.kt`、`AiCastDialogStats.kt`、
    `AiCastIdentityTest.kt` 删除；Koin 里 `singleOf(::AiCastAssignUseCase)` 与 VM/delegate 的构造参数
    一并摘掉。`castChapterPlan` / `AiCastStream` / `failureReason` 三兄弟搬到
    `AiSceneAssignUseCase.kt`（逻辑逐字未改，`internal` 可见性不变，`AiCastChapterPlanTest` 照旧跑得到）。
  - **运行侧只剩一趟**：`ReadAloudCastDelegate.startAiCast` 不再串两趟，`rolesPass` / `assignScene`
    两个开关与 `StartAiCast` 的 `presetId` / `temporaryInstruction` 字段删除；失败清单落盘、取消快照、
    `refreshAfterCast`（重排当前章 + 朗读队列）三条收尾一律保留，配乐这一趟原本就走同一套。
  - **`AiCastPresetStore` 从 558 行收到 168 行**：只剩场景提示词（`scenePrompt/saveScenePrompt/
    defaultScenePrompt`，单行 `scene_output_format`）+ 续跑记账 + 旧推理强度搬迁。**旧的分配要求行与
    角色那四条固定附加行留在库里、不再被任何代码读取**——那是用户写过的文本，静默删除不可逆，
    清理动作归 S9。这些旧行也不会突然冒出来给用户看：AI 提示词管理页按 taskType 白名单枚举
    （`AiPromptConfigViewModel.kt:37-87`），`cast_assign` 不在清单里。
  - **场景提示词补了唯一入口**（这是"零能力损失"里唯一必须新加的一块）：管理页退役后这条文本没有
    地方可改，而它决定配乐识别准不准。落在弹层底部一张 `CollapsibleHeader` 折叠卡片，展开才读库，
    「保存」一次点 = 一次写库，「恢复默认」只把框里换成出厂文本、不顺手覆盖用户那一份。
  - **`CastMemoryMirror` 反向退役**：`applyMemoryToProfiles` / `reconcilePoolsWithCast` / `applyUserEdits`
    删除（记忆文本不再有 AI 产出方，权威在人物档案表）。保留**档案 → 记忆**（`applyProfileToMemory`，
    人物详情页保存时整行替换）与改名/改池的随行同步（`syncCharacterRow`，配音页与正文胶囊两个入口）。
    `UserEdits` / `diffUserEdits` 这对纯文本函数暂时留着给测试盯解析口径，随 S9 一并清。
  - **改名 / 换池的随行同步扩到说话人分段表**（2026-10-25）：库里存着角色名的冗余列只有两处
    （`chapter_role_assignments.characterName` + `voicePoolLabel`、`chapter_speech_segments.characterName`），
    四个写入点以前只同步前者。自 S5① 与分镜页「改归属」起，分镜页那一行与正文「没分配过的那几句」
    显示的就是后者（走 `CastSegmentFinalizer.inferredLabels`），不同步就成了
    「人物页改了名，分镜页和部分句子还是旧名」。现收成 `BookCastStore.syncRenamedReferences` 一处
    （分配行 + 分段行两个 `UPDATE`），配音页编辑、人物详情页改名、进配音页时的档案→角色回填、
    正文胶囊弹层改名四个入口与同名折叠器都改走它。分段行**只按 `characterId` 认人**：规则/正则归因那些段
    的名字是从正文读出来的字面称呼（"他""老者"），按旧名去改会把别人的话改掉。
    顺带补一个真洞：`syncFromProfile` 过去在「只有档案、还没有配音角色行」时（AI 刚认出的临时人物、
    这本书没进过配音页）直接 `return false` 什么都不做。现在仍**不**替他建配音行（建行等于把没转正的人
    拉进配音链路并自动挑声线），但两张引用表照样改名。
    口径确认（维护者拍板）：**知识图谱与人物列表继续只列转正的人物**——
    `BookKnowledgeDao.getCharacterProfiles` 里 `includeDrafts` 默认 false 那条注释是有意口径，不动；
    配音页 / 分镜候选 / 正文候选能看到临时人物，属于"配音链路内部"，不算四处不同名。
  - **⑤ 吸收清单逐项打勾（本轮验收）**：推理强度＝朗读设置那一份，旧弹窗值首次打开即搬走并删键；
    范围＝朗读侧档位「顺带往后分析几章」+ 分镜结果页显式动作（切片C），配乐这一趟自己仍带范围控件；
    续跑/重试＝`ChapterSpeechBatchAnalyzer` + `AiCastRunState`；逐句归属＝朗读链路产出，手工改走
    `ConfirmRoleCast` / `CreateRoleCast` / `UnassignRoleCast` 与配音表；称呼归并＝档案 `aliasesJson`
    随 payload 的 `characters` 进模型（切片B）；新人池建议＝`speakerPool`（切片B）；提示词＝
    `ANALYZE_SPEECH` 任务预设走 AI 提示词管理页 + 本轮的场景提示词卡片。**没有一项落在"删除"**。
  - **字符串口径**：`ai_cast_*` 从 50 键删到 28 键（角色/预设/记忆那 22 条无调用方），保留键里
    「分配」措辞改「识别」、`ai_cast_model_required` 与 `ai_cast_request_hint` 改成配乐口径，
    `speech_analysis_reasoning_level_summary` 去掉「与阅读页的 AI 分配角色共用这一项」那句旧说明。
    values / values-zh-rCN 两份同步（其余 locale 本来就没有这批键，回落英文）。
  - **三张基线表逐条核对：本批零变化，不动 `build.gradle.kts`**。删除的两个文件在 `help/` 下（护栏只数
    `io/legado/app/ui/`），改动的 17 个 UI 文件里 `appDb.` / `data.dao.` import / 旧偏好调用计数全为 0，
    与 HEAD 一致——`ReadBookViewModel.kt to 0` 那两条依旧吻合，棘轮既没被顶高也没被放宽。

- **S3 已落（2026-10-21，未编译，构建由维护者跑）**——音色标签 + 池分组，四层接通：
  - ①新表 `voice_labels`（`voiceId` 主键，`gender/ageBand/tagsJson` 三栏都可空 = 未标注）**追加到手写迁移
    `migration_127_128`**，用 `CREATE TABLE IF NOT EXISTS`，与那条里既有的守卫式写法同一套。不给
    `read_aloud_voices` 加列：那一行由引擎同步逐字段 REPLACE 重建，挂在行上的用户标注会被抹掉
    （与 `voice_pool_members` 同一个理由）。
  - 读取侧唯一出口 `VoiceLabelStore.classificationMap()`，回落顺序定死**用户标注 > 池分类 > 引擎声明 >
    名字启发**。一行标注都没有时行为与引入它之前完全一致，所以这是加法不是切换，回退 = 清空该表。
  - ②池的性别/年龄段可编辑：`VoicePoolStore.createPool/updatePool` 签名补两栏，多角色规则页的池编辑弹窗
    在 `showsClassification` 打开时多出「性别 / 年龄段」两栏（候选由小到大、`未知` 固定收尾，与
    `VoicePoolStore.isClassifiable` 同一套枚举）；`PrepareChapterSpeechPlanUseCase` 不再把性别丢掉。
  - ③成员写入收成「一音色一组」+ 存量折叠：`VoicePoolStore.foldDuplicateMembers()` 幂等、prefs 标记
    只跑一次，保留哪一池按可复现顺序判（池启用 > 池有结构化分类 > …），差异写 AppLog 报告。
    **没有加唯一索引**（D4）。
  - ④"成员标签优先于池分类"定死在 `classificationMap` 一处（`VoicePoolStore:327` 记下口径），选音只认那一处。
  - ⑤备份补 `voiceLabels.json`：`Backup.kt` 写出、`Restore.kt` 回读，与既有 list 恢复同套写法。
  - **与方案的一处口径偏离（记录在案）**：原计划"未标注显示灰字而非硬猜"落在列表行上，实际做成
    **编辑弹窗里的 `labelFallback` 提示**（"这一栏现在按 ×× 走"，已本地化、绝不写回表）。列表行不掺灰字，
    免得与引擎声明那一份混在一起看不出区别。

- **S4 已落（2026-10-21，未编译，构建由维护者跑）**——选音合一，两条链折成一条打分链：
  - `CastVoicePicker.ensureVoice` 的 `random()` 退役。两条链的候选折算成同一种 `VoiceMatchCandidate`、
    同一份比较器 `VoiceMatchScoring.order`（占用少 → 年龄段匹配 → 风格关键词 → 引擎 → 名字 → id），
    全序可复现；`AutoAssignVoiceBindingsUseCase` 改为共用它，**年龄第一次真正进播放路径**。
  - "每章新建的男青年不要永远落在池子第一格"这一条不再靠随机承担，而是靠占用数：第一个角色占走 A，
    第二个看到 A 用过了就排到 B。引擎取这一池多数人在用的那一档，数量相同按引擎名排。
  - N3 一并落：`syncBinding` 一律写 `source=auto`、`bindUserVoice` 一律写 `source=user`，
    两处主体 id 全集统一走 `CastSubjectResolver.subjectIdsFor`（精确名/别名比对，不再各推一套、
    不再用 SQL LIKE 单条查找）。
  - 测试：`CastVoicePickerTest.kt` 删除（它断言的就是"随机补音"那套），`VoiceMatchScoringTest.kt`
    接管比较器的可复现性与优先级断言。

- **S5① 已落（2026-10-21，未编译，构建由维护者跑）**——定稿写入。**上面那条"不做位置匹配、
  要先给分配表补 segment 外键"的结论已被推翻并落地，且零 schema 变更**：
  - 桥不在库里的列上，而在**用同一份带标记段落当场量一次坐标**：`CastSegmentFinalizer.replay()`
    经 `ChapterSpeechBatchAnalyzer` 的同一套 splitPolicy / resolverVersion 重建段落，先过同源闸门
    （`SpeechIdentity.chapterContentHash(paragraphs) == stored.contentHash`），再把
    `(ordinal → 台词范围)` 与 segments 按区间重叠配对。分配表仍是唯一的用户入口，segments 才是真相表。
  - 命中就一次写齐 `roleType/characterId/characterName/source=user/userLocked=true`，走行级
    `setAttribution`（**不整份 `replaceSegments`**——界面上那一行可能是切过的碎片）。写完现成的 6 处
    "跳过锁定段"立即生效，重跑分析不再改动那几句；库里那一行已经一致时跳过写入，`updatedAt` 不空跳。
  - 挂点**两个入口、一条判据**（判据＝"已经是这个角色的定稿就一行都不写"，抽在 `pendingOf` 里共用）：
    ①`CastAssignmentStore.assign()` 上完一行分配后立刻调 `CastSegmentFinalizer.finalize()`——用户点
    「确认／新建」的当下就写进库里那份分段，**分镜页立刻可见**，不必等本章重新准备。这一路手上没有那份
    正文，所以按当前设置重建带标记正文再过同一道同源闸门；对不上（这一章还没分析过／库里那份非当前设置
    生成）就什么都不写，只留一条 AppLog，**不影响这一次分配成立**。②`PrepareChapterSpeechPlanUseCase`
    的 AI/规则结论之后、音色自动分配之前仍跑 `replay()` 兜那两种情况，下游一律改用 `finalized`：于是
    **分镜页、听书队列、自动选音看到的是同一份归属**，且当场写过的那一路到这里是空转（不重刷 `updatedAt`）。
    回放失败只写 AppLog 并按分析结果朗读，取消照旧透传。
  - **耳朵那一条没变**：整章队列在准备时一次建好，改归属不重建队列，正在播的本章仍要等重新准备才听得出来。
    这条曾被我当成"分镜页也要等"写进交付说明，被维护者否掉——页面读的就是库里那份分段，写它不涉及重新分析。
  - 取消分配的代价（`unassign` / `deleteChapter`）：**必须先放开定稿再删分配行**——这一句的坐标要靠
    这一行注入的角色标记才量得到，行先删就再也量不到，那一段会一直挂着旧归属和锁定标记。取消后那一段
    回落旁白，AI 的原判断要重跑本章分析才回来。
  - 粒度随划分方式：「整段」下一个朗读单元就是一整段，定稿与句级变声都作用在整段上；要按句定稿得把
    划分方式换成句级。冲突时以哪边为准在代码里写死（用户定稿赢）并记一条 AppLog，不静默。

- **S8 已落（2026-10-21，未编译，构建由维护者跑）**——覆盖层收缩为**效果层**：
  - 删掉「按正文标记认领说话人 + 认领不到抹成旁白」整段逻辑，连带 `CastSpeechGateway` 的
    `characters` / `subjectIdsFor` / `ensureCharacterVoice` 三个读方法与仓库实现（网关只剩正则效果 +
    句级变声器两项）。现在这一层只做两件事：正则角色顶音色/放音效、那一句的专属变声器写进
    `segment.voiceEffect`。
  - 切单元只在**真配了句级变声器**的那几句发生（`spansOf(...).filter { it.effect.isNotBlank() }`），
    所以分镜页那一行与实际发声单元一一对应，不再出现"界面上一段、队列里四块"。
  - `speechFor` 随之不再是 suspend；日志改成一份汇总（本章 N 个朗读单元；正则角色顶音色 x 段、
    吞字放音效 y 段；句级变声器 z 段；丢掉 k 个不发声单元）。
  - **行为后果四条，交付必须告知**：①没分配过的句子现在保留 AI/规则的结论，不再被抹成旁白；
    ②按句换声/换变声需要句级划分，整段划分下整段一起定稿；③关掉「显示角色」= 正文没有标记 =
    没有按句的分配可读（音色仍来自分析链那一份）；④取消分配 = 回到旁白，要 AI 的原判断就重跑本章分析。
    正则命中优先级仍最高，`regex_cast_dialog_hint` 已写明"会盖掉这一句原本分好的角色"，
    `multi_role_cast_summary` 补上"分配结果会写进正文标记并锁住那几句"。
  - `CastSpeechOverlayPieceSplitTest` 的类注释同步改成"切口只服务句级变声器"，断言未动
    （`spansOf`/`piecesOf` 这两层的行为与 S8 前一致）。

- **S9 部分落地（2026-10-21，未编译，构建由维护者跑）**——身份合一：**规则已生效，表结构尚未并表**：
  - **已落**：N5 搜索口径（`searchCharacterProfiles` 排除草稿，与 `getCharacterProfiles` 同口径，
    唯一调用方 `AiToolRepository.searchBookCharacters` 因此不再把临时说话人当正式角色返回）；
    新 `CastIdentityFolder.fold(bookUrl)` 幂等折叠同名多池——keeper 按「有绑定 > 有音色 > 最近更新 >
    id 最小」选，空栏从被丢的行补齐、`createdAt` 取最早，三处重映射（`chapter_role_assignments`、
    `chapter_speech_segments`、`book_voice_bindings`）+ 记忆池栏随行 + `CastProfileMirror.ensure`，
    被丢的池/音色/变声/气泡全部进 AppLog 报告；挂点在 `CastAssignmentStore.migrateLegacyProfiles`
    **开头**（必须早于档案导入，否则同一趟会照档案把刚并掉的那个人重新长回来）；
    写入侧一人一名（`create` 按名字拒绝、`resolveCharacter` 名字优先、池只作挑选偏好、
    `BookCastStore.createCharacter/updateCharacter/syncFromProfile` 同名即挡、
    `migrateLegacyProfiles` 的档案→角色补建先按 id 认、认不到再按名字认，两边都认不到才建行）；
    备份恢复同口径（`Restore.kt` 按名字认本地行，老备份里的同名两行在恢复当场并成一个人）；
    `CastCharacterDao.getByNameAndPool` 随调用点清零删除；`cast_create_exists` 文案改为
    「这个人已经建过了（同名只留一个角色），可直接点「确认」分配」。
  - **未落，明确留给下一刀**：`cast_characters` 物理退役与两个镜像停写、档案表追加
    `voiceEffect/sortOrder/bubbleRuleJson` 三列、两套主体 id 归一、`book_cast_memory` 文本改结构化导入
    （连带 `UserEdits`/`diffUserEdits` 清理）。原因：这一半是**结构变更 + 约 20 个文件（含 UI）的读写搬家**，
    在"不能自行提版本号、也不能跑构建自证"的前提下与"一人一名"这条用户可直接感知的规则捆在同一轮，
    风险和验收面都会失控。S9 验收列（"一个人只有一行、只有一个声音"）已由行为层达成，
    表层并表可独立回退。
  - **唯一索引仍是 `(bookUrl, name, poolLabel)`**：不改索引（改主键/改唯一索引属结构变更，会要求重装），
    一人一名靠"写入侧拒绝 + 运行期折叠"两处保证，`insertIgnore` 只剩两次写入撞车的竞态兜底。
  - **三张基线表逐条核对：本批（S3/S4/S5①/S8/S9）零变化，不动 `build.gradle.kts`**。按护栏同一套正则
    在 `app/src/main/java` 全树重跑一遍计数：`io/legado/app/ui/` 下每个文件的 `data.dao.` import 与
    `appDb.*Dao` 访问，与 `legacyDaoInjectionBaseline` / `legacyUiDaoAccessBaseline` 逐条**相等**
    （既没顶高也没放宽，`ReadBookViewModel.kt to 0`、`CloudTtsViewModel.kt to 5` 均吻合）；
    `legacyPreferenceCallBaseline` 全树无新增旧偏好调用（含 `Restore.kt to 2`）。
    本批唯一进入基线视野的改动是 `Restore.kt` 的角色恢复查找，它不含 `getPref/putPref` 增减；
    新增的 `CastIdentityFolder`、`CastSegmentFinalizer`、`VoiceLabelStore` 等都在 `help/`、`data/` 下，
    护栏不数这些目录的 DAO 访问（它们本就该直接持有 `appDb`）。

- **D9 已落（2026-10-21，未编译，构建由维护者跑）**——改设置不再让已有的分镜作废，判据换成
  "与粒度无关的同源核对"：
  - **一条新判据**：`SpeechIdentity.alignsWithParagraphs(paragraphs, segments)`
    逐段核"章内起点 + 原文"落在现在的同一处字上；不比 `chapterContentHash`（那份哈希含单元下标与起点，
    换划分方式必变）。它成为**唯一的同源口径**，五个调用点全走它：`AnalyzeChapterSpeechUseCase` 复用闸门、
    `ChapterSpeechBatchAnalyzer.isAnalyzed`（续跑判据）、`CastSegmentFinalizer.bridgeOf` + `replay`（定稿两路）、
    `SpeechStoryboardViewModel.cachedChapterPlan`（分镜页套分配表覆盖）。
  - **读侧只认"这一章存下来的那一份"**：DAO 新增 `getStoredAnalysis`（`status in success/partial/failed`
    取最近一行，不比版本号），`getChapterSegments` 改成挑同一行，`getChapterSummaries(bookUrl)` 去掉版本号参数；
    `getAnalysisByResolverVersion` / `getChapterCurrentAnalyses` / `getChapterSegmentsFor` /
    `ChapterSpeechSegments` / `ChapterSpeechBatchAnalyzer.resolverVersion()` 全部删除。
  - **AI 那一步跟着收口**：`PrepareChapterSpeechPlanUseCase` 只在**本轮真的新跑了分析**时过
    `refineSpeechWithAi`（`analysis.fromCache` 即整份复用），发请求的只剩"没跑过的章"和「重新分析」。
  - **段内相对坐标单独设防**：`ChapterSpeechAnalysisResult.paragraphSpaceMatches`（内容哈希完全相等）
    只给 `ResolveLocalSpeakersUseCase` 用——跨粒度时重认说话人会查到别的句子上，那一步整趟跳过；
    绝对坐标那一路（回放、覆盖、播放队列）跨粒度照常用。
  - **徽标整套删除**：`speech_storyboard_stale_config` / `speech_storyboard_stale_hint` 两条文案
    （`values` + `values-zh-rCN`）、`StoryboardChapterUi.usesCurrentConfig`、
    `SpeechStoryboardUiState.usesCurrentConfig`、列表行与配置卡两处渲染点、`ChapterSpeechSummary.isCurrent`。
    批跑汇总那句"已是当前设置那份"跟着改成"已有结果"。
  - **界面话术同步**：`ReadAloudPlayerViewModel` 里分析模式 / 归因口径 / 划分方式三处的重启注释改成
    "只为**还没跑过的章**立刻按新口径跑，已经做好的那一份不动"（三处 `restartReadAloudPipeline` 调用保留）。
  - **测试源集对齐**：两个 `MemoryChapterSpeechGateway` 补 `getStoredAnalysis`、删
    `getChapterSegmentsFor`、`getChapterSummaries` 去版本号参数（缺一个整个测试源集编不过）。
  - **本批基线核对**：改动落在 `ui/book/readaloud/{storyboard,player}/` 的只有注释、状态字段与 gateway 调用
    参数，`data.dao.` import 与 `appDb.*Dao` 访问、旧偏好调用三项**零增减**，`build.gradle.kts` 三张表不动。

要点：

- **S5 必须早于 S8**。定稿保护是硬前置——先收覆盖层再补定稿，会真冲掉用户手工劳动。
  现成的 6 处"跳过锁定段"判断（`RefineSpeechWithAiUseCase.kt:272,297,460,494`、
  `ResolveLocalSpeakersUseCase.kt:80`、`help/readaloud/resolve/LocalCharacterSpeakerResolver.kt:153`）
  在 `userLocked` 有人写 true 之后立刻生效，**"重跑不冲掉定稿"是现成逻辑，缺的只是写入方**。
- S1–S4、S6 无阻塞，可立刻做，且都不动数据权威。
- **S7 当时会让全书分析缓存失效**（prompt 变 → `promptHash` 变 → 重新分析）。一次性成本必须提前告知。
  切片B 落的是**两笔**：主体规则并成 12 条改了 promptHash，口径进键又改了 `resolverVersion` 本身。
  **这条自 D9 起不再发生**：那一串版本号只写进**新落下**的那份，不再是复用判据，改任何口径都不再重烧
  （要按新口径重判，用户点「重新分析」）。
  若同时采纳 D2（角色 revision 移出缓存键），则此后"改角色"不再引发全书重烧——这条收益比 S7 的
  一次性代价更值，建议两件事同一轮告知用户。
- **「分镜场景」没有独立开关**（切片C 核实结论，别再当待办）：§6.2 那行把它列进设置块是早期草稿，
  §6.3 的"项不增不删"与 line 31 的旋钮清单都没有它，S2/S6 的现场也没有。定案：**场景随分析跑，
  不设第二把闸**——规则模式本来就不拆场景，`isWorthRunning()` 已经把"多角色没开 / 纯规则模式"
  这一档挡在跑批外面，再加一把开关等于给同一件事两个入口。
- **"一章只留一行"在 D9 之后不再是代价**：过去改一次设置就会写新行、顺手删掉旧行，所以"改回去要重烧"；
  现在改设置根本不重跑，旧行只在**没跑过的章**或「重新分析」那一刻被替换。用户锁过的那几行
  （`userLocked`）与定稿层（`chapter_role_assignments`）在替换后由回放进段，不会跟着旧行一起丢。
- **实时 Page 播放与其余三处重建仍不同粒度**（预先存在的偏离，本轮不动）：`ReaderFragment` 在
  划分方式为「整页」时传 `contentSplitMode = Page`，而预合成 / 顺带分析 / 分镜页重建一律用
  `pageStarts = listOf(0)` 的整段视图（页边界只在"这一章真的在屏幕上排过版"时才存在）。
  三者之间彼此一致，只有"整页模式下的实时那一章"是另一种粒度。
- 每步只留一条可观察验证，见表格最后一列。

---

## 8. 决策点（请按编号批复，未批复的按"推荐"执行）

**贯穿原则（你定的）：两方对同一功能各有设置或各有规则，一律融合成同一处的可选项，不做删除式取舍。**

**D1 说话人真相 = segments，分配表降为定稿层。**〔已定，**已执行（2026-10-21：S5① 与 S8 同轮落地）**〕
L7 的完整达成方式就是 S5；不再让覆盖层拥有"谁说话"的语义（S8）。
**落地方式与当初的写法有一处不同**：原计划"给分配表补一列 segment 外键（写入侧一起改）"不需要了——
定稿靠**当场量一次坐标**写回 segments（`CastSegmentFinalizer`，过同源闸门才敢量），零 schema 变更。
量坐标有两个入口、一条判据：用户点「确认／新建」的当下（`finalize`，按当前设置重建带标记正文），
和出计划时（`replay`，手里就拿着分析那份段落）。分配表于是只剩两个职责：
用户的建行入口，和"那一句配了哪个变声器"的存放处。

**D2 把角色库整体从分析缓存键里移出；名册变化的影响改走本地归并。**〔已批复（按推荐：移出）；
键的部分 2026-10-20 已落；归并② 与 S5 同轮核实后**判定不写**——`(bookUrl, name)` 唯一索引让同名草稿与
正式档案不能共存，转正又是同 id 改 status，老章引用的 `characterId` 无需重定向，见 §7 本轮已落〕
现状：改任一正式角色（转正、改别名、换池、详情页点保存）→ 全书 AI 分析作废重烧
（`RefineSpeechWithAiUseCase.kt:158-165`、`:174`；键的构成见 `SpeechIdentity.kt:17-22`）。
设计动机是"名册就是发给 AI 的候选清单（每章分析现读，`RefineSpeechWithAiUseCase.kt:978-979`），
名册变了旧结论就是按旧名册判的"。这里面对的一半是**后续新章要用新名册**——这本来就自动成立，因为每次
分析都现读；错的一半是**追溯性地把已落库的结论判成过期**：归属结论（segments 的 characterId）是历史事实，
名册变化不改变"那句话当时是谁说的"。
→ 缓存键只留真正的解析口径：模式 / 切分规则 / 模型 / prompt（`promptHash` 已覆盖，
`SCENE_PROMPT` 也刻意排除在外，`:1152-1155`——设计者知道粒度该怎么切，只是角色这一项搭错了车）。
→ **名册变化的影响用"归并"而不是"重烧"承接**，这是两条零 token 的本地动作：
①**转正**＝纯 status 改写（`BookVoiceCastingViewModel.kt:341-344`），卡 id 不变、句子归属不变，
**绝不该进缓存键**；②**新人入库后老章的草稿卡**＝按名字/别名把草稿卡的句子重定向到正式档案
（行级 `UPDATE characterId`，复用 `CastProfileMirror` 已有的精确名兜底口径），等价于用户口述的
"之前分析是这个角色的，替换成另一个就行"。
**行为结果**：你维护角色档案不再引发整本重新分析；改名/加别名后，旧章里同名的草稿卡能归并到正式角色上；
想让旧章用新名册重新判一次，用结果页的「重新分析本章 / 批量重分析」显式动作。
**代价**：旧章不会因为档案变化**自动**变得更准——只有你显式重跑才会。这是有意的：把"重烧一本"的权力
交给用户而不是交给一次头像保存。
**一次性代价（随本轮落键已经发生）**：版本号构成变了，已经落库的分析行按新键查不到，
所以每本书已分析过的章在**下次真正听到时各重跑一次 AI**（真金白银的 token）。旧行没有删，
把该字段放回 `resolverVersion` 即回到旧口径；旧的分析行始终没删，能不能立刻重新命中，
取决于那行存的版本号是否等于按当前角色库时间戳重算出来的那一串。
**一键归零**：把该字段放回 `resolverVersion` 即回现状，无数据迁移。
**排期耦合**：见 N1——S5 补转正入口必须与本条同轮落。

**D3 三个截断上限一律改成"按本章出场 + 全书高频"取，不按 id 顺序砍。**〔已批复，**后改判：不做**
（2026-10-20，维护者："200 个已经够够的了"）〕
现状 200（`RefineSpeechWithAiUseCase.kt:978-979`）/ 500（`CastSubjectResolver.kt:41,63`）。
**行为结果**：长篇角色多的书不再出现"AI 认得出但绑不上音"。不修则 S9 折叠时档案侧查不全，
会把该保留的行当"没有档案"重复建。
**改判后的处置**：本条不再排期。S9 折叠若真撞上档案侧查不全，届时按"取满 80 条仍不够"这一具体证据
重新评估，不要提前为量级不明的场景建排序查询。

**D4 池收成互斥分组（一音色一组），1:1 靠写入侧 + 存量折叠，不加唯一索引。**〔已定，沿用〕
理由：给已有表加索引属结构变更，本 fork 不能自行提版本号（`AppDatabase.version = 132` 须与上游一致），
为一条业务规则付"可能要求重装"的代价不划算。
**代价（已接受）**：一个音色不能同时属于「女青年」和「客服腔」；要多标签走 `voice_labels.tagsJson`。

**D5 归因口径两档都留，做成单选（必答 / 宁缺毋滥）。**〔已定，沿用〕
默认「必答」——漏分会让整句掉回兜底音，比错分更难归因。两档共用同一份判断规则与输出契约，
切换只替换口径文本，不动 `pools`/`characters`/契约字段，因此不会分裂解析器。

**D6 同名多池折叠：一人一条记录、一个声音。**〔已定，**已执行（2026-10-21 S9）**〕
**内容代价（已接受）**：「李星菲（女少女）」与「李星菲（女中年）」并成一条，其中一个声音被丢弃；
想换年龄音只能改那一条的池。折叠器必须①按"有绑定/有音色"选保留行②把被丢的池/音色/变声/气泡写进
迁移报告③幂等（`backfillAll`/`ensure` 挂在进配音页路径上，重复执行必须无变化）。
**落地时的三处补充**：折叠器是 `CastIdentityFolder.fold(bookUrl)`，三条都按原口径实现，其中 keeper
的完整次序是「有绑定 > 角色行自己填了音色 > 最近更新 > id 最小」（后两档只为保证同一份数据每次跑出
同一个结果）。②**挂点必须在 `migrateLegacyProfiles` 的开头**，不是原计划写的"随 `backfillAll`/`ensure`
顺带跑"——那一趟紧接着照人物档案补建角色行，顺序反了会把刚并掉的那个人当场重新长回来，幂等就破了。
③光折叠存量不够，**写入侧四入口同时改成按名字拒绝**（弹层「创建」、配音页新建、配音页改名、
官方人物详情同步），外加备份恢复也按名字认本地行；否则用户下一次就再造一个同名。
④唯一索引仍是 `(bookUrl, name, poolLabel)`，没有动结构。

**D7 「AI 分配角色」弹窗退役，能力全部吸收。**〔已定，**已执行（2026-10-21 S7 切片D）**〕
强度进设置块（唯一一份）、范围变朗读侧档位「顺带往后分析几章」、prompt 跳管理页、书级记忆改读档案表、
配乐场景原样独立保留。**弹窗退役的验收标准 = §7 S7 第⑤条的吸收清单逐项打勾**——已逐项核过，
见切片D 记录里那条打勾清单；其中两处与旧文案不同口径：`ai_cast` 那颗按钮的 id 保留（改名会让用户
已配的按钮消失），场景提示词在配乐弹层里补了一张折叠卡片（管理页退役后它是唯一入口）。

**D8 朗读设置的宿主形态统一。**〔已批复（按推荐：本轮只做 S6 归纳，形态统一留到浮层迁移那轮）；
S6 的四个小标题已落（2026-10-21）〕
现状双宿主（Nav3 整页 + `AppModalBottomSheet`，见 §3.6）。归纳（S6）不依赖形态统一，
且共用同一个 `ReadAloudConfigContent`，改一处两入口同时生效；贸然统一会牵动
`docs/dev/readaloud-player-morph.md` 那条线，属另一件事。

**D9 已经做好的分镜是"这一章的结果"，不随设置作废。**〔已批复（2026-10-21，维护者口述，本轮已落）〕
维护者原话三条，逐条对应到实现：
①"已经完成的分镜结果，主要模型提示词规则方法改变了就不可用，这个逻辑也不对，已经生成的还是按照之前的来，
新的按新的来"；②"内容划分方式改了也继续念，这个跟你 AI 分析说话人有啥关系呢"；
③"如果用户没有主动地去点重新分析，就没必要（重新划分）"。

**为什么以前会那样**：那一行结果的主键里写着一串"当前设置的指纹"（`resolverVersion`：划分方式 + 分析模式 +
模型 + 提示词 + 归因口径），读的时候拿现算的指纹一比，不一样就当这一章没结果 → 重切、重发 AI、
旧的还常被新行删掉。**根子是把两件事存成了一件**：朗读单元的粒度（怎么切）和台词归属（谁说的），
再加上一个随设置漂移的键。

**改法（行为口径）**：读的时候只看"这一章有没有结果"，有就整份照用。唯一还算数的核对是
**那几段量得到量不到现在这份正文**——`SpeechIdentity.alignsWithParagraphs` 逐段比"章内起点 + 原文"，
与粒度无关（再怎么切，同一个位置还是同一个字）。真会判不同源的只有正文换过：重新下载、改替换规则、
开关「显示角色」。所以会重跑的只剩两种：这一章从没跑过（或正文换过），以及用户点「重新分析」
（那里先删掉库里那一份）。改设置不再重切、不再重烧、不再删旧行。

**顺带删掉的两件东西**：章节列表那枚「按以前的设置分析，听书没用上」徽标和配置卡里那段说明
——它的唯一理由是"朗读不会用这份"，这句话现在不成立了（N6 的判据修正随之作废）。
`ChapterSpeechSummary.isCurrent`、`usesCurrentConfig`、`ChapterSpeechSegments`、
`getAnalysisByResolverVersion` / `getChapterCurrentAnalyses` 一并删除。

**保留的一处细账**：`ChapterSpeechAnalysisResult.paragraphSpaceMatches`（内容哈希**完全相等**才算）。
它只保护一个消费者——`ResolveLocalSpeakersUseCase` 重认说话人时用的是分段行上的
`paragraphIndex / start / end`，那是**段内相对坐标**，跨粒度去查会查到别的句子上。粒度不一致时那一步
整趟跳过（什么都不改），音色照样当场按名册现算。绝对坐标那一路（定稿回放、分配表覆盖、播放队列）
不受影响，跨粒度照常用。

**代价（已接受）**：①换划分方式后，已经读过的章**还是旧粒度**，听感上"设置改了没反应"——要它按新粒度，
点「重新分析本章」，或在列表里删掉那几章的分镜再跑批；纯规则模式同样如此，规则切出来的那一份也是
"已经做好的结果"。②规则模式读过的章，之后打开 AI 模式**不会自动补判**（那章已有结果），「分析所选」
是续跑语义（有结果的章跳过）；想按新口径重判，同样走「重新分析」。③正文换过的那一章会整章重切重判，
用户锁过的那几行靠 `userLocked` 与定稿层（`chapter_role_assignments`）回放保住。
**一键归零**：把 `AnalyzeChapterSpeechUseCase` 的复用判据换回
`getAnalysis(contentHash, resolverVersion)` 精确命中即回旧行为，无数据迁移（旧行始终没删）。
**验证边界**：未编译。见 §10 新增的三条真机路径。

---

## 9. 风险与迁移边界

- **缓存失效**：S7（prompt 并集 → `promptHash` 变）当时引发过全书重分析；D2 之后"改角色"不再引发，
  **D9 之后连改口径也不再引发**——版本号只写进新落下的那一份，复用判据换成与粒度无关的同源核对。
  都在交付说明里明写成本，不接受"先松基线再迁移"式的静默失效。
- **D9 的失效面（同源核对判错的方向）**：`alignsWithParagraphs` 判"对不上"就整章重切重判，
  判"对得上"就直接照用。假阴性（该重跑却没重跑）比假阳性更隐蔽：屏幕上仍是旧那一份，但它是
  同一批字，念出来不会错行，只会"归属停在旧判断上"。所以判据只做一件事——逐段核原文落点，
  不做区间包含、不做相似度。正文真换过（重新下载、替换规则、开关「显示角色」）时坐标必变，
  这条判据能抓到。
- **追加迁移的写法**：本 fork 最新本地扩展在 `migration_127_128`（`DatabaseMigrations.kt:36` 起），
  同一条里既有 `CREATE TABLE IF NOT EXISTS regex_cast_groups`（`:39`）也有守卫式 `ALTER`
  （`voice_pools.gender/ageBand :119-127`、`book_character_profiles.voicePoolId :153-158`）。
  S3 的 `voice_labels` **已按这条落**（`CREATE TABLE IF NOT EXISTS`，2026-10-21）；S9 的三列**尚未追加**——
  那一半与角色表退役、约 20 个文件的读写搬家同属结构收尾，见 §7 的 S9 记录与"未落"清单。
  真要补时必须沿用守卫式写法，且补前去 `app/schemas/` 与 `autoMigrations` 覆盖区间双重核对。
  **不要因为"版本号不变 Room 就不重跑迁移"而拒绝追加**——线上库靠 `autoMigrations` 链升上来，
  真机都完整跑过手写迁移，追加 `ALTER` 对新旧设备都生效。
- **不要补加"看起来漏了"的列**：`underlineRoundCap`/`underlineFeather`/`characterRole`/`manualNineSlice`
  这类由上游 `AutoMigration` 补齐的列，重复 `ADD COLUMN` 会让整个迁移失败、把设备卡在旧版本。
  补列前去 `app/schemas/` 对应 JSON 核对——但 JSON 可能落后于 `@Entity`，判断"某列在用户库里是否存在"
  要同时看手写 `ALTER` 与 `autoMigrations` 覆盖区间。
- **行级操作 ≠ 结构变更**：S9 的折叠与重映射是 `UPDATE/DELETE`，不改主键、不改列类型，
  所以覆盖安装可升，不需要卸载重装。改主键/改列类型/加唯一索引才需要重装或等上游提版本。
- **冲突处理要显式**：S5 期间同一章可能分配表与 segments 都有结论，**冲突以哪边为准必须在代码里
  写死并记日志**，不能静默——**已按这条落（2026-10-21 S5①）**：当场与回放两处都是用户定稿赢，被顶掉的原判断
  数量汇成一条 AppLog（当场：`定稿: 第N章第 x 句改给「…」…顶掉原记「…」`；兜底：`定稿回放: 第N章有 x 段原记「…」`）。S9 的文本记忆导入必须按名字去重并在
  报告里列冲突，同名两行不能静默并成一行（**这条随结构化导入一起留下一刀**；本刀的折叠报告已按
  "被丢的池/音色/变声/气泡逐条列出"实现）。
- **正则角色是第三条独立路径**，本次不动它，但它优先级仍最高；界面必须写清"正则命中会顶掉 AI 结论"。
- **架构护栏**：S5/S9 会动 `io/legado/app/ui/` 下的 ViewModel 与 DAO 访问。提交前必须回
  `build.gradle.kts` 核对三张基线表（`legacyPreferenceCallBaseline :222`、
  `legacyDaoInjectionBaseline :249`、`legacyUiDaoAccessBaseline :265` 之后）——棘轮是双向的，
  减少直连也要把基线改小，否则 `verifyConfigArchitecture` 失败。新写的 ViewModel 默认零 DAO。

---

## 10. 本轮的验证边界

**已静态核对**（逐文件读码，行号实测）：调用链与 `source` 语义、`ResolveEffectiveSpeechPlanUseCase`
的三个调用方、覆盖层优先级与两个"保留官方结果"的例外、`userLocked` 全仓零写入 true、
AI 冷却与首次失败提示的分工、`resolverVersion` 的六个组成部分与 `SCENE_PROMPT` 隔离、
两张角色表的列与唯一索引、id 分叉的两条真实路径、镜像的全部读写调用点、
同步逐字段 REPLACE 与保留字段清单、`traitsJson` 六个写入方（含 Cloud TTS 那个窄入口）、
池性别被丢弃、`voice_pool_members` 复合主键、绑定九类主体与 `locked/source/confidence` 写入方、
两个 Tab 的逐项行号与形态、两份推理强度存储与档位集合、两个预加载键的真实语义、
胶囊分配的完整写入路径、`deleteChapter` 的删除范围、场景拆分的四道门槛与失败不撤账、
分镜页与配音页的动作清单、草稿卡在配音页的呈现与疑似错绑定。

**未验证，需真机或补读**：

- N4 的代码路径已确定（四步无分支），但**影响面**要真机确认：给用户已手动改过主体的草稿卡选音色时，
  旧旁白绑定被覆盖后能否从界面察觉并恢复。
- 正文改完后回正文，胶囊与朗读各自多久读到新值（分配表侧的章节刷新是否覆盖播放队列）。
- 三档模式下 AI 分析成功率的真实分布——本轮只确认提示通路，没有证据说明 AI 本身常失败。
- D2 采纳后，旧章分析与新档案不一致时用户的实际听感是否可接受。
- S7 的"范围/续跑/重试"三块能否脱离其请求侧独立运行——盘点与搬迁已在切片C/D 落完（静态核对过调用面），
  但**切片D 之后的整条入口链未编译、未真机**：阅读页那颗 AI 按钮打开的是配乐弹层（不再有角色那两页）、
  「清除本章场景」真的只清配乐、弹层里新加的场景提示词卡片保存后下一次识别就用新文本、
  取消仍能续跑。这四条要真机各走一遍。
- 旧「AI 分配角色」留下的 `cast_assign` 预设行与 `book_cast_memory` 文本仍在库里（未删，见切片D）。
  真机上确认它们既不出现在 AI 提示词管理页，也不影响任何朗读结果，S9 才敢动手清。
- **S3/S4 未真机**：①给一条音色写标注后，同一池里新角色补到的声音是否真的跟着年龄段走；
  ②同书同池连续建两个角色是否拿到不同音色且**重跑还是这两个结果**（稳定序的全部意义）；
  ③池成员折叠报告是否把用户手工加过的那一条留下了（`foldDuplicateMembers` 只跑一次，
  靠 prefs 标记，跑第二遍必须无变化）。
- **S5① 未真机（本批风险最高的一条）**：①正文改一句归属 → **立刻开分镜结果页**（不用重新准备）看那一行
  是否已是那个角色——当场写入走的是同一座桥，对不上时应有一条"暂时对不上库里那份分段"的日志；
  ②再让本章重新准备一次（翻走再翻回来继续朗读，或分镜页点「重新分析」）→ 听书是否用那个角色的声音
  （**耳朵仍要等队列重建**，改完当场听不到不是 bug）；③重跑本章分析是否**没有**冲掉那几句
  （6 处跳过判断第一次有真写入方）；④取消分配后是否回落旁白、且没有留下"锁着但没人说"的死段；
  ⑤划分方式为「整段」时整段一起吃定稿是否可接受；⑥日志里 `定稿:`（当场那一次）与 `定稿回放:`
  （出计划兜底那一次）两行是否与操作对得上；⑦故意挑一章"没分析过"的直接分配，再让它出计划，
  验证兜底确实补得上（当场那条日志与补写那条日志不该同时出现同一句）。
- **S8 未真机**：分镜页显示的角色与实际发声是否同一个（这是本条的验收），以及没分配过的句子
  是否不再被抹成旁白。
- **S9 未真机**：①库里已有同名多池的书，进配音页一次之后是否只剩一行、AppLog 报告是否列出被丢的
  那个声音；②再进一次是否空转（无第二份报告）；③正文胶囊"创建"同名角色是否得到「已存在」提示而
  不是多出一个他；④旧备份恢复后是否已经是一个人行。
- **D9 未真机（本轮必验，三条）**：
  ①**改设置不重跑**——先用 AI 模式读透几章（分镜页有角色），再把划分方式换成别的档 / 换模型 /
  换归因口径，回到那一章继续听：**日志里不应出现这些章的新一轮 AI 请求**，分镜页那几章的角色不变，
  章节列表也不再有任何"按以前的设置分析"字样（徽标已删）；AI 日志的"缓存=" 那一栏应为 `true`。
  ②**旧粒度照念照改**——换完划分方式后在正文胶囊把某一句分给某人，**当场开分镜页**应看到那一个角色
  （桥按绝对坐标照搭）；这一章重新准备后发声也是那一个。
  ③**正文真换过要重跑**——对一章改替换规则（或开关「显示角色」）让它对不上，听这一章时应重跑一次
  分析；分镜页此时若跳过分配表覆盖，日志应有一条"分镜页第 N 章跳过分配表覆盖：库里那几段量不到
  现在这份正文"。
  ④顺带验 D9 的代价是否可接受：换粒度后旧章仍是旧切法（听感"设置改了没反应"），点「重新分析本章」
  之后才按新粒度切。

---

## 11. 你要干啥

1. **构建与真机验证 S1–S9 这一整批**（本仓库规定我不跑构建）：
   `.\gradlew.bat :app:compileAppDebugKotlin`、`.\gradlew.bat verifyConfigArchitecture`，
   真机重点验四条（按风险排序）：
   ①**S5① + S8 的验收**——正文胶囊把某一句分给某人后，**当场开分镜结果页**就该是那个人（定稿直接写进
   库里那份分段）；再让这一章重新准备一次（翻走再翻回来继续朗读，或在分镜页点「重新分析」），
   实际发声也是那个人；接着对这一章再点「重新分析」，那几句不被改回 AI 的判断；取消分配则回落旁白、不留死段。
   ②**S9 的验收**——库里已有同名多池的书，进「人物与角色配音」一次之后只剩一行，日志里有那份
   「配音角色同名折叠」报告（写明丢了哪个池/声音）；再进一次不应有第二份报告。
   ③**S4 + S3 的验收**——同书同池连建两个角色应拿到两个不同音色，且重跑还是这两个；
   给一条音色标上性别/年龄段后，新角色补到的声音跟着这一栏走。
   ④**S7 切片D 那条未验的**——阅读页那颗 AI 按钮（标签已变）打开的是配乐弹层：底部「场景提示词」
   展开能改能存，存完下一次「开始识别」发的就是新文本，头部垃圾桶只清本章配乐、不动正文里的角色胶囊。
2. 结果回我。编译或真机有问题就单独一笔修；本轮待提交的是「S3/S4/S5①/S8/S9 代码一笔 + 两份文档一笔」
   **+ D9（改设置不再作废已有分镜、徽标删除）代码一笔 + D9 文档一笔**，
   按你之前的口径推 `trae/agent-Xgf87B`（agent 分支不出包）。
3. 剩下的下一刀只有一个：**S9 的结构收尾**——`cast_characters` 退役、档案表追加
   `voiceEffect/sortOrder/bubbleRuleJson` 三列、两套主体 id 归一、`book_cast_memory` 改结构化导入。
   这四项要动迁移条目并搬约 20 个文件的读写，我会在动手前把迁移写法与回滚口径交给你核对。
   ~~D3~~ 已按你的判定移出计划，不再出现在任何一刀里。
