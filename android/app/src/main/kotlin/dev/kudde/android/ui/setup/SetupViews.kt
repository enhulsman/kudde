package dev.kudde.android.ui.setup

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.kudde.android.R
import dev.kudde.android.ui.addmachine.FieldError
import dev.kudde.android.ui.addmachine.FormErrors
import dev.kudde.android.ui.addmachine.MachineInput
import dev.kudde.android.ui.components.DialogButton
import dev.kudde.android.ui.components.ErrorText
import dev.kudde.android.ui.components.OutlineButton
import dev.kudde.android.ui.components.SubScreenTopBar
import dev.kudde.android.ui.machines.ReachText
import dev.kudde.android.ui.theme.KuddeBorder
import dev.kudde.android.ui.theme.KuddeRadius
import dev.kudde.android.ui.theme.KuddeSpacing
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.KuddeType

// The first-setup screens, drawn from the signed-off renders (design/renders/screens.html,
// ids first-run-*): graphite and paper tokens, Geist, chrome background. Stateless; SetupRoute
// feeds them. Four steps share one frame: back arrow, "Step n of 4", a heading, a scrolling
// body and a footer with the step's buttons.

private val bodyText = KuddeType.body.copy(fontSize = 14.sp, lineHeight = 1.5.em)
private val small = KuddeType.secondary.copy(fontSize = 13.sp, lineHeight = 1.45.em)

/** One screen's frame. [step] null for the welcome and the lock notice. */
@Composable
private fun Frame(
    onBack: (() -> Unit)?,
    step: Int?,
    heading: String?,
    footer: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    title: String = stringResource(R.string.setup_title),
    body: @Composable ColumnScope.() -> Unit,
) {
    val c = KuddeTheme.colors
    Column(modifier.fillMaxSize().background(c.chrome).safeDrawingPadding()) {
        if (onBack != null || step != null) SubScreenTopBar(title, stringResource(R.string.back), onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(start = KuddeSpacing.s20, end = KuddeSpacing.s20, top = KuddeSpacing.s16, bottom = KuddeSpacing.s20),
            verticalArrangement = Arrangement.spacedBy(KuddeSpacing.s16),
        ) {
            if (step != null) {
                BasicText(stringResource(R.string.setup_step, step), style = small.copy(color = c.muted))
            }
            if (heading != null) {
                BasicText(
                    heading,
                    Modifier.semantics { heading() },
                    style = KuddeType.screenTitle.copy(color = c.fg),
                )
            }
            body()
        }
        Column(
            Modifier.padding(start = KuddeSpacing.s20, end = KuddeSpacing.s20, top = KuddeSpacing.s12, bottom = KuddeSpacing.s8),
            verticalArrangement = Arrangement.spacedBy(KuddeSpacing.s8),
            content = footer,
        )
    }
}

@Composable
private fun Paragraph(text: String, modifier: Modifier = Modifier) =
    BasicText(text, modifier, style = bodyText.copy(color = KuddeTheme.colors.muted))

@Composable
private fun Solid(label: String, onClick: () -> Unit, enabled: Boolean = true) =
    DialogButton(label, onClick, Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.38f), solid = true)

@Composable
private fun Outline(label: String, onClick: () -> Unit) = OutlineButton(label, onClick, Modifier.fillMaxWidth())

/** A panel on the canvas colour: failures, waiting lines, the connected line. */
@Composable
private fun Notice(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.field)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.canvas).border(KuddeBorder.thin, c.border, shape)
            .padding(KuddeBorder.thin).padding(KuddeSpacing.s14),
        verticalArrangement = Arrangement.spacedBy(KuddeSpacing.s8),
        content = content,
    )
}

@Composable
private fun Dot() {
    Box(Modifier.size(9.dp).clip(CircleShape).background(KuddeTheme.colors.connected))
}

/** A link in muted text under a button ("Setup guide"). */
@Composable
private fun GuideLink(onClick: () -> Unit) {
    BasicText(
        stringResource(R.string.wl_guide),
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(onClick = onClick).padding(vertical = 12.dp),
        style = bodyText.copy(color = KuddeTheme.colors.muted, textDecoration = TextDecoration.Underline, textAlign = TextAlign.Center),
    )
}

