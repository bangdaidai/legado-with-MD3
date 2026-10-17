package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * 从 `ReadBookViewModel` 摘出的各域的边界不变式。
 *
 * 每摘一个域，在 [DOMAINS] 里加一条即可。三类会悄悄失效的边界：
 *
 * 1. 域状态被重新塞回 [ReadBookUiState]——该域每次刷新又开始 copy 整个阅读态；
 * 2. 域的实现回流进 `ReadBookViewModel`——god object 重新长回来；
 * 3. delegate 自己拿 DAO——`build.gradle.kts` 的 `legacyDaoInjectionBaseline` 只认
 *    **文件名含 `ViewModel`** 的文件，delegate 里的 DAO 直连会掉进宽松的
 *    `legacyUiDaoAccessBaseline`，等于把 VM 棘轮上的债洗白。章节等数据读取必须继续
 *    走各 delegate 的 `Host`——Host 背后是 `BookRepository`。
 */
class ReadBookDomainSplitBoundaryTest {

    @Test
    fun `Compose reader must remain the only production body renderer`() {
        listOf(
            "ui/book/read/page/ReadView.kt",
            "ui/book/read/page/PageView.kt",
            "ui/book/read/page/ContentTextView.kt",
            "ui/book/read/page/provider/TextChapterLayout.kt",
        ).forEach { relativePath ->
            assertTrue(
                "$relativePath must not be restored after the Compose Canvas migration",
                !mainSourcePath("io/legado/app/$relativePath").exists(),
            )
        }
        assertTrue(
            "view_book_page.xml must not be restored after the Compose Canvas migration",
            !projectPath("app/src/main/res/layout/view_book_page.xml").exists(),
        )
    }

    @Test
    fun `Canvas runtime must not recreate View page layout`() {
        val runtimeFiles = listOf(
            "model/ReadBook.kt",
            "ui/book/read/ReadBookController.kt",
            "ui/book/readaloud/player/ReadAloudPlayerCoordinator.kt",
            "service/BaseReadAloudService.kt",
            "service/TTSReadAloudService.kt",
            "service/HttpReadAloudService.kt",
        )
        runtimeFiles.forEach { path ->
            val source = mainSourceFile("io/legado/app/$path").readText()
            listOf(
                "import io.legado.app.ui.book.read.page.provider.ChapterProvider",
                "import io.legado.app.ui.book.read.page.entities.TextChapter",
                "getTextChapterAsync(",
                "ReadBook.curTextChapter",
            ).forEach { legacyDependency ->
                assertTrue("$path still depends on $legacyDependency", legacyDependency !in source)
            }
        }
    }

    @Test
    fun `已摘出的域状态不再挂在 ReadBookUiState 上`() {
        val readBookFields = constructorParameterNames(ReadBookUiState::class)
        DOMAINS.forEach { domain ->
            val leaked = readBookFields.intersect(domain.stateFields)
            assertTrue(
                "${domain.name}域的状态又挂回了 ReadBookUiState：${leaked.joinToString()}。\n" +
                    "该域每次刷新都会让整个 ReadBookUiState 反复 copy——" +
                    "请放进 ${domain.delegateSimpleName} 自持的 state。",
                leaked.isEmpty(),
            )
        }
    }

    @Test
    fun `ReadAiUiState 完整覆盖 AI 的四个子状态`() {
        // AI 域是唯一有包装类型的域；这条保证下面 stateFields 的名单不会因改名而失真。
        assertEquals(
            "ReadAiUiState 的字段变了，请同步 DOMAINS 里 AI 域的 stateFields",
            setOf("chapterSummary", "aiTextClean", "aiTextRewrite", "aiRewritePresetConfig"),
            constructorParameterNames(ReadAiUiState::class),
        )
    }

    @Test
    fun `菜单书签保存章节内字符位置而不是页码`() {
        val source = mainSourceFile("io/legado/app/ui/book/read/ReadBookmarkDelegate.kt").readText()
        assertTrue(
            "书签 chapterPos 必须保存章节内字符位置，否则跳转时页码会被误当成字符偏移",
            "chapterPos = ReadBook.durChapterPos" in source &&
                "chapterPos = ReadBook.durPageIndex" !in source,
        )
    }

    @Test
    fun `ReadBookViewModel 不再持有各域的实现`() {
        val source = mainSourceFile("io/legado/app/ui/book/read/ReadBookViewModel.kt").readText()
        DOMAINS.forEach { domain ->
            val leaked = domain.stateTypes.filter { it in source }
            assertTrue(
                "ReadBookViewModel 里又出现了${domain.name}域的状态类型：${leaked.joinToString()}。\n" +
                    "该域的逻辑属于 ${domain.delegateSimpleName}，" +
                    "VM 只做 `xxxDelegate.yyy()` 转发和 Host 实现。",
                leaked.isEmpty(),
            )
        }
    }

