package dev.kudde.android.ui.terminal

import dev.kudde.android.ui.agent.AgentMotion
import dev.kudde.android.ui.agents.AgentsHome
import dev.kudde.android.ui.agents.agentSheet
import dev.kudde.android.ui.agents.agentsUi
import dev.kudde.android.ui.spaces.mapCells
import uniffi.kudde_mobile.DeskPaneView
import uniffi.kudde_mobile.HubSnapshot
import uniffi.kudde_mobile.TerminalViewInterface
import android.content.Context
import android.content.res.Resources
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import dev.kudde.android.ui.hint.hintAtTop
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kudde.android.R
import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.agents.ProgramIcon
import dev.kudde.android.ui.agents.ProgramMark
import dev.kudde.android.ui.changes.changesMenuItem
import dev.kudde.android.ui.spaces.MapCell
import dev.kudde.android.ui.spaces.TabMap
import dev.kudde.android.ui.components.Chevron
import dev.kudde.android.ui.components.ChevronIcon
import dev.kudde.android.ui.components.rememberScreenReaderOn
import dev.kudde.android.ui.theme.KuddeBorder
import dev.kudde.android.ui.theme.KuddeChipShadow
import dev.kudde.android.ui.theme.KuddeLayout
import dev.kudde.android.ui.theme.KuddeRadius
import dev.kudde.android.ui.theme.KuddeSpacing
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.KuddeType
import uniffi.kudde_mobile.RowKeyTap
import uniffi.kudde_mobile.TermPhase
import kotlin.math.floor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The terminal screen (renders 3, 4, 4b, 5; terminal-view and terminal-input
// specs): the title bar, the queue strip, the grid watched at desk size or
// typed into at phone size, the status chip, the two keys rows, the
// compose sheet and the selection layer. Sizes from design-tokens.json
// (components.terminalTitleBar, queueStrip, watchingChip, sizeTag, keysRow,
// composeSheet; layout.terminalPadding, terminalFit).

/** The title bar's agent: name, mark and "folder · machine"; its state shows on the screen below, not here (issue 57). */
data class TerminalHeader(
    val name: String,
    val icon: ProgramIcon,
    val lineTwo: String,
)

/**
 * The open terminal's place in its tab, for the title bar's map (spaces spec, "The title bar
 * shows where the terminal sits"): the tab's [cells] with the open one marked, and the open
 * terminal as stop [index] (from 1) of [count], counted over the tab's stops only.
 */
data class TitleMap(val cells: List<MapCell>, val index: Int, val count: Int, val spaceId: String, val tabId: String)

/**
 * The title as the screen reader says it: name, place and, in a tab, the position
 * ("zsh, website / build · workstation, terminal 2 of 2"). Two panes of one name still
 * sound different, so a step between them is heard as a move (spaces spec, design D9).
 */
fun spokenTitle(header: TerminalHeader, map: TitleMap?, res: Resources): String {
    val named = res.getString(R.string.title_bar_moved, header.name, header.lineTwo)
    return map?.let { res.getString(R.string.title_bar_spoken_position, named, it.index, it.count) } ?: named
}

fun spokenTitle(ui: TerminalUi, res: Resources): String = spokenTitle(ui.header, ui.map, res)

/** [map]: null in a tab of one pane and for a terminal in no pane. */
data class TerminalUi(val key: AgentKey, val header: TerminalHeader, val queue: QueueStrip?, val map: TitleMap? = null)

class TerminalActions(
    val onBack: () -> Unit,
    /** Opens another agent (the queue strip). */
    val onOpen: (AgentKey) -> Unit,
    /** A vertical swipe on the title bar; false when the queue has ended. */
    val onSwipe: (forward: Boolean) -> Boolean,
    /** The phone's own grid in the current layout, on every layout. */
    val onPhoneGrid: (GridSize) -> Unit,
    /** The person zoomed or panned while watching. */
    val onWatchView: (WatchView) -> Unit = {},
    /** A sideways swipe on the title bar: the next or previous stop of the open tab; false at its end. */
    val onTab: (next: Boolean) -> Boolean = { false },
    /** True while a step must be refused: the compose sheet holds text (design D4). */
    val stepRefused: () -> Boolean = { false },
    /** The open terminal's spoken title after a step, for the screen reader's announcement (design D9). */
    val openTitle: () -> String? = { null },
    /** Speaks a screen-reader announcement; null: the view's announceForAccessibility. */
    val announce: ((String) -> Unit)? = null,
)

/** What the screen itself remembers while it is open. */
class TerminalScreenState(zoom: Float? = null) {
    /** The keyboard was asked for: the keys row shows even before the first key. */
    var inputOpen by mutableStateOf(false)
    var composeOpen by mutableStateOf(false)
    var composeFocusAsked by mutableStateOf(false)
    var composeText by mutableStateOf("")
    var selecting by mutableStateOf(false)
    var watch by mutableStateOf(WatchView(zoom ?: 1f, 0f, 0f))

    // The native offset is in the keeper's session/grid; retain fractional drags too.
    private var scrollKey: Triple<Boolean, Int?, Float>? = null
    private var scroller: RowScroller? = null
    fun rowScroller(typing: Boolean, rows: Int?, cellHeight: Float): RowScroller {
        val key = Triple(typing, rows, cellHeight)
        if (scrollKey != key) {
            scrollKey = key
            scroller = RowScroller(cellHeight)
        }
        return checkNotNull(scroller)
    }

    /** The phone leads at its own size; kept through a reattach until the core says it watches again. */
    var typing by mutableStateOf(false)

    fun follow(phase: TermPhase?) {
        when (phase) {
            TermPhase.Typing -> typing = true
            TermPhase.Watching -> typing = false
            else -> Unit
        }
    }

    /**
     * Pastes the compose text and closes the sheet, only once it was sent; the
     * sheet and its text are kept when [paste] refused it (a line break the
     * program would take as Enter, issue #22) or no terminal was attached to
     * take it (design D5).
     */
    fun sendCompose(paste: (String) -> Paste): Paste {
        if (composeText.isNotEmpty()) {
            val result = paste(composeText)
            if (result != Paste.SENT) return result
        }
        composeOpen = false
        composeText = ""
        return Paste.SENT
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    ui: TerminalUi,
    session: TerminalSession,
    screen: TerminalScreenState,
    actions: TerminalActions,
    input: TerminalInputHandle,
    modifier: Modifier = Modifier,
    /** The compose field takes focus (and the keyboard) when the sheet opens; off for screenshots. */
    composeFocus: Boolean = true,
    /** The switch to the chat view; null hides it (no log the chat can follow). */
    onChat: (() -> Unit)? = null,
    /** Opens the Changes screen, the title bar menu's item; null leaves it out. */
    onChanges: (() -> Unit)? = null,
    /** A tap on the title bar's map: opens the tab overview. */
    onMap: (() -> Unit)? = null,
    /** Whether the title bar's menu is open. */
    menuState: TitleBarMenuState = remember { TitleBarMenuState() },
    /** The title bar's hint (task 4.8): shown over the area's top while the bar has a map and it is due. */
    hint: dev.kudde.android.ui.hint.TitleBarHintSetting? = null,
    renderer: TerminalRenderer? = null,
) {
    val c = KuddeTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val phase = session.state?.phase
    LaunchedEffect(phase) { screen.follow(phase) }
    val keysShown = screen.inputOpen || screen.typing
    val keyboardUp = WindowInsets.isImeVisible
    Box(modifier.fillMaxSize().background(c.chrome)) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            TitleBar(
                ui.header, actions,
                map = ui.map, onMap = onMap,
                switch = onChat?.let { toChat -> @Composable { ViewSwitchIcon(toChat = true, toChat) } },
                menu = listOfNotNull(onChanges?.let(::changesMenuItem)),
                menuState = menuState,
            )
            ui.queue?.let { QueueStripRow(it) { actions.onOpen(it.next) } }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                TerminalArea(session, screen, actions, input, keysShown, ui.key.machine, renderer, hint.takeIf { ui.map != null })
            }
            if (keysShown) {
                KeysRow(
                    armed = session.armed,
                    onTap = session::tap,
                    onInterrupt = session::interrupt,
                    onCompose = {
                        input.hide()
                        screen.composeFocusAsked = true
                        screen.composeOpen = true
                    },
                    onKeyboard = {
                        if (keyboardUp) {
                            input.hide()
                        } else {
                            screen.inputOpen = true
                            input.show()
                        }
                    },
                )
            }
        }
        if (screen.composeOpen) {
            ComposeSheet(
                agent = ui.header.name,
                text = screen.composeText,
                onText = { screen.composeText = it },
                onCancel = {
                    screen.composeOpen = false
                    screen.composeText = ""
                },
                onPaste = {
                    composeNotSent(screen.sendCompose(session::paste))?.let {
                        Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                    }
                },
                focus = composeFocus && screen.composeFocusAsked,
            )
        }
    }
}

