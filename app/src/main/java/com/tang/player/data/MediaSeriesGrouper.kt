package com.tang.player.data

import com.tang.player.core.MediaItem
import java.text.Normalizer
import java.util.Locale

sealed interface LibraryMediaEntry {
    val key: String
    val title: String
    val items: List<MediaItem>

    data class File(val media: MediaItem) : LibraryMediaEntry {
        override val key get() = "file:${media.uri}"
        override val title get() = media.displayName
        override val items get() = listOf(media)
    }

    data class Series(override val key: String, override val title: String,
        override val items: List<MediaItem>) : LibraryMediaEntry
}

/** Pure filename inference. Never writes files or changes their URIs; ambiguous names stay separate. */
object MediaSeriesGrouper {
    private val brackets = Regex("\\[([^]]*)]|【([^】]*)】|\\(([^)]*)\\)|（([^）]*)）")
    private val leadingBracket = Regex("^(?:\\[([^]]+)]|【([^】]+)】)\\s*")
    private val separators = Regex("[\\s._—–-]+")
    private val technical = Regex("(?i)(?:\\b(?:2160p?|1080[pi]?|720p?|480p?|4k|8k|hevc|avc|av1|x26[45]|h[ .]?26[45]|aac|flac|opus|webrip|web[ ._-]?dl|bluray|bdrip|hdr|sdr|10bit|8bit|chs|cht|gb|big5)\\b|简体|繁体|简繁|内嵌|外挂|中字|双语)")
    private val releaseGroup = Regex("(?i)字幕|字幕组|\\b(?:subs?|subtitles?|raws?)\\b|制作组|压制组|发布组")
    private val crc = Regex("(?i)^[a-f0-9]{8}$")
    private val seasonEpisode = Regex("(?i)(?<![a-z0-9])s(\\d{1,2})[ ._-]*e(\\d{1,4})(?!\\d)")
    private val chineseEpisode = Regex("第\\s*([零〇一二两三四五六七八九十百千\\d]+)\\s*[集话話期章回]")
    private val englishEpisode = Regex("(?i)(?<![a-z])(?:episodes?|ep|e|parts?|chapters?)[ ._-]*(\\d{1,4})(?!\\d)")
    private val delimitedNumber = Regex("(?:[ ._-]+)(\\d{1,3})(?!\\d)(?=$|[ ._-])")
    private val trailingNumber = Regex("(\\d{1,3})(?:v\\d+)?$", RegexOption.IGNORE_CASE)
    private val chineseSeason = Regex("第\\s*([零〇一二两三四五六七八九十百千\\d]+)\\s*季$")
    private val englishSeason = Regex("(?i)(?:^|[ ._-])(?:season[ ._-]*|s)(\\d{1,2})$")
    private val cameraName = Regex("(?i)^(?:vid|img|dsc|dcim|pxl|mov|screen[ ._-]?(?:record|shot)|录屏|屏幕录制)(?:[ ._-]*\\d|[ ._-]+)")
    private val mediaExtension = Regex("(?i)\\.(?:mp4|mkv|webm|avi|mov|m4v|wmv|flv|3gp|3g2|ts|m2ts|mts|mpeg|mpg|vob|ogv|mp3|m4a|aac|wav|flac|ogg|opus|wma|aiff|ape|amr)$")
    private val dateStart = Regex("^(?:19|20)\\d{2} (?:0?[1-9]|1[0-2]) (?:0?[1-9]|[12]\\d|3[01])(?:$| )")
    private val generic = setOf("video", "audio", "movie", "clip", "episode", "ep", "season", "series", "sample", "test", "untitled", "视频", "音频", "电影", "未命名", "录屏")
    private data class Parsed(val media: MediaItem, val clean: String, val title: String? = null,
        val season: Int? = null, val episode: Int? = null, val safePrefix: Boolean = true)