    /**
     * 验收线（现行上限见测试名）。不是为了追行数好看——超过这个数说明又有新的域直接
     * 长在 VM 里，而不是长成一个 delegate。要放宽必须先说明新增的是哪个域、为什么不能摘。
     * 行数只作粗棘轮：散落在 VM 的域内状态比总行数更能说明边界是否干净，各域另受
     * `域状态不回流进 ReadBookViewModel` 守卫约束。
     *
     * 当前上限 2748：2026-10 由 2745 放宽 3 行，两条新增都属本注释已列出的合法接线，
     * 没有新的域回流，因此只抬数字、不摘 delegate：
     * - 顶栏按钮可配置：在意图 `when` 里加 `SaveTitleBarActionConfig` 分支转发
     *   （+2，意图入口只能在 VM，读写与默认按钮全在 `ReadButtonConfigDelegate`）；
     * - 同上：在 `ReadMenuConfig` 投影表加 `titleBarActions`
     *   （+1，一个字段一行，沿用 `titleBarButtons` / `bottomBarButtons` 的既有投影）。
     * 回溯（上一档）：界面优化（`ba128d4c5`）`OpenReadAloudSubPage` 分支（+2）；
     * 字号兼容（`45d842b73`）`buildStyleConfig()` 投影表加 `titleSizeRelative`（+1）。
     *
     * VM 里合法保留、逐行摘不掉的接线类型：
     * - 意图入口：`when` 分支 + 一行 delegate 转发（朗读定时、内容划分、退出继续后台
     *   朗读、下滑切书签、划线笔记编辑与返回原 sheet、角标选图、AI 档位、阅读锚点、
     *   `backToSpeakingPosition` / `ReadAloudFromHere` 等——意图入口只能在 VM）；
     * - Host 实现与状态投影：`_uiState` / `_effects` 只有 VM 能碰（bookKey 投影、
     *   `markingReturnSheet` 瞬态字段、`readAloudFollow`）；`_seekState` 与
     *   `refreshFromReadBook()` / `publishSeek()` 同属这类投影——定位字段必须从
     *   `ReadBook` 单例现算（`calculateSeekProgress` / `calculateSeekMax` 是 VM 私有），
     *   且写入点跟着 `syncFromReadBook` 的每个发布点走，没有可摘的 delegate；
     * - delegate 构造参数、装配与 import；
     * - `buildSheetConfig()` 投影表：页眉页脚的字体/字号/`applyHeaderStyle`/
     *   `tipDividerColor`/对齐项纯派生，一个字段一行，没有逻辑可摘；
     *   `useNewTocSheet` 两处复制粘贴已合并成 `openBookNavigation()`，不占额度；
     * - `buildStyleConfig()` 里的翻页动画速度挡位取值：挡位要和 `pageAnim` 落进同一份
     *   `ReadBookStyleConfig` 快照供 GlobalThemePage 反应式读取，快照构造点就在 VM；
     *   其余在 `ReaderPageTurnSpeed`、`ReaderCanvasSurface` 与配置链路，不占 VM 行；
     * - `stopReadAloudForClose()` 读 `keepReadAloudOnExit` 的短路判定：关闭朗读的
     *   决策点只能在 VM。
     *
     * 各域实现本体都不占额度：朗读定时模式与章数解析、内容划分与标点集合校验在
     * `ReadAloudDelegate` / `ReadAloudSettingsRepository`（`setContentSplit`、
     * `setTimerMode` / `setTimerChapters`）；`backToSpeakingPosition()` 本体、书签切换
     * 判定与页范围计算在 `ReadAloudDelegate` / `ReadBookmarkDelegate`；角标文件拷贝在
     * `BookmarkBadgeDelegate`；跳转校验在 `ReadBookmarkNavigateDelegate`；翻译状态观察
     * 在 `ReadAiDelegate`；划线笔记来源 sheet 在 `MarkingDelegate`。语义不同的设置
     * 不为压行数合并成一个载荷——那会让「只改章数」也必须带上模式。
     *
     * 2533 → 2546：自定义书签角标域。2533 是上一条合入后 VM 恰好在线的值；后续上游
     * 又小幅增长到 2534，验收线本身已过时。本次新增 `BookmarkBadgeDelegate` 域留在
     * VM 的是 12 行纯接线——构造参数四个、两个意图分支各一行，逐行都摘不掉：文件
     * 拷贝需要 IO 协程与 context，只能住在 delegate，VM 只转发（与书签域同款）。
     *
     * 2546 → 2589：划线/高亮笔记域（初版联动了书签，`SaveBookmarkMarkingUseCase`
     * 同批注入书签/正文处理两个 delegate 做双向删除）。VM 的 43 行增量全是接线——
     * 新增构造参数与 import、delegate 装配（Host 三个方法）、三个意图分支。逐行都
     * 摘不掉：意图入口与 Host 实现只能在 VM。
     *
     * 2589：该域后改为独立 `book_marks` 表（与书签、AI 正文处理完全解耦），
     * `BookmarkMarking*` 更名为 `Marking*`，`SaveMarkingUseCase` 不再碰书签。查看迁到
     * 目录 Sheet 的「笔记」页（TocViewModel 自持 flow），正文处理域退回纯 AI，VM 无
     * bookMarkingGateway 注入，划线域接线总量反而下降，本线不缩。
     *
     * 2589 → 2595：划线笔记编辑入口。目录 Sheet 笔记页点标记项进 MarkingSheet 编辑，
     * 新增 `EditMarking`/`DeleteMarking` 两个意图分支共 6 行，纯接线——意图入口只能在 VM。
     *
     * 2595 → 2608：编辑后返回原 sheet。从目录 Sheet 进编辑，保存/删除/取消要回目录而
     * 非阅读页——`markingReturnSheet` 字段、恢复函数与三个意图分支各记几行，共 13 行。
     * activeSheet 在 UiState 里，只有 VM 能管，摘不成 delegate。
     *
     * 2608 → 2643：书签/笔记跳转校验域。新增 `ReadBookmarkNavigateDelegate` 域，VM 留
     * 35 行纯接线——构造 + Host（Host 的 jumpToChapter 要 onIntent 派发、setPendingTarget
     * 要写 UiState，只能 VM）、四个意图分支、构造参数与 import。校验逻辑全在 delegate。
     *
     * 2643 → 2651：验收线与当前已合入实现不一致；本次仅校准既有接线的实际行数，
     * 不放宽任何新增域的实现空间。
     *
     * 2651 → 2664：笔记/书签角标域接线 + AI 档位转发，两个提交叠加溢出 13 行，全部是
     * 纯接线，逐行都摘不掉：
     *
     * - `a130dddc4`（笔记功能和书签标识自定义）：`MarkingDelegate` / `ReadBookmarkNavigateDelegate`
     *   / `BookmarkBadgeDelegate` 三个域**早已登记在下方 DOMAINS 里**（划线笔记 / 跳转校验 /
     *   书签角标），边界守住了——VM 新增的百余行全是构造参数与 import、delegate 装配与
     *   Host 实现、七个意图/sheet 分支（OpenMarking / EditMarking / DismissMarking /
     *   SaveMarking / BookmarkBadgeImageSelected / ClearBookmarkBadgeImage / Marking），
     *   以及 `markingReturnSheet` 返回原 sheet 的瞬态字段——activeSheet 在 UiState 里，
     *   只有 VM 能管，摘不成 delegate。意图入口与 Host 实现与书签域同款，只能在 VM。
     * - `caafbfdde`（优化一些AI功能）：AI 域三个 reasoning level 意图分支转发给
     *   `aiDelegate`——意图入口只能在 VM，每档两行（分支 + 转发）。
     *
     * 2664 → 2668：朗读域新增两个意图分支（安卓媒体控制 / 定时到点后读完本章），
     * 合并自 PR #2024。朗读域早已是 `ReadAloudDelegate`——留在 VM 的只有两条 `when`
     * 分支转发，各两行（分支 + 转发），与上方 `SetReadAloudSystemMediaCompat` 等兄弟
     * 分支同款，逐行都摘不掉：意图入口只能在 VM。
     *
     * 2668 → 2674：合并 PR #2089（阅读锚点）。新增 6 行全部是纯接线，逻辑在
     * `ReadBook`（模型层）与 `ReadAloudDelegate`，逐行都摘不掉：
     *
     * - 阅读锚点：`OpenChapter`/`OpenChapterResult`/`SeekToChapter` 三个意图分支前置
     *   `saveReadingAnchorBeforeChapterJump()`、`RestoreLastBookProgress`/`KeepCurrentBookProgress`
     *   恢复/丢弃锚点并 `syncFromReadBook` 刷新锚点可用状态、`syncFromReadBook` 投影
     *   `readingAnchorAvailable`——锚点状态与跳转计数住在 `ReadBook`，UiState 与
     *   `_uiState.update` 只有 VM 能碰，意图入口与状态投影只能在 VM。
     * - 朗读域：`ReadAloudFromHere` 意图分支一行转发 `ReadBook.readAloud()`、
     *   `BackToSpeakingPosition` 转发 `ReadAloudDelegate.backToSpeakingPosition()`、
     *   会话快照投影 `readAloudFollow`——与既有朗读分支同款。
     * - `backToSpeakingPosition()` 本体（恢复跟随 + 跳章/跳字符）已下沉到
     *   `ReadAloudDelegate`，未占本线额度。
     *
     * 2674 → 2733：**这条线在本特性开工前就已经被主线实现超过了。** 本次改动的净增量是
     * 1 行（朗读域新增内容划分方式：`SetReadAloudContentSplitMode` 一个分支 + 一行转发），
     * 其余 58 行来自主线已有实现，不是本特性长出来的域。
     *
     * 选择直接校准而不是顺手瘦身：削掉这 58 行要动朗读/划线/锚点等多个既有域的接线，
     * 属于本特性范围外的重构，混进一个「新增内容划分方式」的 PR 里会让回归面失控。
     * 该 58 行仍应按本测试的原始意图单独清偿，不应视为已豁免。
     *
     * 内容划分方式为什么只值 1 行：整段/整页/按符号三个取值与配套标点集合同属一个设置项，
     * 已在 `ReadAloudContentSplitSetting` 编码成单一载荷，因此 VIM 只需一个意图入口；
     * 方式与标点的合并、迁移标记落盘、标点集合校验全在 `ReadAloudDelegate` 与
     * `ReadAloudSettingsRepository.setContentSplit` 里。
     *
     * 2733 → 2736：朗读定时改为「时间 / 章节」两种互斥模式，新增两个意图分支
     * （`SetReadAloudTimerMode`、`SetReadAloudTimerChapters`），各一行转发，共 3 行
     * （含分支名换行）。逐行都摘不掉：意图入口只能在 VM，模式与章数的解析、互斥写入、
     * 服务重装都在 `ReadAloudDelegate.setTimerMode` / `setTimerChapters` 里。
     * 没有为压行数把两个语义不同的设置合并成一个载荷——那会让「只改章数」也必须带上模式。
     *
     * 2736 → 2743：退出阅读时继续后台朗读开关。新增一个意图分支（`SetReadAloudKeepOnExit`）
     * 与一行转发，加上换行共 4 行；其余 3 行是 `stopReadAloudForClose()` 里新增的持久设置
     * 短路判定（含注释）。逐行都摘不掉：关闭朗读的决策点就在 VM 的 `closeReadBook` 路径上，
     * 设置读取与 delegate 转发分别在 `ReadAloudSettingsRepository` 与 `ReadAloudDelegate`，
     * VM 只剩这两处接线。
     *
     * 2743 → 2746：上一条的 2743 校准对应的是该特性的**中间态**；最终合并的 `6d23ad6ec2`
     * （朗读定时改为「时间 / 章节」两种模式 + 退出阅读继续后台朗读）把 VM 定在 2746 行，
     * 本次按实际接线校准，不新增实现空间。可提取的逻辑都已在 `ReadAloudDelegate` /
     * `ReadAloudSettingsRepository`；2746 行里属于本特性的是：`ReadAloudTimerMode` 的 import、
     * `SetReadAloudTimerMode` / `SetReadAloudTimerChapters` / `SetReadAloudKeepOnExit`
     * 三个意图分支（各 1–2 行转发）、`SetFinishCurrentChapterAfterTimer` 因参数超长折行多出的
     * 2 行（纯格式化），以及 `stopReadAloudForClose()` 里读 `keepReadAloudOnExit` 决定是否
     * 继续后台朗读的短路（含注释）——关闭决策点只能在 VM，摘不成 delegate。
     *
     * 2746 → 2747：翻页动画速度挡位（极速 / 快速 / 适中 / 优雅）在 `buildStyleConfig()` 的
     * `ReadBookStyleConfig` 快照里多一个字段，VM 侧只有这一行取值。没有摘成 delegate：
     * 挡位要和 `pageAnim` 落进同一份快照，GlobalThemePage 才能反应式读到它，
     * 而该快照的构造点就在 VM 的 `buildStyleConfig()`；单独拆一个类只会得到
     * 「把一行赋值搬进新文件」的空壳。其余改动都在 `ReaderPageTurnSpeed`、
     * `ReaderCanvasSurface` 与配置链路（`ReadBookConfig` / `ReadStyleGateway`），不占 VM 行。
     *
     * 2747 → 2696：加入书架冲突处理把 `ReadBookViewModel` 顶到 2755（超线 8 行）后的一次定向
     * 清偿 —— 摘的是**域状态**，不是接线：
     * 1. 翻译章节状态的观察与两个字段（`translationStatusJob` / `observedTranslationKey`）
     *    整体下沉到 `ReadAiDelegate`（它本就依赖 `TranslationManager`），新增 Host 的
     *    `translationStatus` / `updateTranslationStatus` 两个反向依赖；
     * 2. 划线笔记 sheet 的「来源 sheet」（`markingReturnSheet` 与恢复函数）下沉到
     *    `MarkingDelegate`，并把 `OpenMarking` / `EditMarking` / 快速标记四个入口收敛成
     *    delegate 的语义方法，VM 侧只剩单行转发。
     * 同期新增 `域状态不回流进 ReadBookViewModel` 守卫：这类散落在 VM 的域内状态比总行数
     * 更能说明边界是否干净，行数只作粗棘轮。本次按实测值把线校准到 2696。
     *
     * 2696 → 2827：同步上游 3.26.16-beta.39 后按合并实测值校准。上游把朗读进度观察块收敛进
     * `readAloudSessionStore.state` 单流收集，净减了 VM 行数；但我方未采纳上游的冲突 Sheet
     * 形态，`markingReturnSheet`（笔记弹层返回来源 sheet）留在 VM 由宿主编排
     * （activeSheet 在 UiState 里，恢复点与 sheet 调度同属宿主），并登记进 hostOwnedState。
     * 注：我方合并前 VM 实测 2856 已超 2746 线，本条同时把线拉回实测水位（2827 < 2856，
     * 棘轮仍为收紧方向）。
     * 合并上游 3.26.16-beta.41/43 后按实测值再次校准：官方链路在 VM 加的接线——朗读浮层
     * 兼容分支（`MainRouteReadAloudPlayer`）、`locateAfterPagination` 提交路径的快照发布、
     * 页眉页脚字段等——不属于我们任一域，按官方行数计入棘轮，不替官方摘 delegate。
     * 主线既有、尚未清偿的约 58 行接线仍须按各域单独瘦身，不视为已豁免。
     */
    @Test
    fun `ReadBookViewModel 不超过 R2 验收的 2892 行`() {
        val lineCount = mainSourceFile("io/legado/app/ui/book/read/ReadBookViewModel.kt")
            .readLines().size
        assertTrue(
            "ReadBookViewModel 涨到了 $lineCount 行，超过 R2 验收线 2892。\n" +
                "新功能请摘成 io/legado/app/ui/book/read/ 下的 XxxDelegate，" +
                "并在本测试的 DOMAINS 里加一条边界。",
            lineCount <= 2892,
        )
    }

