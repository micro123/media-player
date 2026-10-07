package io.github.micro123.mediaplayer.data

import io.github.micro123.mediaplayer.core.MediaItem
import org.junit.Assert.*
import org.junit.Test

class MediaSeriesGrouperTest {
    private fun file(name: String, type: String = "video/mp4") = MediaItem("content://test/$name/$type", name, type, 10)
    private fun entries(vararg names: String) = MediaSeriesGrouper.group(names.map { file(it) })
    private fun series(vararg names: String) = entries(*names).filterIsInstance<LibraryMediaEntry.Series>()

    @Test fun numericSuffixesSortByEpisodeInsteadOfLexically() {
        val group = series("深海回声10.mp4", "深海回声2.mkv", "深海回声01.mp4").single()
        assertEquals("深海回声", group.title)
        assertEquals(listOf("深海回声01.mp4", "深海回声2.mkv", "深海回声10.mp4"), group.items.map { it.displayName })
    }

    @Test fun chineseEpisodesSupportNumeralsAndEpisodeNames() {
        val group = series("深海回声 第十一集 远方.mp4", "深海回声 第2集 风声.mp4", "深海回声 第一集 起点.mp4").single()
        assertEquals(listOf("深海回声 第一集 起点.mp4", "深海回声 第2集 风声.mp4", "深海回声 第十一集 远方.mp4"), group.items.map { it.displayName })
    }

    @Test fun seasonsAreSeparated() {
        val groups = series("Deep.Ocean.S01E10.mkv", "Deep Ocean S01E02.mp4", "Deep Ocean S02E01.mp4", "Deep Ocean S02E02.mp4")
        assertEquals(2, groups.size)
        assertEquals(setOf("Deep Ocean · 第1季", "Deep Ocean · 第2季"), groups.map { it.title }.toSet())
        assertTrue(groups.first().items.first().displayName.contains("02"))
    }

    @Test fun chineseAndEnglishSeasonNamesAreCompatible() {
        val group = series("深海回声 第二季 第三集.mp4", "深海回声 S02E01.mp4").single()
        assertEquals("深海回声 · 第2季", group.title)
        assertTrue(group.items.first().displayName.contains("S02E01"))
    }

    @Test fun releaseGroupsAndEncodingTagsDoNotSplitASeries() {
        val group = series("[Group A] Deep Ocean - 01 [1080p HEVC AAC][ABCDEF12].mkv",
            "[Group B] Deep Ocean - 02 [720p AVC][CHS].mp4").single()
        assertEquals("Deep Ocean", group.title)
    }

    @Test fun bracketedTitleIsPreserved() {
        assertEquals("深海回声", series("[字幕组][深海回声][01][1080P].mkv", "[字幕组][深海回声][02][720P].mkv").single().title)
        assertEquals("Deep Ocean", series("[Deep Ocean][01][1080P].mkv", "[Deep Ocean][02][1080P].mkv").single().title)
        assertEquals("Submarine", series("[Submarine][01].mkv", "[Submarine][02].mkv").single().title)
    }

    @Test fun episodeMarkersSupportCommonEnglishForms() {
        val group = series("Deep Ocean EP01.mp4", "Deep Ocean E02.mp4", "Deep Ocean Episode 10.mp4").single()
        assertEquals(3, group.items.size)
        assertTrue(group.items.last().displayName.contains("10"))
    }

    @Test fun fullWidthNamesAreNormalized() {
        assertEquals(1, series("深海回声　第０１集.mp4", "深海回声 第02集.mp4").size)
    }

    @Test fun legitimateTitlesMayStartWithNumbers() {
        assertEquals("86 不存在的战区", series("86-不存在的战区 第01集.mp4", "86-不存在的战区 第02集.mp4").single().title)
        assertEquals("3 Body Problem · 第1季", series("3.Body.Problem.S01E01.mkv", "3.Body.Problem.S01E02.mkv").single().title)
    }

