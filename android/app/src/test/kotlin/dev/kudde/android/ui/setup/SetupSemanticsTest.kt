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
import dev.kudde.android.testing.role
import dev.kudde.android.testing.semanticsNodes
import dev.kudde.android.testing.texts
import dev.kudde.android.testing.unmergedSemanticsNodes
import androidx.compose.ui.semantics.SemanticsActions
import org.robolectric.RuntimeEnvironment
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
        assertTrue(semanticsNodes().any { it.descriptions().any { d -> d.endsWith(", Where is it?") } })
        assertEquals("the bar and heading are one element", 1, stepBars("Step 1 of 4, Where is it?"))
        show { KeyStep(KeyUi("workstation", "dev", KeyNotice.None, false, "cmd", false, true, true, false), noKey) }
        assertTrue(semanticsNodes().any { it.descriptions().any { d -> d.endsWith(", This phone's key") } })
        assertEquals(1, stepBars("Step 2 of 4, This phone's key"))
        show { ConnectedStep(ConnectedUi("workstation", null, 0), {}, {}) }
        assertTrue(semanticsNodes().any { it.descriptions().any { d -> d.endsWith(", Connected") } })
        assertEquals(1, stepBars("Step 3 of 4, Connected"))
        show { AlertsStep(AlertsUi("workstation", AlertsState.Before, 0), AlertsActions({}, {}, {}, {}, {}, {}, {}, {})) }
        assertTrue(semanticsNodes().any { it.descriptions().any { d -> d.endsWith(", Alerts (optional)") } })
        assertEquals(1, stepBars("Step 4 of 4, Alerts (optional)"))
        show { AlertsStep(AlertsUi("workstation", AlertsState.On, 0), AlertsActions({}, {}, {}, {}, {}, {}, {}, {})) }
        assertTrue(texts().contains("Alerts are already on for this phone."))
        assertTrue(texts().none { it == "Alerts are on on this phone." })
        assertTrue("no separate step text", texts().none { it.startsWith("Step ") })
    }

    @Test fun theUnfinishedHomeTellsARefusedKeyFromAnUnfinishedSetup() {
        show { UnfinishedHome("workstation", {}) }
        assertTrue(texts().contains("Its setup is not finished."))
        assertTrue(texts().none { it == "It no longer accepts this phone's key." })
        show { UnfinishedHome("workstation", {}, keyRefused = true) }
        assertTrue(texts().contains("workstation is not connected yet"))
        assertTrue(texts().contains("It no longer accepts this phone's key."))
        assertTrue(texts().none { it == "Its setup is not finished." })
    }

    private data class StepCase(val step: Int, val heading: String, val footer: String, val screen: @androidx.compose.runtime.Composable () -> Unit)

    private val cases = listOf(
        StepCase(1, "Where is it?", "Continue") { AddressStep(AddressUi(typed, null, AddressStatus.Idle, null, false, null), noAddress) },
        StepCase(2, "This phone's key", "Try again") {
            KeyStep(KeyUi("workstation", "dev", KeyNotice.Rejected, true, null, false, false, true, true), noKey)
        },
        StepCase(3, "Connected", "Continue") { ConnectedStep(ConnectedUi("workstation", "0.1.16", 3, first = true), {}, {}) },
        StepCase(4, "Alerts (optional)", "Turn on alerts") {
            AlertsStep(AlertsUi("workstation", AlertsState.Before, 0), AlertsActions({}, {}, {}, {}, {}, {}, {}, {}))
        },
    )

    /** Task 6.2: the step bar and the heading are one heading stop, "Step n of 4, <heading>", with no image role. */
    @Test fun eachStepBarAndHeadingIsOneHeadingStopWithNoImageRole() {
        for (case in cases) {
            show(case.screen)
            val spoken = "Step ${case.step} of 4, ${case.heading}"
            val nodes = semanticsNodes().filter { spoken in it.descriptions() }
            assertEquals(spoken, 1, nodes.size)
            val node = nodes.single()
            assertTrue("$spoken is a heading", node.config.contains(SemanticsProperties.Heading))
            assertNull("$spoken has no role", node.role())
            assertEquals("no stop reads only the step", 0, stepBars("Step ${case.step} of 4"))
            assertTrue("the heading text is not a second stop", texts().none { it == case.heading })
        }
    }

    /** Arriving on a step is announced once as that pane's title: TalkBack speaks a pane title when the pane appears. */
    @Test fun eachStepIsAPaneTitledWithItsNumberAndHeading() {
        for (case in cases) {
            show(case.screen)
            val spoken = "Step ${case.step} of 4, ${case.heading}"
            val panes = semanticsNodes().filter { it.config.getOrNull(SemanticsProperties.PaneTitle) == spoken }
            assertEquals(spoken, 1, panes.size)
        }
    }

    /** The traversal index of the screen part that holds [node] (the nearest ancestor that sets one). */
    private fun order(node: androidx.compose.ui.semantics.SemanticsNode?): Float {
        var n = node
        while (n != null) {
            n.config.getOrNull(SemanticsProperties.TraversalIndex)?.let { return it }
            n = n.parent
        }
        return 0f
    }

    /** Task 6.2: swiping forward goes heading, body, footer, and only then Back (it was Back first, Continue last). */
    @Test fun theFooterFollowsTheBodyAndBackComesLastOnEveryStep() {
        for (case in cases) {
            show(case.screen)
            val heading = semanticsNodes().firstOrNull { "Step ${case.step} of 4, ${case.heading}" in it.descriptions() }
            val footer = semanticsNodes().firstOrNull { it.clickable() && it.texts().firstOrNull() == case.footer }
            val back = nodeSaying("Back")
            assertNotNull("step ${case.step} heading", heading)
            assertNotNull("step ${case.step} footer ${case.footer}", footer)
            assertNotNull("step ${case.step} back", back)
            assertTrue("step ${case.step}: heading before footer", order(heading) < order(footer))
            assertTrue("step ${case.step}: footer before Back", order(footer) < order(back))
        }
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

    private fun tileLines(text: String): Int? {
        val node = unmergedSemanticsNodes().firstOrNull { it.texts() == listOf(text) } ?: return null
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        return results.firstOrNull()?.lineCount
    }

    @Test fun eachTileIsOneItemReadValueThenLabel() {
        show { ConnectedStep(ConnectedUi("workstation", "0.1.16", 3, first = true, terminals = 5), {}, {}) }
        val spoken = semanticsNodes().flatMap { it.descriptions() }
        assertTrue(spoken.toString(), spoken.containsAll(listOf("herdr-core 0.1.16", "3 agents running", "5 terminals open")))
        for (label in listOf("herdr-core 0.1.16", "3 agents running", "5 terminals open")) {
            assertEquals(label, 1, semanticsNodes().count { label in it.descriptions() })
        }
    }

    @Test fun tilesUseTheSingularForOne() {
        show { ConnectedStep(ConnectedUi("workstation", "0.1.16", 1, first = false, terminals = 1), {}, {}) }
        val spoken = semanticsNodes().flatMap { it.descriptions() }
        assertTrue(spoken.toString(), spoken.containsAll(listOf("1 agent running", "1 terminal open")))
        assertTrue(spoken.none { it == "1 agents running" || it == "1 terminals open" })
    }

    @Test fun theCountsUpdatePolitelyAndTheVersionStaysStill() {
        show { ConnectedStep(ConnectedUi("workstation", "0.1.16", 3, first = true, terminals = 5), {}, {}) }
        fun live(label: String) = semanticsNodes().first { label in it.descriptions() }.config.getOrNull(SemanticsProperties.LiveRegion)
        assertEquals(LiveRegionMode.Polite, live("3 agents running"))
        assertEquals(LiveRegionMode.Polite, live("5 terminals open"))
        assertNull(live("herdr-core 0.1.16"))
    }

    @Test fun withoutAnAgentTheTilesStayAndTheLineSaysWhatToDo() {
        show { ConnectedStep(ConnectedUi("workstation", "0.1.16", 0, first = true, terminals = 1), {}, {}) }
        val spoken = semanticsNodes().flatMap { it.descriptions() }
        assertTrue("0 agents running" in spoken)
        assertTrue(texts().any { it.startsWith("No agents are running there yet.") })
    }

    @Test fun anUnknownVersionLeavesOutItsTile() {
        show { ConnectedStep(ConnectedUi("workstation", null, 2, first = true, terminals = 2), {}, {}) }
        assertTrue(semanticsNodes().flatMap { it.descriptions() }.none { it.startsWith("herdr-core") })
    }

    @Test fun threeTilesShareARowAtNormalTextSize() {
        show { ConnectedStep(ConnectedUi("workstation", "0.1.16", 3, first = true, terminals = 5), {}, {}) }
        val b = listOf("herdr-core 0.1.16", "3 agents running", "5 terminals open").map { l -> semanticsNodes().first { l in it.descriptions() }.boundsInRoot }
        assertTrue("one row", b.all { it.top == b[0].top })
        assertTrue("left to right", b[0].right <= b[1].left + 1f && b[1].right <= b[2].left + 1f)
    }

    @Test fun atLargeTextTheTilesStackAndHerdrCoreStaysOnOneLine() {
        RuntimeEnvironment.setFontScale(2f)
        try {
            show { ConnectedStep(ConnectedUi("workstation", "0.1.16", 3, first = true, terminals = 5), {}, {}) }
            val b = listOf("herdr-core 0.1.16", "3 agents running", "5 terminals open").map { l -> semanticsNodes().first { l in it.descriptions() }.boundsInRoot }
            assertTrue("one above the other", b[0].bottom <= b[1].top + 1f && b[1].bottom <= b[2].top + 1f)
            assertEquals("same width", b[0].width, b[2].width, 1f)
            assertEquals(1, tileLines("herdr-core"))
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
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
