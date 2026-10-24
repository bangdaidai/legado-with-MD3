package io.legado.app.data.repository

import io.legado.app.help.DefaultData
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.move
import java.util.UUID

/**
 * 排版配置列表的**唯一所有者**。
 *
 * R4.4 把写面收进 gateway 实现之后，`ReadBookConfig` 只剩「选哪一份 + 怎么读」这两件事，
 * 但列表本体和那把 `@Synchronized` 锁还挂在它身上——状态和保护它的锁分居两处，
 * 谁都能顺手再往那个全局 object 上塞新状态。本类把两者放回一起：
 * 上游是文件层 [ReadStyleRepository]，下游是 gateway 实现（写）与 `ReadBookConfig`（只读投影）。
 *
 * 本类**不知道当前选中哪一份**：`styleSelect` / `shareLayout` 是设置项，
 * 由 `ReadBookConfig` 解析成下标后再来取用。
 */
class ReadStyleConfigStore(private val readStyleRepository: ReadStyleRepository) {

    private val lock = Any()
    private val configList = arrayListOf<ReadBookConfig.Config>()

    /**
     * 内置预设按**稳定 id**识别（资产 readConfig.json 里的 preset-N），
     * 不再按下标 <5 划界：预设可删，删完剩下的自建样式不会误入"预设区"。
     * 自建样式一律 UUID（[addConfig]/[importOrReplaceConfig] 新增时生成），
     * 旧备份里的条目解析时也拿随机 UUID，永不撞预设 id。
     */
    private val presetIds: Set<String> =
        DefaultData.readConfigs.mapNotNull { it.id }.toSet()

    private fun isPreset(config: ReadBookConfig.Config): Boolean = config.id in presetIds
    private lateinit var shareConfigRef: ReadBookConfig.Config

    val configFilePath: String get() = readStyleRepository.configFilePath
    val shareConfigFilePath: String get() = readStyleRepository.shareConfigFilePath

    /** 共享排版那一份。 */
    val shareConfig: ReadBookConfig.Config get() = synchronized(lock) { shareConfigRef }

    fun initConfigs() {
        val configs = readStyleRepository.readConfigs()
        synchronized(lock) {
            configList.clear()
            configList.addAll(configs)
        }
    }

    fun initShareConfig() {
        val fallback = synchronized(lock) {
            configList.firstOrNull { !isPreset(it) }
        } ?: ReadBookConfig.Config()
        val config = readStyleRepository.readShareConfig(fallback)
        synchronized(lock) { shareConfigRef = config }
    }

    /** 按下标取用。配置文件为空时先恢复默认，保证至少 1 份可用。 */
    fun configAt(index: Int): ReadBookConfig.Config = synchronized(lock) {
        if (configList.isEmpty()) {
            resetAllLocked()
        }
        configList.getOrNull(index) ?: configList[0]
    }

    /** 整份替换（应用预设 / 导入）。共享排版开着时，共享那份跟着换。 */
    fun replaceConfigAt(index: Int, config: ReadBookConfig.Config, alsoShare: Boolean) {
        synchronized(lock) {
            val replacement = config.copy(id = configList[index].id)
            configList[index] = replacement
            if (alsoShare) {
                shareConfigRef = replacement
            }
        }
    }

    /**
     * 改**当前样式**那一份的字段。共享排版开着时也只动这一份——背景、虚线、状态栏图标
     * 这些按样式独立的项走这里。
     *
     * 下标解析与 [configAt] 保持一致（含配置文件缺斤少两时先恢复默认、越界回落到 0），
     * 否则「读得到、写不进」会成为一类静默失效。
     */
    fun updateStyleAt(index: Int, transform: (ReadBookConfig.Config) -> ReadBookConfig.Config) {
        synchronized(lock) {
            if (configList.isEmpty()) {
                resetAllLocked()
            }
            val target = if (index in configList.indices) index else 0
            configList[target] = transform(configList[target])
        }
    }

    /** 改**当前生效**那一份：共享排版开着时是共享那份，否则就是当前样式。 */
    fun updateEffective(
        index: Int,
        useShare: Boolean,
        transform: (ReadBookConfig.Config) -> ReadBookConfig.Config,
    ) {
        if (useShare) {
            synchronized(lock) { shareConfigRef = transform(shareConfigRef) }
        } else {
            updateStyleAt(index, transform)
        }
    }