// The welcome (first-run-welcome) and the screen-lock notice (first-run-lock).

@Composable
fun WelcomeScreen(onStart: () -> Unit, onGuide: (() -> Unit)?, modifier: Modifier = Modifier) {
    val c = KuddeTheme.colors
    // No back arrow and no skip: Android Back closes the app from here.
    Frame(
        onBack = null, step = null, heading = null, modifier = modifier,
        footer = {
            Solid(stringResource(R.string.wl_action), onStart)
            onGuide?.let { GuideLink(it) }
        },
    ) {
        Image(painterResource(R.drawable.kudde_tag), null, Modifier.size(64.dp).padding(top = KuddeSpacing.s8))
        BasicText(
            stringResource(R.string.wl_title),
            Modifier.semantics { heading() },
            style = KuddeType.screenTitle.copy(color = c.fg),
        )
        Paragraph(stringResource(R.string.wl_line))
        BasicText(stringResource(R.string.wl_steps_title), style = KuddeType.body.copy(color = c.fg, fontWeight = androidx.compose.ui.text.font.FontWeight.W500))
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            for ((title, text) in listOf(
                R.string.wl_step1_title to R.string.wl_step1_text,
                R.string.wl_step2_title to R.string.wl_step2_text,
                R.string.wl_step3_title to R.string.wl_step3_text,
                R.string.wl_step4_title to R.string.wl_step4_text,
            )) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BasicText(stringResource(title), style = KuddeType.body.copy(color = c.fg, fontWeight = androidx.compose.ui.text.font.FontWeight.W500))
                    Paragraph(stringResource(text))
                }
            }
        }
    }
}

@Composable
fun LockNoticeScreen(onAction: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Frame(
        onBack = onBack, step = null, heading = stringResource(R.string.setup_no_lock_title), modifier = modifier,
        footer = { Solid(stringResource(R.string.setup_no_lock_action), onAction) },
    ) {
        Paragraph(stringResource(R.string.setup_no_lock_detail))
    }
}

/** The home's variant after completion: the same words as a card. */
@Composable
fun LockNoticeCard(onAction: () -> Unit, modifier: Modifier = Modifier) {
    Notice(modifier.padding(KuddeSpacing.s16)) {
        BasicText(stringResource(R.string.setup_no_lock_title), style = KuddeType.subScreenTitle.copy(color = KuddeTheme.colors.fg))
        Paragraph(stringResource(R.string.setup_no_lock_detail))
        Solid(stringResource(R.string.setup_no_lock_action), onAction)
    }
}

// Step 1 (first-run-address).

/** What step 1 shows under the fields. */
sealed interface AddressStatus {
    data object Idle : AddressStatus

    /** The first result is awaited; [slow] after a few seconds (A9). */
    data class Waiting(val address: String, val slow: Boolean) : AddressStatus
    data class Failed(val reach: ReachText) : AddressStatus
}

@Immutable
data class AddressUi(
    val input: MachineInput,
    val errors: FormErrors?,
    val status: AddressStatus,
    val error: String?,
    val nameLocked: Boolean,
    /** The address-derived name shown in the Name field's hint. */
    val derivedName: String?,
)

data class AddressActions(
    val onBack: () -> Unit,
    val onChange: (MachineInput) -> Unit,
    val onContinue: () -> Unit,
    val onTryAgain: () -> Unit,
)

