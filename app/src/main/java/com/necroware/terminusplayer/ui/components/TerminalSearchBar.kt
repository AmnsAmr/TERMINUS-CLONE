package com.necroware.terminusplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Shared terminal search input bar with prompt `> `, accent cursor, single-line input, and `[X]` clear button.
 */
@Composable
fun TerminalSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    focusRequester: FocusRequester? = null,
    onClear: (() -> Unit)? = null,
    onSearchAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "> ",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = (if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .weight(1f),
            textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onBackground),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchAction?.invoke() }),
            decorationBox = { innerTextField ->
                if (query.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                innerTextField()
            }
        )
        if (query.isNotEmpty()) {
            TerminalActionText(
                text = "[X]",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable {
                        if (onClear != null) onClear() else onQueryChange("")
                    }
                    .padding(start = 8.dp)
            )
        }
    }
}

/**
 * Builds an AnnotatedString highlighting matching query substrings with highlightColor.
 */
@Composable
fun highlightedText(
    value: String,
    query: String,
    highlightColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary
): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    val needle = query.trim()
    if (needle.isEmpty()) {
        append(value)
        return@buildAnnotatedString
    }
    var cursor = 0
    while (cursor < value.length) {
        val matchStart = value.indexOf(needle, cursor, ignoreCase = true)
        if (matchStart < 0) {
            append(value.substring(cursor))
            break
        }
        append(value.substring(cursor, matchStart))
        withStyle(SpanStyle(color = highlightColor)) {
            append(value.substring(matchStart, matchStart + needle.length))
        }
        cursor = matchStart + needle.length
    }
}
