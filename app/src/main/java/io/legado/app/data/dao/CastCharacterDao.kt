package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.legado.app.data.entities.CastCharacter

@Dao
interface CastCharacterDao {

    @Query(
        """
        SELECT * FROM cast_characters
        WHERE bookUrl = :bookUrl
        ORDER BY name COLLATE NOCASE, updatedAt
        """
    )
    suspend fun getByBook(bookUrl: String): List<CastCharacter>

    @Query("SELECT * FROM cast_characters WHERE id = :id")
    suspend fun getById(id: String): CastCharacter?

    @Query("SELECT * FROM cast_characters WHERE bookUrl = :bookUrl AND name = :name")
    suspend fun getByName(bookUrl: String, name: String): List<CastCharacter>

    /** 分配表 tab：全书所有配音角色。 */
    @Query("SELECT * FROM cast_characters ORDER BY name COLLATE NOCASE")
    suspend fun getAllGlobal(): List<CastCharacter>

    /**
     * 所有设了气泡的角色的 `bubbleRuleJson`（一条只填了气泡那栏的 [HighlightRule]）。
     *
     * 同步版是给 `bg_images` 的垃圾回收用的：那些图按绝对路径存在共享目录里，
     * 只看 highlightRules 会把只有角色气泡在用的文件当成没人要而删掉，
     * 表现为「改完高亮规则，角色气泡就失效了，得重新导入图片」。
     */
    @Query("SELECT bubbleRuleJson FROM cast_characters WHERE bubbleRuleJson <> ''")
    fun getBubbleRefs(): List<String>

    /**
     * 撞唯一索引时忽略并返回 -1。
     *
     * 写入侧（`CastAssignmentStore.create` / `BookCastStore.createCharacter`）已经先按**名字**挡过一层，
     * 这里只剩兜底：两次写入撞在一起、或库里还留着折叠前的同名多行时不至于插入成功。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(character: CastCharacter): Long

    @Update
    suspend fun update(character: CastCharacter)

    /** 拖动排序落库：只动排序位，不碰名字/音色（那些是编辑面板管的）。 */
    @Query("UPDATE cast_characters SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Int)

    @Query("DELETE FROM cast_characters WHERE id = :id")
    suspend fun delete(id: String)
}
