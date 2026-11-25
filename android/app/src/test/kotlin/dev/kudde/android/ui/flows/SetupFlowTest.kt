package dev.kudde.android.ui.flows

import androidx.compose.runtime.saveable.SaverScope
import dev.kudde.android.hub.FakeHub
import dev.kudde.android.hub.HubHost
import dev.kudde.android.ui.addmachine.MachineInput
import dev.kudde.android.ui.addmachine.ValidMachine
import dev.kudde.android.ui.machines.MachinesActions
import dev.kudde.android.ui.setup.KeyChoice
import dev.kudde.android.ui.setup.MemorySetupProgress
import dev.kudde.android.ui.setup.PasswordPanel
import dev.kudde.android.ui.setup.PasswordPorts
import dev.kudde.android.ui.setup.SecretField
import dev.kudde.android.ui.setup.SetupCheckpoint
import dev.kudde.android.ui.setup.SetupHandle
import dev.kudde.android.ui.setup.SetupPhase
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.kudde_mobile.HubListener
import uniffi.kudde_mobile.HubSnapshot
import uniffi.kudde_mobile.MachineState
import uniffi.kudde_mobile.MachineView
import uniffi.kudde_mobile.ProbeOutcome
import uniffi.kudde_mobile.ReachKind
import uniffi.kudde_mobile.SetupOutcome

/** Four steps, Back one step, first-setup progress and the key-install choices (tasks 3.2, 3.6, 3.8, 3.9). */
class SetupFlowTest {
    private val hub = FakeHub()
    private lateinit var listener: HubListener
    private val hubHost = HubHost(createHub = { listener = it; hub }, mainExecutor = Executor { it.run() })
    private val ports = FakePorts()
    private val field = TestField()
    private var secure = true
    private var progress = MemorySetupProgress(SetupPhase.UNSET)
    private var flows = newFlows()

    init {
        hubHost.start()
    }

    private fun newFlows() = MachineFlows(hubHost, progress, ports, field, isDeviceSecure = { secure })

    private fun publish(vararg machines: MachineView): HubSnapshot {
        val s = HubSnapshot(machines.toList(), emptyList(), emptyList(), null)
        listener.onSnapshot(s)
        flows.onSnapshot(s)
        return s
    }

    private fun machine(name: String, state: MachineState) =
        MachineView(name, "$name.example.org", 22u, "dev", null, state, null)

    private val unconfirmed = MachineState.HostKeyUnconfirmed(PRESENTED, "ED25519")
    private val workstation = ValidMachine("workstation", "workstation.example.org", 22u, "dev")

    private fun completed() {
        progress = MemorySetupProgress(SetupPhase.COMPLETED)
        flows = newFlows()
    }

    private fun toStepTwo() {
        flows.startFirstSetup()
        flows.save(workstation)
        publish(machine("workstation", unconfirmed))
        assertEquals(2, flows.setupStep)
    }

    // The welcome, the lock notice, and the way out of neither.

    @Test fun freshInstallShowsTheWelcomeAndNothingElse() {
        assertEquals(Screen.Welcome, flows.screen)
        assertFalse(flows.back())
        assertEquals(Screen.Welcome, flows.screen)
        assertEquals(SetupPhase.UNSET, progress.phase)
        assertEquals(Screen.Welcome, newFlows().screen)
    }

    @Test fun theMainActionOpensStepOneOrTheLockNotice() {
        flows.startFirstSetup()
        assertEquals(Screen.Setup, flows.screen)
        assertEquals(1, flows.setupStep)
        assertEquals(SetupPhase.IN_PROGRESS, progress.phase)

        progress = MemorySetupProgress(SetupPhase.UNSET)
        secure = false
        flows = newFlows()
        flows.startFirstSetup()
        assertEquals(Screen.LockNotice, flows.screen)
        assertTrue(flows.back())
        assertEquals(Screen.Welcome, flows.screen)
        flows.startFirstSetup()
        secure = true
        flows.lockNoticeContinue()
        assertEquals(Screen.Setup, flows.screen)
    }

