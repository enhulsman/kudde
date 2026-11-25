package dev.kudde.android.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
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
import dev.kudde.android.ui.theme.KuddeSpacing
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.KuddeType
import uniffi.kudde_mobile.ChatOption

// How the question card looks, all in this file (direction A, picked
// 2026-11-25): one raised panel, flat option rows split by hairlines, numbers
// in small keycaps, and a footer with Something else on the left and Skip
// (and the send arrow on multi-select) on the right. Colours, radii and type
// are the theme's tokens only. ChatQuestion.kt decides what shows and how it
// reads.

/** One inset for the whole card: rows, title, hairlines and footer line up on it (spacing pass, 042). */
private val INSET = 14.dp
private val ROW_START = INSET
private val ROW_END = INSET
private val TEXT_START = INSET

/** The keycap, tick box and pencil all sit in one slot, then one gap to the text. */
private val SLOT = 24.dp
private val GAP = 10.dp

/** Every tappable thing is at least this tall and wide, whatever it draws. */
private val TOUCH = 48.dp

/** The own-words field reaches this far left of the slot, so the pencil keeps its x when it opens. */
private val FIELD_INSET = 6.dp

/** The panel: popover in graphite, canvas in paper, under a 1 dp border; the body scrolls, the footer stays. */
@Composable
internal fun CardSurface(modifier: Modifier, body: @Composable ColumnScope.() -> Unit, footer: @Composable () -> Unit) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.machineCard)
    Column(
        modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = KuddeSpacing.s12)
            .clip(shape)
            .background(if (c.isDark) c.popover else c.canvas)
            .border(KuddeBorder.thin, c.border, shape),
    ) {
        Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = KuddeSpacing.s12), content = body)
        footer()
    }
}

@Composable
internal fun StepLine(text: String) {
    BasicText(text, style = KuddeType.secondary.copy(color = KuddeTheme.colors.dim), modifier = Modifier.padding(start = TEXT_START, end = TEXT_START, bottom = 6.dp))
}

/**
 * "Question N of M" with the previous and next controls (D11): chevrons in
 * 48 dp touch targets, read as "Previous question" and "Next question"; a
 * null action draws the chevron dimmed and disabled.
 */
@Composable
internal fun StepNav(text: String, back: (() -> Unit)?, forward: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = TEXT_START - 4.dp, end = INSET - 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavChevron(LineIcon.ChevronLeft, PREVIOUS_QUESTION, back)
        // Read with the question below it (one stop), not here.
        BasicText(text, style = KuddeType.secondary.copy(color = KuddeTheme.colors.dim), modifier = Modifier.weight(1f).clearAndSetSemantics {})
        NavChevron(LineIcon.ChevronRight, NEXT_QUESTION, forward)
    }
}

