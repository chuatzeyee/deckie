package com.chuatzeyee.deck.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.chuatzeyee.deck.core.Status
import com.chuatzeyee.deck.poll.SessionView

private const val STALE_ALPHA = 0.45f

/** The dot/status-word color for [status] (reused by [DeckScreen]'s top bar); [Status.Off] is
 * drawn hollow (border, no fill) by [StatusDot]. */
fun dotColor(status: Status): Color = when (status) {
    Status.Waiting, Status.Finished -> DeckColors.Attention
    Status.Working -> DeckColors.Bright
    Status.Idle -> DeckColors.Dim
    Status.Off -> DeckColors.Dim
}

@Composable
private fun StatusDot(status: Status, modifier: Modifier = Modifier) {
    val color = dotColor(status)
    val dot = modifier.size(10.dp).clip(CircleShape)
    if (status == Status.Off) {
        Box(dot.border(1.dp, SolidColor(color), CircleShape))
    } else {
        Box(dot.background(color))
    }
}

/**
 * The left rail (mockup layout B, ≈28% width - set by the caller's weight, not here): one row per
 * live session (status dot + optional `[letter]` jump shortcut + name), selected row highlighted
 * [DeckColors.SelectedRow]. When [message] is non-null (disconnected, identity-changed, or no tmux
 * sessions on the host) it replaces the list entirely - a dropped link must never keep showing a
 * stale session list. When [stale] (the last poll result is older than ~2 poll intervals) the rows
 * are dimmed under an "updating…" line rather than presented as current.
 */
@Composable
fun Rail(
    sessions: List<SessionView>,
    selected: String?,
    letters: Map<String, Char>,
    message: String?,
    stale: Boolean,
    onSelect: (String) -> Unit,
    onLongPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(DeckColors.RailBackground)) {
        if (message != null) {
            Text(message, modifier = Modifier.padding(8.dp))
        } else {
            if (stale) Text("updating…", color = DeckColors.Dim, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            LazyColumn(modifier = if (stale) Modifier.alpha(STALE_ALPHA) else Modifier) {
                items(sessions, key = { it.name }) { session ->
                    RailRow(
                        session = session,
                        letter = letters[session.name],
                        isSelected = session.name == selected,
                        onClick = { onSelect(session.name) },
                        onLongClick = { onLongPress(session.name) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RailRow(session: SessionView, letter: Char?, isSelected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) DeckColors.SelectedRow else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        StatusDot(session.status)
        Text(
            text = if (letter != null) "[$letter] ${session.name}" else session.name,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
