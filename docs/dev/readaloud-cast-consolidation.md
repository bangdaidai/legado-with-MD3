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
给人物改名、加别名、转正，不会引发整本书重新烧钱；朗读设置那一页里，声音 / AI / 切分 / 缓存各有各的
小标题，不再穿插。

**开工顺序**：先做"让现象看得见 + 名字改对 + 修那个换错人的 bug"（不碰任何数据，随时可退）→
再做"只有一位选音算法" → 再做"改一句就是改台词本" → **最后才动"两本名册并一本"**。

下面 §1 起是证据（文件与行号）、去留清单、决策表和分期表。批复状态（2026-10-20）：
D2、D8 按推荐批下并已落地，D3 你改判不做（"200 个够够的"），D1/D4–D7 沿用旧结论——**当前无待批复项**。

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
| L7 | 在正文改一句，分镜结果页同步跟着改 | **两边读写同一张表**，改哪边另一边立刻变 |
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
   → 不必合并成一个开关，**改名即可**：「按角色分声朗读」/「正文显示角色名」。
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
      ↑ 写入者：AI 分析链（三档模式） + 定稿回放（正文/分镜页改一句）
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
定稿回放：quoteOrdinal → 覆盖该开引号的 segment → characterId + userLocked=true
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
        :347 按角色分声朗读（总闸） · :359 正文显示角色名 · :239 分析模式 · :260 推理强度
        :367 顺带识别配乐场景 · 归因口径（新并入） · 顺带往后分析几章（新并入） · prompt 入口
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
| `chapter_speech_segments` | **保留为唯一说话人真相** | `userLocked` 列已在（`data/entities/ReadAloudSpeech.kt:126`），只缺写入方 |
| `chapter_role_assignments` | **改造为定稿层**：锚点仍 `(bookUrl,chapterIndex,quoteOrdinal)`，分析后回放到 segments，不再参与播出认领 | 唯一建行出口 `CastAssignmentStore.assign()`（`:337-371`） |
| `cast_characters` | **退役** → 并档。独有三列 `voiceEffect/sortOrder/bubbleRuleJson` 以**追加列**进档案表；`voiceId` 不搬（绑定才是权威，`CastSpeechOverlay.kt:156-158` 注释自认） | 唯一键粒度差异见 §2；行级折叠不改主键 → 非破坏性 |
| `CastProfileMirror` / `CastMemoryMirror` | **退役**（一张表没有可镜像的东西） | 双写背离是 bug 源 |
| `book_cast_memory` | **退役**：别名→`aliasesJson`、池→`voicePoolId`、关系→`book_character_relations` | 文本行是图谱的弱化复制 |
| `CastSubjectResolver` | 降级为兼容读；**500 上限先修**（N2） | `:41`、`:63` |
| `voice_pools/_members/_groups` | **保留 + 收成互斥分组**（一音色一组），补 `gender/ageBand` 编辑入口，停止丢弃池性别 | 现复合主键天然多对多（`VoicePoolEntities.kt:109-120`）；`VoicePoolScreen` 对这两字段零引用 |
| `read_aloud_voices` | **保留，不加列**；用户标签放新表 `voice_labels(voiceId PK, gender, ageBand, tagsJson, updatedAt)` | 同步逐字段 REPLACE 重建（§3.3） |
| `RegexCastRuleStore` / `RegexCastSplitter` | **保留**，优先级仍最高（关掉多角色也生效），UI 注明"正则命中会顶掉 AI 结论" | `CastSpeechOverlay.kt:59-61`、`:133` |
| `BgmSceneStore` + `AiSceneAssignUseCase` | **保留 + 改名** `BgmCue*`（与分镜场景解名） | 配乐场景与分镜场景零关系 |
| `AiCastAssignUseCase` 的 AI 调用 | **退役**；独有能力（称呼归并、池建议、续跑、范围）全部迁出后不再发请求 | 其输出是官方链的子集；定稿价值不依赖 AI |
| `AutoAssignVoiceBindingsUseCase` + `CastVoicePicker.ensureVoice` | **合一为一个 UseCase**，去 `random()` 改稳定序，**年龄进播放链** | 现两套算法见 §2 |
| `RefineSpeechWithAiUseCase` | **保留为唯一归因引擎**；prompt 取两套并集；缓存键移出角色 revision（D2） | `:150-176` |
| `IdentifyBookCharactersUseCase` + `ai_artifacts` | **保留独立**（产档案不产逐句归属，与归因不冲突） | 入口在知识页 `ui/book/knowledge/BookCharacterListViewModel.kt:251` |