    @Test fun homeMachinesAndAlertsCannotBypassFirstSetup() {
        flows.startFirstSetup()
        flows.openMachines()
        assertEquals(Screen.Setup, flows.screen)
        flows.openAgents()
        assertEquals(Screen.Setup, flows.screen)
        flows.openAddMachine()
        assertEquals(Screen.Setup, flows.screen)
        assertEquals(1, flows.setupStep)
        assertTrue(flows.back())
        assertEquals(Screen.Welcome, flows.screen)
        flows.openAgents()
        assertEquals(Screen.Welcome, flows.screen)
    }

    @Test fun everyStartResumesTheStepTheyLeft() {
        toStepTwo()
        flows.chooseKey(KeyChoice.MANUAL)
        val again = newFlows()
        assertEquals(Screen.Setup, again.screen)
        assertEquals(2, again.setupStep)
        assertEquals("workstation", again.machine)
        assertEquals(KeyChoice.MANUAL, again.keyDetail)
    }

    // Review finding (7.1): a cold start restores before the hub's first snapshot; the machine must survive that.

    private fun coldStart(): Triple<MachineFlows, () -> HubListener, HubHost> {
        progress = MemorySetupProgress(SetupPhase.IN_PROGRESS).also {
            it.checkpoint = SetupCheckpoint(
                machine = "website", step = 2, choice = KeyChoice.PASSWORD, origin = "Agents",
                name = "", host = "website.example.org", port = "22", user = "dev", added = true, reached = true,
            )
        }
        var listener2: HubListener? = null
        val host = HubHost(createHub = { listener2 = it; FakeHub() }, mainExecutor = Executor { it.run() })
        host.start()
        return Triple(MachineFlows(host, progress, ports, field, isDeviceSecure = { true }), { listener2!! }, host)
    }

    @Test fun aColdStartKeepsTheSavedMachineAndReopensItsChoiceAtTheFirstSnapshot() {
        val (cold, listener2, _) = coldStart()
        assertEquals("the saved machine is not dropped while the snapshot is empty", "website", cold.machine)
        val s = HubSnapshot(listOf(machine("website", MachineState.KeyRejected).copy(hostKey = TRUSTED)), emptyList(), emptyList(), null)
        listener2().onSnapshot(s)
        cold.onSnapshot(s)
        assertEquals("website", cold.machine)
        assertEquals(2, cold.setupStep)
        assertEquals(KeyChoice.PASSWORD, cold.keyDetail)
        assertEquals(TRUSTED, ports.probes.single().pinned)
    }

    @Test fun aSavedMachineThatIsGoneIsDroppedAtTheFirstRealSnapshot() {
        val (cold, listener2, _) = coldStart()
        val s = HubSnapshot(listOf(machine("other", MachineState.Paused)), emptyList(), emptyList(), null)
        listener2().onSnapshot(s)
        cold.onSnapshot(s)
        assertNull(cold.machine)
        assertNull(cold.keyDetail)
    }

    // Review finding (7.1): a changed host key before the first connection must not let go of first setup.
    private val mismatch = MachineState.HostKeyMismatch(confirmed = TRUSTED, presented = PRESENTED, algorithm = "ED25519")

    @Test fun backFromTheChangedKeyScreenReturnsToSetupNotToMachines() {
        toStepTwo()
        publish(machine("workstation", mismatch))
        assertEquals(Screen.HostKeyChanged, flows.screen)
        assertTrue(flows.back())
        assertEquals(Screen.Setup, flows.screen)
        assertEquals(2, flows.setupStep)
        assertEquals("workstation", flows.machine)
    }

    @Test fun forgettingTheChangedKeyReturnsToSetup() {
        toStepTwo()
        publish(machine("workstation", mismatch))
        flows.askForget()
        flows.forget()
        assertEquals(Screen.Setup, flows.screen)
        assertEquals("workstation", flows.machine)
    }

    @Test fun aMismatchThatGoesAwayReturnsToSetup() {
        toStepTwo()
        publish(machine("workstation", mismatch))
        publish(machine("workstation", unconfirmed))
        assertEquals(Screen.Setup, flows.screen)
        assertEquals("workstation", flows.machine)
    }

