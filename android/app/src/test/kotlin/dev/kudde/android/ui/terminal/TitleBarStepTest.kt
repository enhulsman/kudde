package dev.kudde.android.ui.terminal

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The title bar's drag decides one of three outcomes (design D4): a step, an
// end (a bounce only sideways, with animations on), or a refusal while the
// compose sheet holds text, decided before either walk is asked.
class TitleBarStepTest {
    private val threshold = 100f
    private val calls = mutableListOf<String>()

    private fun step(dx: Float, dy: Float, moves: Boolean, refused: Boolean = false, animationsOn: Boolean = true) = titleBarStep(
        dx, dy, threshold, refused, animationsOn,
        onTab = { calls += if (it) "next" else "previous"; moves },
        onSwipe = { calls += if (it) "up" else "down"; moves },
    )

    @Test
    fun aSidewaysDragPastTheThresholdThatMovesIsMoved() {
        assertEquals(TitleBarStep.Moved, step(dx = -150f, dy = 20f, moves = true))
        assertEquals(TitleBarStep.Moved, step(dx = 150f, dy = -20f, moves = true))
        assertEquals("finger left: next; finger right: previous", listOf("next", "previous"), calls)
    }

    @Test
    fun anUpOrDownDragThatMovesIsMoved() {
        assertEquals(TitleBarStep.Moved, step(dx = 10f, dy = -150f, moves = true))
        assertEquals(TitleBarStep.Moved, step(dx = 10f, dy = 150f, moves = true))
        assertEquals(listOf("up", "down"), calls)
    }

    @Test
    fun aSidewaysEndBouncesInTheSwipesDirection() {
        assertEquals(TitleBarStep.End(Bounce.LEFT), step(dx = -150f, dy = 0f, moves = false))
        assertEquals(TitleBarStep.End(Bounce.RIGHT), step(dx = 150f, dy = 0f, moves = false))
        assertEquals(listOf("next", "previous"), calls)
    }

    @Test
    fun aSidewaysEndWithAnimationsOffHasNoBounce() {
        assertEquals(TitleBarStep.End(null), step(dx = -150f, dy = 0f, moves = false, animationsOn = false))
        assertEquals(TitleBarStep.End(null), step(dx = 150f, dy = 0f, moves = false, animationsOn = false))
    }

    @Test
    fun anUpOrDownEndHasNoBounce() {
        assertEquals(TitleBarStep.End(null), step(dx = 0f, dy = -150f, moves = false))
        assertEquals(TitleBarStep.End(null), step(dx = 0f, dy = 150f, moves = false))
        assertEquals(listOf("up", "down"), calls)
    }

    @Test
    fun aRefusedDragInEachDirectionCallsNeitherWalk() {
        for ((dx, dy) in listOf(-150f to 0f, 150f to 0f, 0f to -150f, 0f to 150f)) {
            assertEquals("($dx, $dy)", TitleBarStep.Refused, step(dx, dy, moves = true, refused = true))
        }
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun aDragShorterThanTheThresholdIsNothing() {
        assertNull(step(dx = -60f, dy = 10f, moves = true))
        assertNull(step(dx = 10f, dy = 99f, moves = true))
        assertNull(step(dx = 0f, dy = 0f, moves = true))
        assertNull("no haptic for a short drag, refused or not", step(dx = -60f, dy = 0f, moves = true, refused = true))
        assertEquals(emptyList<String>(), calls)
    }

    // Owner, 2026-11-22: the light tick was almost unnoticeable on a Pixel 10, so a
    // step taps like a key; an end or a refusal keeps the reject buzz.
    @Test
    fun aStepTapsLikeAKeyAndAnEndOrRefusalRejects() {
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, titleBarHaptic(TitleBarStep.Moved))
        for (step in listOf(TitleBarStep.End(Bounce.LEFT), TitleBarStep.End(null), TitleBarStep.Refused)) {
            assertEquals("$step", HapticFeedbackConstants.REJECT, titleBarHaptic(step))
        }
    }
}
