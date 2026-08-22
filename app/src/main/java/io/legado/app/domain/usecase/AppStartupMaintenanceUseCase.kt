package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.AppStartupGateway

class AppStartupMaintenanceUseCase(
    private val appStartupGateway: AppStartupGateway
) {

    suspend fun deleteNotShelfBooks() {
        appStartupGateway.deleteNotShelfBooks()
        // 书行删掉之后才知道哪些分镜数据成了孤儿，顺序不能反
        appStartupGateway.deleteOrphanChapterSpeech()
    }
}