    @Test fun afterCompletionTheChangedKeyStillLeadsToMachines() {
        completed()
        publish(machine("workstation", mismatch).copy(lastConnectedMs = 1uL))
        flows.openHostKey("workstation")
        assertEquals(Screen.HostKeyChanged, flows.screen)
        assertTrue(flows.back())
        assertEquals(Screen.Machines, flows.screen)
    }

    @Test fun backFromStepOneGoesToTheWelcomeAndKeepsTheFields() {
        flows.startFirstSetup()
        val typed = MachineInput("", "typed.example.org", "2200", "dev")
        flows.changeInput(typed)
        assertTrue(flows.back())
        assertEquals(Screen.Welcome, flows.screen)
        assertTrue(hub.calls.isEmpty())
        flows = newFlows()
        flows.startFirstSetup()
        assertEquals(1, flows.setupStep)
        assertEquals(typed, flows.input)
    }

    @Test fun backAfterAFailedTryRemovesTheMachineThisVisitAdded() {
        flows.startFirstSetup()
        flows.save(workstation)
        publish(machine("workstation", MachineState.Unreachable(ReachKind.NO_ANSWER, "timeout")))
        flows.back()
        assertEquals("remove workstation", hub.calls.last())
        assertEquals(Screen.Welcome, flows.screen)
    }

    @Test fun backFromStepTwoIsStepOneWithTheFieldsAndTheMachineStaysListed() {
        toStepTwo()
        hub.calls.clear()
        assertTrue(flows.back())
        assertEquals(1, flows.setupStep)
        assertEquals("workstation.example.org", flows.input.host)
        assertEquals("dev", flows.input.user)
        assertEquals("22", flows.input.port)
        assertTrue(hub.calls.isEmpty())
        // The saved step follows Back down.
        val again = newFlows()
        assertEquals(Screen.Setup, again.screen)
        assertEquals(1, again.setupStep)
        assertEquals("workstation", again.machine)
        // Reconnecting does not move forward by itself past a Back.
        // (Step 1 of a reached machine does not remove it on Back.)
        assertTrue(flows.back())
        assertEquals(Screen.Welcome, flows.screen)
        assertTrue(hub.calls.none { it.startsWith("remove") })
    }

    @Test fun backClosesTheDialogThenTheDetailThenGoesOneStepBack() {
        toStepTwo()
        flows.chooseKey(KeyChoice.MANUAL)
        flows.compareFingerprint()
        assertEquals("workstation", flows.trusting)
        assertTrue(flows.back())
        assertNull(flows.trusting)
        assertEquals(KeyChoice.MANUAL, flows.keyDetail)
        assertTrue(flows.back())
        assertNull(flows.keyDetail)
        assertEquals(2, flows.setupStep)
        assertTrue(flows.back())
        assertEquals(1, flows.setupStep)
    }

    // Step 3 completes first setup; step 4 is only reached by Continue.

    @Test fun aRealConnectionAtStepThreeCompletesFirstSetup() {
        toStepTwo()
        publish(machine("workstation", MachineState.Connected("1.0")).copy(hostKey = TRUSTED, lastConnectedMs = 1uL))
        assertEquals(3, flows.setupStep)
        assertEquals(SetupPhase.COMPLETED, progress.phase)
        assertNull(progress.checkpoint)
        // Step 3 holds through a drop and never moves to step 4 by itself.
        publish(machine("workstation", MachineState.Unreachable(ReachKind.OTHER, "x")).copy(hostKey = TRUSTED, lastConnectedMs = 1uL))
        assertEquals(3, flows.setupStep)
        flows.continueToAlerts()
        assertEquals(4, flows.setupStep)
        assertTrue(flows.back())
        assertEquals(3, flows.setupStep)
        assertTrue(flows.back())
        assertEquals(Screen.Agents, flows.screen)
    }

    @Test fun continueIsOnlyOfferedFromStepThree() {
        toStepTwo()
        flows.continueToAlerts()
        assertEquals(2, flows.setupStep)
    }

    @Test fun listingImportingTrustingOrInstallingAKeyDoesNotComplete() {
        toStepTwo()
        flows.chooseKey(KeyChoice.MANUAL)
        flows.compareFingerprint()
        flows.trust(flows.trustPrompt(hubHost.snapshot.value)!!)
        publish(machine("workstation", MachineState.KeyRejected).copy(hostKey = PRESENTED))
        assertEquals(SetupPhase.IN_PROGRESS, progress.phase)
        assertEquals(Screen.Setup, flows.screen)
    }

