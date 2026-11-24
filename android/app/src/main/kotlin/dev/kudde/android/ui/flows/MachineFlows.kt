package dev.kudde.android.ui.flows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.kudde.android.hub.HubHost
import dev.kudde.android.ui.addmachine.FormErrors
import dev.kudde.android.ui.addmachine.MachineInput
import dev.kudde.android.ui.addmachine.ValidMachine
import dev.kudde.android.ui.addmachine.derivedMachineName
import dev.kudde.android.ui.addmachine.validateMachine
import dev.kudde.android.ui.hostkey.HostKeyPrompt
import dev.kudde.android.ui.hostkey.hostKeyChange
import dev.kudde.android.ui.hostkey.hostKeyPrompt
import dev.kudde.android.ui.machines.MachinesActions
import dev.kudde.android.ui.setup.KeyChoice
import dev.kudde.android.ui.setup.MemorySetupProgress
import dev.kudde.android.ui.setup.PasswordPanel
import dev.kudde.android.ui.setup.PasswordPorts
import dev.kudde.android.ui.setup.PasswordSetup
import dev.kudde.android.ui.setup.SecretField
import dev.kudde.android.ui.setup.SetupCheckpoint
import dev.kudde.android.ui.setup.SetupPhase
import dev.kudde.android.ui.setup.SetupProgress
import dev.kudde.android.ui.setup.StringSecretField
import uniffi.kudde_mobile.HubException
import uniffi.kudde_mobile.HubInterface
import uniffi.kudde_mobile.HubSnapshot
import uniffi.kudde_mobile.MachineState
import uniffi.kudde_mobile.ReachKind

/** The app's screens; switched by state, without a navigation library. */
enum class Screen {
    /** First start: before home, Machines or any alert, until a real machine connects. */
    Welcome,

    /** A phone without a screen lock, after the welcome and before step 1. */
    LockNotice,

    /** The start screen: every agent, the ones that need you first (renders 1 and 2). */
    Agents,
    Machines,
    Setup,

    /** A machine presented a different host key: both fingerprints, and only "Forget this machine's key". */
    HostKeyChanged,

    /** The restricted authorized_keys line with its Copy button. */
    KeyCommand,
}

/**
 * Where the person is among the Machines flows, and the hub calls those flows
 * make (machine-connection spec: add machine, host keys, the restricted line).
 * Kept in Compose state so the screens follow it, and plain enough to test
 * without a composition (MachineFlowsTest).
 *
 * The host-key rules it keeps: a trust dialog exists only for a machine whose
 * state is HostKeyUnconfirmed and confirms exactly the fingerprint it showed;
 * a changed key leads to [Screen.HostKeyChanged], where the only call is
 * forgetting the old key, and only after [askForget]'s second confirm. After
 * forgetting, nothing opens the trust dialog by itself: the machine's card
 * offers it as for any first connection.
 *
 * Removing a machine asks first ([askRemove]); only the confirm's Remove
 * calls the hub, and a refusal is kept in [error] for the Machines screen.
 */
