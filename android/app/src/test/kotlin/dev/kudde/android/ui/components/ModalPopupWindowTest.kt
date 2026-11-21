package dev.kudde.android.ui.components

import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import dev.kudde.android.testing.BoundedFrames
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.testing.PopupSafeClock
import dev.kudde.android.testing.composeRoots
import dev.kudde.android.testing.texts
import dev.kudde.android.testing.windowRoots
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class ModalPopupWindowTest {
    @Suppress("unused")
    private val clock = PopupSafeClock.paused()
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()

    @After
    fun close() {
        controller.pause().stop().destroy()
        BoundedFrames.settle(minFrames = 3)
    }

    @Test
    fun aWindowOpensWithTheLayerInIt() {
        // Windows other than the activity's own: its decor attaches on the first frame, after
        // setContent, so a count taken before would include it.
        fun popups() = windowRoots().filter { it !== controller.get().window.decorView }
        assertEquals("no popup before", 0, popups().size)
        var positionedSize = IntSize.Zero
        val position = object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ): IntOffset {
                positionedSize = popupContentSize
                return IntOffset(32, 48)
            }
        }
        controller.get().setContent {
            Box(Modifier.size(48.dp)) {
                ModalPopup(popupPositionProvider = position, onDismissRequest = {}) {
                    BasicText("panel sentinel")
                }
            }
        }
        BoundedFrames.step(6)
        assertEquals("a separate popup window opens", 1, popups().size)
        val popup = popups().single()
        val nodes = mutableListOf<androidx.compose.ui.semantics.SemanticsNode>()
        fun collect(node: androidx.compose.ui.semantics.SemanticsNode) {
            nodes += node
            node.children.forEach(::collect)
        }
        composeRoots(popup).forEach { collect(it.semanticsOwner.rootSemanticsNode) }
        assertTrue("the panel is in the popup window", nodes.any { it.texts() == listOf("panel sentinel") })
        val panel = nodes.single { it.texts() == listOf("panel sentinel") }
        assertEquals(32f, panel.boundsInWindow.left, .5f)
        assertEquals(48f, panel.boundsInWindow.top, .5f)
        assertTrue("the layer measures the panel, not the whole window", positionedSize.width > 0 && positionedSize.width < popup.width)
        val decor = controller.get().findViewById<android.view.View>(android.R.id.content)
        assertTrue("the catcher spans the activity width", popup.width >= decor.width)
        assertTrue("the catcher spans the activity height", popup.height >= decor.height)
    }

    private class Touches {
        var panel = Rect.Zero
        var dismissed = 0
        var chosen = 0
        var underneath = 0
    }

    private fun showLayer(): Touches {
        val result = Touches()
        val open = mutableStateOf(true)
        val position = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset(32, 48)
        }
        controller.get().setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent()
                            result.underneath++
                        }
                    }
                })
                if (open.value) ModalPopupLayer(
                    anchorBounds = IntRect.Zero, popupPositionProvider = position, shadowRoom = 24.dp,
                    onDismissRequest = { result.dismissed++; open.value = false },
                ) {
                    Box(Modifier.size(100.dp, 80.dp)
                        .onGloballyPositioned { result.panel = it.boundsInWindow() }
                        .clickable { result.chosen++ })
                }
            }
        }
        BoundedFrames.step(4)
        assertTrue("the panel is measured", result.panel.width > 0)
        return result
    }

    private fun gesture(vararg points: Pair<Int, androidx.compose.ui.geometry.Offset>) {
        val down = SystemClock.uptimeMillis()
        points.forEachIndexed { index, (action, point) ->
            val event = MotionEvent.obtain(down, down + index * 16L, action, point.x, point.y, 0)
            try { controller.get().window.decorView.dispatchTouchEvent(event) } finally { event.recycle() }
            BoundedFrames.step(2)
        }
    }

    @Test
    fun aTapInTheShadowRoomClosesWithoutReachingBelow() {
        val touches = showLayer()
        val outside = androidx.compose.ui.geometry.Offset(touches.panel.left - 2f, touches.panel.center.y)
        gesture(MotionEvent.ACTION_DOWN to outside, MotionEvent.ACTION_UP to outside)
        assertEquals(1, touches.dismissed)
        assertEquals(0, touches.chosen)
        assertEquals(0, touches.underneath)
    }

    @Test
    fun aDragStartingOutsideAndEndingInsideOnlyCloses() {
        val touches = showLayer()
        val outside = androidx.compose.ui.geometry.Offset(2f, 2f)
        gesture(MotionEvent.ACTION_DOWN to outside, MotionEvent.ACTION_MOVE to touches.panel.center,
            MotionEvent.ACTION_UP to touches.panel.center)
        assertEquals(1, touches.dismissed)
        assertEquals(0, touches.chosen)
        assertEquals(0, touches.underneath)
    }

    @Test
    fun aDragStartingInsideAndEndingOutsideBelongsToThePanel() {
        val touches = showLayer()
        val outside = androidx.compose.ui.geometry.Offset(2f, 2f)
        gesture(MotionEvent.ACTION_DOWN to touches.panel.center, MotionEvent.ACTION_MOVE to outside,
            MotionEvent.ACTION_UP to outside)
        assertEquals(0, touches.dismissed)
        assertEquals(0, touches.chosen)
        assertEquals(0, touches.underneath)
        gesture(MotionEvent.ACTION_DOWN to touches.panel.center, MotionEvent.ACTION_UP to touches.panel.center)
        assertEquals("panel controls still receive taps", 1, touches.chosen)
        assertEquals(0, touches.dismissed)
        assertEquals(0, touches.underneath)
    }

}
