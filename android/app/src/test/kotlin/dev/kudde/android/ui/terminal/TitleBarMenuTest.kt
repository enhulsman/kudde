package dev.kudde.android.ui.terminal

import androidx.activity.BackEventCompat
import dev.kudde.android.ui.agent.AgentRouteScenario
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import dev.kudde.android.testing.BoundedFrames
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.testing.PopupSafeClock
import dev.kudde.android.testing.click
import dev.kudde.android.testing.clickable
import dev.kudde.android.testing.descriptions
import dev.kudde.android.testing.nodeSaying
import dev.kudde.android.testing.role
import dev.kudde.android.testing.semanticsNodes
import dev.kudde.android.testing.texts
import dev.kudde.android.testing.windowRoots
import dev.kudde.android.ui.chat.ChatImageSource
import dev.kudde.android.ui.chat.ChatScreen
import dev.kudde.android.ui.chat.ChatState
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.kudde_mobile.ChatFound
import uniffi.kudde_mobile.ChatHealth
import uniffi.kudde_mobile.ChatPoll
import uniffi.kudde_mobile.TermPhase
import uniffi.kudde_mobile.TermState

// spaces spec, "The title bar keeps its options in one menu" (design D10): the
// overflow button is read as "More options", a button of at least 48 dp; a tap
// shows "Changes", read as "Changes", which opens the Changes view once; a tap
// outside and Back close the menu and send nothing to the terminal; a drag that
// starts on the button still steps. The menu draws in a Popup's own window, so
// its nodes are read from every window (testing/Semantics.kt). The class runs under
// PopupSafeClock (a paused Choreographer, frames stepped by BoundedFrames): in
// this Robolectric a Popup under the free-running one never lets a frame end.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class TitleBarMenuTest {
    // A Popup under a free-running Choreographer never lets a frame end (testing/PopupSafeClock.kt).
    @Suppress("unused")
    private val clock = PopupSafeClock.paused()
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()
    private val view = FakeTerminalView()
    private val session = TerminalSession(TitleBarFixtures.pricing) { it.run() }
    private val screen = TerminalScreenState()
    private val menu = TitleBarMenuState()
    private val tabs = mutableListOf<Boolean>()
    private var changes = 0
    private var backs = 0
    private val actions = terminalActions(
        screen,
        onBack = { backs++ },
        onOpen = {},
        onSwipe = { false },
        onTab = { tabs += it; true },
        onPhoneGrid = {},
    )

    @After
    fun close() {
        controller.pause().stop().destroy()
        idle()
    }

    private fun idle() = BoundedFrames.settle(minFrames = 3)

    /** Two bounded frames, so an opened menu's window is composed. */
    private fun frame() = BoundedFrames.step(2)

    private fun showTerminal() {
        session.attach(view)
        session.listener.onState(TermState(TermPhase.Watching, 96u, 44u))
        session.listener.onFrame(SessionFixture.frame(96, 44, wide = true, bottom = false))
        val ui = terminalUi(TitleBarFixtures.hub, TitleBarFixtures.pricing, activity.resources)
        activity.setContent {
            KuddeTheme(dark = true) {
                TerminalScreen(ui, session, screen, actions, TerminalInputHandle.None, composeFocus = false, onChat = {}, onChanges = { changes++ }, onMap = {}, menuState = menu)
            }
        }
        idle()
    }

    private fun showChat() {
        val poll = ChatPoll(
            found = ChatFound.FOUND, answered = true, agent = "claude", title = "", version = "", status = "idle",
            health = ChatHealth.Ok, problem = "", exited = false, canSend = true, cut = false, rows = emptyList(),
            gaugeUsed = 283_000u, gaugeWindow = null, gaugePercent = null, queued = emptyList(), timings = "",
        )
        val images = object : ChatImageSource {
            override fun cached(key: String): ImageBitmap? = null
            override suspend fun load(key: String): ImageBitmap? = null
        }
        activity.setContent {
            KuddeTheme(dark = true) {
                ChatScreen(TitleBarFixtures.pricingHeader, actions, poll, ChatState(), images, { 0L },
                    onTerminal = {}, onPick = {}, onSend = {}, animate = false, onChanges = { changes++ },
                    map = TitleBarFixtures.pricingMap, onMap = {}, menuState = menu)
            }
        }
        idle()
    }

    private fun button() = nodeSaying("More options")!!
    private fun item() = semanticsNodes().singleOrNull { it.texts() == listOf("Changes") }

    private fun openMenu() {
        assertTrue(button().click())
        frame()
        assertTrue(menu.open)
        assertNotNull("the menu shows", item())
    }

    @Test
    fun theButtonIsReadAsMoreOptionsAButtonOfAtLeast48Dp() {
        showTerminal()
        val button = button()
        assertEquals(listOf("More options"), button.descriptions())
        assertEquals(Role.Button, button.role())
        val min = 48 * activity.resources.displayMetrics.density
        assertTrue("${button.size}", button.size.width >= min && button.size.height >= min)
    }

    @Test
    fun noChangesButtonOutsideTheMenu() {
        showTerminal()
        assertNull(item())
        assertTrue(semanticsNodes().none { "Show the files this agent changed" in it.descriptions() || "Changes" in it.descriptions() })
    }

    @Test
    fun aTapShowsChangesReadAsChanges() {
        showTerminal()
        openMenu()
        val changesItem = item()!!
        assertTrue(changesItem.clickable())
        assertEquals("read as its word, nothing longer", emptyList<String>(), changesItem.descriptions())
    }

    @Test
    fun choosingChangesOpensItOnceAndClosesTheMenu() {
        showTerminal()
        openMenu()
        assertTrue(item()!!.click())
        idle()
        assertEquals(1, changes)
        assertFalse("the menu closed", menu.open)
    }

    @Test
    fun theChatsBarHasTheSameMenu() {
        showChat()
        openMenu()
        assertTrue(item()!!.click())
        idle()
        assertEquals(1, changes)
    }

    @Test
    fun aTapOutsideClosesTheMenuAndSendsNothing() {
        showTerminal()
        val before = view.calls.toList()
        openMenu()
        val popup = windowRoots().last()
        val t = SystemClock.uptimeMillis()
        val outside = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, -20f, -20f, 0)
        popup.dispatchTouchEvent(outside)
        outside.recycle()
        idle()
        assertFalse("the menu closed", menu.open)
        assertEquals("nothing reached the terminal", before, view.calls)
        assertEquals(0, changes)
        assertEquals(0, backs)
    }

    @Test
    fun dismissingByAnOutsideTapStillAnimatesOut() {
        showTerminal()
        openMenu()
        val popup = windowRoots().last()
        val t = SystemClock.uptimeMillis()
        val outside = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, -20f, -20f, 0)
        popup.dispatchTouchEvent(outside)
        outside.recycle()
        frame()
        assertFalse("the menu is closing", menu.open)
        assertNotNull("the menu is still drawn, animating out", item())
        // The exit spring runs on the wall clock in this Robolectric.
        repeat(100) { if (item() != null) { Thread.sleep(20); BoundedFrames.step(2) } }
        assertNull("and then gone", item())
    }

    @Test
    fun backClosesTheMenuAndSendsNothing() {
        showTerminal()
        val before = view.calls.toList()
        openMenu()
        val popup = windowRoots().last()
        popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        idle()
        assertFalse("the menu closed", menu.open)
        assertEquals("the terminal stays open", 0, backs)
        assertEquals("nothing reached the terminal", before, view.calls)
        assertEquals(0, changes)
    }

    @Test
    fun aDragThatStartsOnTheButtonStillSteps() {
        showTerminal()
        val bounds = button().boundsInWindow
        val x0 = bounds.center.x
        val y = bounds.center.y
        val down = SystemClock.uptimeMillis()
        fun send(event: MotionEvent) {
            activity.window.decorView.dispatchTouchEvent(event)
            event.recycle()
            idle()
        }
        send(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x0, y, 0))
        for (i in 1..12) send(MotionEvent.obtain(down, down + i * 10L, MotionEvent.ACTION_MOVE, x0 - 25f * i, y, 0))
        send(MotionEvent.obtain(down, down + 130L, MotionEvent.ACTION_UP, x0 - 300f, y, 0))
        assertEquals("finger left: the next stop", listOf(true), tabs)
        assertFalse("and the menu did not open", menu.open)
    }

    // red until 4.1
    @Test
    fun aDragOutsideTheMenuStepsNothingAndCloses() = AgentRouteScenario().use { host ->
        host.show()
        val title = host.titleBounds()
        val key = host.rig.home.openKey
        val before = host.opened.view.calls.toList()
        val anchor = nodeSaying("More options")!!.boundsInWindow
        val layerOpen = androidx.compose.runtime.mutableStateOf(true)
        val layer = androidx.compose.ui.platform.ComposeView(host.activity).apply {
            setContent {
                KuddeTheme(dark = true) {
                    if (layerOpen.value) dev.kudde.android.ui.components.ModalPopupLayer(
                        anchorBounds = androidx.compose.ui.unit.IntRect(
                            anchor.left.toInt(), anchor.top.toInt(), anchor.right.toInt(), anchor.bottom.toInt()),
                        popupPositionProvider = MenuPosition(androidx.compose.ui.platform.LocalDensity.current),
                        shadowRoom = androidx.compose.ui.unit.Dp(24f),
                        onDismissRequest = { layerOpen.value = false },
                    ) {
                        TitleBarMenuPanel(listOf(dev.kudde.android.ui.changes.changesMenuItem {})) {}
                    }
                }
            }
        }
        host.activity.addContentView(layer, android.view.ViewGroup.LayoutParams(-1, -1))
        host.rig.frames(4)
        host.draw()
        val popup = host.activity.window.decorView
        val location = IntArray(2).also(popup::getLocationOnScreen)
        val down = SystemClock.uptimeMillis()
        // Send the whole gesture through the activity containing the layer and real route.
        fun send(action: Int, x: Float) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                x - location[0], title.center.y - location[1], 0)
            try { popup.dispatchTouchEvent(e) } finally { e.recycle() }
            host.rig.frames(2)
        }
        send(MotionEvent.ACTION_DOWN, title.width * .65f)
        repeat(12) { send(MotionEvent.ACTION_MOVE, title.width * .65f - (it + 1) * 25f) }
        send(MotionEvent.ACTION_UP, title.width * .65f - 300f)
        assertNull("outside drag must close the menu", nodeSaying("Changes"))
        assertEquals("outside menu drag reached onTab", key, host.rig.home.openKey)
        assertEquals("outside menu drag sent terminal input", before, host.opened.view.calls)
        assertEquals("outside menu drag attached another pane", 1, host.rig.ports.opens.size)
        // The activity-sized layer must cover the title area; the wrapper test checks its window.
        assertTrue("menu window does not cover the title bar against outside drag pass-through",
            location[0] <= 0 && location[1] <= title.top &&
                location[0] + popup.width >= title.right && location[1] + popup.height >= title.bottom)
    }

    // red until 4.1
    @Test
    fun aTapInsideTheMenuPanelDoesNotClose() = AgentRouteScenario().use { host ->
        host.show()
        val anchor = nodeSaying("More options")!!.boundsInWindow
        val layerOpen = androidx.compose.runtime.mutableStateOf(true)
        val layer = androidx.compose.ui.platform.ComposeView(host.activity).apply {
            setContent {
                KuddeTheme(dark = true) {
                    if (layerOpen.value) dev.kudde.android.ui.components.ModalPopupLayer(
                        anchorBounds = androidx.compose.ui.unit.IntRect(
                            anchor.left.toInt(), anchor.top.toInt(), anchor.right.toInt(), anchor.bottom.toInt()),
                        popupPositionProvider = MenuPosition(androidx.compose.ui.platform.LocalDensity.current),
                        shadowRoom = androidx.compose.ui.unit.Dp(24f),
                        onDismissRequest = { layerOpen.value = false },
                    ) {
                        TitleBarMenuPanel(listOf(dev.kudde.android.ui.changes.changesMenuItem {})) {}
                    }
                }
            }
        }
        host.activity.addContentView(layer, android.view.ViewGroup.LayoutParams(-1, -1))
        host.rig.frames(4)
        host.draw()
        val item = nodeSaying("Changes")!!.boundsInWindow
        val popup = host.activity.window.decorView
        // The panel has 4 dp vertical padding. This point is inside that panel,
        // 2 dp above the first item, rather than in the outer 24 dp shadow room.
        val x = item.center.x
        val y = item.top - 2f * host.activity.resources.displayMetrics.density
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            try { popup.dispatchTouchEvent(event) } finally { event.recycle() }
            host.rig.frames(2)
        }
        assertNotNull("tap inside menu panel padding dismissed the menu", nodeSaying("Changes"))
        assertTrue("panel padding must not open Changes", host.rig.ports.changes.isEmpty())
        assertEquals(host.rig.key, host.rig.home.openKey)
    }

    // red until 4.1
    @Test
    fun backClosesTheMenuAndNotTheChat() = AgentRouteScenario().use { host ->
        host.show(chat = true)
        host.edit("keep chat open")
        val before = host.opened.view.calls.toList()
        // The legacy key path remains a positive control, then exercise the activity dispatcher.
        val anchor = nodeSaying("More options")!!.boundsInWindow
        val layerOpen = androidx.compose.runtime.mutableStateOf(true)
        val layer = androidx.compose.ui.platform.ComposeView(host.activity).apply {
            setContent {
                KuddeTheme(dark = true) {
                    if (layerOpen.value) dev.kudde.android.ui.components.ModalPopupLayer(
                        anchorBounds = androidx.compose.ui.unit.IntRect(
                            anchor.left.toInt(), anchor.top.toInt(), anchor.right.toInt(), anchor.bottom.toInt()),
                        popupPositionProvider = MenuPosition(androidx.compose.ui.platform.LocalDensity.current),
                        shadowRoom = androidx.compose.ui.unit.Dp(24f),
                        onDismissRequest = { layerOpen.value = false },
                    ) {
                        TitleBarMenuPanel(listOf(dev.kudde.android.ui.changes.changesMenuItem {})) {}
                    }
                }
            }
        }
        host.activity.addContentView(layer, android.view.ViewGroup.LayoutParams(-1, -1))
        host.rig.frames(4)
        host.draw()
        val popup = layer
        popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        popup.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        host.rig.frames()
        assertNull("key Back closes only the menu", nodeSaying("Changes"))
        assertEquals("key Back must keep the chat", host.rig.key, host.rig.home.openKey)
        layerOpen.value = true
        host.rig.frames(4)
        val dispatcher = host.activity.onBackPressedDispatcher
        dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
        dispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 100f, .5f, BackEventCompat.EDGE_LEFT))
        dispatcher.onBackPressed()
        host.rig.frames()
        assertEquals("menu Back reached the chat and left the agent", host.rig.key, host.rig.home.openKey)
        assertNull("dispatcher Back must close the menu", nodeSaying("Changes"))
        assertEquals("keep chat open", host.fieldText())
        assertEquals("menu Back must send nothing and detach nothing", before, host.opened.view.calls)
    }

}
