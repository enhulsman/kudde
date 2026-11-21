package dev.kudde.android.ui.agent

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kudde.android.testing.*
import dev.kudde.android.ui.agents.AgentsHome
import dev.kudde.android.ui.agents.AgentsRoute
import dev.kudde.android.ui.flows.MachineFlows
import dev.kudde.android.ui.theme.KuddeTheme
import org.robolectric.Robolectric
import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.terminal.GridSize
import dev.kudde.android.ui.terminal.TerminalMetrics
import dev.kudde.android.ui.terminal.phoneGrid
import dev.kudde.android.ui.theme.KuddeLayout
import dev.kudde.android.ui.theme.KuddeType
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import uniffi.kudde_mobile.ChatFound

@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class AgentRouteSizeTest {
    private fun List<String>.sizes() = filter { it.startsWith("size ") }
    private fun FakeAgentPorts.Open.grid() = GridSize(cols.toInt(), rows.toInt())

    // passes today: heldGrid blocks newly measured sizes once the original grid has settled.
    @Test fun noSizeWhileCoveredFromASettledGrid() = AgentRouteScenario().use {
        it.show()
        it.rig.advanceBoth(TerminalMetrics.SETTLE_MS * 2)
        val calls = it.opened.view.calls.size
        it.changes()
        it.resize(650)
        it.rig.advanceBoth(TerminalMetrics.SETTLE_MS * 2)
        assertEquals("settled terminal sent phoneSize under Changes", emptyList<String>(), it.opened.view.calls.drop(calls).sizes())
        assertFalse(it.opened.view.calls.drop(calls).contains("lead"))
        assertFalse(it.opened.view.calls.contains("detach"))
    }

    // red until 5.2
    @Test fun noSizeWhenTheCoverOpensMidSettleAndItIsSentOnceOnReturn() = AgentRouteScenario().use {
        it.show()
        val view = it.opened.view
        val before = view.calls.size
        it.resize(650)
        assertTrue("fixture size has not settled yet", view.calls.drop(before).sizes().isEmpty())
        it.changes()
        it.rig.advanceBoth(TerminalMetrics.SETTLE_MS * 2)
        assertEquals("pending phoneSize escaped while Changes covers a mid-settle terminal", emptyList<String>(), view.calls.drop(before).sizes())
        it.back()
        it.rig.advanceBoth(TerminalMetrics.SETTLE_MS * 2)
        assertEquals("returning sends the current measured grid once", 1, view.calls.drop(before).sizes().size)
        it.rig.advanceBoth(TerminalMetrics.SETTLE_MS * 2)
        assertEquals("no duplicate size after return settles", 1, view.calls.drop(before).sizes().size)
    }

    // red until 5.2
    @Test fun aPollDecidingTheTerminalUnderChangesTakesNothing() = AgentRouteScenario().use {
        val chat = it.rig.ports.chat(it.rig.key)
        chat.current = chat.current.copy(answered = false, found = ChatFound.NONE)
        it.show(chat = true, settle = false)
        assertFalse("fixture opens watching before the desk answers", it.opened.lead)
        it.changes()
        val view = it.opened.view
        val before = view.calls.size
        chat.current = chat.current.copy(answered = true)
        it.rig.await("first answered poll decides terminal under Changes") { it.state.poll?.answered == true }
        fun untouched() {
            val calls = view.calls.drop(before)
            assertFalse("poll led the terminal while Changes still covers it", calls.contains("lead"))
            assertTrue("poll sent phoneSize while Changes still covers it", calls.sizes().isEmpty())
        }
        untouched()
        it.rig.advanceBoth(400)
        untouched()
        it.back()
        it.rig.advanceBoth(TerminalMetrics.SETTLE_MS * 2)
        val calls = view.calls.drop(before)
        assertEquals("one measured phoneSize on terminal return", 1, calls.sizes().size)
        assertEquals("one takeover on terminal return", 1, calls.count { c -> c == "lead" })
        assertTrue("real grid must be sent before lead", calls.indexOfFirst { c -> c.startsWith("size ") } < calls.indexOf("lead"))
    }

    // red until 5.2
    @Test fun aChatFirstAgentAttachesWatchingAtTheSeedAndNeverLeadsOnIt() = AgentRouteScenario().use {
        // Give the process a deliberately different last phone grid before a first chat visit.
        it.heightDp = 650
        it.show()
        val seed = it.opened.grid()
        it.rig.app.chatView.choose(true)
        it.heightDp = 915
        val other = AgentKey("workstation", "t2")
        it.rig.ports.chat(other).current = it.rig.ports.chat(other).current.copy(agent = "codex")
        it.rig.home.open(other)
        it.rig.await("first chat visit attaches its watched terminal") { it.rig.ports.opens.size == 2 }
        assertFalse("chat-first attach must watch", it.opened.lead)
        assertEquals("chat-first attach must use last phone grid seed, not measure a hidden terminal", seed, it.opened.grid())
        it.rig.advanceBoth(800)
        assertTrue("seed must never become a takeover size", it.opened.view.calls.none { c -> c == "lead" || c.startsWith("size ") })
        assertNotNull("chat is showing", nodeSaying("Terminal"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun AgentRouteScenario.idleAndResume() {
        val old = rig.ports.opens.last { it.key == rig.home.openKey }.view
        val count = rig.ports.opens.size
        controller!!.pause().stop()
        rig.frames()
        val field = rig.app.javaClass.getDeclaredField("idle").apply { isAccessible = true }
        val idle = field.get(rig.app) as MutableStateFlow<Int>
        idle.value++
        rig.await("background idle detaches the old generation") { old.calls.contains("detach") }
        ShadowChoreographer.setPaused(false)
        try { controller!!.start().resume().visible() } finally { PopupSafeClock.paused() }
        rig.await("foreground reopens under the real Changes cover") { rig.ports.opens.size == count + 1 }
    }

    /** A different real route in a second window measures a distinct process-wide grid. */
    private fun AgentRouteScenario.measureAnotherTerminal(height: Int): GridSize {
        val previousChoice = rig.app.chatView.chat
        val count = rig.ports.opens.size
        val otherHome = AgentsHome(rig.app.hubHost, now = rig.ports::now) { true }
        otherHome.open(AgentKey("workstation", "t2"))
        rig.app.chatView.choose(false)
        ShadowChoreographer.setPaused(false)
        val other = try { Robolectric.buildActivity(ComponentActivity::class.java).setup() }
            finally { PopupSafeClock.paused() }
        try {
            other.get().setContent {
                val flows = remember { MachineFlows(rig.app.hubHost) }
                KuddeTheme(dark = true) {
                    Box(Modifier.fillMaxWidth().height(height.dp)) {
                        AgentsRoute(rig.app, flows, otherHome, ports = rig.ports)
                    }
                }
            }
            rig.await("second real terminal measures the process grid") { rig.ports.opens.size == count + 1 }
            rig.advanceBoth(TerminalMetrics.SETTLE_MS * 2)
            return rig.ports.opens.last().grid()
        } finally {
            try { other.pause().stop().destroy(); rig.frames() }
            finally { rig.app.chatView.choose(previousChoice) }
        }
    }

    // red until 5.2
    @Test fun reopenUnderAChangesCoverOpensWatching() = AgentRouteScenario().use {
        it.show()
        val grid = it.opened.grid()
        val old = it.opened.view
        it.changes()
        it.idleAndResume()
        assertNotNull(nodeSaying("Changes"))
        assertFalse("reopen under Changes opened leading instead of watching", it.opened.lead)
        assertEquals("reopen keeps its own last grid seed", grid, it.opened.grid())
        assertEquals(1, old.calls.count { c -> c == "detach" })
        assertTrue(it.opened.view.calls.none { c -> c == "lead" || c.startsWith("size ") })
        it.back()
        it.rig.advanceBoth(TerminalMetrics.SETTLE_MS * 2)
        assertEquals("return leads once", 1, it.opened.view.calls.count { c -> c == "lead" })
    }

    // red until 5.2
    @Test fun theSeedOrderIsOwnGridThenLastPhoneGridThenEstimate() {
        AgentRouteScenario().use {
            // No preceding terminal: the seed must be the documented host-constraint estimate.
            it.show(chat = true)
            val d = it.activity.resources.displayMetrics
            val estimate = phoneGrid(it.measuredHost.width.toFloat(),
                it.measuredHost.height - (KuddeLayout.terminalTitleHeight.value + 2 * KuddeLayout.keysRowHeight.value) * d.density,
                KuddeLayout.terminalPaddingX.value * d.density, KuddeLayout.terminalPaddingTop.value * d.density,
                KuddeType.terminalPhone.fontSize.value * d.scaledDensity)
            assertEquals("cold chat attach uses the host estimate", estimate, it.opened.grid())
            assertFalse(it.opened.lead)
        }
        AgentRouteScenario().use {
            it.heightDp = 650
            it.show()
            val processGrid = it.opened.grid()
            it.rig.app.chatView.choose(true)
            it.heightDp = 915
            it.rig.home.open(AgentKey("workstation", "t2"))
            it.rig.await("chat-first next agent attaches") { it.rig.ports.opens.size == 2 }
            assertEquals("last phone grid must outrank a fresh host estimate", processGrid, it.opened.grid())
        }
        AgentRouteScenario().use {
            it.heightDp = 650
            it.show()
            val ownGrid = it.opened.grid()
            it.changes()
            val otherGrid = it.measureAnotherTerminal(915)
            assertNotEquals("fixture own grid differs from the process's newest measured grid", ownGrid, otherGrid)
            it.resize(915)
            it.idleAndResume()
            assertEquals("own last grid must outrank last phone grid and changed estimate on reopen", ownGrid, it.opened.grid())
            assertFalse("own grid is a watching seed under Changes", it.opened.lead)
        }
    }
}
