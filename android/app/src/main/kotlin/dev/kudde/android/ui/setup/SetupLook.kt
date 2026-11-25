package dev.kudde.android.ui.setup

import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.view.autofill.AutofillManager
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kudde.android.R
import dev.kudde.android.ui.components.Chevron
import dev.kudde.android.ui.components.ChevronIcon
import dev.kudde.android.ui.theme.KuddeBorder
import dev.kudde.android.ui.theme.KuddeRadius
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.KuddeType

// The look of first setup (design D14, render first-run-*, components.firstRun*): the step bar,
// the choice cards, the checklist, the success moment, and the password field with its protections.

/** Whether small motion runs: the system's animations are on, unless a test or a golden says otherwise. */
val LocalSetupMotion = compositionLocalOf<Boolean?> { null }

@Composable
private fun motionOn(): Boolean {
    LocalSetupMotion.current?.let { return it }
    val context = LocalContext.current
    return remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
}

/** Four segments, the current one 2.2 times as wide; one element for a screen reader, "Step n of 4". */
@Composable
internal fun StepBar(step: Int) {
    val c = KuddeTheme.colors
    val spoken = stringResource(R.string.setup_step, step)
    val motion = motionOn()
    val sweep = remember(step) { Animatable(if (motion) 0f else 1f) }
    LaunchedEffect(step) { if (motion) sweep.animateTo(1f, tween(200)) }
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken; role = Role.Image },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (i in 1..4) {
            Box(
                Modifier.weight(if (i == step) 2.2f else 1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(c.border),
            ) {
                if (i <= step) {
                    val fraction = if (i == step) sweep.value else 1f
                    Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(c.fg))
                }
            }
        }
    }
}

/** One key choice as one tappable card: title, explanation, chevron. The filled one is the primary way. */
@Composable
fun ChoiceCard(title: String, text: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(20.dp)
    val ink = if (filled) c.solidFg else c.fg
    Box(
        Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f).clip(shape)
            .then(if (filled) Modifier.background(c.solid) else Modifier.border(KuddeBorder.thin, c.input, shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Column(Modifier.padding(start = 18.dp, top = 18.dp, bottom = 18.dp, end = 44.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BasicText(title, style = KuddeType.body.copy(fontSize = 16.sp, fontWeight = FontWeight.W500, color = ink))
            BasicText(text, style = KuddeType.body.copy(fontSize = 14.sp, lineHeight = 20.sp, color = if (filled) ink.copy(alpha = 0.74f) else c.muted))
        }
        ChevronIcon(Chevron.Right, 20.dp, ink.copy(alpha = 0.7f), Modifier.align(Alignment.CenterEnd).padding(end = 14.dp))
    }
}

private fun tickPath(unit: Float) = Path().apply {
    moveTo(2.5f * unit, 7.5f * unit); lineTo(5.5f * unit, 10.5f * unit); lineTo(11.5f * unit, 3.5f * unit)
}

private fun crossPath(unit: Float) = Path().apply {
    moveTo(3f * unit, 3f * unit); lineTo(11f * unit, 11f * unit); moveTo(11f * unit, 3f * unit); lineTo(3f * unit, 11f * unit)
}

@Composable
private fun Tick(size: Int, color: Color, width: Float = 2f) {
    Canvas(Modifier.size(size.dp)) {
        val unit = this.size.width / 14f
        drawPath(tickPath(unit), color, style = Stroke(width * unit, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A checklist mark (components.firstRunChecklist): done filled with a tick, now a turning arc, to do an empty ring, failed a cross. */
@Composable
internal fun StepMarkView(mark: StepMark) {
    val c = KuddeTheme.colors
    val motion = motionOn()
    Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
        when (mark) {
            StepMark.Done -> {
                val shown by androidx.compose.animation.core.animateFloatAsState(1f, tween(180), label = "tick")
                Box(Modifier.size(26.dp).clip(CircleShape).background(c.connected).alpha(shown), contentAlignment = Alignment.Center) {
                    Tick(14, c.solidFg)
                }
            }
            StepMark.Todo -> Box(Modifier.size(26.dp).border(2.dp, c.border, CircleShape))
            StepMark.Failed -> Box(Modifier.size(26.dp).border(2.dp, c.blocked, CircleShape), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(14.dp)) {
                    val unit = size.width / 14f
                    drawPath(crossPath(unit), c.blocked, style = Stroke(2f * unit, cap = StrokeCap.Round))
                }
            }
            StepMark.Now -> {
                val turn = if (motion) {
                    val t = rememberInfiniteTransition(label = "ring")
                    t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "turn").value
                } else 0f
                Canvas(Modifier.size(26.dp).rotate(turn)) {
                    val stroke = 2.dp.toPx()
                    val inset = stroke / 2
                    drawCircle(c.border, radius = size.width / 2 - inset, style = Stroke(stroke))
                    drawArc(c.fg, -90f, 90f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
                }
            }
        }
    }
}

/**
 * The password way's checklist (S1 to S3) with its outcome under the row that failed, or as a block
 * under the whole list. One polite live region: each change is read once.
 */
@Composable
internal fun Checklist(view: PasswordView, machine: String, user: String, bodyText: androidx.compose.ui.text.TextStyle, small: androidx.compose.ui.text.TextStyle) {
    val c = KuddeTheme.colors
    val marks = view.marks ?: return
    val labels = listOf(
        stringResource(R.string.s_signing_in, user),
        stringResource(R.string.s_adding_key),
        stringResource(R.string.s_signing_out),
    )
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        labels.forEachIndexed { i, label ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StepMarkView(marks[i])
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BasicText(label, style = bodyText.copy(fontSize = 16.sp, lineHeight = 26.sp, color = if (marks[i] == StepMark.Todo) c.dimText else c.fg))
                    if (view.outcomeRow == i) view.outcome?.let { OutcomeLines(it, bodyText, small) }
                }
            }
        }
        if (view.outcomeRow == null) view.outcome?.let { o -> OutcomeBlock(o, bodyText, small) }
    }
}

@Composable
private fun OutcomeLines(o: Outcome, bodyText: androidx.compose.ui.text.TextStyle, small: androidx.compose.ui.text.TextStyle) {
    val c = KuddeTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(o.title, style = bodyText.copy(color = c.fg, fontWeight = FontWeight.W500))
        o.detail?.let { BasicText(it, style = small.copy(color = c.muted)) }
    }
}

