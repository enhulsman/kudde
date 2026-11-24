package dev.kudde.android.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.kudde.android.ui.terminal.LineIcon
import dev.kudde.android.ui.terminal.LineIconImage
import dev.kudde.android.ui.theme.KuddeBorder
import dev.kudde.android.ui.theme.KuddeRadius
import dev.kudde.android.ui.theme.KuddeScrim
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.KuddeType
import uniffi.kudde_mobile.ChatOption

// How the question card looks, all in this file (direction A, picked
// 2026-11-25): one raised panel, flat option rows split by hairlines, numbers
// in small keycaps, and a footer with Something else on the left and Skip
// (and the send arrow on multi-select) on the right. Colours, radii and type
// are the theme's tokens only. ChatQuestion.kt decides what shows and how it
// reads.

/** Where the hairline between options starts: the row's start padding, the keycap and the gap. */
private val HAIRLINE_START = 58.dp
private val ROW_START = 16.dp
private val ROW_END = 18.dp
private val TEXT_START = 18.dp

/** The panel: popover in graphite, canvas in paper, under a 1 dp border; the body scrolls, the footer stays. */
@Composable
internal fun CardSurface(modifier: Modifier, body: @Composable ColumnScope.() -> Unit, footer: @Composable () -> Unit) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.machineCard)
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp)
            .clip(shape)
            .background(if (c.isDark) c.popover else c.canvas)
            .border(KuddeBorder.thin, c.border, shape),
    ) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 16.dp), content = body)
        footer()
    }
}

@Composable
internal fun StepLine(text: String) {
    BasicText(text, style = KuddeType.secondary.copy(color = KuddeTheme.colors.dim), modifier = Modifier.padding(start = TEXT_START, end = TEXT_START, bottom = 6.dp))
}

@Composable
internal fun QuestionTitle(text: String) {
    BasicText(
        text,
        style = KuddeType.subScreenTitle.copy(color = KuddeTheme.colors.fg, fontWeight = FontWeight.Medium, lineHeight = 27.sp, letterSpacing = (-0.01).em),
        modifier = Modifier.padding(start = TEXT_START, end = TEXT_START, bottom = 12.dp),
    )
}

@Composable
internal fun Hint(text: String) {
    BasicText(text, style = KuddeType.secondary.copy(color = KuddeTheme.colors.muted), modifier = Modifier.offset(y = (-6).dp).padding(start = TEXT_START, end = TEXT_START, bottom = 4.dp))
}

/** Why nothing was sent: a quiet strip with a hollow ring, and the way to the terminal where it helps. */
@Composable
internal fun Notice(text: String, onTerminal: (() -> Unit)?) {
    val c = KuddeTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp).heightIn(min = 36.dp)
            .clip(RoundedCornerShape(KuddeRadius.notice)).background(c.hover).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(9.dp).border(1.5.dp, c.idle, CircleShape))
        BasicText(text, style = KuddeType.secondary.copy(color = c.fg), modifier = Modifier.weight(1f))
        if (onTerminal != null) {
            BasicText(
                SHOW_TERMINAL,
                style = KuddeType.pill.copy(color = c.fg),
                modifier = Modifier.clip(RoundedCornerShape(KuddeRadius.key)).clickable(role = Role.Button, onClick = onTerminal).padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * One option: a flat row, a hairline above all but the first, a light wash
 * when chosen. [click] carries the row's tap and its one spoken stop; the
 * [trailing] button (Preview) stays outside it, a stop of its own.
 */
@Composable
internal fun OptionRow(
    first: Boolean,
    chosen: Boolean,
    lead: @Composable () -> Unit,
    label: String,
    description: String,
    click: Modifier,
    trailing: (@Composable () -> Unit)?,
) {
    val c = KuddeTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .background(if (chosen) c.hover else Color.Transparent)
            .drawBehind { if (!first) hairline(c.border) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).then(click)
                .padding(start = ROW_START, end = if (trailing == null) ROW_END else 8.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            lead()
            OptionBody(label, description)
        }
        if (trailing != null) Box(Modifier.padding(end = 12.dp)) { trailing() }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.hairline(color: Color) {
    val y = 0.5.dp.toPx()
    drawLine(color, Offset(HAIRLINE_START.toPx(), y), Offset(size.width - ROW_END.toPx(), y), KuddeBorder.thin.toPx())
}

@Composable
private fun OptionBody(label: String, description: String) {
    val c = KuddeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(label, style = KuddeType.rowName.copy(color = c.fg, lineHeight = 21.sp))
        if (description.isNotBlank()) BasicText(description, style = KuddeType.secondary.copy(color = c.muted, lineHeight = 19.sp))
    }
}

/** The option's number in a small keycap: "1", no dot. */
@Composable
internal fun Keycap(n: Int) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.key)
    Box(
        Modifier.offset(y = (-2).dp).size(28.dp).clip(shape)
            .background(if (c.isDark) c.selected else c.popover)
            .then(if (c.isDark) Modifier else Modifier.border(KuddeBorder.thin, c.border, shape)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText("$n", style = KuddeType.keysRow.copy(color = c.muted))
    }
}

/** A keycap's outline holding an icon (the pen of Something else). */
@Composable
private fun OutlineKey(icon: LineIcon) {
    val c = KuddeTheme.colors
    Box(Modifier.size(28.dp).border(KuddeBorder.thin, c.input, RoundedCornerShape(KuddeRadius.key)), contentAlignment = Alignment.Center) {
        LineIconImage(icon, c.muted, 14.dp)
    }
}

/** A multi-select tick box: outlined, filled solid with a check when ticked. */
@Composable
internal fun CheckBox(on: Boolean) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.pill)
    Box(
        Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp).size(20.dp).clip(shape)
            .then(if (on) Modifier.background(c.solid) else Modifier.border(KuddeBorder.thin, c.muted, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (on) {
            Canvas(Modifier.size(14.dp)) {
                val u = size.width / 24f
                val p = Path().apply {
                    moveTo(5f * u, 12.5f * u)
                    lineTo(9.5f * u, 17f * u)
                    lineTo(19f * u, 7.5f * u)
                }
                drawPath(p, c.solidFg, style = Stroke(width = 3f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

/** An option's Preview: a small washed pill with the eye. */
@Composable
internal fun PreviewPill(onClick: () -> Unit) {
    val c = KuddeTheme.colors
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(KuddeRadius.key)).background(c.hover)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LineIconImage(LineIcon.Eye, c.muted, 14.dp)
        BasicText(PREVIEW, style = KuddeType.pill.copy(color = c.muted))
    }
}

/** The footer: a hairline above, [left] taking the room, [right] its buttons; [below] a line under it. */
@Composable
internal fun Footer(left: @Composable RowScope.() -> Unit, right: @Composable RowScope.() -> Unit, below: String? = null) {
    val c = KuddeTheme.colors
    Column(
        Modifier.fillMaxWidth().drawBehind {
            drawLine(c.border, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), KuddeBorder.thin.toPx())
        },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = left)
            right()
        }
        below?.let { BasicText(it, style = KuddeType.secondary.copy(color = c.blocked), modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp)) }
    }
}