    /** `Config` 的值字段不可变，快照直接共享实例即可。 */
    fun configsSnapshot(): List<ReadBookConfig.Config> = synchronized(lock) {
        configList.toList()
    }

    fun shareConfigSnapshot(): ReadBookConfig.Config = synchronized(lock) { shareConfigRef }

    fun addConfig(config: ReadBookConfig.Config): Int = synchronized(lock) {
        configList.add(config.copy(id = UUID.randomUUID().toString()))
        configList.lastIndex
    }

    fun importOrReplaceConfig(config: ReadBookConfig.Config): String = synchronized(lock) {
        val index = configList.indexOfFirst { it.name == config.name }
        if (index >= 0) {
            configList[index] = config.copy(id = configList[index].id)
        } else {
            configList.add(config.copy(id = UUID.randomUUID().toString()))
        }
        config.name
    }

    /** 至少留 1 份，删到空会失去当前排版。预设与自建都可删。 */
    fun deleteConfigAt(index: Int): Boolean = synchronized(lock) {
        if (configList.size <= 1) return@synchronized false
        configList.removeAt(index)
        true
    }

    /**
     * 把 [from] 处的一份样式移到 [to]（都是列表绝对下标，语义同 [io.legado.app.utils.move]：
     * 先移除，再插入到 [to]）。
     *
     * 预设不可拖；自建样式在"预设区之后"任意移动（预设可删，边界按 id 现算，
     * 保证预设始终排在列表前部）。应用预设（`applyPreset`）读的是内置默认值，
     * 与列表里的顺序无关。
     */
    fun moveStyle(from: Int, to: Int): Boolean = synchronized(lock) {
        if (!isCustomIndex(from) || !isCustomIndex(to) || from == to) return@synchronized false
        if (to < presetBoundary()) return@synchronized false
        configList.move(from, to)
        true
    }

    /**
     * 批量删除样式（下标按删除前的列表算），预设与自建都可删，返回是否真删了。
     * 至少保留 1 份：全选时会静默跳过最后一个目标。
     * 选中下标修正与高亮规则解绑由上层负责——本类不知道当前选中哪一份。
     */
    fun deleteStylesAt(indices: List<Int>): Boolean = synchronized(lock) {
        val targets = indices.filter { it in configList.indices }.distinct().sortedDescending()
        if (targets.isEmpty() || targets.size >= configList.size) return@synchronized false
        targets.forEach { configList.removeAt(it) }
        true
    }

    /**
     * 把 [indices] 里那些样式**整块**搬到边缘（[toTop] = 紧跟预设之后，否则列表末尾），
     * 块内保持它们原来的相对次序。返回是否真的动了。
     */
    fun moveStylesToEdge(indices: List<Int>, toTop: Boolean): Boolean = synchronized(lock) {
        val targets = indices.filter { isCustomIndex(it) }.distinct().sorted()
        if (targets.isEmpty()) return@synchronized false
        val moving = targets.map { configList[it] }
        // 从后往前删，避免下标漂移
        targets.sortedDescending().forEach { configList.removeAt(it) }
        if (toTop) {
            // 紧跟预设区之后：预设可删，边界按 id 现算
            configList.addAll(presetBoundary(), moving)
        } else {
            configList.addAll(moving)
        }
        true
    }

    /** 自建样式：非预设 id 的条目。预设可删，预设区大小随删随变。 */
    private fun isCustomIndex(index: Int): Boolean =
        index in configList.indices && !isPreset(configList[index])

    /** 预设区边界（最后一份预设的下标 + 1）；预设全删光时为 0。 */
    private fun presetBoundary(): Int =
        configList.indexOfLast { isPreset(it) } + 1

    /** 备份要把排版引用到的背景图一起带走。 */
    fun allPicBgStr(): List<String> = readStyleRepository.getAllPicBgStr(configsSnapshot())

    /** 清掉没有任何排版引用的背景图，以及导入导出留下的缓存。 */
    fun clearBgAndCache() {
        readStyleRepository.clearBgAndCache(configsSnapshot())
    }

    private fun resetAllLocked() {
        configList.clear()
        configList.addAll(DefaultData.readConfigs)
        save()
    }

    private fun save() {
        // 列表与共享配置在同一临界区内取快照，落盘的两份内容彼此自洽
        val (configs, share) = synchronized(lock) { configList.toList() to shareConfigRef }
        Coroutine.async {
            readStyleRepository.save(configs, share)
        }
    }
}
