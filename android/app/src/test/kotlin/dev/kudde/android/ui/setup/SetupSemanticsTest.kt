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
        assertTrue(texts().contains("Where is it?"))
        assertEquals("the bar is one element", 1, stepBars("Step 1 of 4"))
        show { KeyStep(KeyUi("workstation", "dev", KeyNotice.None, false, "cmd", false, true, true, false), noKey) }
        assertTrue(texts().contains("This phone's key"))
        assertEquals(1, stepBars("Step 2 of 4"))
        show { ConnectedStep(ConnectedUi("workstation", null, 0), {}, {}) }
        assertTrue(texts().contains("Connected"))
        assertEquals(1, stepBars("Step 3 of 4"))
        show { AlertsStep(AlertsUi("workstation", AlertsState.Before, 0), AlertsActions({}, {}, {}, {}, {}, {}, {}, {})) }
        assertTrue(texts().contains("Alerts (optional)"))
        assertEquals(1, stepBars("Step 4 of 4"))
        assertTrue("no separate step text", texts().none { it.startsWith("Step ") })
    }

    /** The step bar: one node whose spoken text is "Step n of 4", never four segments. */
    private fun stepBars(spoken: String) = semanticsNodes().count { spoken in it.descriptions() }

    /** A choice card: the clickable node whose first text is its title. */
    private fun card(title: String) = semanticsNodes().firstOrNull { it.clickable() && it.texts().firstOrNull() == title }

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
        val order = semanticsNodes().filter { it.clickable() }.mapNotNull { it.texts().firstOrNull() }
            .filter { it == "kudde adds it for you" || it == "Add it yourself" }
        assertEquals(listOf("kudde adds it for you", "Add it yourself"), order)
        assertTrue(card("kudde adds it for you")!!.click())
        assertTrue(card("Add it yourself")!!.click())
        assertEquals(listOf(KeyChoice.PASSWORD, KeyChoice.MANUAL), chosen)
    }

    @Test fun aWithdrawnPasswordChoiceCannotBeTapped() {
        val chosen = mutableListOf<KeyChoice>()
        show {
            KeyStep(KeyUi("workstation", "dev", KeyNotice.Rejected, true, null, false, false, false, true), noKey.copy(onChoose = { chosen += it }))
        }
        val node = card("kudde adds it for you")!!
        assertTrue(node.config.contains(SemanticsProperties.Disabled))
        assertTrue(chosen.isEmpty())
    }

    @Test fun eachCardIsOneStopThatReadsItsTitleThenItsDescription() {
        show { KeyStep(KeyUi("workstation", "dev", KeyNotice.Rejected, true, null, false, false, true, true), noKey) }
        val first = card("kudde adds it for you")!!
        assertEquals(
            listOf("kudde adds it for you", "kudde signs in once as dev with that account's password, adds the same key and signs out. The password is not stored."),
            first.texts(),
        )
        assertEquals(androidx.compose.ui.semantics.Role.Button, first.config.getOrNull(SemanticsProperties.Role))
        assertEquals(listOf("Add it yourself", "Run one command at the machine. kudde never sees a password."), card("Add it yourself")!!.texts())
    }

    @Test fun theCardsStackAtEverySize() {
        show { KeyStep(KeyUi("workstation", "dev", KeyNotice.Rejected, true, null, false, false, true, true), noKey) }
        val a = card("kudde adds it for you")!!.boundsInRoot
        val b = card("Add it yourself")!!.boundsInRoot
        assertTrue("one above the other", a.bottom <= b.top + 1f)
        assertEquals("same width", a.width, b.width, 1f)
    }

    @Test fun theManualDetailKeepsBothCardsToo() {
        show { KeyStep(KeyUi("workstation", "dev", KeyNotice.None, false, "cmd", false, true, true, false), noKey) }
        assertNotNull(card("kudde adds it for you"))
        assertNotNull(card("Add it yourself"))
    }

    @Test fun theSuccessMomentNamesTheFirstMachineOnlyOnce() {
        show { ConnectedStep(ConnectedUi("workstation", "0.1.16", 3, first = true), {}, {}) }
        assertTrue("workstation is connected." in texts())
        assertTrue("Your first machine is set up." in texts())
        show { ConnectedStep(ConnectedUi("workstation", "0.1.16", 3, first = false), {}, {}) }
        assertTrue("workstation is connected." in texts())
        assertTrue("Your first machine is set up." !in texts())
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
