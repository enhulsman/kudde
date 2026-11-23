package dev.kudde.android.ui.agent

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import dev.kudde.android.testing.*
import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.agents.AgentsRoute
import dev.kudde.android.ui.flows.MachineFlows
import dev.kudde.android.ui.terminal.TerminalInputView
import dev.kudde.android.ui.terminal.TerminalMetrics
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import uniffi.kudde_mobile.ChatRow

/** Shared wiring to the real routes, never a copy of their screen/Back/size decisions. */
class AgentRouteScenario : AutoCloseable {
    val rig = AgentRouteRig().also { it.install() }
    var heightDp by mutableIntStateOf(915)
    var measuredHost = IntSize.Zero
        private set
    var routePorts: AgentPorts = rig.ports
    var controller: ActivityController<ComponentActivity>? = null
        private set
    val activity get() = controller!!.get()
    val state get() = rig.app.chatStates.open(rig.key)
    val opened get() = rig.ports.opens.last()

    fun show(chat: Boolean = false, settle: Boolean = true) {
        rig.app.chatView.choose(chat)
        rig.home.open(rig.key)
        ShadowChoreographer.setPaused(false)
        try {
            controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        } finally { PopupSafeClock.paused() }
        activity.setContent {
            val snapshot by rig.app.hubHost.snapshot.collectAsState()
            LaunchedEffect(snapshot) { rig.home.onSnapshot(snapshot) }
            val flows = remember { MachineFlows(rig.app.hubHost) }
            KuddeTheme(dark = true) {
                Box(Modifier.fillMaxWidth().height(heightDp.dp).onSizeChanged { measuredHost = it }) {
                    AgentsRoute(rig.app, flows, rig.home, ports = routePorts)
                }
            }
        }
        rig.await("the real route attaches") { rig.ports.opens.size == 1 }
        if (settle) rig.advance(TerminalMetrics.SETTLE_MS * 2)
        draw()
    }

    fun tap(label: String) {
        val node = nodeSaying(label)
        assertNotNull("fixture node: $label", node)
        assertTrue("fixture click: $label", node!!.click())
        rig.frames()
        draw()
    }

    fun changes() {
        tap("More options")
        tap("Changes")
        assertNotNull("Changes is the real cover", nodeSaying("Changes"))
    }

    fun overview() {
        val map = semanticsNodes().single { it.descriptions().any { d -> d.startsWith("Tab overview,") } }
        assertTrue(map.click())
        rig.frames()
        draw()
        assertNotNull("real overview tile", nodeSaying("Pricing page copy"))
    }

    fun back(frames: Boolean = true) {
        activity.onBackPressedDispatcher.onBackPressed()
        if (frames) { rig.frames(); draw() }
    }