/** The message for compose text that was not sent; null once it was. */
@StringRes
fun composeNotSent(result: Paste): Int? = when (result) {
    Paste.SENT -> null
    Paste.REFUSED -> R.string.compose_lines_refused
    Paste.NOT_ATTACHED -> R.string.compose_not_attached
}

/** Shows and hides the terminal keyboard (TerminalInputView); a no-op in previews. */
interface TerminalInputHandle {
    fun show()
    fun hide()

    object None : TerminalInputHandle {
        override fun show() = Unit
        override fun hide() = Unit
    }
}

// --- Title bar and queue strip ---------------------------------------------

/**
 * The terminal's title bar, also the chat's (render terminal-map,
 * components.terminalTitleBar): back, the program's mark, the name over its
 * line (no status pill, issue 57), then the [gauge] (the chat's), the tab's
 * [map], the view [switch] and the [menu]'s button last. Where the width runs
 * out, the gauge gives way first, then the name ([titleBarFit]). Swipes step
 * through the open tab's panes and the agents that want you.
 */
@Composable
internal fun TitleBar(
    header: TerminalHeader,
    actions: TerminalActions,
    map: TitleMap? = null,
    onMap: (() -> Unit)? = null,
    gauge: (@Composable () -> Unit)? = null,
    switch: (@Composable () -> Unit)? = null,
    menu: List<TitleBarMenuItem> = emptyList(),
    menuState: TitleBarMenuState = remember { TitleBarMenuState() },
) {
    val c = KuddeTheme.colors
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current
    var shownHeader by remember { mutableStateOf(header) }
    val titleOpacity = remember { Animatable(1f) }
    LaunchedEffect(header) {
        if (shownHeader != header) {
            if (animationsOn(context)) titleOpacity.animateTo(0f, tween(AgentMotion.titleFadeMs))
            shownHeader = header
            titleOpacity.snapTo(1f)
        }
    }
    val threshold = with(density) { SWIPE_STEP.toPx() }
    val bounceBy = with(density) { TITLE_BAR_BOUNCE_DISTANCE.toPx() }
    // The bar's content, moved at a tab's end and sprung back (design D3); the background stays.
    val bounce = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // Plays a step's result: the haptic, and the bounce at a tab's end (design D3, D4).
    val play: (TitleBarStep?) -> Unit = { step ->
        if (step != null) {
            view.performHapticFeedback(titleBarHaptic(step))
        }
        val direction = (step as? TitleBarStep.End)?.bounce
        if (direction != null) {
            scope.launch {
                bounce.snapTo(0f)
                bounce.animateTo(
                    if (direction == Bounce.LEFT) -bounceBy else bounceBy,
                    tween(TITLE_BAR_BOUNCE_MS / 3, easing = FastOutSlowInEasing),
                )
                bounce.animateTo(0f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium))
            }
        }
    }
    // The four screen-reader actions do what the four swipes do and say what came of it (design D9).
    val tabEnd = stringResource(R.string.title_bar_tab_end)
    val noneNeedYou = stringResource(R.string.title_bar_none_need_you)
    val refusedText = stringResource(R.string.title_bar_step_refused)
    val stepActions = listOf(
        R.string.title_bar_next_terminal to (true to true),
        R.string.title_bar_previous_terminal to (true to false),
        R.string.title_bar_next_needs_you to (false to true),
        R.string.title_bar_previous_needs_you to (false to false),
    ).map { (label, how) ->
        val (sideways, forward) = how
        CustomAccessibilityAction(stringResource(label)) {
            // Finger left is next, finger up is forward: as the swipe in that direction.
            val towards = if (forward) -1f else 1f
            val step = titleBarStep(
                dx = if (sideways) towards else 0f,
                dy = if (sideways) 0f else towards,
                threshold = 1f,
                refused = actions.stepRefused(),
                animationsOn = animationsOn(context),
                onTab = actions.onTab,
                onSwipe = actions.onSwipe,
            )
            play(step)
            val said = when (step) {
                TitleBarStep.Moved -> actions.openTitle()
                is TitleBarStep.End -> if (sideways) tabEnd else noneNeedYou
                TitleBarStep.Refused -> refusedText
                null -> null
            }
            if (said != null) (actions.announce ?: view::announceForAccessibility)(said)
            true
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            // Grows with large font sizes, so the place line is never cut (accessibility spec).
            .heightIn(min = KuddeLayout.terminalTitleHeight)
            .background(c.chrome)
            .pointerInput(actions) {
                // Up and down step through the agents that want you; sideways through
                // the open tab's panes. The longer direction of the drag decides, and
                // the result is decided by titleBarStep (design D4), only played here.
                // On the whole row, so a drag that starts on a button steps as well.
                var dx = 0f
                var dy = 0f
                detectDragGestures(
                    onDragStart = {
                        dx = 0f
                        dy = 0f
                    },
                    onDragEnd = {
                        val step = titleBarStep(
                            dx, dy, threshold,
                            refused = actions.stepRefused(),
                            animationsOn = animationsOn(context),
                            onTab = actions.onTab,
                            onSwipe = actions.onSwipe,
                        )
                        play(step)
                    },
                ) { change, drag ->
                    change.consume()
                    dx += drag.x
                    dy += drag.y
                }
            }
            // `.qbar`: 4 dp at either end.
            .padding(horizontal = KuddeSpacing.s4)
            .graphicsLayer { translationX = bounce.value },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val back = stringResource(R.string.back)
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(KuddeRadius.button))
                .clickable(role = Role.Button, onClick = actions.onBack)
                .semantics { contentDescription = back },
            contentAlignment = Alignment.Center,
        ) {
            ChevronIcon(Chevron.Left, 22.dp, c.fg)
        }
        // A layer only while fading, and no wrapper: at rest the title must draw and lay out
        // exactly as it did without motion.
        val fade = if (titleOpacity.value < 1f) Modifier.graphicsLayer { alpha = titleOpacity.value } else Modifier
        ProgramMark(shownHeader.icon, Modifier.padding(start = 6.dp, end = 12.dp).then(fade), size = 20.dp)
        NameAndGauge(shownHeader, map, stepActions, gauge, Modifier.weight(1f).then(fade))
        Spacer(Modifier.width(6.dp))
        map?.let { TitleMapButton(it, onMap) }
        switch?.invoke()
        if (menu.isNotEmpty()) TitleBarMenu(menu, menuState)
    }
}

