package io.legado.app.data

import androidx.room.DeleteColumn
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.legado.app.constant.AppConst
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType

object DatabaseMigrations {

    val migrations: Array<Migration> by lazy {
        arrayOf(
            migration_10_11, migration_11_12, migration_12_13, migration_13_14,
            migration_14_15, migration_15_17, migration_17_18, migration_18_19,
            migration_19_20, migration_20_21, migration_21_22, migration_22_23,
            migration_23_24, migration_24_25, migration_25_26, migration_26_27,
            migration_27_28, migration_28_29, migration_29_30, migration_30_31,
            migration_31_32, migration_32_33, migration_33_34, migration_34_35,
            migration_35_36, migration_36_37, migration_37_38, migration_38_39,
            migration_39_40, migration_40_41, migration_41_42, migration_42_43,
            migration_82_83,
            migration_98_99,
            migration_99_100,
            migration_100_101,
            migration_102_103,
        )
    }

    private val migration_10_11 = object : Migration(10, 11) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("DROP TABLE txtTocRules")
            database.execSQL(
                """CREATE TABLE txtTocRules(id INTEGER NOT NULL, 
                    name TEXT NOT NULL, rule TEXT NOT NULL, serialNumber INTEGER NOT NULL, 
                    enable INTEGER NOT NULL, PRIMARY KEY (id))"""
            )
        }
    }

    private val migration_11_12 = object : Migration(11, 12) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE rssSources ADD style TEXT ")
        }
    }

    private val migration_12_13 = object : Migration(12, 13) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE rssSources ADD articleStyle INTEGER NOT NULL DEFAULT 0 ")
        }
    }

    private val migration_13_14 = object : Migration(13, 14) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """CREATE TABLE IF NOT EXISTS `books_new` (`bookUrl` TEXT NOT NULL, `tocUrl` TEXT NOT NULL, `origin` TEXT NOT NULL,
                    `originName` TEXT NOT NULL, `name` TEXT NOT NULL, `author` TEXT NOT NULL, `kind` TEXT, `customTag` TEXT, `coverUrl` TEXT, 
                    `customCoverUrl` TEXT, `intro` TEXT, `customIntro` TEXT, `charset` TEXT, `type` INTEGER NOT NULL, `group` INTEGER NOT NULL, 
                    `latestChapterTitle` TEXT, `latestChapterTime` INTEGER NOT NULL, `lastCheckTime` INTEGER NOT NULL, `lastCheckCount` INTEGER NOT NULL, 
                    `totalChapterNum` INTEGER NOT NULL, `durChapterTitle` TEXT, `durChapterIndex` INTEGER NOT NULL, `durChapterPos` INTEGER NOT NULL, 
                    `durChapterTime` INTEGER NOT NULL, `wordCount` TEXT, `canUpdate` INTEGER NOT NULL, `order` INTEGER NOT NULL, 
                    `originOrder` INTEGER NOT NULL, `useReplaceRule` INTEGER NOT NULL, `variable` TEXT, PRIMARY KEY(`bookUrl`))"""
            )
            database.execSQL("INSERT INTO books_new select * from books ")
            database.execSQL("DROP TABLE books")
            database.execSQL("ALTER TABLE books_new RENAME TO books")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_books_name_author` ON `books` (`name`, `author`) ")
        }
    }

    private val migration_14_15 = object : Migration(14, 15) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE bookmarks ADD bookAuthor TEXT NOT NULL DEFAULT ''")
        }
    }

    private val migration_15_17 = object : Migration(15, 17) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `readRecord` (`bookName` TEXT NOT NULL, `readTime` INTEGER NOT NULL, PRIMARY KEY(`bookName`))")
        }
    }

    private val migration_17_18 = object : Migration(17, 18) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `httpTTS` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, PRIMARY KEY(`id`))")
        }
    }

    private val migration_18_19 = object : Migration(18, 19) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """CREATE TABLE IF NOT EXISTS `readRecordNew` (`androidId` TEXT NOT NULL, `bookName` TEXT NOT NULL, `readTime` INTEGER NOT NULL, 
                    PRIMARY KEY(`androidId`, `bookName`))"""
            )
            database.execSQL("INSERT INTO readRecordNew(androidId, bookName, readTime) select '${AppConst.androidId}' as androidId, bookName, readTime from readRecord")
            database.execSQL("DROP TABLE readRecord")
            database.execSQL("ALTER TABLE readRecordNew RENAME TO readRecord")
        }
    }
    private val migration_19_20 = object : Migration(19, 20) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE book_sources ADD bookSourceComment TEXT")
        }
    }

    private val migration_20_21 = object : Migration(20, 21) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE book_groups ADD show INTEGER NOT NULL DEFAULT 1")
        }
    }

    private val migration_21_22 = object : Migration(21, 22) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """CREATE TABLE IF NOT EXISTS `books_new` (`bookUrl` TEXT NOT NULL, `tocUrl` TEXT NOT NULL, `origin` TEXT NOT NULL, 
                    `originName` TEXT NOT NULL, `name` TEXT NOT NULL, `author` TEXT NOT NULL, `kind` TEXT, `customTag` TEXT, 
                    `coverUrl` TEXT, `customCoverUrl` TEXT, `intro` TEXT, `customIntro` TEXT, `charset` TEXT, `type` INTEGER NOT NULL, 
                    `group` INTEGER NOT NULL, `latestChapterTitle` TEXT, `latestChapterTime` INTEGER NOT NULL, `lastCheckTime` INTEGER NOT NULL, 
                    `lastCheckCount` INTEGER NOT NULL, `totalChapterNum` INTEGER NOT NULL, `durChapterTitle` TEXT, `durChapterIndex` INTEGER NOT NULL, 
                    `durChapterPos` INTEGER NOT NULL, `durChapterTime` INTEGER NOT NULL, `wordCount` TEXT, `canUpdate` INTEGER NOT NULL, 
                    `order` INTEGER NOT NULL, `originOrder` INTEGER NOT NULL, `variable` TEXT, `readConfig` TEXT, PRIMARY KEY(`bookUrl`))"""
            )
            database.execSQL(
                """INSERT INTO books_new select `bookUrl`, `tocUrl`, `origin`, `originName`, `name`, `author`, `kind`, `customTag`, `coverUrl`, 
                    `customCoverUrl`, `intro`, `customIntro`, `charset`, `type`, `group`, `latestChapterTitle`, `latestChapterTime`, `lastCheckTime`, 
                    `lastCheckCount`, `totalChapterNum`, `durChapterTitle`, `durChapterIndex`, `durChapterPos`, `durChapterTime`, `wordCount`, `canUpdate`, 
                    `order`, `originOrder`, `variable`, null
                    from books"""
            )
            database.execSQL("DROP TABLE books")
            database.execSQL("ALTER TABLE books_new RENAME TO books")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_books_name_author` ON `books` (`name`, `author`) ")
        }
    }

    private val migration_22_23 = object : Migration(22, 23) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE chapters ADD baseUrl TEXT NOT NULL DEFAULT ''")
        }
    }

    private val migration_23_24 = object : Migration(23, 24) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("CREATE TABLE IF NOT EXISTS `caches` (`key` TEXT NOT NULL, `value` TEXT, `deadline` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_caches_key` ON `caches` (`key`)")
        }
    }

    private val migration_24_25 = object : Migration(24, 25) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """CREATE TABLE IF NOT EXISTS `sourceSubs` 
                    (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `type` INTEGER NOT NULL, `customOrder` INTEGER NOT NULL, 
                    PRIMARY KEY(`id`))"""
            )
        }
    }

    private val migration_25_26 = object : Migration(25, 26) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """CREATE TABLE IF NOT EXISTS `ruleSubs` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `type` INTEGER NOT NULL, 
                    `customOrder` INTEGER NOT NULL, `autoUpdate` INTEGER NOT NULL, `update` INTEGER NOT NULL, PRIMARY KEY(`id`))"""
            )
            database.execSQL(" insert into `ruleSubs` select *, 0, 0 from `sourceSubs` ")
            database.execSQL("DROP TABLE `sourceSubs`")
        }
    }

    private val migration_26_27 = object : Migration(26, 27) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(" ALTER TABLE rssSources ADD singleUrl INTEGER NOT NULL DEFAULT 0 ")
            database.execSQL(
                """CREATE TABLE IF NOT EXISTS `bookmarks1` (`time` INTEGER NOT NULL, `bookUrl` TEXT NOT NULL, `bookName` TEXT NOT NULL, 
                        `bookAuthor` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, `chapterPos` INTEGER NOT NULL, `chapterName` TEXT NOT NULL, 
                        `bookText` TEXT NOT NULL, `content` TEXT NOT NULL, PRIMARY KEY(`time`))"""
            )
            database.execSQL(
                """insert into `bookmarks1` 
                        select `time`, `bookUrl`, `bookName`, `bookAuthor`, `chapterIndex`, `pageIndex`, `chapterName`, '', `content` 
                        from bookmarks"""
            )
            database.execSQL(" DROP TABLE `bookmarks` ")
            database.execSQL(" ALTER TABLE bookmarks1 RENAME TO bookmarks ")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bookmarks_time` ON `bookmarks` (`time`)")
        }
    }

    private val migration_27_28 = object : Migration(27, 28) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE rssArticles ADD variable TEXT")
            database.execSQL("ALTER TABLE rssStars ADD variable TEXT")
        }
    }

    private val migration_28_29 = object : Migration(28, 29) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE rssSources ADD sourceComment TEXT")
        }
    }

    private val migration_29_30 = object : Migration(29, 30) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE chapters ADD `startFragmentId` TEXT")
            database.execSQL("ALTER TABLE chapters ADD `endFragmentId` TEXT")
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS `epubChapters` 
                    (`bookUrl` TEXT NOT NULL, `href` TEXT NOT NULL, `parentHref` TEXT, 
                    PRIMARY KEY(`bookUrl`, `href`), FOREIGN KEY(`bookUrl`) REFERENCES `books`(`bookUrl`) ON UPDATE NO ACTION ON DELETE CASCADE )
                """
            )
            database.execSQL("CREATE INDEX IF NOT EXISTS `index_epubChapters_bookUrl` ON `epubChapters` (`bookUrl`)")
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_epubChapters_bookUrl_href` ON `epubChapters` (`bookUrl`, `href`)")
        }
    }

    private val migration_30_31 = object : Migration(30, 31) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE readRecord RENAME TO readRecord1")
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS `readRecord` (`deviceId` TEXT NOT NULL, `bookName` TEXT NOT NULL, `readTime` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `bookName`))
                """
            )
            database.execSQL("insert into readRecord (deviceId, bookName, readTime) select androidId, bookName, readTime from readRecord1")
        }
    }

    private val migration_31_32 = object : Migration(31, 32) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("DROP TABLE `epubChapters`")
        }
    }

    private val migration_32_33 = object : Migration(32, 33) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE bookmarks RENAME TO bookmarks_old")
            database.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS `bookmarks` (`time` INTEGER NOT NULL,
                    `bookName` TEXT NOT NULL, `bookAuthor` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, 
                    `chapterPos` INTEGER NOT NULL, `chapterName` TEXT NOT NULL, `bookText` TEXT NOT NULL, 
                    `content` TEXT NOT NULL, PRIMARY KEY(`time`))
                """
            )
            database.execSQL(
                """
                    CREATE INDEX IF NOT EXISTS `index_bookmarks_bookName_bookAuthor` ON `bookmarks` (`bookName`, `bookAuthor`)
                """
            )
            database.execSQL(
                """
                    insert into bookmarks (time, bookName, bookAuthor, chapterIndex, chapterPos, chapterName, bookText, content)
                    select time, ifNull(b.name, bookName) bookName, ifNull(b.author, bookAuthor) bookAuthor, 
                    chapterIndex, chapterPos, chapterName, bookText, content from bookmarks_old o
                    left join books b on o.bookUrl = b.bookUrl
                """
            )
        }
    }

    private val migration_33_34 = object : Migration(33, 34) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `book_groups` ADD `cover` TEXT")
        }
    }

    private val migration_34_35 = object : Migration(34, 35) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `book_sources` ADD `concurrentRate` TEXT")
        }
    }

    private val migration_35_36 = object : Migration(35, 36) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `book_sources` ADD `loginUi` TEXT")
            database.execSQL("ALTER TABLE `book_sources` ADD`loginCheckJs` TEXT")
        }
    }

    private val migration_36_37 = object : Migration(36, 37) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `rssSources` ADD `loginUrl` TEXT")
            database.execSQL("ALTER TABLE `rssSources` ADD `loginUi` TEXT")
            database.execSQL("ALTER TABLE `rssSources` ADD `loginCheckJs` TEXT")
        }
    }

    private val migration_37_38 = object : Migration(37, 38) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `book_sources` ADD `respondTime` INTEGER NOT NULL DEFAULT 180000")
        }
    }

    private val migration_38_39 = object : Migration(38, 39) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `rssSources` ADD `concurrentRate` TEXT")
        }
    }

    private val migration_39_40 = object : Migration(39, 40) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `chapters` ADD `isVip` INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE `chapters` ADD `isPay` INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val migration_40_41 = object : Migration(40, 41) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `httpTTS` ADD `loginUrl` TEXT")
            database.execSQL("ALTER TABLE `httpTTS` ADD `loginUi` TEXT")
            database.execSQL("ALTER TABLE `httpTTS` ADD `loginCheckJs` TEXT")
            database.execSQL("ALTER TABLE `httpTTS` ADD `header` TEXT")
            database.execSQL("ALTER TABLE `httpTTS` ADD `concurrentRate` TEXT")
        }
    }

    private val migration_41_42 = object : Migration(41, 42) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE 'httpTTS' ADD `contentType` TEXT")
        }
    }

    private val migration_42_43 = object : Migration(42, 43) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE `chapters` ADD `isVolume` INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val migration_82_83 = object : Migration(82, 83) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("ALTER TABLE readRecord RENAME TO readRecord_old")
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `readRecord` (
                    `deviceId` TEXT NOT NULL,
                    `bookName` TEXT NOT NULL,
                    `bookAuthor` TEXT NOT NULL DEFAULT '',
                    `readTime` INTEGER NOT NULL DEFAULT 0,
                    `lastRead` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`deviceId`, `bookName`, `bookAuthor`)
                )
                """
            )
            database.execSQL(
                """
                INSERT INTO readRecord(deviceId, bookName, bookAuthor, readTime, lastRead)
                SELECT
                    rr.deviceId,
                    rr.bookName,
                    IFNULL(
                        (
                            SELECT CASE
                                WHEN COUNT(DISTINCT b.author) = 1 THEN MAX(b.author)
                                ELSE ''
                            END
                            FROM books b
                            WHERE b.name = rr.bookName
                        ),
                        ''
                    ) AS bookAuthor,
                    rr.readTime,
                    rr.lastRead
                FROM readRecord_old rr
                """
            )
            database.execSQL("DROP TABLE readRecord_old")

            database.execSQL("ALTER TABLE readRecordDetail RENAME TO readRecordDetail_old")
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `readRecordDetail` (
                    `deviceId` TEXT NOT NULL,
                    `bookName` TEXT NOT NULL,
                    `bookAuthor` TEXT NOT NULL DEFAULT '',
                    `date` TEXT NOT NULL,
                    `readTime` INTEGER NOT NULL DEFAULT 0,
                    `readWords` INTEGER NOT NULL DEFAULT 0,
                    `firstReadTime` INTEGER NOT NULL DEFAULT 0,
                    `lastReadTime` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`deviceId`, `bookName`, `bookAuthor`, `date`)
                )
                """
            )
            database.execSQL(
                """
                INSERT INTO readRecordDetail(
                    deviceId, bookName, bookAuthor, date, readTime, readWords, firstReadTime, lastReadTime
                )
                SELECT
                    rd.deviceId,
                    rd.bookName,
                    IFNULL(
                        (
                            SELECT CASE
                                WHEN COUNT(DISTINCT b.author) = 1 THEN MAX(b.author)
                                ELSE ''
                            END
                            FROM books b
                            WHERE b.name = rd.bookName
                        ),
                        ''
                    ) AS bookAuthor,
                    rd.date,
                    rd.readTime,
                    rd.readWords,
                    rd.firstReadTime,
                    rd.lastReadTime
                FROM readRecordDetail_old rd
                """
            )
            database.execSQL("DROP TABLE readRecordDetail_old")

            database.execSQL("ALTER TABLE readRecordSession RENAME TO readRecordSession_old")
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `readRecordSession` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `deviceId` TEXT NOT NULL,
                    `bookName` TEXT NOT NULL,
                    `bookAuthor` TEXT NOT NULL DEFAULT '',
                    `startTime` INTEGER NOT NULL,
                    `endTime` INTEGER NOT NULL,
                    `words` INTEGER NOT NULL
                )
                """
            )
            database.execSQL(
                """
                INSERT INTO readRecordSession(id, deviceId, bookName, bookAuthor, startTime, endTime, words)
                SELECT
                    rs.id,
                    rs.deviceId,
                    rs.bookName,
                    IFNULL(
                        (
                            SELECT CASE
                                WHEN COUNT(DISTINCT b.author) = 1 THEN MAX(b.author)
                                ELSE ''
                            END
                            FROM books b
                            WHERE b.name = rs.bookName
                        ),
                        ''
                    ) AS bookAuthor,
                    rs.startTime,
                    rs.endTime,
                    rs.words
                FROM readRecordSession_old rs
                """
            )
            database.execSQL("DROP TABLE readRecordSession_old")
        }
    }

    @Suppress("ClassName")
    class Migration_54_55 : AutoMigrationSpec {

        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                update books set type = ${BookType.audio}
                where type = ${BookSourceType.audio}
            """.trimIndent()
            )
            db.execSQL(
                """
                update books set type = ${BookType.image}
                where type = ${BookSourceType.image}
            """.trimIndent()
            )
            db.execSQL(
                """
                update books set type = ${BookType.webFile}
                where type = ${BookSourceType.file}
            """.trimIndent()
            )
            db.execSQL(
                """
                update books set type = ${BookType.text}
                where type = ${BookSourceType.default}
            """.trimIndent()
            )
            db.execSQL(
                """
                update books set type = type | ${BookType.local}
                where origin like '${BookType.localTag}%' or origin like '${BookType.webDavTag}%'
            """.trimIndent()
            )
        }

    }


    @Suppress("ClassName")
    @DeleteColumn(
        tableName = "book_sources",
        columnName = "enabledReview"
    )
    class Migration_64_65 : AutoMigrationSpec

    // region 98→99: 合并上游 txtTocRules 重构 / highlightRules 扩展列 + 本地 readingMemory 表 / BookCharacterProfile.isProtagonist 列

    private val migration_98_99 = object : Migration(98, 99) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // --- 上游: txtTocRules 重构（rule → chapterRule + volumeRule）---
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `txtTocRules_new` (
                    `id` INTEGER NOT NULL,
                    `name` TEXT NOT NULL,
                    `chapterRule` TEXT NOT NULL,
                    `volumeRule` TEXT NOT NULL DEFAULT '',
                    `example` TEXT,
                    `serialNumber` INTEGER NOT NULL,
                    `enable` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO txtTocRules_new (id, name, chapterRule, volumeRule, example, serialNumber, enable)
                SELECT id, name, rule, '', example, serialNumber, enable FROM txtTocRules
                """.trimIndent()
            )
            database.execSQL("DROP TABLE txtTocRules")
            database.execSQL("ALTER TABLE txtTocRules_new RENAME TO txtTocRules")

            // --- 上游: highlightRules 扩展（字重、斜体、九宫格背景）---
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN fontWeight INTEGER NOT NULL DEFAULT 400")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN isItalic INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN npLeft REAL NOT NULL DEFAULT 0.1")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN npRight REAL NOT NULL DEFAULT 0.1")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN npTop REAL NOT NULL DEFAULT 0.1")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN npBottom REAL NOT NULL DEFAULT 0.1")

            // --- 本地: readingMemory 阅读记忆表 ---
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS readingMemory (
                    bookUrl TEXT NOT NULL,
                    bookName TEXT NOT NULL DEFAULT '',
                    bookAuthor TEXT NOT NULL DEFAULT '',
                    coverUrl TEXT,
                    intro TEXT,
                    userModifiedIntro INTEGER NOT NULL DEFAULT 0,
                    kind TEXT,
                    wordCount TEXT,
                    type INTEGER NOT NULL DEFAULT 0,
                    progress REAL NOT NULL DEFAULT 0.0,
                    totalChapterNum INTEGER NOT NULL DEFAULT 0,
                    durChapterIndex INTEGER NOT NULL DEFAULT 0,
                    durChapterPos INTEGER NOT NULL DEFAULT 0,
                    rating REAL NOT NULL DEFAULT 0.0,
                    review TEXT,
                    abandoned INTEGER NOT NULL DEFAULT 0,
                    firstReadTime INTEGER NOT NULL DEFAULT 0,
                    finishReadTime INTEGER NOT NULL DEFAULT 0,
                    lastReadTime INTEGER NOT NULL DEFAULT 0,
                    createTime INTEGER NOT NULL DEFAULT 0,
                    updateTime INTEGER NOT NULL DEFAULT 0,
                    annotationCount INTEGER NOT NULL DEFAULT 0,
                    protagonistsJson TEXT,
                    excerptsJson TEXT,
                    statTotalReadTime INTEGER NOT NULL DEFAULT 0,
                    statReadingDays INTEGER NOT NULL DEFAULT 0,
                    statMaxDayReadTime INTEGER NOT NULL DEFAULT 0,
                    statMaxDayReadDate TEXT,
                    statTotalWords INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (bookUrl)
                )
                """.trimIndent()
            )

            // --- 本地: BookCharacterProfile 新增 isProtagonist 列 ---
            database.execSQL(
                "ALTER TABLE book_character_profiles ADD COLUMN isProtagonist INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    // endregion

    // region 99→100: 标签管理体系（BookTag / 分组 / 关联 / 排除 / 映射 / 关联书籍 / 已移除自动标签）

    private val migration_99_100 = object : Migration(99, 100) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS bookTags (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL DEFAULT '',
                    color INTEGER NOT NULL DEFAULT 0,
                    groupId INTEGER NOT NULL DEFAULT 0,
                    createTime INTEGER NOT NULL DEFAULT 0,
                    updateTime INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS bookTagGroups (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL DEFAULT '',
                    sortOrder INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS bookTagRelations (
                    id TEXT NOT NULL PRIMARY KEY,
                    bookUrl TEXT NOT NULL DEFAULT '',
                    tagId INTEGER NOT NULL DEFAULT 0,
                    createTime INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS excludedTags (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL DEFAULT '',
                    isRegex INTEGER NOT NULL DEFAULT 0,
                    createTime INTEGER NOT NULL DEFAULT 0,
                    updateTime INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tagMappings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    oldTagName TEXT NOT NULL DEFAULT '',
                    newTagId INTEGER NOT NULL DEFAULT 0,
                    createTime INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS bookTagBooks (
                    id TEXT NOT NULL PRIMARY KEY,
                    bookUrl TEXT NOT NULL DEFAULT '',
                    tagName TEXT NOT NULL DEFAULT '',
                    bookName TEXT NOT NULL DEFAULT '',
                    author TEXT NOT NULL DEFAULT '',
                    coverUrl TEXT NOT NULL DEFAULT '',
                    createTime INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS removedAutoTags (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    tagName TEXT NOT NULL DEFAULT '',
                    createTime INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
        }
    }

    // region 100→101: 删除孤儿冗余表 bookTagBooks（标签关联书籍快照从未被读写，与关系表 bookTagRelations 永久不同步）

    private val migration_100_101 = object : Migration(100, 101) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL("DROP TABLE IF EXISTS bookTagBooks")
            // 上游: books 新增 listIntro 列, 并从搜索缓存回填
            database.execSQL("ALTER TABLE books ADD COLUMN listIntro TEXT")
            database.execSQL(
                """
                update books set listIntro = (
                    select intro from searchBooks where searchBooks.bookUrl = books.bookUrl
                )
                where listIntro is null
                """.trimIndent()
            )
            // 本地额外: highlightRules 扩展列
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN useProtagonist INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN textColorNight INTEGER")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN bgColorNight INTEGER")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN underlineColorNight INTEGER")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN bgPaddingStart REAL NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN bgPaddingEnd REAL NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN bgPaddingTop REAL NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN bgPaddingBottom REAL NOT NULL DEFAULT 0")
            database.execSQL("UPDATE highlightRules SET npLeft = 0.5 WHERE npLeft = 0.1")
            database.execSQL("UPDATE highlightRules SET npRight = 0.5 WHERE npRight = 0.1")
            database.execSQL("UPDATE highlightRules SET npTop = 0.5 WHERE npTop = 0.1")
            database.execSQL("UPDATE highlightRules SET npBottom = 0.5 WHERE npBottom = 0.1")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN bgMarginStart REAL NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN bgMarginEnd REAL NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN bgMarginTop REAL NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN bgMarginBottom REAL NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN fontSizeOffset INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN underlineBelowText INTEGER NOT NULL DEFAULT 0")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN underlineDashLen REAL NOT NULL DEFAULT 8")
            database.execSQL("ALTER TABLE highlightRules ADD COLUMN underlineDashGap REAL NOT NULL DEFAULT 5")
            // 本地额外: 分享模板表
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS shareCardTemplates (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL,
                    htmlContent TEXT NOT NULL,
                    isBuiltin INTEGER NOT NULL DEFAULT 0,
                    createTime INTEGER NOT NULL DEFAULT 0,
                    updateTime INTEGER NOT NULL DEFAULT 0,
                    groupName TEXT NOT NULL DEFAULT '书籍'
                )
                """.trimIndent()
            )
            // 本地额外: readRecord 系列表新增 bookType 列
            database.execSQL("ALTER TABLE readRecord ADD COLUMN bookType INTEGER NOT NULL DEFAULT 8")
            database.execSQL("ALTER TABLE readRecordDetail ADD COLUMN bookType INTEGER NOT NULL DEFAULT 8")
            database.execSQL("ALTER TABLE readRecordSession ADD COLUMN bookType INTEGER NOT NULL DEFAULT 8")
            // 本地额外: readRecordSession 新增 chapterTitle 列（视频集数名/文本章节名，冗余存储）
            database.execSQL("ALTER TABLE readRecordSession ADD COLUMN chapterTitle TEXT NOT NULL DEFAULT ''")
            // 本地额外: readRecord 新增 coverUrl 列（影视等不在书架的记录的封面）
            database.execSQL("ALTER TABLE readRecord ADD COLUMN coverUrl TEXT NOT NULL DEFAULT ''")
        }
    }

    // endregion

    /**
     * 新版阅读器统一使用空 deviceId。旧版本数据库中的记录可能仍带有 Android ID，
     * 需要在覆盖升级时归并到本地分区，否则升级后继续阅读会产生两条记录。
     * 作者为空且书架中只有一个作者时使用该作者，否则继续保留空作者；冲突行按 SQL
     * 中的聚合规则合并，以保证主键唯一。
     */
    private val migration_102_103 = object : Migration(102, 103) {
        override fun migrate(database: SupportSQLiteDatabase) {
            // 将旧设备分区合并到本地分区；同一本书的旧空作者仅在书架存在唯一作者时归并。
            // 阅读时段记录按书名、作者和完整时间区间聚合，字段重复的记录只保留一条并取较大的字数。
            database.execSQL(
                """
                CREATE TABLE readRecord_migrated (
                    deviceId TEXT NOT NULL,
                    bookName TEXT NOT NULL,
                    bookAuthor TEXT NOT NULL DEFAULT '',
                    readTime INTEGER NOT NULL DEFAULT 0,
                    lastRead INTEGER NOT NULL DEFAULT 0,
                    bookType INTEGER NOT NULL DEFAULT 8,
                    PRIMARY KEY(deviceId, bookName, bookAuthor)
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO readRecord_migrated(deviceId, bookName, bookAuthor, readTime, lastRead, bookType)
                SELECT '', bookName, canonicalAuthor, SUM(readTime), MAX(lastRead), MAX(bookType)
                FROM (
                    SELECT rr.bookName, rr.readTime, rr.lastRead, rr.bookType,
                        CASE WHEN rr.bookAuthor <> '' THEN rr.bookAuthor ELSE COALESCE((
                            SELECT CASE WHEN COUNT(DISTINCT b.author) = 1 THEN MAX(b.author) ELSE '' END
                            FROM books b WHERE b.name = rr.bookName
                        ), '') END AS canonicalAuthor
                    FROM readRecord rr
                )
                GROUP BY bookName, canonicalAuthor
                """.trimIndent()
            )
            database.execSQL("DROP TABLE readRecord")
            database.execSQL("ALTER TABLE readRecord_migrated RENAME TO readRecord")

            database.execSQL(
                """
                CREATE TABLE readRecordDetail_migrated (
                    deviceId TEXT NOT NULL,
                    bookName TEXT NOT NULL,
                    bookAuthor TEXT NOT NULL DEFAULT '',
                    date TEXT NOT NULL,
                    readTime INTEGER NOT NULL DEFAULT 0,
                    readWords INTEGER NOT NULL DEFAULT 0,
                    firstReadTime INTEGER NOT NULL DEFAULT 0,
                    lastReadTime INTEGER NOT NULL DEFAULT 0,
                    bookType INTEGER NOT NULL DEFAULT 8,
                    PRIMARY KEY(deviceId, bookName, bookAuthor, date)
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO readRecordDetail_migrated(
                    deviceId, bookName, bookAuthor, date, readTime, readWords,
                    firstReadTime, lastReadTime, bookType
                )
                SELECT '', bookName, canonicalAuthor, date, SUM(readTime), SUM(readWords),
                    COALESCE(MIN(CASE WHEN firstReadTime > 0 THEN firstReadTime ELSE NULL END), 0),
                    MAX(lastReadTime), MAX(bookType)
                FROM (
                    SELECT rd.bookName, rd.date, rd.readTime, rd.readWords,
                        rd.firstReadTime, rd.lastReadTime, rd.bookType,
                        CASE WHEN rd.bookAuthor <> '' THEN rd.bookAuthor ELSE COALESCE((
                            SELECT CASE WHEN COUNT(DISTINCT b.author) = 1 THEN MAX(b.author) ELSE '' END
                            FROM books b WHERE b.name = rd.bookName
                        ), '') END AS canonicalAuthor
                    FROM readRecordDetail rd
                )
                GROUP BY bookName, canonicalAuthor, date
                """.trimIndent()
            )
            database.execSQL("DROP TABLE readRecordDetail")
            database.execSQL("ALTER TABLE readRecordDetail_migrated RENAME TO readRecordDetail")

            database.execSQL(
                """
                CREATE TABLE readRecordSession_migrated (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    deviceId TEXT NOT NULL,
                    bookName TEXT NOT NULL,
                    bookAuthor TEXT NOT NULL DEFAULT '',
                    startTime INTEGER NOT NULL,
                    endTime INTEGER NOT NULL,
                    words INTEGER NOT NULL,
                    bookType INTEGER NOT NULL DEFAULT 8,
                    chapterTitle TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent()
            )
            database.execSQL(
                """
                INSERT INTO readRecordSession_migrated(
                    deviceId, bookName, bookAuthor, startTime, endTime, words, bookType, chapterTitle
                )
                SELECT '', bookName, canonicalAuthor, startTime, endTime, MAX(words), MAX(bookType), MAX(chapterTitle)
                FROM (
                    SELECT rs.bookName, rs.startTime, rs.endTime, rs.words, rs.bookType, rs.chapterTitle,
                        CASE WHEN rs.bookAuthor <> '' THEN rs.bookAuthor ELSE COALESCE((
                            SELECT CASE WHEN COUNT(DISTINCT b.author) = 1 THEN MAX(b.author) ELSE '' END
                            FROM books b WHERE b.name = rs.bookName
                        ), '') END AS canonicalAuthor
                    FROM readRecordSession rs
                )
                GROUP BY bookName, canonicalAuthor, startTime, endTime
                """.trimIndent()
            )
            database.execSQL("DROP TABLE readRecordSession")
            database.execSQL("ALTER TABLE readRecordSession_migrated RENAME TO readRecordSession")

            // 本地额外: 作者简介表。原先整份塞在 AppConfigStore 的 author_intros 单个 JSON 里，
            // 而 settings DataStore 不能放大 value（会阻塞 App.onCreate 的同步预载），故独立成表。
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS authorProfiles (
                    name TEXT NOT NULL,
                    bio TEXT NOT NULL,
                    source TEXT NOT NULL DEFAULT 'manual',
                    updateTime INTEGER NOT NULL DEFAULT 0,
                    model TEXT,
                    PRIMARY KEY(name)
                )
                """.trimIndent()
            )

            // 本地额外: readingMemory.customTag 与 bookTags 的 name 唯一索引在实体里已声明,
            // 但建表迁移(98_99 / 99_100)漏了, 老库升级后会在 Room 校验 schema 时抛
            // IllegalStateException。全新安装由 createAllTables 建表, 已带这两项, 因此先探测再补。
            val hasCustomTag = database.query("PRAGMA table_info(`readingMemory`)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "customTag") {
                        found = true
                        break
                    }
                }
                found
            }
            if (!hasCustomTag) {
                database.execSQL("ALTER TABLE readingMemory ADD COLUMN customTag TEXT")
            }
            // 唯一索引缺失期间可能已写入同名标签, 先把关联指向保留下来的那一条再去重, 否则建索引会失败
            database.execSQL(
                """
                UPDATE bookTagRelations SET tagId = (
                    SELECT MIN(keep.id) FROM bookTags keep
                    WHERE keep.name = (
                        SELECT dup.name FROM bookTags dup WHERE dup.id = bookTagRelations.tagId
                    )
                )
                WHERE EXISTS (SELECT 1 FROM bookTags dup WHERE dup.id = bookTagRelations.tagId)
                """.trimIndent()
            )
            database.execSQL(
                "DELETE FROM bookTags WHERE id NOT IN (SELECT MIN(id) FROM bookTags GROUP BY name)"
            )
            database.execSQL(
                """
                DELETE FROM bookTagRelations WHERE id NOT IN (
                    SELECT MIN(id) FROM bookTagRelations GROUP BY bookUrl, tagId
                )
                """.trimIndent()
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_bookTags_name` ON `bookTags` (`name`)"
            )

            // 书架标签筛选：bookTags.showOnBookshelf
            database.execSQL("ALTER TABLE bookTags ADD COLUMN showOnBookshelf INTEGER NOT NULL DEFAULT 1")
        }
    }
}
