package dev.kudde.android.ui.terminal

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.testing.click
import dev.kudde.android.testing.clickable
import dev.kudde.android.testing.descriptions
import dev.kudde.android.testing.role
import dev.kudde.android.testing.semanticsNodes
import dev.kudde.android.testing.texts
import dev.kudde.android.ui.chat.ChatImageSource
import dev.kudde.android.ui.chat.ChatScreen
import dev.kudde.android.ui.chat.ChatState
import dev.kudde.android.ui.chat.SHOW_TERMINAL
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

// spaces spec, "The view switch is an icon" (design D10): no word on it, read
// as "Chat" in the terminal and "Terminal" in the chat, a button of at least
// 48 dp; absent where the chat is not offered; a tap switches as before; the
// chat's "Show the terminal" buttons keep their words.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ViewSwitchTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()
    private val actions = TerminalActions(onBack = {}, onOpen = {}, onSwipe = { false }, onPhoneGrid = {})
    private var switched = 0

    @After
    fun close() {
        controller.pause().stop().destroy()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun showTerminal(chatOffered: Boolean) {
        val ui = terminalUi(TitleBarFixtures.hub, TitleBarFixtures.pricing, activity.resources)
        val session = TerminalSession(TitleBarFixtures.pricing) { it.run() }
        activity.setContent {
            KuddeTheme(dark = true) {
                TerminalScreen(ui, session, TerminalScreenState(), actions, TerminalInputHandle.None, composeFocus = false,
                    onChat = if (chatOffered) ({ switched++ }) else null, onChanges = {}, onMap = {})
            }
        }
        idle()
    }

    private fun showChat(found: ChatFound = ChatFound.FOUND) {
        val poll = ChatPoll(
            found = found, answered = true, agent = "claude", title = "", version = "", status = "idle",
            health = ChatHealth.Ok, problem = "", exited = false, canSend = true, cut = false, rows = emptyList(),
            gaugeUsed = null, gaugeWindow = null, gaugePercent = null, queued = emptyList(), timings = "",
        )
        val images = object : ChatImageSource {
            override fun cached(key: String): ImageBitmap? = null
            override suspend fun load(key: String): ImageBitmap? = null
        }
        activity.setContent {
            KuddeTheme(dark = true) {
                ChatScreen(TitleBarFixtures.pricingHeader, actions, poll, ChatState(), images, { 0L },
                    onTerminal = { switched++ }, onPick = {}, onSend = {}, animate = false, onChanges = {},
                    map = TitleBarFixtures.pricingMap, onMap = {})
            }
        }
        idle()
    }

    private fun spokenAs(name: String) = semanticsNodes().filter { name in it.descriptions() }

    private fun assertIconButton(name: String) {
        val node = spokenAs(name).single()
        assertEquals(listOf(name), node.descriptions())
        assertEquals("an icon and no word", emptyList<String>(), node.texts())
        assertEquals(Role.Button, node.role())
        val min = 48 * activity.resources.displayMetrics.density
        assertTrue("${node.size}", node.size.width >= min && node.size.height >= min)
        assertTrue("no word $name shows in the title bar", semanticsNodes().none { it.texts() == listOf(name) })
    }

    @Test
    fun fromTheTerminalTheSwitchIsReadAsChat() {
        showTerminal(chatOffered = true)
        assertIconButton("Chat")
    }

    @Test
    fun fromTheChatTheSwitchIsReadAsTerminal() {
        showChat()
        assertIconButton("Terminal")
    }

    @Test
    fun noSwitchWhereTheChatIsNotOffered() {
        showTerminal(chatOffered = false)
        assertTrue(spokenAs("Chat").isEmpty())
    }

    @Test
    fun aTapSwitchesAsBefore() {
        showTerminal(chatOffered = true)
        assertTrue(spokenAs("Chat").single().click())
        assertEquals(1, switched)
        showChat()
        assertTrue(spokenAs("Terminal").single().click())
        assertEquals(2, switched)
    }

    @Test
    fun theChatsShowTheTerminalButtonsKeepTheirWords() {
        showChat(found = ChatFound.MISSED)
        val words = semanticsNodes().single { it.texts() == listOf(SHOW_TERMINAL) }
        assertTrue(words.clickable())
        assertTrue(words.click())
        assertEquals(1, switched)
    }
}
