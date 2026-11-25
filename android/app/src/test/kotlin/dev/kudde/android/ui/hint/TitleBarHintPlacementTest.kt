package dev.kudde.android.ui.hint

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// design D11 "Where": the hint never covers the cursor row. It sits at the top of the screen's area
// unless the cursor row falls under it, and then it sits just above the keys.
class TitleBarHintPlacementTest {
    @Test fun aCursorFarBelowLeavesItAtTheTop() {
        assertTrue(hintAtTop(cursorTop = 400f, cursorBottom = 424f, hintBottom = 200f))
    }

    @Test fun aPromptInTheTopRowMovesItDown() {
        assertFalse(hintAtTop(cursorTop = 6f, cursorBottom = 30f, hintBottom = 200f))
    }

    @Test fun aCursorJustUnderItsEdgeStillCountsAsUnder() {
        assertFalse(hintAtTop(cursorTop = 190f, cursorBottom = 214f, hintBottom = 200f))
    }

    @Test fun aCursorExactlyAtItsBottomEdgeIsClear() {
        assertTrue(hintAtTop(cursorTop = 200f, cursorBottom = 224f, hintBottom = 200f))
    }

    @Test fun noCursorMeansNothingToCover() {
        assertTrue(hintAtTop(cursorTop = null, cursorBottom = null, hintBottom = 200f))
    }
}
