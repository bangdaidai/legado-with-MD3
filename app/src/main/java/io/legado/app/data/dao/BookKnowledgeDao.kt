package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.legado.app.data.entities.BookCharacterEvent
import io.legado.app.data.entities.BookCharacterProfile
import io.legado.app.data.entities.BookCharacterRelation
import io.legado.app.data.entities.BookKnowledgeEntry
import io.legado.app.data.entities.BookOutlineNode

@Dao
interface BookKnowledgeDao {

    @Query(
        """
        select * from book_character_profiles
        where bookUrl = :bookUrl
          and status != ${BookCharacterProfile.STATUS_DELETED}
          and (:query = '' or name like '%' || :query || '%' or aliasesJson like '%' || :query || '%' or summary like '%' || :query || '%')
        order by updatedAt desc
        limit :limit
        """
    )
    suspend fun searchCharacterProfiles(
        bookUrl: String,
        query: String,
        limit: Int,
    ): List<BookCharacterProfile>

    /**
     * [includeDrafts] 默认不含草稿卡：草稿是 AI 分镜时认出的临时说话人，只有配音页需要看到，
     * 人物列表 / 关系图 / 事件选人都只认转正后的正式角色。
     * 排序把草稿压到最后，避免草稿把正式角色挤出 limit。
     */
    @Query(
        """
        select * from book_character_profiles
        where bookUrl = :bookUrl
          and status != ${BookCharacterProfile.STATUS_DELETED}
          and (:includeDrafts or status != ${BookCharacterProfile.STATUS_DRAFT})
        order by status = ${BookCharacterProfile.STATUS_DRAFT}, updatedAt desc
        limit :limit
        """
    )
    suspend fun getCharacterProfiles(
        bookUrl: String,
        limit: Int,
        includeDrafts: Boolean = false,
    ): List<BookCharacterProfile>

    @Query(
        """
        select * from book_character_profiles
        where bookUrl = :bookUrl
          and status != ${BookCharacterProfile.STATUS_DELETED}
          and (id = :idOrName or name = :idOrName or aliasesJson like '%' || :idOrName || '%')
        order by updatedAt desc
        limit 1
        """
    )
    suspend fun getCharacterProfile(bookUrl: String, idOrName: String): BookCharacterProfile?

    @Query(
        """
        select * from book_character_events
        where bookUrl = :bookUrl
          and (:characterId is null or characterId = :characterId)
          and (:maxChapterIndex is null or chapterIndex is null or chapterIndex <= :maxChapterIndex)
        order by coalesce(chapterIndex, 2147483647), importance desc, updatedAt desc
        limit :limit
        """
    )
    suspend fun getCharacterEvents(
        bookUrl: String,
        characterId: String?,
        maxChapterIndex: Int?,
        limit: Int,
    ): List<BookCharacterEvent>

    @Query(
        """
        select * from book_character_relations
        where bookUrl = :bookUrl
          and status != ${BookCharacterProfile.STATUS_DELETED}
          and (fromCharacterId = :characterId or toCharacterId = :characterId)
        order by updatedAt desc
        limit :limit
        """
    )
    suspend fun getCharacterRelations(
        bookUrl: String,
        characterId: String,
        limit: Int,
    ): List<BookCharacterRelation>

    @Query(
        """
        select * from book_character_relations
        where bookUrl = :bookUrl
          and status != ${BookCharacterProfile.STATUS_DELETED}
        order by updatedAt desc
        limit :limit
        """
    )
    suspend fun getBookCharacterRelations(
        bookUrl: String,
        limit: Int,
    ): List<BookCharacterRelation>

    @Query(
        """
        select * from book_knowledge_entries
        where bookUrl = :bookUrl
          and (:type is null or type = :type)
          and (:chapterIndex is null
               or (scopeStartChapter is null or scopeStartChapter <= :chapterIndex)
               and (scopeEndChapter is null or scopeEndChapter >= :chapterIndex))
          and (:query = '' or title like '%' || :query || '%' or keywordsJson like '%' || :query || '%' or content like '%' || :query || '%')
        order by priority desc, updatedAt desc
        limit :limit
        """
    )
    suspend fun searchKnowledgeEntries(
        bookUrl: String,
        query: String,
        type: String?,
        chapterIndex: Int?,
        limit: Int,
    ): List<BookKnowledgeEntry>