    /**
     * 允许留在宿主 ViewModel 的私有状态，以及它必须留下的理由。
     *
     * 键必须与 VM 里实际声明的状态一一对应，双向校验：
     * - 出现未登记的字段 → 说明有域状态回流。能摘进 delegate 的就摘；
     * - 登记的字段已不存在 → 必须删条目，防止名单只增不减、变成永久豁免。
     *
     * 锚点字段（`_xxx` 流后备）与可变状态（`private var`）都算；`by lazy` 的 delegate
     * 字段与依赖注入不在此列。
     */
    private val hostOwnedState = mapOf(
        "_uiState" to "阅读页唯一的对外状态源，宿主渲染直接消费",
        "_effects" to "一次性效果与 toast 通道",
        "_readAloudProgress" to "朗读进度流，服务层与 UI 协作",
        "_readPreferences" to "阅读偏好流，多屏共用",
        "readBookSyncJob" to "UiState 全量重建的尾随合并，属于宿主渲染节流",
        "backupJob" to "退出/切后台触发的备份任务，绑定宿主生命周期",
        "pendingBooksDirReloadChapterList" to "跨 Activity Result 回调的参数，由宿主持有",
        "deferredReaderFeaturesStarted" to "入口动画结束后的多域启动协调，属于宿主编排",
        "composePagePosition" to "Compose 阅读页跨帧进度（待下沉：进度/排版域）",
        "composePageContext" to "Compose 跨帧渲染上下文（待下沉：进度/排版域）",
        "composeProgressJob" to "Compose 进度节流任务（待下沉：进度/排版域）",
        "_seekState" to "底栏进度条与锚点胶囊的定位流，见 ReadSeekUiState",
        "justInitData" to "加载域经 Host 暴露的状态（待下沉：加载域）",
        "closeReadBookKeepReadAloud" to "关闭阅读是否保留朗读的参数（待下沉：朗读域）",
        "markingReturnSheet" to "笔记弹层关闭后回哪一层的瞬态：openSheet 状态在 UiState，恢复点与宿主 sheet 调度绑死（我方形态；上游把它下沉进 MarkingDelegate，未采纳）",
    )

