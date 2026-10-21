package io.legado.app.data.repository

import androidx.room.withTransaction
import io.legado.app.data.AppDatabase
import io.legado.app.data.dao.ReadAloudVoiceDao
import io.legado.app.data.entities.BookVoiceBindingEntity
import io.legado.app.data.entities.ReadAloudVoiceEntity
import io.legado.app.domain.gateway.ReadAloudVoiceGateway
import io.legado.app.domain.model.readaloud.BookVoiceBinding
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ReadAloudVoiceRepository(
    private val dao: ReadAloudVoiceDao,
    private val database: AppDatabase,
) : ReadAloudVoiceGateway {

    override suspend fun <T> withTransaction(block: suspend () -> T): T = database.withTransaction(block)

    override suspend fun countExcludedVoices(): Int = dao.countExcludedVoices()

    override fun observeVoices(): Flow<List<ReadAloudVoice>> =
        dao.observeVoices().map { voices -> voices.map(ReadAloudVoiceEntity::toDomain) }

    override suspend fun getVoices(): List<ReadAloudVoice> = withContext(Dispatchers.IO) {
        dao.getVoices().map(ReadAloudVoiceEntity::toDomain)
    }

    override suspend fun getEnabledVoices(): List<ReadAloudVoice> = withContext(Dispatchers.IO) {
        dao.getEnabledVoices().map(ReadAloudVoiceEntity::toDomain)
    }

    override suspend fun getVoice(id: String): ReadAloudVoice? = withContext(Dispatchers.IO) {
        dao.getVoice(id)?.toDomain()
    }

    override suspend fun upsertVoice(voice: ReadAloudVoice) = withContext(Dispatchers.IO) {
        database.withTransaction {
            // 目录可能在删除引擎之前已生成草稿；删除之后的迟到同步不能重建孤儿。
            if (voice.engineType == ReadAloudVoice.ENGINE_HTTP &&
                voice.engineId.toLongOrNull()?.let(database.httpTTSDao::get) == null) {
                return@withTransaction
            }
            dao.upsertVoice(voice.toEntity())
        }
    }

    override suspend fun deleteVoice(voice: ReadAloudVoice) = withContext(Dispatchers.IO) {
        dao.deleteVoice(voice.toEntity())
        // 音色没了，用户给它写的分类标注也一并清掉。挂在这一层是因为删除音色的入口有五六处
        // （批量删除、删引擎、改名换行、同步清理、孤儿回收），全部走本方法，
        // 让每个调用方各自记得清反而最容易漏。
        // 表可能不存在（老库没重跑过那条建表语句）：标注清不掉只留一行孤儿，不能把删除带崩。
        runCatching { database.voiceLabelDao.delete(voice.id) }
    }

    override fun observeBindings(bookUrl: String): Flow<List<BookVoiceBinding>> =
        dao.observeBindings(bookUrl).map { bindings ->
            bindings.map(BookVoiceBindingEntity::toDomain)
        }

    override suspend fun getBindings(bookUrl: String): List<BookVoiceBinding> =
        withContext(Dispatchers.IO) {
            dao.getBindings(bookUrl).map(BookVoiceBindingEntity::toDomain)
        }

    override suspend fun getBinding(
        bookUrl: String,
        subjectType: String,
        subjectId: String,
    ): BookVoiceBinding? = withContext(Dispatchers.IO) {
        dao.getBinding(bookUrl, subjectType, subjectId)?.toDomain()
    }

    override suspend fun upsertBinding(binding: BookVoiceBinding) = withContext(Dispatchers.IO) {
        dao.upsertBinding(binding.toEntity())
    }

    override suspend fun deleteBinding(binding: BookVoiceBinding) = withContext(Dispatchers.IO) {
        dao.deleteBinding(binding.toEntity())
    }
}

internal fun ReadAloudVoiceEntity.toDomain() = ReadAloudVoice(
    id = id,
    engineType = engineType,
    engineId = engineId,
    speakerId = speakerId,
    displayName = displayName,
    traitsJson = traitsJson,
    emotionCatalogJson = emotionCatalogJson,
    managedBy = managedBy,
    enabled = enabled,
    available = available,
    excluded = excluded,
    revision = revision,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun ReadAloudVoice.toEntity() = ReadAloudVoiceEntity(
    id = id,
    engineType = engineType,
    engineId = engineId,
    speakerId = speakerId,
    displayName = displayName,
    traitsJson = traitsJson,
    emotionCatalogJson = emotionCatalogJson,
    managedBy = managedBy,
    enabled = enabled,
    available = available,
    excluded = excluded,
    revision = revision,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun BookVoiceBindingEntity.toDomain() = BookVoiceBinding(
    bookUrl = bookUrl,
    subjectType = subjectType,
    subjectId = subjectId,
    voiceId = voiceId,
    locked = locked,
    source = source,
    confidence = confidence,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun BookVoiceBinding.toEntity() = BookVoiceBindingEntity(
    bookUrl = bookUrl,
    subjectType = subjectType,
    subjectId = subjectId,
    voiceId = voiceId,
    locked = locked,
    source = source,
    confidence = confidence,
    createdAt = createdAt,
    updatedAt = updatedAt,
)
