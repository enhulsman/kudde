package dev.kudde.android.ui.setup

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.kudde.android.testing.BoundedFrames
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.testing.click
import dev.kudde.android.testing.clickable
import dev.kudde.android.testing.descriptions
import dev.kudde.android.testing.nodeSaying
import dev.kudde.android.testing.semanticsNodes
import dev.kudde.android.testing.texts
import dev.kudde.android.ui.addmachine.MachineInput
import dev.kudde.android.ui.machines.ReachText
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// What the first-setup screens say to a screen reader and do when tapped (tasks 4.1, 4.5a).
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class SetupSemanticsTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()

    @After fun close() { controller.pause().stop().destroy() }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        activity.setContent { KuddeTheme(dark = true) { content() } }
        BoundedFrames.settle(minFrames = 3)
    }

    private fun texts() = semanticsNodes().flatMap { it.texts() }

    private val typed = MachineInput("", "workstation", "22", "dev")
    private val noAddress = AddressActions({}, {}, {}, {})
    private val noKey = KeyActions({}, {}, {}, {}, {}, {}, {})

    @Test fun eachStepSaysItsNumberAndHeading() {
        show { AddressStep(AddressUi(typed, null, AddressStatus.Idle, null, false, null), noAddress) }
        assertTrue(texts().containsAll(listOf("Step 1 of 4", "Where is it?")))
        show { KeyStep(KeyUi("workstation", "dev", KeyNotice.None, false, "cmd", false, true, true, false), noKey) }
        assertTrue(texts().containsAll(listOf("Step 2 of 4", "This phone's key")))
        show { ConnectedStep(ConnectedUi("workstation", null, 0), {}, {}) }
        assertTrue(texts().containsAll(listOf("Step 3 of 4", "Connected")))
        show { AlertsStep(AlertsUi("workstation", AlertsState.Before, 0), AlertsActions({}, {}, {}, {}, {}, {}, {}, {})) }
        assertTrue(texts().containsAll(listOf("Step 4 of 4", "Alerts (optional)")))
    }

    @Test fun theWelcomeHasNoBackArrowAndNoSkip() {
        show { WelcomeScreen({}, {}) }
        assertNull(nodeSaying("Back"))
        val labels = semanticsNodes().filter { it.clickable() }.flatMap { it.texts() + it.descriptions() }
        assertTrue(labels.none { it.contains("skip", ignoreCase = true) || it == "Not now" })
        assertTrue("Set up a machine" in texts())
    }

    @Test fun theCommandIsOneNodeNamedSetupCommandAndNotALiveRegion() {
        show { KeyStep(KeyUi("workstation", "dev", KeyNotice.None, false, "line one\nline two", false, true, true, false), noKey) }
        val nodes = semanticsNodes().filter { "Setup command" in it.descriptions() }
        assertEquals(1, nodes.size)
        assertNull(nodes.single().config.getOrNull(SemanticsProperties.LiveRegion))
    }

    @Test fun aFailureIsOneLiveRegion() {
        show {
            AddressStep(AddressUi(typed, null, AddressStatus.Failed(ReachText("No answer from workstation.", "Check.")), null, false, null), noAddress)
        }
        val live = semanticsNodes().filter { it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite }
        assertEquals(1, live.size)
        assertTrue(live.single().texts().any { it.startsWith("No answer from workstation.") } || live.single().children.any { c -> c.texts().any { it.startsWith("No answer") } })
    }

    @Test fun theChoicesAreInOrderAndOpenTheirDetails() {
        val chosen = mutableListOf<KeyChoice>()
        show {
            KeyStep(KeyUi("workstation", "dev", KeyNotice.Rejected, true, null, false, false, true, true), noKey.copy(onChoose = { chosen += it }))
        }
        val order = semanticsNodes().filter { it.clickable() }.flatMap { it.texts() }
            .filter { it == "kudde adds it for you" || it == "Add it yourself" }
        assertEquals(listOf("kudde adds it for you", "Add it yourself"), order)
        assertTrue(nodeSaying("kudde adds it for you")!!.click())
        assertTrue(nodeSaying("Add it yourself")!!.click())
        assertEquals(listOf(KeyChoice.PASSWORD, KeyChoice.MANUAL), chosen)
    }

    @Test fun aWithdrawnPasswordChoiceCannotBeTapped() {
        val chosen = mutableListOf<KeyChoice>()
        show {
            KeyStep(KeyUi("workstation", "dev", KeyNotice.Rejected, true, null, false, false, false, true), noKey.copy(onChoose = { chosen += it }))
        }
        val node = nodeSaying("kudde adds it for you")!!
        assertTrue(node.config.contains(SemanticsProperties.Disabled))
        assertTrue(chosen.isEmpty())
    }

    @Test fun stepFourShowsTheRoutesInEveryStateAndStartsNothingByItself() {
        val taps = mutableListOf<String>()
        val actions = AlertsActions(
            onBack = {}, onTurnOn = { taps += "on" }, onAllow = { taps += "allow" }, onSendTest = { taps += "test" },
            onGuide = {}, onShowAgents = { taps += "agents" }, onStartTerminal = { taps += "start" }, onDone = { taps += "done" },
        )
        for (state in listOf(AlertsState.Before, AlertsState.NotAllowed, AlertsState.NoDistributor, AlertsState.TestMissed, AlertsState.TestReceived)) {
            show { AlertsStep(AlertsUi("workstation", state, 2), actions) }
            assertNotNull("$state", nodeSaying("Show my agents"))
        }
        show { AlertsStep(AlertsUi("workstation", AlertsState.Before, 0), actions) }
        assertNotNull(nodeSaying("Start a terminal or agent"))
        assertNotNull(nodeSaying("Done"))
        assertTrue(taps.isEmpty())
        nodeSaying("Turn on alerts")!!.click()
        nodeSaying("Done")!!.click()
        assertEquals(listOf("on", "done"), taps)
    }
}
