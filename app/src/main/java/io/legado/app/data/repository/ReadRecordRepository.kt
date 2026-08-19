package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.dao.ReadRecordDao
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordAliasAction
import io.legado.app.data.entities.readRecord.ReadRecordAliasDecision
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.data.entities.readRecord.ReadRecordTimelineDay
import io.legado.app.data.entities.readRecord.ReadRecordIdentity
import io.legado.app.data.entities.readRecord.ReadRecordRepairReport
import io.legado.app.data.local.preferences.LocalPreferencesKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.max
import kotlin.math.min

class ReadRecordRepository(
    private val dao: ReadRecordDao,
    private val database: AppDatabase,
    private val localPreferencesRepository: SettingsRepository,
) {
    private fun getCurrentDeviceId(): String = ""

    private val SESSION_MERGE_GAP = 2 * 60 * 1000L

    /** UI 展示层合并阈值: 相邻同书会话间隔不超过 5 分钟时聚合为一段展示 */
    private val DISPLAY_MERGE_GAP = 5 * 60 * 1000L

    /**
     * 展示层合并: 把按 startTime 升序排列的同书会话中, 相邻间隔 <= [DISPLAY_MERGE_GAP] 的聚合为一段.
     * 仅用于展示, 不写库. 合并后:
     * - endTime 取末段结束
     * - words 累加(避免丢字数)
     * - chapterTitle 保留首段(时间线上"从这一章开始")
     * 返回的每段仍是 ReadRecordSession, 其 id 只指向首段,
     * 因此 [deleteSession] 按 [startTime, endTime] 区间删除而非按主键删除.
     */
    private fun mergeContinuousSessions(sessions: List<ReadRecordSession>): List<ReadRecordSession> {
        if (sessions.isEmpty()) return emptyList()
        val merged = mutableListOf<ReadRecordSession>()
        merged.add(sessions.first().copy())
        for (i in 1 until sessions.size) {
            val current = sessions[i]
            val last = merged.last()
            if (current.bookName == last.bookName &&
                current.bookAuthor == last.bookAuthor &&
                current.startTime - last.endTime <= DISPLAY_MERGE_GAP
            ) {
                merged[merged.lastIndex] = last.copy(
                    endTime = maxOf(last.endTime, current.endTime),
                    words = last.words + current.words
                )
            } else {
                merged.add(current.copy())
            }
        }
        return merged
    }

    private fun Long.toDateString(): String =
        Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

    val readRecordEnabled: Flow<Boolean> =
        localPreferencesRepository.getPreference(LocalPreferencesKeys.ENABLE_READ_RECORD, true)

    suspend fun setReadRecordEnabled(enabled: Boolean) {
        localPreferencesRepository.updatePreference(LocalPreferencesKeys.ENABLE_READ_RECORD, enabled)
    }

    /**
     * 获取总阅读时长流
     */
    fun getTotalReadTime(bookType: Int? = null): Flow<Long> {
        return if (bookType != null) {
            dao.getTotalReadTimeByType(bookType).map { it ?: 0L }
        } else {
            dao.getTotalReadTime().map { it ?: 0L }
        }
    }

    /**
     * 根据搜索关键字获取跨设备聚合后的最新阅读书籍列表流。
     * 同一本书的各设备记录会合并阅读时长，并保留最新阅读时间。
     */
    fun getLatestReadRecords(query: String = "", bookType: Int? = null): Flow<List<ReadRecord>> {
        return if (bookType != null) {
            if (query.isBlank()) {
                dao.getAllReadRecordsSortedByLastReadByType(bookType)
            } else {
                dao.searchReadRecordsByLastReadByType(query, bookType)
            }
        } else {
            if (query.isBlank()) {
                dao.getAllReadRecordsSortedByLastRead()
            } else {
                dao.searchReadRecordsByLastRead(query)
            }
        }
            .map { records ->
                records.groupBy { it.bookName to it.bookAuthor }
                    .values
                    .map { sameBook ->
                        sameBook.first().copy(
                            deviceId = "",
                            readTime = sameBook.sumOf { it.readTime },
                            lastRead = sameBook.maxOf { it.lastRead },
                        )
                    }
                    .sortedByDescending { it.lastRead }
            }
    }

    /**
     * 获取跨设备聚合后的每日统计详情流。
     * 同一本书同一天的各设备详情会合并阅读时长和字数。
     */
    fun getAllRecordDetails(query: String = "", bookType: Int? = null): Flow<List<ReadRecordDetail>> {
        return if (bookType != null) {
            if (query.isBlank()) {
                dao.getAllDetailsByType(bookType)
            } else {
                dao.searchDetailsByType(query, bookType)
            }
        } else {
            if (query.isBlank()) {
                dao.getAllDetails()
            } else {
                dao.searchDetails(query)
            }
        }
            .map { details ->
                details.groupBy { Triple(it.bookName, it.bookAuthor, it.date) }
                    .values
                    .map { sameDay ->
                        sameDay.first().copy(
                            deviceId = "",
                            readTime = sameDay.sumOf { it.readTime },
                            readWords = sameDay.sumOf { it.readWords },
                            firstReadTime = sameDay.map { it.firstReadTime }
                                .filter { it > 0L }
                                .minOrNull() ?: 0L,
                            lastReadTime = sameDay.maxOf { it.lastReadTime },
                        )
                    }
                    .sortedWith(compareByDescending<ReadRecordDetail> { it.date }.thenByDescending { it.lastReadTime })
            }
    }

    fun getAllSessions(bookType: Int? = null): Flow<List<ReadRecordSession>> {
        // UI 展示的是跨设备合并后的时间线；去重键不包含自增 id，避免同步副本重复计时。
        return dao.getAllSessions().map { sessions ->
            sessions.asSequence()
                .filter { bookType == null || (it.bookType and bookType) > 0 }
                .distinctBy {
                    listOf(it.bookName, it.bookAuthor, it.startTime, it.endTime, it.words)
                }
                .toList()
        }
    }

    fun getBookSessions(bookName: String, bookAuthor: String): Flow<List<ReadRecordSession>> {
        // 时间线按书名、作者和阅读时段内容去重，避免同步副本重复计时。
        return dao.getAllSessions().map { sessions ->
            sessions.asSequence()
                .filter { it.bookName == bookName && it.bookAuthor == bookAuthor }
                .distinctBy { listOf(it.bookName, it.bookAuthor, it.startTime, it.endTime, it.words) }
                .toList()
        }
    }

    fun getBookTimelineDays(bookName: String, bookAuthor: String): Flow<List<ReadRecordTimelineDay>> {
        return getBookSessions(bookName, bookAuthor).map { sessions ->
            sessions.groupBy { it.startTime.toDateString() }
                .toSortedMap(compareByDescending { it })
                .map { (date, daySessions) ->
                    ReadRecordTimelineDay(
                        date = date,
                        sessions = mergeContinuousSessions(
                            daySessions.sortedBy { it.startTime }
                        ).reversed()
                    )
                }
        }
    }

    fun getBookReadTime(bookName: String, bookAuthor: String): Flow<Long> {
        // 统计所有设备的汇总时长，与跨设备时间线保持一致。
        return dao.getReadTimeFlow(bookName, bookAuthor).map { it ?: 0L }
    }

    suspend fun getMergeCandidates(targetRecord: ReadRecord): List<ReadRecord> {
        return dao.getMergeCandidates(
            targetRecord.deviceId,
            targetRecord.bookName,
            targetRecord.bookAuthor
        )
    }

    /** 获取指定书名下作者为空的旧记录，供打开书籍时确认归属。 */
    suspend fun getUnknownAuthorRecords(bookName: String): List<ReadRecord> {
        return dao.getUnknownAuthorRecords(bookName)
    }

    /**
     * 保存一个完整的阅读会话.
     * auto-save 每 120 秒提交一个片段, 若该书最后一条会话与本片段间隔不超过 [SESSION_MERGE_GAP],
     * 直接合并延长该会话, 避免连续阅读在 DB 里积累大量 2 分钟碎片.
     */
    suspend fun saveReadSession(newSession: ReadRecordSession) {
        if (!readRecordEnabled.first()) return
        if (newSession.endTime <= newSession.startTime) return
        val normalizedSession = newSession.copy(
            bookName = ReadRecordIdentity.bookName(newSession.bookName),
            bookAuthor = ReadRecordIdentity.author(newSession.bookAuthor),
        )
        database.withTransaction {
            // 旧版记录可能没有作者；打开同一本有作者的书后，将未知作者记录并入当前记录，
            // 避免第一次阅读时重新创建一条独立的阅读统计。
            // 用户曾明确选择「保留独立记录」时尊重该决定，不自动合并。
            if (normalizedSession.bookAuthor.isNotBlank() &&
                !hasKeepAliasDecision(normalizedSession.bookName, normalizedSession.bookAuthor)
            ) {
                val unknownAuthorRecord = dao.getReadRecord(
                    normalizedSession.deviceId,
                    normalizedSession.bookName,
                    ""
                )
                if (unknownAuthorRecord != null) {
                    mergeSingleReadRecordInto(
                        targetRecord = ReadRecord(
                            deviceId = normalizedSession.deviceId,
                            bookName = normalizedSession.bookName,
                            bookAuthor = normalizedSession.bookAuthor
                        ),
                        sourceRecord = unknownAuthorRecord
                    )
                }
            }
            val existingSession = dao.getSession(
                normalizedSession.deviceId,
                normalizedSession.bookName,
                normalizedSession.bookAuthor,
                normalizedSession.startTime,
                normalizedSession.endTime,
                normalizedSession.words
            )
            if (existingSession != null) return@withTransaction

            val segmentDuration = normalizedSession.endTime - normalizedSession.startTime
            val lastSession = dao.getLatestSessionByBook(
                normalizedSession.bookName,
                normalizedSession.bookAuthor
            )
            if (lastSession != null &&
                lastSession.deviceId == normalizedSession.deviceId &&
                normalizedSession.startTime - lastSession.endTime <= SESSION_MERGE_GAP
            ) {
                dao.updateSession(
                    lastSession.copy(
                        endTime = max(lastSession.endTime, normalizedSession.endTime),
                        words = lastSession.words + normalizedSession.words,
                        chapterTitle = normalizedSession.chapterTitle.ifBlank { lastSession.chapterTitle }
                    )
                )
            } else {
                dao.insertSession(normalizedSession)
            }
            val dateString = normalizedSession.startTime.toDateString()
            updateReadRecordDetail(normalizedSession, segmentDuration, normalizedSession.words, dateString)
            updateReadRecord(normalizedSession, segmentDuration)
        }
    }

    /** 用户是否曾为当前书籍明确选择「保留独立记录」。 */
    private suspend fun hasKeepAliasDecision(bookName: String, bookAuthor: String): Boolean {
        val key = ReadRecordIdentity.key(bookName, bookAuthor)
        return localPreferencesRepository
            .getString(LocalPreferencesKeys.READ_RECORD_ALIAS_DECISIONS.name)
            .first()
            .split('\n')
            .mapNotNull { ReadRecordAliasDecision.decode(it, key) }
            .firstOrNull() == ReadRecordAliasAction.KEEP
    }

    private suspend fun updateReadRecord(session: ReadRecordSession, durationDelta: Long) {
        if (durationDelta <= 0) return
        val existingRecord = dao.getReadRecord(session.deviceId, session.bookName, session.bookAuthor)
        if (existingRecord != null) {
            dao.update(
                existingRecord.copy(
                    readTime = existingRecord.readTime + durationDelta,
                    lastRead = session.endTime
                )
            )
        } else {
            dao.insert(
                ReadRecord(
                    deviceId = session.deviceId,
                    bookName = session.bookName,
                    bookAuthor = session.bookAuthor,
                    readTime = durationDelta,
                    lastRead = session.endTime
                )
            )
        }
    }

    private suspend fun updateReadRecordDetail(
        session: ReadRecordSession,
        durationDelta: Long,
        wordsDelta: Long,
        dateString: String
    ) {
        if (durationDelta <= 0 && wordsDelta <= 0) return
        val existingDetail = dao.getDetail(
            session.deviceId,
            session.bookName,
            session.bookAuthor,
            dateString
        )
        if (existingDetail != null) {
            existingDetail.readTime += durationDelta
            existingDetail.readWords += wordsDelta
            existingDetail.firstReadTime = minPositive(existingDetail.firstReadTime, session.startTime)
            existingDetail.lastReadTime = max(existingDetail.lastReadTime, session.endTime)
            dao.insertDetail(existingDetail)
        } else {
            dao.insertDetail(
                ReadRecordDetail(
                    deviceId = session.deviceId,
                    bookName = session.bookName,
                    bookAuthor = session.bookAuthor,
                    date = dateString,
                    readTime = durationDelta,
                    readWords = wordsDelta,
                    firstReadTime = session.startTime,
                    lastReadTime = session.endTime,
                    bookType = session.bookType
                )
            )
        }
    }

    suspend fun deleteDetail(detail: ReadRecordDetail) {
        database.withTransaction {
            // 聚合详情代表所有设备同一天的阅读，删除时必须同步删除底层阅读时段记录。
            val affectedDevices = dao.allSession.asSequence()
                .filter {
                    it.bookName == detail.bookName &&
                        it.bookAuthor == detail.bookAuthor &&
                        it.startTime.toDateString() == detail.date
                }
                .mapTo(linkedSetOf()) { it.deviceId }
                .apply { addAll(dao.getReadRecordsByName(detail.bookName, detail.bookAuthor).map { it.deviceId }) }
            dao.deleteDetailByNameAndDate(detail.bookName, detail.bookAuthor, detail.date)
            affectedDevices.forEach { deviceId ->
                dao.deleteSessionsByBookAndDate(deviceId, detail.bookName, detail.bookAuthor, detail.date)
                // 没有阅读时段记录的旧版汇总记录不能因删除详情而被误删。
                if (dao.getSessionsByBook(deviceId, detail.bookName, detail.bookAuthor).isNotEmpty()) {
                    updateReadRecordTotal(deviceId, detail.bookName, detail.bookAuthor)
                }
            }
        }
    }

    /**
     * 删除一个会话。传入的 session 可能是展示层合并后的聚合段，
     * 其 id 只指向首段，因此按 [startTime, endTime] 区间删掉所有底层成员。
     */
    suspend fun deleteSession(session: ReadRecordSession) {
        database.withTransaction {
            val affectedDevices = dao.allSession.asSequence()
                .filter {
                    it.bookName == session.bookName &&
                        it.bookAuthor == session.bookAuthor &&
                        it.startTime == session.startTime &&
                        it.endTime == session.endTime &&
                        it.words == session.words
                }
                .mapTo(linkedSetOf()) { it.deviceId }
                .apply { addAll(dao.getReadRecordsByName(session.bookName, session.bookAuthor).map { it.deviceId }) }
            dao.deleteSessionByIdentity(
                session.bookName,
                session.bookAuthor,
                session.startTime,
                session.endTime,
                session.words,
            )
            val dateString = session.startTime.toDateString()
            affectedDevices.forEach { deviceId ->
                    val record = ReadRecord(
                        deviceId = deviceId,
                        bookName = session.bookName,
                        bookAuthor = session.bookAuthor,
                        bookType = session.bookType
                    )
                    val remainingSessions = dao.getSessionsByBookAndDate(
                        deviceId,
                        record.bookName,
                        record.bookAuthor,
                        dateString,
                    )
                    val detail = dao.getDetail(
                        deviceId,
                        record.bookName,
                        record.bookAuthor,
                        dateString,
                    )
                    if (remainingSessions.isEmpty()) {
                        detail?.let { dao.deleteDetail(it) }
                    } else {
                        dao.insertDetail(
                            (detail ?: ReadRecordDetail(
                                deviceId = record.deviceId,
                                bookName = record.bookName,
                                bookAuthor = record.bookAuthor,
                                date = dateString,
                            )).copy(
                                readTime = remainingSessions.sumOf { it.endTime - it.startTime },
                                readWords = remainingSessions.sumOf { it.words },
                                firstReadTime = remainingSessions
                                    .map { it.startTime }
                                    .filter { it > 0L }
                                    .minOrNull() ?: 0L,
                                lastReadTime = remainingSessions.maxOf { it.endTime },
                            )
                        )
                    }
                    updateReadRecordTotal(deviceId, record.bookName, record.bookAuthor)
                }
        }
    }

    private fun minPositive(left: Long, right: Long): Long = when {
        left <= 0L -> right
        right <= 0L -> left
        else -> min(left, right)
    }

    private suspend fun updateReadRecordTotal(deviceId: String, bookName: String, bookAuthor: String) {
        val allRemainingSessions = dao.getSessionsByBook(deviceId, bookName, bookAuthor)

        if (allRemainingSessions.isEmpty()) {
            dao.getReadRecord(deviceId, bookName, bookAuthor)?.let { dao.deleteReadRecord(it) }
        } else {
            val totalTime = allRemainingSessions.sumOf { it.endTime - it.startTime }
            val lastRead = allRemainingSessions.maxOf { it.endTime }

            val existingRecord = dao.getReadRecord(deviceId, bookName, bookAuthor)
            if (existingRecord == null) {
                dao.insert(
                    ReadRecord(
                        deviceId = deviceId,
                        bookName = bookName,
                        bookAuthor = bookAuthor,
                        readTime = totalTime,
                        lastRead = lastRead,
                    )
                )
            } else {
                dao.update(
                    existingRecord.copy(
                        readTime = totalTime,
                        lastRead = lastRead
                    )
                )
            }
        }
    }

    suspend fun deleteReadRecord(record: ReadRecord) {
        database.withTransaction {
            dao.deleteByName(record.bookName, record.bookAuthor)
            dao.deleteDetailByName(record.bookName, record.bookAuthor)
            dao.deleteSessionByName(record.bookName, record.bookAuthor)
        }
    }

    suspend fun clearReadRecords() {
        database.withTransaction {
            dao.clearReadRecordSessions()
            dao.clearReadRecordDetails()
            dao.clearReadRecords()
        }
    }

    suspend fun mergeReadRecordInto(targetRecord: ReadRecord, sourceRecords: List<ReadRecord>) {
        database.withTransaction {
            sourceRecords.forEach { sourceRecord ->
                mergeSingleReadRecordInto(targetRecord, sourceRecord)
            }
        }
    }

    private suspend fun mergeSingleReadRecordInto(targetRecord: ReadRecord, sourceRecord: ReadRecord) {
        if (targetRecord == sourceRecord) return
        if (targetRecord.deviceId != sourceRecord.deviceId) return

        val source = dao.getReadRecord(
            sourceRecord.deviceId,
            sourceRecord.bookName,
            sourceRecord.bookAuthor
        ) ?: return

        val target = dao.getReadRecord(
            targetRecord.deviceId,
            targetRecord.bookName,
            targetRecord.bookAuthor
        ) ?: targetRecord

        dao.insert(
            target.copy(
                readTime = target.readTime + source.readTime,
                lastRead = max(target.lastRead, source.lastRead)
            )
        )

        val sourceDetails = dao.getDetailsByBook(
            sourceRecord.deviceId,
            sourceRecord.bookName,
            sourceRecord.bookAuthor
        )
        sourceDetails.forEach { detail ->
            val existingTargetDetail = dao.getDetail(
                targetRecord.deviceId,
                targetRecord.bookName,
                targetRecord.bookAuthor,
                detail.date
            )
            if (existingTargetDetail == null) {
                dao.insertDetail(
                    detail.copy(
                        bookName = targetRecord.bookName,
                        bookAuthor = targetRecord.bookAuthor
                    )
                )
            } else {
                dao.insertDetail(
                    existingTargetDetail.copy(
                        readTime = existingTargetDetail.readTime + detail.readTime,
                        readWords = existingTargetDetail.readWords + detail.readWords,
                        firstReadTime = minPositive(existingTargetDetail.firstReadTime, detail.firstReadTime),
                        lastReadTime = max(existingTargetDetail.lastReadTime, detail.lastReadTime)
                    )
                )
            }
        }
        dao.deleteDetailsByBook(sourceRecord.deviceId, sourceRecord.bookName, sourceRecord.bookAuthor)

        val sourceSessions = dao.getSessionsByBook(
            sourceRecord.deviceId,
            sourceRecord.bookName,
            sourceRecord.bookAuthor
        )
        sourceSessions.forEach { session ->
            dao.updateSession(
                session.copy(
                    bookName = targetRecord.bookName,
                    bookAuthor = targetRecord.bookAuthor
                )
            )
        }

        dao.deleteReadRecord(source)
        updateReadRecordTotal(targetRecord.deviceId, targetRecord.bookName, targetRecord.bookAuthor)
        // 旧版只保存汇总时长、没有阅读时段明细；重算阅读时段总时长时不能把这部分历史清零。
        if (sourceSessions.isEmpty() && source.readTime > 0) {
            val mergedTarget = dao.getReadRecord(
                targetRecord.deviceId,
                targetRecord.bookName,
                targetRecord.bookAuthor
            )
            if (mergedTarget == null) {
                dao.insert(
                    ReadRecord(
                        deviceId = targetRecord.deviceId,
                        bookName = targetRecord.bookName,
                        bookAuthor = targetRecord.bookAuthor,
                        readTime = source.readTime,
                        lastRead = max(target.lastRead, source.lastRead)
                    )
                )
            } else {
                dao.update(mergedTarget.copy(readTime = mergedTarget.readTime + source.readTime))
            }
        }
    }

    /** 清理字段完全相同的阅读时段记录，并根据剩余记录重建汇总记录。 */
    suspend fun repairDuplicateSessions(): Int {
        return database.withTransaction {
            val before = dao.allSession.size
            dao.deleteDuplicateSessions()
            dao.all.forEach { record ->
                if (dao.getSessionsByBook(record.deviceId, record.bookName, record.bookAuthor).isNotEmpty()) {
                    updateReadRecordTotal(record.deviceId, record.bookName, record.bookAuthor)
                }
            }
            return@withTransaction before - dao.allSession.size
        }
    }

    /** 规范化书名/作者，并同步合并汇总、日期详情和阅读时段记录中的碰撞记录。 */
    suspend fun repairReadRecordIdentities(): ReadRecordRepairReport {
        return database.withTransaction {
            var merged = 0
            var normalized = 0
            var exceptions = 0
            dao.all.forEach { record ->
                val name = ReadRecordIdentity.bookName(record.bookName)
                val author = ReadRecordIdentity.author(record.bookAuthor)
                if (name != record.bookName || author != record.bookAuthor) {
                    runCatching {
                        mergeReadRecordInto(ReadRecord(record.deviceId, name, author), listOf(record))
                        merged++
                        normalized++
                    }.onFailure { exceptions++ }
                }
            }
            dao.allDetail.forEach { detail ->
                val normalized = detail.copy(
                    bookName = ReadRecordIdentity.bookName(detail.bookName),
                    bookAuthor = ReadRecordIdentity.author(detail.bookAuthor),
                )
                if (normalized.bookName != detail.bookName || normalized.bookAuthor != detail.bookAuthor) {
                    val existing = dao.getDetail(normalized.deviceId, normalized.bookName, normalized.bookAuthor, normalized.date)
                    if (existing == null) dao.insertDetail(normalized)
                    else dao.insertDetail(existing.copy(
                        readTime = existing.readTime + detail.readTime,
                        readWords = existing.readWords + detail.readWords,
                        firstReadTime = minPositive(existing.firstReadTime, detail.firstReadTime),
                        lastReadTime = max(existing.lastReadTime, detail.lastReadTime),
                    ))
                    dao.deleteDetail(detail)
                }
            }
            dao.allSession.forEach { session ->
                val normalized = session.copy(
                    bookName = ReadRecordIdentity.bookName(session.bookName),
                    bookAuthor = ReadRecordIdentity.author(session.bookAuthor),
                )
                if (normalized.bookName != session.bookName || normalized.bookAuthor != session.bookAuthor) {
                    val collision = dao.getSession(
                        normalized.deviceId,
                        normalized.bookName,
                        normalized.bookAuthor,
                        normalized.startTime,
                        normalized.endTime,
                        normalized.words,
                    )
                    if (collision == null) dao.updateSession(normalized) else dao.deleteSession(session)
                }
            }
            dao.deleteDuplicateSessions()
            return@withTransaction ReadRecordRepairReport(
                mergedCount = merged,
                exceptionCount = exceptions,
                normalizedRecordCount = normalized,
            )
        }
    }

    /** 只读扫描阅读记录问题，不修改数据库；结果用于展示可修复项数量。 */
    suspend fun scanReadRecordIssues(): ReadRecordRepairReport {
        val records = dao.all
        val details = dao.allDetail
        val sessions = dao.allSession
        val duplicateSessions = sessions.size - sessions.distinctBy {
            listOf(it.deviceId, it.bookName, it.bookAuthor, it.startTime, it.endTime, it.words)
        }.size
        val duplicateRecords = records.size - records.distinctBy {
            listOf(it.deviceId, ReadRecordIdentity.bookName(it.bookName), ReadRecordIdentity.author(it.bookAuthor))
        }.size
        val duplicateDetails = details.size - details.distinctBy {
            listOf(it.deviceId, ReadRecordIdentity.bookName(it.bookName), ReadRecordIdentity.author(it.bookAuthor), it.date)
        }.size
        val normalized = records.count {
            it.bookName != ReadRecordIdentity.bookName(it.bookName) ||
                it.bookAuthor != ReadRecordIdentity.author(it.bookAuthor)
        }
        return ReadRecordRepairReport(
            duplicateSessionCount = duplicateSessions,
            mergedCount = duplicateRecords + duplicateDetails,
            normalizedRecordCount = normalized,
        )
    }

    /**
     * 以阅读时段记录为权威重建汇总与每日明细，并保留未被会话覆盖的历史时长。
     *
     * 备份恢复后调用：会话按身份去重导入（幂等），汇总/明细导入取已有与导入两者中的较大值，
     * 再按会话重算，可避免同一备份重复导入导致时长翻倍，同时正确合并跨设备的会话时长。
     */
    suspend fun reconcileReadRecordTotalsFromSessions() {
        database.withTransaction {
            dao.all.forEach { record ->
                val sessions = dao.getSessionsByBook(record.deviceId, record.bookName, record.bookAuthor)
                if (sessions.isEmpty()) return@forEach
                dao.update(record.copy(
                    readTime = maxOf(record.readTime, sessions.sumOf { it.endTime - it.startTime }),
                    lastRead = maxOf(record.lastRead, sessions.maxOf { it.endTime }),
                ))
            }
            dao.allDetail.forEach { detail ->
                val sessions = dao.getSessionsByBook(detail.deviceId, detail.bookName, detail.bookAuthor)
                    .filter {
                        it.startTime.toDateString() == detail.date
                    }
                if (sessions.isEmpty()) return@forEach
                dao.insertDetail(detail.copy(
                    readTime = maxOf(detail.readTime, sessions.sumOf { it.endTime - it.startTime }),
                    readWords = maxOf(detail.readWords, sessions.sumOf { it.words }),
                    firstReadTime = minPositive(
                        detail.firstReadTime,
                        sessions.map { it.startTime }.filter { it > 0L }.minOrNull() ?: 0L,
                    ),
                    lastReadTime = maxOf(detail.lastReadTime, sessions.maxOf { it.endTime }),
                ))
            }
        }
    }

}