    @Test fun stepFourIsNotInTheCheckpoint() {
        toStepTwo()
        publish(machine("workstation", MachineState.Connected("1.0")).copy(hostKey = TRUSTED, lastConnectedMs = 1uL))
        flows.continueToAlerts()
        val saved = save()
        val back = restore(saved)
        assertEquals(Screen.Agents, back.screen)
    }

    @Test fun anExistingInstallationWithAConnectionIsCompleted() {
        val s = publish(machine("workstation", MachineState.Paused).copy(hostKey = TRUSTED, lastConnectedMs = 1uL))
        assertEquals(SetupPhase.COMPLETED, progress.phase)
        assertEquals(Screen.Agents, flows.screen)
        assertEquals(1, s.machines.size)
    }

    @Test fun aListedMachineThatNeverConnectedStillNeedsSetup() {
        publish(machine("workstation", MachineState.KeyRejected).copy(hostKey = TRUSTED))
        assertEquals(SetupPhase.UNSET, progress.phase)
        assertEquals(Screen.Welcome, flows.screen)
    }

    @Test fun removingTheLastMachineAfterCompletionDoesNotRepeatTheWelcome() {
        completed()
        publish(machine("workstation", MachineState.Paused).copy(lastConnectedMs = 1uL))
        publish()
        assertEquals(Screen.Agents, flows.screen)
        assertEquals(Screen.Agents, newFlows().screen)
    }

    @Test fun backFromStepOneOfALaterMachineReturnsToItsOpenerAndKeepsTheFields() {
        completed()
        flows.openMachines()
        flows.actions(MachinesActions.Preview).onAddMachine()
        val typed = MachineInput("", "typed.example.org", "22", "dev")
        flows.changeInput(typed)
        assertTrue(flows.back())
        assertEquals(Screen.Machines, flows.screen)
        assertTrue(hub.calls.isEmpty())
        assertEquals(typed, progress.checkpoint?.let { MachineInput(it.name, it.host, it.port, it.user) })
    }

    @Test fun anAlertDuringALaterMachinesSetupOpensTheAgentAndKeepsTheMachine() {
        completed()
        publish(machine("workstation", unconfirmed))
        flows.openSetup("workstation", atLeast = 2)
        flows.openAgents()
        assertEquals(Screen.Agents, flows.screen)
        assertTrue(hub.calls.none { it.startsWith("remove") })
        flows.openSetup("workstation")
        assertEquals(Screen.Setup, flows.screen)
    }

    @Test fun theCheckpointHoldsNoPassword() {
        toStepTwo()
        publish(machine("workstation", MachineState.KeyRejected).copy(hostKey = TRUSTED))
        flows.chooseKey(KeyChoice.PASSWORD)
        field.text = SENTINEL
        val checkpoint = progress.checkpoint.toString() + save().toString()
        assertFalse(checkpoint.contains(SENTINEL))
        assertEquals(KeyChoice.PASSWORD, progress.checkpoint?.choice)
    }

    // Both key-install choices, shared by first setup and the Machines card.

    @Test fun theCardsTwoChoicesOpenTheirDetails() {
        completed()
        publish(machine("website", MachineState.KeyRejected).copy(hostKey = TRUSTED))
        flows.openKeyChoice("website", KeyChoice.MANUAL)
        assertEquals(Screen.Setup, flows.screen)
        assertEquals(2, flows.setupStep)
        assertEquals(KeyChoice.MANUAL, flows.keyDetail)
        flows.back()
        flows.openKeyChoice("website", KeyChoice.PASSWORD)
        assertEquals(KeyChoice.PASSWORD, flows.keyDetail)
        assertEquals(1, ports.probes.size)
        assertEquals(TRUSTED, ports.probes.single().pinned)
    }