@Composable
private fun OutcomeBlock(o: Outcome, bodyText: androidx.compose.ui.text.TextStyle, small: androidx.compose.ui.text.TextStyle) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.field)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.canvas).border(KuddeBorder.thin, c.border, shape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { OutcomeLines(o, bodyText, small) }
}

/** The success moment (components.firstRunSuccess): the disc pops once inside its ring. */
@Composable
internal fun SuccessMoment(machine: String, first: Boolean, bodyText: androidx.compose.ui.text.TextStyle, small: androidx.compose.ui.text.TextStyle) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(20.dp)
    val motion = motionOn()
    val pop = remember { Animatable(if (motion) 0.85f else 1f) }
    LaunchedEffect(Unit) { if (motion) pop.animateTo(1f, tween(250)) }
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.canvas).border(KuddeBorder.thin, c.border, shape)
            .padding(vertical = 28.dp, horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(Modifier.size(104.dp).scale(pop.value).clip(CircleShape).background(c.successHalo), contentAlignment = Alignment.Center) {
            Box(Modifier.size(68.dp).clip(CircleShape).background(c.connected), contentAlignment = Alignment.Center) {
                Tick(30, c.solidFg)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(
                stringResource(R.string.c_connected, machine),
                style = bodyText.copy(fontSize = 17.sp, color = c.fg, fontWeight = FontWeight.W500, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            )
            if (first) BasicText(stringResource(R.string.s_first_machine), style = small.copy(color = c.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center))
        }
    }
}

// The password field and what protects it while the panel shows (spec "The password is never kept").

/** The password way's panel state with the field's own text state (never saved; emptied on close). */
data class PasswordUi(val view: PasswordView, val field: TextFieldState)

/** The platform calls the panel makes, replaceable in tests. */
class PasswordSeams(val cancelAutofill: () -> Unit)

val LocalPasswordSeams = compositionLocalOf<PasswordSeams?> { null }

private tailrec fun Context.activity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/**
 * While the panel shows: the window is secure (no screenshots, no recents picture), autofill and a
 * password manager's save offer are off for the hosting view; on leaving, both are restored and the
 * autofill session is cancelled.
 */
@Composable
internal fun PasswordProtection() {
    val view = LocalView.current
    val seams = LocalPasswordSeams.current
    DisposableEffect(view) {
        val window = view.context.activity()?.window
        val alreadySecure = window != null && window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        // The view hosting the composition (Compose's own view keeps its mode): its descendants are out of autofill.
        val host = view.parent as? View ?: view
        val before = host.importantForAutofill
        host.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose {
            if (!alreadySecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            host.importantForAutofill = before
            val cancel = seams?.cancelAutofill ?: { view.context.getSystemService(AutofillManager::class.java)?.cancel() }
            cancel()
        }
    }
}

/** The hidden field: no copy, no cut, the characters hidden, announced as a password. */
@Composable
internal fun PasswordField(label: String, state: TextFieldState, onDone: () -> Unit, body: androidx.compose.ui.text.TextStyle) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.field)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(label, style = body.copy(color = c.muted))
        BasicSecureTextField(
            state,
            Modifier.fillMaxWidth().clip(shape).border(KuddeBorder.thin, c.input, shape).background(c.canvas)
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .semantics {
                    contentDescription = label
                    contentType = ContentType.Password
                    // A screen reader is given dots, never the typed characters.
                    editableText = AnnotatedString("\u2022".repeat(state.text.length))
                },
            textStyle = body.copy(color = c.fg, fontSize = 16.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            onKeyboardAction = { onDone() },
            textObfuscationMode = TextObfuscationMode.Hidden,
            cursorBrush = SolidColor(c.fg),
        )
    }
}