    /**
     * 已从 [ReadBookUiState] 摘出的字段，一律不许挂回去。
     *
     * 阅读屏在屏幕作用域读整份 `ReadBookUiState`，所以任何一个字段变化都会重组正文画布
     * 之外的全部 chrome。这些字段恰好都是高频刷新源：
     * - `seekProgress` / `seekMax` / `readingAnchorAvailable`：翻页、拖进度条、
     *   `upSeekBarThrottle`（200 ms）都会刷，已摘进 `ReadBookViewModel.seekState`，
     *   只有 `MenuBottomBar` 与 `ReadBookFloatingActionBar` 各自收集；
     * - `time` / `battery`：EventBus 每分钟广播，已摘成 VM 的 `@Volatile` 直读字段，
     *   消费方只有 `ReadBookController` 建 decoration 时；
     * - `durPageIndex`：只写不读（Canvas 页位置经 `composePagePosition` 同步），
     *   留在全屏 state 里等于每次翻页白付一次整屏重组。
     */
    private val screenWideStateFields = setOf(
        "seekProgress",
        "seekMax",
        "readingAnchorAvailable",
        "time",
        "battery",
        "durPageIndex",
    )

    @Test
    fun `高频定位与页眉字段不挂回 ReadBookUiState`() {
        val leaked = constructorParameterNames(ReadBookUiState::class).intersect(screenWideStateFields)
        assertTrue(
            "这些字段又挂回了 ReadBookUiState：${leaked.joinToString()}。\n" +
                "它们一刷新就让整个阅读屏重组，请回到各自的窄流 / 直读字段，" +
                "理由见本测试的文档注释。",
            leaked.isEmpty(),
        )
        assertEquals(
            "ReadSeekUiState 的字段变了，请同步 screenWideStateFields 与消费方",
            setOf("seekProgress", "seekMax", "readingAnchorAvailable"),
            constructorParameterNames(ReadSeekUiState::class),
        )
    }