    fun group(media: List<MediaItem>): List<LibraryMediaEntry> {
        val parsed = media.distinctBy { it.uri }.map(::parse)
        val result = mutableListOf<LibraryMediaEntry>()
        val explicit = parsed.filter { it.title != null }.groupBy {
            "series:${type(it.media)}:${it.season ?: 0}:${canonical(it.title!!)}"
        }
        for ((key, members) in explicit) {
            if (members.size < 2) result += LibraryMediaEntry.File(members.single().media)
            else {
                val ordered = members.sortedWith { a, b ->
                    compareValues(a.episode, b.episode).takeIf { it != 0 } ?: compareMedia(a.media, b.media)
                }
                val title = members.minWith { a, b -> naturalCompare(a.title!!, b.title!!) }.title!!
                result += LibraryMediaEntry.Series(key, title + (members.first().season?.let { " · 第${it}季" } ?: ""), ordered.map { it.media })
            }
        }
        // Only complete tokens can form a fallback prefix: "Show Alpha" and "Showcase Beta" never merge.
        val unmarked = parsed.filter { it.title == null }
        val buckets = linkedMapOf<String, MutableList<Parsed>>()
        for (item in unmarked.filter { it.safePrefix }) {
            val words = item.clean.split(' ').filter { it.isNotEmpty() }
            for (end in 1 until words.size) {
                val prefix = words.take(end).joinToString(" ")
                if (!meaningful(prefix, fallback = true)) continue
                val key = "prefix:${type(item.media)}:${canonical(prefix)}"
                buckets.getOrPut(key) { mutableListOf() } += item
            }
        }
        val used = hashSetOf<String>()
        // Prefer the longest shared name; avoid swallowing distinct nested series into a short parent prefix.
        for ((key, bucket) in buckets.entries.sortedWith(compareByDescending<Map.Entry<String, MutableList<Parsed>>> { it.key.length }.thenBy { it.key })) {
            val members = bucket.filter { it.media.uri !in used }
            if (members.size < 2) continue
            val wordCount = key.substringAfter(":", "").substringAfter(':').split(' ').size
            val title = members.first().clean.split(' ').take(wordCount).joinToString(" ")
            result += LibraryMediaEntry.Series(key, title, members.map { it.media }.sortedWith(::compareMedia))
            used += members.map { it.media.uri }
        }
        unmarked.filter { it.media.uri !in used }.forEach { result += LibraryMediaEntry.File(it.media) }
        return result.sortedWith { a, b -> naturalCompare(a.title, b.title).takeIf { it != 0 } ?: a.key.compareTo(b.key) }
    }

    private fun parse(media: MediaItem): Parsed {
        val stem = mediaExtension.replace(Normalizer.normalize(media.displayName, Normalizer.Form.NFKC), "")
        var name = stem.trim()
        while (true) {
            val lead = leadingBracket.find(name) ?: break
            val value = lead.groupValues.drop(1).first { it.isNotEmpty() }
            val remaining = name.substring(lead.range.last + 1).trim()
            val nextBracket = leadingBracket.find(remaining)
            val nextValue = nextBracket?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
            val plainTitleFollows = nextBracket == null && remaining.any(Char::isLetter) &&
                !Regex("(?i)^(?:s\\d+e\\d+|(?:ep|e)\\d+|\\d+)").containsMatchIn(remaining)
            val bracketTitleFollows = nextValue != null && nextValue.any(Char::isLetter) && !isTechnical(nextValue)
            if (releaseGroup.containsMatchIn(value) || isTechnical(value) || plainTitleFollows || bracketTitleFollows) name = remaining else break
        }
        name = brackets.replace(name) { match ->
            val value = match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }.orEmpty().trim()
            if (isTechnical(value) || releaseGroup.containsMatchIn(value)) " " else " $value "
        }.trim()
        val clean = normalizeTitle(name)
        if (cameraName.containsMatchIn(clean) || dateStart.containsMatchIn(clean) || clean.all { it.isDigit() || it == ' ' }) {
            return Parsed(media, clean, safePrefix = false)
        }
        seasonEpisode.find(name)?.let { match ->
            return explicit(media, clean, name.substring(0, match.range.first), match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }
        chineseEpisode.find(name)?.let { match ->
            return explicit(media, clean, name.substring(0, match.range.first), null, chineseNumber(match.groupValues[1]))
        }
        englishEpisode.find(name)?.let { match ->
            return explicit(media, clean, name.substring(0, match.range.first), null, match.groupValues[1].toInt())
        }
        delimitedNumber.find(name)?.let { match ->
            return explicit(media, clean, name.substring(0, match.range.first), null, match.groupValues[1].toInt())
        }
        // Trailing technical tags are ignored only for the unmarked numeric-suffix form.
        val withoutTags = technical.replace(name, " ").trim().trimEnd('-', '.', '_', ' ')
        trailingNumber.find(withoutTags)?.let { match ->
            val prefix = withoutTags.substring(0, match.range.first)
            if (prefix.lastOrNull()?.isLetter() == true || prefix.lastOrNull() in listOf(' ', '-', '_', '.')) {
                return explicit(media, clean, prefix, null, match.groupValues[1].toInt())
            }
        }
        return Parsed(media, clean)
    }