    @Test fun switchingChoiceClearsThePassword() {
        completed()
        publish(machine("website", MachineState.KeyRejected).copy(hostKey = TRUSTED))
        flows.openKeyChoice("website", KeyChoice.PASSWORD)
        field.text = SENTINEL
        flows.chooseKey(KeyChoice.MANUAL)
        assertEquals("", field.text)
        assertEquals(PasswordPanel.Hidden, flows.password.panel)
    }

    @Test fun anImportedMachineStillNeedsConfirmationBeforeAnyPassword() {
        completed()
        publish(machine("imported", unconfirmed))
        flows.openKeyChoice("imported", KeyChoice.PASSWORD)
        assertEquals("imported", flows.trusting)
        assertTrue(ports.probes.isEmpty())
        assertTrue(hub.calls.none { it.startsWith("confirm") })
    }

    // The password panel (design D12).

    private fun toField(): PasswordPanel {
        completed()
        publish(machine("website", MachineState.KeyRejected).copy(hostKey = TRUSTED))
        flows.openKeyChoice("website", KeyChoice.PASSWORD)
        ports.answerProbe(ProbeOutcome.Password)
        return flows.password.panel
    }

    @Test fun trustingFirstThenTheProbe() {
        completed()
        publish(machine("workstation", unconfirmed))
        flows.openKeyChoice("workstation", KeyChoice.PASSWORD)
        assertEquals("workstation", flows.trusting)
        flows.cancelTrust()
        assertTrue(hub.calls.none { it.startsWith("confirm") })
        assertTrue(ports.probes.isEmpty())
        flows.chooseKey(KeyChoice.PASSWORD)
        val s = hubHost.snapshot.value
        flows.trust(flows.trustPrompt(s)!!)
        assertEquals("confirm workstation $PRESENTED", hub.calls.last())
        assertEquals(PRESENTED, ports.probes.single().pinned)
    }

    @Test fun probeResultsDecideTheField() {
        assertEquals(PasswordPanel.Field(null), toField())
        for ((outcome, expected) in listOf(
            ProbeOutcome.KeyboardInteractive("Verification code: ") to PasswordPanel.Field("Verification code: "),
            ProbeOutcome.NoPasswordMethod to PasswordPanel.NoPassword,
            ProbeOutcome.Unreachable(ReachKind.REFUSED, "x") to PasswordPanel.Reach(ReachKind.REFUSED),
        )) {
            ports.probes.clear()
            flows.chooseKey(KeyChoice.MANUAL)
            flows.chooseKey(KeyChoice.PASSWORD)
            ports.answerProbe(outcome)
            assertEquals(expected, flows.password.panel)
        }
    }

    @Test fun addTheKeyEmptiesTheFieldBeforeTheCallReturnsAndSendsTheText() {
        toField()
        field.text = SENTINEL
        ports.onSetup = { assertEquals("", field.text) }
        flows.addKey()
        val call = ports.setups.single()
        assertEquals(SENTINEL, call.password)
        assertEquals(TRUSTED, call.pinned)
        assertEquals(PasswordPanel.Working, flows.password.panel)
    }

    @Test fun outcomesMapToTheirPanels() {
        for ((outcome, expected) in listOf(
            SetupOutcome.NoPasswordMethod to PasswordPanel.NoPassword,
            SetupOutcome.MoreThanPassword to PasswordPanel.MoreThanPassword,
            SetupOutcome.Dropped to PasswordPanel.Dropped,
            SetupOutcome.ScriptFailed(3u) to PasswordPanel.ScriptFailed(3),
            SetupOutcome.Unreachable(ReachKind.NO_ANSWER, "x") to PasswordPanel.Reach(ReachKind.NO_ANSWER),
        )) {
            toField()
            field.text = "pw"
            flows.addKey()
            ports.answerSetup(outcome)
            assertEquals(expected, flows.password.panel)
        }
    }

    // Review finding (7.1): after a dropped connection the field and "Add the key" are shown, so they must work.
    @Test fun aDroppedConnectionCanBeRetriedWithTheTypedPassword() {
        toField()
        field.text = "pw1"; flows.addKey(); ports.answerSetup(SetupOutcome.Dropped)
        assertEquals(PasswordPanel.Dropped, flows.password.panel)
        field.text = "pw2"; flows.addKey()
        assertEquals("the retry went out", 2, ports.setups.size)
        assertEquals("pw2", ports.setups.last().password)
        assertEquals(PasswordPanel.Working, flows.password.panel)
    }

