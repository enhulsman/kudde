package dev.kudde.android.ui.agent

import dev.kudde.android.ui.terminal.KeyboardSink
import dev.kudde.android.ui.terminal.TerminalInputHandle
import dev.kudde.android.ui.terminal.TerminalKeyboard
import dev.kudde.android.ui.terminal.TerminalMetrics
import dev.kudde.android.ui.terminal.TerminalRenderer
import dev.kudde.android.ui.terminal.TerminalScreen
import dev.kudde.android.ui.terminal.phoneGrid
import dev.kudde.android.ui.terminal.terminalActions
import dev.kudde.android.ui.terminal.spokenTitle
import dev.kudde.android.ui.terminal.terminalUi
import dev.kudde.android.ui.terminal.TerminalReopen
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.kudde.android.KuddeApp
import dev.kudde.android.R
import dev.kudde.android.ui.theme.KuddeLayout
import dev.kudde.android.ui.theme.KuddeType
import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.agents.AgentsHome
import dev.kudde.android.ui.chat.ChatPolling
import dev.kudde.android.ui.changes.ChangesRoute
import dev.kudde.android.ui.chat.ChatRoute
import dev.kudde.android.ui.chat.rememberChatVisit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import dev.kudde.android.ui.spaces.TabOverviewRoute

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
fun AgentHost(
    app: KuddeApp, home: AgentsHome, key: AgentKey, ports: AgentPorts = HubPorts(app),
    leaveContent: @Composable () -> Unit = {},
) {
    val context = LocalContext.current
    val res = context.resources
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val estimate = with(density) {
            phoneGrid(
                constraints.maxWidth.toFloat(),
                constraints.maxHeight - KuddeLayout.terminalTitleHeight.toPx() - 2 * KuddeLayout.keysRowHeight.toPx(),
                KuddeLayout.terminalPaddingX.toPx(), KuddeLayout.terminalPaddingTop.toPx(),
                KuddeType.terminalPhone.fontSize.toPx(),
            )
        }
        val snapshot by app.hubHost.snapshot.collectAsState()
        // Chat creation still follows the hub, independently of terminal generations.
        val chat = rememberChatVisit(app, key, ports::openChat)
        val revision = remember(key) { mutableIntStateOf(0) }
        val keeper = remember(key) {
            AgentKeeper(
                key, ports, context.mainExecutor, chat,
                arrivedByStep = home.arrivedByStep,
                zoom = app.terminalZoom[key],
                estimate = estimate,
                lastPhoneGrid = { app.lastPhoneGrid },
                rememberPhoneGrid = { app.lastPhoneGrid = it },
                changed = { revision.intValue++ },
            )
        }
        keeper.chat = chat
        val reopen = keeper.reopen
        val generation = reopen.generation
        val current = keeper.generation(ports.now())
        val session = current.session
        val opener = current.opener
        val screen = keeper.screen
        val switch = keeper.chat.switch
        // The plain stack and grid notify the host synchronously, without owning Compose state.
        @Suppress("UNUSED_VARIABLE")
        val observedRevision = revision.intValue
        val top = keeper.top
        val motion = remember(keeper) { ScreenStackHostState(top) }
        // The Android input view exists only with its terminal destination.
        val terminalInput = remember(keeper) { arrayOf<TerminalInputHandle>(TerminalInputHandle.None) }
        fun <T> navigate(change: () -> T): T = keeper.navigate(
            clearInput = {
                focus.clearFocus(force = true)
                keyboard?.hide()
                terminalInput[0].hide()
            },
            change = change,
        )
        val commitBack: () -> Unit = { navigate { keeper.back(home::close) } }
        val back: () -> Unit = {
            if (keeper.stack.cover == null) motion.requestBack?.invoke()
            else commitBack()
        }
        // Input during the wait opens the terminal first, then goes to it (design D5).
        DisposableEffect(opener) {
            onDispose { keeper.disposeOpener(current) }
        }
        LaunchedEffect(opener) {
            val wait = opener.dueAtMs - ports.now()
            if (wait > 0) delay(wait)
            keeper.tick(ports.now())
        }
        val phone = keeper.phone
        // The keeper owns settle offers. Covers cancel them; a return measures afresh.
        LaunchedEffect(phone, generation, top) {
            if (top != AgentScreen.Terminal) return@LaunchedEffect
            delay(TerminalMetrics.SETTLE_MS)
            keeper.tick(ports.now())
        }
        LaunchedEffect(keeper, generation, top) {
            if (top == AgentScreen.Chat) keeper.terminalShown(ports.now())
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
                val last = keeper.closeGeneration(current)
                if (last != null) app.terminalCacheExecutor.execute { app.terminalCache.store(key, last) }
            }
        }
        DisposableEffect(keeper) {
            onDispose {
                keeper.release()?.let { last ->
                    app.terminalCacheExecutor.execute { app.terminalCache.store(key, last) }
                }
            }
        }
        // Background idle (Stay connected): detach while stopped and keep the last frame;
        // collected as a coroutine, as nothing composes while the activity is stopped.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(key, generation) {
            app.terminalIdle.drop(1).collect {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@collect
                reopen.onIdle {
                    keeper.closeGeneration(current)?.also { last ->
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
                override fun type(text: String) {
                    if (keeper.top == AgentScreen.Terminal && !motion.running && motion.settled == AgentScreen.Terminal) session.type(text)
                }
                override fun backspace() {
                    if (keeper.top == AgentScreen.Terminal && !motion.running && motion.settled == AgentScreen.Terminal) session.backspace()
                }
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

        val ui = remember(snapshot, key, res) { terminalUi(snapshot, key, res) }
        val actions = keeper.actions(home) {
            terminalActions(
                screen,
                onBack = back,
                onOpen = { target -> navigate { home.open(target) } },
                onSwipe = { forward -> navigate { home.step(forward) } },
                onTab = { next -> navigate { home.tab(next) } },
                onPhoneGrid = {
                    // A transitioning-out destination may finish layout, but cannot measure.
                    if (keeper.top == AgentScreen.Terminal) keeper.onTerminalGrid(it, ports.now())
                },
                onWatchView = { app.terminalZoom[key] = it.zoom },
                // Read after a step: the terminal it opened, as its title bar will show it.
                openTitle = { home.openKey?.let { spokenTitle(terminalUi(app.hubHost.snapshot.value, it, res), res) } },
            )
        }
        ChatPolling(
            chat, app,
            screen = { keeper.session.grid.let { grid -> (0 until grid.rows).map(grid::rowText) } },
        )
        val openChanges: () -> Unit = { navigate { keeper.stack.push(AgentScreen.Changes) } }
        val openOverview: () -> Unit = {
            ui.map?.let { map -> navigate { keeper.stack.push(AgentScreen.Overview(map.spaceId, map.tabId)) } }
        }
        val renderer = keeper.renderer {
            TerminalRenderer(res.getFont(R.font.jetbrains_mono_regular), res.getFont(R.font.jetbrains_mono_bold))
        }
        ScreenStackHost(
            top,
            state = motion,
            overviewKey = key,
            backTarget = {
                if (keeper.stack.cover == null) null
                else if (keeper.chat.switch.chat) AgentScreen.Chat else AgentScreen.Terminal
            },
            backSource = { keeper.top },
            leaveContent = leaveContent,
            onBack = commitBack,
        ) { destination ->
            when (destination) {
                AgentScreen.Terminal -> {
                    // Drawing an outgoing terminal must not retain its Android editor.
                    // A cancelled leave keeps top unchanged and therefore keeps this view.
                    val input = if (top == AgentScreen.Terminal) TerminalKeyboard(key, sink)
                        else TerminalInputHandle.None
                    DisposableEffect(keeper, input) {
                        terminalInput[0] = input
                        onDispose {
                            keeper.terminalLeft()
                            terminalInput[0] = TerminalInputHandle.None
                        }
                    }
                    BackHandler(enabled = screen.composeOpen || screen.selecting) {
                        when {
                            screen.composeOpen -> {
                                screen.composeOpen = false
                                screen.composeText = ""
                                screen.composeFocusAsked = false
                            }
                            screen.selecting -> screen.selecting = false
                        }
                    }
                    TerminalScreen(
                        ui, session, screen, actions, input,
                        onChat = if (switch.offered) ({
                            navigate {
                                app.chatView.choose(chat = true)
                                switch.toChat()
                            }
                        }) else null,
                        onChanges = openChanges,
                        onMap = openOverview,
                        renderer = renderer,
                    )
                }
                AgentScreen.Chat -> ChatRoute(
                    chat, app, ui.header, actions,
                    onChanges = openChanges, map = ui.map, onMap = openOverview,
                ) {
                    navigate {
                        app.chatView.choose(chat = false)
                        switch.toTerminal()
                    }
                }
                AgentScreen.Changes -> {
                    val changes = remember(key) { ports.openChanges(key) }
                    ChangesRoute(changes, ui.header.name, ui.header.lineTwo.substringBefore(" · "), back)
                }
                is AgentScreen.Overview -> TabOverviewRoute(
                    app, key.machine, destination.spaceId, destination.tabId, onBack = back, ports = ports,
                ) { tile ->
                    if (tile == key) back() else navigate { home.open(tile) }
                }
            }
        }
    }
}
