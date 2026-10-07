package io.github.micro123.mediaplayer.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import io.github.micro123.mediaplayer.data.BrowseSort

@Composable
fun SearchButton(expanded: Boolean, onClick: () -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    IconButton(onClick = { if (expanded) keyboard?.hide(); onClick() }) {
        PlayerSymbol(if (expanded) PlayerIcon.CLOSE else PlayerIcon.SEARCH, description = if (expanded) "关闭搜索" else "搜索")
    }
}

@Composable
fun BrowserSearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(value, onChange, singleLine = true,
        placeholder = { Text(placeholder) }, modifier = Modifier.fillMaxWidth().focusRequester(focus))
}

@Composable
fun BrowseSortButton(sort: BrowseSort, descending: Boolean, onChange: (BrowseSort, Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { PlayerSymbol(PlayerIcon.SORT, description = "文件排序") }
        DropdownMenu(open, { open = false }) {
            BrowseSort.entries.forEach { item ->
                DropdownMenuItem(text = { Text("${if (item == sort) "✓ " else ""}按${item.label}排序") },
                    onClick = { onChange(item, descending); open = false })
            }
            HorizontalDivider()
            listOf(false to "升序", true to "降序").forEach { (reverse, label) ->
                DropdownMenuItem(text = { Text("${if (reverse == descending) "✓ " else ""}$label") },
                    onClick = { onChange(sort, reverse); open = false })
            }
        }
    }
}