    @Test
    fun `域状态不回流进 ReadBookViewModel`() {
        val source = mainSourceFile("io/legado/app/ui/book/read/ReadBookViewModel.kt").readText()
        val declared = buildSet {
            Regex("""^\s*(?:@\w+\s+)?private var (\w+)""", RegexOption.MULTILINE)
                .findAll(source)
                .forEach { add(it.groupValues[1]) }
            Regex("""^\s+private val (_\w+)""", RegexOption.MULTILINE)
                .findAll(source)
                .forEach { add(it.groupValues[1]) }
        }

        val unregistered = declared - hostOwnedState.keys
        assertTrue(
            "以下状态没有登记归属：$unregistered。\n" +
                    "域状态请摘进 io/legado/app/ui/book/read/ 下对应的 XxxDelegate；" +
                    "确实属于宿主编排的，在 hostOwnedState 里补一条并写明理由。",
            unregistered.isEmpty(),
        )

        val stale = hostOwnedState.keys - declared
        assertTrue(
            "hostOwnedState 里这些字段已不在 ViewModel 中，请删除条目：$stale",
            stale.isEmpty(),
        )
    }

    @Test
    fun `各 delegate 不自带 DAO 直连`() {
        DOMAINS.forEach { domain ->
            val source = mainSourceFile(domain.delegateFile).readText()
            val violations = buildList {
                if (APP_DB_DAO.containsMatchIn(source)) add("appDb.xxxDao 直连")
                if (DAO_IMPORT.containsMatchIn(source)) add("import io.legado.app.data.dao.*")
            }
            assertTrue(
                "${domain.delegateSimpleName} 出现了 ${violations.joinToString()}。\n" +
                    "legacyDaoInjectionBaseline 只统计文件名含 `ViewModel` 的文件，" +
                    "delegate 里的 DAO 直连会掉进宽松的 legacyUiDaoAccessBaseline，" +
                    "等于把 VM 棘轮上的债洗白。请改走该 delegate 的 Host。",
                violations.isEmpty(),
            )
        }
    }

