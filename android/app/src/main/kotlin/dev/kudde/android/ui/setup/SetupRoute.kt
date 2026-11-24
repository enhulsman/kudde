package dev.kudde.android.ui.setup

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.kudde.android.KuddeApp
import dev.kudde.android.key.KeyManager
import dev.kudde.android.push.timeOfDay
import dev.kudde.android.ui.addmachine.FormErrors
import dev.kudde.android.ui.addmachine.derivedMachineName
import dev.kudde.android.ui.addmachine.validateMachine
import dev.kudde.android.ui.components.rememberWallClock
import dev.kudde.android.ui.flows.MachineFlows
import dev.kudde.android.ui.hostkey.hostKeyPrompt
import dev.kudde.android.ui.machines.PhoneAlerts
import dev.kudde.android.ui.machines.appSettingsIntent
import dev.kudde.android.ui.machines.notificationSettingsIntent
import dev.kudde.android.ui.machines.reachText
import uniffi.kudde_mobile.HubException
import uniffi.kudde_mobile.MachineState
import uniffi.kudde_mobile.setupCommand

/** The setup guide, one link out (design D10): the README's "Setting up". */
private const val GUIDE_URL = "https://github.com/enhulsman/kudde#setting-up"

private fun notificationsAllowed(context: Context): Boolean =
    context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

/** Opens [url] in a browser; false when no browser answers. */
private fun openGuide(context: Context): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GUIDE_URL)))
    true
} catch (_: ActivityNotFoundException) {
    false
}

/** The welcome and the lock notice (before any machine), fed by [flows]. */
@Composable
fun WelcomeRoute(flows: MachineFlows) {
    val context = LocalContext.current
    WelcomeScreen(onStart = flows::startFirstSetup, onGuide = { openGuide(context) })
}

@Composable
fun LockNoticeRoute(flows: MachineFlows) {
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { flows.lockNoticeContinue() }
    LockNoticeScreen(
        onAction = { openScreenLockSettings { context.startActivity(it) } },
        onBack = { flows.back() },
    )
}

/** Steps 1 to 4 for the machine [flows] is about, from the hub's snapshot. */
@Composable
fun SetupRoute(app: KuddeApp, flows: MachineFlows) {
    val snapshot by app.hubHost.snapshot.collectAsState()
    val context = LocalContext.current
    val res = context.resources
    val machine = snapshot.machines.find { it.name == flows.machine }
    when (flows.setupStep) {
        1 -> AddressRoute(flows, snapshot, machine)
        2 -> KeyRoute(app, flows, snapshot, machine)
        3 -> ConnectedStep(
            ConnectedUi(flows.machine.orEmpty(), (machine?.state as? MachineState.Connected)?.version, snapshot.agents.count { it.machine == flows.machine }),
            onBack = { flows.back() },
            onContinue = flows::continueToAlerts,
        )
        else -> AlertsRoute(app, flows, snapshot)
    }
}

@Composable
private fun AddressRoute(flows: MachineFlows, snapshot: uniffi.kudde_mobile.HubSnapshot, machine: uniffi.kudde_mobile.MachineView?) {
    val res = LocalContext.current.resources
    var errors by remember { mutableStateOf<FormErrors?>(null) }
    val clock = rememberWallClock()
    // When the first result started to be awaited: A9 follows after five seconds.
    var waitingSince by remember { mutableStateOf<Long?>(null) }
    val waiting = flows.waitingFor
    LaunchedEffect(waiting) { waitingSince = if (waiting != null) clock.value else null }
    val input = flows.input
    val taken = snapshot.machines.map { it.name }.filter { it != flows.machine }
    val reach = (flows.failure(snapshot) as? MachineState.Unreachable)?.let { machine?.let { m -> reachText(m, res) } }
    val address = "${input.host.trim()}:${input.port.trim()}"
    val ui = AddressUi(
        input = input,
        errors = errors,
        status = addressStatus(waiting, address, waiting != null && waitingSince?.let { isSlow(it, clock.value) } == true, reach),
        error = flows.error,
        nameLocked = flows.nameLocked,
        derivedName = derivedMachineName(input.host.trim()).takeIf { input.name.isBlank() && it.isNotEmpty() },
    )
    val unchanged = machine != null && machine.host == input.host.trim() && machine.port.toString() == input.port.trim() && machine.user == input.user.trim()
    AddressStep(
        ui,
        AddressActions(
            onBack = { flows.back() },
            onChange = {
                flows.changeInput(it)
                if (errors != null) errors = validateMachine(flows.input, taken)
            },
            onContinue = { errors = flows.submit() },
            onTryAgain = { if (unchanged) flows.retry() else errors = flows.submit() },
        ),
    )
}