    private fun explicit(media: MediaItem, clean: String, rawTitle: String, season: Int?, episode: Int?): Parsed {
        var title = normalizeTitle(rawTitle)
        var resolvedSeason = season
        chineseSeason.find(title)?.let { match -> resolvedSeason = chineseNumber(match.groupValues[1]); title = title.substring(0, match.range.first).trim() }
        englishSeason.find(title)?.let { match -> resolvedSeason = match.groupValues[1].toInt(); title = title.substring(0, match.range.first).trim() }
        if (!meaningful(title) || episode == null) return Parsed(media, clean, safePrefix = false)
        return Parsed(media, clean, title, resolvedSeason, episode)
    }

    private fun normalizeTitle(value: String) = separators.replace(value.trim(), " ").trim()
    private fun canonical(value: String) = normalizeTitle(value).lowercase(Locale.ROOT)
    private fun type(media: MediaItem) = if (media.isVideo) "video" else "audio"
    private fun isTechnical(value: String) = technical.containsMatchIn(value) || crc.matches(value)
    private fun meaningful(value: String, fallback: Boolean = false): Boolean {
        val key = canonical(value)
        if (key in generic || key.isBlank()) return false
        val han = key.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
        return han >= 2 || key.count(Char::isLetter) >= if (fallback) 6 else 3
    }

    private fun chineseNumber(value: String): Int? {
        value.toIntOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (value.all { it in digits }) return value.map { digits.getValue(it) }.joinToString("").toIntOrNull()
        var result = 0; var digit = 0
        for (char in value) {
            if (char in digits) digit = digits.getValue(char)
            else {
                val unit = when (char) { '十' -> 10; '百' -> 100; '千' -> 1000; else -> return null }
                result += (if (digit == 0) 1 else digit) * unit; digit = 0
            }
        }
        return result + digit
    }

    private fun compareMedia(a: MediaItem, b: MediaItem) = naturalCompare(a.displayName, b.displayName).takeIf { it != 0 } ?: a.uri.compareTo(b.uri)

    /** Compare digit runs without parsing into fixed-size integers, so episode 2 precedes 10. */
    fun naturalCompare(left: String, right: String): Int {
        val a = left.lowercase(Locale.ROOT); val b = right.lowercase(Locale.ROOT)
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            if (a[i] in '0'..'9' && b[j] in '0'..'9') {
                val ai = i; val bj = j
                while (i < a.length && a[i] in '0'..'9') i++
                while (j < b.length && b[j] in '0'..'9') j++
                val na = a.substring(ai, i).trimStart('0').ifEmpty { "0" }
                val nb = b.substring(bj, j).trimStart('0').ifEmpty { "0" }
                val compared = na.length.compareTo(nb.length).takeIf { it != 0 } ?: na.compareTo(nb)
                if (compared != 0) return compared
            } else {
                val compared = a[i].compareTo(b[j])
                if (compared != 0) return compared
                i++; j++
            }
        }
        return (a.length - i).compareTo(b.length - j).takeIf { it != 0 } ?: left.compareTo(right)
    }
}