@Composable
fun AddressStep(ui: AddressUi, actions: AddressActions, modifier: Modifier = Modifier) {
    val editable = ui.status !is AddressStatus.Waiting
    val input = ui.input
    Frame(
        onBack = actions.onBack, step = 1, heading = stringResource(R.string.a_heading), modifier = modifier,
        footer = {
            when (ui.status) {
                is AddressStatus.Failed -> Solid(stringResource(R.string.f_try_again), actions.onTryAgain)
                else -> Solid(stringResource(R.string.a_continue), actions.onContinue, enabled = editable)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(KuddeSpacing.s14)) {
            Field(
                R.string.a_address, input.host, { actions.onChange(input.copy(host = it)) },
                hint = stringResource(R.string.a_address_hint), help = stringResource(R.string.a_address_help),
                error = ui.errors?.host?.let { fieldError(it, "") }, enabled = editable,
                keyboard = KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            Field(
                R.string.a_user, input.user, { actions.onChange(input.copy(user = it)) },
                hint = stringResource(R.string.a_user_hint),
                error = ui.errors?.user?.let { fieldError(it, "") }, enabled = editable,
                keyboard = KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            )
            Field(
                R.string.a_port, input.port, { actions.onChange(input.copy(port = it)) },
                error = ui.errors?.port?.let { fieldError(it, "") }, enabled = editable,
                keyboard = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            )
            Field(
                R.string.a_name, input.name, { actions.onChange(input.copy(name = it)) },
                hint = ui.derivedName ?: stringResource(R.string.a_name_hint),
                help = stringResource(R.string.a_name_help),
                error = ui.errors?.name?.let { fieldError(it, input.name.trim()) },
                enabled = editable && !ui.nameLocked,
                keyboard = KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            )
        }
        Paragraph(stringResource(R.string.a_tailscale))
        ui.error?.let { ErrorText(it) }
        when (val status = ui.status) {
            AddressStatus.Idle -> Unit
            is AddressStatus.Waiting -> Notice(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                BasicText(stringResource(R.string.a_connecting, status.address), style = bodyText.copy(color = KuddeTheme.colors.fg))
                if (status.slow) Paragraph(stringResource(R.string.a_slow))
            }
            is AddressStatus.Failed -> ReachNotice(status.reach)
        }
    }
}

/** F1 to F5: the failure on the step and on the card, announced once. */
@Composable
fun ReachNotice(reach: ReachText, modifier: Modifier = Modifier) {
    Notice(modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        BasicText(reach.reason, style = bodyText.copy(color = KuddeTheme.colors.fg, fontWeight = androidx.compose.ui.text.font.FontWeight.W500))
        reach.recovery?.let { Paragraph(it) }
    }
}

@Composable
private fun Field(
    label: Int,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String? = null,
    help: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.field)
    val fieldText = KuddeType.body.copy(fontSize = 16.sp, lineHeight = 1.45.em)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(stringResource(label), style = bodyText.copy(color = c.muted))
        BasicTextField(
            value, onValueChange,
            Modifier.fillMaxWidth().semantics { if (error != null) error(error) },
            enabled = enabled, textStyle = fieldText.copy(color = c.fg), keyboardOptions = keyboard,
            singleLine = true, cursorBrush = SolidColor(c.fg),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().background(c.canvas, shape)
                        .border(KuddeBorder.thin, if (error != null) c.blocked else c.input, shape)
                        .padding(KuddeBorder.thin).padding(horizontal = KuddeSpacing.s14, vertical = KuddeSpacing.s12),
                ) {
                    if (value.isEmpty() && hint != null) BasicText(hint, style = fieldText.copy(color = c.dimText))
                    inner()
                }
            },
        )
        help?.let { BasicText(it, style = small.copy(color = c.muted)) }
        error?.let { ErrorText(it) }
    }
}

@Composable
private fun fieldError(error: FieldError, name: String): String = when (error) {
    FieldError.NameRequired -> stringResource(R.string.error_name_required)
    FieldError.NameTaken -> stringResource(R.string.error_name_taken, name)
    FieldError.NameTooLong -> stringResource(R.string.error_name_too_long)
    FieldError.NameOneLine -> stringResource(R.string.error_name_one_line)
    FieldError.HostRequired -> stringResource(R.string.error_host_required)
    FieldError.HostInvalid -> stringResource(R.string.error_host_invalid)
    FieldError.PortInvalid -> stringResource(R.string.error_port_invalid)
    FieldError.UserRequired -> stringResource(R.string.error_user_required)
    FieldError.UserInvalid -> stringResource(R.string.error_user_invalid)
}

// Step 2 (first-run-manual, first-run-key-refused): the two choices, and the manual detail.

/** What the machine says at step 2 before a choice. */
sealed interface KeyNotice {
    data object None : KeyNotice

    /** K1: the key was not accepted. */
    data object Rejected : KeyNotice

