package io.legado.app.data.entities.rule

import android.os.Parcelable
import com.google.gson.JsonDeserializer
import io.legado.app.utils.INITIAL_GSON
import kotlinx.parcelize.Parcelize

/**
 * 书籍详情页规则
 */
@Parcelize
data class BookInfoRule(
    var init: String? = null,
    var name: String? = null,
    var author: String? = null,
    var intro: String? = null,
    var kind: String? = null,
    var lastChapter: String? = null,
    var updateTime: String? = null,
    var coverUrl: String? = null,
    var tocUrl: String? = null,
    var wordCount: String? = null,
    var canReName: String? = null,
    var downloadUrls: String? = null,
    var relatedBooks: String? = null
) : Parcelable {

    /**
     * 起点助手等书源用 JSON.stringify(source.ruleBookInfo) 的结果做指纹校验，
     * 而 Rhino 对 Java 对象取的是 toString()。relatedBooks 是本 fork 新增字段，
     * 若出现在 toString 里会让指纹与上游不一致，导致书源报"书源验证失败"。
     * 因此手写 toString 排除该字段，字段本身与关联书籍推荐功能不受影响。
     */
    override fun toString(): String =
        "BookInfoRule(init=$init, name=$name, author=$author, intro=$intro, " +
            "kind=$kind, lastChapter=$lastChapter, updateTime=$updateTime, " +
            "coverUrl=$coverUrl, tocUrl=$tocUrl, wordCount=$wordCount, " +
            "canReName=$canReName, downloadUrls=$downloadUrls)"

    companion object {

        val jsonDeserializer = JsonDeserializer<BookInfoRule?> { json, _, _ ->
            when {
                json.isJsonObject -> INITIAL_GSON.fromJson(json, BookInfoRule::class.java)
                json.isJsonPrimitive -> INITIAL_GSON.fromJson(
                    json.asString,
                    BookInfoRule::class.java
                )
                else -> null
            }
        }

    }

}