/**
 * The name over its line, and the gauge at the end of the room they share.
 * The gauge is measured first, in a probe that is never placed nor spoken;
 * [titleBarFit] decides whether it shows, and the name gets the rest.
 */
@Composable
private fun NameAndGauge(
    header: TerminalHeader,
    map: TitleMap?,
    stepActions: List<CustomAccessibilityAction>,
    gauge: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    val c = KuddeTheme.colors
    val measurer = rememberTextMeasurer()
    // Read through state inside the slot: the subcomposition does not recompose when only the position changes.
    val spoken = rememberUpdatedState(spokenTitle(header, map, LocalContext.current.resources))
    SubcomposeLayout(modifier) { constraints ->
        val room = constraints.maxWidth
        val gap = 6.dp.roundToPx()
        val gaugeWidth = gauge?.let { slot ->
            subcompose("probe") { Box(Modifier.clearAndSetSemantics {}) { slot() } }
                .first().measure(Constraints()).width + gap
        }
        fun width(text: String) = measurer.measure(text, KuddeType.terminalTitle, maxLines = 1, softWrap = false).size.width
        val name = header.name
        val eight = if (name.length > 8) width(name.take(8) + "…") else width(name)
        val fit = titleBarFit(room, gaugeWidth, eight, width(name.take(1) + "…"))
        val column = subcompose("name") {
            // One node for the name, its line and the four steps; Back and the trailing controls stay their own.
            val said = spoken.value
            Column(
                // One stop that says only this, as the step announcement says it, position included
                // (design D9); the name and line below are not read a second time.
                Modifier.clearAndSetSemantics {
                    customActions = stepActions
                    contentDescription = said
                },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                BasicText(header.name, style = KuddeType.terminalTitle.copy(color = c.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicText(header.lineTwo, style = KuddeType.secondary.copy(color = c.dimText), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }.first().measure(Constraints(maxWidth = fit.nameWidth, maxHeight = constraints.maxHeight))
        val shown = if (fit.gauge && gauge != null) {
            subcompose("gauge", gauge).first().measure(Constraints(maxHeight = constraints.maxHeight))
        } else {
            null
        }
        val height = maxOf(column.height, shown?.height ?: 0, constraints.minHeight)
        layout(room, height) {
            column.placeRelative(0, (height - column.height) / 2)
            shown?.placeRelative(room - shown.width, (height - shown.height) / 2)
        }
    }
}

/** The tab's map as a 48 dp button (components.tabMap, titleBar), spoken "Tab overview, terminal 2 of 3". */
@Composable
private fun TitleMapButton(map: TitleMap, onMap: (() -> Unit)?) {
    val spoken = stringResource(R.string.title_bar_map, map.index, map.count)
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(KuddeRadius.button))
            .clickable(role = Role.Button) { onMap?.invoke() }
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        TabMap(map.cells)
    }
}

/** The system's animations are on (`ANIMATOR_DURATION_SCALE` not 0), read as ChatRoute reads it. */
private fun animationsOn(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/** `.queue`: 34 dp, the blocked edge, "N more needs you · next: <agent>, <kind>"; a tap opens that agent. */
@Composable
private fun QueueStripRow(strip: QueueStrip, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    val label = stringResource(R.string.terminal_queue_open)
    val rest = strip.nextKind?.let { stringResource(R.string.terminal_queue_next, strip.nextName, it.lowercase()) }
        ?: stringResource(R.string.terminal_queue_next_plain, strip.nextName)
    Row(
        Modifier
            .fillMaxWidth()
            .height(KuddeLayout.queueStripHeight)
            .drawBehind {
                drawRect(blockedMix(c.blocked, c.chrome))
                drawRect(c.border, size = size.copy(height = KuddeBorder.thin.toPx()))
                drawRect(c.blocked, size = size.copy(width = KuddeBorder.signature.toPx()))
            }
            .cancelTapOnDrag()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(start = 16.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KuddeSpacing.s8),
    ) {
        BasicText(
            pluralStringResource(R.plurals.terminal_queue_count, strip.count, strip.count),
            style = KuddeType.secondary.copy(color = c.blocked, fontWeight = FontWeight.W600),
            maxLines = 1,
        )
        BasicText(
            rest,
            Modifier.weight(1f),
            style = KuddeType.secondary.copy(color = c.muted),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ChevronIcon(Chevron.Up, 16.dp, c.muted)
    }
}

/**
 * A press that moves past touch slop is a drag, never a tap (spaces spec, "Steps start only on the
 * title bar"): the drag is consumed before the clickable below sees it, which cancels its click.
 * A clickable alone keeps the press while the finger stays inside its wide bounds.
 */
internal fun Modifier.cancelTapOnDrag(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var dragged = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!dragged && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
            if (dragged) event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/** semantic.queueStripBackground: blocked mixed 9% into chrome. */
private fun blockedMix(blocked: Color, chrome: Color) = Color(
    red = chrome.red + (blocked.red - chrome.red) * 0.09f,
    green = chrome.green + (blocked.green - chrome.green) * 0.09f,
    blue = chrome.blue + (blocked.blue - chrome.blue) * 0.09f,
)

// --- The terminal ------------------------------------------------------------

@Composable
private fun TerminalArea(
    session: TerminalSession,
    screen: TerminalScreenState,
    actions: TerminalActions,
    input: TerminalInputHandle,
    keysShown: Boolean,
    machine: String,
    heldRenderer: TerminalRenderer?,
    hint: dev.kudde.android.ui.hint.TitleBarHintSetting? = null,
) {
    val c = KuddeTheme.colors
    val density = LocalDensity.current
    val renderer = heldRenderer ?: rememberTerminalRenderer()
    val label = stringResource(R.string.terminal_label)
    // A screen reader hears the visible text (accessibility spec), gathered at most once a second and only while one is on.
    val readerOn = rememberScreenReaderOn()
    var spoken by remember(session) { mutableStateOf("") }
    if (readerOn) {
        LaunchedEffect(session) {
            var seen = -1
            while (true) {
                if (session.version != seen) {
                    seen = session.version
                    spoken = if (session.grid.isEmpty) "" else spokenScreen(session.grid.text())
                }
                delay(SPOKEN_REFRESH_MS)
            }
        }
    }
    val described = if (readerOn && spoken.isNotEmpty()) "$label\n$spoken" else label
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val padX = with(density) { KuddeLayout.terminalPaddingX.toPx() }
        val padTop = with(density) { KuddeLayout.terminalPaddingTop.toPx() }
        val phoneFont = with(density) { KuddeType.terminalPhone.fontSize.toPx() }
        // The phone's grid always leaves room for the two keys rows, which typing shows.
        val keysPx = if (keysShown) 0f else with(density) { KuddeLayout.keysRowHeight.toPx() } * 2
        val phone = phoneGrid(widthPx, heightPx - keysPx, padX, padTop, phoneFont)
        SideEffect { actions.onPhoneGrid(phone) }

        // Only the grid's size and whether it is live recompose; a frame alone redraws the canvas.
        val shape = session.shape
        val grid = session.grid
        val watch = if (screen.typing || shape == null) {
            null
        } else {
            WatchLayout(widthPx, heightPx, padX, padTop, shape.cols, shape.rows, phoneFont)
        }
        val view = watch?.clamp(screen.watch) ?: WatchView.Fit
        val scroller = screen.rowScroller(screen.typing, shape?.rows, phoneFont * TerminalMetrics.LINE_EM)
        Canvas(
            Modifier
                .fillMaxSize()
                // A desk-sized grid while typing starts, or a zoomed one, never draws over the bars.
                .clipToBounds()
                .semantics { contentDescription = described }
                // Keyed by what the handlers act on: moving to another agent's terminal swaps them.
                .pointerInput(session, screen, screen.typing, padX, padTop, phoneFont) {
                    detectTapGestures(
                        // A tap reads and clicks; the keyboard has its own key. At the phone's
                        // size the cell under the finger is the program's: a program that takes
                        // clicks gets one, as on the desk. Watching draws the desk's size, which
                        // the takeover changes, so a tap there only takes over at phone size.
                        onTap = { at ->
                            if (screen.typing) {
                                val col = floor((at.x - padX) / (phoneFont * TerminalMetrics.ADVANCE_EM)).toInt()
                                val row = floor((at.y - padTop) / (phoneFont * TerminalMetrics.LINE_EM)).toInt()
                                session.click(col.coerceAtLeast(0), row.coerceAtLeast(0))
                            } else {
                                session.lead()
                            }
                        },
                        onLongPress = { screen.selecting = true },
                    )
                }
                .pointerInput(session, screen, actions, screen.typing, shape?.cols, shape?.rows, widthPx, heightPx) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        var dy = pan.y
                        if (watch != null) {
                            var v = watch.clamp(screen.watch)
                            if (zoom != 1f) v = watch.zoomBy(v, zoom, centroid.x, centroid.y)
                            val (moved, rest) = watch.panBy(v, pan.x, pan.y)
                            if (moved != screen.watch) {
                                screen.watch = moved
                                actions.onWatchView(moved)
                            }
                            dy = rest
                        }
                        session.scroll(scroller.drag(dy))
                    }
                },
        ) {
            @Suppress("UNUSED_EXPRESSION")
            session.version
            drawRect(if (grid.isEmpty) c.bg else Color(0xFF000000 or grid.defaultBg.toLong()))
            if (grid.isEmpty) return@Canvas
            val damage = session.damage.take()
            if (watch == null) {
                translate(padX, padTop) {
                    drawIntoCanvas { renderer.draw(it.nativeCanvas, grid, damage, phoneFont) }
                }
            } else {
                translate(view.panX, view.panY) {
                    scale(view.zoom, Offset.Zero) {
                        translate(padX, padTop) {
                            drawIntoCanvas { renderer.draw(it.nativeCanvas, grid, damage, watch.fitFontPx) }
                        }
                    }
                }
                // Below the desk's grid: the terminal's own background, painted above.
            }
        }
        StatusChip(session, screen, machine, grid, input)
        if (hint != null) {
            // The cursor row as drawn, in px from this area's top, so the hint can leave it uncovered (design D11).
            val cursorRow by remember(session) { derivedStateOf { session.version.let { session.grid.takeIf { g -> g.cursorShown && !g.isEmpty }?.cursorY ?: -1 } } }
            var hintHeight by remember { mutableIntStateOf(0) }
            val margin = with(density) { 8.dp.toPx() }
            val lineHeight = (watch?.fitFontPx ?: phoneFont) * TerminalMetrics.LINE_EM
            val cursorTop = if (cursorRow < 0) null else if (watch == null) padTop + cursorRow * lineHeight
                else view.panY + view.zoom * (padTop + cursorRow * lineHeight)
            val cursorBottom = cursorTop?.let { it + lineHeight * (if (watch == null) 1f else view.zoom) }
            val atTop = hintAtTop(cursorTop, cursorBottom, margin + hintHeight)
            dev.kudde.android.ui.hint.TitleBarHintCard(
                visible = !hint.seen,
                onClose = hint::close,
                modifier = Modifier
                    .align(if (atTop) Alignment.TopCenter else Alignment.BottomCenter)
                    .padding(top = 8.dp, bottom = if (screen.typing) 8.dp else 64.dp)
                    .onSizeChanged { hintHeight = it.height },
            )
        }
        if (screen.typing && shape != null) SizeTag(shape.cols, shape.rows)
        if (shape != null && !shape.live) {
            LiveControl(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = KuddeSpacing.s16, bottom = if (screen.typing) 16.dp else 64.dp),
            ) {
                scroller.reset()
                session.live()
            }
        }
        if (screen.selecting && shape != null) {
            // The screen as it was when the long press opened the layer.
            val text = remember(grid) { grid.text() }
            SelectionLayer(text, grid) { screen.selecting = false }
        }
    }
}

@Composable
private fun rememberTerminalRenderer(): TerminalRenderer {
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    return remember(res) {
        TerminalRenderer(res.getFont(R.font.jetbrains_mono_regular), res.getFont(R.font.jetbrains_mono_bold))
    }
}

/** The bottom chip: watching at desk size, or the state while the terminal is not live. */
@Composable
private fun BoxScope.StatusChip(
    session: TerminalSession,
    screen: TerminalScreenState,
    machine: String,
    grid: TerminalGrid,
    input: TerminalInputHandle,
) {
    val c = KuddeTheme.colors
    val state = session.state
    val text = when (val phase = state?.phase) {
        TermPhase.Typing -> return
        TermPhase.Watching -> if (screen.inputOpen) return else null
        TermPhase.Waiting -> stringResource(R.string.terminal_waiting, machine)
        is TermPhase.Ended -> phase.reason.takeIf { it.isNotBlank() }?.let { stringResource(R.string.terminal_ended_reason, it) }
            ?: stringResource(R.string.terminal_ended)
        is TermPhase.Failed -> stringResource(R.string.terminal_failed, phase.detail)
        TermPhase.Attaching, null -> stringResource(R.string.terminal_attaching)
    }
    val shape = RoundedCornerShape(KuddeRadius.chip)
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(start = KuddeSpacing.s16, end = KuddeSpacing.s16, bottom = KuddeSpacing.s16)
            .heightIn(min = 40.dp)
            .dropShadow(shape, KuddeChipShadow)
            .clip(shape)
            .background(c.popover)
            .border(KuddeBorder.thin, c.border, shape)
            .clickable(role = Role.Button) {
                // Ends a step's wait, so the keyboard types into an attached terminal.
                session.wantInput()
                screen.inputOpen = true
                input.show()
            }
            .padding(horizontal = KuddeSpacing.s16, vertical = KuddeSpacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KuddeSpacing.s8),
    ) {
        if (text == null) {
            LineIconImage(LineIcon.Eye, c.muted, 16.dp)
            val cols = state?.cols?.toInt()?.takeIf { it > 0 } ?: grid.cols
            val rows = state?.rows?.toInt()?.takeIf { it > 0 } ?: grid.rows
            val bold = stringResource(R.string.terminal_watching)
            val rest = stringResource(R.string.terminal_watching_rest, cols, rows)
            BasicText(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.fg, fontWeight = FontWeight.W500)) { append(bold) }
                    append(' ')
                    append(rest)
                },
                style = chipText.copy(color = c.muted),
                maxLines = 1,
            )
        } else {
            BasicText(text, style = chipText.copy(color = c.muted), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** `.chip`: 13.5 sp Geist (render 3). */
private val chipText = KuddeType.secondary.copy(fontSize = 13.5.sp)

/** `.sizetag`: "phone size CxR", 11.5 dim on canvas, top right of the terminal. */
@Composable
private fun BoxScope.SizeTag(cols: Int, rows: Int) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.pill)
    BasicText(
        stringResource(R.string.terminal_phone_size, cols, rows),
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = KuddeSpacing.s6, end = KuddeSpacing.s8)
            .background(c.canvas, shape)
            .border(KuddeBorder.thin, c.border, shape)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        style = KuddeType.secondary.copy(fontSize = 11.5.sp, color = c.dimText),
        maxLines = 1,
    )
}

