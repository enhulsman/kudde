package dev.kudde.android.ui.chat

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.testing.nodeSaying
import dev.kudde.android.ui.agent.AgentRouteScenario
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.Assert.assertEquals
import org.junit.Test
import uniffi.kudde_mobile.ChatRow

// accessibility spec, "The chat announces new answers": live "said" rows,
// each once, in order; never history, the person's rows, tools or notes.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ChatAnnounceTest {
    private fun row(key: String, kind: String = "said") = ChatRow(key, kind, "Text $key", "", emptyList(), emptyList())

    @Test
    fun theFirstReadIsNotAnnounced() {
        val live = LiveRows()
        val heard = Announced()
        val rows = listOf(row("a"), row("b"))
        live.saw(rows, now = 1, live = false)
        assertEquals(emptyList<String>(), heard.next(rows, live))
    }

    @Test
    fun aLiveAnswerIsAnnouncedOnce() {
        val live = LiveRows()
        val heard = Announced()
        val first = listOf(row("a"))
        live.saw(first, now = 1, live = false)
        heard.next(first, live)
        val more = first + row("b")
        live.saw(more, now = 2, live = true)
        assertEquals(listOf("Text b"), heard.next(more, live))
        assertEquals(emptyList<String>(), heard.next(more, live))
    }

    @Test
    fun onlyAnswersAndInOrder() {
        val live = LiveRows()
        val heard = Announced()
        val rows = listOf(row("u", "user"), row("t", "tools"), row("n", "note"), row("x"), row("ag", "agent"), row("y"))
        live.saw(rows, now = 1, live = true)
        assertEquals(listOf("Text x", "Text y"), heard.next(rows, live))
    }

    @Test
    fun aReadBackIsNotAnnounced() {
        val live = LiveRows()
        val heard = Announced()
        val now = listOf(row("c"))
        live.saw(now, now = 1, live = false)
        val older = listOf(row("a"), row("b")) + now
        live.saw(older, now = 2, live = false)
        assertEquals(emptyList<String>(), heard.next(older, live))
    }

    // passes today: ChatScreen remains composed under Changes and announces there already.
    @Test
    fun anAnswerUnderChangesIsAnnouncedOnceAndNotOnReturn() = AgentRouteScenario().use { host ->
        val manager = host.rig.app.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as AccessibilityManager
        val reader = shadowOf(manager)
        reader.setEnabled(true)
        reader.setTouchExplorationEnabled(true)
        host.show(chat = true)
        fun heard(text: String) = reader.sentAccessibilityEvents.count { event ->
            event.eventType == AccessibilityEvent.TYPE_ANNOUNCEMENT &&
                text in event.text.map { it.toString() }
        }
        val chat = host.rig.ports.chat(host.rig.key)
        val visible = row("visible-control")
        chat.answer(visible)
        host.rig.await("visible control answer has reached the real poll") { host.state.poll?.rows?.contains(visible) == true }
        host.rig.frames(12)
        assertEquals("visible chat announces the control answer exactly once", 1, heard(visible.text))
        host.changes()
        val covered = row("covered-answer")
        chat.answer(covered)
        host.rig.await("covered answer has reached the real poll") { host.state.poll?.rows?.contains(covered) == true }
        host.rig.frames(12)
        assertNotNull("answer arrived with real Changes still on top", nodeSaying("Changes"))
        assertEquals("answer arriving under Changes must be announced now exactly once", 1, heard(covered.text))
        host.back()
        host.rig.advance(800)
        assertNull(nodeSaying("Changes"))
        assertEquals("returning to chat must not announce the covered answer again", 1, heard(covered.text))
        assertEquals("returning must not repeat the visible control either", 1, heard(visible.text))
    }

}
