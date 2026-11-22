package dev.kudde.android.ui.terminal

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import dev.kudde.android.ui.agent.AgentMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import dev.kudde.android.R
import dev.kudde.android.ui.components.ModalPopup
import dev.kudde.android.ui.theme.KuddeBorder
import dev.kudde.android.ui.theme.KuddeChipShadow
import dev.kudde.android.ui.theme.KuddeRadius
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.KuddeType

// The quieter title bar's own controls (spaces-navigation, decisions 7, 8 and
// 10; design D10; render terminal-map, components.titleBarMenu,
// viewSwitchIcon and tabMap in design-tokens.json): the overflow menu, the
// view switch as an icon, the tab's map as a button, and where the bar gives
// way when it is too narrow.

/** One option of the title bar's menu: shown and spoken as [label]. */
class TitleBarMenuItem(val label: String, val icon: LineIcon?, val onClick: () -> Unit)

/** Whether the title bar's menu is open; the screen holds it, so a test can read it without a frame. */
class TitleBarMenuState(open: Boolean = false) {
    var open by mutableStateOf(open)
}

/** The space left between the menu and its window's edge, so its shadow is drawn whole. */
private val ShadowRoom = 24.dp

/**
 * `.qbtn` and `.qmenu`: a 48 dp three-dot button, last in the bar, spoken
 * "More options"; a tap opens the menu under it at the right, in its own
 * window (a Compose Popup, focusable, so a tap outside and Back close it and
 * reach nothing else). Choosing an item closes the menu at once (no exit motion), then runs it; a tap
 * outside or Back closes it with the exit motion.
 */
@Composable
fun TitleBarMenu(items: List<TitleBarMenuItem>, state: TitleBarMenuState = remember { TitleBarMenuState() }) {
    val c = KuddeTheme.colors
    var open by state::open
    var present by remember { mutableStateOf(open) }
    val motion = remember { Animatable(if (open) 1f else 0f) }
    val resolver = LocalContext.current.contentResolver
    LaunchedEffect(open) {
        val reduced = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (open) {
            present = true
            if (reduced) motion.snapTo(1f) else motion.animateTo(1f, AgentMotion.springs.getValue("fastSpatial"))
        } else {
            // A chosen item has already dropped the popup: nothing to animate.
            if (reduced || !present) motion.snapTo(0f) else motion.animateTo(0f, AgentMotion.springs.getValue("fastEffects"))
            present = false
        }
    }
    val label = stringResource(R.string.title_bar_more)
    val shape = RoundedCornerShape(KuddeRadius.button)
    Box {
        Box(
            Modifier
                .size(48.dp)
                .clip(shape)
                .background(if (open) c.selected else Color.Transparent)
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            LineIconImage(LineIcon.More, if (open) c.fg else c.muted, 22.dp)
        }
        if (open || present) {
            val density = LocalDensity.current
            val position = remember(density) { MenuPosition(density) }
            ModalPopup(popupPositionProvider = position, onDismissRequest = { open = false }, shadowRoom = ShadowRoom,
                motionProgress = if (open) motion.value else 1 - motion.value, opening = open) {
                TitleBarMenuPanel(items, progress = if (open) motion.value else 1f) { item ->
                    if (open) {
                        // Drop the focusable popup with the choice: its exit would eat a Back at once.
                        present = false
                        open = false
                        item.onClick()
                    }
                }
            }
        }
    }
}

/** The menu itself (`.qmenu`, `.qitem`): popover, a 1 dp border, 12 corners, the chip's shadow, at least 184 dp wide. */
@Composable
internal fun TitleBarMenuPanel(items: List<TitleBarMenuItem>, progress: Float = 1f, onChoose: (TitleBarMenuItem) -> Unit) {
    val density = LocalDensity.current
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.button)
    Column(
        Modifier
            .widthIn(min = 184.dp)
            .width(androidx.compose.foundation.layout.IntrinsicSize.Max)
            .dropShadow(shape, KuddeChipShadow)
            .clip(shape)
            .background(c.popover)
            .border(KuddeBorder.thin, c.border, shape)
            .padding(vertical = 4.dp),
    ) {
        for ((index, item) in items.withIndex()) {
            Row(
                Modifier
                    .graphicsLayer {
                        val frame = AgentMotion.menuItem(progress, index)
                        alpha = frame.alpha
                        translationY = frame.y * density.density
                    }
                    .widthIn(min = 184.dp)
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { onChoose(item) }
                    .semantics(mergeDescendants = true) {}
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item.icon?.let { LineIconImage(it, c.muted, 20.dp) }
                BasicText(item.label, style = KuddeType.rowName.copy(color = c.fg), maxLines = 1)
            }
        }
    }
}

/** The menu's right edge 4 dp in from the button's, its top 6 dp under it (`.qmenu`: right 8, top 62 in the bar). */
internal class MenuPosition(private val density: Density) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        with(density) {
            val room = ShadowRoom.roundToPx()
            val x = anchorBounds.right - 4.dp.roundToPx() + room - popupContentSize.width
            val y = anchorBounds.bottom + 6.dp.roundToPx() - room
            return IntOffset(x.coerceIn(-room, (windowSize.width - popupContentSize.width + room).coerceAtLeast(-room)), y)
        }
    }
}

/** viewSwitchIcon: a 48 dp icon button, the chat's icon in the terminal ([toChat]) and the terminal's in the chat; spoken as the word. */
@Composable
fun ViewSwitchIcon(toChat: Boolean, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    val label = stringResource(if (toChat) R.string.view_switch_chat else R.string.view_switch_terminal)
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(KuddeRadius.button))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        LineIconImage(if (toChat) LineIcon.Chat else LineIcon.TerminalBox, c.muted, 22.dp)
    }
}

/** What the title bar shows of the parts that may give way: the gauge, and the width the name gets. */
data class TitleFit(val gauge: Boolean, val nameWidth: Int)

/**
 * The give-way rule (decision 10; design D10, "The fit"). [room] is the width
 * left for the name and the gauge once the fixed controls (Back, the mark, the
 * map, the switch and the menu button, which never hide) are measured;
 * [gauge] the gauge's width with its gap, null without one; [eightChars] the
 * width of the name's first eight characters and the ellipsis (or the whole
 * name, if shorter); [floor] its first character and the ellipsis. The gauge
 * shows while the name keeps its eight characters beside it; otherwise it
 * hides first, and only then does the name give way below eight characters,
 * never below [floor].
 */
fun titleBarFit(room: Int, gauge: Int?, eightChars: Int, floor: Int): TitleFit =
    if (gauge != null && room - gauge >= eightChars) {
        TitleFit(gauge = true, nameWidth = room - gauge)
    } else {
        TitleFit(gauge = false, nameWidth = maxOf(room, floor))
    }