/** "Live": back to the bottom while scrolled up (terminal-view spec, "Scrollback with a way back to live"). */
@Composable
private fun LiveControl(modifier: Modifier, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.chip)
    Row(
        modifier
            .heightIn(min = 40.dp)
            .dropShadow(shape, KuddeChipShadow)
            .clip(shape)
            .background(c.popover)
            .border(KuddeBorder.thin, c.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BasicText(stringResource(R.string.terminal_live), style = chipText.copy(color = c.fg, fontWeight = FontWeight.W500))
        ChevronIcon(Chevron.Down, 16.dp, c.fg)
    }
}

/**
 * The selection layer (terminal-view spec, "Select and copy from the local
 * screen"): the view's text from the local grid, never `terminal.read`, at
 * phone size in the terminal's colours, with the platform's selection handles
 * and Copy action.
 */
@Composable
private fun SelectionLayer(text: String, grid: TerminalGrid, onDone: () -> Unit) {
    val c = KuddeTheme.colors
    val bg = Color(0xFF000000 or grid.defaultBg.toLong())
    val fg = Color(0xFF000000 or grid.defaultFg.toLong())
    Column(Modifier.fillMaxSize().background(bg).clickable(remember { MutableInteractionSource() }, null) {}) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.chrome)
                .padding(start = KuddeSpacing.s16, end = KuddeSpacing.s8)
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(stringResource(R.string.terminal_select_title), Modifier.weight(1f), style = KuddeType.secondary.copy(color = c.muted))
            BasicText(
                stringResource(R.string.terminal_select_done),
                Modifier
                    .clip(RoundedCornerShape(KuddeRadius.button))
                    .clickable(role = Role.Button, onClick = onDone)
                    .padding(horizontal = KuddeSpacing.s12, vertical = KuddeSpacing.s12),
                style = KuddeType.settingRow.copy(color = c.fg, fontWeight = FontWeight.W500),
            )
        }
        SelectionContainer {
            BasicText(
                text,
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = KuddeLayout.terminalPaddingX, vertical = KuddeLayout.terminalPaddingTop),
                style = KuddeType.terminalPhone.copy(color = fg),
                softWrap = false,
            )
        }
    }
}

