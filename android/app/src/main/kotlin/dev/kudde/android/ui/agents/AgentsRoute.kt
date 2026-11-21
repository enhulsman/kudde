package dev.kudde.android.ui.agents

import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import dev.kudde.android.ui.agent.AgentPorts
import dev.kudde.android.ui.agent.HubPorts
import dev.kudde.android.KuddeApp
import dev.kudde.android.ui.components.rememberWallClock
import dev.kudde.android.ui.flows.MachineFlows
import dev.kudde.android.ui.spaces.SpacesScreen
import dev.kudde.android.ui.spaces.TabOverviewRoute
import dev.kudde.android.ui.spaces.spacesTexts
import dev.kudde.android.ui.spaces.spacesUi
import dev.kudde.android.ui.agent.AgentHost
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The home fed by the app's hub, [flows] (Machines, Add machine) and
 * [home] (the open terminal, "Other terminals"): the Agents view or the
 * Spaces view, the one chosen last (app.homeView), and the tab overview
 * opened from a tab line. An open agent shows its terminal screen
 * (AgentHost), with the home composed underneath only while leaving. One clock ticks each second while the
 * screen is started; only the ages read it every tick, and the rows are
 * rebuilt once a minute for "last connected".
 */
@Composable
fun AgentsRoute(app: KuddeApp, flows: MachineFlows, home: AgentsHome, ports: AgentPorts = HubPorts(app)) {
    val snapshot by app.hubHost.snapshot.collectAsState()
    val startError by app.hubHost.startError.collectAsState()
    val clock = rememberWallClock()
    val minute by remember(clock) { derivedStateOf { clock.value / 60_000 } }
    val context = LocalContext.current
    val res = context.resources
    val is24 = DateFormat.is24HourFormat(context)
    val time = remember(is24) { timeFormat(is24) }
    // Read without subscribing: the route follows [minute], not every tick.
    val ui = remember(snapshot, startError, minute, res, time) {
        agentsUi(snapshot, Snapshot.withoutReadObservation { clock.value }, res, time, startError)
    }
    val spin = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    val actions = remember(flows, home, app) {
        AgentsActions(
            onMachines = flows::openMachines,
            onAddMachine = flows::openAddMachine,
            onOpen = home::open,
            onCloseSheet = home::close,
            onToggleOthers = home::toggleOthers,
            onRetry = app.hubHost::retry,
        )
    }
    val new = app.newTerminal
    val newUi = NewEntryUi(
        enabled = newEnabled(snapshot),
        sheet = new.sheet?.let { newSheetUi(snapshot, it, res) },
        noMachine = new.noMachine,
    )
    val newActions = remember(new) {
        NewSheetActions(
            onNew = new::tapNew,
            onNoMachineGone = new::noMachineShown,
            onMachine = new::chooseMachine,
            onPlace = new::choosePlace,
            onStart = new::start,
            onClose = new::close,
        )
    }
    // The Spaces view's place is held here, above the terminal that replaces the list,
    // so Back from a terminal shows it where it was (design D6).
    val spacesState = rememberLazyListState()
    // The tab overview opened from a tab line; Back from it returns to Spaces, and a
    // terminal opened from it returns to it.
    var overview by remember { mutableStateOf<OverviewAt?>(null) }
    val homeView = app.homeView
    // Since M2 an agent opens its live terminal; the M1 read-only sheet is no longer shown.
    val open = home.openKey
    val at = overview
    val list: @Composable () -> Unit = {
        when {
            at != null -> TabOverviewRoute(app, at.machine, at.spaceId, at.tabId, onBack = { overview = null }, onOpen = home::open)
            homeView.spaces -> {
                BackHandler(enabled = new.sheet != null, onBack = new::close)
                val spaces = remember(snapshot, ui, res) { spacesUi(snapshot, ui, spacesTexts(res)) }
                SpacesScreen(
                    spaces, clock, spin, actions,
                    onTab = { machine, spaceId, tabId -> overview = OverviewAt(machine, spaceId, tabId) },
                    state = spacesState,
                    needsYou = ui.needsYou.size,
                    onHomeView = homeView::choose,
                    new = newUi,
                    newActions = newActions,
                )
            }
            else -> {
                BackHandler(enabled = new.sheet != null, onBack = new::close)
                AgentsScreen(ui, home.othersExpanded, null, clock, spin, actions, new = newUi, newActions = newActions, onHomeView = homeView::choose)
            }
        }
    }
    if (open != null) AgentHost(app, home, open, ports, leaveContent = list)
    else list()
}

/** Where the tab overview opened from the Spaces view shows: a machine's space, at a tab. */
private data class OverviewAt(val machine: String, val spaceId: String, val tabId: String)

/** "14:05", or "2:05 PM" when the phone uses a 12-hour clock. */
internal fun timeFormat(is24: Boolean): (Long) -> String {
    val pattern = DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a", Locale.getDefault())
    return { ms -> pattern.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())) }
}
