package dev.kudde.android.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/** A window covering the screen; [popupPositionProvider] positions only its panel. */
@Composable
fun ModalPopup(
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: () -> Unit,
    shadowRoom: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    // The anchor is in the activity's window; the popup's window may start lower (below
    // the status bar on a phone), so the layer converts through screen positions.
    val parentView = LocalView.current
    // Compose supplies the real anchor in window coordinates. Keep the window at
    // the origin so even the space far from the panel belongs to this popup.
    val windowPosition = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ): IntOffset {
                anchor = anchorBounds
                return IntOffset.Zero
            }
        }
    }
    Popup(
        popupPositionProvider = windowPosition,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, usePlatformDefaultWidth = false),
    ) {
        ModalPopupLayer(anchor, popupPositionProvider, onDismissRequest, shadowRoom,
            parentWindowOnScreen = { parentView.rootView.screenOrigin() }, content = content)
    }
}

private class PanelBounds {
    var rect = Rect.Zero
}

/**
 * The window-independent modal surface (screen-stack D0/D6).
 * [anchorBounds] is in window coordinates. [shadowRoom] reserves the same space
 * passed to the old position rule, but belongs to the outside catcher, not the
 * panel. An outside gesture is swallowed until its last pointer lifts, then
 * dismisses once; an inside gesture stays with the panel even if it leaves it.
 */
@Composable
fun ModalPopupLayer(
    anchorBounds: IntRect,
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: () -> Unit,
    shadowRoom: Dp = 0.dp,
    /** Where the window [anchorBounds] is measured in starts on screen; default: this layer's own window. */
    parentWindowOnScreen: (() -> Offset)? = null,
    content: @Composable () -> Unit,
) {
    val dismiss by rememberUpdatedState(onDismissRequest)
    val ownView = LocalView.current
    val panel = remember { PanelBounds() }
    val focus = remember { FocusRequester() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    BackHandler { dismiss() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Layout(
        // Clipped to the panel plus its shadow room, as the old popup window was: the
        // drop shadow ends at the same edge, so the menu looks the same.
        content = { Box(Modifier.clipToBounds().padding(shadowRoom)) { content() } },
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                val parent = parentWindowOnScreen?.invoke() ?: ownView.rootView.screenOrigin()
                origin = it.positionOnScreen() - parent
            }
            .focusRequester(focus)
            .onPreviewKeyEvent {
                if (it.key == Key.Back) {
                    if (it.type == KeyEventType.KeyUp) dismiss()
                    true
                } else false
            }
            .focusable()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val outside = !panel.rect.contains(down.position)
                    do {
                        // Consume an outside gesture before descendants can act.
                        // Inside events stay untouched: consuming even at Final
                        // would cancel a panel control's click/drag recognizer.
                        // This full-size hit target still excludes siblings below.
                        val event = currentEvent
                        if (outside) event.changes.forEach { it.consume() }
                        val pressed = event.changes.any { it.pressed }
                        if (pressed) awaitPointerEvent(PointerEventPass.Initial)
                    } while (pressed)
                    if (outside) dismiss()
                }
            },
    ) { measurables, constraints ->
        val room = shadowRoom.roundToPx()
        val placeable = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val offset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt())
        val localAnchor = IntRect(
            anchorBounds.left - offset.x, anchorBounds.top - offset.y,
            anchorBounds.right - offset.x, anchorBounds.bottom - offset.y,
        )
        val position = popupPositionProvider.calculatePosition(
            localAnchor, IntSize(width, height), layoutDirection,
            IntSize(placeable.width, placeable.height),
        )
        val x = position.x + room
        val y = position.y + room
        panel.rect = Rect(x.toFloat(), y.toFloat(), (x + placeable.width - room * 2).toFloat(), (y + placeable.height - room * 2).toFloat())
        layout(width, height) { placeable.place(position.x, position.y) }
    }
}

private fun android.view.View.screenOrigin(): Offset {
    val at = IntArray(2)
    getLocationOnScreen(at)
    return Offset(at[0].toFloat(), at[1].toFloat())
}
