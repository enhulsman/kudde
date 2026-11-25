package dev.kudde.android.ui.hint

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.testing.click
import dev.kudde.android.testing.nodeSaying
import dev.kudde.android.testing.semanticsNodes
import dev.kudde.android.testing.texts
import dev.kudde.android.ui.agent.AgentHost
import dev.kudde.android.ui.agent.AgentRouteRig
import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.terminal.TitleBarFixtures
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Task 4.7, spec "The title bar explains itself once": where it shows, what it says, how it closes.
// Texts G1 to G6 are the approved ones (design.md "Texts").
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class TitleBarHintTest {
    private lateinit var rig: AgentRouteRig
    private var controller: ActivityController<ComponentActivity>? = null

    @Before fun prepare() {
        rig = AgentRouteRig()
        rig.install()
        rig.publish(TitleBarFixtures.hub)
        rig.home.onSnapshot(TitleBarFixtures.hub)
    }

    @After fun close() {
        try { controller?.pause()?.stop()?.destroy() } finally { rig.close() }
    }

    private fun show(address: String, chat: Boolean) {
        rig.app.chatView.choose(chat)
        rig.home.open(AgentKey("workstation", address))
        if (controller == null) controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller!!.get().setContent {
            KuddeTheme(dark = true) {
                rig.home.openKey?.let { AgentHost(rig.app, rig.home, it, rig.ports) }
            }
        }
        rig.await("the title bar shows") { nodeSaying("More options") != null }
    }

    private fun hint(): SemanticsNode? = semanticsNodes().firstOrNull { node -> node.texts().any { it.startsWith("Move around from this bar") } }
    private fun allText() = semanticsNodes().flatMap { it.texts() }.joinToString("\n")

    @Test fun showsOnTheFirstTerminalInATabOfThree() = firstOpen(chat = false)
    @Test fun showsOnTheFirstChatInATabOfThree() = firstOpen(chat = true)

    private fun firstOpen(chat: Boolean) {
        show("t1", chat)
        assertNotNull(hint())
        val text = allText()
        assertTrue(text, "Swipe sideways for other panes in this tab. At either end it bounces; with one pane it only bounces." in text)
        assertTrue(text, "Swipe up: the next agent that needs you. Down: back." in text)
        assertTrue(text, "Tap the map: every pane of the tab." in text)
        assertFalse("not the TalkBack variant", "With TalkBack" in text)
    }

    @Test fun showsInAOnePaneTab() {
        show("t4", chat = false)
        assertNotNull(hint())
        assertTrue("Swipe sideways for other panes in this tab. At either end it bounces; with one pane it only bounces." in allText())
    }

    @Test fun aTerminalInNoPaneLeavesItDue() {
        show("t7", chat = false)
        assertNull(hint())
        assertFalse(rig.app.titleBarHint.seen)
        show("t7", chat = true)
        assertNull(hint())
        assertFalse(rig.app.titleBarHint.seen)
    }

    @Test fun aTapClosesItForGoodInTerminalAndChat() {
        show("t1", chat = false)
        assertTrue(hint()!!.click())
        rig.frames()
        assertNull(hint())
        assertTrue(rig.app.titleBarHint.seen)
        assertEquals(setOf("seen"), controller!!.get().getSharedPreferences("title_bar_hint", Context.MODE_PRIVATE).all.keys)
        show("t2", chat = true)
        assertNull("not over the chat any more", hint())
        show("t4", chat = false)
        assertNull(hint())
        assertTrue("a new app instance reads the flag", TitleBarHintSetting.open(rig.app).seen)
    }

    @Test fun leavingWithoutATapLeavesItDue() {
        show("t1", chat = false)
        assertNotNull(hint())
        rig.home.close()
        assertFalse(rig.app.titleBarHint.seen)
        show("t2", chat = true)
        assertNotNull("shows over the chat too", hint())
    }

    @Test fun itIsOneFocusableElementWithAGotItAction() {
        show("t1", chat = false)
        val node = hint()!!
        val label = node.config.getOrNull(SemanticsActions.OnClick)?.label
        assertEquals("Got it", label)
        assertEquals(LiveRegionMode.Polite, node.config.getOrNull(SemanticsProperties.LiveRegion))
        assertEquals("one element holds the whole text", 1, semanticsNodes().count { n -> n.texts().any { "Swipe up: the next agent" in it } })
    }

    @Test fun withTalkBackItNamesTheActionsInsteadOfTheSwipes() {
        val manager = controllerAccessibility()
        shadowOf(manager).setTouchExplorationEnabled(true)
        show("t1", chat = false)
        val text = allText()
        assertTrue(text, "With TalkBack, the title bar's actions move around: Next terminal, Previous terminal, Next agent that needs you, Previous agent that needs you. Double tap the map for every pane of the tab." in text)
        assertFalse("not G2 to G4", "Swipe sideways" in text)
        assertFalse(text.contains("Swipe up"))
        assertFalse(text.contains("Tap the map"))
    }

    @Test fun withAnimationsOffItIsThereAtOnce() {
        Settings.Global.putFloat(rig.app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        show("t1", chat = false)
        assertNotNull(hint())
        assertTrue(hint()!!.click())
        rig.frames(2)
        assertNull("gone without waiting for a fade", hint())
    }

    @Test fun itSitsUnderTheTitleBarAndAboveTheMessageBox() {
        show("t1", chat = true)
        val bar = nodeSaying("More options")!!.boundsInRoot
        val h = hint()!!.boundsInRoot
        assertTrue("under the title bar", h.top >= bar.bottom - 1f)
        val box = semanticsNodes().firstOrNull { "Message" in it.texts() || it.config.getOrNull(SemanticsProperties.EditableText) != null }
        if (box != null) assertTrue("clear of the message box", h.bottom <= box.boundsInRoot.top + 1f)
    }

    private fun controllerAccessibility(): AccessibilityManager =
        rig.app.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
}
