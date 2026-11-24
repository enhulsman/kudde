package dev.kudde.android.ui.setup

import dev.kudde.android.push.AlertSetup
import dev.kudde.android.push.MachineAlerts
import dev.kudde.android.ui.machines.PhoneAlerts
import dev.kudde.android.ui.machines.ReachText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.kudde_mobile.AlertsRegistration
import uniffi.kudde_mobile.AlertsView
import uniffi.kudde_mobile.MachineState
import uniffi.kudde_mobile.MachineView

// Step 4's states (design D13), step 1's status, and step 3's count: the logic
// the screens are drawn from, before the screens.
class SetupModelTest {
    private val now = 1_000_000L
    private fun machine(registration: AlertsRegistration = AlertsRegistration.NOT_YET) =
        MachineView(
            "workstation", "workstation.example.org", 22u, "dev", "SHA256:x", MachineState.Connected("0.1.16"), null,
        ).copy(alerts = AlertsView(registration, null))

    private fun phone(
        registered: Boolean = true,
        setup: AlertSetup = AlertSetup.Off,
        permitted: Boolean = true,
        mine: MachineAlerts = MachineAlerts(),
    ) = PhoneAlerts(registered, setup, permitted, mapOf("workstation" to mine)) { "14:02" }

    @Test fun alertsOffIsTurnOn() {
        assertEquals(AlertsState.Before, alertsState(phone(registered = false), machine(), now))
    }

    @Test fun eachStopIsItsOwnState() {
        assertEquals(AlertsState.Waiting, alertsState(phone(registered = false, setup = AlertSetup.Waiting), machine(), now))
        assertEquals(AlertsState.NoDistributor, alertsState(phone(registered = false, setup = AlertSetup.NoDistributor), machine(), now))
        assertEquals(AlertsState.Failed("boom"), alertsState(phone(registered = false, setup = AlertSetup.Failed("boom")), machine(), now))
        assertEquals(AlertsState.NotAllowed, alertsState(phone(permitted = false), machine(), now))
    }

    @Test fun alreadyOnOffersTheTestAndSendsNothing() {
        assertEquals(AlertsState.On, alertsState(phone(), machine(AlertsRegistration.REGISTERED), now))
        assertFalse(shouldSendTest(turnedOnHere = false, phone(), machine(AlertsRegistration.REGISTERED)))
    }

    @Test fun thePathIsTestedByItselfOnceAfterTurningOnHereAndTheRegistrationIsWritten() {
        val registered = machine(AlertsRegistration.REGISTERED)
        assertTrue(shouldSendTest(true, phone(), registered))
        // Not before kudde-push's document holds the registration.
        assertFalse(shouldSendTest(true, phone(), machine(AlertsRegistration.NOT_YET)))
        // Not without permission, not twice.
        assertFalse(shouldSendTest(true, phone(permitted = false), registered))
        assertFalse(shouldSendTest(true, phone(mine = MachineAlerts(testAskedMs = now - 1)), registered))
        assertFalse(shouldSendTest(true, phone(registered = false), registered))
    }

    @Test fun theTestWaitsThirtySecondsThenSaysWhatCame() {
        val registered = machine(AlertsRegistration.REGISTERED)
        val asked = now - 10_000
        assertEquals(AlertsState.TestWaiting, alertsState(phone(mine = MachineAlerts(testAskedMs = asked)), registered, now))
        assertEquals(AlertsState.TestMissed, alertsState(phone(mine = MachineAlerts(testAskedMs = now - 31_000)), registered, now))
        assertEquals(
            AlertsState.TestReceived,
            alertsState(phone(mine = MachineAlerts(testAskedMs = asked, testReceivedMs = asked + 2_000)), registered, now),
        )
    }

    @Test fun addressStatusFollowsTheFirstResult() {
        val reach = ReachText("No answer from workstation.", "Check.")
        assertEquals(AddressStatus.Idle, addressStatus(null, "workstation:22", false, null))
        assertEquals(AddressStatus.Waiting("workstation:22", false), addressStatus("workstation", "workstation:22", false, null))
        assertEquals(AddressStatus.Waiting("workstation:22", true), addressStatus("workstation", "workstation:22", true, null))
        assertEquals(AddressStatus.Failed(reach), addressStatus("workstation", "workstation:22", true, reach))
    }

    @Test fun slowAfterFiveSeconds() {
        assertFalse(isSlow(startedMs = 0, nowMs = 4_999))
        assertTrue(isSlow(startedMs = 0, nowMs = 5_000))
    }

    @Test fun theKeyStepsFooterAndNotice() {
        assertEquals(KeyNotice.Rejected, keyNotice(MachineState.KeyRejected, null))
        assertEquals(KeyNotice.CoreMissing, keyNotice(MachineState.NotInstalled, null))
        assertEquals(KeyNotice.None, keyNotice(MachineState.Connecting, null))
        val reach = ReachText("x", null)
        assertEquals(KeyNotice.Reach(reach), keyNotice(MachineState.Unreachable(uniffi.kudde_mobile.ReachKind.OTHER, "x"), reach))
    }
}
