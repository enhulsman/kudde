package dev.kudde.android.ui.setup

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.ui.addmachine.MachineInput
import dev.kudde.android.ui.hostkey.HostKeyPrompt
import dev.kudde.android.ui.hostkey.TrustDialog
import dev.kudde.android.ui.machines.ReachText
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The first-setup screens against the signed-off renders (design/renders/screens.html,
// first-run-*): graphite and paper, each step at font size 2x, and step 1 with each
// failure kind. Reference phone 412 x 915 dp at density 2, no system bars.
// Goldens: src/test/snapshots/first-run-*.png.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class SetupScreenshotTest {
    @Test fun welcomeGraphite() = capture("first-run-welcome-graphite", true) { WelcomeScreen({}, {}) }
    @Test fun welcomePaper() = capture("first-run-welcome-paper", false) { WelcomeScreen({}, {}) }
    @Test fun welcome2x() = font2x { capture("first-run-welcome-2x-graphite", true) { WelcomeScreen({}, {}) } }

    @Test fun lockGraphite() = capture("first-run-lock-graphite", true) { LockNoticeScreen({}, {}) }
    @Test fun lockPaper() = capture("first-run-lock-paper", false) { LockNoticeScreen({}, {}) }
    @Test fun lockHomeGraphite() = capture("first-run-lock-home-graphite", true) { LockNoticeCard({}) }
    @Test fun lockHomePaper() = capture("first-run-lock-home-paper", false) { LockNoticeCard({}) }

    private val typed = MachineInput("", "workstation", "22", "dev")
    private fun address(status: AddressStatus) = AddressUi(typed, null, status, null, false, "workstation")
    private val addressActions = AddressActions({}, {}, {}, {})

    @Test fun addressWaitingGraphite() = capture("first-run-address-waiting-graphite", true) { AddressStep(address(AddressStatus.Waiting("workstation:22", true)), addressActions) }
    @Test fun addressWaitingPaper() = capture("first-run-address-waiting-paper", false) { AddressStep(address(AddressStatus.Waiting("workstation:22", true)), addressActions) }
    @Test fun address2x() = font2x { capture("first-run-address-2x-graphite", true) { AddressStep(address(AddressStatus.Idle), addressActions) } }

    private val failures = listOf(
        "no-answer" to ReachText("No answer from workstation.", "Check that the phone is on the same Wi-Fi or VPN as the machine, and that the address is right."),
        "refused" to ReachText("workstation answered, but nothing listens on port 22.", "Check that the OpenSSH server is running there, and the port."),
        "name-not-found" to ReachText("The phone cannot find a machine called workstation.", "Check the spelling. With a VPN such as Tailscale, check that it is on, on this phone."),
        "no-route" to ReachText("This network has no way to workstation.", "Join the same Wi-Fi as the machine, or turn the VPN on."),
        "other" to ReachText("Could not connect to workstation: connection reset", null),
    )

    @Test fun addressFailureKindsGraphite() {
        for ((kind, reach) in failures) capture("first-run-address-$kind-graphite", true) { AddressStep(address(AddressStatus.Failed(reach)), addressActions) }
    }

    @Test fun addressFailurePaper() = capture("first-run-address-no-answer-paper", false) { AddressStep(address(AddressStatus.Failed(failures.first().second)), addressActions) }

    private val command = "(\n  umask 077\n  mkdir -p ~/.ssh && chmod 700 ~/.ssh &&\n  touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys &&\n  cat >> ~/.ssh/authorized_keys <<'KUDDE_KEY' &&\n\nrestrict,command=\"sh -c '…'\" ecdsa-sha2-nistp256 AAAA… kudde\nKUDDE_KEY\n  ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub\n)"
    private val manual = KeyUi("workstation", "dev", KeyNotice.None, false, command, false, true, true, false)
    private val refused = KeyUi("workstation", "dev", KeyNotice.Rejected, true, null, false, false, true, true)
    private val keyActions = KeyActions({}, {}, {}, {}, {}, {}, {})

    @Test fun manualGraphite() = capture("first-run-manual-graphite", true) { KeyStep(manual, keyActions) }
    @Test fun manualPaper() = capture("first-run-manual-paper", false) { KeyStep(manual, keyActions) }
    @Test fun manual2x() = font2x { capture("first-run-manual-2x-graphite", true) { KeyStep(manual, keyActions) } }
    @Test fun refusedGraphite() = capture("first-run-key-refused-graphite", true) { KeyStep(refused, keyActions) }
    @Test fun refusedPaper() = capture("first-run-key-refused-paper", false) { KeyStep(refused, keyActions) }
    @Test fun refused2x() = font2x { capture("first-run-key-refused-2x-graphite", true) { KeyStep(refused, keyActions) } }

    @Test fun trustGraphite() = capture("first-run-trust-graphite", true) { trust() }
    @Test fun trustPaper() = capture("first-run-trust-paper", false) { trust() }

    @Composable
    private fun trust() = Box {
        KeyStep(manual, keyActions)
        TrustDialog(HostKeyPrompt("workstation", "workstation:22", "ED25519", PRESENTED), null, {}, {})
    }

    private val connected = ConnectedUi("workstation", "0.1.16", 3)

    @Test fun connectedGraphite() = capture("first-run-connected-graphite", true) { ConnectedStep(connected, {}, {}) }
    @Test fun connectedPaper() = capture("first-run-connected-paper", false) { ConnectedStep(connected, {}, {}) }
    @Test fun connected2x() = font2x { capture("first-run-connected-2x-graphite", true) { ConnectedStep(connected, {}, {}) } }

    private val alertsActions = AlertsActions({}, {}, {}, {}, {}, {}, {}, {})
    private fun alerts(state: AlertsState, agents: Int = 3) = AlertsUi("workstation", state, agents)

    @Test fun alertsBeforeGraphite() = capture("first-run-alerts-before-graphite", true) { AlertsStep(alerts(AlertsState.Before), alertsActions) }
    @Test fun alertsBeforePaper() = capture("first-run-alerts-before-paper", false) { AlertsStep(alerts(AlertsState.Before), alertsActions) }
    @Test fun alertsReceivedGraphite() = capture("first-run-alerts-received-graphite", true) { AlertsStep(alerts(AlertsState.TestReceived), alertsActions) }
    @Test fun alertsReceivedPaper() = capture("first-run-alerts-received-paper", false) { AlertsStep(alerts(AlertsState.TestReceived), alertsActions) }
    @Test fun alertsMissedGraphite() = capture("first-run-alerts-missed-graphite", true) { AlertsStep(alerts(AlertsState.TestMissed), alertsActions) }
    @Test fun alertsMissedPaper() = capture("first-run-alerts-missed-paper", false) { AlertsStep(alerts(AlertsState.TestMissed), alertsActions) }
    @Test fun alertsNoAgentsGraphite() = capture("first-run-alerts-no-agents-graphite", true) { AlertsStep(alerts(AlertsState.Before, agents = 0), alertsActions) }
    @Test fun alerts2x() = font2x { capture("first-run-alerts-2x-graphite", true) { AlertsStep(alerts(AlertsState.Before), alertsActions) } }

    @Test fun homeFinishSetupGraphite() = capture("first-run-home-finish-graphite", true) { UnfinishedHome("workstation", {}) }
    @Test fun homeFinishSetupPaper() = capture("first-run-home-finish-paper", false) { UnfinishedHome("workstation", {}) }
    @Test fun homeEmptyGraphite() = capture("first-run-home-empty-graphite", true) { EmptyHome({}) }
    @Test fun homeEmptyPaper() = capture("first-run-home-empty-paper", false) { EmptyHome({}) }

    private fun font2x(block: () -> Unit) {
        RuntimeEnvironment.setFontScale(2f)
        try {
            block()
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        captureRoboImage("src/test/snapshots/$name.png") { KuddeTheme(dark = dark) { content() } }
    }

    private companion object {
        const val PRESENTED = "SHA256:q3Vb8m2N4sR7tK0yL9wE1aZ5cX6vB3nM8jH2gF4dS0p"
    }
}
