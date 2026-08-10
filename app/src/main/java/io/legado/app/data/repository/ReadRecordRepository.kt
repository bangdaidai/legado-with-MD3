package io.legado.app.data.repository

import androidx.room.withTransaction
import cn.hutool.core.date.DatePattern
import cn.hutool.core.date.DateUtil
import io.legado.app.data.AppDatabase
import io.legado.app.data.dao.ReadRecordDao
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.data.entities.readRecord.ReadRecordTimelineDay
import io.legado.app.data.local.preferences.LocalPreferencesKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Date
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
     * 根据搜索关键字获取最新的阅读书籍列表流
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
    }

    /**
     * 获取所有的每日统计详情流
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
    }

    fun getAllSessions(bookType: Int? = null): Flow<List<ReadRecordSession>> {
        return if (bookType != null) {
            dao.getAllSessionsByType(getCurrentDeviceId(), bookType)
        } else {
            dao.getAllSessions(getCurrentDeviceId())
        }
    }

    fun getBookSessions(bookName: String, bookAuthor: String): Flow<List<ReadRecordSession>> {
        return dao.getSessionsByBookFlow(getCurrentDeviceId(), bookName, bookAuthor)
    }

    fun getBookTimelineDays(bookName: String, bookAuthor: String): Flow<List<ReadRecordTimelineDay>> {
        return getBookSessions(bookName, bookAuthor).map { sessions ->
            sessions.groupBy { DateUtil.format(Date(it.startTime), "yyyy-MM-dd") }
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
        return dao.getReadTimeFlow(getCurrentDeviceId(), bookName, bookAuthor).map { it ?: 0L }
    }

    suspend fun getMergeCandidates(targetRecord: ReadRecord): List<ReadRecord> {
        return dao.getMergeCandidates(
            targetRecord.deviceId,
            targetRecord.bookName,
            targetRecord.bookAuthor
        )
    }

    /**
     * 保存一个完整的阅读会话.
     * auto-save 每 120 秒提交一个片段, 若该书最后一条会话与本片段间隔不超过 [SESSION_MERGE_GAP],
     * 直接合并延长该会话, 避免连续阅读在 DB 里积累大量 2 分钟碎片.
     */
    suspend fun saveReadSession(newSession: ReadRecordSession) {
        if (!readRecordEnabled.first()) return
        if (newSession.endTime <= newSession.startTime) return
        database.withTransaction {
            val existingSession = dao.getSession(
                newSession.deviceId,
                newSession.bookName,
                newSession.bookAuthor,
                newSession.startTime,
                newSession.endTime
            )
            if (existingSession != null) return@withTransaction

            val segmentDuration = newSession.endTime - newSession.startTime
            val lastSession = dao.getLatestSessionByBook(
                newSession.bookName,
                newSession.bookAuthor
            )
            if (lastSession != null &&
                lastSession.deviceId == newSession.deviceId &&
                newSession.startTime - lastSession.endTime <= SESSION_MERGE_GAP
            ) {
                dao.updateSession(
                    lastSession.copy(
                        endTime = max(lastSession.endTime, newSession.endTime),
                        words = lastSession.words + newSession.words,
                        chapterTitle = newSession.chapterTitle.ifBlank { lastSession.chapterTitle }
                    )
                )
            } else {
                dao.insertSession(newSession)
            }
            val dateString =
                DateUtil.format(Date(newSession.startTime), DatePattern.NORM_DATE_PATTERN)
            updateReadRecordDetail(newSession, segmentDuration, newSession.words, dateString)
            updateReadRecord(newSession, segmentDuration)
        }
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
            existingDetail.firstReadTime = min(existingDetail.firstReadTime, session.startTime)
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
            dao.deleteDetail(detail)
            dao.deleteSessionsByBookAndDate(
                detail.deviceId,
                detail.bookName,
                detail.bookAuthor,
                detail.date
            )
            updateReadRecordTotal(detail.deviceId, detail.bookName, detail.bookAuthor)
        }
    }

    /**
     * 删除一个会话。传入的 session 可能是展示层合并后的聚合段，
     * 其 id 只指向首段，因此按 [startTime, endTime] 区间删掉所有底层成员。
     */
    suspend fun deleteSession(session: ReadRecordSession) {
        database.withTransaction {
            dao.deleteSessionsByBookAndRange(
                session.deviceId,
                session.bookName,
                session.bookAuthor,
                session.startTime,
                session.endTime
            )

            val dateString = DateUtil.format(Date(session.startTime), "yyyy-MM-dd")
            val remainingSessions =
                dao.getSessionsByBookAndDate(
                    session.deviceId,
                    session.bookName,
                    session.bookAuthor,
                    dateString
                )

            if (remainingSessions.isEmpty()) {
                val detail = dao.getDetail(
                    session.deviceId,
                    session.bookName,
                    session.bookAuthor,
                    dateString
                )
                detail?.let { dao.deleteDetail(it) }
            } else {
                val totalTime = remainingSessions.sumOf { it.endTime - it.startTime }
                val totalWords = remainingSessions.sumOf { it.words }
                val firstRead = remainingSessions.minOf { it.startTime }
                val lastRead = remainingSessions.maxOf { it.endTime }

                val existingDetail = dao.getDetail(
                    session.deviceId,
                    session.bookName,
                    session.bookAuthor,
                    dateString
                )
                dao.insertDetail(
                    existingDetail?.copy(
                        readTime = totalTime,
                        readWords = totalWords,
                        firstReadTime = firstRead,
                        lastReadTime = lastRead
                    ) ?: ReadRecordDetail(
                        deviceId = session.deviceId,
                        bookName = session.bookName,
                        bookAuthor = session.bookAuthor,
                        date = dateString,
                        readTime = totalTime,
                        readWords = totalWords,
                        firstReadTime = firstRead,
                        lastReadTime = lastRead,
                        bookType = session.bookType
                    )
                )
            }

            updateReadRecordTotal(session.deviceId, session.bookName, session.bookAuthor)
        }
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
            dao.deleteReadRecord(record)
            dao.deleteDetailsByBook(record.deviceId, record.bookName, record.bookAuthor)
            dao.deleteSessionsByBook(record.deviceId, record.bookName, record.bookAuthor)
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
                        firstReadTime = min(existingTargetDetail.firstReadTime, detail.firstReadTime),
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
    }

}