    // Review finding (7.1): Cancel has to stop the core's session, and say what a late cancel left behind.
    private fun working() {
        toField()
        field.text = SENTINEL
        flows.addKey()
        assertEquals(PasswordPanel.Working, flows.password.panel)
    }

    @Test fun cancelWhileWorkingAsksTheCoreToStopAndWaitsForItsAnswer() {
        working()
        assertTrue(flows.back())
        assertEquals(1, ports.cancels)
        assertEquals("the panel waits for the core's answer", PasswordPanel.Working, flows.password.panel)
        ports.answerSetup(SetupOutcome.Cancelled)
        assertEquals(PasswordPanel.Cancelled, flows.password.panel)
        assertEquals("", field.text)
    }

    @Test fun cancelledAfterSigningInSaysItSignedInAndChangedNothing() {
        working()
        flows.back()
        ports.answerSetup(SetupOutcome.CancelledSignedIn)
        assertEquals(PasswordPanel.CancelledSignedIn, flows.password.panel)
    }

    @Test fun cancelledAfterTheKeyWentInSaysSoAndConnects() {
        working()
        flows.back()
        ports.answerSetup(SetupOutcome.CancelledKeyAdded)
        assertEquals(PasswordPanel.CancelledKeyAdded, flows.password.panel)
        assertEquals("the hub retries with the key now on the machine", "retry", hub.calls.last())
    }

    @Test fun leavingWhileItRunsStopsTheCoreToo() {
        working()
        flows.openAgents()
        assertEquals(1, ports.cancels)
    }

    @Test fun aCancelWhenNothingIsRunningJustClosesThePanel() {
        toField()
        assertTrue(flows.back())
        assertEquals(0, ports.cancels)
        assertEquals(PasswordPanel.Hidden, flows.password.panel)
    }

    @Test fun aSecondRefusalInOneVisitIsTheLockoutTextAndANewVisitStartsOver() {
        toField()
        field.text = "bad"; flows.addKey(); ports.answerSetup(SetupOutcome.WrongPassword)
        assertEquals(PasswordPanel.WrongPassword(again = false), flows.password.panel)
        assertEquals("", field.text)
        flows.addKey() // nothing typed yet: still allowed to try again with a typed one
        field.text = "bad2"; flows.addKey(); ports.answerSetup(SetupOutcome.WrongPassword)
        assertEquals(PasswordPanel.WrongPassword(again = true), flows.password.panel)
        flows.openKeyChoice("website", KeyChoice.PASSWORD)
        ports.answerProbe(ProbeOutcome.Password)
        field.text = "bad3"; flows.addKey(); ports.answerSetup(SetupOutcome.WrongPassword)
        assertEquals(PasswordPanel.WrongPassword(again = false), flows.password.panel)
    }

    @Test fun doneRetriesOnceAndNothingRetriesByItself() {
        toField()
        field.text = "good"; flows.addKey()
        hub.calls.clear()
        ports.answerSetup(SetupOutcome.Done)
        assertEquals(PasswordPanel.Done, flows.password.panel)
        assertEquals(listOf("retry"), hub.calls)
        publish(machine("website", MachineState.Connecting).copy(hostKey = TRUSTED))
        assertEquals(listOf("retry"), hub.calls)
    }

    @Test fun stillRefusedAfterADoneRunIsTheFinalTextAndNeverThePasswordAgain() {
        toField()
        field.text = "good"; flows.addKey(); ports.answerSetup(SetupOutcome.Done)
        publish(machine("website", MachineState.KeyRejected).copy(hostKey = TRUSTED))
        assertEquals(PasswordPanel.StillRefused, flows.password.panel)
        flows.chooseKey(KeyChoice.PASSWORD)
        assertEquals(PasswordPanel.StillRefused, flows.password.panel)
        assertTrue(ports.probes.size == 1)
        // A new visit offers it again.
        flows.openKeyChoice("website", KeyChoice.PASSWORD)
        assertEquals(2, ports.probes.size)
    }