    @Test fun episodeSuffixIsNotMistakenForAnExtensionWhenNoExtensionExists() {
        assertEquals("Deep Ocean · 第1季", series("Deep.Ocean.S01E01", "Deep.Ocean.S01E02").single().title)
    }

    @Test fun namedEpisodesWithSharedTokenPrefixAreGrouped() {
        assertEquals("机器学习", series("机器学习-安装环境.mp4", "机器学习-训练模型.mp4").single().title)
        assertEquals("Nature Walk", series("Nature_Walk_Forest.mp4", "Nature_Walk_Coast.mp4").single().title)
    }

    @Test fun longestSharedPrefixKeepsNestedCoursesSeparate() {
        val groups = series("机器学习-基础-入门.mp4", "机器学习-基础-进阶.mp4", "机器学习-实战-入门.mp4", "机器学习-实战-进阶.mp4")
        assertEquals(setOf("机器学习 基础", "机器学习 实战"), groups.map { it.title }.toSet())
        assertTrue(groups.all { it.items.size == 2 })
    }

    @Test fun differentExplicitTitlesAreNotMergedByAShortCommonPrefix() {
        assertTrue(series("Deep Ocean E01.mp4", "Deep Space E02.mp4").isEmpty())
    }

    @Test fun prefixesCannotEndInTheMiddleOfAWord() {
        assertTrue(series("Nature Forest.mp4", "Natureland Coast.mp4").isEmpty())
    }

    @Test fun cameraAndDateNamesRemainIndividualFiles() {
        val values = entries("VID_20261007_101010.mp4", "VID_20261007_101020.mp4", "IMG_20261007_01.mp4", "IMG_20261007_02.mp4",
            "2026-10-01.mp4", "2026-10-02.mp4", "录屏-20261007-01.mp4", "录屏-20261007-02.mp4")
        assertEquals(8, values.size)
        assertTrue(values.all { it is LibraryMediaEntry.File })
    }

    @Test fun genericAndNumberOnlyNamesAreNotSeries() {
        assertTrue(series("video01.mp4", "video02.mp4", "01.mp4", "02.mp4").isEmpty())
    }

    @Test fun audioAndVideoNeverShareAGroup() {
        val values = MediaSeriesGrouper.group(listOf(file("深海回声01.mp4"), file("深海回声02.mp4"),
            file("深海回声01.mp3", "audio/mpeg"), file("深海回声02.mp3", "audio/mpeg")))
        assertEquals(2, values.size)
        assertTrue(values.all { it is LibraryMediaEntry.Series && it.items.size == 2 })
    }

    @Test fun aSingleFileIsNeverPresentedAsASeries() {
        assertEquals(1, entries("深海回声01.mp4").size)
        assertTrue(entries("深海回声01.mp4").single() is LibraryMediaEntry.File)
    }

    @Test fun duplicateUrisDoNotInflateEpisodeCounts() {
        val media = file("深海回声01.mp4")
        assertTrue(MediaSeriesGrouper.group(listOf(media, media)).single() is LibraryMediaEntry.File)
    }

    @Test fun identityIsStableWhenNewEpisodesArriveAndInputOrderChanges() {
        val a = series("Deep Ocean E02.mp4", "Deep Ocean E01.mp4").single()
        val b = series("Deep Ocean E03.mp4", "Deep Ocean E01.mp4", "Deep Ocean E02.mp4").single()
        assertEquals(a.key, b.key)
        assertEquals(a.items, b.items.take(2))
    }

    @Test fun groupingDoesNotRenameFilesOrChangeUrisAndKeepsAllUniqueItems() {
        val input = listOf(file("深海回声01.mp4"), file("深海回声02.mkv"), file("一部独立电影.mp4"))
        assertEquals(input.toSet(), MediaSeriesGrouper.group(input).flatMap { it.items }.toSet())
    }

    @Test fun naturalComparatorHandlesLargeNumbersWithoutOverflow() {
        assertTrue(MediaSeriesGrouper.naturalCompare("file2", "file10") < 0)
        assertTrue(MediaSeriesGrouper.naturalCompare("file99999999999999999999999", "file100000000000000000000000") < 0)
    }
}