@Composable
private fun NavChevron(icon: LineIcon, label: String, onClick: (() -> Unit)?) {
    val c = KuddeTheme.colors
    Box(
        Modifier.size(TOUCH).clickable(enabled = onClick != null, role = Role.Button) { onClick?.invoke() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        LineIconImage(icon, if (onClick != null) c.fg else c.dim, 18.dp)
    }
}

/**
 * One kept answer on the review step (D11): the question above its answer
 * (and its note), one stop; then its change control, a stop of its own that
 * goes back to the question.
 */
@Composable
internal fun AnswerRow(
    question: String,
    answer: String,
    note: String?,
    enabled: Boolean,
    answered: Boolean = true,
    modifier: Modifier = Modifier,
    onChange: () -> Unit,
) {
    val c = KuddeTheme.colors
    Row(
        Modifier.fillMaxWidth().drawBehind { hairline(c.border) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier.weight(1f).padding(start = ROW_START, top = KuddeSpacing.s8, bottom = KuddeSpacing.s8)
                .clearAndSetSemantics { contentDescription = listOfNotNull(question, answer, note).joinToString(", ") },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BasicText(question, style = KuddeType.secondary.copy(color = c.muted, lineHeight = 18.sp))
            BasicText(answer, style = KuddeType.rowName.copy(color = if (answered) c.fg else c.dim, lineHeight = 20.sp))
            if (note != null) BasicText(note, style = KuddeType.secondary.copy(color = c.muted, lineHeight = 18.sp))
        }
        Box(
            Modifier.heightIn(min = TOUCH).padding(end = INSET - 12.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onChange)
                .clearAndSetSemantics {
                    contentDescription = if (answered) changeSpoken(question) else answerSpoken(question)
                    role = Role.Button
                    if (!enabled) disabled()
                    onClick { onChange(); true }
                }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(if (answered) CHANGE else ANSWER, style = KuddeType.settingRow.copy(color = if (enabled) c.fg else c.dim, fontWeight = FontWeight.Medium))
        }
    }
}

@Composable
internal fun QuestionTitle(text: String, modifier: Modifier = Modifier) {
    BasicText(
        text,
        style = KuddeType.subScreenTitle.copy(color = KuddeTheme.colors.fg, fontWeight = FontWeight.Medium, lineHeight = 27.sp, letterSpacing = (-0.01).em),
        modifier = modifier.padding(start = TEXT_START, end = TEXT_START, bottom = KuddeSpacing.s8),
    )
}

@Composable
internal fun Hint(text: String) {
    BasicText(text, style = KuddeType.secondary.copy(color = KuddeTheme.colors.muted), modifier = Modifier.padding(start = TEXT_START, end = TEXT_START, bottom = KuddeSpacing.s4))
}

/** A plain sentence in the card's body: what is left to do (the review step, no hook). */
@Composable
internal fun CardText(text: String, modifier: Modifier = Modifier) {
    BasicText(text, style = KuddeType.body.copy(color = KuddeTheme.colors.fg), modifier = modifier.padding(start = TEXT_START, end = TEXT_START, bottom = KuddeSpacing.s8))
}

/** Why nothing was sent: a quiet strip with a hollow ring, and the way to the terminal where it helps. */
@Composable
internal fun Notice(text: String, onTerminal: (() -> Unit)?) {
    val c = KuddeTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = INSET, end = INSET, bottom = KuddeSpacing.s8).heightIn(min = 36.dp)
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
                modifier = Modifier.heightIn(min = TOUCH).clickable(role = Role.Button, onClick = onTerminal).wrapContentHeight().padding(horizontal = 6.dp),
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
            Modifier.weight(1f).heightIn(min = TOUCH).then(click)
                .padding(start = ROW_START, end = if (trailing == null) ROW_END else 8.dp, top = KuddeSpacing.s8, bottom = KuddeSpacing.s8),
            // The keycap or tick box sits in the middle of the whole row, however many lines (as Claude's app).
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GAP),
        ) {
            lead()
            OptionBody(label, description)
        }
        // The pill's own 2 dp touch margin plus this puts its edge on the card's inset.
        if (trailing != null) Box(Modifier.padding(end = INSET - 2.dp)) { trailing() }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.hairline(color: Color) {
    val y = 0.5.dp.toPx()
    drawLine(color, Offset(INSET.toPx(), y), Offset(size.width - INSET.toPx(), y), KuddeBorder.thin.toPx())
}

@Composable
private fun OptionBody(label: String, description: String) {
    val c = KuddeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(label, style = KuddeType.rowName.copy(color = c.fg, lineHeight = 20.sp))
        if (description.isNotBlank()) BasicText(description, style = KuddeType.secondary.copy(color = c.muted, lineHeight = 18.sp))
    }
}

/** The option's number in a small keycap: "1", no dot. */
@Composable
internal fun Keycap(n: Int) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.key)
    Box(
        Modifier.size(SLOT).clip(shape)
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
    Box(Modifier.size(SLOT).border(KuddeBorder.thin, c.input, RoundedCornerShape(KuddeRadius.key)), contentAlignment = Alignment.Center) {
        LineIconImage(icon, c.muted, 14.dp)
    }
}

/** A multi-select tick box: outlined, filled solid with a check when ticked. */
@Composable
internal fun CheckBox(on: Boolean) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.pill)
    Box(
        Modifier.padding(2.dp).size(20.dp).clip(shape)
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
    Box(Modifier.heightIn(min = TOUCH).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(32.dp).clip(RoundedCornerShape(KuddeRadius.key)).background(c.hover).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LineIconImage(LineIcon.Eye, c.muted, 14.dp)
            BasicText(PREVIEW, style = KuddeType.pill.copy(color = c.muted))
        }
    }
}

/**
 * The footer: a line above (on the card's inset, full width over the
 * multi-select bar when [fullRule]), [left] taking the room, [right] its
 * buttons; [below] a line under it. [fieldOpen]: the own-words field sits on
 * the left, reaching [FIELD_INSET] past the inset so its pencil stays put.
 */