    /** K2: herdr-core is not there; the key was accepted. */
    data object CoreMissing : KeyNotice

    /** A failure of the hub's attempt, shown as on step 1. */
    data class Reach(val reach: ReachText) : KeyNotice
}

@Immutable
data class KeyUi(
    val machine: String,
    val user: String,
    val notice: KeyNotice,
    /** The two choices come with their descriptions until a detail is open (first-run-key-refused). */
    val describe: Boolean,
    /** The manual detail: the command to show, or null for the two choices only. */
    val command: String?,
    val copied: Boolean,
    /** Whether the host key can be compared now ("Compare the fingerprint"). */
    val canCompare: Boolean,
    /** After a done password run that was refused (R16), the password choice is withdrawn. */
    val passwordOffered: Boolean = true,
    /** Footer: "Try again" after a failure, "Change the address". */
    val canTryAgain: Boolean,
    /** Not reached yet (a return from the background): "Connecting to workstation…" where the button was. */
    val connecting: Boolean = false,
)

data class KeyActions(
    val onBack: () -> Unit,
    val onChoose: (KeyChoice) -> Unit,
    val onCopy: () -> Unit,
    val onShare: () -> Unit,
    val onCompare: () -> Unit,
    val onTryAgain: () -> Unit,
    val onChangeAddress: () -> Unit,
)

@Composable
fun KeyStep(ui: KeyUi, actions: KeyActions, modifier: Modifier = Modifier) {
    Frame(
        onBack = actions.onBack, step = 2, heading = stringResource(R.string.m_heading), modifier = modifier,
        footer = {
            if (ui.command != null && ui.canCompare) Solid(stringResource(R.string.m_compare), actions.onCompare)
            if (ui.command != null && !ui.canCompare && ui.connecting) {
                BasicText(
                    stringResource(R.string.a_connecting, ui.machine),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(vertical = 11.dp),
                    style = bodyText.copy(color = KuddeTheme.colors.muted),
                )
            }
            if (ui.canTryAgain) {
                Solid(stringResource(R.string.f_try_again), actions.onTryAgain)
                Outline(stringResource(R.string.setup_change_address), actions.onChangeAddress)
            }
        },
    ) {
        when (val notice = ui.notice) {
            KeyNotice.None -> Unit
            KeyNotice.Rejected -> TitledNotice(stringResource(R.string.k_rejected_title, ui.machine), stringResource(R.string.k_rejected_detail, ui.machine, ui.user))
            KeyNotice.CoreMissing -> TitledNotice(stringResource(R.string.k_missing_title, ui.machine), stringResource(R.string.k_missing_detail, ui.machine, ui.user))
            is KeyNotice.Reach -> ReachNotice(notice.reach)
        }
        ui.command?.let { command ->
            Paragraph(stringResource(R.string.m_sentence, ui.machine, ui.user))
            CommandBox(command)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton(stringResource(if (ui.copied) R.string.m_copied else R.string.m_copy), actions.onCopy)
                ActionButton(stringResource(R.string.m_share), actions.onShare)
            }
            Paragraph(stringResource(R.string.m_note_send))
            Paragraph(stringResource(R.string.m_note_what))
            Paragraph(stringResource(R.string.m_note_shells))
        }
        Choices(ui, actions)
        if (ui.describe) Paragraph(stringResource(R.string.r_choices_note))
    }
}

/** The command: one node named "Setup command", not a live region, mono, scrolling sideways. */
@Composable
fun CommandBox(command: String) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.field)
    val name = stringResource(R.string.m_command_name)
    Box(
        Modifier.fillMaxWidth().clip(shape).background(c.canvas).border(KuddeBorder.thin, c.border, shape)
            .padding(KuddeBorder.thin)
            .semantics(mergeDescendants = true) { contentDescription = name },
    ) {
        BasicText(
            command,
            Modifier.horizontalScroll(rememberScrollState())
                .padding(horizontal = KuddeSpacing.s14, vertical = KuddeSpacing.s12),
            style = KuddeType.fingerprint.copy(color = c.fg),
            softWrap = false,
        )
    }
}

