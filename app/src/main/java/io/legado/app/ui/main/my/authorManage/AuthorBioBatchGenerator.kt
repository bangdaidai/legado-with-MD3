package io.legado.app.ui.main.my.authorManage

import androidx.compose.runtime.Stable
import io.legado.app.R
import io.legado.app.data.repository.AuthorProfileRepository
import io.legado.app.data.repository.ReadingMemoryRepository
import io.legado.app.domain.model.AiFailureKind
import io.legado.app.domain.model.aiFailureKind
import io.legado.app.domain.usecase.GenerateAuthorBioUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import splitties.init.appCtx

/**
 * 批量生成作者简介的应用级状态宿主。
 *
 * 之前任务挂在页面 ViewModel 的 viewModelScope 上，任何作用域取消（导航回收 entry、
 * Activity 被系统重建等）都会把批量静默杀掉——AI 日志里只剩一条 "Job was cancelled"，
 * 界面永远停在"正在生成"，看不到统计。现在进度与结果放在这个单例里，页面 ViewModel
 * 只负责收集展示，销毁重建都不中断生成，回到页面还能接上进度与结果。
 */
class AuthorBioBatchGenerator(
    private val repository: ReadingMemoryRepository,
    private val authorProfileRepository: AuthorProfileRepository,
    private val generateAuthorBioUseCase: GenerateAuthorBioUseCase,
) {

    @Stable
    data class State(
        val bioGeneration: BioGenerationUi? = null,
        val message: String? = null,
    )

    /** 与 CoverAlbumRepository 等仓库一致的自管作用域：应用级生命周期，不随页面销毁。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.update {
            it.copy(
                bioGeneration = null,
                message = appCtx.getString(R.string.author_bio_generate_cancelled),
            )
        }
    }

    /** 一键生成缺失简介：逐个为没有简介的作者调用 AI，完成后写档并刷新列表 */
    private suspend fun run() {
        _state.update { State() }
        val memories = repository.observeAll().first()
        val profiles = authorProfileRepository.observeProfiles().first()
        val targets = memories.groupBy { it.bookAuthor.trim() }
            .filterKeys { it.isNotBlank() }
            .map { (name, mems) -> name to mems.map { it.bookName } }
            .filter { (name, _) -> profiles[name]?.bio.isNullOrBlank() }
        if (targets.isEmpty()) {
            _state.update {
                it.copy(
                    bioGeneration = null,
                    message = appCtx.getString(R.string.author_bio_missing_none),
                )
            }
            return
        }
        var success = 0
        var failed = 0
        var consecutiveRateLimit = 0
        targets.forEachIndexed { index, (name, titles) ->
            _state.update {
                it.copy(bioGeneration = BioGenerationUi(name, index, targets.size))
            }
            val result = try {
                generateAuthorBioUseCase.execute(name, titles)
            } catch (e: CancellationException) {
                // 只有批量任务自身被取消（用户点取消或进程结束）才向上抛；
                // 生成链路里泄漏出来的取消按该作者失败处理，否则整个批量会静默中断。
                if (!currentCoroutineContext().isActive) throw e
                Result.failure(e)
            }
            result.fold(
                onSuccess = { generated ->
                    authorProfileRepository.saveAiBio(name, generated.bio, generated.modelId)
                    success++
                    consecutiveRateLimit = 0
                },
                onFailure = { error ->
                    failed++
                    if (error.aiFailureKind() == AiFailureKind.RATE_LIMIT) {
                        consecutiveRateLimit++
                    } else {
                        consecutiveRateLimit = 0
                    }
                },
            )
            // 连续多位作者都撞限流，说明是接口整体被限流而不是单次偶发，
            // 继续逐个重试只会把同样的事重复几十遍，直接熔断中止。
            if (consecutiveRateLimit >= MAX_CONSECUTIVE_RATE_LIMIT) {
                _state.update {
                    it.copy(
                        bioGeneration = null,
                        message = appCtx.getString(
                            R.string.author_bio_generate_rate_limited, success, failed,
                        ),
                    )
                }
                return
            }
        }
        _state.update {
            it.copy(
                bioGeneration = null,
                message = appCtx.getString(
                    R.string.author_bio_generate_done, success, failed,
                ),
            )
        }
    }

    private companion object {
        /** 连续多少位作者因限流失败后中止整个批量。 */
        const val MAX_CONSECUTIVE_RATE_LIMIT = 3
    }
}