@Composable
internal fun Footer(
    left: @Composable RowScope.() -> Unit,
    right: @Composable RowScope.() -> Unit,
    below: String? = null,
    fieldOpen: Boolean = false,
    fullRule: Boolean = false,
    endsWithCircle: Boolean = false,
) {
    val c = KuddeTheme.colors
    Column(
        Modifier.fillMaxWidth().drawBehind {
            val y = 0.5.dp.toPx()
            val inset = if (fullRule) 0f else INSET.toPx()
            drawLine(c.border, Offset(inset, y), Offset(size.width - inset, y), KuddeBorder.thin.toPx())
        },
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 50.dp)
                // The last button's drawn edge on the inset: a circle draws 8 dp inside its 48 dp target.
                .padding(start = if (fieldOpen) INSET - FIELD_INSET else INSET, end = if (endsWithCircle) INSET - 8.dp else INSET, top = KuddeSpacing.s4, bottom = KuddeSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KuddeSpacing.s6),
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, content = left)
            right()
        }
        below?.let { BasicText(it, style = KuddeType.secondary.copy(color = c.blocked), modifier = Modifier.padding(start = INSET, end = INSET, bottom = KuddeSpacing.s8)) }
    }
}

/** "Something else" in the footer: the pen in an outlined keycap, then the words. */
@Composable
internal fun SomethingElse(enabled: Boolean, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    Row(
        Modifier.heightIn(min = TOUCH).clip(RoundedCornerShape(KuddeRadius.button)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GAP),
    ) {
        OutlineKey(LineIcon.Pen)
        BasicText(SOMETHING_ELSE, style = KuddeType.rowName.copy(color = if (enabled) c.muted else c.dim, lineHeight = 20.sp))
    }
}

/** "Something else" as the last row of a multi-select question. */
@Composable
internal fun SomethingElseRow(enabled: Boolean, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = TOUCH).drawBehind { hairline(c.border) }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(start = ROW_START, end = ROW_END, top = KuddeSpacing.s8, bottom = KuddeSpacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GAP),
    ) {
        OutlineKey(LineIcon.Pen)
        BasicText(SOMETHING_ELSE, style = KuddeType.rowName.copy(color = c.muted, lineHeight = 20.sp))
    }
}

/** Where the own-words field sits as a row of a multi-select question. */
@Composable
internal fun OtherRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = ROW_START - FIELD_INSET, end = ROW_END, top = KuddeSpacing.s4, bottom = KuddeSpacing.s8), content = content)
}

/**
 * The own-words field: a tinted row, a quiet 1 dp line only while focused,
 * the pencil and the text at the x they had closed, the hint while empty,
 * [send] centred at its end.
 */
@Composable
internal fun RowScope.OtherField(
    empty: Boolean,
    focused: Boolean,
    lead: @Composable () -> Unit,
    send: (@Composable () -> Unit)?,
    input: @Composable (TextStyle, Brush) -> Unit,
) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.field)
    Row(
        Modifier.weight(1f).heightIn(min = TOUCH).clip(shape).background(c.hover)
            .then(if (focused) Modifier.border(KuddeBorder.thin, c.border, shape) else Modifier)
            .padding(start = FIELD_INSET, end = if (send == null) KuddeSpacing.s8 else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GAP),
    ) {
        Box(Modifier.size(SLOT), contentAlignment = Alignment.Center) { lead() }
        Box(Modifier.weight(1f).padding(vertical = KuddeSpacing.s8)) {
            val style = KuddeType.rowName.copy(lineHeight = 20.sp)
            if (empty) BasicText(TYPE_YOUR_ANSWER, style = style.copy(color = c.dimText))
            input(style.copy(color = c.fg), SolidColor(c.fg))
        }
        send?.invoke()
    }
}

/** The own-words field's lead on a single-choice question. */
@Composable
internal fun Pencil() {
    LineIconImage(LineIcon.Pen, KuddeTheme.colors.muted, 14.dp)
}

/**
 * A multi-select question's typed answer once its field is closed: a ticked
 * row with the text, read as one stop; a tap opens the field again.
 */
@Composable
internal fun TypedRow(text: String, enabled: Boolean, spoken: String, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = TOUCH).drawBehind { hairline(c.border) }
            .clickable(enabled = enabled, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick { onClick(); true }
            }
            .padding(start = ROW_START, end = ROW_END, top = KuddeSpacing.s8, bottom = KuddeSpacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GAP),
    ) {
        Box(Modifier.size(SLOT), contentAlignment = Alignment.Center) { CheckBox(true) }
        BasicText(text, style = KuddeType.rowName.copy(color = c.fg, lineHeight = 20.sp))
    }
}

/** An outlined button of the footer: Skip, Open the terminal. */
@Composable
internal fun OutlineButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.button)
    Box(Modifier.heightIn(min = TOUCH).clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.height(32.dp).border(KuddeBorder.thin, c.input, shape).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            BasicText(label, style = KuddeType.settingRow.copy(color = if (enabled) c.fg else c.dim, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center))
        }
    }
}