@Composable
private fun RowScope.ActionButton(label: String, onClick: () -> Unit) =
    OutlineButton(label, onClick, Modifier.weight(1f))

@Composable
private fun TitledNotice(title: String, detail: String) {
    Notice(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        BasicText(title, style = bodyText.copy(color = KuddeTheme.colors.fg, fontWeight = androidx.compose.ui.text.font.FontWeight.W500))
        Paragraph(detail)
    }
}

/**
 * The two choices, password-once first, side by side, stacked in that order
 * when large text cannot fit them.
 */
@Composable
private fun Choices(ui: KeyUi, actions: KeyActions) {
    val stacked = LocalDensity.current.fontScale >= 1.6f
    val password: @Composable (Modifier) -> Unit = { m ->
        Choice(
            stringResource(R.string.r_choice_password),
            if (ui.describe) stringResource(R.string.r_choice_password_text, ui.user) else null,
            ui.passwordOffered,
            { actions.onChoose(KeyChoice.PASSWORD) }, m,
        )
    }
    val manual: @Composable (Modifier) -> Unit = { m ->
        Choice(
            stringResource(R.string.r_choice_manual),
            if (ui.describe) stringResource(R.string.r_choice_manual_text) else null,
            true,
            { actions.onChoose(KeyChoice.MANUAL) }, m,
        )
    }
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            password(Modifier.fillMaxWidth())
            manual(Modifier.fillMaxWidth())
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            password(Modifier.weight(1f))
            manual(Modifier.weight(1f))
        }
    }
}

@Composable
private fun Choice(label: String, description: String?, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.button)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape).alpha(if (enabled) 1f else 0.38f)
                .border(KuddeBorder.thin, c.input, shape)
                .clickable(enabled = enabled, onClick = onClick, role = androidx.compose.ui.semantics.Role.Button)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(label, style = bodyText.copy(color = c.fg, textAlign = TextAlign.Center))
        }
        description?.let { BasicText(it, style = small.copy(color = c.muted)) }
    }
}

// Step 3 (first-run-connected).

@Immutable
data class ConnectedUi(val machine: String, val version: String?, val agents: Int)

