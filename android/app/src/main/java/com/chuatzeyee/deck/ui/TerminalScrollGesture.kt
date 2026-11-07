package com.chuatzeyee.deck.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/**
 * Swipe-to-scroll (fix round 1, important #5): termlib consumes vertical drags for its own
 * gestures at [PointerEventPass.Main], so a detector running on that same pass never wins the
 * drag in practice. Running at the earlier [PointerEventPass.Initial] instead lets this parent
 * "steal" the drag once it clears touch slop - termlib's own `Main`-pass detector then sees the
 * change already consumed. Below slop, nothing is consumed, so a tap still reaches termlib
 * untouched. [rowPx] is the drag distance (px) counted as one tmux history row; [onScrollStart]
 * fires once per drag, then [onScroll] fires per row crossed - dragging down (`up = true`)
 * reveals older output, matching tmux copy-mode's own sense of scrolling back.
 */
suspend fun PointerInputScope.detectTerminalScroll(
    rowPx: Float,
    onScrollStart: () -> Unit,
    onScroll: (up: Boolean, lines: Int) -> Unit,
) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    val slop = viewConfiguration.touchSlop
    var traveled = 0f
    var armed = false
    var accumulated = 0f
    while (true) {
        val change = awaitPointerEvent(pass = PointerEventPass.Initial).changes.firstOrNull() ?: break
        val dy = change.positionChange().y
        if (!armed) {
            traveled += dy
            if (abs(traveled) > slop) {
                armed = true
                accumulated = traveled
                onScrollStart()
            }
        } else {
            accumulated += dy
        }
        if (armed) {
            change.consume()
            val lines = (accumulated / rowPx).toInt()
            if (lines != 0) {
                onScroll(lines > 0, abs(lines))
                accumulated -= lines * rowPx
            }
        }
        if (!change.pressed) break
    }
}