---

## 7. 分期落地

顺序原则：**先让现象可见、再修错名与错绑定、然后做零权威变化的收敛、最后动数据权威。**
每步可独立回退；动权威的一步都排在"现象已能自证"之后。

| 步 | 内容 | 依赖 | 动权威 | 回退 | 可观察验证 |
|---|---|---|---|---|---|
| **S1 现象可见 + 一个行为级 bug**（7 项） | ①分镜页自建 `ALOUD_AI_FALLBACK` 订阅（现只有服务订，`BaseReadAloudService.kt:383-386`）；②冷却期命中给可区分提示（现静默，`RefineSpeechWithAiUseCase.kt:241`）；③场景拆分失败给提示（现 `catch(Throwable)→return plan` 不撤账不提示，`SpeechStoryboardViewModel.kt:546-548`）；④`ai_cast` 入口加 AI 模型门禁（现恒可用，`ReadAloudCastDelegate.kt:83`）；⑤每章标注"结论来自规则还是 AI、有没有被分配表覆盖"；⑥纠正 `CastSpeechOverlay.kt:76-79` 日志措辞（那条分支其实原样保留官方角色音）；⑦**修 N4**：草稿档案应走角色编辑器（按 `SUBJECT_CHARACTER` + 档案 id 绑），只有真旁白行才发 `SetNarratorVoice`——现两者共用面板、发同一 Intent（`BookVoiceCastingScreen.kt:609-618`），并配 AppLog 记下"这次写的是哪个主体"作回归观察点 | 无 | 否（纯新增 + 一处分支修正） | 直接 revert | 关掉播放再进分镜页，失败时看得见 toast；给临时角色选音色后旁白不变、那句角色音变 |
| **S2 旋钮合一** | ①推理强度只留 DataStore 那份（`AiCastPresetStore.kt:502-512` 退役，旧值作初值读出后删，**不静默丢**）；档位取全枚举 7 档（`domain/model/AiModels.kt:257-264`），AUTO=不发参数跟随服务商、OFF=显式不思考，差别写进小字；②「听书预加载数量」按真实语义改名（它是正文预下载），并给 `audioPreDownloadNum` 补一个入口（§3.5）；③两个开关改名：「按角色分声朗读」/「正文显示角色名」（§3.4）；④清第三个 Tab 死口径（§6.3） | 无 | 否（设置介质） | revert；旧键未删即无损 | 弹窗里不再有强度 chips |
| **S3 音色标签 + 池分组**（四层接通） | ①新建 `voice_labels` 表承载用户标注（**不能加列到 `read_aloud_voices`**），标签为空时读取侧回落 `traitsJson` 预填，所以是加法不是切换；名字启发降为预填提示；未标注显示灰字而非硬猜；②池 `gender/ageBand` 补编辑入口（`VoicePoolStore.createPool/updatePool:363-403` 签名要加参数）、`voiceClassificationMap` 的性别不再被丢弃（`PrepareChapterSpeechPlanUseCase.kt:125-126`）；③`voice_pool_members` 写入侧收成 1:1 + 存量折叠报告（**不加唯一索引**，见 D4）；④定死"成员标签优先于池分类"；⑤备份补 `voice_labels` 字段 | 无 | 是（新表） | `voice_labels` 空即等同现状 | 同一角色两次进配音页，音色不再变 |
| **S4 选音合一** | `CastVoicePicker.ensureVoice` 并入 `AutoAssignVoiceBindingsUseCase`，统一打分链，`random()`（`:31`）改稳定序，**年龄第一次真正进播放路径**；`syncBinding` 的 `source` 默认值按实际来源写（修 N3），界面据此显示"你选的 / 自动选音" | S3 | 是（选音结果可复现性） | revert 恢复旧补音 | 同书同池重跑给出同一个音色 |
| **S5 定稿写入打通**（2026-10-20 安全切片已落：非当前章套覆盖 + 锁定此段 + 转正入口 + 场景回写不再顶掉分析段；**只剩①定稿写入未落**，理由见下方"本轮已落"） | 正文胶囊分配（唯一出口 `assign():337-371`）在写分配表的同时，把结论写成 segments 的 `characterId + userLocked=true`；分镜页加"锁定/解锁此段"Intent（现无任何编辑/锁定动作，`SpeechStoryboardContract` 只有列表/重分析/试听/删除）；**非当前章也套覆盖**（`planFromSegments` 后补 `overlayOnSegments`，`ResolveEffectiveSpeechPlanUseCase.kt:132-144`），使 L7 完整；补「转正」UI 入口（§3.8）**——必须与 D2（角色库移出缓存键）同轮，否则第一次点转正就当场重烧全书（N1）**；并落 D2 的两条零 token 本地归并：转正＝纯 status 改写、新人入库后老章同名草稿卡按名字/别名重定向到正式档案（行级 `UPDATE characterId`） | 无阻塞 | 否（补写入方） | 无写入方时等同现状 | 正文改一句 → 翻到别章再翻回来，分镜页仍是那个角色；转正一张卡之后，已分析章不重烧 |
| **S6 界面归纳** | 按 §6.3 给现有 18 项插四个小标题，「常规」Tab 两块；读写语义一律不动 | 无 | 否 | 逐项挪回原位 | 同一列里不再 AI 项与缓存项穿插 |
| **S7 prompt 并集 + 逐句 AI 退役 + 执行归位** | ①两套判定规则并入 `ANALYZE_SPEECH` 契约（含归因口径两档单选、可选 `speakerPool` **只问未绑音色的新人**）；②`AiCastAssignUseCase` 请求侧删除，范围/续跑/重试归位到朗读侧档位「顺带往后分析几章」+ 结果页显式动作（补分析/重试失败章/重新分析），**设置块零运行按钮**；③场景拆分触发点从"进分镜页"上提到"该章分析收尾"，独立调用与 `SCENE_PROMPT` 不变（`RefineSpeechWithAiUseCase.kt:1152-1155` 的隔离保住）；④页面级记账 `sceneAttemptedChapters` 作废；⑤吸收清单逐项打勾，任何一项没落点就不许删调用 | S1/S5，且需先做调用面盘点 | 是（调用拓扑 + 执行入口） | 旧 usecase 与开关保留，按书灰度 | 一次朗读只看到一次归因调用日志 |
| **S8 覆盖层职责收缩** | 删"分配表认领 + 认领不到降级旁白"（`CastSpeechOverlay.kt:140-150`），只留正则、静音、段级特效 | S7 | 是（呈现语义） | revert 即恢复覆盖 | 分镜页显示的与耳朵里的是同一个角色 |
| **S9 身份合一** | 先修两处截断（N2）与搜索口径（N5）→ 档案表追加 `voiceEffect/sortOrder/bubbleRuleJson`（**追加到手写迁移 `migration_127_128`**，守卫式 `ADD COLUMN + defaultValue`，先例 `DatabaseMigrations.kt:119-127,153-158`）→ 同名多行折叠成一条档案（保留"有绑定/有音色"的那条，差异进报告）→ 三处 `characterId/subjectId` 重映射 → `cast_characters` 与两个镜像停写退役 → `book_cast_memory` 文本改结构化导入 profiles + relations | S3、S8 | 是（身份权威），但只追加列 + 行级改写，**不改主键** | 追加列不影响旧读路径；折叠器幂等；退役前留角色表只读 | 一个人只有一行、只有一个声音 |

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
  `audioPreDownloadNum` 补了滑块入口；两个开关改名为「按角色分声朗读」/「正文显示角色名」；
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
- **S5 的安全切片已落（2026-10-20），S5① 定稿写入未落**：
  - 已落：①**非当前章也套覆盖**——分镜页缓存路径改成"当前章与非当前章同一条路"，段落重建从
    `readerChapterInputWindow.current` 扩到上/当前/下一章三份（`chapterParagraphs`），并在套之前加
    **同源闸门**：`SpeechIdentity.chapterContentHash(段落)` 必须等于那份分析的 `contentHash`
    （`ChapterSpeechSegments` 新增该字段，只有当前设置那份才带基准，退回旧划分时为空即跳过）。
    闸门是必需的：`ReaderChapterSourceParser` 在「正文显示角色名」打开时往 `semanticContent` 注入
    `<<名字（池）>>` 标记，分析段的坐标就在带标记的空间里，标记一变整套坐标一起挪，
    不加判据套覆盖会把胶囊安到错的人身上。跳过时写 AppLog 说明原因。
    ②**分镜页「锁定/解锁这一段」**：`ToggleSegmentLock` Intent → `ChapterSpeechGateway.setSegmentUserLocked`
    → DAO 行级 `UPDATE userLocked`（不整份回写分段），行右侧角标按钮与试听钮同款尺寸，
    锁定态转主色；不重启朗读、不重算计划。这一列一写 true，现成的 6 处"跳过锁定段"立即生效。
    ③**场景拆分回写不再拿碎片顶掉分析段**：`withScenes` 过去把 `plan.map(SpeechPlanItem::segment)`
    整份 `replaceSegments`，而 plan 里可能是覆盖切过的碎片（正文/起止点被改过），先删后插等于
    用碎片换掉库里那条分析段；改成 `getSegments(analysisId)` 取回库自己的行、只搬场景两列再回写。
    ④**「转正」UI 入口**：配音页草稿角色行原来右侧什么都不给（`trailingContent = null`），
    现在挂进本页已有的「更多」菜单语法（`CastDraftRowActions`：转正 / 编辑），
    `BookVoiceCastingIntent.PromoteCharacter` 在 ViewModel 早已实现、全仓无调用点的状态结束。
  - 未落并改口径的两条，记录核实结果：
    - **S5①「正文胶囊 → segments 定稿写入」不做位置匹配**。分配表按 `(bookUrl, chapterIndex, quoteOrdinal)`
      锚定，segments 按 `start/end` 锚定，两者之间没有可依赖的桥；把 quoteOrdinal 换算成段内位置需要
      重建带标记正文，而同源闸门告诉我们标记空间在设置一变后就不可靠。要落这条得先给分配表补一列
      segment 外键（写入侧一起改），属数据权威变化，排在 S3/S9 那类迁移轮里。
    - **D2 的归并②（老章同名草稿卡重定向到正式档案）不需要写。**`book_character_profiles` 有
      `(bookUrl, name)` 唯一索引，同名草稿与正式档案无法共存；转正走的又是同 id 的 status 改写，
      老章分段引用的 `characterId` 本来就指向那张卡。真正要盯的是别处按名字 `REPLACE` 入库导致 id 漂移，
      那属于 S9 的身份合一，本轮不写投机 `UPDATE`。

