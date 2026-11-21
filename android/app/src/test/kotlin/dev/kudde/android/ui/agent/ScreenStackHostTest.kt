package dev.kudde.android.ui.agent

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.runtime.snapshots.Snapshot
import dev.kudde.android.testing.*
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ScreenStackHostTest {
    private val paused = PopupSafeClock.paused()
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity get() = controller.get()
    private val top = mutableStateOf<AgentScreen>(AgentScreen.Chat)
    private val clock = TestClock()
    private val host = ScreenStackHostState<AgentScreen>(top.value, clock)
    private val composed = mutableSetOf<AgentScreen>()
    private val touches = mutableMapOf<AgentScreen, Int>()
    private val backs = mutableMapOf<AgentScreen, Int>()
    private val requester = FocusRequester()
    private var left = 0
    private var hide = 0
    private var focusOnArrival = false
    private val selected = mutableListOf<Triple<AgentScreen, AgentScreen?, AgentChangeReason>>()
    private val keyboard = object : SoftwareKeyboardController {
        override fun show() = Unit
        override fun hide() { hide++ }
    }

    @After fun close() {
        controller.pause().stop().destroy()
        BoundedFrames.step(4)
    }

    private fun show(animated: Boolean = false, innerBack: Boolean = false, cut: Boolean = false) {
        activity.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                ScreenStackHost(
                    top = top.value,
                    state = host,
                    backTarget = { if (top.value == AgentScreen.Changes) AgentScreen.Chat else null },
                    leaveContent = { Box(Modifier.fillMaxSize().testTag("dest-leave")) },
                    onBack = { if (top.value == AgentScreen.Changes) top.value = AgentScreen.Chat else left++ },
                    transitionFor = { from, to, reason ->
                        selected += Triple(from, to, reason)
                        AgentMotion.transitionFor(from, to, reason).let {
                            if (animated) it.copy(progressSpec = tween(1000, easing = LinearEasing))
                            else if (cut) it.copy(progressSpec = snap()) else it
                        }
                    },
                ) { destination ->
                    DisposableEffect(destination) {
                        composed += destination
                        onDispose { composed -= destination }
                    }
                    if (innerBack && destination == AgentScreen.Chat) BackHandler {
                        backs[destination] = (backs[destination] ?: 0) + 1
                    }
                    Box(Modifier.fillMaxSize().testTag("dest-$destination").pointerInput(destination) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent()
                                touches[destination] = (touches[destination] ?: 0) + 1
                            }
                        }
                    }) {
                        if (destination == AgentScreen.Chat) {
                            BasicTextField("draft", {}, Modifier.focusRequester(requester))
                            if (focusOnArrival) LaunchedEffect(Unit) { requester.requestFocus() }
                        } else BasicText(destination.toString())
                    }
                }
            }
        }
        frames()
    }

    private fun frames(n: Int = 4) {
        Snapshot.sendApplyNotifications()
        BoundedFrames.step(n)
    }

    private fun pulse(ms: Long) {
        clock.pulse(ms)
        frames()
    }

    private fun change(to: AgentScreen) { top.value = to; frames() }

    private fun touch() {
        val decor = activity.window.decorView
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { i, action ->
            val event = MotionEvent.obtain(now, now + i * 16L, action, decor.width / 2f, decor.height / 2f, 0)
            try { decor.dispatchTouchEvent(event) } finally { event.recycle() }
            frames(1)
        }
    }

    private fun startBack(progress: Float) {
        activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
        frames()
        activity.onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(100f, 100f, progress, BackEventCompat.EDGE_LEFT))
        frames()
    }

    @Test fun onlyTheTopsContentIsComposedAtRest() {
        show(cut = true)
        assertEquals(setOf(AgentScreen.Chat), composed)
        for (destination in listOf(AgentScreen.Changes, AgentScreen.Terminal, AgentScreen.Overview("space", "tab"), AgentScreen.Chat)) {
            top.value = destination
            frames(1)
            assertEquals("the placeholder cuts in one frame", setOf(destination), composed)
        }
    }

    @Test fun aFocusedFieldInALeftDestinationIsUnfocusedAndTheKeyboardHidden() {
        focusOnArrival = true
        show()
        assertTrue(unmergedSemanticsNodes().any { it.focused() })
        change(AgentScreen.Changes)
        assertFalse(unmergedSemanticsNodes().any { it.focused() })
        assertTrue("the controller is asked to hide; SDK 35 cannot report Compose IME visibility", hide > 0)
        focusOnArrival = false
        change(AgentScreen.Chat)
        assertFalse("a return without a request stays unfocused", unmergedSemanticsNodes().any { it.focused() })
    }

    @Test fun aPointerInputInALeftDestinationReceivesNothing() {
        show(cut = true)
        touch()
        assertTrue((touches[AgentScreen.Chat] ?: 0) > 0)
        change(AgentScreen.Changes)
        val before = touches[AgentScreen.Chat]
        touch()
        assertEquals(before, touches[AgentScreen.Chat])
        assertTrue((touches[AgentScreen.Changes] ?: 0) > 0)
    }

    @Test fun theBackHandlersOfALeftDestinationDoNotRun() {
        show(innerBack = true, cut = true)
        activity.onBackPressedDispatcher.onBackPressed()
        frames()
        assertEquals(1, backs[AgentScreen.Chat])
        change(AgentScreen.Changes)
        activity.onBackPressedDispatcher.onBackPressed()
        frames()
        assertEquals(AgentScreen.Chat, top.value)
        assertEquals(1, backs[AgentScreen.Chat])
        assertEquals(0, left)
    }

    @Test fun theOutgoingDestinationTakesNoInputOrSemanticsMidTransition() {
        focusOnArrival = true
        show(animated = true, innerBack = true)
        assertTrue(unmergedSemanticsNodes().any { it.focused() })
        focusOnArrival = false
        change(AgentScreen.Changes)
        pulse(0)
        pulse(500)
        assertEquals(.5f, host.progress, .001f)
        assertEquals(setOf(AgentScreen.Chat, AgentScreen.Changes), composed)
        assertFalse("no outgoing focus survives the first half of the motion", unmergedSemanticsNodes().any { it.focused() })
        assertTrue(hide > 0)
        // The merged tree is what a screen reader reads: clearAndSetSemantics drops the outgoing
        // subtree from it, while the unmerged tree still lists the cleared children.
        assertFalse("the outgoing chat field is not reachable by a screen reader", semanticsNodes().any { it.config.getOrNull(SemanticsActions.SetText) != null })
        assertNotNull(nodeSaying("Changes"))
        val before = touches.toMap()
        touch()
        assertEquals("both destinations are touch-gated with the animation clock stopped", before, touches)
        activity.onBackPressedDispatcher.onBackPressed()
        frames()
        assertEquals("the outgoing chat BackHandler is disabled", null, backs[AgentScreen.Chat])
        assertEquals("Back interrupts at the current progress", .5f, host.progress, .001f)
        pulse(1000)
        pulse(2000)
    }

    @Test fun transitionForIsChosenPerChangeAndReason() {
        show(cut = true)
        change(AgentScreen.Terminal)
        change(AgentScreen.Changes)
        activity.onBackPressedDispatcher.onBackPressed()
        frames()
        activity.onBackPressedDispatcher.onBackPressed()
        frames()
        assertTrue(selected.contains(Triple(AgentScreen.Chat, AgentScreen.Terminal, AgentChangeReason.BaseSwitch)))
        assertTrue(selected.contains(Triple(AgentScreen.Terminal, AgentScreen.Changes, AgentChangeReason.Push)))
        assertTrue(selected.contains(Triple(AgentScreen.Changes, AgentScreen.Chat, AgentChangeReason.Pop)))
        assertTrue(selected.contains(Triple(AgentScreen.Chat, null, AgentChangeReason.Leave)))
        assertEquals(1, left)
        val overview = AgentScreen.Overview("space", "tab")
        assertEquals(AgentMotion.pushOverview, AgentMotion.transitionFor(AgentScreen.Chat, overview, AgentChangeReason.Push))
        assertEquals(AgentMotion.popOverview, AgentMotion.transitionFor(overview, AgentScreen.Chat, AgentChangeReason.Pop))
        assertEquals(AgentMotion.toChat, AgentMotion.transitionFor(AgentScreen.Terminal, AgentScreen.Chat, AgentChangeReason.BaseSwitch))
        assertEquals(AgentMotion.toTerminal, AgentMotion.transitionFor(AgentScreen.Chat, AgentScreen.Terminal, AgentChangeReason.BaseSwitch))
        assertEquals(AgentMotion.pushChanges, AgentMotion.transitionFor(AgentScreen.Chat, AgentScreen.Changes, AgentChangeReason.Push))
        assertEquals(AgentMotion.popChanges, AgentMotion.transitionFor(AgentScreen.Changes, AgentScreen.Chat, AgentChangeReason.Pop))
        assertEquals(AgentMotion.leave, AgentMotion.transitionFor(AgentScreen.Chat, null, AgentChangeReason.Leave))
    }

    @Test fun aSeekedGestureSetsTheProgressAndACancelRestoresIt() {
        show(animated = true)
        // Finish the opening motion before starting the gesture.
        change(AgentScreen.Changes)
        pulse(0); pulse(1000)
        startBack(.5f)
        assertEquals(.5f, host.progress, .001f)
        assertEquals(AgentScreen.Changes, top.value)
        val hidden = hide
        activity.onBackPressedDispatcher.dispatchOnBackCancelled()
        frames()
        pulse(2000); pulse(3000)
        assertEquals(0f, host.progress, .001f)
        assertEquals(setOf(AgentScreen.Changes), composed)
        assertEquals(AgentScreen.Changes, top.value)
        assertEquals(hidden, hide)
        assertEquals(0, left)
        startBack(.5f)
        activity.onBackPressedDispatcher.onBackPressed()
        frames()
        pulse(4000); pulse(5000)
        assertEquals(AgentScreen.Chat, top.value)
        assertEquals(setOf(AgentScreen.Chat), composed)
        requester.requestFocus()
        frames()
        assertTrue(unmergedSemanticsNodes().any { it.focused() })
        startBack(.5f)
        assertEquals(.5f, host.progress, .001f)
        activity.onBackPressedDispatcher.dispatchOnBackCancelled()
        frames()
        pulse(6000); pulse(7000)
        assertTrue("a cancelled leave preserves focus", unmergedSemanticsNodes().any { it.focused() })
        assertEquals(0, left)
        assertEquals(setOf(AgentScreen.Chat), composed)
        startBack(.5f)
        activity.onBackPressedDispatcher.onBackPressed()
        frames()
        pulse(8000); pulse(9000)
        assertEquals(1, left)
    }

    @Test fun aSecondChangeRetargetsFromTheCurrentProgress() {
        show(animated = true)
        change(AgentScreen.Changes)
        pulse(0); pulse(500)
        val half = host.progress
        assertEquals(.5f, half, .001f)
        change(AgentScreen.Overview("space", "tab"))
        assertEquals("retargeting does not reset progress", half, host.progress, .001f)
        pulse(600); pulse(1600)
        assertEquals(setOf(top.value), composed)
        assertFalse(host.running)
    }

    @Test fun reducedMotionCutsAndCommitsOrCancelsInOneFrame() {
        val resolver = activity.contentResolver
        val old = android.provider.Settings.Global.getString(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE)
        android.provider.Settings.Global.putFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        try {
            show(animated = true)
            for (destination in listOf(AgentScreen.Terminal, AgentScreen.Chat, AgentScreen.Overview("space", "tab"), AgentScreen.Chat, AgentScreen.Changes)) {
                change(destination)
                assertFalse(host.running)
                assertEquals(setOf(destination), composed)
            }
            startBack(.5f)
            activity.onBackPressedDispatcher.dispatchOnBackCancelled()
            frames(1)
            assertFalse(host.running)
            assertEquals(AgentScreen.Changes, top.value)
            startBack(.5f)
            activity.onBackPressedDispatcher.onBackPressed()
            frames(1)
            assertFalse(host.running)
            assertEquals(AgentScreen.Chat, top.value)
        } finally {
            android.provider.Settings.Global.putString(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, old)
        }
    }

    @Test fun noTouchReachesTheScreenUnderneathDuringOrAfterATransition() {
        show(animated = true)
        change(AgentScreen.Changes)
        pulse(0); pulse(500)
        val before = touches.toMap()
        touch()
        assertEquals(before, touches)
        pulse(1000); pulse(2000)
        val chatBefore = touches[AgentScreen.Chat]
        touch()
        assertEquals(chatBefore, touches[AgentScreen.Chat])
        assertTrue((touches[AgentScreen.Changes] ?: 0) > 0)
    }

    // Changed with motion.md (task 029): the 0.33 release rule is gone, the system decides.
    // Was aPredictiveReleaseBelowTheThresholdCancels, which expected a press at 0.2 to cancel.
    @Test fun aShallowCommittedBackPopsOnceAndTheMotionRunsToTheEnd() {
        show(animated = true)
        change(AgentScreen.Changes)
        pulse(0); pulse(1000)
        startBack(.1f)
        activity.onBackPressedDispatcher.onBackPressed()
        frames()
        assertEquals("released from where it is", .1f, host.progress, .001f)
        assertTrue(host.running)
        assertEquals(AgentScreen.Changes, top.value)
        pulse(2000)
        assertTrue("mid-motion, not yet popped", host.progress in .1f..1f)
        pulse(3000)
        assertEquals(AgentScreen.Chat, top.value)
        assertEquals(setOf(AgentScreen.Chat), composed)
        assertFalse(host.running)
        assertEquals(0, left)
    }

    @Test fun aShallowCancelledBackStays() {
        show(animated = true)
        change(AgentScreen.Changes)
        pulse(0); pulse(1000)
        startBack(.1f)
        activity.onBackPressedDispatcher.dispatchOnBackCancelled()
        frames()
        pulse(2000); pulse(3000)
        assertEquals(AgentScreen.Changes, top.value)
        assertEquals(setOf(AgentScreen.Changes), composed)
        assertEquals(0f, host.progress, .001f)
        assertFalse(host.running)
    }

    @Test fun cancellingLeaveKeepsFocusAndKeyboardOwnershipAtEveryFrame() {
        focusOnArrival = true
        show(animated = true, innerBack = false)
        assertTrue(unmergedSemanticsNodes().any { it.focused() })
        val hidden = hide
        startBack(.5f)
        assertTrue("seeking leave retains focus", unmergedSemanticsNodes().any { it.focused() })
        activity.onBackPressedDispatcher.dispatchOnBackCancelled()
        frames(1)
        pulse(0); pulse(250)
        assertTrue("rollback retains focus", unmergedSemanticsNodes().any { it.focused() })
        pulse(1000); pulse(2000)
        assertTrue("settling rollback retains focus", unmergedSemanticsNodes().any { it.focused() })
        assertEquals(hidden, hide)
        assertEquals(0, left)
        assertEquals(setOf(AgentScreen.Chat), composed)
    }

    @Test fun animatedArrivalOwnsTouchAndBackAfterItSettles() {
        show(animated = true, innerBack = true)
        change(AgentScreen.Changes)
        pulse(0); pulse(500)
        val before = touches.toMap()
        touch()
        assertEquals(before, touches)
        pulse(1000); pulse(2000)
        touch()
        assertTrue((touches[AgentScreen.Changes] ?: 0) > 0)
        assertEquals(null, touches[AgentScreen.Chat])
        activity.onBackPressedDispatcher.onBackPressed()
        frames()
        assertEquals(null, backs[AgentScreen.Chat])
        pulse(3000); pulse(4000)
        assertEquals(AgentScreen.Chat, top.value)
        activity.onBackPressedDispatcher.onBackPressed()
        frames()
        assertEquals(1, backs[AgentScreen.Chat])
        assertEquals(0, left)
    }

    @Test fun aPredictiveReleaseHandsVelocityToTheRemainingDistance() {
        show()
        change(AgentScreen.Changes)
        pulse(0); pulse(1000)
        startBack(.5f)
        activity.onBackPressedDispatcher.dispatchOnBackProgressed(
            BackEventCompat(160f, 110f, .6f, BackEventCompat.EDGE_LEFT))
        frames()
        activity.onBackPressedDispatcher.onBackPressed()
        frames()
        assertNotNull(host.releaseAnimation)
        assertEquals(.6f, host.progress, .001f)
        assertTrue("the fixture carries a nonzero finger velocity", host.releaseVelocityDp > 0f)
        assertTrue(host.releaseDistanceDp > 0f)
        assertEquals(host.releaseVelocityDp / host.releaseDistanceDp, host.releaseAnimation!!.velocity, .001f)
        pulse(2000); pulse(3000)
        assertEquals(AgentScreen.Chat, top.value)
        assertFalse(host.running)
    }

    @Test fun aRightEdgeFlingUsesVelocityTowardBack() {
        show()
        change(AgentScreen.Changes)
        pulse(0); pulse(1000)
        activity.onBackPressedDispatcher.dispatchOnBackStarted(
            BackEventCompat(800f, 100f, 0f, BackEventCompat.EDGE_RIGHT))
        frames()
        activity.onBackPressedDispatcher.dispatchOnBackProgressed(
            BackEventCompat(780f, 100f, .05f, BackEventCompat.EDGE_RIGHT))
        frames()
        activity.onBackPressedDispatcher.dispatchOnBackProgressed(
            BackEventCompat(500f, 100f, .2f, BackEventCompat.EDGE_RIGHT))
        frames()
        activity.onBackPressedDispatcher.onBackPressed()
        frames()
        assertTrue(host.rightEdge)
        assertNotNull("a fling commits below the distance threshold", host.releaseAnimation)
        assertTrue("a fling carries its velocity into the release", host.releaseVelocityDp > 0f)
        assertEquals(host.releaseVelocityDp / host.releaseDistanceDp, host.releaseAnimation!!.velocity, .001f)
        pulse(2000); pulse(3000)
        assertEquals(AgentScreen.Chat, top.value)
    }

    private fun bounds(tag: String) = unmergedSemanticsNodes()
        .firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }?.boundsInWindow

    private fun left(tag: String) = unmergedSemanticsNodes()
        .firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }?.positionInWindow?.x

    private fun tags() = unmergedSemanticsNodes()
        .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.filter { it.startsWith("dest-") }

    private fun gesture(edge: Int, vararg progress: Float, onFrame: (Float) -> Unit) {
        val dispatcher = activity.onBackPressedDispatcher
        val x = if (edge == BackEventCompat.EDGE_RIGHT) 800f else 0f
        dispatcher.dispatchOnBackStarted(BackEventCompat(x, 100f, 0f, edge))
        frames(1)
        onFrame(0f)
        for (p in progress) {
            dispatcher.dispatchOnBackProgressed(BackEventCompat(x, 100f, p, edge))
            frames(1)
            onFrame(p)
        }
    }

    /** One frame per event: the card shows that event's progress, nothing older or newer. */
    private fun assertEveryFrameFollowsItsOwnEvent(edge: Int, outgoingTag: String, baseTag: String, baseShown: () -> Boolean) {
        val density = activity.resources.displayMetrics.density
        val width = activity.window.decorView.width.toFloat()
        val sign = if (edge == BackEventCompat.EDGE_RIGHT) -1f else 1f
        val seen = mutableListOf<Pair<Float, Float>>()
        gesture(edge, .3f, .1f, .4f, .05f, .35f) { p ->
            val card = bounds(outgoingTag)
            assertNotNull("outgoing card exists at p=$p", card)
            val scale = 1 - (1 - AgentMotion.backScale) * p
            val x = sign * AgentMotion.backShift * p * density
            assertEquals("card left at p=$p", x, left(outgoingTag)!!, 1.5f)
            assertEquals("card right at p=$p", minOf(width, x + width * scale), card!!.right, 1.5f)
            assertEquals("host progress is the event's at p=$p", p, host.progress, .001f)
            val base = bounds(baseTag)
            if (p > 0f) assertNotNull("base exists at p=$p", base)
            if (base != null) {
                val baseX = AgentMotion.predictiveBaseX(p, edge == BackEventCompat.EDGE_RIGHT, width)
                assertEquals("base sits on the swipe edge's side at p=$p", baseX, left(baseTag)!!, 1.5f)
                assertEquals(
                    "base is mirrored, positive from the right edge, at p=$p", edge == BackEventCompat.EDGE_RIGHT, baseX > 0f)
                assertEquals("base is never scaled at p=$p", minOf(width, baseX + width), base.right, 1.5f)
                val order = tags()
                assertTrue("base drawn below the card at p=$p: $order", order.indexOf(baseTag) < order.indexOf(outgoingTag))
            }
            seen += p to card.right - left(outgoingTag)!!
        }
        assertTrue(baseShown())
        // Monotone: a larger progress is never a wider card.
        for (a in seen) for (b in seen) if (a.first < b.first) assertTrue(a.second >= b.second)
    }

    @Test fun everyFrameOfAnEdgeSwipeOnChangesFollowsItsOwnEvent_left() {
        show(animated = true)
        change(AgentScreen.Changes)
        pulse(0); pulse(1000)
        assertEveryFrameFollowsItsOwnEvent(BackEventCompat.EDGE_LEFT, "dest-Changes", "dest-Chat") { true }
    }

    @Test fun everyFrameOfAnEdgeSwipeOnChangesFollowsItsOwnEvent_right() {
        show(animated = true)
        change(AgentScreen.Changes)
        pulse(0); pulse(1000)
        assertEveryFrameFollowsItsOwnEvent(BackEventCompat.EDGE_RIGHT, "dest-Changes", "dest-Chat") { true }
    }

    @Test fun everyFrameOfAnEdgeSwipeOnTheBaseFollowsItsOwnEvent_left() {
        show(animated = true)
        assertEveryFrameFollowsItsOwnEvent(BackEventCompat.EDGE_LEFT, "dest-Chat", "dest-leave") { true }
    }

    @Test fun everyFrameOfAnEdgeSwipeOnTheBaseFollowsItsOwnEvent_right() {
        show(animated = true)
        assertEveryFrameFollowsItsOwnEvent(BackEventCompat.EDGE_RIGHT, "dest-Chat", "dest-leave") { true }
    }

    @Test fun theFirstFrameAfterTheGestureStartsShowsTheOutgoingAtProgressZero_left() = firstFrames(BackEventCompat.EDGE_LEFT)
    @Test fun theFirstFrameAfterTheGestureStartsShowsTheOutgoingAtProgressZero_right() = firstFrames(BackEventCompat.EDGE_RIGHT)

    private fun firstFrames(edge: Int) {
        run {
            show(animated = true)
            change(AgentScreen.Changes)
            pulse(0); pulse(1000)
            val width = activity.window.decorView.width.toFloat()
            activity.onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, edge))
            for (frame in 1..3) {
                frames(1)
                val card = bounds("dest-Changes")
                assertNotNull("frame $frame: the outgoing card is there", card)
                assertEquals("frame $frame: the card covers the screen", 0f, left("dest-Changes")!!, 1.5f)
                assertEquals("frame $frame: at full size", width, card!!.right, 1.5f)
                assertEquals("frame $frame: the base is already on the edge's side", AgentMotion.predictiveBaseX(0f, edge == BackEventCompat.EDGE_RIGHT, width), left("dest-Chat") ?: AgentMotion.predictiveBaseX(0f, edge == BackEventCompat.EDGE_RIGHT, width), 1.5f)
                assertEquals("frame $frame: progress is 0", 0f, host.progress, .001f)
            }
            activity.onBackPressedDispatcher.dispatchOnBackCancelled()
            frames(); pulse(2000); pulse(3000)
        }
    }

    @Test fun aShallowCommitCarriesAnEdgeRelease() {
        show(animated = true)
        change(AgentScreen.Changes)
        pulse(0); pulse(1000)
        gesture(BackEventCompat.EDGE_RIGHT, .1f) {}
        activity.onBackPressedDispatcher.onBackPressed()
        frames()
        assertNotNull(host.releaseAnimation)
        pulse(2000); pulse(3000)
        assertEquals(AgentScreen.Chat, top.value)
    }

    // Task 029b: a gesture that starts while the previous one's cancel or finish still runs.
    private fun frameOf(edge: Int, p: Float, cardTag: String, why: String) {
        val density = activity.resources.displayMetrics.density
        val width = activity.window.decorView.width.toFloat()
        val sign = if (edge == BackEventCompat.EDGE_RIGHT) -1f else 1f
        val scale = 1 - (1 - AgentMotion.backScale) * p
        val x = sign * AgentMotion.backShift * p * density
        assertEquals("$why: host progress follows its event", p, host.progress, .001f)
        assertEquals("$why: card left", x, left(cardTag)!!, 1.5f)
        assertEquals("$why: card right", minOf(width, x + width * scale), bounds(cardTag)!!.right, 1.5f)
    }

    private fun reentry(edge: Int, cardTag: String, baseTag: String) {
        val dispatcher = activity.onBackPressedDispatcher
        val x = if (edge == BackEventCompat.EDGE_RIGHT) 800f else 0f
        for (settleFrames in listOf(1, 3)) {
            dispatcher.dispatchOnBackStarted(BackEventCompat(x, 100f, 0f, edge))
            frames(1)
            dispatcher.dispatchOnBackProgressed(BackEventCompat(x, 100f, .4f, edge))
            frames(1)
            frameOf(edge, .4f, cardTag, "first gesture")
            dispatcher.dispatchOnBackCancelled()
            frames(1)
            var now = clockNow + 1000
            val trace = mutableListOf(host.progress)
            pulse(now); trace += host.progress
            repeat(settleFrames) { now += 100; pulse(now); trace += host.progress }
            val midCancel = host.progress
            assertTrue("the cancel is animating back from .4 (trace $trace)", midCancel < .4f && midCancel > 0f)
            dispatcher.dispatchOnBackStarted(BackEventCompat(x, 100f, 0f, edge))
            frames(1)
            for (p in listOf(.1f, .3f, .2f)) {
                dispatcher.dispatchOnBackProgressed(BackEventCompat(x, 100f, p, edge))
                frames(1)
                frameOf(edge, p, cardTag, "re-entry after $settleFrames cancel frames, p=$p")
                val order = tags()
                assertTrue("base below the card", order.indexOf(baseTag) < order.indexOf(cardTag))
            }
            dispatcher.dispatchOnBackCancelled()
            frames(1)
            now += 100; pulse(now); now += 2000; pulse(now); now += 2000; pulse(now)
            assertEquals("the cancel returns to full screen", 0f, host.progress, .001f)
            assertEquals(0f, left(cardTag)!!, 1.5f)
            assertFalse(host.running)
            clockNow = now
        }
    }
    private var clockNow = 1000L

    @Test fun aGestureAfterACancelStillRunningFollowsItsOwnEvents_changes_left() {
        show(animated = true); change(AgentScreen.Changes); pulse(0); pulse(1000)
        reentry(BackEventCompat.EDGE_LEFT, "dest-Changes", "dest-Chat")
        assertEquals(AgentScreen.Changes, top.value)
    }
    @Test fun aGestureAfterACancelStillRunningFollowsItsOwnEvents_changes_right() {
        show(animated = true); change(AgentScreen.Changes); pulse(0); pulse(1000)
        reentry(BackEventCompat.EDGE_RIGHT, "dest-Changes", "dest-Chat")
    }
    @Test fun aGestureAfterACancelStillRunningFollowsItsOwnEvents_leave_left() {
        show(animated = true)
        reentry(BackEventCompat.EDGE_LEFT, "dest-Chat", "dest-leave")
        assertEquals(0, left)
    }
    @Test fun aGestureAfterACancelStillRunningFollowsItsOwnEvents_leave_right() {
        show(animated = true)
        reentry(BackEventCompat.EDGE_RIGHT, "dest-Chat", "dest-leave")
    }

    private fun duringTheFinish(edge: Int, startOnChanges: Boolean) {
        if (startOnChanges) { change(AgentScreen.Changes); pulse(0); pulse(1000) }
        val dispatcher = activity.onBackPressedDispatcher
        val x = if (edge == BackEventCompat.EDGE_RIGHT) 800f else 0f
        dispatcher.dispatchOnBackStarted(BackEventCompat(x, 100f, 0f, edge))
        frames(1)
        dispatcher.dispatchOnBackProgressed(BackEventCompat(x, 100f, .4f, edge))
        frames(1)
        dispatcher.onBackPressed()
        frames(1)
        pulse(2000); pulse(2200)
        val mid = host.progress
        pulse(2300)
        assertTrue("the finish only moves toward the end", host.progress >= mid && mid > .4f)
        dispatcher.dispatchOnBackStarted(BackEventCompat(x, 100f, 0f, edge))
        frames(1)
        dispatcher.dispatchOnBackProgressed(BackEventCompat(x, 100f, .1f, edge))
        frames(1)
        dispatcher.dispatchOnBackCancelled()
        frames(1)
        pulse(2400); pulse(5000); pulse(8000)
        assertFalse(host.running)
        assertEquals("popped exactly once", if (startOnChanges) AgentScreen.Chat else AgentScreen.Chat, top.value)
        assertEquals(setOf(AgentScreen.Chat), composed)
    }
    @Test fun aGestureDuringACommittedFinishNeitherReversesItNorPopsTwice_left() {
        show(animated = true); duringTheFinish(BackEventCompat.EDGE_LEFT, true); assertEquals(0, left)
    }
    @Test fun aGestureDuringACommittedFinishNeitherReversesItNorPopsTwice_right() {
        show(animated = true); duringTheFinish(BackEventCompat.EDGE_RIGHT, true); assertEquals(0, left)
    }

    private fun fastRelease(edge: Int, cardTag: String) {
        val dispatcher = activity.onBackPressedDispatcher
        val sign = if (edge == BackEventCompat.EDGE_RIGHT) -1f else 1f
        val x0 = if (edge == BackEventCompat.EDGE_RIGHT) 800f else 0f
        dispatcher.dispatchOnBackStarted(BackEventCompat(x0, 100f, 0f, edge))
        frames()
        dispatcher.dispatchOnBackProgressed(BackEventCompat(x0 + sign * 100f, 100f, .1f, edge))
        frames()
        dispatcher.dispatchOnBackProgressed(BackEventCompat(x0 + sign * 700f, 100f, .5f, edge))
        frames()
        dispatcher.onBackPressed()
        frames()
        assertTrue("the release carries a fast velocity toward Back", host.releaseVelocityDp > 0f)
        var last = host.progress
        var now = 2000L
        pulse(now)
        repeat(40) {
            now += 16; pulse(now)
            val p = host.progress
            assertTrue("progress only moves to the end: $last -> $p", p >= last - 1e-4f)
            val cardLeft = left(cardTag)
            if (cardLeft != null) assertTrue("the card never comes back in: $cardLeft", sign * cardLeft >= -1f)
            last = p
        }
        pulse(now + 3000)
        assertFalse(host.running)
    }
    @Test fun aFastCommittedReleaseOnlyMovesTowardTheEnd_changes_left() {
        show(animated = true); change(AgentScreen.Changes); pulse(0); pulse(1000)
        fastRelease(BackEventCompat.EDGE_LEFT, "dest-Changes")
    }
    @Test fun aFastCommittedReleaseOnlyMovesTowardTheEnd_changes_right() {
        show(animated = true); change(AgentScreen.Changes); pulse(0); pulse(1000)
        fastRelease(BackEventCompat.EDGE_RIGHT, "dest-Changes")
    }

    /** A stopped animation clock; only explicit pulses move animation time. */
    private class TestClock : MonotonicFrameClock {
        private val waiting = mutableListOf<CancellableContinuation<Long>>()
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            val nanos = suspendCancellableCoroutine<Long> { continuation ->
                waiting += continuation
                continuation.invokeOnCancellation { waiting -= continuation }
            }
            return onFrame(nanos)
        }
        fun pulse(ms: Long) {
            val pending = waiting.toList()
            waiting.clear()
            pending.forEach { if (it.isActive) it.resume(ms * 1_000_000L) }
        }
    }

}
