package com.tang.player.data

import java.util.Locale

enum class BrowseSort(val label: String) { NAME("名称"), SIZE("大小"), TYPE("类型") }

/** Directories stay first in either direction; unknown sizes stay after known sizes. */
fun <T> sortBrowseItems(items: List<T>, sort: BrowseSort, descending: Boolean,
    name: (T) -> String, directory: (T) -> Boolean, size: (T) -> Long?): List<T> = items.sortedWith { a, b ->
    val directories = compareValues(!directory(a), !directory(b))
    if (directories != 0) directories else {
        val order = if (directory(a) || sort == BrowseSort.NAME) 0 else when (sort) {
            BrowseSort.SIZE -> {
                val left = size(a); val right = size(b)
                if ((left == null) != (right == null)) return@sortedWith compareValues(left == null, right == null)
                compareValues(left, right)
            }
            BrowseSort.TYPE -> compareValues(name(a).substringAfterLast('.', "").lowercase(Locale.ROOT), name(b).substringAfterLast('.', "").lowercase(Locale.ROOT))
            BrowseSort.NAME -> 0
        }
        (if (order != 0) order else MediaSeriesGrouper.naturalCompare(name(a), name(b))) * if (descending) -1 else 1
    }
}