要点：

- **S5 必须早于 S8**。定稿保护是硬前置——先收覆盖层再补定稿，会真冲掉用户手工劳动。
  现成的 6 处"跳过锁定段"判断（`RefineSpeechWithAiUseCase.kt:272,297,460,494`、
  `ResolveLocalSpeakersUseCase.kt:80`、`help/readaloud/resolve/LocalCharacterSpeakerResolver.kt:153`）
  在 `userLocked` 有人写 true 之后立刻生效，**"重跑不冲掉定稿"是现成逻辑，缺的只是写入方**。
- S1–S4、S6 无阻塞，可立刻做，且都不动数据权威。
- **S7 会让全书分析缓存失效**（prompt 变 → `promptHash` 变 → 重新分析）。一次性成本必须提前告知。
  若同时采纳 D2（角色 revision 移出缓存键），则此后"改角色"不再引发全书重烧——这条收益比 S7 的
  一次性代价更值，建议两件事同一轮告知用户。
- 每步只留一条可观察验证，见表格最后一列。

---

## 8. 决策点（请按编号批复，未批复的按"推荐"执行）

**贯穿原则（你定的）：两方对同一功能各有设置或各有规则，一律融合成同一处的可选项，不做删除式取舍。**

**D1 说话人真相 = segments，分配表降为定稿层。**〔已定，沿用〕
L7 的完整达成方式就是 S5；不再让覆盖层拥有"谁说话"的语义（S8）。

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

