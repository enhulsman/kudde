package dev.kudde.android.ui.agent

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import dev.kudde.android.KuddeApp
import dev.kudde.android.testing.click
import dev.kudde.android.testing.nodeSaying
import dev.kudde.android.ui.agents.AgentsRoute
import dev.kudde.android.ui.flows.MachineFlows
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.testing.KuddeTestRunner
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer

/** The regression is a second composition after a real route's IO and window have been torn down. */
@RunWith(KuddeTestRunner::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class AgentRouteIsolationTest {
    private val rigs = mutableListOf<AgentRouteRig>()
    private val activities = mutableListOf<ActivityController<ComponentActivity>>()

    @After
    fun close() {
        var failure: Throwable? = null
        fun clean(action: () -> Unit) {
            try { action() } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
        }
        activities.reversed().forEach { c -> clean { c.pause().stop().destroy() } }
        rigs.reversed().forEach { rig -> clean { rig.close() } }
        failure?.let { throw it }
    }

    private fun rig(): AgentRouteRig = AgentRouteRig().also { rigs += it; it.install() }

    private fun show(rig: AgentRouteRig, chat: Boolean): ActivityController<ComponentActivity> {
        rig.app.chatView.choose(chat)
        rig.home.open(rig.key)
        val c = Robolectric.buildActivity(ComponentActivity::class.java)
        activities += c
        c.setup()
        c.get().setContent {
            val flows = remember { MachineFlows(rig.app.hubHost) }
            KuddeTheme(dark = true) { AgentsRoute(rig.app, flows, rig.home, ports = rig.ports) }
        }
        rig.await("the real route attaches") { rig.ports.opens.size == 1 }
        return c
    }

    @Test
    fun c_closeRestoresClockSettingsApplicationStateAndAccessibility() {
        val app = RuntimeEnvironment.getApplication() as KuddeApp
        val cache = app.terminalCache
        val states = app.chatStates
        val zoom = app.terminalZoom
        val choice = app.chatView
        val snapshot = app.hubHost.snapshot.value
        val paused = ShadowChoreographer.isPaused()
        val motion = Settings.Global.getString(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        val manager = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = manager.isEnabled
        val exploration = manager.isTouchExplorationEnabled
        val events = shadowOf(manager).sentAccessibilityEvents.toList()
        val idleField = app.javaClass.getDeclaredField("idle").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val idle = idleField.get(app) as MutableStateFlow<Int>
        val idleBefore = idle.value
        val fixture = rig()
        app.chatView.choose(!choice.chat)
        app.terminalZoom[fixture.key] = 1.25f
        app.chatStates.open(fixture.key).draft = "temporary draft"
        idle.value++
        shadowOf(manager).setEnabled(true)
        shadowOf(manager).setTouchExplorationEnabled(!exploration)
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        event.text.add("temporary accessibility event")
        manager.sendAccessibilityEvent(event)
        fixture.close()
        assertSame(cache, app.terminalCache)
        assertSame(states, app.chatStates)
        assertSame(zoom, app.terminalZoom)
        assertSame(choice, app.chatView)
        assertEquals(snapshot, app.hubHost.snapshot.value)
        assertEquals(idleBefore, idle.value)
        assertEquals(paused, ShadowChoreographer.isPaused())
        assertEquals(motion, Settings.Global.getString(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE))
        assertEquals(enabled, manager.isEnabled)
        assertEquals(exploration, manager.isTouchExplorationEnabled)
        assertEquals(events, shadowOf(manager).sentAccessibilityEvents)
    }

    @Test
    fun a_closeWaitsForAPollAndTheNextRoutesMenuStillComposes() {
        val first = rig()
        val firstActivity = show(first, chat = true)
        first.await("a fake hub wait is in flight") { first.ports.io.inFlight > 0 }
        firstActivity.pause().stop().destroy()
        activities.remove(firstActivity)
        first.close()
        assertEquals("no IO continuation may outlive teardown", 0, first.ports.io.inFlight)

        val next = rig()
        show(next, chat = false)
        assertTrue(nodeSaying("More options")!!.click())
        next.frames()
        assertNotNull("a later route can compose its Popup", nodeSaying("Changes"))
        assertTrue(nodeSaying("Changes")!!.click())
        next.frames()
        assertEquals(1, next.ports.changes.size)
        assertNotNull("a later route still recomposes after the menu action", nodeSaying("Changes"))
    }

    @Test
    fun b_afterThePreviousTestsResetTheNextRoutesMenuStillComposes() {
        // Cross the runner's looper reset as the mixed run does; two activities
        // in a single test do not exercise that boundary.
        val next = rig()
        show(next, chat = false)
        assertTrue(nodeSaying("More options")!!.click())
        next.frames()
        assertNotNull("the menu composes after the previous test's reset", nodeSaying("Changes"))
        assertTrue(nodeSaying("Changes")!!.click())
        next.frames()
        assertEquals(1, next.ports.changes.size)
        assertNotNull(nodeSaying("Changes"))
    }

    @Test
    fun d_awaitCanObserveARealClockTimeoutLongerThanItsOldBudget() {
        val fixture = rig()
        val due = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(650)
        // AndroidUiDispatcher does not implement Delay: pointer-input timeouts
        // can use the coroutine default delay's real clock, independently of Robolectric.
        fixture.await("a timeout beyond the old 500 ms wall-clock budget", realWaitMs = 1_000) {
            System.nanoTime() >= due
        }
    }
}