@Composable
fun ConnectedStep(ui: ConnectedUi, onBack: () -> Unit, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Frame(
        onBack = onBack, step = 3, heading = stringResource(R.string.c_heading), modifier = modifier,
        footer = { Solid(stringResource(R.string.c_continue), onContinue) },
    ) {
        Notice {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dot()
                BasicText(stringResource(R.string.c_connected, ui.machine), style = bodyText.copy(color = KuddeTheme.colors.fg))
            }
            ui.version?.let { Paragraph("herdr-core $it") }
        }
        Paragraph(
            if (ui.agents > 0) pluralStringResource(R.plurals.c_agents, ui.agents, ui.agents)
            else stringResource(R.string.c_none),
            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

// Step 4 (first-run-alerts).

/** Where alerts stand on step 4. */
sealed interface AlertsState {
    /** Not on yet: "Turn on alerts". */
    data object Before : AlertsState
    data object Waiting : AlertsState
    data object NoDistributor : AlertsState
    data object NotAllowed : AlertsState
    data class Failed(val reason: String) : AlertsState

    /** Already on on this phone: "Send test alert", nothing sent unasked. */
    data object On : AlertsState
    data object TestWaiting : AlertsState
    data object TestReceived : AlertsState
    data object TestMissed : AlertsState
}

@Immutable
data class AlertsUi(val machine: String, val state: AlertsState, val agents: Int)

data class AlertsActions(
    val onBack: () -> Unit,
    val onTurnOn: () -> Unit,
    val onAllow: () -> Unit,
    val onSendTest: () -> Unit,
    val onGuide: () -> Unit,
    val onShowAgents: () -> Unit,
    val onStartTerminal: () -> Unit,
    val onDone: () -> Unit,
)

@Composable
fun AlertsStep(ui: AlertsUi, actions: AlertsActions, modifier: Modifier = Modifier) {
    val state = ui.state
    Frame(
        onBack = actions.onBack, step = 4, heading = stringResource(R.string.al_heading), modifier = modifier,
        footer = {
            when (state) {
                AlertsState.Before -> Solid(stringResource(R.string.al_turn_on), actions.onTurnOn)
                AlertsState.NotAllowed -> Solid(stringResource(R.string.al_allow), actions.onAllow)
                AlertsState.NoDistributor, AlertsState.Waiting, is AlertsState.Failed, AlertsState.TestMissed ->
                    Solid(stringResource(R.string.f_try_again), actions.onTurnOn.takeIf { state != AlertsState.TestMissed } ?: actions.onSendTest)
                AlertsState.On -> Solid(stringResource(R.string.al_send_test), actions.onSendTest)
                AlertsState.TestWaiting, AlertsState.TestReceived -> Unit
            }
            // The routes on are always there: they are the way past alerts.
            if (ui.agents > 0) {
                RouteButton(stringResource(R.string.al_show_agents), actions.onShowAgents, solid = state == AlertsState.TestReceived)
            } else {
                RouteButton(stringResource(R.string.al_start_terminal), actions.onStartTerminal, solid = state == AlertsState.TestReceived)
                RouteButton(stringResource(R.string.al_done), actions.onDone, solid = false)
            }
        },
    ) {
        Paragraph(stringResource(R.string.al_intro))
        Paragraph(stringResource(R.string.al_needs, ui.machine))
        GuideLink(actions.onGuide)
        val live = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        when (state) {
            AlertsState.Before, AlertsState.On -> if (state == AlertsState.On) Notice(live) {
                BasicText(stringResource(R.string.al_already_on), style = bodyText.copy(color = KuddeTheme.colors.fg))
            }
            AlertsState.Waiting -> Notice(live) { BasicText(stringResource(R.string.al_waiting), style = bodyText.copy(color = KuddeTheme.colors.fg)) }
            AlertsState.NoDistributor -> Notice(live) { BasicText(stringResource(R.string.al_no_distributor), style = bodyText.copy(color = KuddeTheme.colors.fg)) }
            AlertsState.NotAllowed -> Notice(live) { BasicText(stringResource(R.string.al_not_allowed), style = bodyText.copy(color = KuddeTheme.colors.fg)) }
            is AlertsState.Failed -> Notice(live) { BasicText(stringResource(R.string.alerts_failed, state.reason), style = bodyText.copy(color = KuddeTheme.colors.fg)) }
            AlertsState.TestWaiting -> Notice(live) { BasicText(stringResource(R.string.al_test_waiting, ui.machine), style = bodyText.copy(color = KuddeTheme.colors.fg)) }
            AlertsState.TestReceived -> Notice(live) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Dot()
                    BasicText(stringResource(R.string.al_test_received), style = bodyText.copy(color = KuddeTheme.colors.fg))
                }
            }
            AlertsState.TestMissed -> Notice(live) {
                BasicText(stringResource(R.string.al_test_missed_title, ui.machine), style = bodyText.copy(color = KuddeTheme.colors.fg))
                Paragraph(stringResource(R.string.al_test_missed_detail))
            }
        }
    }
}

@Composable
private fun RouteButton(label: String, onClick: () -> Unit, solid: Boolean) =
    if (solid) Solid(label, onClick) else Outline(label, onClick)

/** The home after completion with no machine (WL11, WL12, WL7). */
@Composable
fun EmptyHome(onSetup: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(stringResource(R.string.wl_empty_title), style = KuddeType.subScreenTitle.copy(color = KuddeTheme.colors.fg, textAlign = TextAlign.Center))
        BasicText(stringResource(R.string.wl_empty_detail), style = bodyText.copy(color = KuddeTheme.colors.muted, textAlign = TextAlign.Center))
        Solid(stringResource(R.string.wl_action), onSetup)
    }
}

/** The home's unfinished-setup block (U2, U3). */
@Composable
fun UnfinishedHome(machine: String, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Notice {
            BasicText(stringResource(R.string.setup_unfinished_title, machine), style = bodyText.copy(color = KuddeTheme.colors.fg))
            Paragraph(stringResource(R.string.setup_unfinished_detail))
            Outline(stringResource(R.string.setup_continue), onContinue)
        }
    }
}