/** "Something else" in the footer: the pen in an outlined keycap, then the words. */
@Composable
internal fun SomethingElse(enabled: Boolean, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(KuddeRadius.button)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlineKey(LineIcon.Pen)
        BasicText(SOMETHING_ELSE, style = KuddeType.rowName.copy(color = if (enabled) c.muted else c.dim))
    }
}

/** "Something else" as the last row of a multi-select question. */
@Composable
internal fun SomethingElseRow(enabled: Boolean, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    Row(
        Modifier.fillMaxWidth().drawBehind { hairline(c.border) }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(start = ROW_START, end = ROW_END, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OutlineKey(LineIcon.Pen)
        BasicText(SOMETHING_ELSE, style = KuddeType.rowName.copy(color = c.muted))
    }
}

/** Where the own-words field sits as a row of a multi-select question. */
@Composable
internal fun OtherRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = ROW_START, end = ROW_END, top = 8.dp, bottom = 12.dp), content = content)
}

/** The own-words field: the page's ground under a ring, the hint while empty, [send] at its end. */
@Composable
internal fun RowScope.OtherField(empty: Boolean, send: @Composable () -> Unit, input: @Composable (TextStyle, Brush) -> Unit) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.field)
    Row(
        Modifier.weight(1f).heightIn(min = 44.dp).clip(shape).background(if (c.isDark) c.bg else c.popover)
            .border(KuddeBorder.thin, c.ring, shape).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            if (empty) BasicText(TYPE_YOUR_ANSWER, style = KuddeType.rowName.copy(color = c.dimText))
            input(KuddeType.rowName.copy(color = c.fg), SolidColor(c.fg))
        }
        send()
    }
}

/** An outlined button of the footer: Skip, Open the terminal. */
@Composable
internal fun OutlineButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.button)
    Box(
        Modifier.height(40.dp).clip(shape).border(KuddeBorder.thin, c.input, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = KuddeType.settingRow.copy(color = if (enabled) c.fg else c.dim, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center))
    }
}

/** The step's main button: Send answers. */
@Composable
internal fun SolidButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(KuddeRadius.button)).background(if (enabled) c.solid else c.selected)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = KuddeType.settingRow.copy(color = if (enabled) c.solidFg else c.dim, fontWeight = FontWeight.Medium))
    }
}

/** The send arrow in a solid circle, spoken "Send"; [small] inside the own-words field. */
@Composable
internal fun SendCircle(enabled: Boolean, small: Boolean = false, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    val size: Dp = if (small) 36.dp else 40.dp
    Box(
        Modifier.size(size).clip(CircleShape).background(if (enabled) c.solid else c.selected)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = SEND },
        contentAlignment = Alignment.Center,
    ) {
        BasicText("↑", style = KuddeType.terminalTitle.copy(color = if (enabled) c.solidFg else c.dim))
    }
}

/** The multi-select count on the footer's left. */
@Composable
internal fun CountText(text: String) {
    BasicText(text, style = KuddeType.secondary.copy(color = KuddeTheme.colors.dim), modifier = Modifier.padding(start = 2.dp))
}

/** An option's preview in a sheet, in the chat's code box: wrapped, never sideways. */
@Composable
fun PreviewSheet(o: ChatOption, onClose: () -> Unit) {
    val c = KuddeTheme.colors
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(KuddeScrim).clickable(remember { MutableInteractionSource() }, null, onClick = onClose))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.62f)
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(sheetColor())
                .clickable(remember { MutableInteractionSource() }, null) {},
        ) {
            Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(c.dim))
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Box(Modifier.align(Alignment.CenterStart).size(44.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    LineIconImage(LineIcon.Close, c.fg, 22.dp)
                }
                BasicText(
                    previewTitle(o.label),
                    style = KuddeType.settingRow.copy(color = c.fg, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 52.dp),
                )
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp)) {
                BasicText(
                    o.preview.orEmpty(),
                    style = KuddeType.cardMsg.copy(color = c.termFg),
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(KuddeRadius.row)).background(boxColor()).padding(14.dp),
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