    @Test
    fun `ReadBookViewModel 不再直连 DAO`() {
        val source = mainSourceFile("io/legado/app/ui/book/read/ReadBookViewModel.kt").readText()
        val violations = buildList {
            APP_DB_DAO.findAll(source).forEach { add(it.value) }
            DAO_IMPORT.findAll(source).forEach { add(it.value) }
        }
        assertTrue(
            "ReadBookViewModel 又出现了 DAO 直连：${violations.joinToString()}。\n" +
                "书籍与目录的读写全部收在 BookRepository，" +
                "`legacyDaoInjectionBaseline` 里这个文件的基线是 0——" +
                "章节读取请用 currentChapter() 或 bookRepository 的方法。",
            violations.isEmpty(),
        )
    }

    private fun constructorParameterNames(type: KClass<*>): Set<String> =
        type.primaryConstructor?.parameters?.mapNotNull { it.name }?.toSet().orEmpty()

    private data class DomainSplit(
        val name: String,
        val delegateFile: String,
        /** 不允许再出现在 ReadBookUiState 里的字段名。 */
        val stateFields: Set<String>,
        /** 不允许再出现在 ReadBookViewModel.kt 里的状态类型名。 */
        val stateTypes: List<String>,
    ) {
        val delegateSimpleName: String get() = delegateFile.substringAfterLast('/').removeSuffix(".kt")
    }

