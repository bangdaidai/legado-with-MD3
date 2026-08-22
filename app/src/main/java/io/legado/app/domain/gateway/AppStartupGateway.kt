package io.legado.app.domain.gateway

interface AppStartupGateway {
    suspend fun deleteNotShelfBooks()

    /** 清掉已经不存在的书留下的分镜数据，必须在 [deleteNotShelfBooks] 之后跑 */
    suspend fun deleteOrphanChapterSpeech()
}
