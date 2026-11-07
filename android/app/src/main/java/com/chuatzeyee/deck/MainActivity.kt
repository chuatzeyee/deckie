package com.chuatzeyee.deck

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.chuatzeyee.deck.poll.LinkState
import com.chuatzeyee.deck.ui.AppViewModel
import com.chuatzeyee.deck.ui.DeckColors
import com.chuatzeyee.deck.ui.DeckScreen
import com.chuatzeyee.deck.ui.DeckTheme
import com.chuatzeyee.deck.ui.HostDialogs
import com.chuatzeyee.deck.ui.HostSetupScreen
import com.chuatzeyee.deck.ui.Screen
import com.chuatzeyee.deck.ui.SessionMenuActions
import com.chuatzeyee.deck.ui.SessionPickerScreen
import com.chuatzeyee.deck.ui.TestConnectionState
import com.chuatzeyee.deck.ui.firstUseMessage
import com.chuatzeyee.deck.ui.hostKeyMismatchMessage

class MainActivity : ComponentActivity() {

    private lateinit var model: AppViewModel

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[AppViewModel::class.java]
        // onNewIntent below always handles a later tap; this only needs to handle a cold launch
        // straight from a notification, not a config-change recreation re-delivering the same
        // intent a second time (fix round 1, minor).
        if (savedInstanceState == null) model.openFromNotification(intent)
        enableEdgeToEdge() // R19: content draws full-bleed; safeDrawing insets below keep it clear of system bars
        setContent {
            DeckTheme {
                Surface(
                    modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
                    color = DeckColors.Background,
                ) {
                    DeckRoot(model, onToggleWatch = ::handleWatchToggle, onDeckScreenShown = ::askWatchPermissionsOnceIfNeeded)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.openFromNotification(intent)
    }

    /** Ruling R15: request `POST_NOTIFICATIONS` and a battery-optimization exemption the moment
     * the user turns "Watch in background" on - not before, so Deck never asks for either
     * permission unless the feature is actually wanted. */
    private fun handleWatchToggle(enabled: Boolean) {
        if (enabled) requestWatchPermissions()
        model.setWatchEnabled(enabled)
    }

    private fun requestWatchPermissions() {
        if (notificationPermissionMissing()) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (batteryExemptionMissing()) {
            // Some OEMs refuse this intent outright; a failure here just means the user keeps
            // seeing the ordinary battery-optimization prompt later, not a crash.
            runCatching {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }.onFailure { Log.w("Deck", "Could not open the battery-optimization exemption screen", it) }
        }
    }

    private fun notificationPermissionMissing(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun batteryExemptionMissing(): Boolean {
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(packageName) == false
    }

    /** Ruling R15 default path (fix round 1): the toggle defaults ON, so a user who never touches
     * it would otherwise never be asked for either permission. The first time the Deck screen is
     * shown with the toggle on and something still missing, ask once and remember it via
     * [AppViewModel.markPermissionsAsked] - re-asking stays available by flipping the toggle off
     * and on, which still always goes through [handleWatchToggle]. */
    private fun askWatchPermissionsOnceIfNeeded() {
        if (notificationPermissionMissing() || batteryExemptionMissing()) requestWatchPermissions()
        model.markPermissionsAsked()
    }
}

@Composable
private fun DeckRoot(viewModel: AppViewModel, onToggleWatch: (Boolean) -> Unit, onDeckScreenShown: () -> Unit) {
    val hosts by viewModel.hosts.collectAsState()
    val hostErrors by viewModel.hostErrors.collectAsState()
    val hostDialog by viewModel.hostAdmin.dialog.collectAsState()
    val identityChanges by viewModel.hostAdmin.identityChanges.collectAsState()
    val testState by viewModel.testState.collectAsState()
    val draft by viewModel.draft.collectAsState()
    val pendingTrust by viewModel.pendingTrust.collectAsState()
    val link by viewModel.link.collectAsState()
    val selected by viewModel.selected.collectAsState()
    val activeHost by viewModel.activeHost.collectAsState()
    val autoStarted by viewModel.autoStarted.collectAsState()
    val terminalState by viewModel.terminalState.collectAsState()
    val answerRefusal by viewModel.answerRefusal.collectAsState()
    val answerInFlight by viewModel.answerInFlight.collectAsState()
    val menuState by viewModel.menuState.collectAsState()
    val actionInFlight by viewModel.actionInFlight.collectAsState()
    val watchEnabled by viewModel.watchEnabled.collectAsState()
    val watchAskState by viewModel.watchAskState.collectAsState()
    val pendingOpen by viewModel.pendingOpen.collectAsState()
    var screen by remember { mutableStateOf<Screen>(Screen.HostSetup) }
    var identityAcknowledged by remember { mutableStateOf(false) }

    LaunchedEffect(testState) {
        val connected = testState as? TestConnectionState.Connected ?: return@LaunchedEffect
        screen = Screen.SessionPicker(connected.host.id)
    }
    LaunchedEffect(link) {
        if (link !is LinkState.IdentityChanged) identityAcknowledged = false
    }
    // R17: once auto-started, jump straight to the Deck screen - fires at most once (autoStarted
    // is set exactly once by the ViewModel), so the back button never bounces the user back here.
    LaunchedEffect(autoStarted) {
        autoStarted?.let { hostId -> screen = Screen.Deck(hostId) }
    }
    // An alert notification's tap (Task 13): jump to its host/session. Assumes that host already
    // has a live HostLink - true in v1's single-active-host model, since WatchService and the R17
    // auto-start above pick the same host (DeckRuntime.eligibleHost's rule).
    LaunchedEffect(pendingOpen) {
        pendingOpen?.let { open ->
            screen = Screen.Deck(open.hostId)
            viewModel.select(open.session)
            viewModel.clearPendingOpen()
        }
    }
    // R15 default path (fix round 1/2): the toggle defaults on, so ask once - the first time the
    // Deck screen is actually shown with it on - rather than never asking at all. watchAskState is
    // null until its two DataStore-backed values have actually loaded, so this never fires on a
    // stateIn placeholder (fix round 2, minor).
    LaunchedEffect(screen, watchAskState) {
        val ask = watchAskState ?: return@LaunchedEffect
        if (screen is Screen.Deck && ask.enabled && !ask.asked) onDeckScreenShown()
    }

    pendingTrust?.let { trust ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveTrust(false) },
            confirmButton = { Button(onClick = { viewModel.resolveTrust(true) }) { Text("Trust") } },
            dismissButton = { Button(onClick = { viewModel.resolveTrust(false) }) { Text("Don't trust") } },
            text = { Text(firstUseMessage(trust.address, trust.keyType, trust.fingerprint)) },
        )
    }

    HostDialogs(
        dialog = hostDialog,
        onConfirmDelete = viewModel.hostAdmin::confirmDelete,
        onConfirmRetrust = viewModel.hostAdmin::confirmRetrust,
        onDismiss = viewModel.hostAdmin::dismiss,
    )

    (link as? LinkState.IdentityChanged)?.takeIf { !identityAcknowledged }?.let { changed ->
        AlertDialog(
            onDismissRequest = { identityAcknowledged = true },
            confirmButton = { Button(onClick = { identityAcknowledged = true }) { Text("OK") } },
            text = { Text(hostKeyMismatchMessage(changed.expected, changed.got)) },
        )
    }

    when (val current = screen) {
        Screen.HostSetup -> HostSetupScreen(
            hosts = hosts,
            hostErrors = hostErrors,
            identityChanged = identityChanges.keys,
            draft = draft,
            testState = testState,
            watchEnabled = watchEnabled,
            onToggleWatch = onToggleWatch,
            onGenerateKey = viewModel::generateKey,
            onImportKey = viewModel::importKey,
            onSaveHost = viewModel::saveHost,
            onManageSessions = { host ->
                viewModel.clearTestState()
                viewModel.manageSessions(host)
            },
            onDeleteHost = viewModel.hostAdmin::requestDelete,
            onRetrustHost = { host ->
                viewModel.clearTestState()
                viewModel.hostAdmin.requestRetrust(host)
            },
        )

        is Screen.SessionPicker -> {
            val connected = (testState as? TestConnectionState.Connected)?.takeIf { it.host.id == current.hostId }
            val host = connected?.host ?: hosts.firstOrNull { it.id == current.hostId }
            LaunchedEffect(host) {
                if (host == null) screen = Screen.HostSetup
            }
            if (host != null) {
                SessionPickerScreen(
                    host = host,
                    result = connected?.result,
                    onSave = { hostId, names ->
                        viewModel.chooseSessions(hostId, names)
                        viewModel.clearTestState()
                        screen = Screen.Deck(hostId)
                    },
                    onBack = {
                        viewModel.clearTestState()
                        screen = Screen.HostSetup
                    },
                )
            }
        }

        is Screen.Deck -> {
            val host = activeHost?.takeIf { it.id == current.hostId } ?: hosts.firstOrNull { it.id == current.hostId }
            LaunchedEffect(host) {
                if (host == null) screen = Screen.HostSetup
            }
            if (host != null) {
                DeckScreen(
                    host = host,
                    link = link,
                    selected = selected,
                    terminalState = terminalState,
                    answerRefusal = answerRefusal,
                    answerInFlight = answerInFlight,
                    staleAfterMs = viewModel.staleAfterMs,
                    menuState = menuState,
                    actionInFlight = actionInFlight,
                    menuActions = SessionMenuActions(
                        onOpen = viewModel::openMenu,
                        onOpenFor = viewModel::openMenuFor,
                        onDismiss = viewModel::dismissMenu,
                        onNewSessionRequest = viewModel::requestNewSession,
                        onRenameRequest = viewModel::requestRename,
                        onKillRequest = viewModel::requestKill,
                        onCreate = viewModel::confirmNewSession,
                        onRename = viewModel::confirmRename,
                        onKill = viewModel::confirmKill,
                    ),
                    onSelect = viewModel::select,
                    onBack = {
                        viewModel.leaveDeckScreen()
                        screen = Screen.HostSetup
                    },
                    onSendRaw = viewModel::sendRawToTerminal,
                    onRetryTerminal = viewModel::retryTerminal,
                    onScrollStart = viewModel::scrollStart,
                    onScroll = viewModel::scroll,
                    onAnswer = viewModel::answer,
                    onClearAnswerRefusal = viewModel::clearAnswerRefusal,
                )
            }
        }
    }
}
