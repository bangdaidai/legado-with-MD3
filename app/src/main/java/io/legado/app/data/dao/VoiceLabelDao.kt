package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import io.legado.app.data.entities.VoiceLabel

@Dao
interface VoiceLabelDao {

    @Query("SELECT * FROM voice_labels WHERE voiceId = :voiceId")
    suspend fun get(voiceId: String): VoiceLabel?

    /** 全表：音色列表要按标注给每一行挂标签，逐行查不如一次取完。 */
    @Query("SELECT * FROM voice_labels")
    suspend fun getAll(): List<VoiceLabel>

    @Upsert
    suspend fun upsert(label: VoiceLabel)

    /** 备份/恢复用：主键 voiceId，@Upsert 幂等覆盖。 */
    @Upsert
    suspend fun insertAll(labels: List<VoiceLabel>)

    /** 取消标注 = 删行（读取侧退回池分类与引擎声明，不留一条"空标注"占位）；音色被删掉时也走这里随行清理。 */
    @Query("DELETE FROM voice_labels WHERE voiceId = :voiceId")
    suspend fun delete(voiceId: String)
}