/** The step's main button: Send answers. */
@Composable
internal fun SolidButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    Box(Modifier.heightIn(min = TOUCH).clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(
            Modifier.height(32.dp).clip(RoundedCornerShape(KuddeRadius.button)).background(if (enabled) c.solid else c.selected).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(label, style = KuddeType.settingRow.copy(color = if (enabled) c.solidFg else c.dim, fontWeight = FontWeight.Medium))
        }
    }
}

/**
 * The send arrow in a solid circle, spoken "Send", in a 48 dp touch target;
 * 32 dp in the footer, 28 dp ([small]) inside the own-words field.
 */
@Composable
internal fun SendCircle(enabled: Boolean, small: Boolean = false, label: String = SEND, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    val size: Dp = if (small) 28.dp else 32.dp
    Box(
        Modifier.size(TOUCH).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).clip(CircleShape).background(if (enabled) c.solid else c.selected), contentAlignment = Alignment.Center) {
            BasicText("↑", style = KuddeType.settingRow.copy(color = if (enabled) c.solidFg else c.dim, fontWeight = FontWeight.SemiBold))
        }
    }
}

/** The multi-select count on the footer's left. */
@Composable
internal fun CountText(text: String) {
    BasicText(text, style = KuddeType.secondary.copy(color = KuddeTheme.colors.dim), modifier = Modifier.padding(start = 2.dp))
}

/**
 * An option's preview in a sheet (look A, owner 2026-11-25): the sample full
 * width in the code font, its lines never wrapped (a wide one scrolls
 * sideways, a long one up and down); fixed below it the [note] field and
 * "Choose this". With [note] null (multi-select) the sheet shows the sample
 * only. As tall as its content, at most 90% of the screen.
 */
@Composable
fun PreviewSheet(
    o: ChatOption,
    note: String?,
    onNote: (String) -> Unit,
    enabled: Boolean,
    onChoose: () -> Unit,
    onClose: () -> Unit,
) {
    val c = KuddeTheme.colors
    val maxH = LocalConfiguration.current.screenHeightDp.dp * 0.9f
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(KuddeScrim).clickable(remember { MutableInteractionSource() }, null, onClick = onClose))
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().heightIn(max = maxH)
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
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(KuddeRadius.row)).background(boxColor()).horizontalScroll(rememberScrollState())) {
                    BasicText(o.preview.orEmpty(), style = KuddeType.cardMsg.copy(color = c.termFg), softWrap = false, modifier = Modifier.padding(14.dp))
                }
                if (note == null) Spacer(Modifier.navigationBarsPadding().height(16.dp))
            }
            if (note != null) NoteBar(note, onNote, enabled, onChoose)
        }
    }
}

/** The preview sheet's bottom: the note field and "Choose this", on the card's footer language. */
@Composable
private fun NoteBar(note: String, onNote: (String) -> Unit, enabled: Boolean, onChoose: () -> Unit) {
    val c = KuddeTheme.colors
    val tooLong = note.length > OTHER_MAX
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth()
            .drawBehind { drawLine(c.border, Offset(0f, 0.5.dp.toPx()), Offset(size.width, 0.5.dp.toPx()), KuddeBorder.thin.toPx()) }
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = KuddeSpacing.s8, bottom = KuddeSpacing.s12),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KuddeSpacing.s8)) {
            val shape = RoundedCornerShape(KuddeRadius.field)
            val style = KuddeType.rowName.copy(lineHeight = 20.sp)
            BasicTextField(
                value = note,
                onValueChange = onNote,
                enabled = enabled,
                textStyle = style.copy(color = c.fg),
                cursorBrush = SolidColor(c.fg),
                modifier = Modifier.weight(1f).heightIn(min = TOUCH).clip(shape).background(c.hover)
                    .then(if (focused || note.isNotEmpty()) Modifier.border(KuddeBorder.thin, c.border, shape) else Modifier)
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { contentDescription = NOTE_HINT },
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = 12.dp, vertical = KuddeSpacing.s8), contentAlignment = Alignment.CenterStart) {
                        if (note.isEmpty()) BasicText(NOTE_HINT, style = style.copy(color = c.dimText))
                        inner()
                    }
                },
            )
            SolidButton(CHOOSE_THIS, enabled = enabled && !tooLong, onClick = onChoose)
        }
        if (tooLong) BasicText(TOO_LONG, style = KuddeType.secondary.copy(color = c.blocked), modifier = Modifier.padding(top = KuddeSpacing.s4))
    }
}
