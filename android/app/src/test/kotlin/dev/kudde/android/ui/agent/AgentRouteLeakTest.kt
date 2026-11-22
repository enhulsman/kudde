package dev.kudde.android.ui.agent

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import dev.kudde.android.testing.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class AgentRouteLeakTest {
    /** Freeze the real route's host; destinations, keeper and ports stay production code. */
    private class PausedMotionClock : MonotonicFrameClock {
        private val waiting = mutableListOf<CancellableContinuation<Long>>()
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            val time = suspendCancellableCoroutine<Long> { continuation ->
                waiting += continuation
                continuation.invokeOnCancellation { waiting -= continuation }
            }
            return onFrame(time)
        }
        fun pulse(ms: Long) {
            val pending = waiting.toList()
            waiting.clear()
            pending.forEach { if (it.isActive) it.resume(ms * 1_000_000L) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun AgentRouteScenario.motion(): ScreenStackHostState<AgentScreen> =
        unmergedSemanticsNodes().mapNotNull { it.config.getOrNull(ScreenStackHostStateKey) }
            .single() as ScreenStackHostState<AgentScreen>

    /** The rig runs at animator scale 0 (instant cuts); these fixtures need the real motion. */
    private fun realMotion() {
        android.provider.Settings.Global.putFloat(
            org.robolectric.RuntimeEnvironment.getApplication().contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private fun AgentRouteScenario.pauseMotion() {
        realMotion()
        motion().animationClock = PausedMotionClock()
    }

    /** The title menu closes with its own exit motion; let that popup go before looking at focus. */
    private fun AgentRouteScenario.letMenuClose() = rig.advanceBoth(400)

    private fun AgentRouteScenario.seekHalfway() {
        val host = motion()
        assertTrue("fixture must have two live destinations", host.running)
        ++host.revision
        host.job?.cancel()
        host.job = host.animationScope!!.launch(host.animationClock!!) {
            host.seekable.seekTo(.5f, 1f)
        }
        rig.frames()
        assertEquals("the actual host is held at progress 0.5", .5f, host.progress, .001f)
    }

    /** The fake reads and opens files; navigation and Back ownership stay in the route. */
    private fun AgentRouteScenario.installChangesFile(): List<String> {
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val ports = rig.ports
        routePorts = object : AgentPorts by ports {
            override fun openChanges(key: dev.kudde.android.ui.agents.AgentKey): uniffi.kudde_mobile.ChangesInterface {
                ports.changes += key
                val signal = ports.io.signal()
                val view = java.util.concurrent.atomic.AtomicReference(uniffi.kudde_mobile.ChangesView(
                    uniffi.kudde_mobile.ChangesList.Listed(listOf(
                        uniffi.kudde_mobile.ChangesEntry("docs/guide.md", "changed", 2uL, 1uL, true),
                    ), false), null, null,
                ))
                return object : uniffi.kudde_mobile.ChangesInterface {
                    override fun read() = ports.io.call { view.get() }
                    override fun open(path: String?) = ports.io.call {
                        calls += "open $path"
                        view.get().copy(open = path, diff = if (path == null) null else uniffi.kudde_mobile.ChangesDiff.Reading)
                            .also { view.set(it) }
                    }
                    override fun waitChange(timeoutMs: UInt) = ports.io.waitForEvent(signal)
                    override fun leave() = ports.io.call { calls += "leave"; signal.release() }
                }
            }
        }
        return calls
    }

    private fun AgentRouteScenario.awaitChangesList() {
        rig.await("Changes file is listed") {
            draw()
            semanticsNodes().any { n -> n.descriptions().any { d -> "guide.md" in d } }
        }
    }

    private fun AgentRouteScenario.frame(clock: PausedMotionClock, ms: Long) {
        clock.pulse(ms)
        rig.frames()
    }

    private fun AgentRouteScenario.progressBack(progress: Float) {
        activity.onBackPressedDispatcher.dispatchOnBackProgressed(
            androidx.activity.BackEventCompat(100f, 100f, progress, androidx.activity.BackEventCompat.EDGE_LEFT))
        rig.frames()
    }

    @Test fun predictiveLeaveSeeksCancelsAndKeepsChatFocus() = AgentRouteScenario().use {
        it.show(chat = true)
        it.edit("draft survives leave cancellation")
        it.focusChat()
        val before = it.opened.view.calls.toList()
        assertTrue(unmergedSemanticsNodes().none { n -> n.config.getOrNull(ScreenStackLeaveBaseKey) == true })
        val host = it.motion()
        val clock = PausedMotionClock()
        realMotion()
        host.animationClock = clock
        it.activity.onBackPressedDispatcher.dispatchOnBackStarted(
            androidx.activity.BackEventCompat(0f, 100f, 0f, androidx.activity.BackEventCompat.EDGE_LEFT))
        it.rig.frames()
        it.progressBack(.5f)
        assertEquals(AgentMotion.leave, host.motion)
        assertTrue(host.predictive)
        assertEquals(.5f, host.progress, .001f)
        assertNull(host.target)
        assertTrue("the list is composed behind the agent during leave",
            unmergedSemanticsNodes().any { n -> n.config.getOrNull(ScreenStackLeaveBaseKey) == true })
        assertNull("the home must be absent from the reader tree", nodeSaying("Machines"))
        assertEquals(it.rig.key, it.rig.home.openKey)
        assertTrue(unmergedSemanticsNodes().any { n -> n.focused() })
        it.activity.onBackPressedDispatcher.dispatchOnBackCancelled()
        it.rig.frames()
        it.frame(clock, 0); it.frame(clock, 1000)
        assertFalse(host.running)
        assertEquals(0f, host.progress, .001f)
        assertEquals(it.rig.key, it.rig.home.openKey)
        assertEquals(before, it.opened.view.calls)
        assertTrue("cancel disposes the list again",
            unmergedSemanticsNodes().none { n -> n.config.getOrNull(ScreenStackLeaveBaseKey) == true })
        assertEquals("draft survives leave cancellation", it.fieldText())
        assertTrue(unmergedSemanticsNodes().any { n -> n.focused() })
    }

    @Test fun predictiveLeaveReleasesOnceAndOnlyThenTheListTakesTouch() = AgentRouteScenario().use {
        it.show()
        val view = it.opened.view
        val host = it.motion()
        val clock = PausedMotionClock()
        realMotion()
        host.animationClock = clock
        it.activity.onBackPressedDispatcher.dispatchOnBackStarted(
            androidx.activity.BackEventCompat(0f, 100f, 0f, androidx.activity.BackEventCompat.EDGE_LEFT))
        it.rig.frames()
        it.progressBack(.5f)
        assertTrue(host.predictive)
        assertEquals(.5f, host.progress, .001f)
        it.draw()
        // Physical taps must be caught even in the exposed area to the left of the card.
        val down = android.os.SystemClock.uptimeMillis()
        it.touch(android.view.MotionEvent.ACTION_DOWN, 8f, 250f, down)
        it.touch(android.view.MotionEvent.ACTION_UP, 8f, 250f, down)
        assertEquals(it.rig.key, it.rig.home.openKey)
        assertEquals(1, it.rig.ports.opens.size)
        assertFalse(view.calls.contains("detach"))
        it.activity.onBackPressedDispatcher.onBackPressed()
        it.rig.frames()
        assertEquals("release must retain the agent until the motion finishes", it.rig.key, it.rig.home.openKey)
        it.frame(clock, 0); it.frame(clock, 1000)
        it.rig.frames()
        it.draw()
        assertNull(it.rig.home.openKey)
        assertEquals(1, view.calls.count { c -> c == "detach" })
        assertEquals(1, view.calls.count { c -> c == "destroy" })
        val nextRow = semanticsNodes().single { n -> n.clickable() && "og tags" in n.texts() }.boundsInWindow
        val row = semanticsNodes().single { n -> n.clickable() && "Pricing page copy" in n.texts() }
        val bounds = row.boundsInWindow
        val tap = android.os.SystemClock.uptimeMillis()
        it.touch(android.view.MotionEvent.ACTION_DOWN, bounds.center.x, bounds.center.y, tap)
        it.touch(android.view.MotionEvent.ACTION_UP, bounds.center.x, bounds.center.y, tap)
        it.rig.await("the list accepts touch after leaving") { it.rig.ports.opens.size == 2 }
        // Repeat against a known clickable row: hitting the other agent would expose a leak.
        val secondHost = it.motion()
        val secondClock = PausedMotionClock()
        secondHost.animationClock = secondClock
        it.activity.onBackPressedDispatcher.dispatchOnBackStarted(
            androidx.activity.BackEventCompat(0f, 100f, 0f, androidx.activity.BackEventCompat.EDGE_LEFT))
        it.rig.frames()
        it.progressBack(.5f)
        val width = it.measuredHost.width.toFloat()
        val shiftedX = nextRow.center.x - .12f * width * (1 - .45f * .5f)
        val blockedTap = android.os.SystemClock.uptimeMillis()
        it.touch(android.view.MotionEvent.ACTION_DOWN, shiftedX, nextRow.center.y, blockedTap)
        it.touch(android.view.MotionEvent.ACTION_UP, shiftedX, nextRow.center.y, blockedTap)
        assertEquals(it.rig.key, it.rig.home.openKey)
        assertEquals(2, it.rig.ports.opens.size)
        it.activity.onBackPressedDispatcher.onBackPressed()
        it.rig.frames()
        it.frame(secondClock, 0); it.frame(secondClock, 1000)
        it.draw()
        val acceptedTap = android.os.SystemClock.uptimeMillis()
        it.touch(android.view.MotionEvent.ACTION_DOWN, nextRow.center.x, nextRow.center.y, acceptedTap)
        it.touch(android.view.MotionEvent.ACTION_UP, nextRow.center.x, nextRow.center.y, acceptedTap)
        it.rig.await("the same row accepts touch when home is on top") { it.rig.ports.opens.size == 3 }
        assertEquals(dev.kudde.android.ui.agents.AgentKey("workstation", "t2"), it.rig.home.openKey)
    }

    // Changed with motion.md (task 029): was releaseBelowTheLeaveThresholdCancelsWithoutClearingFocus,
    // which expected a press at 0.2 to cancel; cancelling is now only handleOnBackCancelled
    // (predictiveLeaveSeeksCancelsAndKeepsChatFocus covers it).
    @Test fun aShallowCommittedLeaveLeavesOnce() = AgentRouteScenario().use {
        it.show(chat = true)
        it.focusChat()
        val before = it.opened.view.calls.toList()
        val host = it.motion()
        val clock = PausedMotionClock()
        realMotion()
        host.animationClock = clock
        it.activity.onBackPressedDispatcher.dispatchOnBackStarted(
            androidx.activity.BackEventCompat(0f, 100f, 0f, androidx.activity.BackEventCompat.EDGE_LEFT))
        it.rig.frames()
        it.progressBack(.2f)
        it.activity.onBackPressedDispatcher.onBackPressed()
        it.rig.frames()
        it.frame(clock, 0); it.frame(clock, 1000)
        assertFalse(host.running)
        assertNull("a press at 0.2 leaves, the system committed it", it.rig.home.openKey)
        assertEquals(1, it.opened.view.calls.count { c -> c == "detach" })
        assertEquals(before.count { c -> c == "detach" } + 1, it.opened.view.calls.count { c -> c == "detach" })
    }

    @Test fun twoAnimatedBacksWithNoFramePopThenLeaveOnce() = AgentRouteScenario().use {
        it.show()
        it.changes()
        val host = it.motion()
        val clock = PausedMotionClock()
        realMotion()
        host.animationClock = clock
        it.activity.onBackPressedDispatcher.onBackPressed()
        it.activity.onBackPressedDispatcher.onBackPressed()
        it.rig.frames()
        it.frame(clock, 0); it.frame(clock, 1000)
        it.rig.frames()
        assertNull(it.rig.home.openKey)
        assertEquals(1, it.opened.view.calls.count { c -> c == "detach" })
        assertEquals(1, it.opened.view.calls.count { c -> c == "destroy" })
    }

    @Test fun buttonBackFromTheBaseRunsLeaveBeforeClosing() = AgentRouteScenario().use {
        it.show()
        val host = it.motion()
        val clock = PausedMotionClock()
        realMotion()
        host.animationClock = clock
        it.tap("Back")
        assertTrue(host.running)
        assertEquals(AgentMotion.leave, host.motion)
        assertEquals(it.rig.key, it.rig.home.openKey)
        it.frame(clock, 0); it.frame(clock, 1000)
        assertNull(it.rig.home.openKey)
        assertEquals(1, it.opened.view.calls.count { c -> c == "detach" })
    }

    @Test fun predictiveBackOnTheChangesListSeeksAndReleaseClosesItOnce() = AgentRouteScenario().use {
        val calls = it.installChangesFile()
        it.show(chat = true)
        val host = it.motion()
        val clock = PausedMotionClock()
        realMotion()
        host.animationClock = clock
        it.changes()
        it.frame(clock, 0); it.frame(clock, 1000)
        it.awaitChangesList()
        assertFalse(host.running)
        val before = it.opened.view.calls.toList()
        it.activity.onBackPressedDispatcher.dispatchOnBackStarted(
            androidx.activity.BackEventCompat(0f, 100f, 0f, androidx.activity.BackEventCompat.EDGE_LEFT))
        it.rig.frames()
        for (p in listOf(.25f, .5f)) {
            it.progressBack(p)
            assertEquals(p, host.progress, .001f)
            assertTrue(host.running)
            assertEquals(AgentMotion.popChanges, host.motion)
            assertNotNull(nodeSaying("Changes"))
        }
        it.activity.onBackPressedDispatcher.onBackPressed()
        it.rig.frames()
        it.frame(clock, 2000); it.frame(clock, 3000)
        assertFalse(host.running)
        assertEquals(AgentScreen.Chat, host.settled)
        assertNull(nodeSaying("Changes"))
        assertNotNull(nodeSaying("More options"))
        assertEquals("a second close would leave the agent", it.rig.key, it.rig.home.openKey)
        assertEquals(before, it.opened.view.calls)
        assertEquals(1, it.rig.ports.opens.size)
        it.rig.await("Changes reading stops once") { calls.count { call -> call == "leave" } == 1 }
        it.rig.frames(20)
        assertEquals(1, calls.count { call -> call == "leave" })
    }

    @Test fun backInsideAChangesFileReturnsToTheListWithoutSeekingTheHost() = AgentRouteScenario().use {
        val calls = it.installChangesFile()
        it.show(chat = true)
        val host = it.motion()
        val clock = PausedMotionClock()
        realMotion()
        host.animationClock = clock
        it.changes()
        it.frame(clock, 0); it.frame(clock, 1000)
        it.awaitChangesList()
        assertTrue(semanticsNodes().first { n -> n.descriptions().any { d -> "guide.md" in d } }.click())
        it.rig.await("Changes file opens") { "open docs/guide.md" in calls && nodeSaying("guide.md") != null }
        val before = it.opened.view.calls.toList()
        val progress = host.progress
        val revision = host.revision
        it.activity.onBackPressedDispatcher.dispatchOnBackStarted(
            androidx.activity.BackEventCompat(0f, 100f, 0f, androidx.activity.BackEventCompat.EDGE_LEFT))
        it.rig.frames()
        it.progressBack(.5f)
        assertEquals(progress, host.progress, .001f)
        assertEquals(revision, host.revision)
        assertFalse(host.running)
        assertNotNull(nodeSaying("guide.md"))
        it.activity.onBackPressedDispatcher.onBackPressed()
        it.rig.frames()
        it.rig.await("Back returns to the Changes list") { nodeSaying("Changes") != null && "open null" in calls }
        assertEquals(1, calls.count { call -> call == "open null" })
        assertEquals(revision, host.revision)
        assertFalse(host.running)
        assertEquals(AgentScreen.Changes, host.settled)
        assertEquals(it.rig.key, it.rig.home.openKey)
        assertEquals(before, it.opened.view.calls)
        assertFalse("returning to the list must not close Changes", "leave" in calls)
    }

    @Test fun backButtonAndKeyOnTheChangesListCloseExactlyOnce() {
        for (key in listOf(false, true)) AgentRouteScenario().use {
            val calls = it.installChangesFile()
            it.show(chat = true)
            val host = it.motion()
            val clock = PausedMotionClock()
            realMotion()
        host.animationClock = clock
            it.changes()
            it.frame(clock, 0); it.frame(clock, 1000)
            it.awaitChangesList()
            val before = it.opened.view.calls.toList()
            if (key) {
                val decor = it.activity.window.decorView
                for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
                    decor.dispatchKeyEvent(android.view.KeyEvent(action, android.view.KeyEvent.KEYCODE_BACK))
                }
                it.rig.frames()
            } else it.tap("Back")
            it.frame(clock, 2000); it.frame(clock, 3000)
            assertNull("Changes closes for key=$key", nodeSaying("Changes"))
            assertNotNull(nodeSaying("More options"))
            assertEquals(AgentScreen.Chat, host.settled)
            assertEquals("one close returns to chat; two would leave it", it.rig.key, it.rig.home.openKey)
            assertEquals(before, it.opened.view.calls)
            it.rig.await("Changes closes once for key=$key") { calls.count { call -> call == "leave" } == 1 }
            it.rig.frames(20)
            assertEquals(1, calls.count { call -> call == "leave" })
        }
    }

    /** These legacy assertions inspect a completed pop without stepping animation frames. */
    private fun withInstantMotion(body: () -> Unit) {
        val resolver = org.robolectric.RuntimeEnvironment.getApplication().contentResolver
        val setting = android.provider.Settings.Global.ANIMATOR_DURATION_SCALE
        val previous = android.provider.Settings.Global.getString(resolver, setting)
        android.provider.Settings.Global.putFloat(resolver, setting, 0f)
        try { body() } finally { android.provider.Settings.Global.putString(resolver, setting, previous) }
    }

    private var midTransition = false

    private fun AgentRouteScenario.halfwayBack() {
        if (!midTransition) return
        realMotion()
        activity.onBackPressedDispatcher.dispatchOnBackStarted(
            androidx.activity.BackEventCompat(0f, 100f, 0f, androidx.activity.BackEventCompat.EDGE_LEFT))
        rig.frames()
        activity.onBackPressedDispatcher.dispatchOnBackProgressed(
            androidx.activity.BackEventCompat(100f, 100f, .5f, androidx.activity.BackEventCompat.EDGE_LEFT))
        rig.frames()
        assertEquals("the real route must deliver predictive events to its host", .5f, motion().progress, .001f)
    }

    @Test fun overviewInertSwipesHalfway() { midTransition = true; aSwipeOnAnInertPartOfTheOverviewDoesNotStep() }
    @Test fun overviewTileAndChipSwipesHalfway() { midTransition = true; aSwipeOnATileOrChipOfTheOverviewDoesNotStep() }
    @Test fun changesSwipesHalfway() { midTransition = true; aSwipeOnChangesEmptyPartAndRowDoesNotStep() }

    @Test fun chatFocusAndReaderTreeHalfway() = AgentRouteScenario().use {
        it.show(chat = true)
        it.edit("draft kept during motion")
        it.focusChat()
        it.changes()
        midTransition = true
        it.halfwayBack()
        it.noFocusOrKeyboard("Halfway back from Changes")
        assertTrue(semanticsNodes().none { n -> n.config.getOrNull(SemanticsActions.SetText) != null })
    }

    @Test fun terminalKeyboardAndLateInputHalfway() = AgentRouteScenario().use {
        it.show()
        val input = it.inputViews().single()
        input.showKeyboard()
        val connection = input.onCreateInputConnection(EditorInfo())
        it.pauseMotion()
        it.overview()
        it.seekHalfway()
        val calls = it.opened.view.calls.toList()
        connection.commitText("late key during motion", 1)
        it.rig.frames()
        assertEquals(calls, it.opened.view.calls)
        it.noFocusOrKeyboard("Halfway back from overview")
        assertFalse(input.isFocused)
    }

    @Test fun composeDraftAndFocusHalfway() = AgentRouteScenario().use {
        it.show()
        it.tap("Compose")
        it.edit("compose draft during motion")
        it.pauseMotion()
        it.overview()
        it.seekHalfway()
        it.noFocusOrKeyboard("Compose preview halfway back")
        assertTrue(semanticsNodes().none { n -> n.config.getOrNull(SemanticsActions.SetText) != null })
        val calls = it.opened.view.calls.toList()
        it.rig.frames(20)
        assertEquals(calls, it.opened.view.calls)
        it.noFocusOrKeyboard("Following frames halfway back")
    }

    @Test fun lateKeyboardCallbackHalfway() = AgentRouteScenario().use {
        it.show(chat = true)
        it.edit("draft under a late callback")
        it.focusChat()
        it.changes()
        midTransition = true
        it.halfwayBack()
        it.activity.window.decorView.dispatchApplyWindowInsets(WindowInsets.Builder()
            .setVisible(WindowInsets.Type.ime(), false).build())
        it.rig.advance(800)
        it.noFocusOrKeyboard("Late keyboard callback halfway back")
        assertEquals(it.rig.key, it.rig.home.openKey)
    }

    private fun haptics() = windowRoots().flatMap(::composeRoots).map { shadowOf(it as View).lastHapticFeedbackPerformed() }

    private fun probeSwipe(cover: String, region: String, vertical: Boolean): List<String> = AgentRouteScenario().use {
        it.routePorts = it.rig.ports.withListedChanges()
        it.show()
        val title = it.titleBounds()
        if (midTransition) it.pauseMotion()
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
        if (midTransition) { it.letMenuClose(); it.seekHalfway() }
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

    @Test fun onlyTheTopScreensNodesExistHalfwayThroughATransition() = AgentRouteScenario().use {
        it.show(chat = true)
        it.edit("hidden draft sentinel")
        it.pauseMotion()
        it.changes()
        it.letMenuClose()
        it.seekHalfway()
        val exposed = semanticsNodes()
        assertTrue("reader tree contains Changes", exposed.any { n -> n.texts() == listOf("Changes") })
        assertTrue("Changes exposes no chat field to the reader", exposed.none { n -> n.config.getOrNull(SemanticsActions.SetText) != null })
        assertTrue("outgoing chat really remains composed halfway", unmergedSemanticsNodes().any { n ->
            n.config.getOrNull(SemanticsActions.SetText) != null
        })
        it.noFocusOrKeyboard("Outgoing chat halfway through a push")
    }

    @Test fun openingChangesWithTheChatFocusedLeavesNoFocusAndTheKeyboardDownHalfway() = AgentRouteScenario().use {
        it.show(chat = true)
        it.edit("keep the draft")
        it.focusChat()
        it.pauseMotion()
        it.changes()
        it.letMenuClose()
        it.seekHalfway()
        it.noFocusOrKeyboard("Opening Changes with chat focused")
        it.rig.frames(20)
        assertEquals(.5f, it.motion().progress, .001f)
        it.noFocusOrKeyboard("Following frames under Changes")
    }

    @Test fun backToTheChatLeavesTheKeyboardDownHalfway() = AgentRouteScenario().use {
        it.show(chat = true)
        it.edit("draft comes back")
        it.focusChat()
        it.pauseMotion()
        it.changes()
        it.seekHalfway()
        it.tap("Back")
        it.seekHalfway()
        assertEquals("draft comes back", it.fieldText())
        it.noFocusOrKeyboard("Back to chat without a fresh field tap")
        it.rig.frames(20)
        assertEquals(.5f, it.motion().progress, .001f)
        it.noFocusOrKeyboard("Frames after Back to chat")
    }

    @Test fun theTerminalsKeyboardIsDownAfterTheOverviewAndAKeyReachesNoSinkHalfway() = AgentRouteScenario().use {
        it.show()
        val input = it.inputViews().single()
        input.showKeyboard()
        val connection = input.onCreateInputConnection(EditorInfo())
        assertTrue("fixture terminal keyboard up", it.keyboardUp())
        it.pauseMotion()
        it.overview()
        it.seekHalfway()
        val before = it.opened.view.calls.toList()
        connection.commitText("late key", 1)
        it.rig.frames()
        assertEquals("key under overview reached a terminal sink", before, it.opened.view.calls)
        it.noFocusOrKeyboard("Opening overview from terminal")
        assertFalse("terminal Android input view still holds focus under overview", input.isFocused)
    }

    @Test fun aComposeSheetWithTextComesBackWithoutFocusHalfway() = AgentRouteScenario().use {
        it.show()
        it.tap("Compose")
        it.edit("compose text survives")
        it.rig.await("compose field focused") { unmergedSemanticsNodes().any { n -> n.focused() } }
        it.pauseMotion()
        it.overview()
        it.seekHalfway()
        it.back()
        it.seekHalfway()
        assertEquals("compose text survives", it.fieldText())
        assertNotNull(nodeSaying("Paste"))
        it.noFocusOrKeyboard("Returning to an existing compose sheet")
        it.rig.frames(20)
        assertEquals(.5f, it.motion().progress, .001f)
        it.noFocusOrKeyboard("Frames after returning to compose")
    }

    @Test fun quickChangesWithALateKeyboardCallbackLeaveNoKeyboardHalfway() = AgentRouteScenario().use {
        it.show(chat = true)
        it.edit("draft survives quick covers")
        it.focusChat()
        it.pauseMotion()
        val decor = it.activity.window.decorView
        Handler(Looper.getMainLooper()).postDelayed({
            decor.dispatchApplyWindowInsets(WindowInsets.Builder()
                .setVisible(WindowInsets.Type.ime(), false).build())
        }, 600)
        repeat(2) { _ ->
            it.changes(); it.seekHalfway()
            it.tap("Back"); it.seekHalfway()
        }
        it.rig.advance(800)
        assertEquals(.5f, it.motion().progress, .001f)
        it.noFocusOrKeyboard("Quick Changes with late IME-hidden callback")
        assertEquals("draft survives quick covers", it.fieldText())
        assertEquals(it.rig.key, it.rig.home.openKey)
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
    @Test fun backToTheChatLeavesTheKeyboardDown() = withInstantMotion {
        AgentRouteScenario().use {
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
    @Test fun quickChangesWithALateKeyboardCallbackLeaveNoKeyboard() = withInstantMotion {
        AgentRouteScenario().use {
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

    // screen-stack 7.3: a pointer already down on a tile keeps its path through a transition.
    @Test fun aPressBeganBeforeBackOnAnOverviewTileOpensNothingWhenReleasedDuringThePop() = AgentRouteScenario().use {
        it.show()
        it.overview()
        val tile = nodeSaying("og tags")
        assertNotNull("a tile for another terminal", tile)
        val b = tile!!.boundsInWindow
        val down = android.os.SystemClock.uptimeMillis()
        it.touch(android.view.MotionEvent.ACTION_DOWN, b.center.x, b.center.y, down)
        it.pauseMotion()
        it.back()
        assertTrue("the pop is running", it.motion().running)
        it.touch(android.view.MotionEvent.ACTION_UP, b.center.x, b.center.y, down)
        it.rig.frames(20)
        assertEquals("the tile did not open its terminal", it.rig.key, it.rig.home.openKey)
        assertEquals("no terminal attached", 1, it.rig.ports.opens.size)
    }

    // screen-stack 7.3: a chosen menu item drops its popup at once, so Back is not eaten by its exit.
    @Test fun backRightAfterChoosingChangesClosesChangesAndShowsTheTerminal() = AgentRouteScenario().use {
        it.show()
        realMotion()
        it.tap("More options")
        it.tap("Changes")
        // The platform sends Back to the topmost window: a lingering menu popup would take it.
        val top = windowRoots().last()
        top.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
        top.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        it.rig.advanceBoth(1_500)
        assertNull("Changes closed", nodeSaying("Changes"))
        assertNotNull("the terminal is shown", nodeSaying("More options"))
        assertEquals(it.rig.key, it.rig.home.openKey)
    }
}