// --- Keys row ----------------------------------------------------------------

internal sealed interface RowKey {
    data class Tap(val label: String, val key: RowKeyTap, val description: Int? = null) : RowKey
    data object Keyboard : RowKey
    data object Compose : RowKey
}

/**
 * The two keys rows (design-tokens.json `components.keysRow`, 2026-11-03),
 * Termux-like: modifiers and the stop and enter keys on the left, away from
 * the arrows; the keyboard key in the middle of the top row, compose under it.
 */
internal val KEY_LINES: List<List<RowKey>> = listOf(
    listOf(
        RowKey.Tap("esc", RowKeyTap.Esc, R.string.key_esc),
        RowKey.Tap("ctrl", RowKeyTap.Ctrl, R.string.key_ctrl),
        RowKey.Tap("alt", RowKeyTap.Alt, R.string.key_alt),
        RowKey.Tap("tab", RowKeyTap.Tab, R.string.key_tab),
        RowKey.Keyboard,
        RowKey.Tap("-", RowKeyTap.Symbol("-"), R.string.key_dash),
        RowKey.Tap("/", RowKeyTap.Symbol("/"), R.string.key_slash),
        RowKey.Tap("↑", RowKeyTap.Up, R.string.key_up),
        RowKey.Tap("~", RowKeyTap.Symbol("~"), R.string.key_tilde),
    ),
    listOf(
        RowKey.Tap("^C", RowKeyTap.CtrlC, R.string.key_ctrl_c),
        RowKey.Tap("⇧⇥", RowKeyTap.ShiftTab, R.string.key_shift_tab),
        RowKey.Tap("\\", RowKeyTap.Symbol("\\"), R.string.key_backslash),
        RowKey.Tap("⏎", RowKeyTap.Enter, R.string.key_enter),
        RowKey.Compose,
        RowKey.Tap("|", RowKeyTap.Symbol("|"), R.string.key_pipe),
        RowKey.Tap("←", RowKeyTap.Left, R.string.key_left),
        RowKey.Tap("↓", RowKeyTap.Down, R.string.key_down),
        RowKey.Tap("→", RowKeyTap.Right, R.string.key_right),
    ),
)

