package dev.kudde.android.ui.setup

import dev.kudde.android.push.AlertSetup
import dev.kudde.android.ui.machines.PhoneAlerts
import dev.kudde.android.ui.machines.ReachText
import dev.kudde.android.ui.machines.TEST_WAIT_MS
import uniffi.kudde_mobile.AlertsRegistration
import uniffi.kudde_mobile.MachineState
import uniffi.kudde_mobile.MachineView

// What the first-setup screens show, worked out from the hub's and the phone's state
// (SetupModelTest). Texts live in the views; these pick which one.

/** After this long without an answer, step 1 adds A9. */
const val SLOW_AFTER_MS = 5_000L

fun isSlow(startedMs: Long, nowMs: Long): Boolean = nowMs - startedMs >= SLOW_AFTER_MS

/** Step 1 under the fields: a failure wins, then the waiting line, else nothing. */
fun addressStatus(waitingFor: String?, address: String, slow: Boolean, reach: ReachText?): AddressStatus = when {
    reach != null -> AddressStatus.Failed(reach)
    waitingFor != null -> AddressStatus.Waiting(address, slow)
    else -> AddressStatus.Idle
}

/** What step 2 says first: the key refused (K1), herdr-core missing (K2), or a reach failure. */
fun keyNotice(state: MachineState, reach: ReachText?): KeyNotice = when {
    state == MachineState.KeyRejected -> KeyNotice.Rejected
    state == MachineState.NotInstalled -> KeyNotice.CoreMissing
    reach != null -> KeyNotice.Reach(reach)
    else -> KeyNotice.None
}

/** Step 4's state for [m]: the phone's alerts, then this machine's test (design D13). */
fun alertsState(phone: PhoneAlerts, m: MachineView, nowMs: Long): AlertsState {
    if (!phone.registered) {
        return when (val setup = phone.setup) {
            AlertSetup.Off -> AlertsState.Before
            AlertSetup.Waiting -> AlertsState.Waiting
            AlertSetup.NoDistributor -> AlertsState.NoDistributor
            is AlertSetup.Failed -> AlertsState.Failed(setup.reason)
        }
    }
    if (!phone.permitted) return AlertsState.NotAllowed
    val mine = phone.machines[m.name]
    val asked = mine?.testAskedMs ?: return AlertsState.On
    val received = mine.testReceivedMs
    return when {
        received != null && received >= asked -> AlertsState.TestReceived
        nowMs - asked < TEST_WAIT_MS -> AlertsState.TestWaiting
        else -> AlertsState.TestMissed
    }
}

/**
 * Whether step 4 sends the test by itself: only after "Turn on alerts" was tapped
 * here, once this machine's kudde-push document holds the registration, with
 * permission, and when no test was asked yet. Alerts that were already on send
 * nothing unasked.
 */
fun shouldSendTest(turnedOnHere: Boolean, phone: PhoneAlerts, m: MachineView): Boolean =
    turnedOnHere && phone.registered && phone.permitted &&
        m.alerts?.registration == AlertsRegistration.REGISTERED &&
        phone.machines[m.name]?.testAskedMs == null
