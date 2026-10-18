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
            migration_82_83, migration_98_99, migration_99_100, migration_100_101,
            migration_102_103, migration_123_124, migration_124_125, migration_127_128,
        )
    }

    /**
     * 127 → 128：正则角色的分组从文本列升级成可嵌套的分组树。
     *
     * 迁移前的 `regex_cast_rules.group` 存的是组名字符串（只能一层、改名要全表改写）。
     * 这里把已有的组名各建成一个根层分组（id 直接用组名，幂等且好认），规则改指 id，
     * 然后把 rules 整表重建去掉旧列——minSdk 26 的 SQLite 没有 DROP COLUMN（要 3.35），
     * 与 124→125 同一手法。
     */
    private val migration_127_128 = object : Migration(127, 128) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `regex_cast_groups` (
                    `id` TEXT NOT NULL, `name` TEXT NOT NULL, `parentId` TEXT NOT NULL DEFAULT '',
                    `order` INTEGER NOT NULL DEFAULT 0, `enabled` INTEGER NOT NULL DEFAULT 1,
                    `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`))"""
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_regex_cast_groups_parentId_name` ON `regex_cast_groups` (`parentId`, `name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_regex_cast_groups_parentId` ON `regex_cast_groups` (`parentId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_regex_cast_groups_parentId_order` ON `regex_cast_groups` (`parentId`, `order`)")
            db.execSQL(
                """INSERT OR IGNORE INTO regex_cast_groups(id, name, parentId, `order`, enabled, createdAt, updatedAt)
                SELECT DISTINCT r.`group`, r.`group`, '', 0, 1, r.createdAt, r.updatedAt
                FROM regex_cast_rules r WHERE r.`group` IS NOT NULL AND TRIM(r.`group`) <> ''"""
            )
            db.execSQL("ALTER TABLE regex_cast_rules ADD COLUMN groupId TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE regex_cast_rules SET groupId = IFNULL(`group`, '')")
            db.execSQL(
                """CREATE TABLE regex_cast_rules_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL,
                    pattern TEXT NOT NULL, poolKind TEXT NOT NULL DEFAULT 'role',
                    poolId TEXT NOT NULL DEFAULT '', itemId TEXT NOT NULL DEFAULT '',
                    groupId TEXT NOT NULL DEFAULT '', enabled INTEGER NOT NULL DEFAULT 1,
                    `order` INTEGER NOT NULL DEFAULT 0, scope TEXT, excludeScope TEXT,
                    createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)"""
            )
            db.execSQL(
                """INSERT INTO regex_cast_rules_new
                    (id, name, pattern, poolKind, poolId, itemId, groupId, enabled, `order`, scope, excludeScope, createdAt, updatedAt)
                SELECT id, name, pattern, poolKind, poolId, itemId, groupId, enabled, `order`, scope, excludeScope, createdAt, updatedAt
                FROM regex_cast_rules"""
            )
            db.execSQL("DROP TABLE regex_cast_rules")
            db.execSQL("ALTER TABLE regex_cast_rules_new RENAME TO regex_cast_rules")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_regex_cast_rules_enabled_order` ON `regex_cast_rules` (`enabled`, `order`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_regex_cast_rules_groupId_order` ON `regex_cast_rules` (`groupId`, `order`)")
            // fork 本地扩展列：上游 123→124 / 124→125 整表重建 highlightRules 时只带上了
            // 上游列，本地列（在 100→101 里 ADD 的）会被那次重建丢掉。这里守卫式补回，
            // 已存在就跳过，避免重复 ADD COLUMN 让迁移失败。新增的本地列也统一挂在这里——
            // 与 AppDatabase 版本号无关，任何低于 128 的库都会经过这一次守卫 ADD。
            val highlightRuleColumns = db.query("PRAGMA table_info(highlightRules)").use { cursor ->
                val nameAt = cursor.getColumnIndexOrThrow("name")
                buildSet { while (cursor.moveToNext()) add(cursor.getString(nameAt)) }
            }
            listOf(
                "useProtagonist INTEGER NOT NULL DEFAULT 0",
                "bgBorderScale REAL NOT NULL DEFAULT 1",
                "textColorNight INTEGER",
                "bgColorNight INTEGER",
                "underlineColorNight INTEGER",
                "bgPaddingStart REAL NOT NULL DEFAULT 0",
                "bgPaddingEnd REAL NOT NULL DEFAULT 0",
                "bgPaddingTop REAL NOT NULL DEFAULT 0",
                "bgPaddingBottom REAL NOT NULL DEFAULT 0",
                "bgMarginStart REAL NOT NULL DEFAULT 0",
                "bgMarginEnd REAL NOT NULL DEFAULT 0",
                "bgMarginTop REAL NOT NULL DEFAULT 0",
                "bgMarginBottom REAL NOT NULL DEFAULT 0",
                "underlineBelowText INTEGER NOT NULL DEFAULT 0",
                "underlineDashLen REAL NOT NULL DEFAULT 8",
                "underlineDashGap REAL NOT NULL DEFAULT 5",
                "underlineWavePeak REAL NOT NULL DEFAULT 1.5",
                "underlineWaveLength REAL NOT NULL DEFAULT 24",
                "characterRole TEXT",
                "underlineRoundCap INTEGER NOT NULL DEFAULT 0",
                "underlineFeather REAL NOT NULL DEFAULT 0",
            ).forEach { spec ->
                val name = spec.substringBefore(' ')
                if (name !in highlightRuleColumns) {
                    db.execSQL("ALTER TABLE highlightRules ADD COLUMN `$name` ${spec.substringAfter(' ')}")
                }
            }

            // fork 本地扩展列：声音池的结构化分类（性别 × 年龄段）。
            // 音色本身没有年龄字段，年龄从「属于哪个池」派生，而池的年龄记在这里——
            // 这是「角色年龄性别 → 音色」精确匹配链路的唯一桥梁。
            // 守卫式：已存在就跳过，避免重复 ADD COLUMN 让迁移失败（同上 highlightRules 的写法）。
            val voicePoolColumns = db.query("PRAGMA table_info(voice_pools)").use { cursor ->
                val nameAt = cursor.getColumnIndexOrThrow("name")
                buildSet { while (cursor.moveToNext()) add(cursor.getString(nameAt)) }
            }
            listOf(
                "gender TEXT NOT NULL DEFAULT 'unknown'",
                "ageBand TEXT NOT NULL DEFAULT 'unknown'",
            ).forEach { spec ->
                val name = spec.substringBefore(' ')
                if (name !in voicePoolColumns) {
                    db.execSQL("ALTER TABLE voice_pools ADD COLUMN `$name` ${spec.substringAfter(' ')}")
                }
            }
            // 按默认池名回填结构化分类（幂等：UPDATE 对已回填的行只是写回同样的值）。
            // 用户自建池不在名单里，保持 unknown（走关键词兜底匹配）。
            listOf(
                "男童" to ("male" to "child"),
                "男少年" to ("male" to "teen"),
                "男青年" to ("male" to "young_adult"),
                "男中年" to ("male" to "adult"),
                "男性老年" to ("male" to "elderly"),
                "失配男" to ("male" to "unknown"),
                "女童" to ("female" to "child"),
                "女少女" to ("female" to "teen"),
                "女青年" to ("female" to "young_adult"),
                "女中年" to ("female" to "adult"),
                "女性老年" to ("female" to "elderly"),
                "失配女" to ("female" to "unknown"),
            ).forEach { (name, classification) ->
                db.execSQL(
                    "UPDATE voice_pools SET gender = '${classification.first}', ageBand = '${classification.second}' WHERE name = '$name'"
                )
            }

            // fork 本地扩展列：人物档案的「声音池外键」。
            // 该表的 voiceAgeBand 列曾被写入**池名**（如「女青年」）而非年龄段枚举，与列名背离；
            // 现在 voiceAgeBand 回归枚举语义，池的归属改由 voicePoolId 承载。
            // 守卫式：已存在就跳过。
            val profileColumns = db.query("PRAGMA table_info(book_character_profiles)").use { cursor ->
                val nameAt = cursor.getColumnIndexOrThrow("name")
                buildSet { while (cursor.moveToNext()) add(cursor.getString(nameAt)) }
            }
            if ("voicePoolId" !in profileColumns) {
                db.execSQL("ALTER TABLE book_character_profiles ADD COLUMN voicePoolId TEXT NOT NULL DEFAULT ''")
            }
            // 两步数据迁移（顺序不能反）：
            // 1) 把「voiceAgeBand 存的是池名」的行解析成 voicePoolId（按池名精确匹配）。
            //    枚举值（child/teen/…）不是池名，跳过；池已被删除则留空串。
            db.execSQL(
                """UPDATE book_character_profiles SET voicePoolId = COALESCE(
                    (SELECT id FROM voice_pools WHERE name = book_character_profiles.voiceAgeBand), ''
                ) WHERE voiceAgeBand != '' AND voiceAgeBand NOT IN
                    ('child','teen','young_adult','adult','elderly','unknown')"""
            )
            // 2) 用池的 ageBand 回填枚举（对所有有 poolId 的行统一做，幂等）。
            //    池已删除时 COALESCE 归 unknown——语义正确（曾指向的池没了）。
            db.execSQL(
                """UPDATE book_character_profiles SET voiceAgeBand = COALESCE(
                    (SELECT ageBand FROM voice_pools WHERE id = book_character_profiles.voicePoolId), 'unknown'
                ) WHERE voicePoolId != ''"""
            )
        }
    }

    private val migration_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE txtTocRules")
            db.execSQL(
                """CREATE TABLE txtTocRules(id INTEGER NOT NULL, 
                    name TEXT NOT NULL, rule TEXT NOT NULL, serialNumber INTEGER NOT NULL, 
                    enable INTEGER NOT NULL, PRIMARY KEY (id))"""
            )
        }
    }

    private val migration_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE rssSources ADD style TEXT ")
        }
    }

    private val migration_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE rssSources ADD articleStyle INTEGER NOT NULL DEFAULT 0 ")
        }
    }

    private val migration_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `books_new` (`bookUrl` TEXT NOT NULL, `tocUrl` TEXT NOT NULL, `origin` TEXT NOT NULL,
                    `originName` TEXT NOT NULL, `name` TEXT NOT NULL, `author` TEXT NOT NULL, `kind` TEXT, `customTag` TEXT, `coverUrl` TEXT, 
                    `customCoverUrl` TEXT, `intro` TEXT, `customIntro` TEXT, `charset` TEXT, `type` INTEGER NOT NULL, `group` INTEGER NOT NULL, 
                    `latestChapterTitle` TEXT, `latestChapterTime` INTEGER NOT NULL, `lastCheckTime` INTEGER NOT NULL, `lastCheckCount` INTEGER NOT NULL, 
                    `totalChapterNum` INTEGER NOT NULL, `durChapterTitle` TEXT, `durChapterIndex` INTEGER NOT NULL, `durChapterPos` INTEGER NOT NULL, 
                    `durChapterTime` INTEGER NOT NULL, `wordCount` TEXT, `canUpdate` INTEGER NOT NULL, `order` INTEGER NOT NULL, 
                    `originOrder` INTEGER NOT NULL, `useReplaceRule` INTEGER NOT NULL, `variable` TEXT, PRIMARY KEY(`bookUrl`))"""
            )
            db.execSQL("INSERT INTO books_new select * from books ")
            db.execSQL("DROP TABLE books")
            db.execSQL("ALTER TABLE books_new RENAME TO books")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_books_name_author` ON `books` (`name`, `author`) ")
        }
    }

    private val migration_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE bookmarks ADD bookAuthor TEXT NOT NULL DEFAULT ''")
        }
    }

    private val migration_15_17 = object : Migration(15, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `readRecord` (`bookName` TEXT NOT NULL, `readTime` INTEGER NOT NULL, PRIMARY KEY(`bookName`))")
        }
    }

    private val migration_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `httpTTS` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, PRIMARY KEY(`id`))")
        }
    }

    private val migration_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `readRecordNew` (`androidId` TEXT NOT NULL, `bookName` TEXT NOT NULL, `readTime` INTEGER NOT NULL, 
                    PRIMARY KEY(`androidId`, `bookName`))"""
            )
            db.execSQL("INSERT INTO readRecordNew(androidId, bookName, readTime) select '${AppConst.androidId}' as androidId, bookName, readTime from readRecord")
            db.execSQL("DROP TABLE readRecord")
            db.execSQL("ALTER TABLE readRecordNew RENAME TO readRecord")
        }
    }
    private val migration_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE book_sources ADD bookSourceComment TEXT")
        }
    }

    private val migration_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE book_groups ADD show INTEGER NOT NULL DEFAULT 1")
        }
    }

    private val migration_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `books_new` (`bookUrl` TEXT NOT NULL, `tocUrl` TEXT NOT NULL, `origin` TEXT NOT NULL, 
                    `originName` TEXT NOT NULL, `name` TEXT NOT NULL, `author` TEXT NOT NULL, `kind` TEXT, `customTag` TEXT, 
                    `coverUrl` TEXT, `customCoverUrl` TEXT, `intro` TEXT, `customIntro` TEXT, `charset` TEXT, `type` INTEGER NOT NULL, 
                    `group` INTEGER NOT NULL, `latestChapterTitle` TEXT, `latestChapterTime` INTEGER NOT NULL, `lastCheckTime` INTEGER NOT NULL, 
                    `lastCheckCount` INTEGER NOT NULL, `totalChapterNum` INTEGER NOT NULL, `durChapterTitle` TEXT, `durChapterIndex` INTEGER NOT NULL, 
                    `durChapterPos` INTEGER NOT NULL, `durChapterTime` INTEGER NOT NULL, `wordCount` TEXT, `canUpdate` INTEGER NOT NULL, 
                    `order` INTEGER NOT NULL, `originOrder` INTEGER NOT NULL, `variable` TEXT, `readConfig` TEXT, PRIMARY KEY(`bookUrl`))"""
            )
            db.execSQL(
                """INSERT INTO books_new select `bookUrl`, `tocUrl`, `origin`, `originName`, `name`, `author`, `kind`, `customTag`, `coverUrl`, 
                    `customCoverUrl`, `intro`, `customIntro`, `charset`, `type`, `group`, `latestChapterTitle`, `latestChapterTime`, `lastCheckTime`, 
                    `lastCheckCount`, `totalChapterNum`, `durChapterTitle`, `durChapterIndex`, `durChapterPos`, `durChapterTime`, `wordCount`, `canUpdate`, 
                    `order`, `originOrder`, `variable`, null
                    from books"""
            )
            db.execSQL("DROP TABLE books")
            db.execSQL("ALTER TABLE books_new RENAME TO books")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_books_name_author` ON `books` (`name`, `author`) ")
        }
    }

    private val migration_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chapters ADD baseUrl TEXT NOT NULL DEFAULT ''")
        }
    }

    private val migration_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `caches` (`key` TEXT NOT NULL, `value` TEXT, `deadline` INTEGER NOT NULL, PRIMARY KEY(`key`))")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_caches_key` ON `caches` (`key`)")
        }
    }

    private val migration_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `sourceSubs` 
                    (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `type` INTEGER NOT NULL, `customOrder` INTEGER NOT NULL, 
                    PRIMARY KEY(`id`))"""
            )
        }
    }

    private val migration_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `ruleSubs` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `type` INTEGER NOT NULL, 
                    `customOrder` INTEGER NOT NULL, `autoUpdate` INTEGER NOT NULL, `update` INTEGER NOT NULL, PRIMARY KEY(`id`))"""
            )
            db.execSQL(" insert into `ruleSubs` select *, 0, 0 from `sourceSubs` ")
            db.execSQL("DROP TABLE `sourceSubs`")
        }
    }

    private val migration_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(" ALTER TABLE rssSources ADD singleUrl INTEGER NOT NULL DEFAULT 0 ")
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `bookmarks1` (`time` INTEGER NOT NULL, `bookUrl` TEXT NOT NULL, `bookName` TEXT NOT NULL, 
                        `bookAuthor` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, `chapterPos` INTEGER NOT NULL, `chapterName` TEXT NOT NULL, 
                        `bookText` TEXT NOT NULL, `content` TEXT NOT NULL, PRIMARY KEY(`time`))"""
            )
            db.execSQL(
                """insert into `bookmarks1` 
                        select `time`, `bookUrl`, `bookName`, `bookAuthor`, `chapterIndex`, `pageIndex`, `chapterName`, '', `content` 
                        from bookmarks"""
            )
            db.execSQL(" DROP TABLE `bookmarks` ")
            db.execSQL(" ALTER TABLE bookmarks1 RENAME TO bookmarks ")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bookmarks_time` ON `bookmarks` (`time`)")
        }
    }

    private val migration_27_28 = object : Migration(27, 28) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE rssArticles ADD variable TEXT")
            db.execSQL("ALTER TABLE rssStars ADD variable TEXT")
        }
    }

    private val migration_28_29 = object : Migration(28, 29) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE rssSources ADD sourceComment TEXT")
        }
    }

    private val migration_29_30 = object : Migration(29, 30) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE chapters ADD `startFragmentId` TEXT")
            db.execSQL("ALTER TABLE chapters ADD `endFragmentId` TEXT")
            db.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS `epubChapters` 
                    (`bookUrl` TEXT NOT NULL, `href` TEXT NOT NULL, `parentHref` TEXT, 
                    PRIMARY KEY(`bookUrl`, `href`), FOREIGN KEY(`bookUrl`) REFERENCES `books`(`bookUrl`) ON UPDATE NO ACTION ON DELETE CASCADE )
                """
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_epubChapters_bookUrl` ON `epubChapters` (`bookUrl`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_epubChapters_bookUrl_href` ON `epubChapters` (`bookUrl`, `href`)")
        }
    }

    private val migration_30_31 = object : Migration(30, 31) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE readRecord RENAME TO readRecord1")
            db.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS `readRecord` (`deviceId` TEXT NOT NULL, `bookName` TEXT NOT NULL, `readTime` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `bookName`))
                """
            )
            db.execSQL("insert into readRecord (deviceId, bookName, readTime) select androidId, bookName, readTime from readRecord1")
        }
    }

    private val migration_31_32 = object : Migration(31, 32) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE `epubChapters`")
        }
    }

    private val migration_32_33 = object : Migration(32, 33) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE bookmarks RENAME TO bookmarks_old")
            db.execSQL(
                """
                    CREATE TABLE IF NOT EXISTS `bookmarks` (`time` INTEGER NOT NULL,
                    `bookName` TEXT NOT NULL, `bookAuthor` TEXT NOT NULL, `chapterIndex` INTEGER NOT NULL, 
                    `chapterPos` INTEGER NOT NULL, `chapterName` TEXT NOT NULL, `bookText` TEXT NOT NULL, 
                    `content` TEXT NOT NULL, PRIMARY KEY(`time`))
                """
            )
            db.execSQL(
                """
                    CREATE INDEX IF NOT EXISTS `index_bookmarks_bookName_bookAuthor` ON `bookmarks` (`bookName`, `bookAuthor`)
                """
            )
            db.execSQL(
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
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `book_groups` ADD `cover` TEXT")
        }
    }

    private val migration_34_35 = object : Migration(34, 35) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `book_sources` ADD `concurrentRate` TEXT")
        }
    }

    private val migration_35_36 = object : Migration(35, 36) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `book_sources` ADD `loginUi` TEXT")
            db.execSQL("ALTER TABLE `book_sources` ADD`loginCheckJs` TEXT")
        }
    }

    private val migration_36_37 = object : Migration(36, 37) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `rssSources` ADD `loginUrl` TEXT")
            db.execSQL("ALTER TABLE `rssSources` ADD `loginUi` TEXT")
            db.execSQL("ALTER TABLE `rssSources` ADD `loginCheckJs` TEXT")
        }
    }

    private val migration_37_38 = object : Migration(37, 38) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `book_sources` ADD `respondTime` INTEGER NOT NULL DEFAULT 180000")
        }
    }

    private val migration_38_39 = object : Migration(38, 39) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `rssSources` ADD `concurrentRate` TEXT")
        }
    }

    private val migration_39_40 = object : Migration(39, 40) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chapters` ADD `isVip` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `chapters` ADD `isPay` INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val migration_40_41 = object : Migration(40, 41) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `httpTTS` ADD `loginUrl` TEXT")
            db.execSQL("ALTER TABLE `httpTTS` ADD `loginUi` TEXT")
            db.execSQL("ALTER TABLE `httpTTS` ADD `loginCheckJs` TEXT")
            db.execSQL("ALTER TABLE `httpTTS` ADD `header` TEXT")
            db.execSQL("ALTER TABLE `httpTTS` ADD `concurrentRate` TEXT")
        }
    }

    private val migration_41_42 = object : Migration(41, 42) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE 'httpTTS' ADD `contentType` TEXT")
        }
    }

    private val migration_42_43 = object : Migration(42, 43) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `chapters` ADD `isVolume` INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val migration_82_83 = object : Migration(82, 83) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE readRecord RENAME TO readRecord_old")
            db.execSQL(
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
            db.execSQL(
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
            db.execSQL("DROP TABLE readRecord_old")

            db.execSQL("ALTER TABLE readRecordDetail RENAME TO readRecordDetail_old")
            db.execSQL(
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
            db.execSQL(
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
            db.execSQL("DROP TABLE readRecordDetail_old")

            db.execSQL("ALTER TABLE readRecordSession RENAME TO readRecordSession_old")
            db.execSQL(
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
            db.execSQL(
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
            db.execSQL("DROP TABLE readRecordSession_old")
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
        override fun migrate(db: SupportSQLiteDatabase) {
            // --- 上游: txtTocRules 重构（rule → chapterRule + volumeRule）---
            db.execSQL(
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
            db.execSQL(
                """
                INSERT INTO txtTocRules_new (id, name, chapterRule, volumeRule, example, serialNumber, enable)
                SELECT id, name, rule, '', example, serialNumber, enable FROM txtTocRules
                """.trimIndent()
            )
            db.execSQL("DROP TABLE txtTocRules")
            db.execSQL("ALTER TABLE txtTocRules_new RENAME TO txtTocRules")

            // --- 上游: highlightRules 扩展（字重、斜体、九宫格背景）---
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN fontWeight INTEGER NOT NULL DEFAULT 400")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN isItalic INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN npLeft REAL NOT NULL DEFAULT 0.1")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN npRight REAL NOT NULL DEFAULT 0.1")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN npTop REAL NOT NULL DEFAULT 0.1")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN npBottom REAL NOT NULL DEFAULT 0.1")

            // --- 本地: readingMemory 阅读记忆表 ---
            db.execSQL(
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
            db.execSQL(
                "ALTER TABLE book_character_profiles ADD COLUMN isProtagonist INTEGER NOT NULL DEFAULT 0"
            )
        }
    }

    // endregion

    // region 99→100: 标签管理体系（BookTag / 分组 / 关联 / 排除 / 映射 / 关联书籍 / 已移除自动标签）

    private val migration_99_100 = object : Migration(99, 100) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
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
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS bookTagGroups (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL DEFAULT '',
                    sortOrder INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS bookTagRelations (
                    id TEXT NOT NULL PRIMARY KEY,
                    bookUrl TEXT NOT NULL DEFAULT '',
                    tagId INTEGER NOT NULL DEFAULT 0,
                    createTime INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
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
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS tagMappings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    oldTagName TEXT NOT NULL DEFAULT '',
                    newTagId INTEGER NOT NULL DEFAULT 0,
                    createTime INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
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
            db.execSQL(
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
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS bookTagBooks")
            // 上游: books 新增 listIntro 列, 并从搜索缓存回填
            db.execSQL("ALTER TABLE books ADD COLUMN listIntro TEXT")
            db.execSQL(
                """
                update books set listIntro = (
                    select intro from searchBooks where searchBooks.bookUrl = books.bookUrl
                )
                where listIntro is null
                """.trimIndent()
            )
            // 本地额外: highlightRules 扩展列
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN useProtagonist INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN textColorNight INTEGER")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN bgColorNight INTEGER")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN underlineColorNight INTEGER")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN bgPaddingStart REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN bgPaddingEnd REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN bgPaddingTop REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN bgPaddingBottom REAL NOT NULL DEFAULT 0")
            db.execSQL("UPDATE highlightRules SET npLeft = 0.5 WHERE npLeft = 0.1")
            db.execSQL("UPDATE highlightRules SET npRight = 0.5 WHERE npRight = 0.1")
            db.execSQL("UPDATE highlightRules SET npTop = 0.5 WHERE npTop = 0.1")
            db.execSQL("UPDATE highlightRules SET npBottom = 0.5 WHERE npBottom = 0.1")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN bgMarginStart REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN bgMarginEnd REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN bgMarginTop REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN bgMarginBottom REAL NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN fontSizeOffset INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN underlineBelowText INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN underlineDashLen REAL NOT NULL DEFAULT 8")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN underlineDashGap REAL NOT NULL DEFAULT 5")
            // 波浪形状参数。默认 1.5dp 峰高 / 24dp 波长 = 迁移前的观感，不改变已有规则的表现。
            // 注意：只加这两个新列。underlineRoundCap / underlineFeather 等列在本文件里没有
            // 对应 ALTER，它们要么来自上游 103→107 的 autoMigration，要么本身就存在缺口，
            // 不要在这里补——重复 ADD COLUMN 会让整个 migration_100_101 失败。
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN underlineWavePeak REAL NOT NULL DEFAULT 1.5")
            db.execSQL("ALTER TABLE highlightRules ADD COLUMN underlineWaveLength REAL NOT NULL DEFAULT 24")
            // 本地额外: 分享模板表
            db.execSQL(
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
            db.execSQL("ALTER TABLE readRecord ADD COLUMN bookType INTEGER NOT NULL DEFAULT 8")
            db.execSQL("ALTER TABLE readRecordDetail ADD COLUMN bookType INTEGER NOT NULL DEFAULT 8")
            db.execSQL("ALTER TABLE readRecordSession ADD COLUMN bookType INTEGER NOT NULL DEFAULT 8")
            // 本地额外: readRecordSession 新增 chapterTitle 列（视频集数名/文本章节名，冗余存储）
            db.execSQL("ALTER TABLE readRecordSession ADD COLUMN chapterTitle TEXT NOT NULL DEFAULT ''")
            // 本地额外: readRecord 新增 coverUrl 列（影视等不在书架的记录的封面）
            db.execSQL("ALTER TABLE readRecord ADD COLUMN coverUrl TEXT NOT NULL DEFAULT ''")
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
        override fun migrate(db: SupportSQLiteDatabase) {
            // 将旧设备分区合并到本地分区；同一本书的旧空作者仅在书架存在唯一作者时归并。
            // 阅读时段记录按书名、作者和完整时间区间聚合，字段重复的记录只保留一条并取较大的字数。
            db.execSQL(
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
            db.execSQL(
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
            db.execSQL("DROP TABLE readRecord")
            db.execSQL("ALTER TABLE readRecord_migrated RENAME TO readRecord")

            db.execSQL(
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
            db.execSQL(
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
            db.execSQL("DROP TABLE readRecordDetail")
            db.execSQL("ALTER TABLE readRecordDetail_migrated RENAME TO readRecordDetail")

            db.execSQL(
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
            db.execSQL(
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
            db.execSQL("DROP TABLE readRecordSession")
            db.execSQL("ALTER TABLE readRecordSession_migrated RENAME TO readRecordSession")

            // 本地额外: 作者简介表。原先整份塞在 AppConfigStore 的 author_intros 单个 JSON 里，
            // 而 settings DataStore 不能放大 value（会阻塞 App.onCreate 的同步预载），故独立成表。
            db.execSQL(
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
            val hasCustomTag = db.query("PRAGMA table_info(`readingMemory`)").use { cursor ->
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
                db.execSQL("ALTER TABLE readingMemory ADD COLUMN customTag TEXT")
            }
            // 唯一索引缺失期间可能已写入同名标签, 先把关联指向保留下来的那一条再去重, 否则建索引会失败
            db.execSQL(
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
            db.execSQL(
                "DELETE FROM bookTags WHERE id NOT IN (SELECT MIN(id) FROM bookTags GROUP BY name)"
            )
            db.execSQL(
                """
                DELETE FROM bookTagRelations WHERE id NOT IN (
                    SELECT MIN(id) FROM bookTagRelations GROUP BY bookUrl, tagId
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_bookTags_name` ON `bookTags` (`name`)"
            )

            // 书架标签筛选：bookTags.showOnBookshelf
            db.execSQL("ALTER TABLE bookTags ADD COLUMN showOnBookshelf INTEGER NOT NULL DEFAULT 1")

            // 本地额外: 分镜场景分组。场景是朗读分析之后按整章补跑的轻量拆分，
            // 只有 AI 模式会写入；0 / 空串表示该章还没有场景信息，分镜页退化为扁平列表。
            db.execSQL(
                "ALTER TABLE chapter_speech_segments ADD COLUMN sceneIndex INTEGER NOT NULL DEFAULT 0"
            )
            db.execSQL(
                "ALTER TABLE chapter_speech_segments ADD COLUMN sceneTitle TEXT NOT NULL DEFAULT ''"
            )
        }
    }
    /**
     * 版本号 123 对外发布过两套列定义：一套是 bgPadXEm 与 matchSpacing 那五条列，
     * 另一套是命中排版四列 + 九宫格长度偏移。装过任一版的机器 room_master_table 里存的
     * 身份哈希与现行 schema 对不上时，Room 开库直接抛「Room cannot verify the data
     * integrity」，所以现行 schema 升到 124。
     *
     * 迁移本身要做出 124.json 的**逐列一致**：Room 会拿新 schema 校验迁移结果，
     * 旧版多出来的那七列留着就会报「Migration didn't validate」。而
     * `ALTER TABLE ... DROP COLUMN` 要 SQLite 3.35，minSdk 26 的机器上没有，
     * 于是按本文件既有的路子整表重建（先补列，再照 124.json 建表搬数据）。
     * 起点是「只跑过 122→123 自动迁移」的库也成立：那时五列已在、七列不存在。
     */
    private val migration_123_124 = object : Migration(123, 124) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = db.query("PRAGMA table_info(highlightRules)").use { cursor ->
                val nameAt = cursor.getColumnIndexOrThrow("name")
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(nameAt))
                }
            }
            listOf(
                "letterSpacingBefore", "letterSpacingAfter",
                "lineSpacingTop", "lineSpacingBottom", "bgLengthOffset",
            ).forEach { name ->
                if (name !in columns) {
                    db.execSQL("ALTER TABLE highlightRules ADD COLUMN `$name` REAL NOT NULL DEFAULT 0")
                }
            }
            db.execSQL(
                """
                CREATE TABLE highlightRules_r53 (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `pattern` TEXT NOT NULL,
                    `sampleText` TEXT NOT NULL,
                    `targetScope` INTEGER NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `position` INTEGER NOT NULL,
                    `textColor` INTEGER,
                    `bgColor` INTEGER,
                    `underlineMode` INTEGER NOT NULL,
                    `underlineColor` INTEGER,
                    `underlineWidth` REAL NOT NULL,
                    `underlineOffset` REAL NOT NULL,
                    `underlineSvgPath` TEXT,
                    `bgImage` TEXT,
                    `bgImageFit` INTEGER NOT NULL,
                    `bgImageScale` REAL NOT NULL,
                    `configName` TEXT,
                    `fontPath` TEXT,
                    `fontWeight` INTEGER NOT NULL,
                    `isItalic` INTEGER NOT NULL,
                    `fontSizeOffset` INTEGER NOT NULL,
                    `npLeft` REAL NOT NULL,
                    `npRight` REAL NOT NULL,
                    `npTop` REAL NOT NULL,
                    `npBottom` REAL NOT NULL,
                    `manualNineSlice` INTEGER NOT NULL DEFAULT 1,
                    `letterSpacingBefore` REAL NOT NULL DEFAULT 0,
                    `letterSpacingAfter` REAL NOT NULL DEFAULT 0,
                    `lineSpacingTop` REAL NOT NULL DEFAULT 0,
                    `lineSpacingBottom` REAL NOT NULL DEFAULT 0,
                    `bgLengthOffset` REAL NOT NULL DEFAULT 0,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO highlightRules_r53(
                    `id`, `name`, `pattern`, `sampleText`,
                    `targetScope`, `enabled`, `position`, `textColor`,
                    `bgColor`, `underlineMode`, `underlineColor`, `underlineWidth`,
                    `underlineOffset`, `underlineSvgPath`, `bgImage`, `bgImageFit`,
                    `bgImageScale`, `configName`, `fontPath`, `fontWeight`,
                    `isItalic`, `fontSizeOffset`, `npLeft`, `npRight`,
                    `npTop`, `npBottom`, `manualNineSlice`, `letterSpacingBefore`,
                    `letterSpacingAfter`, `lineSpacingTop`, `lineSpacingBottom`, `bgLengthOffset`
                )
                SELECT
                    `id`, `name`, `pattern`, `sampleText`,
                    `targetScope`, `enabled`, `position`, `textColor`,
                    `bgColor`, `underlineMode`, `underlineColor`, `underlineWidth`,
                    `underlineOffset`, `underlineSvgPath`, `bgImage`, `bgImageFit`,
                    `bgImageScale`, `configName`, `fontPath`, `fontWeight`,
                    `isItalic`, `fontSizeOffset`, `npLeft`, `npRight`,
                    `npTop`, `npBottom`, `manualNineSlice`, `letterSpacingBefore`,
                    `letterSpacingAfter`, `lineSpacingTop`, `lineSpacingBottom`, `bgLengthOffset`
                FROM highlightRules
                """.trimIndent()
            )
            db.execSQL("DROP TABLE highlightRules")
            db.execSQL("ALTER TABLE highlightRules_r53 RENAME TO highlightRules")
        }
    }

    /**
     * 「九宫格长度偏移」拆成左偏移 + 右偏移两项（对称的一项调不动气泡两端）。
     * 旧的 `bgLengthOffset` 因此要从表里去掉，换成 `bgLengthOffsetLeft` / `bgLengthOffsetRight`。
     * `ALTER TABLE ... DROP COLUMN` 要 SQLite 3.35，minSdk 26 没有，照本文件既有路子整表重建。
     * 老数据按「左右各一半」落到新列上，与升级前的观感一致。
     */
    private val migration_124_125 = object : Migration(124, 125) {
        override fun migrate(db: SupportSQLiteDatabase) {
            val columns = db.query("PRAGMA table_info(highlightRules)").use { cursor ->
                val nameAt = cursor.getColumnIndexOrThrow("name")
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(nameAt))
                }
            }
            if ("bgLengthOffset" !in columns) {
                db.execSQL("ALTER TABLE highlightRules ADD COLUMN `bgLengthOffset` REAL NOT NULL DEFAULT 0")
            }
            db.execSQL(
                """
                CREATE TABLE highlightRules_r56 (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `pattern` TEXT NOT NULL,
                    `sampleText` TEXT NOT NULL,
                    `targetScope` INTEGER NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `position` INTEGER NOT NULL,
                    `textColor` INTEGER,
                    `bgColor` INTEGER,
                    `underlineMode` INTEGER NOT NULL,
                    `underlineColor` INTEGER,
                    `underlineWidth` REAL NOT NULL,
                    `underlineOffset` REAL NOT NULL,
                    `underlineSvgPath` TEXT,
                    `bgImage` TEXT,
                    `bgImageFit` INTEGER NOT NULL,
                    `bgImageScale` REAL NOT NULL,
                    `configName` TEXT,
                    `fontPath` TEXT,
                    `fontWeight` INTEGER NOT NULL,
                    `isItalic` INTEGER NOT NULL,
                    `fontSizeOffset` INTEGER NOT NULL,
                    `npLeft` REAL NOT NULL,
                    `npRight` REAL NOT NULL,
                    `npTop` REAL NOT NULL,
                    `npBottom` REAL NOT NULL,
                    `manualNineSlice` INTEGER NOT NULL DEFAULT 1,
                    `letterSpacingBefore` REAL NOT NULL DEFAULT 0,
                    `letterSpacingAfter` REAL NOT NULL DEFAULT 0,
                    `lineSpacingTop` REAL NOT NULL DEFAULT 0,
                    `lineSpacingBottom` REAL NOT NULL DEFAULT 0,
                    `bgLengthOffsetLeft` REAL NOT NULL DEFAULT 0,
                    `bgLengthOffsetRight` REAL NOT NULL DEFAULT 0,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO highlightRules_r56(
                    `id`, `name`, `pattern`, `sampleText`,
                    `targetScope`, `enabled`, `position`, `textColor`,
                    `bgColor`, `underlineMode`, `underlineColor`, `underlineWidth`,
                    `underlineOffset`, `underlineSvgPath`, `bgImage`, `bgImageFit`,
                    `bgImageScale`, `configName`, `fontPath`, `fontWeight`,
                    `isItalic`, `fontSizeOffset`, `npLeft`, `npRight`,
                    `npTop`, `npBottom`, `manualNineSlice`, `letterSpacingBefore`,
                    `letterSpacingAfter`, `lineSpacingTop`, `lineSpacingBottom`,
                    `bgLengthOffsetLeft`, `bgLengthOffsetRight`
                )
                SELECT
                    `id`, `name`, `pattern`, `sampleText`,
                    `targetScope`, `enabled`, `position`, `textColor`,
                    `bgColor`, `underlineMode`, `underlineColor`, `underlineWidth`,
                    `underlineOffset`, `underlineSvgPath`, `bgImage`, `bgImageFit`,
                    `bgImageScale`, `configName`, `fontPath`, `fontWeight`,
                    `isItalic`, `fontSizeOffset`, `npLeft`, `npRight`,
                    `npTop`, `npBottom`, `manualNineSlice`, `letterSpacingBefore`,
                    `letterSpacingAfter`, `lineSpacingTop`, `lineSpacingBottom`,
                    `bgLengthOffset` / 2.0, `bgLengthOffset` / 2.0
                FROM highlightRules
                """.trimIndent()
            )
            db.execSQL("DROP TABLE highlightRules")
            db.execSQL("ALTER TABLE highlightRules_r56 RENAME TO highlightRules")
        }
    }
}
