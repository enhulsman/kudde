package dev.kudde.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kudde.android.ui.theme.KuddeBorder
import dev.kudde.android.ui.theme.KuddeRadius
import dev.kudde.android.ui.theme.KuddeScrim
import dev.kudde.android.ui.theme.KuddeSpacing
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.KuddeType

// The dialog of render 8 (.scrim, .dialog, .fp, .btn in design/renders/screens.html;
// components.hostKeyDialog and components.button in design-tokens.json), drawn in
// the screen's own composition so screenshots catch it and system back reaches
// the flow that owns it.

/** Render 8's dialog top: 200 px from the phone's top, less the 36 px system bar. */
// CSS spreads line-height above and below every line; Compose trims it off the
// first and last line unless told not to.
private val CssLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private val DialogTop = 164.dp

/**
 * A scrim over the whole screen and the dialog card on it. Tapping the scrim
 * is [onDismiss] (the same as the dialog's Cancel).
 */
@Composable
fun ModalDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.dialog)
    Box(
        Modifier
            .fillMaxSize()
            .background(KuddeScrim)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        Column(
            Modifier
                .safeDrawingPadding()
                .padding(start = KuddeSpacing.s20, end = KuddeSpacing.s20, top = DialogTop, bottom = KuddeSpacing.s20)
                .fillMaxWidth()
                .clip(shape)
                .background(c.popover)
                .border(KuddeBorder.thin, c.border, shape)
                // A CSS border takes room; a Compose border is drawn over the content.
                .padding(KuddeBorder.thin)
                // Taps inside the card are not taps on the scrim.
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                .semantics { paneTitle = title }
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = KuddeSpacing.s24, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(KuddeSpacing.s14),
        ) {
            BasicText(title, style = KuddeType.dialogTitle.copy(color = c.fg))
            content()
        }
    }
}

/** A dialog paragraph: 15 sp muted (`.dialog p`). */
@Composable
fun DialogText(text: AnnotatedString, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = KuddeType.body.copy(color = KuddeTheme.colors.muted, lineHeightStyle = CssLines))
}

@Composable
fun DialogText(text: String, modifier: Modifier = Modifier) = DialogText(AnnotatedString(text), modifier)

/** The small note under the fingerprint: 13 sp muted. */
@Composable
fun DialogNote(text: String) {
    BasicText(
        text,
        style = KuddeType.body.copy(fontSize = 13.sp, color = KuddeTheme.colors.muted, lineHeightStyle = CssLines),
    )
}

/** A line saying why the last action failed, in the tokens' error colour (error = blocked). */
@Composable
fun ErrorText(text: String, modifier: Modifier = Modifier) {
    BasicText(text, modifier, style = KuddeType.secondary.copy(color = KuddeTheme.colors.blocked))
}

/**
 * The fingerprint box (`.fp`): mono 13.5 on the canvas colour, one line per
 * entry, long ones wrapped. [lines] are e.g. the key type, then `SHA256:...`.
 */
@Composable
fun MonoBox(lines: List<String>, modifier: Modifier = Modifier) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.field)
    BasicText(
        lines.joinToString("\n"),
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.canvas)
            .border(KuddeBorder.thin, c.border, shape)
            .padding(KuddeBorder.thin)
            .padding(horizontal = KuddeSpacing.s14, vertical = KuddeSpacing.s12),
        style = KuddeType.fingerprint.copy(color = c.fg, lineHeightStyle = CssLines),
    )
}

/**
 * The dialog's buttons, right-aligned 10 dp apart (`margin-top: 4px` on top of the 14 dp gap) while they
 * fit side by side. When they do not (large system font), they stack at full width, the last one (the
 * primary) on top, and a label wraps instead of being cut off.
 */
@Composable
fun DialogButtons(content: @Composable () -> Unit) {
    Layout(
        content = content,
        modifier = Modifier.fillMaxWidth().padding(top = KuddeSpacing.s4),
    ) { measurables, constraints ->
        val gap = KuddeSpacing.s10.roundToPx()
        val room = constraints.maxWidth
        val natural = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val sideBySide = natural.sum() + gap * (measurables.size - 1).coerceAtLeast(0) <= room
        if (sideBySide) {
            val placeables = measurables.map { it.measure(Constraints()) }
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(room, height) {
                var x = room
                for (p in placeables.asReversed()) {
                    x -= p.width
                    p.placeRelative(x, 0)
                    x -= gap
                }
            }
        } else {
            val placeables = measurables.asReversed().map { it.measure(Constraints(minWidth = room, maxWidth = room)) }
            val height = placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)
            layout(room, height) {
                var y = 0
                for (p in placeables) {
                    p.placeRelative(0, y)
                    y += p.height + gap
                }
            }
        }
    }
}

/**
 * A `.btn` sized to its label: outlined, or [solid] (solid background,
 * solidFg, 500), 44 dp high, radius 12.
 */
@Composable
fun DialogButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, solid: Boolean = false) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.button)
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .then(if (solid) Modifier.background(c.solid) else Modifier.border(KuddeBorder.thin, c.input, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        BasicText(
            label,
            style = KuddeType.body.copy(
                color = if (solid) c.solidFg else c.fg,
                fontSize = 15.sp,
                fontWeight = if (solid) FontWeight.W500 else FontWeight.W400,
                lineHeight = KuddeType.settingRow.lineHeight,
            ),
        )
    }
}
