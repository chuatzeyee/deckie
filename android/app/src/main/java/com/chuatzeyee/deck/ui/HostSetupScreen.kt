package com.chuatzeyee.deck.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.chuatzeyee.deck.core.Host
import com.chuatzeyee.deck.core.validateHost
import kotlinx.coroutines.launch

/** How the new host's SSH key is being provisioned, in the order the form reveals them. */
private sealed interface KeyMode {
    data object Generate : KeyMode
    data object Import : KeyMode
}

/**
 * Lists already-configured hosts (each can jump straight to the session picker, and shows its last
 * connection error, if any) and a form to add a new one: fields, a key step (generate or import),
 * then "Test connection" - see Task 10 brief Step 2. [testState]/[draft] report
 * [onSaveHost]/[onGenerateKey]/[onImportKey]'s progress; a connection already in flight disables
 * every "Sessions"/"Test connection" button (fix round 1, important #3).
 */
@Composable
fun HostSetupScreen(
    hosts: List<Host>,
    hostErrors: Map<String, String>,
    identityChanged: Set<String>,
    draft: Draft,
    testState: TestConnectionState,
    watchEnabled: Boolean,
    onToggleWatch: (Boolean) -> Unit,
    onGenerateKey: () -> Unit,
    onImportKey: (pem: String, passphrase: CharArray?) -> Unit,
    onSaveHost: (name: String, address: String, port: String, user: String) -> Unit,
    onManageSessions: (Host) -> Unit,
    onDeleteHost: (Host) -> Unit,
    onRetrustHost: (Host) -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = testState is TestConnectionState.Connecting
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Watch in background")
            Switch(checked = watchEnabled, onCheckedChange = onToggleWatch)
        }
        if (hosts.isNotEmpty()) {
            Text("Saved hosts")
            hosts.forEach { host ->
                SavedHostRow(
                    host = host,
                    error = hostErrors[host.id],
                    identityChanged = host.id in identityChanged,
                    busy = busy,
                    onManageSessions = { onManageSessions(host) },
                    onDelete = { onDeleteHost(host) },
                    onRetrust = { onRetrustHost(host) },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        }

        Text("Add a host")
        NewHostForm(draft, testState, onGenerateKey, onImportKey, onSaveHost)
    }
}

/** One saved host: name/address, "Sessions", "Delete", and "Re-trust" while its server presents a
 * different host key than the pinned one (B5); its last connection error underneath. */
@Composable
private fun SavedHostRow(
    host: Host,
    error: String?,
    identityChanged: Boolean,
    busy: Boolean,
    onManageSessions: () -> Unit,
    onDelete: () -> Unit,
    onRetrust: () -> Unit,
) {
    Text("${host.name} (${host.address})", modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onManageSessions, enabled = !busy) { Text("Sessions") }
        if (identityChanged) Button(onClick = onRetrust, enabled = !busy) { Text("Re-trust") }
        Button(onClick = onDelete, enabled = !busy) { Text("Delete") }
    }
    error?.let { Text(it) }
}

@Composable
private fun NewHostForm(
    draft: Draft,
    testState: TestConnectionState,
    onGenerateKey: () -> Unit,
    onImportKey: (pem: String, passphrase: CharArray?) -> Unit,
    onSaveHost: (name: String, address: String, port: String, user: String) -> Unit,
) {
    // Keyed on draft.id so a fresh draft (after a successful save resets it) shows an empty form,
    // instead of carrying over the previous host's typed fields (fix round 2, minor).
    var name by remember(draft.id) { mutableStateOf("") }
    var address by remember(draft.id) { mutableStateOf("") }
    var port by remember(draft.id) { mutableStateOf("") }
    var user by remember(draft.id) { mutableStateOf("") }
    var keyMode by remember(draft.id) { mutableStateOf<KeyMode?>(null) }
    val errors = remember(name, address, port, user) { validateHost(name, address, effectivePort(port), user) }
    val keyAction = draft.keyAction
    val keyReady = keyAction is KeyActionState.Generated || keyAction is KeyActionState.Imported

    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text("Name") },
        isError = errors["name"] != null,
        supportingText = { errors["name"]?.let { Text(it) } },
    )
    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text("Address") },
        isError = errors["address"] != null,
        supportingText = { errors["address"]?.let { Text(it) } },
    )
    OutlinedTextField(
        value = port,
        onValueChange = { port = it },
        label = { Text("Port") },
        placeholder = { Text("22") },
        isError = errors["port"] != null,
        supportingText = { errors["port"]?.let { Text(it) } },
    )
    OutlinedTextField(
        value = user,
        onValueChange = { user = it },
        label = { Text("User") },
        isError = errors["user"] != null,
        supportingText = { errors["user"]?.let { Text(it) } },
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { keyMode = KeyMode.Generate; onGenerateKey() }, enabled = !keyReady) {
            Text("Generate new key")
        }
        Button(onClick = { keyMode = KeyMode.Import }, enabled = !keyReady) { Text("Import key") }
    }

    when {
        keyMode is KeyMode.Generate -> GeneratedKeySection(keyAction)
        keyMode is KeyMode.Import && !keyReady -> ImportKeySection(onImport = onImportKey)
        keyAction is KeyActionState.Imported -> Text("Key imported.")
    }
    (keyAction as? KeyActionState.Failed)?.let { Text(it.message) }

    Button(
        onClick = { onSaveHost(name, address, port, user) },
        enabled = errors.isEmpty() && keyReady && testState !is TestConnectionState.Connecting,
    ) {
        Text(if (testState is TestConnectionState.Connecting) "Connecting…" else "Test connection")
    }
    (testState as? TestConnectionState.Failed)?.let { Text(it.message) }
}

@Composable
private fun GeneratedKeySection(keyAction: KeyActionState) {
    val generated = keyAction as? KeyActionState.Generated ?: return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Text("Add this to ~/.ssh/authorized_keys on the server")
    Text(generated.publicKeyLine)
    Button(onClick = {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("public key", generated.publicKeyLine)))
        }
    }) { Text("Copy") }
}

@Composable
private fun ImportKeySection(onImport: (pem: String, passphrase: CharArray?) -> Unit) {
    var pem by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    OutlinedTextField(
        value = pem,
        onValueChange = { pem = it },
        label = { Text("Private key (PEM)") },
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
    )
    OutlinedTextField(
        value = passphrase,
        onValueChange = { passphrase = it },
        label = { Text("Passphrase (optional)") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    )
    Button(onClick = { onImport(pem, passphrase.ifBlank { null }?.toCharArray()) }, enabled = pem.isNotBlank()) {
        Text("Import")
    }
}
