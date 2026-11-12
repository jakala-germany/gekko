package com.jakala.gekko.sample

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp

/**
 * The screen of a TV app: a search field above a grid of teasers that a remote moves the focus through (up, down, left,
 * right) and opens (select). The ids are the ones of the tvOS sample, so the same tests run on both.
 */
@Composable
internal fun TvSample() {
    var query by rememberSaveable { mutableStateOf("") }
    var opened by rememberSaveable { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // A TV app starts with the focus on its content
    LaunchedEffect(Unit) { first.requestFocus() }
    Column(Modifier.padding(48.dp).semantics { testTagsAsResourceId = true }) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier.testTag("sample.tv.search").onPreviewKeyEvent { leaveVertically(it, focusManager) },
        )
        Text("Opened: $opened", modifier = Modifier.padding(vertical = 16.dp).testTag("sample.teaser.opened"))
        repeat(TEASER_ROWS) { row ->
            Row {
                repeat(TEASER_COLUMNS) { column ->
                    val number = row * TEASER_COLUMNS + column + 1
                    Teaser(number, if (number == 1) Modifier.focusRequester(first) else Modifier) { opened = number }
                }
            }
        }
    }
}

/**
 * A text field takes the arrow keys for its cursor, so up and down would never leave it: a focus trap that a TV app has
 * to avoid. Up and down move the focus on; left and right stay with the cursor.
 */
private fun leaveVertically(event: KeyEvent, focusManager: FocusManager): Boolean {
    val direction =
        when (event.key) {
            Key.DirectionUp -> FocusDirection.Up
            Key.DirectionDown -> FocusDirection.Down
            else -> return false
        }
    if (event.type == KeyEventType.KeyDown) {
        focusManager.moveFocus(direction)
    }
    return true
}

/** A teaser that shows it has the focus with a thick border; a click (select on the remote) opens it. */
@Composable
private fun Teaser(number: Int, modifier: Modifier, onOpen: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        "Teaser $number",
        modifier =
            modifier
                .testTag("sample.teaser.$number")
                .padding(8.dp)
                .onFocusChanged { focused = it.isFocused }
                .border(if (focused) 4.dp else 1.dp, if (focused) Color(0xFF1565C0) else Color.Gray)
                .clickable(onClick = onOpen)
                .padding(24.dp),
    )
}

private const val TEASER_ROWS = 3
private const val TEASER_COLUMNS = 3
