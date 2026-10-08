package io.legado.app.domain.usecase

import io.legado.app.constant.BookType
import io.legado.app.data.entities.ShelfBookSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书架同名检测的分组口径。
 *
 * 这组用例锁住的是"三处判重不能互相矛盾"这条不变量：扫描必须与加入书架查重
 * （[io.legado.app.domain.model.BookMatchKey]）以及阅读记录的副本判定同口径。
 */
class FindShelfDuplicatesUseCaseTest {

    @Test
    fun `same name and author from two sources forms one group`() {
        val result = groupShelfDuplicates(
            listOf(
                book("u1", "三体", "刘慈欣", BookType.text),
                book("u2", "三体", "刘慈欣", BookType.text),
                book("u3", "活着", "余华", BookType.text),
            )
        )

        val group = result.groups.single()
        assertEquals("三体", group.name)
        assertEquals(2, group.copyCount)
        // 唯一无关的那本不进任何组，但仍计入扫描总数
        assertEquals(3, result.scannedBookCount)
        assertEquals(2, result.duplicateCopyCount)
    }

    @Test
    fun `padding around the title is folded away`() {
        val result = groupShelfDuplicates(
            listOf(
                book("u1", "  三体  ", "刘慈欣"),
                book("u2", "三体", "刘慈欣"),
            )
        )

        assertEquals(1, result.duplicateGroupCount)
        assertEquals(2, result.groups.single().copyCount)
    }

    @Test
    fun `fullwidth latin in the title folds to halfwidth`() {
        val result = groupShelfDuplicates(
            listOf(
                book("u1", "Restart(Vol.1)", "甲"),
                book("u2", "Restart（Ｖｏｌ．1）", "甲"),
            )
        )

        assertEquals(1, result.duplicateGroupCount)
        assertEquals(2, result.groups.single().copyCount)
    }

    @Test
    fun `interior spacing is collapsed but not removed`() {
        // 折叠的是连续空白，首尾空白才去掉；"三 体" 与 "三体" 是两本不同的书，不能合并
        val result = groupShelfDuplicates(
            listOf(
                book("u1", "三   体", "刘慈欣"),
                book("u2", "三 体", "刘慈欣"),
                book("u3", "三体", "刘慈欣"),
            )
        )

        val group = result.groups.single()
        assertEquals(listOf("u1", "u2"), group.copies.map { it.bookUrl })
        // 展示名取副本里的原始书名，不是规范化后的键——键里没有用户真正见过的那本书
        assertEquals("三   体", group.name)
    }

    @Test
    fun `same title by different authors is not a duplicate`() {
        val result = groupShelfDuplicates(
            listOf(
                book("u1", "三国演义", "罗贯中"),
                book("u2", "三国演义", "曹雪芹"),
            )
        )

        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun `different form types of the same title are not duplicates`() {
        val result = groupShelfDuplicates(
            listOf(
                book("u1", "深夜食堂", "村井春哉", BookType.text),
                book("u2", "深夜食堂", "村井春哉", BookType.audio),
            )
        )

        assertTrue(result.groups.isEmpty())
    }

    @Test
    fun `blank author joins the single known author of the same title`() {
        val result = groupShelfDuplicates(
            listOf(
                book("u1", "诡秘之主", "爱潜水的乌贼"),
                book("u2", "诡秘之主", ""),
            )
        )

        assertEquals(1, result.duplicateGroupCount)
        assertEquals(2, result.groups.single().copyCount)
    }

    @Test
    fun `blank author is left unattributed when several authors share the title`() {
        val result = groupShelfDuplicates(
            listOf(
                book("u1", "山海经", "佚名"),
                book("u2", "山海经", "佚名"),
                book("u3", "山海经", "无名氏"),
                book("u4", "山海经", ""),
            )
        )

        // "佚名"两本是确定的重复；"无名氏"只有一本不成组；
        // 空作者那本归属不明，不并进任何一组
        val group = result.groups.single()
        assertEquals("佚名", group.author)
        assertEquals(2, group.copyCount)
    }

    @Test
    fun `blank title is skipped instead of collapsing unrelated books`() {
        val result = groupShelfDuplicates(
            listOf(book("u1", "", "A"), book("u2", "", "B"))
        )

        assertTrue(result.groups.isEmpty())
        assertEquals(2, result.scannedBookCount)
    }

    @Test
    fun `most recently read copy is listed first`() {
        val result = groupShelfDuplicates(
            listOf(
                book("u1", "红楼梦", "曹雪芹", durChapterTime = 100L),
                book("u2", "红楼梦", "曹雪芹", durChapterTime = 900L),
            )
        )

        assertEquals(
            listOf("u2", "u1"),
            result.groups.single().copies.map { it.bookUrl },
        )
    }

    @Test
    fun `local copies report their local flag and custom cover wins`() {
        val result = groupShelfDuplicates(
            listOf(
                book("u1", "呐喊", "鲁迅", type = BookType.text or BookType.local, cover = "c1", customCover = "c2"),
                book("u2", "呐喊", "鲁迅"),
            )
        )

        val copy = result.groups.single().copies.first { it.bookUrl == "u1" }
        assertTrue(copy.isLocal)
        assertEquals("c2", copy.coverUrl)
    }

    @Test
    fun `deleting a copy updates groups and stats locally`() {
        val result = groupShelfDuplicates(
            listOf(
                book("u1", "三体", "刘慈欣"),
                book("u2", "三体", "刘慈欣"),
                book("u3", "三体", "刘慈欣"),
            )
        )

        val after = result.withoutCopy("u1")
        assertEquals(listOf("u2", "u3"), after.groups.single().copies.map { it.bookUrl })
        assertEquals(2, after.duplicateGroupCount)
        assertEquals(2, after.duplicateCopyCount)
    }

    @Test
    fun `group dissolves when fewer than two copies remain`() {
        val result = groupShelfDuplicates(
            listOf(
                book("u1", "三体", "刘慈欣"),
                book("u2", "三体", "刘慈欣"),
            )
        )

        val after = result.withoutCopy("u1")
        assertTrue(after.groups.isEmpty())
        assertEquals(0, after.duplicateGroupCount)
        assertEquals(0, after.duplicateCopyCount)
    }

    @Test
    fun `deleting an unknown copy keeps the result untouched`() {
        val result = groupShelfDuplicates(
            listOf(
                book("u1", "三体", "刘慈欣"),
                book("u2", "三体", "刘慈欣"),
            )
        )

        val after = result.withoutCopy("missing")
        assertEquals(1, after.duplicateGroupCount)
        assertEquals(2, after.groups.single().copyCount)
    }

    private fun book(
        bookUrl: String,
        name: String,
        author: String,
        type: Int = BookType.text,
        durChapterTime: Long = 0L,
        cover: String? = null,
        customCover: String? = null,
    ) = ShelfBookSummary(
        bookUrl = bookUrl,
        name = name,
        author = author,
        origin = "https://example.com/$bookUrl",
        originName = "source-$bookUrl",
        coverUrl = cover,
        customCoverUrl = customCover,
        totalChapterNum = 100,
        durChapterIndex = 10,
        durChapterTime = durChapterTime,
        type = type,
    )
}