    @Query(
        """
        select * from book_outline_nodes
        where bookUrl = :bookUrl
          and (:nodeType is null or nodeType = :nodeType)
          and (:chapterIndex is null
               or (startChapterIndex is null or startChapterIndex <= :chapterIndex)
               and (endChapterIndex is null or endChapterIndex >= :chapterIndex))
        order by coalesce(startChapterIndex, -1), `order`, updatedAt desc
        limit :limit
        """
    )
    suspend fun getOutlineNodes(
        bookUrl: String,
        chapterIndex: Int?,
        nodeType: String?,
        limit: Int,
    ): List<BookOutlineNode>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCharacterProfile(profile: BookCharacterProfile)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCharacterEvent(event: BookCharacterEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCharacterRelation(relation: BookCharacterRelation)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKnowledgeEntry(entry: BookKnowledgeEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOutlineNode(node: BookOutlineNode)

    @Query("update book_character_profiles set status = ${BookCharacterProfile.STATUS_DELETED}, updatedAt = :now where bookUrl = :bookUrl and id = :characterId")
    suspend fun deleteCharacterProfile(bookUrl: String, characterId: String, now: Long = System.currentTimeMillis())

    @Query("update book_character_relations set status = ${BookCharacterProfile.STATUS_DELETED} where fromCharacterId = :characterId or toCharacterId = :characterId")
    suspend fun deleteRelationsForCharacter(characterId: String)

    @Query("delete from book_character_events where bookUrl = :bookUrl and characterId = :characterId")
    suspend fun deleteEventsForCharacter(bookUrl: String, characterId: String)

    @Query("delete from book_character_relations where id = :relationId")
    suspend fun deleteCharacterRelation(relationId: String)

    /**
     * 真删某个角色的全部关系行，和 [deleteRelationsForCharacter] 的软删除区分开：
     * 后者是用户主动删角色，要留 status=DELETED 的历史；换源去重丢弃目标侧残留时角色本身都要删掉，
     * 留软删行只会变成指向已删角色的悬空引用。
     */
    @Query("delete from book_character_relations where fromCharacterId = :characterId or toCharacterId = :characterId")
    suspend fun hardDeleteRelationsOfCharacter(characterId: String)

    @Query("delete from book_knowledge_entries where id = :entryId")
    suspend fun deleteKnowledgeEntry(entryId: String)

    // region 主角相关（统一数据源 = book_character_profiles.isProtagonist）

    /** 获取指定书籍的全部主角 */
    @Query(
        """
        select * from book_character_profiles
        where bookUrl = :bookUrl
          and isProtagonist = 1
          and status != ${BookCharacterProfile.STATUS_DELETED}
        order by updatedAt desc
        """
    )
    suspend fun getProtagonists(bookUrl: String): List<BookCharacterProfile>

    /**
     * 按角色分类获取人物，不要求是主角。
     * 高亮规则指定「角色筛选」时用这个：配角按定义不是主角，若再 AND isProtagonist 会永远查不到。
     */
    @Query(
        """
        select * from book_character_profiles
        where bookUrl = :bookUrl
          and role = :role
          and status != ${BookCharacterProfile.STATUS_DELETED}
        order by updatedAt desc
        """
    )
    suspend fun getCharactersByRole(bookUrl: String, role: String): List<BookCharacterProfile>

    /** 备份用：全量人物档案（含已删除，保证恢复后状态一致） */
    @Query("select * from book_character_profiles")
    fun getAllCharacterProfilesSync(): List<BookCharacterProfile>

    /** 恢复用：批量写入人物档案 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCharacterProfiles(profiles: List<BookCharacterProfile>)

    /** 备份用：全量人物事件 */
    @Query("select * from book_character_events")
    fun getAllCharacterEventsSync(): List<BookCharacterEvent>

    /** 恢复用：批量写入人物事件 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCharacterEvents(events: List<BookCharacterEvent>)

    /** 备份用：全量人物关系（含已删除） */
    @Query("select * from book_character_relations")
    fun getAllCharacterRelationsSync(): List<BookCharacterRelation>

    /** 恢复用：批量写入人物关系 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCharacterRelations(relations: List<BookCharacterRelation>)

    /** 备份用：全量知识条目 */
    @Query("select * from book_knowledge_entries")
    fun getAllKnowledgeEntriesSync(): List<BookKnowledgeEntry>

    /** 恢复用：批量写入知识条目 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertKnowledgeEntries(entries: List<BookKnowledgeEntry>)

    /** 备份用：全量大纲节点 */
    @Query("select * from book_outline_nodes")
    fun getAllOutlineNodesSync(): List<BookOutlineNode>

    /** 恢复用：批量写入大纲节点 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOutlineNodes(nodes: List<BookOutlineNode>)

    /** 按书+名查主角（用于去重检查） */
    @Query(
        """
        select * from book_character_profiles
        where bookUrl = :bookUrl
          and name = :name
          and status != ${BookCharacterProfile.STATUS_DELETED}
        limit 1
        """
    )
    suspend fun getProtagonistByName(bookUrl: String, name: String): BookCharacterProfile?

    /** 按书+名查角色（包含已删除，用于自动提取去重） */
    @Query(
        """
        select * from book_character_profiles
        where bookUrl = :bookUrl
          and name = :name
        limit 1
        """
    )
    suspend fun getCharacterByNameIncludeDeleted(bookUrl: String, name: String): BookCharacterProfile?

    /** 设置/取消主角标志 */
    @Query("UPDATE book_character_profiles SET isProtagonist = :isProtagonist, updatedAt = :updatedAt WHERE bookUrl = :bookUrl AND name = :name")
    suspend fun setProtagonist(
        bookUrl: String,
        name: String,
        isProtagonist: Boolean,
        updatedAt: Long = System.currentTimeMillis(),
    )

    /** 换源迁移：将旧 bookUrl 的角色、事件、关系迁移到新 bookUrl */
    @Query("UPDATE book_character_profiles SET bookUrl = :newBookUrl WHERE bookUrl = :oldBookUrl")
    suspend fun migrateCharacterProfilesToNewBookUrl(oldBookUrl: String, newBookUrl: String)

    /**
     * 目标 bookUrl 下与旧 bookUrl 同名的角色 id。这些行挡住了 [migrateCharacterProfilesToNewBookUrl]：
     * 角色名是自然键，同一本书在两个源下各自识别过人物就一定重名（历史孤儿行、备份恢复全量写回、
     * 来回换源都会留下这种数据）。
     */
    @Query(
        """
        select newProfile.id from book_character_profiles newProfile
        where newProfile.bookUrl = :newBookUrl
          and exists (
            select 1 from book_character_profiles oldProfile
            where oldProfile.bookUrl = :oldBookUrl and oldProfile.name = newProfile.name
          )
        """
    )
    suspend fun getDuplicatedCharacterIds(oldBookUrl: String, newBookUrl: String): List<String>

    @Query("delete from book_character_profiles where id in (:ids)")
    suspend fun deleteCharacterProfilesByIds(ids: List<String>)

    /**
     * 换源迁移：把旧 bookUrl 的角色、事件、关系并到新 bookUrl。
     *
     * 不要退回成裸 UPDATE 依次调三个 migrate*：[book_character_profiles] 上有
     * `unique(bookUrl, name)`，目标 bookUrl 一旦已有同名角色就会抛 2067；而换源把这一步放在
     * 同一个事务里（见 ChangeBookSourceUseCase），一炸整单回滚，用户看到的就是「换源失败」，
     * 而且旧角色还留在旧 bookUrl 上，再点多少次都是同样的结果。
     *
     * 关系表不用额外去重：它的唯一键 `unique(bookUrl, fromCharacterId, toCharacterId)` 里两个
     * characterId 都全局唯一，同一对端点全局只可能存在一行，改 bookUrl 不会撞。
     *
     * 取舍是保留旧书的角色数据（换源调用处的意图），所以冲突时让目标侧的重复行让位，并连同它的
     * events / relations 一起删掉——那些行本就是「书已经不在这个 bookUrl」状态下的残留，删了不会
     * 留下指向已删角色的悬空引用。
     */
    @Transaction
    suspend fun mergeToNewBookUrl(oldBookUrl: String, newBookUrl: String) {
        // 两个 url 相同时下面的去重会把要搬的数据自己删光，必须先挡住
        if (oldBookUrl == newBookUrl) return
        val duplicatedIds = getDuplicatedCharacterIds(oldBookUrl, newBookUrl)
        duplicatedIds.forEach { characterId ->
            deleteEventsForCharacter(newBookUrl, characterId)
            hardDeleteRelationsOfCharacter(characterId)
        }
        if (duplicatedIds.isNotEmpty()) {
            deleteCharacterProfilesByIds(duplicatedIds)
        }
        migrateCharacterProfilesToNewBookUrl(oldBookUrl, newBookUrl)
        migrateCharacterEventsToNewBookUrl(oldBookUrl, newBookUrl)
        migrateCharacterRelationsToNewBookUrl(oldBookUrl, newBookUrl)
    }

    @Query("UPDATE book_character_events SET bookUrl = :newBookUrl WHERE bookUrl = :oldBookUrl")
    suspend fun migrateCharacterEventsToNewBookUrl(oldBookUrl: String, newBookUrl: String)

    @Query("UPDATE book_character_relations SET bookUrl = :newBookUrl WHERE bookUrl = :oldBookUrl")
    suspend fun migrateCharacterRelationsToNewBookUrl(oldBookUrl: String, newBookUrl: String)

    // endregion
}
