package dev.kudde.android.ui.agent

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import dev.kudde.android.testing.PopupSafeClock
import dev.kudde.android.testing.texts
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.testing.click
import dev.kudde.android.testing.descriptions
import dev.kudde.android.testing.nodeSaying
import dev.kudde.android.testing.semanticsNodes
import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.agents.AgentsHome
import dev.kudde.android.ui.agents.AgentsRoute
import dev.kudde.android.ui.chat.Attachment
import dev.kudde.android.ui.flows.MachineFlows
import dev.kudde.android.ui.terminal.TerminalGrid
import dev.kudde.android.ui.terminal.TerminalMetrics
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.KuddeType
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.kudde_mobile.ChatRow

/** The real host, including its nested chat, Changes and overview routes (screen-stack 2.1). */
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class AgentRouteCharacterizationTest {
    private lateinit var rig: AgentRouteRig
    private var controller: ActivityController<AgentRouteTestActivity>? = null
    private val activity get() = controller!!.get()
    private val state get() = rig.app.chatStates.open(rig.key)
    private val opened get() = rig.ports.opens.single()
    private var routeCallbacks: Application.ActivityLifecycleCallbacks? = null

    @Before
    fun prepare() {
        rig = AgentRouteRig()
        rig.install()
        // Install content during every activity creation, including recreation,
        // before Robolectric's recreate() attaches and focuses its new window.
        routeCallbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is AgentRouteTestActivity) composeRoutes(activity)
            }
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }.also(rig.app::registerActivityLifecycleCallbacks)
    }

    @After
    fun close() {
        try {
            controller?.let { c ->
                val now = SystemClock.uptimeMillis()
                val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                try {
                    try { c.get().window.decorView.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
                } finally {
                    c.pause().stop().destroy()
                }
            }
        } finally {
            if (::rig.isInitialized) {
                try {
                    routeCallbacks?.let(rig.app::unregisterActivityLifecycleCallbacks)
                    routeCallbacks = null
                } finally {
                    rig.close()
                }
            }
        }
    }

    private fun composeRoutes(host: ComponentActivity) {
        // The same saveable home and snapshot forwarding used by the app's Root.
        // This is wiring around AgentsRoute, not a replacement for a destination.
        host.setContent {
            val home = rememberSaveable(saver = AgentsHome.saver(rig.app.hubHost, isForeground = { true })) { rig.home }
            val snapshot by rig.app.hubHost.snapshot.collectAsState()
            LaunchedEffect(snapshot) { home.onSnapshot(snapshot) }
            SideEffect { rig.home = home }
            val flows = remember { MachineFlows(rig.app.hubHost) }
            KuddeTheme(dark = true) { AgentsRoute(rig.app, flows, home, ports = rig.ports) }
        }
    }

    private fun recreate() {
        // Recreation relaunches the activity, which needs the Choreographer running
        // (a paused one NPEs in windowFocusChanged); frames are stepped by hand again after.
        org.robolectric.shadows.ShadowChoreographer.setPaused(false)
        try { controller!!.recreate() } finally { PopupSafeClock.paused() }
    }

    private fun show(chat: Boolean = false) {
        rig.app.chatView.choose(chat)
        rig.home.open(rig.key)
        assertFalse("a row open has no step dwell", rig.home.arrivedByStep)
        controller = Robolectric.buildActivity(AgentRouteTestActivity::class.java)
        controller!!.setup()
        rig.await("the real terminal attaches") { rig.ports.opens.size == 1 }
        rig.advance(TerminalMetrics.SETTLE_MS * 2)
    }

    private fun tap(label: String) {
        val node = nodeSaying(label) ?: error("No node saying $label")
        assertTrue("$label is clickable", node.click())
        rig.frames()
    }

    private fun changes() {
        tap("More options")
        tap("Changes")
        assertNotNull(nodeSaying("Changes"))
        assertEquals(rig.home.openKey, rig.ports.changes.last())
    }

    private fun overview() {
        val node = semanticsNodes().single { n -> n.descriptions().any { it.startsWith("Tab overview,") } }
        assertTrue(node.click())
        rig.frames()
        assertNotNull("the overview's own tile", nodeSaying("Pricing page copy"))
        assertNull("the title bar is covered", nodeSaying("More options"))
    }

    private fun back() {
        activity.onBackPressedDispatcher.onBackPressed()
        rig.frames()
    }

    private fun assertBase(chat: Boolean) {
        assertEquals(rig.key, rig.home.openKey)
        assertNull(nodeSaying("Changes"))
        // The switch points to the other view.
        assertNotNull(nodeSaying(if (chat) "Terminal" else "Chat"))
        assertNotNull(nodeSaying("More options"))
    }

    private fun edit(text: String) {
        val field = semanticsNodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
        assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text)))
        rig.frames()
    }

    private fun fieldText(): String = semanticsNodes()
        .single { it.config.getOrNull(SemanticsActions.SetText) != null }
        .config[SemanticsProperties.EditableText].text

    private fun send(action: Int, x: Float, y: Float, down: Long) {
        val pointers = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val points = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        })
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, pointers, points,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        activity.window.decorView.dispatchTouchEvent(event)
        event.recycle()
        rig.frames(2)
    }

    /** The layer's clickable root merges its title with the text below, so the title is one of several. */
    private fun selectionLayerShown() = semanticsNodes().any { "Select text to copy" in it.texts() }

    /**
     * Compose lays out new nodes whose size cannot change their parent's on the next draw, not on
     * a layout request, and this Robolectric surface never draws by itself: draw the window once.
     */
    private fun drawn() {
        val view = activity.window.decorView
        view.draw(Canvas(Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)))
    }

    private fun terminalBounds() = semanticsNodes().first { "Terminal" in it.descriptions() }.boundsInWindow

    private fun drag(dy: Float) {
        val b = terminalBounds()
        val t = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, b.center.x, b.center.y, t)
        send(MotionEvent.ACTION_MOVE, b.center.x, b.center.y + dy, t)
        send(MotionEvent.ACTION_UP, b.center.x, b.center.y + dy, t)
    }

    @Test
    fun backFromChangesReturnsToTheTerminal() {
        show()
        changes()
        back()
        assertBase(chat = false)
        assertEquals(1, rig.ports.opens.size)
        assertFalse(opened.view.calls.contains("detach"))
    }

    @Test
    fun backFromChangesReturnsToTheChat() {
        show(chat = true)
        changes()
        back()
        assertBase(chat = true)
        assertEquals(1, rig.ports.opens.size)
        assertFalse(opened.view.calls.contains("detach"))
    }

    @Test
    fun backFromTheOverviewReturnsToItsView() {
        show()
        overview()
        back()
        assertBase(chat = false)
        tap("Chat")
        overview()
        back()
        assertBase(chat = true)
        assertEquals(1, rig.ports.opens.size)
    }

    private fun leavesOnce(chat: Boolean) {
        show(chat)
        val view = opened.view
        back()
        assertNull(rig.home.openKey)
        assertEquals(1, view.calls.count { it == "detach" })
        assertEquals(1, view.calls.count { it == "destroy" })
        back()
        rig.advance(800)
        assertEquals(1, view.calls.count { it == "detach" })
        assertEquals(1, rig.ports.opens.size)
    }

    @Test fun backFromTheTerminalLeavesOnce() = leavesOnce(chat = false)
    @Test fun backFromTheChatLeavesOnce() = leavesOnce(chat = true)

    @Test
    fun theComposeSheetAndSelectionCloseBeforeLeaving() {
        show()
        tap("Compose")
        edit("unsent text")
        back()
        assertEquals(rig.key, rig.home.openKey)
        assertNull(nodeSaying("Paste"))
        tap("Compose")
        assertEquals("Back discards the compose text today", "", fieldText())
        back()
        val b = terminalBounds()
        assertTrue("the real terminal has measured a grid", opened.cols.toInt() > 0 && opened.rows.toInt() > 0)
        assertTrue("the gesture lands inside the laid-out canvas", b.width > 0f && b.height > 0f)
        val t = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, b.center.x, b.center.y, t)
        // Compose times a long press with delays on a real-time thread, each resuming
        // on the main looper (TerminalSwitchTest): hold in real time, letting frames run.
        rig.await("a held finger opens the real selection layer",
            realWaitMs = ViewConfiguration.getLongPressTimeout().toLong() * 2 + 250) { selectionLayerShown() }
        send(MotionEvent.ACTION_UP, b.center.x, b.center.y, t)
        assertTrue(selectionLayerShown())
        back()
        assertFalse(selectionLayerShown())
        assertEquals(rig.key, rig.home.openKey)
        assertFalse(opened.view.calls.contains("detach"))
        back()
        assertNull(rig.home.openKey)
        assertEquals(1, opened.view.calls.count { it == "detach" })
    }

    @Test
    fun composeTextSurvivesChangesAndTheOverview() {
        show()
        tap("Compose")
        edit("keep this compose text")
        changes()
        back()
        assertEquals("keep this compose text", fieldText())
        overview()
        back()
        assertEquals("keep this compose text", fieldText())
        assertEquals(1, rig.ports.opens.size)
        assertTrue(opened.view.calls.none { it == "detach" || it.startsWith("paste ") })
    }

    @Test
    fun terminalScrollbackPlaceSurvivesChangesAndTheOverview() {
        show()
        // A partial row on each side of a cover detects losing the screen-local
        // RowScroller remainder, as well as losing the native scrollback position.
        val height = KuddeType.terminalPhone.fontSize.value * activity.resources.displayMetrics.scaledDensity * TerminalMetrics.LINE_EM
        val dragDistance = height * 2.8f
        // A 0.8-row drag is below Robolectric's touch slop. Both gestures must
        // cross slop; their 0.8-row remainders still distinguish keeping vs resetting.
        assertTrue("each drag crosses touch slop", dragDistance > ViewConfiguration.get(activity).scaledTouchSlop)
        for (cover in listOf<() -> Unit>(::changes, ::overview)) {
            drag(dragDistance)
            val before = opened.offset
            assertTrue("scrolled away from live", before > 0)
            cover()
            // A frame from the desk while covered still describes the same place.
            opened.paint("new output while reading history")
            rig.frames()
            back()
            assertNotNull(nodeSaying("Live"))
            assertEquals(before, opened.offset)
            val calls = opened.view.calls.size
            drag(dragDistance)
            assertEquals("fractional row survives the cover", before + 3, opened.offset)
            assertTrue(opened.view.calls.drop(calls).any { it == "scroll -3" })
            tap("Live")
        }
        assertEquals(1, rig.ports.opens.size)
        assertFalse(opened.view.calls.contains("detach"))
    }

    @Test
    fun noDetachNoPhoneSizeNoLeadWhileACoverShows() {
        show()
        for (chat in listOf(false, true)) {
            if (chat) tap("Chat")
            rig.advance(TerminalMetrics.SETTLE_MS * 2)
            for (cover in listOf<() -> Unit>(::changes, ::overview)) {
                cover()
                val before = opened.view.calls.toList()
                rig.advance(TerminalMetrics.SETTLE_MS * 2)
                assertEquals(before, opened.view.calls)
                back()
            }
        }
        assertEquals(1, rig.ports.opens.size)
    }

    @Test
    fun terminalOverChatBaseWatchesOverTerminalBaseLeads() {
        show(chat = true)
        assertFalse("chat-first attach watches", opened.lead)
        assertTrue("no takeover while the chat shows", opened.view.calls.none { it == "lead" })
        val before = opened.view.calls.size
        tap("Terminal")
        assertTrue(opened.view.calls.drop(before).contains("lead"))
        val switched = opened.view.calls.size
        tap("Chat")
        assertTrue(opened.view.calls.drop(switched).contains("watch"))
        assertEquals(1, rig.ports.opens.size)
        assertFalse(opened.view.calls.contains("detach"))
    }

    @Test
    fun aTileOfAnotherTerminalOpensItAndItsOwnTileCloses() {
        show()
        overview()
        tap("Pricing page copy")
        assertBase(chat = false)
        assertEquals(1, rig.ports.opens.size)
        overview()
        val old = opened.view
        tap("og tags")
        val other = AgentKey("workstation", "t2")
        rig.await("the other tile attaches without a step dwell") { rig.ports.opens.size == 2 }
        assertEquals(other, rig.home.openKey)
        assertEquals(other, rig.ports.opens.last().key)
        assertFalse(rig.home.arrivedByStep)
        assertEquals(1, old.calls.count { it == "detach" })
    }

    @Test
    fun rotationRebuildsTheKeeperAsToday() {
        // First capture terminal-local state being lost on actual activity recreation.
        rig.app.terminalZoom[rig.key] = 1.25f
        show()
        tap("Compose")
        edit("lost on rotation")
        changes()
        val old = opened.view
        recreate()
        rig.await("reattach after recreation") { rig.ports.opens.size == 2 }
        rig.frames()
        assertEquals(1, old.calls.count { it == "detach" })
        assertBase(chat = false)
        assertNull(nodeSaying("Paste"))
        tap("Compose")
        assertEquals("", fieldText())
        back()
        assertEquals(1.25f, rig.app.terminalZoom[rig.key])

        // Then chat state held by the real application across another recreation.
        tap("Chat")
        edit("draft survives")
        val photo = Attachment("photo.png", byteArrayOf(1), ImageBitmap(1, 1))
        state.attached = listOf(photo)
        val rows = (0..25).map { ChatRow("row-$it", "said", "Answer $it", "", emptyList(), emptyList()) }
        rig.ports.chat(rig.key).current = rig.ports.chat(rig.key).current.copy(rows = rows)
        state.poll = rig.ports.chat(rig.key).current
        rig.frames()
        rig.await("the chat shows its rows") { drawn(); semanticsNodes().any { n -> n.texts().any { it.startsWith("Answer") } } }
        // Move the actual chat list through its accessibility scroll action.
        val list = semanticsNodes().first { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }
        assertTrue(list.config[SemanticsActions.ScrollToIndex].action!!.invoke(4))
        rig.frames(12)
        drawn()
        rig.frames(2)
        // Programmatic positioning alone does not turn off follow-bottom;
        // the production list recognizes a person's drag for that.
        // A node read before the frames above keeps the bounds it had then (zero): read it afresh.
        val bounds = semanticsNodes().first { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }.boundsInWindow
        assertTrue("the list is laid out under the finger", bounds.width > 0f && bounds.height > 0f)
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, bounds.center.x, bounds.center.y, down)
        repeat(8) { i ->
            send(MotionEvent.ACTION_MOVE, bounds.center.x, bounds.center.y + (i + 1) * 15f, down)
        }
        send(MotionEvent.ACTION_UP, bounds.center.x, bounds.center.y + 120f, down)
        rig.frames(30)
        assertFalse("reading earlier messages disables follow-bottom", state.stick)
        // Cancel any residual fling and choose a stable, non-bottom place.
        assertTrue(list.config[SemanticsActions.ScrollToIndex].action!!.invoke(4))
        rig.frames()
        val place = semanticsNodes().first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        changes()
        val last = rig.ports.opens.last().view
        recreate()
        rig.await("chat-first reattach") { rig.ports.opens.size == 3 }
        rig.frames()
        assertBase(chat = true)
        assertEquals(1, last.calls.count { it == "detach" })
        assertFalse(rig.ports.opens.last().lead)
        assertEquals("draft survives", fieldText())
        assertSame(photo, state.attached.single())
        assertNotNull("chat keeps its reading place", state.startAt(rows))
        val restored = semanticsNodes().first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals("same item and offset after recreation", place, restored, .01f)
        assertEquals(1.25f, rig.app.terminalZoom[rig.key])
    }

    @Test
    fun backgroundIdleUnderAChangesCoverReopens() {
        show()
        changes()
        val old = opened.view
        controller!!.pause().stop()
        rig.frames()
        // Deliver the same idle signal ForegroundTracker emits after its timeout.
        val field = rig.app.javaClass.getDeclaredField("idle").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val idleSignal = field.get(rig.app) as MutableStateFlow<Int>
        idleSignal.value++
        rig.await("background idle detaches") { old.calls.count { it == "detach" } == 1 }
        controller!!.start().resume().visible()
        rig.await("foreground reopens under Changes") { rig.ports.opens.size == 2 }
        assertNotNull(nodeSaying("Changes"))
        assertTrue("today's behavior: reopening under the terminal's cover leads immediately", rig.ports.opens.last().lead)
        back()
        assertBase(chat = false)
        assertEquals(2, rig.ports.opens.size)
        assertEquals(1, old.calls.count { it == "detach" })
    }

    @Test
    fun theAgentLeavingTheHubReleasesOnceUnderAChangesCover() {
        show()
        changes()
        val view = opened.view
        opened.paint("screen before removal")
        rig.frames()
        rig.publish(rig.snapshot.copy(agents = rig.snapshot.agents.filterNot { it.machine == rig.key.machine && it.address == rig.key.address }))
        rig.await("snapshot removes and releases the open agent") {
            rig.home.openKey == null && view.calls.count { it == "detach" } == 1
        }
        assertEquals(1, view.calls.count { it == "detach" })
        assertEquals(1, view.calls.count { it == "destroy" })
        rig.app.terminalCacheExecutor.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS)
        val cached = rig.cache.load(rig.key)
        assertNotNull("last screen was cached", cached)
        val grid = TerminalGrid().apply { apply(cached!!) }
        assertTrue("the cache contains the last painted screen", grid.text().contains("screen before removal"))
        rig.advance(800)
        assertEquals(1, rig.ports.opens.size)
        assertEquals(1, view.calls.count { it == "detach" })
    }

    @Test
    fun anAnnouncedAnswerIsHeardOnceWhileChangesIsOver() {
        val manager = rig.app.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val accessibility = shadowOf(manager)
        accessibility.setEnabled(true)
        accessibility.setTouchExplorationEnabled(true)
        show(chat = true)
        changes()
        val text = "The new answer is ready"
        rig.ports.chat(rig.key).answer(ChatRow("live-answer", "said", text, "", emptyList(), emptyList()))
        fun heard() = accessibility.sentAccessibilityEvents.count {
            it.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT && text in it.text.map { part -> part.toString() }
        }
        rig.await("the new answer is announced while Changes covers the chat") { heard() == 1 }
        assertNotNull(nodeSaying("Changes"))
        back()
        rig.advance(800)
        assertBase(chat = true)
        assertEquals("returning does not repeat the answer", 1, heard())
    }
}


/** A distinct activity type lets the per-test lifecycle callback install the real routes without static state. */
class AgentRouteTestActivity : ComponentActivity()