/**
 * `.keys`: two rows of nine, 48 dp each, on chrome, always both while typing.
 * Ctrl and alt latch (solid) while held for the next key; a long press on
 * esc interrupts the agent; the keyboard key shows and hides the keyboard.
 */
@Composable
private fun KeysRow(
    armed: uniffi.kudde_mobile.Armed,
    onTap: (RowKeyTap) -> Unit,
    onInterrupt: () -> Unit,
    onCompose: () -> Unit,
    onKeyboard: () -> Unit,
) {
    val c = KuddeTheme.colors
    val view = LocalView.current
    Column(Modifier.fillMaxWidth().background(c.chrome)) {
        for (line in KEY_LINES) {
            KeyLine {
                for (key in line) {
                    when (key) {
                        RowKey.Keyboard -> IconKey(LineIcon.Keyboard, R.string.key_keyboard, outlined = false, onClick = onKeyboard)
                        RowKey.Compose -> IconKey(LineIcon.Pen, R.string.key_compose, outlined = true, onClick = onCompose)
                        is RowKey.Tap -> {
                            val latched = (key.key == RowKeyTap.Ctrl && armed.ctrl) || (key.key == RowKeyTap.Alt && armed.alt)
                            val hold = if (key.key == RowKeyTap.Esc) {
                                {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    onInterrupt()
                                }
                            } else {
                                null
                            }
                            Key(key.label, key.description, latched, onClick = { onTap(key.key) }, onLongClick = hold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyLine(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val c = KuddeTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(KuddeLayout.keysRowHeight)
            .drawBehind { drawRect(c.border, size = size.copy(height = KuddeBorder.thin.toPx())) }
            .padding(horizontal = 6.dp, vertical = KuddeSpacing.s4),
        horizontalArrangement = Arrangement.spacedBy(KuddeSpacing.s4),
        content = content,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Key(
    label: String,
    description: Int?,
    latched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val c = KuddeTheme.colors
    val spoken = description?.let { stringResource(it) }
    Box(
        Modifier
            .weight(1f)
            .height(40.dp)
            .clip(RoundedCornerShape(KuddeRadius.key))
            .background(if (latched) c.solid else c.raised)
            .combinedClickableCompat(onClick, onLongClick)
            .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        // At large font sizes a label shrinks to fit its key rather than being cut ("ctr").
        BasicText(
            label,
            // The key's spoken name says it; read the label too and TalkBack says "Dash, dash".
            modifier = if (spoken != null) Modifier.clearAndSetSemantics {} else Modifier,
            style = KuddeType.keysRow.copy(color = if (latched) c.solidFg else c.fg),
            maxLines = 1,
            // No wrapping: a label too wide must count as overflowing its width, which shrinks it.
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = KEY_MIN_FONT, maxFontSize = KuddeType.keysRow.fontSize),
        )
    }
}

/** An icon key: the keyboard key (raised, like the others) or compose (outlined with the input colour). */
@Composable
private fun androidx.compose.foundation.layout.RowScope.IconKey(icon: LineIcon, description: Int, outlined: Boolean, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.key)
    val spoken = stringResource(description)
    Box(
        Modifier
            .weight(1f)
            .height(40.dp)
            .clip(shape)
            .then(if (outlined) Modifier.border(KuddeBorder.thin, c.input, shape) else Modifier.background(c.raised))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        LineIconImage(icon, c.fg, 18.dp)
    }
}

private fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    this.combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = onClick)

private val SWIPE_STEP = 40.dp

/** How often the text a screen reader hears from the terminal is gathered again. */
private const val SPOKEN_REFRESH_MS = 1_000L

/** The smallest a keys row label shrinks to at large font sizes. */
private val KEY_MIN_FONT = 9.sp

/** The title bar and queue strip for [key], from the hub's rows; the tab's map from its machine's layout. */
fun terminalUi(snapshot: HubSnapshot, key: AgentKey, res: android.content.res.Resources): TerminalUi {
    val sheet = agentSheet(agentsUi(snapshot, 0L, res, { "" }), key, res)
    val header = sheet?.let { TerminalHeader(it.name, it.icon, it.lineTwo) }
        ?: TerminalHeader(key.address, ProgramIcon.Terminal, key.machine)
    return TerminalUi(key, header, queueStrip(snapshot.agents, key), titleMap(snapshot, key))
}

/**
 * The tab that holds [key]'s pane, as the title bar's map draws it (spaces
 * spec, "The title bar shows where the terminal sits"): null for a terminal in
 * no pane (loose, a sub-agent's vacant pane, no layout). A tab of one pane
 * shows one marked cell. The last layout read stays while the machine is out
 * of reach, as in the Spaces view. Counted over the stops: the terminal cells
 * the phone lists.
 */
fun titleMap(snapshot: HubSnapshot, key: AgentKey): TitleMap? {
    val desk = snapshot.machines.firstOrNull { it.name == key.machine }?.desk ?: return null
    val attention = snapshot.agents.filter { it.machine == key.machine }.associate { it.address to it.attention }
    val listed = attention.keys + snapshot.others.filter { it.machine == key.machine }.map { it.address }
    for (space in desk.spaces) for (tab in space.tabs) {
        val addresses = tab.cells.mapNotNull { (it.pane as? DeskPaneView.Terminal)?.address }
        if (key.address !in addresses) continue
        val stops = addresses.filter { it in listed }
        return TitleMap(mapCells(tab, { attention[it] }, marked = key.address), stops.indexOf(key.address) + 1, stops.size, space.id, tab.id)
    }
    return null
}

/**
 * Opens the terminal once the screen first knows the phone's grid (the core
 * needs it to type at phone size), then hands later sizes to a [SizeSettler],
 * so the phone size goes out once the keyboard has settled.
 *
 * A terminal reached by a step waits [dwellMs] ([STEP_DWELL_MS]) from
 * [arrivalMs] before it is opened (design D5): it opens on the first
 * [onPhoneGrid] or [tick] at or after [dueAtMs], or at once on [openNow] (the
 * person's input). Passing through never attaches, so the desk keeps its sizes.
 * Once [dispose]d (the terminal was left) it never opens.
 */
class TerminalOpener(
    private val session: TerminalSession,
    arrivalMs: Long = 0,
    dwellMs: Long = 0,
    private val open: (GridSize) -> TerminalViewInterface?,
) {
    private var settler: SizeSettler? = null
    private var grid: GridSize? = null
    private var disposed = false

    /** When the wait ends; the route ticks then. */
    val dueAtMs: Long = arrivalMs + dwellMs

    fun onPhoneGrid(size: GridSize, nowMs: Long) {
        val settle = settler
        if (settle != null) {
            settle.offer(size, nowMs)
            return
        }
        grid = size
        if (nowMs >= dueAtMs) openAt(size)
    }

    fun tick(nowMs: Long) {
        val settle = settler
        if (settle != null) {
            settle.tick(nowMs)
            return
        }
        val size = grid ?: return
        if (nowMs >= dueAtMs) openAt(size)
    }

    /** Input during the wait: opens now if the phone's grid is known. */
    @Suppress("UNUSED_PARAMETER")
    fun openNow(nowMs: Long) {
        if (settler != null) return
        openAt(grid ?: return)
    }

    fun dispose() {
        disposed = true
    }

    private fun openAt(size: GridSize) {
        if (disposed) return
        // Without a running hub there is nothing to open yet; the next layout or input tries again.
        val view = open(size) ?: return
        session.attach(view)
        settler = SizeSettler(TerminalMetrics.SETTLE_MS, opened = size, send = session::phoneSize)
    }

    companion object {
        /** How long a terminal reached by a step shows its last screen before it is opened. */
        const val STEP_DWELL_MS = 400L
    }
}

/**
 * The route's title-bar actions, shared by the terminal and the chat over it: a
 * step in any direction is refused while [screen]'s compose sheet holds text
 * (design D4), so a draft is never left behind by a swipe.
 */
internal fun terminalActions(
    screen: TerminalScreenState,
    onBack: () -> Unit,
    onOpen: (AgentKey) -> Unit,
    onSwipe: (forward: Boolean) -> Boolean,
    onTab: (next: Boolean) -> Boolean,
    onPhoneGrid: (GridSize) -> Unit,
    onWatchView: (WatchView) -> Unit = {},
    openTitle: () -> String? = { null },
    announce: ((String) -> Unit)? = null,
) = TerminalActions(
    onBack = onBack,
    onOpen = onOpen,
    onSwipe = onSwipe,
    onPhoneGrid = onPhoneGrid,
    onWatchView = onWatchView,
    onTab = onTab,
    stepRefused = { screen.composeText.isNotEmpty() },
    openTitle = openTitle,
    announce = announce,
)