**D6 同名多池折叠：一人一条记录、一个声音。**〔已定，沿用〕
**内容代价（已接受）**：「李星菲（女少女）」与「李星菲（女中年）」并成一条，其中一个声音被丢弃；
想换年龄音只能改那一条的池。折叠器必须①按"有绑定/有音色"选保留行②把被丢的池/音色/变声/气泡写进
迁移报告③幂等（`backfillAll`/`ensure` 挂在进配音页路径上，重复执行必须无变化）。

**D7 「AI 分配角色」弹窗退役，能力全部吸收。**〔已定，沿用〕
强度进设置块（唯一一份）、范围变朗读侧档位「顺带往后分析几章」、prompt 跳管理页、书级记忆改读档案表、
配乐场景原样独立保留。**弹窗退役的验收标准 = §7 S7 第⑤条的吸收清单逐项打勾。**

**D8 朗读设置的宿主形态统一。**〔已批复（按推荐：本轮只做 S6 归纳，形态统一留到浮层迁移那轮）；
S6 本身本轮未执行〕
现状双宿主（Nav3 整页 + `AppModalBottomSheet`，见 §3.6）。归纳（S6）不依赖形态统一，
且共用同一个 `ReadAloudConfigContent`，改一处两入口同时生效；贸然统一会牵动
`docs/dev/readaloud-player-morph.md` 那条线，属另一件事。