@Composable
private fun KeyRoute(app: KuddeApp, flows: MachineFlows, snapshot: uniffi.kudde_mobile.HubSnapshot, machine: uniffi.kudde_mobile.MachineView?) {
    val context = LocalContext.current
    val res = context.resources
    val name = flows.machine.orEmpty()
    var copied by remember(name) { mutableStateOf(false) }
    // The key type the machine presented, remembered once seen: the command prints that one host key.
    var algorithm by remember(name) { mutableStateOf<String?>(null) }
    (machine?.state as? MachineState.HostKeyUnconfirmed)?.let { algorithm = it.algorithm }
    val manual = flows.keyDetail == KeyChoice.MANUAL
    val command = if (manual) {
        remember(name, algorithm) {
            runCatching { setupCommand(app.keyManager.publicKey(), KeyManager.COMMENT, algorithm) }.getOrNull()
        }
    } else null
    val state = machine?.state
    val reach = machine?.let { reachText(it, res) }?.takeIf { flows.failure(snapshot) != null }
    val ui = KeyUi(
        machine = name,
        user = machine?.user.orEmpty(),
        notice = keyNotice(state ?: MachineState.Connecting, reach),
        describe = flows.keyDetail == null,
        command = command,
        copied = copied,
        canCompare = hostKeyPrompt(snapshot, name) != null,
        passwordOffered = !flows.password.unavailable,
        canTryAgain = state == MachineState.KeyRejected || state == MachineState.NotInstalled || state is MachineState.Unreachable,
        connecting = state == MachineState.Connecting && hostKeyPrompt(snapshot, name) == null,
    )
    KeyStep(
        ui,
        KeyActions(
            onBack = { flows.back() },
            onChoose = flows::chooseKey,
            onCopy = {
                command?.let {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(setupCommandClip(it, res))
                    copied = true
                }
            },
            onShare = { command?.let { shareSetupCommand(it, res) { intent -> context.startActivity(intent) } } },
            onCompare = flows::compareFingerprint,
            onTryAgain = flows::retry,
            onChangeAddress = flows::changeAddress,
        ),
    )
}

@Composable
private fun AlertsRoute(app: KuddeApp, flows: MachineFlows, snapshot: uniffi.kudde_mobile.HubSnapshot) {
    val context = LocalContext.current
    val activity = context as? Activity
    val clock = rememberWallClock()
    val name = flows.machine.orEmpty()
    val machine = snapshot.machines.find { it.name == name } ?: return
    val alertChanges by app.pushStore.changes.collectAsState()
    var permitted by remember { mutableStateOf(notificationsAllowed(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permitted = notificationsAllowed(context) }
    // "Turn on alerts" was tapped on this step: only then does the test go by itself.
    var turnedOnHere by remember(name) { mutableStateOf(false) }
    var registerAfterAsking by remember { mutableStateOf(false) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitted = notificationsAllowed(context)
        if (registerAfterAsking) {
            registerAfterAsking = false
            if (permitted) activity?.let(app::turnOnAlerts)
        }
    }
    val store = app.pushStore
    val phone = remember(alertChanges, permitted, snapshot, context) {
        PhoneAlerts(
            registered = store.registration != null,
            setup = store.setup,
            permitted = permitted,
            machines = snapshot.machines.associate { it.name to store.machine(it.name) },
            timeText = timeOfDay(context),
        )
    }
    fun sendTest() {
        val hub = app.hubHost.hub ?: return
        try {
            hub.sendTest(name)
            store.testAsked(name, System.currentTimeMillis())
        } catch (_: HubException) {
            // Not connected any more: the state follows the connection.
        }
    }
    // The registration written to this machine's kudde-push document, then one test, once.
    LaunchedEffect(phone, machine.alerts) {
        if (shouldSendTest(turnedOnHere, phone, machine)) sendTest()
    }
    val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    fun turnOn() {
        turnedOnHere = true
        if (canAsk && !notificationsAllowed(context) && !store.permissionAsked) {
            store.permissionAsked = true
            registerAfterAsking = true
            askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (notificationsAllowed(context) || !canAsk) {
            activity?.let(app::turnOnAlerts)
        }
        // Asked and refused before: the step already says "not allowed" with "Allow".
    }
    val agents = snapshot.agents.count { it.machine == name }
    AlertsStep(
        AlertsUi(name, alertsState(phone, machine, clock.value), agents),
        AlertsActions(
            onBack = { flows.back() },
            onTurnOn = ::turnOn,
            onAllow = {
                try {
                    context.startActivity(notificationSettingsIntent(context.packageName))
                } catch (_: ActivityNotFoundException) {
                    context.startActivity(appSettingsIntent(context.packageName))
                }
            },
            onSendTest = ::sendTest,
            onGuide = { openGuide(context) },
            onShowAgents = flows::finishSetup,
            onStartTerminal = {
                flows.finishSetup()
                app.newTerminal.tapNew()
                app.newTerminal.chooseMachine(name)
            },
            onDone = flows::finishSetup,
        ),
    )
}
