package com.chuatzeyee.deck.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** The host list's delete / re-trust confirmations (B5) - nothing happens until "Delete" or
 * "Re-trust" is pressed; dismissing or "Cancel" leaves the host untouched. */
@Composable
fun HostDialogs(dialog: HostDialog?, onConfirmDelete: () -> Unit, onConfirmRetrust: () -> Unit, onDismiss: () -> Unit) {
    when (dialog) {
        null -> Unit
        is HostDialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { Button(onClick = onConfirmDelete) { Text("Delete") } },
            dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
            text = { Text(deleteHostMessage(dialog.host.name)) },
        )
        is HostDialog.ConfirmRetrust -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { Button(onClick = onConfirmRetrust) { Text("Re-trust") } },
            dismissButton = { Button(onClick = onDismiss) { Text("Cancel") } },
            text = { Text(retrustMessage(dialog.host.name, dialog.change)) },
        )
    }
}