class MachineFlows(
    private val hubHost: HubHost,
    private val progress: SetupProgress = MemorySetupProgress(SetupPhase.COMPLETED),
    private val ports: PasswordPorts = PasswordPorts.None,
    field: SecretField = StringSecretField(),
    private val isDeviceSecure: () -> Boolean = { true },
) {
    var screen by mutableStateOf(Screen.Agents)
        private set

    /** The password way's state, with the field that is never saved. */
    val password = PasswordSetup(ports, field)

    /** The key-install detail open within step 2 (the two choices show when null). */
    var keyDetail by mutableStateOf<KeyChoice?>(null)
        private set

    /** First setup is not finished: the welcome, the lock notice and setup are all there is. */
    private val firstSetup: Boolean get() = progress.phase != SetupPhase.COMPLETED


    /** The machine [Screen.HostKeyChanged] or [Screen.Setup] is about (none for the Phone key row). */
    var machine by mutableStateOf<String?>(null)
        private set

    /** Only a submitted step 1 is waiting for its first result. */
    var waitingFor by mutableStateOf<String?>(null)
        private set

    var setupStep by mutableStateOf(1)
        private set
    var input by mutableStateOf(MachineInput())
        private set
    private var addedThisVisit by mutableStateOf(false)
    private var reachedThisVisit by mutableStateOf(false)
    private var setupOrigin = Screen.Agents

    val nameLocked: Boolean
        get() = current().machines.find { it.name == machine }?.let {
            it.hostKey != null || it.lastConnectedMs != null
        } ?: false

    fun changeInput(value: MachineInput) {
        input = if (nameLocked) value.copy(name = machine.orEmpty()) else value
        persist()
    }

    /** The machine whose trust dialog is open, over whichever screen is showing. */
    var trusting by mutableStateOf<String?>(null)
        private set

    /** The second confirm on [Screen.HostKeyChanged] is open. */
    var confirmingForget by mutableStateOf(false)
        private set

    /** The machine whose Remove confirm is open on the Machines screen. */
    var removing by mutableStateOf<String?>(null)
        private set

    /** Why the last hub call failed, shown where it was made. */
    var error by mutableStateOf<String?>(null)
        private set

    private fun current(): HubSnapshot = hubHost.snapshot.value

    /** The Machines screen's seams, wired to these flows. */
    fun actions(base: MachinesActions): MachinesActions = base.copy(
        onHostKey = ::openHostKey,
        onAddPhoneKey = { name -> openSetup(name, atLeast = 2) },
        onContinueSetup = { name -> openSetup(name) },
        onChangeAddress = { name ->
            openSetup(name)
            changeAddress()
        },
        onPhoneKey = { show(Screen.KeyCommand, null) },
        onAddMachine = ::openAddMachine,
        onRemove = ::askRemove,
        onConfirmRemove = ::remove,
        onCancelRemove = ::cancelRemove,
    )

    /** The Agents home's Machines button and machine notices. */
    fun openMachines() {
        if (firstSetup) return
        show(Screen.Machines, null)
    }

    /** A tapped alert: the Agents home, where its terminal opens; closes any dialog. */
    fun openAgents() {
        if (firstSetup) return
        trusting = null
        show(Screen.Agents, null)
    }

    /** Start a fresh visit; back from step 1 returns to its caller. */
    fun openAddMachine() {
        if (firstSetup) return
        beginStepOne(screen.takeIf { it == Screen.Machines } ?: Screen.Agents, MachineInput())
    }

    private fun beginStepOne(origin: Screen, fields: MachineInput) {
        setupOrigin = origin
        show(Screen.Setup, null)
        setupStep = 1
        input = fields
        addedThisVisit = false
        reachedThisVisit = false
        persist()
    }

    /** The welcome's one main action: the lock notice if the phone has none, else step 1, resuming kept fields. */
    fun startFirstSetup() {
        if (screen != Screen.Welcome) return
        progress.phase = SetupPhase.IN_PROGRESS
        if (!isDeviceSecure()) {
            show(Screen.LockNotice, null)
            return
        }
        enterFirstSetup()
    }

    /** The lock notice's return from Android's settings: continues only once a lock exists. */
    fun lockNoticeContinue() {
        if (screen != Screen.LockNotice || !isDeviceSecure()) return
        enterFirstSetup()
    }

    private fun enterFirstSetup() {
        val kept = progress.checkpoint
        if (kept != null) restoreCheckpoint(kept) else beginStepOne(Screen.Agents, MachineInput())
        persist()
    }

    /** Every start before a real connection: the welcome, or the step the person left. */
    private fun resumeFirstSetup() {
        val kept = progress.checkpoint
        if (progress.phase == SetupPhase.IN_PROGRESS && kept != null) {
            restoreCheckpoint(kept)
        } else {
            screen = Screen.Welcome
        }
    }

    private fun restoreCheckpoint(kept: SetupCheckpoint) {
        screen = Screen.Setup
        machine = kept.machine?.takeIf { name -> current().machines.any { it.name == name } }
        setupStep = kept.step.coerceIn(1, 3)
        input = MachineInput(kept.name, kept.host, kept.port, kept.user)
        addedThisVisit = kept.added
        reachedThisVisit = kept.reached
        setupOrigin = runCatching { Screen.valueOf(kept.origin) }.getOrDefault(Screen.Agents)
        password.newVisit()
        keyDetail = kept.choice
        if (kept.choice == KeyChoice.PASSWORD && setupStep == 2) chooseKey(KeyChoice.PASSWORD)
    }

    /** An update with no record: completed only by a machine that has actually connected. */
    private fun inferCompletion(snapshot: HubSnapshot) {
        if (progress.phase == SetupPhase.UNSET && snapshot.machines.any { it.lastConnectedMs != null }) {
            progress.phase = SetupPhase.COMPLETED
            progress.checkpoint = null
            if (screen == Screen.Welcome) screen = Screen.Agents
        }
    }

    /** Only steps 1 and 2 are kept: step 3 means connected, and step 4 is never resumed. */
    private fun persist() {
        if (screen != Screen.Setup) return
        if (setupStep >= 3) {
            progress.checkpoint = null
            return
        }
        progress.checkpoint = SetupCheckpoint(
            machine = machine, step = setupStep, choice = keyDetail, origin = setupOrigin.name,
            name = input.name, host = input.host, port = input.port, user = input.user,
            added = addedThisVisit, reached = reachedThisVisit,
        )
    }

    /** Step 4's routes on, and step 3's way to the home: setup is over (first setup completed at step 3). */
    fun finishSetup() {
        if (screen == Screen.Setup && setupStep >= 3) show(Screen.Agents, null)
    }

    /** Step 3's "Continue"; step 4 is reached nowhere else. */
    fun continueToAlerts() {
        if (screen == Screen.Setup && setupStep == 3) setupStep = 4
    }

    /** One of step 2's two choices, from the step itself. */
    fun chooseKey(choice: KeyChoice) {
        val name = machine ?: return
        if (screen != Screen.Setup || setupStep != 2) return
        // After "still refused" the password is not offered again in this visit.
        if (choice == KeyChoice.PASSWORD && password.unavailable) return
        password.close()
        keyDetail = choice
        if (choice == KeyChoice.MANUAL) {
            persist()
            return
        }
        val m = current().machines.find { it.name == name } ?: return
        when {
            hostKeyPrompt(current(), name) != null -> compareFingerprint()
            m.hostKey != null -> probe(m.hostKey!!)
        }
        persist()
    }

    /** A Machines card's choice for a listed machine: opens step 2 with that detail. */
    fun openKeyChoice(name: String, choice: KeyChoice) {
        openSetup(name, atLeast = 2)
        if (screen == Screen.Setup && setupStep == 2) chooseKey(choice)
    }

    private fun probe(pinned: String) {
        val m = current().machines.find { it.name == machine } ?: return
        password.open(m.host, m.port.toInt(), m.user, pinned, onChangedKey = ::retry)
    }

    /** "Add the key": the field is emptied before the call starts. */
    fun addKey() {
        val m = current().machines.find { it.name == machine }?.takeIf { it.hostKey != null } ?: return
        if (keyDetail != KeyChoice.PASSWORD) return
        password.addKey(
            m.host, m.port.toInt(), m.user, m.hostKey!!,
            onDone = ::retry,
            onChangedKey = ::retry,
        )
    }

    /** Resume a listed machine. Card desk actions explicitly open step 2. */
    fun openSetup(name: String, atLeast: Int = 1) {
        val m = current().machines.find { it.name == name } ?: return
        setupOrigin = if (firstSetup) Screen.Agents else screen.takeIf { it == Screen.Agents } ?: Screen.Machines
        password.newVisit()
        show(Screen.Setup, name)
        setupStep = when {
            m.state is MachineState.Connected -> 3
            m.hostKey == null && m.state !is MachineState.HostKeyUnconfirmed -> 1
            else -> 2
        }.coerceAtLeast(atLeast.coerceIn(1, 2))
        val name = if (m.hostKey == null && m.lastConnectedMs == null && m.name == derivedMachineName(m.host)) "" else m.name
        input = MachineInput(name, m.host, m.port.toString(), m.user)
        addedThisVisit = false
        reachedThisVisit = setupStep >= 2
        if (m.state is MachineState.HostKeyMismatch) show(Screen.HostKeyChanged, name) else persist()
    }

    /** The only deliberate backward step; preserves this visit's ownership. */
    fun changeAddress() {
        if (screen != Screen.Setup || machine == null) return
        setupStep = 1
        keyDetail = null
        password.close()
        trusting = null
        waitingFor = null
        persist()
    }

    fun retry() {
        call { it.retry() }
    }

    /** The hub owns grace. Neither deadlines nor the number of snapshots matter. */
    fun failure(snapshot: HubSnapshot): MachineState? {
        val m = snapshot.machines.find { it.name == machine } ?: return null
        // The key not being accepted yet is what the password way fixes: its panel says so.
        if (m.state == MachineState.KeyRejected && keyDetail == KeyChoice.PASSWORD) return null
        return when (val state = m.state) {
            is MachineState.Unreachable -> if (
                state.kind == ReachKind.CORE_STARTUP_CLOSED && m.startupRetryPending
            ) null else state
            MachineState.Connecting, MachineState.Paused,
            is MachineState.Connected, is MachineState.HostKeyUnconfirmed -> null
            else -> state
        }
    }

    private fun show(next: Screen, about: String?) {
        screen = next
        keyDetail = null
        password.close()
        machine = about
        waitingFor = null
        confirmingForget = false
        removing = null
        error = null
    }

    /** The card opens the desk step; comparison is a separate deliberate action. */
    fun openHostKey(name: String) {
        when (current().machines.find { it.name == name }?.state) {
            is MachineState.HostKeyUnconfirmed -> openSetup(name, atLeast = 2)
            is MachineState.HostKeyMismatch -> show(Screen.HostKeyChanged, name)
            else -> Unit
        }
    }

    fun compareFingerprint() {
        val name = machine ?: return
        if (screen != Screen.Setup || setupStep != 2 || hostKeyPrompt(current(), name) == null) return
        error = null
        trusting = name
    }

    /** The trust dialog's content, or null when no dialog should show. */
    fun trustPrompt(snapshot: HubSnapshot): HostKeyPrompt? = trusting?.let { hostKeyPrompt(snapshot, it) }

    /** Follow settlements forward, closing dialogs whose keys are no longer presented. */
    fun onSnapshot(snapshot: HubSnapshot) {
        inferCompletion(snapshot)
        if (screen == Screen.Setup) {
            snapshot.machines.find { it.name == machine }?.let { m ->
                when (m.state) {
                    is MachineState.Connected -> {
                        if (setupStep < 3) {
                            setupStep = 3
                            keyDetail = null
                            password.close()
                        }
                        reachedThisVisit = true
                        waitingFor = null
                        // A real connection at step 3 completes first setup.
                        progress.phase = SetupPhase.COMPLETED
                        progress.checkpoint = null
                    }
                    is MachineState.HostKeyMismatch -> show(Screen.HostKeyChanged, m.name)
                    is MachineState.HostKeyUnconfirmed -> {
                        if (setupStep < 2) setupStep = 2
                        reachedThisVisit = true
                        waitingFor = null
                    }
                    else -> {
                        if (m.state == MachineState.KeyRejected) password.phoneKeyStillRefused()
                        // A confirmed pin also proves that the machine was reached,
                        // including before its first successful core connection.
                        if (m.hostKey != null) {
                            if (setupStep < 2) setupStep = 2
                            reachedThisVisit = true
                        }
                        if (m.state != MachineState.Connecting && m.state != MachineState.Paused) waitingFor = null
                    }
                }
            }
            persist()
        }
        trusting?.let { name ->
            if (hostKeyPrompt(snapshot, name) == null) {
                trusting = null
                error = null
            }
        }
        if (screen == Screen.HostKeyChanged && hostKeyChange(snapshot, machine) == null) {
            show(Screen.Machines, null)
        }
        removing?.let { name ->
            if (snapshot.machines.none { it.name == name }) removing = null
        }
    }

    /** Form seam for the later production screen; own name is never a conflict. */
    fun submit(): FormErrors {
        val value = if (nameLocked) input.copy(name = machine.orEmpty()) else input
        val errors = validateMachine(value, current().machines.map { it.name }.filter { it != machine })
        errors.valid?.let(::save)
        return errors
    }

    /** Only an unpinned, never-connected entry may be replaced to follow a name edit. */
    fun save(valid: ValidMachine) {
        val old = current().machines.find { it.name == machine }
        val listedName = if (old != null && (old.hostKey != null || old.lastConnectedMs != null)) old.name else valid.name
        val saved = if (old == null) {
            call { it.addMachine(listedName, valid.host, valid.port, valid.user) }.also {
                if (it) addedThisVisit = true
            }
        } else if (old.hostKey != null || old.lastConnectedMs != null) {
            call { it.updateMachine(old.name, valid.host, valid.port, valid.user) }
        } else {
            if (!call { it.removeMachine(old.name) }) return
            // If adding is refused, retain the typed fields and clear the removed
            // identity so the next submit can retry the add.
            machine = null
            call { it.addMachine(valid.name, valid.host, valid.port, valid.user) }
        }
        if (saved) {
            machine = listedName
            waitingFor = listedName
            input = MachineInput(if (nameLocked) listedName else input.name, valid.host, valid.port.toString(), valid.user)
        }
    }

    /** "Trust and connect": confirms the fingerprint the dialog showed, nothing else. */
    fun trust(prompt: HostKeyPrompt) {
        if (trusting != prompt.machine || trustPrompt(current()) != prompt) return
        if (call { it.confirmHostKey(prompt.machine, prompt.fingerprint) }) {
            trusting = null
            waitingFor = null
            if (keyDetail == KeyChoice.PASSWORD && screen == Screen.Setup) probe(prompt.fingerprint)
        }
    }

    /** "Cancel": nothing is trusted and the machine stays unconfirmed. */
    fun cancelTrust() {
        trusting = null
        error = null
        // A password choice that was waiting on this confirm has nothing to show.
        if (keyDetail == KeyChoice.PASSWORD && password.panel == PasswordPanel.Hidden) {
            keyDetail = null
            persist()
        }
    }

    /** "Forget this machine's key": opens the second confirm; calls nothing. */
    fun askForget() {
        if (screen == Screen.HostKeyChanged) {
            error = null
            confirmingForget = true
        }
    }

    fun cancelForget() {
        confirmingForget = false
        error = null
    }

    /** The second confirm's "Forget key": the next connection asks as a first one. */
    fun forget() {
        val name = machine
        if (screen != Screen.HostKeyChanged || !confirmingForget || name == null) return
        if (call { it.forgetHostKey(name) }) show(Screen.Machines, null)
    }

    /** The card's "Remove machine": opens the confirm; calls nothing. */
    fun askRemove(name: String) {
        error = null
        removing = name
    }

    /** Cancel, the scrim or back: nothing is removed. */
    fun cancelRemove() {
        removing = null
        error = null
    }

    /**
     * The confirm's Remove: the hub forgets the machine, its trusted host key
     * and its seen turns. The confirm closes either way; a refusal stays in [error].
     */
    fun remove() {
        val name = removing ?: return
        removing = null
        if (call { it.removeMachine(name) } && progress.checkpoint?.machine == name) progress.checkpoint = null
    }

    /** System back: closes a dialog first, then returns to Machines, then to the Agents home; false there. */
    fun back(): Boolean {
        return when {
            trusting != null -> {
                cancelTrust()
                true
            }
            confirmingForget -> {
                cancelForget()
                true
            }
            removing != null -> {
                cancelRemove()
                true
            }
            screen == Screen.Welcome -> false
            screen == Screen.LockNotice -> {
                progress.phase = SetupPhase.WELCOME
                show(Screen.Welcome, null)
                true
            }
            screen == Screen.Setup -> backInSetup()
            screen == Screen.Machines -> {
                show(Screen.Agents, null)
                true
            }
            screen != Screen.Agents -> {
                show(Screen.Machines, null)
                true
            }
            else -> false
        }
    }

    /** Back inside setup: a key detail to its two choices, else one step back (design D6). */
    private fun backInSetup(): Boolean {
        if (keyDetail != null) {
            keyDetail = null
            password.close()
            persist()
            return true
        }
        when (setupStep) {
            4 -> setupStep = 3
            3 -> show(Screen.Agents, null)
            2 -> {
                setupStep = 1
                persist()
            }
            else -> {
                var kept = machine
                val m = current().machines.find { it.name == machine }
                if (addedThisVisit && !reachedThisVisit && m?.hostKey == null && m?.lastConnectedMs == null) {
                    val name = machine
                    if (name != null) {
                        if (!call { it.removeMachine(name) }) return true
                        kept = null
                    }
                }
                machine = kept
                persist()
                if (firstSetup) {
                    progress.phase = SetupPhase.WELCOME
                    show(Screen.Welcome, null)
                } else {
                    show(setupOrigin, null)
                }
            }
        }
        return true
    }

    /** Runs a hub call; on failure keeps the reason in [error] and returns false. */
    private inline fun call(block: (HubInterface) -> Unit): Boolean {
        val hub = hubHost.hub ?: run {
            hubHost.ensureStarted()
            hubHost.hub
        }
        if (hub == null) {
            error = hubHost.startError.value ?: "the connection core is not running"
            return false
        }
        return try {
            block(hub)
            error = null
            true
        } catch (e: HubException) {
            error = hubErrorText(e)
            false
        }
    }

    init {
        inferCompletion(current())
        if (firstSetup) resumeFirstSetup()
    }

    companion object {
        /** Keeps the flow across rotation and process death (the error is not kept). */
        fun saver(
            hubHost: HubHost,
            progress: SetupProgress = MemorySetupProgress(SetupPhase.COMPLETED),
            ports: PasswordPorts = PasswordPorts.None,
            field: SecretField = StringSecretField(),
            isDeviceSecure: () -> Boolean = { true },
        ): Saver<MachineFlows, Any> = listSaver(
            save = {
                // No password, no password state: the detail's name only, never its text.
                listOf(
                    it.screen.name, it.machine, it.waitingFor, it.trusting, it.confirmingForget, it.removing,
                    it.setupStep, it.addedThisVisit, it.reachedThisVisit, it.setupOrigin.name,
                    it.input.name, it.input.host, it.input.port, it.input.user, it.keyDetail?.name,
                )
            },
            restore = { saved ->
                MachineFlows(hubHost, progress, ports, field, isDeviceSecure).apply {
                    screen = Screen.valueOf(saved[0] as String)
                    machine = saved[1] as String?
                    waitingFor = saved[2] as String?
                    trusting = saved[3] as String?
                    confirmingForget = saved[4] as Boolean
                    removing = saved[5] as String?
                    setupStep = saved[6] as Int
                    addedThisVisit = saved[7] as Boolean
                    reachedThisVisit = saved[8] as Boolean
                    setupOrigin = Screen.valueOf(saved[9] as String)
                    input = MachineInput(saved[10] as String, saved[11] as String, saved[12] as String, saved[13] as String)
                    // Step 4 is never resumed: the home opens, and the card holds the alerts state.
                    if (screen == Screen.Setup && setupStep == 4) screen = Screen.Agents
                    val detail = (saved[14] as String?)?.let { KeyChoice.valueOf(it) }
                    keyDetail = detail
                    // The password detail comes back at its confirm or probe, with an empty field.
                    if (screen == Screen.Setup && setupStep == 2 && detail == KeyChoice.PASSWORD) chooseKey(KeyChoice.PASSWORD)
                }
            },
        )
    }
}

/** The hub's refusal in words (its Rust Display text; the form's own checks normally come first). */
fun hubErrorText(e: HubException): String = when (e) {
    is HubException.Invalid -> e.detail
    is HubException.Duplicate -> "a machine named \"${e.name}\" is already listed"
    is HubException.NotFound -> "no machine named \"${e.name}\""
    is HubException.Unreadable -> "not a machine list: ${e.detail}"
    is HubException.Storage -> e.detail
}
