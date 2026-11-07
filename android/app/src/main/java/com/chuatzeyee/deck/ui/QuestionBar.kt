package com.chuatzeyee.deck.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.chuatzeyee.deck.core.Question

/**
 * One orange-outlined button per option of [question] ("1 Yes", "2 No", …), sitting under the
 * terminal when the selected session has a live question. Purely presentational - [onAnswer]
 * (index into [Question.options]) drives [com.chuatzeyee.deck.core.Sender.answer]; any
 * [com.chuatzeyee.deck.core.AnswerResult.Refused] is the caller's job to surface. [enabled] is false
 * while an answer is already in flight, so a double tap can't send a second one.
 */
@Composable
fun QuestionBar(question: Question, enabled: Boolean, onAnswer: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        question.options.forEachIndexed { index, option ->
            OutlinedButton(
                onClick = { onAnswer(index) },
                enabled = enabled,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = DeckColors.Attention),
                border = BorderStroke(1.dp, DeckColors.Attention),
            ) {
                Text("${index + 1} $option")
            }
        }
    }
}
