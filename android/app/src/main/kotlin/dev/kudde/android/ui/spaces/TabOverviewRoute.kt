package dev.kudde.android.ui.spaces

import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalContext
import dev.kudde.android.KuddeApp
import androidx.compose.runtime.rememberUpdatedState
import dev.kudde.android.ui.agent.AgentPorts
import dev.kudde.android.ui.agent.HubPorts
import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.agents.agentsUi
import dev.kudde.android.ui.agents.timeFormat
import dev.kudde.android.ui.components.rememberWallClock

/**
 * The tab overview of [machine]'s space [spaceId] fed by the hub, first
 * showing [tabId] (spaces spec, "A tab overview shows the splits as tiles").
 * It follows the desk like the Spaces view: a closed tab gives way to the
 * space's first, and a closed space closes the overview ([onBack]). Opened
 * from a tab line in the Spaces view and from the title bar's map; Back
 * returns there. A tile or row opens its terminal through [onOpen], at once
 * (a tap, not a step).
 */
@Composable
fun TabOverviewRoute(
    app: KuddeApp,
    machine: String,
    spaceId: String,
    tabId: String?,
    onBack: () -> Unit,
    ports: AgentPorts = HubPorts(app),
    onOpen: (AgentKey) -> Unit,
) {
    val snapshot by app.hubHost.snapshot.collectAsState()
    var shown by rememberSaveable(machine, spaceId) { mutableStateOf(tabId) }
    val context = LocalContext.current
    val res = context.resources
    val clock = rememberWallClock()
    val minute by remember(clock) { derivedStateOf { clock.value / 60_000 } }
    val time = remember(context) { timeFormat(DateFormat.is24HourFormat(context)) }
    val spin = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    // The tab's still pictures (D11): read when the overview opens and when the tab shown
    // changes, and never while it stays open, so the effect is keyed on the tab alone.
    var pictures by remember(machine, spaceId) { mutableStateOf<Pair<String?, Map<String, String>>>(null to emptyMap()) }
    val ui = remember(snapshot, shown, minute, res, time, pictures) {
        val agents = agentsUi(snapshot, Snapshot.withoutReadObservation { clock.value }, res, time)
        val shownPictures = pictures.takeIf { (tab, _) -> tab != null && tab == resolvedTab(snapshot, machine, spaceId, shown) }?.second.orEmpty()
        tabOverviewUi(snapshot, agents, machine, spaceId, shown, spacesTexts(res), shownPictures)
    }
    if (ui == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val latest by rememberUpdatedState(ui)
    LaunchedEffect(machine, spaceId, ui.shown) {
        val tab = ui.shown
        val addresses = latest.tiles.filterIsInstance<OverviewTile.Terminal>().map { it.key.address }
        pictures = tab to readTabPictures(addresses) { address -> ports.readScreen(AgentKey(machine, address)) }
    }
    TabOverviewScreen(ui, clock, spin, onBack = onBack, onTab = { shown = it }, onOpen = onOpen)
}

/** The id of the tab the overview shows for [shown]: that tab, else the space's first; null without the space. */
private fun resolvedTab(snapshot: uniffi.kudde_mobile.HubSnapshot, machine: String, spaceId: String, shown: String?): String? {
    val space = snapshot.machines.firstOrNull { it.name == machine }?.desk?.spaces?.firstOrNull { it.id == spaceId } ?: return null
    return (space.tabs.firstOrNull { it.id == shown } ?: space.tabs.firstOrNull())?.id
}
