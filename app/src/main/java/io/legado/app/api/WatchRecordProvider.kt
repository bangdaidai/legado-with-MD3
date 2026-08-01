package io.legado.app.api

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import io.legado.app.data.appDb


/**
 * 对外暴露观看记录查询接口，供第三方 TV App 读取本 App 的阅读/观看记录。
 * Authority: ${applicationId}.watchRecordProvider
 */
class WatchRecordProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("bookName", "readTime", "lastRead"))
        val records = appDb.readRecordDao.all
        records.forEach { r ->
            cursor.addRow(arrayOf(r.bookName, r.readTime, r.lastRead))
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.legado.watchrecord"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
