package dev.kudde.android.ui.terminal

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.kudde.android.ui.agent.AgentPorts
import dev.kudde.android.ui.agent.HubPorts
import dev.kudde.android.KuddeApp
import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.agents.AgentsHome
import dev.kudde.android.ui.agents.ProgramIcon
import dev.kudde.android.ui.agents.agentSheet
import dev.kudde.android.ui.agents.agentsUi
import dev.kudde.android.ui.chat.ChatPolling
import dev.kudde.android.ui.changes.ChangesRoute
import dev.kudde.android.ui.chat.ChatRoute
import dev.kudde.android.ui.chat.rememberChatVisit
import dev.kudde.android.ui.components.undrawnWhen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import dev.kudde.android.ui.spaces.TabOverviewRoute
import dev.kudde.android.ui.spaces.mapCells
import uniffi.kudde_mobile.DeskPaneView
import uniffi.kudde_mobile.HubSnapshot
import uniffi.kudde_mobile.TerminalViewInterface

/**
 * The terminal screen fed by the app: opens [key] through the hub once the
 * screen knows the phone's grid, shows the cached last screen meanwhile,
 * sends the phone size once the keyboard settles, keeps the zoom, and on
 * leaving detaches and caches the last screen. With Stay connected on, 60 s
 * in the background detaches it as well and the return opens it again
 * ([TerminalReopen]). The queue strip and the title-bar swipe open other
 * agents through [home], which marks them seen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalRoute(app: KuddeApp, home: AgentsHome, key: AgentKey, ports: AgentPorts = HubPorts(app)) {
    val context = LocalContext.current
    val res = context.resources
    val snapshot by app.hubHost.snapshot.collectAsState()
    val reopen = remember(key) { TerminalReopen() }
    val generation = reopen.generation
    val session = remember(key, generation) { TerminalSession(key, context.mainExecutor) }
    val screen = remember(key) { TerminalScreenState(app.terminalZoom[key]) }
    // The chat view (issue 17): over the live terminal, so switching back is instant.
    val chat = rememberChatVisit(app, key, ports::openChat)
    val switch = chat.switch
    // The Changes screen (issue 19) over either view; nothing is sent to the terminal for it.
    var changesOpen by remember(key) { mutableStateOf(false) }
    // The tab overview (spaces spec), opened from the title bar's map, over either view: its space and tab.
    var overview by remember(key) { mutableStateOf<Pair<String, String>?>(null) }
    // While it shows, the phone's grid (it grows when the keyboard hides) is held, not
    // sent: the desk keeps its size under a permission prompt. Back applies the last one.
    var heldGrid by remember(key) { mutableStateOf<GridSize?>(null) }
    val opener = remember(key, generation) {
        // Reached by a step: wait before taking the desk's terminal (design D5). A reopen
        // after the background does not wait; nor does an open from a row, the strip or an alert.
        val dwell = if (generation == 0 && home.arrivedByStep) TerminalOpener.STEP_DWELL_MS else 0L
        TerminalOpener(session, arrivalMs = ports.now(), dwellMs = dwell) { size ->
            // Opened at the phone's size (phone check round 2) unless the chat shows over it;
            // closing lets the desk take its size back.
            val lead = atPhoneSizeOnOpen(switch.chat)
            ports.openTerminal(key, size.cols.toUShort(), size.rows.toUShort(), lead, session.listener)
        }
    }
    // Input during the wait opens the terminal first, then goes to it (design D5).
    DisposableEffect(opener) {
        session.onInputWithoutView = { opener.openNow(ports.now()) }
        onDispose { opener.dispose() }
    }
    LaunchedEffect(opener) {
        val wait = opener.dueAtMs - ports.now()
        if (wait > 0) delay(wait)
        opener.tick(ports.now())
    }
    var phone by remember(key) { mutableStateOf<GridSize?>(null) }
    // A reopened generation opens again at the last phone size.
    LaunchedEffect(phone, generation) {
        val size = phone ?: return@LaunchedEffect
        opener.onPhoneGrid(size, ports.now())
        delay(TerminalMetrics.SETTLE_MS)
        opener.tick(ports.now())
    }

    // Instant open: the last screen, until the live first paint replaces it; after a
    // background detach, the frame kept in memory, with no disk load or Keystore call.
    DisposableEffect(key, generation) {
        var cancelled = false
        val seed = reopen.takeSeed()
        if (seed != null) {
            session.showCached(seed)
        } else {
            app.terminalCacheExecutor.execute {
                val frame = app.terminalCache.load(key) ?: return@execute
                context.mainExecutor.execute { if (!cancelled) session.showCached(frame) }
            }
        }
        onDispose {
            cancelled = true
            val last = session.close()
            if (last != null) app.terminalCacheExecutor.execute { app.terminalCache.store(key, last) }
        }
    }
    // Background idle (Stay connected): detach while stopped and keep the last frame;
    // collected as a coroutine, as nothing composes while the activity is stopped.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(key, generation) {
        app.terminalIdle.drop(1).collect {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@collect
            reopen.onIdle {
                session.close()?.also { last ->
                    app.terminalCacheExecutor.execute { app.terminalCache.store(key, last) }
                }
            }
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { reopen.onStart() }

    // Leaving the app may be the last chance to keep the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        reopen.onStop()
        if (session.painted) {
            session.grid.toFullFrameOrNull()?.let { frame ->
                app.terminalCacheExecutor.execute { app.terminalCache.store(key, frame) }
            }
        }
    }

    // The grid follows the keyboard: the window resizes for it (the manifest's
    // adjustResize; set here at runtime, it was lost in the background, issue 5).

    val sink = remember(session) {
        object : KeyboardSink {
            override fun type(text: String) = session.type(text)
            override fun backspace() = session.backspace()
        }
    }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (!imeVisible && !screen.typing) screen.inputOpen = false
    }

    val report = session.report
    LaunchedEffect(report) {
        if (report != null) {
            Toast.makeText(context, report, Toast.LENGTH_LONG).show()
            session.reportShown()
        }
    }

    BackHandler {
        when {
            screen.composeOpen -> {
                screen.composeOpen = false
                screen.composeText = ""
            }
            screen.selecting -> screen.selecting = false
            else -> home.close()
        }
    }

    val ui = remember(snapshot, key, res) { terminalUi(snapshot, key, res) }
    val actions = remember(key, home) {
        terminalActions(
            screen,
            onBack = home::close,
            onOpen = home::open,
            onSwipe = home::step,
            onTab = home::tab,
            onPhoneGrid = { if (changesOpen || overview != null) heldGrid = it else phone = it },
            onWatchView = { app.terminalZoom[key] = it.zoom },
            // Read after a step: the terminal it opened, as its title bar will show it.
            openHeader = { home.openKey?.let { terminalUi(app.hubHost.snapshot.value, it, res).header } },
        )
    }
    // Under the chat the desk keeps its size; the terminal shown takes the phone's.
    LaunchedEffect(session, switch.chat) { session.follow(switch.chat) }
    ChatPolling(
        chat, app,
        screen = { (0 until session.grid.rows).map(session.grid::rowText) },
    )
    Box {
        val input = TerminalKeyboard(key, sink)
        val openChanges = {
            // The phone's keyboard would cover the screen; the terminal itself is left as it is.
            input.hide()
            if (!screen.typing) screen.inputOpen = false
            changesOpen = true
        }
        val openOverview = {
            ui.map?.let { map ->
                input.hide()
                if (!screen.typing) screen.inputOpen = false
                overview = map.spaceId to map.tabId
            }
            Unit
        }
        val over = changesOpen || overview != null
        // Under the chat the terminal is left out of accessibility: its keys are covered.
        // Nor is it drawn there: the chat is opaque, and each of its frames would redraw it.
        Box((if (switch.chat || over) Modifier.clearAndSetSemantics {} else Modifier).undrawnWhen(switch.chat)) {
            TerminalScreen(
                ui, session, screen, actions, input,
                onChat = if (switch.offered) ({
                    // The keyboard was the terminal's: it must not type there under the chat.
                    input.hide()
                    screen.inputOpen = false
                    app.chatView.choose(chat = true)
                    switch.toChat()
                }) else null,
                onChanges = openChanges,
                onMap = openOverview,
                covered = switch.chat,
            )
        }
        if (switch.chat) {
            Box(if (over) Modifier.clearAndSetSemantics {} else Modifier) {
                ChatRoute(chat, app, ui.header, actions, onChanges = openChanges, map = ui.map, onMap = openOverview) {
                    app.chatView.choose(chat = false)
                    switch.toTerminal()
                }
            }
        }
        if (changesOpen) {
            val changes = remember(key) { ports.openChanges(key) }
            ChangesRoute(changes, ui.header.name, ui.header.lineTwo.substringBefore(" · ")) {
                changesOpen = false
                heldGrid?.let { phone = it }
                heldGrid = null
            }
        }
        overview?.let { (spaceId, tabId) ->
            val back = {
                overview = null
                heldGrid?.let { phone = it }
                heldGrid = null
            }
            // A tile opens its terminal at once (a tap, not a step); this one's own tile goes back to it.
            TabOverviewRoute(app, key.machine, spaceId, tabId, onBack = back) { tile -> if (tile == key) back() else home.open(tile) }
        }
    }
}

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
 * no pane (loose, a sub-agent's vacant pane, no layout) and in a tab of one
 * pane. The last layout read stays while the machine is out of reach, as in
 * the Spaces view. Counted over the stops: the terminal cells the phone lists.
 */
fun titleMap(snapshot: HubSnapshot, key: AgentKey): TitleMap? {
    val desk = snapshot.machines.firstOrNull { it.name == key.machine }?.desk ?: return null
    val attention = snapshot.agents.filter { it.machine == key.machine }.associate { it.address to it.attention }
    val listed = attention.keys + snapshot.others.filter { it.machine == key.machine }.map { it.address }
    for (space in desk.spaces) for (tab in space.tabs) {
        val addresses = tab.cells.mapNotNull { (it.pane as? DeskPaneView.Terminal)?.address }
        if (key.address !in addresses) continue
        if (tab.cells.size < 2) return null
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
    openHeader: () -> TerminalHeader? = { null },
    announce: ((String) -> Unit)? = null,
) = TerminalActions(
    onBack = onBack,
    onOpen = onOpen,
    onSwipe = onSwipe,
    onPhoneGrid = onPhoneGrid,
    onWatchView = onWatchView,
    onTab = onTab,
    stepRefused = { screen.composeText.isNotEmpty() },
    openHeader = openHeader,
    announce = announce,
)
