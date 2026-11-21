package dev.kudde.android.ui.chat

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.testing.descriptions
import dev.kudde.android.testing.semanticsNodes
import dev.kudde.android.testing.texts
import dev.kudde.android.ui.terminal.SessionFixture
import dev.kudde.android.ui.terminal.TerminalActions
import dev.kudde.android.ui.terminal.TerminalInputHandle
import dev.kudde.android.ui.terminal.TerminalScreen
import dev.kudde.android.ui.terminal.TerminalScreenState
import dev.kudde.android.ui.terminal.TerminalSession
import dev.kudde.android.ui.terminal.TerminalUi
import dev.kudde.android.ui.terminal.TitleBarFixtures
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.kudde_mobile.ChatFound
import uniffi.kudde_mobile.ChatHealth
import uniffi.kudde_mobile.ChatPoll

// spaces spec, "The title bar keeps a place for the context gauge" (design
// D10): the chat's bar holds the gauge with the map, the switch and the menu
// button, in the render's order (terminal-map: gauge, map, switch, menu); a
// chat without a token count has no gauge and no empty place where it would
// be; the terminal's bar has none. The gauge itself is chat-view's, unchanged.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ChatGaugeTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()
    private val actions = TerminalActions(onBack = {}, onOpen = {}, onSwipe = { false }, onPhoneGrid = {})
    private val images = object : ChatImageSource {
        override fun cached(key: String): ImageBitmap? = null
        override suspend fun load(key: String): ImageBitmap? = null
    }
    private val map = "Tab overview, terminal 1 of 3"

    @After
    fun close() {
        controller.pause().stop().destroy()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun poll(used: ULong?) = ChatPoll(
        found = ChatFound.FOUND, answered = true, agent = "claude", title = "", version = "", status = "idle",
        health = ChatHealth.Ok, problem = "", exited = false, canSend = true, cut = false, rows = emptyList(),
        gaugeUsed = used, gaugeWindow = null, gaugePercent = null, queued = emptyList(), timings = "",
    )

    private fun showChat(used: ULong?) {
        activity.setContent {
            KuddeTheme(dark = true) {
                ChatScreen(TitleBarFixtures.pricingHeader, actions, poll(used), ChatState(), images, { 0L },
                    onTerminal = {}, onPick = {}, onSend = {}, animate = false, onChanges = {},
                    map = TitleBarFixtures.pricingMap, onMap = {})
            }
        }
        idle()
    }

    private fun showTerminal() {
        val ui = TerminalUi(TitleBarFixtures.pricing, TitleBarFixtures.pricingHeader, null, TitleBarFixtures.pricingMap)
        val session = TerminalSession(TitleBarFixtures.pricing) { it.run() }
        session.listener.onFrame(SessionFixture.frame(96, 44, wide = true, bottom = false))
        activity.setContent {
            KuddeTheme(dark = true) {
                TerminalScreen(ui, session, TerminalScreenState(), actions, TerminalInputHandle.None, composeFocus = false,
                    onChat = {}, onChanges = {}, onMap = {})
            }
        }
        idle()
    }

    private fun bounds(spoken: String): Rect =
        semanticsNodes().single { spoken in it.descriptions() || it.texts() == listOf(spoken) }.boundsInWindow

    @Test
    fun theChatsBarHoldsTheGaugeWithTheMapTheSwitchAndTheMenu() {
        showChat(used = 283_000u)
        val gauge = bounds("283k")
        val onMap = bounds(map)
        val switch = bounds("Terminal")
        val menu = bounds("More options")
        assertTrue("gauge before the map", gauge.right <= onMap.left)
        assertTrue("map before the switch", onMap.right <= switch.left)
        assertTrue("the menu button last", switch.right <= menu.left)
    }

    @Test
    fun withoutATokenCountThereIsNoGaugeAndNoEmptyPlace() {
        showChat(used = null)
        assertTrue(semanticsNodes().none { it.texts() == listOf("283k") })
        val chat = listOf(bounds(map), bounds("Terminal"), bounds("More options"))
        // The terminal's bar holds the same controls without a gauge: the chat's close up to the same places.
        showTerminal()
        val terminal = listOf(bounds(map), bounds("Chat"), bounds("More options"))
        assertEquals(terminal.map { it.left }, chat.map { it.left })
    }

    @Test
    fun theTerminalsBarHasNoGauge() {
        showTerminal()
        assertTrue(semanticsNodes().none { it.texts() == listOf("283k") })
        assertTrue(semanticsNodes().none { node -> node.texts().any { it.endsWith("%") || it.endsWith("k") } })
    }
}