---

## 9. 风险与迁移边界

- **缓存失效**：S7（prompt 并集 → `promptHash` 变）引发全书重分析；若采纳 D2 则此后"改角色"不再引发。
  两者都在交付说明里明写成本，不接受"先松基线再迁移"式的静默失效。
- **追加迁移的写法**：本 fork 最新本地扩展在 `migration_127_128`（`DatabaseMigrations.kt:36` 起），
  同一条里既有 `CREATE TABLE IF NOT EXISTS regex_cast_groups`（`:39`）也有守卫式 `ALTER`
  （`voice_pools.gender/ageBand :119-127`、`book_character_profiles.voicePoolId :153-158`）。
  S3 的 `voice_labels` 与 S9 的三列**追加到这条**，并必须沿用守卫式写法，否则重复执行失败。
  **不要因为"版本号不变 Room 就不重跑迁移"而拒绝追加**——线上库靠 `autoMigrations` 链升上来，
  真机都完整跑过手写迁移，追加 `ALTER` 对新旧设备都生效。
- **不要补加"看起来漏了"的列**：`underlineRoundCap`/`underlineFeather`/`characterRole`/`manualNineSlice`
  这类由上游 `AutoMigration` 补齐的列，重复 `ADD COLUMN` 会让整个迁移失败、把设备卡在旧版本。
  补列前去 `app/schemas/` 对应 JSON 核对——但 JSON 可能落后于 `@Entity`，判断"某列在用户库里是否存在"
  要同时看手写 `ALTER` 与 `autoMigrations` 覆盖区间。
- **行级操作 ≠ 结构变更**：S9 的折叠与重映射是 `UPDATE/DELETE`，不改主键、不改列类型，
  所以覆盖安装可升，不需要卸载重装。改主键/改列类型/加唯一索引才需要重装或等上游提版本。
- **冲突处理要显式**：S5 期间同一章可能分配表与 segments 都有结论，**冲突以哪边为准必须在代码里
  写死并记日志**，不能静默。S9 的文本记忆导入必须按名字去重并在报告里列冲突，同名两行不能静默并成一行。
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
- 三档模式下 AI 分析成功率的真实分布——本轮只确认提示通路，没有证据说明 AI 本身常失败。- D2 采纳后，旧章分析与新档案不一致时用户的实际听感是否可接受。
- S7 的"范围/续跑/重试"三块能否脱离其请求侧独立运行——属职责搬迁而非重写，但**S7 前必须做一次
  调用面盘点**（`AiCastAssignUseCase` 的公开面 + `AiCastPresetStore` 的调用方清单）。

---

## 11. 你要干啥

1. **构建与真机验证 S1/S2/S6 + D2/N6/N4 + S5 安全切片这一批**（本仓库规定我不跑构建）：
   `.\gradlew.bat :app:compileAppDebugKotlin`、`.\gradlew.bat verifyConfigArchitecture`，
   真机重点验三条：①给临时角色选音色，旁白不变；②正文胶囊改一句归属后，去分镜页点这一章——
   那一句显示的音色/角色就是改后的（不再要"必须正在读这章"）；③草稿角色行右侧菜单点「转正」，
   这一行从临时说话人变成正式角色卡。锁定按钮验"锁定后重新分析这一句不被改"。
2. 结果回我，我按"修复一笔 / 提示与日志一笔"拆开提交。
3. 剩下的下一刀：S5① 定稿写入（要先给分配表补 segment 外键，属权威变化，我会把迁移条目和
   回滚口径一并交给你核对）或 S3（音色标签 + 池分组，同样要新建表）。
   ~~D3~~ 已按你的判定移出计划，不再出现在任何一刀里。
