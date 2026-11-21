package dev.kudde.android.ui.agent

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import dev.kudde.android.testing.*
import org.junit.Assert.*
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class AgentRouteLeakTest {
    private fun haptics() = windowRoots().flatMap(::composeRoots).map { shadowOf(it as View).lastHapticFeedbackPerformed() }

    private fun probeSwipe(cover: String, region: String, vertical: Boolean): List<String> = AgentRouteScenario().use {
        it.routePorts = it.rig.ports.withListedChanges()
        it.show()
        val title = it.titleBounds()
        if (cover == "Overview") it.overview() else it.changes()
        val bounds = when (region) {
            "inert" -> Rect(title.width * .25f, title.top, title.right, title.bottom)
            "tile" -> nodeSaying("Pricing page copy")!!.boundsInWindow
            "chip" -> semanticsNodes().first { n -> n.descriptions().any { d -> d.startsWith("Tab main,") } }.boundsInWindow
            else -> {
                it.rig.await("listed Changes row is drawn") {
                    it.draw()
                    semanticsNodes().any { n -> n.descriptions().any { d -> "guide.md" in d } }
                }
                semanticsNodes().first { n -> n.descriptions().any { d -> "guide.md" in d } }.boundsInWindow
            }
        }
        val key = it.rig.home.openKey
        val attaches = it.rig.ports.opens.size
        val calls = it.opened.view.calls.toList()
        val buzz = haptics()
        it.swipe(bounds, vertical)
        val reason = "$cover $region swipe"
        buildList {
            if (key != it.rig.home.openKey) add("$reason reached ${if (vertical) "onSwipe" else "onTab"}")
            if (attaches != it.rig.ports.opens.size) add("$reason attached a pane")
            if (calls != it.rig.ports.opens.first().view.calls) add("$reason sent input to the terminal")
            if (buzz != haptics()) add("$reason played a step haptic")
            if (nodeSaying(if (cover == "Overview") "Pricing page copy" else "Changes") == null ||
                nodeSaying("More options") != null) add("$reason dismissed the cover")
        }
    }

    private fun assertNoLeaks(cover: String, regions: List<String>) {
        // A fresh real host for each gesture means an earlier leak cannot invalidate the
        // later fixture. Report every direction and region even when the first one leaks.
        val leaks = regions.flatMap { region -> listOf(false, true).flatMap { vertical -> probeSwipe(cover, region, vertical) } }
        assertTrue(leaks.joinToString("; "), leaks.isEmpty())
    }

    // red until 5.2
    @Test fun aSwipeOnAnInertPartOfTheOverviewDoesNotStep() = assertNoLeaks("Overview", listOf("inert"))

    // red until 5.4: the chips scroll, but a sideways drag that lifts on a tile is taken as a tap (closes the overview).
    @Test fun aSwipeOnATileOrChipOfTheOverviewDoesNotStep() = assertNoLeaks("Overview", listOf("tile", "chip"))

    // red until 5.2
    @Test fun aSwipeOnChangesEmptyPartAndRowDoesNotStep() = assertNoLeaks("Changes", listOf("inert", "row"))

    // red until 5.2
    @Test fun onlyTheTopScreensNodesExist() = AgentRouteScenario().use {
        it.show(chat = true)
        it.edit("hidden draft sentinel")
        it.changes()
        val exposed = semanticsNodes()
        assertTrue("reader tree contains Changes", exposed.any { n -> n.texts() == listOf("Changes") })
        assertTrue("Changes exposes no chat field to the reader", exposed.none { n -> n.config.getOrNull(SemanticsActions.SetText) != null })
        // Clear-and-set wrappers can hide the reader nodes while leaving a live field beneath.
        // Check the unmerged composition as well; at rest only the top destination may exist.
        assertTrue("chat nodes still exist under Changes at rest", unmergedSemanticsNodes().none { n ->
            n.config.getOrNull(SemanticsActions.SetText) != null || "More options" in n.descriptions() || "Terminal" in n.descriptions()
        })
    }

    @Ignore("needs 5.1")
    @Test fun onlyTheTopScreensNodesExistHalfwayThroughATransition() {
        // The transition progress seam belongs to 5.1; do not reference an invented API here.
    }

    private fun AgentRouteScenario.noFocusOrKeyboard(reason: String) {
        assertTrue("$reason left a focused cursor beneath the cover", unmergedSemanticsNodes().none { it.focused() })
        assertFalse("$reason left the keyboard up", keyboardUp())
    }

    // red until 5.2
    @Test fun openingChangesWithTheChatFocusedLeavesNoFocusAndTheKeyboardDown() = AgentRouteScenario().use {
        it.show(chat = true)
        it.edit("keep the draft")
        it.focusChat()
        it.changes()
        it.noFocusOrKeyboard("Opening Changes with chat focused")
        it.rig.frames(20)
        it.noFocusOrKeyboard("Following frames under Changes")
    }

    // red until 5.2
    @Test fun backToTheChatLeavesTheKeyboardDown() = AgentRouteScenario().use {
        it.show(chat = true)
        it.edit("draft comes back")
        it.focusChat()
        it.changes()
        it.back()
        assertEquals("draft comes back", it.fieldText())
        it.noFocusOrKeyboard("Back to chat without a fresh field tap")
        it.rig.frames(20)
        it.noFocusOrKeyboard("Frames after Back to chat")
    }

    // red until 5.2
    @Test fun theTerminalsKeyboardIsDownAfterTheOverviewAndAKeyReachesNoSink() = AgentRouteScenario().use {
        it.show()
        val input = it.inputViews().single()
        input.showKeyboard()
        val connection = input.onCreateInputConnection(EditorInfo())
        assertTrue("fixture terminal keyboard up", it.keyboardUp())
        it.overview()
        val before = it.opened.view.calls.toList()
        connection.commitText("late key", 1)
        it.rig.frames()
        assertEquals("key under overview reached a terminal sink", before, it.opened.view.calls)
        it.noFocusOrKeyboard("Opening overview from terminal")
        assertFalse("terminal Android input view still holds focus under overview", input.isFocused)
    }

    // red until 5.2
    @Test fun aComposeSheetWithTextComesBackWithoutFocus() = AgentRouteScenario().use {
        it.show()
        it.tap("Compose")
        it.edit("compose text survives")
        it.rig.await("compose field focused") { unmergedSemanticsNodes().any { n -> n.focused() } }
        it.overview()
        it.back()
        assertEquals("compose text survives", it.fieldText())
        assertNotNull(nodeSaying("Paste"))
        it.noFocusOrKeyboard("Returning to an existing compose sheet")
        it.rig.frames(20)
        it.noFocusOrKeyboard("Frames after returning to compose")
    }

    // red until 5.2
    @Test fun quickChangesWithALateKeyboardCallbackLeaveNoKeyboard() = AgentRouteScenario().use {
        it.show(chat = true)
        it.edit("draft survives quick covers")
        it.focusChat()
        // Deliver the platform's IME-hidden insets after both rapid round trips.
        val decor = it.activity.window.decorView
        Handler(Looper.getMainLooper()).postDelayed({
            decor.dispatchApplyWindowInsets(WindowInsets.Builder()
                .setVisible(WindowInsets.Type.ime(), false).build())
        }, 600)
        repeat(2) { _ -> it.changes(); it.back() }
        it.rig.advance(800)
        it.noFocusOrKeyboard("Quick Changes with late IME-hidden callback")
        assertEquals("draft survives quick covers", it.fieldText())
        assertEquals(it.rig.key, it.rig.home.openKey)
    }
}
