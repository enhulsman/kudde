package dev.kudde.android.ui.chat

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.kudde.android.ui.terminal.LineIcon
import dev.kudde.android.ui.terminal.LineIconImage
import dev.kudde.android.ui.theme.KuddeBorder
import dev.kudde.android.ui.theme.KuddeRadius
import dev.kudde.android.ui.theme.KuddeScrim
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.KuddeType
import uniffi.kudde_mobile.ChatOption

// How the question card looks, all in this file: surfaces, spacing, text
// styles, buttons and the preview sheet. ChatQuestion.kt decides what shows
// and how it reads; a new look replaces this file only. The look drawn here
// is a placeholder: the owner rejected it on 2026-11-24 and a redesign follows.

internal object QuestionLook {
    @Composable fun plainText(): TextStyle = KuddeType.body.copy(color = KuddeTheme.colors.fg)
    @Composable fun problemText(): TextStyle = KuddeType.secondary.copy(color = KuddeTheme.colors.fg)
    @Composable fun stepText(): TextStyle = KuddeType.secondary.copy(color = KuddeTheme.colors.dimText)
    @Composable fun questionText(): TextStyle = KuddeType.body.copy(color = KuddeTheme.colors.fg, fontWeight = FontWeight.Medium)
}

/** The card's surface, holding its content in a column that scrolls when taller than its room. */
@Composable
internal fun CardSurface(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.card)
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(shape)
            .background(c.popover)
            .border(KuddeBorder.thin, c.border, shape)
            .drawBehind { drawRect(c.blocked, size = size.copy(width = KuddeBorder.signature.toPx())) },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 15.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** Buttons side by side. */
@Composable
internal fun CardRow(content: @Composable RowScope.() -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** An option row's surface (before its click and semantics); [optionPadding] goes after them. */
internal fun Modifier.optionSurface(chosen: Boolean): Modifier = composed {
    val c = KuddeTheme.colors
    fillMaxWidth().clip(RoundedCornerShape(KuddeRadius.row)).background(if (chosen) c.selected else c.raised)
}

internal fun Modifier.optionPadding(): Modifier = padding(horizontal = 12.dp, vertical = 10.dp)

/** Where an option's Preview button sits, under its row. */
@Composable
internal fun PreviewButtonPlace(content: @Composable () -> Unit) {
    Box(Modifier.padding(top = 4.dp)) { content() }
}

/** "N." and the label, the description beneath; never cut. */
@Composable
internal fun OptionText(i: Int, o: ChatOption) {
    val c = KuddeTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText("${i + 1}.", style = KuddeType.body.copy(color = c.muted), modifier = Modifier.widthIn(min = 18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            BasicText(o.label, style = KuddeType.body.copy(color = c.fg))
            if (o.description.isNotBlank()) BasicText(o.description, style = KuddeType.secondary.copy(color = c.dimText))
        }
    }
}

/** The own-words block: the field, why it cannot be sent, and Send. */
@Composable
internal fun OtherLayout(field: @Composable () -> Unit, problem: String?, send: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        field()
        problem?.let { BasicText(it, style = KuddeType.secondary.copy(color = KuddeTheme.colors.blocked)) }
        send()
    }
}

/** The own-words field's box and hint; [input] draws the text field with the look's style and cursor. */
@Composable
internal fun OtherField(empty: Boolean, input: @Composable (TextStyle, Brush) -> Unit) {
    val c = KuddeTheme.colors
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(KuddeRadius.field)).background(c.input).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (empty) BasicText(TYPE_YOUR_ANSWER, style = KuddeType.body.copy(color = c.dimText))
        input(KuddeType.body.copy(color = c.fg), SolidColor(c.fg))
    }
}

/** A pill button of the card; [solid] for the step's main action. */
@Composable
internal fun CardButton(label: String, enabled: Boolean, solid: Boolean = false, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    val bg = when {
        !enabled -> c.hover
        solid -> c.solid
        else -> c.raised
    }
    val fg = when {
        !enabled -> c.dimText
        solid -> c.solidFg
        else -> c.fg
    }
    BasicText(
        label,
        style = KuddeType.pill.copy(color = fg, textAlign = TextAlign.Center),
        modifier = Modifier.widthIn(min = 48.dp).clip(RoundedCornerShape(KuddeRadius.chip)).background(bg)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
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