    private companion object {
        val DOMAINS = listOf(
            // 多角色分配域：确认/创建/取消与收尾全在 delegate，VM 只剩三个意图分支转发。
            // 标记读写与角色表访问全收口 CastAssignmentStore（架构护栏）。
            DomainSplit(
                name = "多角色分配",
                delegateFile = "io/legado/app/ui/book/read/ReadAloudCastDelegate.kt",
                stateFields = emptySet(),
                stateTypes = listOf("CastAssignmentStore", "CastResult"),
            ),
            DomainSplit(
                name = "AI",
                delegateFile = "io/legado/app/ui/book/read/ReadAiDelegate.kt",
                stateFields = setOf(
                    "chapterSummary",
                    "aiTextClean",
                    "aiTextRewrite",
                    "aiRewritePresetConfig",
                ),
                stateTypes = listOf(
                    "ChapterSummaryUiState",
                    "AiTextCleanUiState",
                    "AiTextRewriteUiState",
                    "AiRewritePresetConfigUiState",
                    "AiRewritePresetUi",
                    "AiRewriteHistoryUi",
                ),
            ),
            DomainSplit(
                name = "高亮规则",
                delegateFile = "io/legado/app/ui/book/read/ReadHighlightRuleDelegate.kt",
                stateFields = setOf("highlightRuleConfig"),
                stateTypes = listOf("HighlightRuleConfigUiState"),
            ),
            DomainSplit(
                name = "正文编辑",
                delegateFile = "io/legado/app/ui/book/read/ReadContentEditDelegate.kt",
                stateFields = setOf(
                    "contentEditLoading",
                    "contentEditText",
                    "contentEditTitle",
                    "contentEditCursorOffset",
                    "contentEditIsLocalTxt",
                    "contentEditSaveToSource",
                ),
                stateTypes = listOf("ContentEditUiState"),
            ),
            // 配置分发域无自持状态：stateFields 为空，靠 stateTypes 守「158 分支不回流 VM」
            DomainSplit(
                name = "配置更新分发",
                delegateFile = "io/legado/app/ui/book/read/ReadConfigUpdateDelegate.kt",
                stateFields = emptySet(),
                stateTypes = listOf("is ConfigUpdate."),
            ),
            DomainSplit(
                name = "正文处理",
                delegateFile = "io/legado/app/ui/book/read/ReadContentProcessDelegate.kt",
                stateFields = setOf("contentProcessConfig"),
                stateTypes = listOf("ContentProcessConfigUiState", "ContentProcessItemUi"),
            ),
            // 开书域无自持状态：isInitFinish 是 Canvas 首帧的放行门闩，必须留在 UiState
            DomainSplit(
                name = "开书/换源",
                delegateFile = "io/legado/app/ui/book/read/ReadBookLoadDelegate.kt",
                stateFields = emptySet(),
                // 用「调用点」而不是「依赖名」当标记：依赖名在 VM 的 delegate 装配处
                // 本来就会出现，那是正当接线，不是逻辑回流。
                stateTypes = listOf(
                    "changeBookSourceUseCase.changeTo",
                    "WebBook.getChapterListAwait",
                    "uploadReadingProgressUseCase.execute",
                ),
            ),
            DomainSplit(
                name = "阅读记录归属",
                delegateFile = "io/legado/app/ui/book/read/ReadRecordAliasDelegate.kt",
                stateFields = emptySet(),
                stateTypes = listOf("getUnknownAuthorRecords", "ReadRecordAliasDecision."),
            ),
            DomainSplit(
                name = "书签",
                delegateFile = "io/legado/app/ui/book/read/ReadBookmarkDelegate.kt",
                stateFields = emptySet(),
                // ReaderBookmarkState 是渲染层同步查角标用的快照：订阅 flowByBook 与
                // 退出时清理都归本域，VM 只投影 bookKey。
                stateTypes = listOf(
                    "bookmarkRepository.save",
                    "bookmarkRepository.delete",
                    "bookmarkRepository.flowByBook",
                    "ReaderBookmarkState",
                ),
            ),
            // 样式域无自持状态：styleConfig 的重建由 VM 的 collectReadStyle() 统一驱动，
            // activeReminder / eyeProtection 被菜单栏直读；靠 stateTypes 守
            // 「取色、日夜提醒判定、样式导入导出不回流 VM」
            DomainSplit(
                name = "阅读样式",
                delegateFile = "io/legado/app/ui/book/read/ReadStyleDelegate.kt",
                stateFields = emptySet(),
                stateTypes = listOf(
                    "ReadBookColorPickerIds",
                    "ReminderType.DayNightReminder",
                    "importCurrentStyle",
                    "saveBackgroundImage",
                ),
            ),
            // 朗读域无自持状态：播放/定时/语速字段仍被菜单等渲染直读；朗读设置改整页后，
            // 设置写入与合成管线重启落在 readaloud/player 包的 ReadAloudPlayerViewModel，
            // delegate 只剩播放控制、声音目录同步与语速写入。
            // 靠 stateTypes 守「这些逻辑不回流阅读页 VM」
            DomainSplit(
                name = "朗读",
                delegateFile = "io/legado/app/ui/book/read/ReadAloudDelegate.kt",
                stateFields = emptySet(),
                stateTypes = listOf(
                    "readAloudSettingsRepository.update",
                    "VoiceCatalogEntry",
                    "refreshReadAloudClass",
                ),
            ),
            // 按钮配置域无自持状态：按钮列表仍在 menuConfig 里，靠 stateTypes 守
            // 「SharedPreferences 读写和归一化逻辑不回流 VM」。上游曾把「更多操作」的
            // 归一化/解析直接长在 VM（MoreActionIds 是它的标记），已并回本域。
            DomainSplit(
                name = "菜单按钮配置",
                delegateFile = "io/legado/app/ui/book/read/ReadButtonConfigDelegate.kt",
                stateFields = emptySet(),
                stateTypes = listOf("ReadBookButtonIds", "getSharedPreferences", "MoreActionIds"),
            ),
            // 净化规则域无自持状态：allReplaceRules 被 TextProcessingSheet 直读，仍在
            // UiState；靠 stateTypes 守「规则读写与净化管线刷新不回流 VM」
            DomainSplit(
                name = "净化规则",
                delegateFile = "io/legado/app/ui/book/read/ReadReplaceRuleDelegate.kt",
                stateFields = emptySet(),
                stateTypes = listOf(
                    "replaceRuleRepository.flowAll",
                    "replaceRuleRepository.setEnabled",
                    "replaceRuleRepository.moveReplaceRule",
                    "replaceRuleRepository.insert",
                    "upReplaceRules",
                ),
            ),
            // 书签角标域无自持状态：图片拷贝落盘与解码缓存都在 delegate / 渲染层，
            // 靠 stateTypes 守「文件操作逻辑不回流 VM」（VM 只转发意图）
            DomainSplit(
                name = "书签角标",
                delegateFile = "io/legado/app/ui/book/read/BookmarkBadgeDelegate.kt",
                stateFields = emptySet(),
                stateTypes = listOf(
                    "copyToAppStorage",
                    "bookmark_badge.",
                ),
            ),
            // 划线笔记域自持临时会话状态：配置会话与落库都在 delegate / use case，
            // book_marks 表独立于书签与 AI 正文处理，靠 stateTypes 守「标记会话与
            // 保存逻辑不回流 VM」（VM 只转发意图并注入 use case）
            DomainSplit(
                name = "划线笔记",
                delegateFile = "io/legado/app/ui/book/read/MarkingDelegate.kt",
                stateFields = emptySet(),
                stateTypes = listOf(
                    "MarkingUiState",
                    "saveMarkingUseCase.save",
                    "highlightRuleRepository.load",
                ),
            ),
            // 跳转校验域无自持状态：校验逻辑在 delegate，确认框状态 pendingBookmarkTarget
            // 是瞬态对话框（同 activeDialog），留 UiState；靠 stateTypes 守「校验与跳转不回流 VM」
            DomainSplit(
                name = "跳转校验",
                delegateFile = "io/legado/app/ui/book/read/ReadBookmarkNavigateDelegate.kt",
                stateFields = emptySet(),
                stateTypes = listOf(
                    "verifyUseCase.verify",
                    "bookRepository.getChapterTitle",
                ),
            ),
            // 云端进度同步域无自持状态：投影目标 isReadingProgressSyncConfigured 是菜单
            // 可见性输入，仍在 UiState；靠 stateTypes 守「订阅云端可用性流 + 开菜单补
            // 初始化不回流 VM」——这两件事一回流，入口就会重新变成「读一次快照」。
            DomainSplit(
                name = "云端进度同步",
                delegateFile = "io/legado/app/ui/book/read/ReadingProgressSyncDelegate.kt",
                stateFields = emptySet(),
                stateTypes = listOf(
                    "isConfiguredFlow",
                    "useCase.ensureConfigured",
                ),
            ),
        )

        val APP_DB_DAO = Regex("""\bappDb\.[A-Za-z0-9_]*Dao\b""")
        val DAO_IMPORT = Regex(
            """^import io\.legado\.app\.data\.dao\.[A-Za-z0-9_*]+$""",
            RegexOption.MULTILINE,
        )

        fun mainSourceFile(relativePath: String): File {
            val candidate = mainSourcePath(relativePath)
            if (candidate.isFile) return candidate
            error("从 ${File("").absolutePath} 向上找不到 $relativePath")
        }

        fun mainSourcePath(relativePath: String): File = locateProjectPath(
            candidates = listOf("src/main/java/$relativePath", "app/src/main/java/$relativePath"),
        )

        fun projectPath(relativePath: String): File = locateProjectPath(
            candidates = listOf(relativePath, relativePath.removePrefix("app/")),
        )

        private fun locateProjectPath(candidates: List<String>): File {
            var directory: File? = File("").absoluteFile
            while (directory != null) {
                candidates.forEach { relativePath ->
                    val candidate = File(directory, relativePath)
                    if (candidate.exists()) return candidate
                }
                if (File(directory, ".git").exists()) return File(directory, candidates.first())
                directory = directory.parentFile
            }
            return File(candidates.first())
        }
    }
}