    @Test fun theExpectedRefusalWhileThePanelShowsIsNotAFailureButOthersAre() {
        toField()
        val rejected = publish(machine("website", MachineState.KeyRejected).copy(hostKey = TRUSTED))
        assertNull(flows.failure(rejected))
        val other = publish(machine("website", MachineState.NotInstalled).copy(hostKey = TRUSTED))
        assertEquals(MachineState.NotInstalled, flows.failure(other))
        publish(machine("website", MachineState.Connected("1.0")).copy(hostKey = TRUSTED, lastConnectedMs = 1uL))
        assertEquals(3, flows.setupStep)
        assertEquals(PasswordPanel.Hidden, flows.password.panel)
        assertEquals("", field.text)
    }

    @Test fun leavingStepTwoOrTheDetailEmptiesTheField() {
        toField()
        field.text = SENTINEL
        flows.back()
        assertEquals("", field.text)
        toField()
        field.text = SENTINEL
        flows.openAgents()
        assertEquals("", field.text)
    }

    @Test fun anotherHostKeyEndsTheProbeAtTheChangedKeyScreen() {
        completed()
        publish(machine("website", MachineState.KeyRejected).copy(hostKey = TRUSTED))
        flows.openKeyChoice("website", KeyChoice.PASSWORD)
        ports.answerProbe(ProbeOutcome.HostKeyChanged(PRESENTED))
        assertEquals(PasswordPanel.ChangedKey(PRESENTED), flows.password.panel)
        assertEquals("retry", hub.calls.last())
        publish(machine("website", MachineState.HostKeyMismatch(TRUSTED, PRESENTED, "ED25519")).copy(hostKey = TRUSTED))
        assertEquals(Screen.HostKeyChanged, flows.screen)
        assertEquals("", field.text)
    }

    @Test fun afterProcessDeathTheFieldIsEmptyAndTheSavedStateHoldsNoPassword() {
        toField()
        field.text = SENTINEL
        val saved = save()
        assertFalse(saved.toString().contains(SENTINEL))
        field.text = ""
        val back = restore(saved)
        assertEquals("", field.text)
        assertEquals(Screen.Setup, back.screen)
    }

    // Helpers.

    private fun save(): Any {
        val saver = MachineFlows.saver(hubHost, progress, ports, field) { secure }
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        return with(saver) { scope.save(flows) }!!
    }

    private fun restore(saved: Any): MachineFlows =
        MachineFlows.saver(hubHost, progress, ports, field) { secure }.restore(saved)!!

    class TestField : SecretField {
        var text = ""
        override fun text() = text
        override fun clear() { text = "" }
    }

    class FakePorts : PasswordPorts {
        data class Probe(val host: String, val port: Int, val user: String, val pinned: String)
        data class Setup(val pinned: String, val password: String)
        val probes = mutableListOf<Probe>()
        val setups = mutableListOf<Setup>()
        var onSetup: () -> Unit = {}
        private var probeDone: ((ProbeOutcome) -> Unit)? = null
        private var setupDone: ((SetupOutcome) -> Unit)? = null

        override fun probe(host: String, port: Int, user: String, pinned: String, done: (ProbeOutcome) -> Unit) {
            probes += Probe(host, port, user, pinned)
            probeDone = done
        }

        var cancels = 0

        override fun setup(host: String, port: Int, user: String, pinned: String, password: String, done: (SetupOutcome) -> Unit): SetupHandle {
            onSetup()
            setups += Setup(pinned, password)
            setupDone = done
            return object : SetupHandle { override fun cancel() { cancels++ } }
        }

        fun answerProbe(outcome: ProbeOutcome) = probeDone!!(outcome)
        fun answerSetup(outcome: SetupOutcome) = setupDone!!(outcome)
    }

    companion object {
        const val TRUSTED = "SHA256:Zt7oP1qW3eR5tY7uI9oP2aS4dF6gH8jK0lZ2xC4vB6n"
        const val PRESENTED = "SHA256:q3Vb8m2N4sR7tK0yL9wE1aZ5cX6vB3nM8jH2gF4dS0p"
        const val SENTINEL = "sentinel-Hunter2-sentinel"
    }
}