    fun edit(text: String) {
        val field = semanticsNodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
        assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text)))
        rig.frames()
    }

    fun fieldText() = semanticsNodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
        .config[SemanticsProperties.EditableText].text

    fun inputViews(view: View = activity.window.decorView): List<TerminalInputView> = when (view) {
        is TerminalInputView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { inputViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    fun resize(height: Int) {
        heightDp = height
        rig.frames(2)
        draw()
        rig.frames(2)
    }

    fun keyboardUp() = shadowOf(activity.getSystemService(InputMethodManager::class.java)).isSoftInputVisible

    fun focusChat() {
        val field = semanticsNodes().single { it.config.getOrNull(SemanticsActions.SetText) != null }
        val b = field.boundsInWindow
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, b.center.x, b.center.y, down)
        touch(MotionEvent.ACTION_UP, b.center.x, b.center.y, down)
        // Compose (SDK 35) raises the keyboard through the window's insets controller, which
        // Robolectric's InputMethodManager shadow does not record; the focused field is the
        // observable here, and the keyboard itself is checked on the phone (task 7.x).
        rig.await("chat field focused before opening a cover") { unmergedSemanticsNodes().any { it.focused() } }
    }

    fun draw() {
        val v = activity.window.decorView
        val bitmap = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try { v.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
    }

    fun touch(action: Int, x: Float, y: Float, down: Long) {
        val pointers = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, pointers, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { activity.window.decorView.dispatchTouchEvent(e) } finally { e.recycle() }
        rig.frames(2)
    }

    fun swipe(bounds: Rect, vertical: Boolean = false) {
        val down = SystemClock.uptimeMillis()
        val x = if (vertical) bounds.center.x else bounds.right - 12f
        val y = if (vertical) bounds.bottom - 12f else bounds.center.y
        val distance = if (vertical) 240f else 300f
        touch(MotionEvent.ACTION_DOWN, x, y, down)
        repeat(12) { i ->
            val delta = distance * (i + 1) / 12
            touch(MotionEvent.ACTION_MOVE, x - if (vertical) 0f else delta, y - if (vertical) delta else 0f, down)
        }
        touch(MotionEvent.ACTION_UP, x - if (vertical) 0f else distance, y - if (vertical) distance else 0f, down)
    }

    fun titleBounds(): Rect {
        val b = nodeSaying("More options")!!.boundsInWindow
        return Rect(0f, b.top, activity.window.decorView.width.toFloat(), b.bottom)
    }

    override fun close() {
        try {
            controller?.let {
                val t = SystemClock.uptimeMillis()
                val e = MotionEvent.obtain(t, t, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
                try { it.get().window.decorView.dispatchTouchEvent(e) } finally { e.recycle() }
                it.pause().stop().destroy()
            }
        } finally { rig.close() }
    }
}

@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class AgentRouteStepTest {
    private val next = AgentKey("workstation", "t2")

    /** Capture the actual opener's arrival time, then advance to an exact elapsed millisecond. */
    private fun AgentRouteScenario.step(): Long {
        var arrival: Long? = null
        rig.ports.clock = { SystemClock.uptimeMillis().also { if (arrival == null) arrival = it } }
        assertTrue(rig.home.tab(true))
        rig.frames()
        assertEquals(next, rig.home.openKey)
        assertTrue(rig.home.arrivedByStep)
        assertNotNull("the destination created its real opener", arrival)
        return arrival!!
    }

    /**
     * The route's dwell is a coroutine delay(), which Compose runs on a real-time timer, not on
     * Robolectric's clock; the opener itself compares the injected clock (so 399 ms stays exact).
     * Once the fake clock is at 400 ms, give the real timer its wall-clock time.
     */
    private fun AgentRouteScenario.attachedAfterTheDwell() =
        rig.await("the next pane attaches once 400 ms have passed", realWaitMs = 1_500) { rig.ports.opens.size == 2 }

    private fun AgentRouteScenario.at(arrival: Long, elapsed: Long) {
        val remaining = arrival + elapsed - SystemClock.uptimeMillis()
        assertTrue("fixture has not passed ${elapsed}ms", remaining >= 0)
        rig.advanceExactly(remaining)
    }

    @Test fun theOldSessionDetachesBeforeTheNextAttaches() = AgentRouteScenario().use {
        it.show()
        val old = it.opened.view
        val arrival = it.step()
        assertEquals("old session detached while next is still waiting", 1, old.calls.count { c -> c == "detach" })
        assertEquals("next has not attached before old detach", 1, it.rig.ports.opens.size)
        it.at(arrival, 400)
        it.attachedAfterTheDwell()
        assertEquals(listOf(it.rig.key, next), it.rig.ports.opens.map { o -> o.key })
        assertEquals(1, old.calls.count { c -> c == "detach" })
    }

    @Test fun leavingAt399msAttachesAndDetachesNothing() = AgentRouteScenario().use {
        it.show()
        val arrival = it.step()
        it.at(arrival, 399)
        assertEquals("destination never attached at 399ms", 1, it.rig.ports.opens.size)
        // Keep the injected route clock at 399 while the disposal frame runs: advancing
        // it to 415 just to apply the close would test leaving after the dwell instead.
        it.rig.ports.clock = { arrival + 399 }
        it.rig.home.close()
        it.rig.frames()
        it.rig.ports.clock = SystemClock::uptimeMillis
        it.rig.advance(800)
        assertEquals("unopened destination cannot attach or detach after leaving", listOf(it.rig.key), it.rig.ports.opens.map { o -> o.key })
        assertEquals(1, it.rig.ports.opens.single().view.calls.count { c -> c == "detach" })
    }

    @Test fun at400msTheNextPaneIsAttached() = AgentRouteScenario().use {
        it.show()
        val arrival = it.step()
        it.at(arrival, 399)
        assertEquals(1, it.rig.ports.opens.size)
        it.at(arrival, 400)
        it.attachedAfterTheDwell()
        assertEquals("the next pane attaches at 400ms", next, it.rig.ports.opens.last().key)
        assertEquals(2, it.rig.ports.opens.size)
    }

    @Test fun inputDuringTheDwellOpensFirstThenSends() = AgentRouteScenario().use {
        it.show()
        it.step()
        assertEquals(1, it.rig.ports.opens.size)
        it.inputViews().single().onCreateInputConnection(EditorInfo()).commitText("hello", 1)
        it.rig.frames()
        assertEquals("input opens the destination before the dwell expires", 2, it.rig.ports.opens.size)
        assertEquals(next, it.opened.key)
        assertEquals("input reaches the newly attached view", listOf("type hello"), it.opened.view.calls.filter { c -> c.startsWith("type ") })
        assertTrue("input never reaches the departed pane", it.rig.ports.opens.first().view.calls.none { c -> c.startsWith("type ") })
        it.rig.advance(800)
        assertEquals("dwell does not open twice", 2, it.rig.ports.opens.size)
    }

    @Test fun aSwipeOnAWideChatTableScrollsItWithoutStepping() = AgentRouteScenario().use {
        val header = listOf("Repository", "Work remaining", "Verification notes", "Expected outcome")
        val divider = List(header.size) { "---" }
        val row = listOf("website", "Review the navigation behaviour", "Run the phone checks", "Keep the current pane open")
        val table = listOf(header, divider, row).joinToString("\n") { it.joinToString(" | ", "| ", " |") }
        it.rig.ports.chat(it.rig.key).answer(ChatRow("wide-table", "said", table, "", emptyList(), emptyList()))
        it.show(chat = true)
        it.rig.await("the wide table can scroll sideways") {
            unmergedSemanticsNodes().any { node ->
                node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.maxValue()?.let { max -> max > 0f } == true
            }
        }
        val tableNode = unmergedSemanticsNodes().single { node ->
            node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.maxValue()?.let { max -> max > 0f } == true
        }
        val scrollRange = tableNode.config[SemanticsProperties.HorizontalScrollAxisRange]
        val before = scrollRange.value()
        it.swipe(tableNode.boundsInWindow)
        assertTrue("the swipe scrolls the table", scrollRange.value() > before)
        assertEquals("the swipe keeps the current pane open", it.rig.key, it.rig.home.openKey)
        assertEquals("the swipe does not attach another terminal", 1, it.rig.ports.opens.size)
    }

    @Test fun disposalCancelsAnUnopenedDestination() = AgentRouteScenario().use {
        it.show()
        // Normal pending dwell, followed by pending dwell under an actual Changes cover.
        repeat(2) { covered ->
            it.step()
            if (covered == 1) it.changes()
            assertEquals("still inside the pending dwell", covered + 1, it.rig.ports.opens.size)
            it.rig.home.close()
            it.rig.frames()
            it.rig.advance(800)
            assertEquals("disposal cancels an unopened destination, including under Changes", covered + 1, it.rig.ports.opens.size)
            if (covered == 0) {
                it.rig.home.open(it.rig.key)
                it.rig.await("reopen the starting pane") { it.rig.ports.opens.size == 2 }
                it.rig.advance(TerminalMetrics.SETTLE_MS * 2)
            }
        }
    }
}
