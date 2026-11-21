package dev.kudde.android.ui.terminal

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.kudde.android.hub.FakeHub
import dev.kudde.android.hub.HubHost
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.agents.AgentsHome
import dev.kudde.android.ui.agents.connected
import dev.kudde.android.ui.agents.row
import dev.kudde.android.ui.agents.snapshot
import dev.kudde.android.ui.chat.ChatImageSource
import dev.kudde.android.ui.chat.ChatScreen
import dev.kudde.android.ui.chat.ChatState
import dev.kudde.android.ui.chat.gaugeLabel
import dev.kudde.android.ui.theme.KuddeTheme
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uniffi.kudde_mobile.AgentAttention
import uniffi.kudde_mobile.ChatFound
import uniffi.kudde_mobile.ChatHealth
import uniffi.kudde_mobile.ChatPoll
import uniffi.kudde_mobile.DeskCellView
import uniffi.kudde_mobile.DeskPaneView
import uniffi.kudde_mobile.DeskSpaceView
import uniffi.kudde_mobile.DeskTabView
import uniffi.kudde_mobile.DeskView
import uniffi.kudde_mobile.HubListener

// spaces spec, "Every step has a way without a swipe" (design D9): with a
// screen reader the title bar is one node holding the name, the line under it
// and four actions that do what the four swipes do, each waiting as a step
// does (design D5); the app says once what came of it. Read from the merged
// semantics tree, which is what TalkBack is given (as ReadOnceTest).
@RunWith(KuddeTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class TitleBarActionsTest {
    private val res = RuntimeEnvironment.getApplication().resources
    private val fakeHub = FakeHub()
    private lateinit var listener: HubListener
    private val hubHost = HubHost(createHub = { listener = it; fakeHub }, mainExecutor = Executor { it.run() })
    private val home = AgentsHome(hubHost, now = { 0L }) { true }
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()
    private val screen = TerminalScreenState()
    private val heard = mutableListOf<String>()

    private fun key(address: String) = AgentKey("workstation", address)
    private fun cell(address: String) = DeskCellView(DeskPaneView.Terminal(address), 0f, 0f, 1f, 1f)

    // The space "website": tab "main" holds a and b, tab "two" holds c, who needs you.
    private val hub = snapshot(
        listOf(
            connected("workstation").copy(
                desk = DeskView(
                    listOf(DeskSpaceView("s", "website", 0u, listOf(DeskTabView("main", "main", listOf(cell("a"), cell("b"))), DeskTabView("two", "two", listOf(cell("c")))))),
                    emptyList(),
                    null,
                ),
            ),
        ),
        listOf(
            row("c", AgentAttention.NEEDS_YOU, name = "Pricing page copy"),
            row("a", AgentAttention.WORKING, name = "docs nav"),
            row("b", AgentAttention.IDLE, name = "api tests"),
        ),
    )

    // Built as TerminalRoute builds them, with the announcement heard here.
    private val actions = terminalActions(
        screen,
        onBack = {},
        onOpen = home::open,
        onSwipe = home::step,
        onTab = home::tab,
        onPhoneGrid = {},
        openHeader = { home.openKey?.let { terminalUi(hubHost.snapshot.value, it, res).header } },
        announce = { heard += it },
    )

    init {
        hubHost.start()
        listener.onSnapshot(hub)
        home.onSnapshot(hub)
    }

    @After
    fun closeScreen() {
        controller.pause().stop().destroy()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun header(address: String) = terminalUi(hub, key(address), res).header
    private fun spoken(address: String) = header(address).let { "${it.name}, ${it.lineTwo}" }

    private val poll = ChatPoll(
        found = ChatFound.FOUND, answered = true, agent = "claude", title = "Upload retry fix", version = "2.1.325", status = "idle",
        health = ChatHealth.Ok, problem = "", exited = false, canSend = true, cut = false, rows = emptyList(),
        gaugeUsed = 283_000u, gaugeWindow = null, gaugePercent = null, queued = emptyList(), timings = "",
    )

    private fun show(content: @Composable (AgentKey) -> Unit) {
        activity.setContent {
            KuddeTheme(dark = true) {
                Box(Modifier.fillMaxSize()) { home.openKey?.let { content(it) } }
            }
        }
        idle()
    }

    private fun showTerminal() = show { k ->
        val session = remember(k) { TerminalSession(k) { it.run() } }
        TerminalScreen(terminalUi(hubHost.snapshot.value, k, res), session, screen, actions, TerminalInputHandle.None,
            composeFocus = false, onChat = {}, onChanges = {})
    }

    private fun showChat() = show { k ->
        val images = remember {
            object : ChatImageSource {
                override fun cached(key: String): ImageBitmap? = null
                override suspend fun load(key: String): ImageBitmap? = null
            }
        }
        val ui = terminalUi(hubHost.snapshot.value, k, res)
        ChatScreen(ui.header, actions, poll, remember(k) { ChatState() }, images, { 0L },
            onTerminal = {}, onPick = {}, onSend = {}, animate = false, onChanges = {}, map = ui.map)
    }

    private fun nodes(): List<SemanticsNode> {
        val composeView = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup
        val root = composeView.getChildAt(0) as ViewRootForTest
        val all = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            all += node
            node.children.forEach(::walk)
        }
        walk(root.semanticsOwner.rootSemanticsNode)
        return all
    }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    private fun SemanticsNode.descriptions() = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    private fun SemanticsNode.actionNames() = config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    private fun titleNode(): SemanticsNode = nodes().single { it.actionNames().isNotEmpty() }

    private fun choose(action: String) {
        val chosen = titleNode().config[SemanticsActions.CustomActions].single { it.label == action }
        assertTrue(chosen.action())
        idle()
    }

    private val four = listOf("Next terminal", "Previous terminal", "Next agent that needs you", "Previous agent that needs you")

    private fun assertOneTitleNode(address: String, ownStops: List<List<String>>) {
        val title = titleNode()
        assertEquals(four, title.actionNames())
        assertEquals("the name and its line, nothing else", listOf(header(address).name, header(address).lineTwo), title.texts())
        assertEquals(emptyList<String>(), title.descriptions())
        for (stop in ownStops) {
            val node = nodes().singleOrNull { (it.texts() == stop || it.descriptions() == stop) && it.id != title.id }
            assertNotNull("$stop is not a focus stop of its own", node)
        }
    }

    @Test
    fun theTerminalsTitleBarIsOneNodeWithTheFourActions() {
        home.open(key("a"))
        showTerminal()
        // Back, the map, the switch and the menu button stay stops of their own (tasks 5.4 to 5.6).
        assertOneTitleNode("a", listOf(listOf("Back"), listOf("Tab overview, terminal 1 of 2"), listOf("Chat"), listOf("More options")))
    }

    @Test
    fun theChatsTitleBarIsOneNodeWithTheFourActions() {
        home.open(key("a"))
        showChat()
        val gauge = gaugeLabel(poll.gaugeUsed, poll.gaugeWindow, poll.gaugePercent)!!
        assertOneTitleNode("a", listOf(listOf("Back"), listOf(gauge), listOf("Tab overview, terminal 1 of 2"), listOf("Terminal"), listOf("More options")))
    }

    @Test
    fun nextAndPreviousTerminalStayInTheOpenTab() {
        home.open(key("a"))
        showTerminal()
        choose("Next terminal")
        assertEquals(key("b"), home.openKey)
        assertTrue("waits as a step does", home.arrivedByStep)
        assertEquals(listOf(spoken("b")), heard)
        choose("Next terminal")
        assertEquals("c sits in another tab", key("b"), home.openKey)
        assertEquals("No more terminals in this tab", heard.last())
        choose("Previous terminal")
        assertEquals(key("a"), home.openKey)
        assertEquals(spoken("a"), heard.last())
        assertEquals(3, heard.size)
    }

    @Test
    fun theAgentsThatNeedYouCrossTabs() {
        // In list order c, who needs you, stands before a: nobody after a needs you.
        home.open(key("a"))
        showTerminal()
        choose("Next agent that needs you")
        assertEquals(key("a"), home.openKey)
        assertEquals(listOf("No more agents need you"), heard)
        choose("Previous agent that needs you")
        assertEquals("c sits in another tab", key("c"), home.openKey)
        assertTrue("waits as a step does", home.arrivedByStep)
        assertEquals(spoken("c"), heard.last())
        choose("Previous agent that needs you")
        assertEquals(key("c"), home.openKey)
        assertEquals("No more agents need you", heard.last())
        choose("Next agent that needs you")
        assertEquals("forward again, as a swipe up retraces", key("a"), home.openKey)
        assertEquals(spoken("a"), heard.last())
    }

    @Test
    fun theActionsWorkUnderTheChatToo() {
        home.open(key("a"))
        showChat()
        choose("Next terminal")
        assertEquals(key("b"), home.openKey)
        assertEquals(listOf(spoken("b")), heard)
        choose("Next terminal")
        assertEquals("No more terminals in this tab", heard.last())
    }

    @Test
    fun everyActionIsRefusedWhileTheComposeSheetHoldsText() {
        home.open(key("a"))
        showTerminal()
        screen.composeOpen = true
        screen.composeText = "run the tests"
        idle()
        for (action in four) choose(action)
        assertEquals(key("a"), home.openKey)
        assertFalse(home.arrivedByStep)
        assertEquals("run the tests", screen.composeText)
        assertEquals(List(4) { "Not moved: the compose text is kept" }, heard)
    }
}
