package io.legado.app.model.jsSource

import io.legado.app.data.entities.BookSource

/**
 * 单文件 JS 书源与声明式 JSON 书源共用 [BookSource] 实体。
 * 用扩展函数判别，避免给 Rhino 可达的宿主对象新增公开实例方法。
 */
fun BookSource.isJsSource(): Boolean = !mainJs.isNullOrBlank()
