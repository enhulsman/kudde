package dev.kudde.android.ui.spaces

import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.agents.AgentsUi
import dev.kudde.android.ui.agents.ProgramIcon
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import uniffi.kudde_mobile.AgentAttention
import uniffi.kudde_mobile.DeskCellView
import uniffi.kudde_mobile.DeskPaneView
import uniffi.kudde_mobile.HubSnapshot

// The tab overview apart from drawing it (design D8; spaces spec, "A tab
// overview shows the splits as tiles"): one space's tabs, the shown tab's
// panes as tiles placed as the splits place them, or as rows when a tile would
// be smaller than 96 by 64 dp, and which tab shows while the desk changes.

/** Every tile is at least this wide and high, in dp; else the overview lists rows. */
const val TILE_MIN_WIDTH_DP = 96f
const val TILE_MIN_HEIGHT_DP = 64f

/** The space between two tiles, in dp (paneTile: 8 apart). */
const val TILE_GAP_DP = 8f

/** The tiles' area across a phone [phoneWidthDp] wide: 16 dp in from each side (`.otiles`). */
fun overviewAreaWidth(phoneWidthDp: Float): Float = phoneWidthDp - 32f

/**
 * Whether [cells] fit as tiles in an area [widthDp] by [heightDp]: each tile
 * is its cell's share of the area less the gap, and every one must be at least
 * [TILE_MIN_WIDTH_DP] by [TILE_MIN_HEIGHT_DP]. No cells never fit.
 */
fun tilesFit(cells: List<DeskCellView>, widthDp: Float, heightDp: Float): Boolean =
    cells.isNotEmpty() && cells.all {
        it.w * widthDp - TILE_GAP_DP >= TILE_MIN_WIDTH_DP && it.h * heightDp - TILE_GAP_DP >= TILE_MIN_HEIGHT_DP
    }

/** One of the space's tabs to choose from: its map and name, spoken as a tab line is. */
data class OverviewTab(val id: String, val name: String, val cells: List<MapCell>, val spoken: String)

/** A pane of the shown tab, placed as fractions of the tab. */
sealed interface OverviewTile {
    val x: Float
    val y: Float
    val w: Float
    val h: Float

    /** A listed terminal: its mark, name and state (null without status); a tap opens it. */
    data class Terminal(
        val key: AgentKey,
        val name: String,
        val icon: ProgramIcon,
        val attention: AgentAttention?,
        override val x: Float,
        override val y: Float,
        override val w: Float,
        override val h: Float,
        /** The terminal's screen as plain text, a still picture (D11); null when the desk gave none. */
        val picture: String? = null,
    ) : OverviewTile

    /** A placeholder, a vacant pane or a terminal the phone does not list: "Desk only", nothing on a tap. */
    data class DeskOnly(override val x: Float, override val y: Float, override val w: Float, override val h: Float) : OverviewTile
}

data class TabOverviewUi(
    val machine: String,
    val spaceId: String,
    val spaceName: String,
    val tabs: List<OverviewTab>,
    /** The id of the tab whose panes show. */
    val shown: String,
    /** The shown tab's map, above the rows when tiles do not fit. */
    val map: List<MapCell>,
    /** The shown tab's cells, for [tilesFit]. */
    val cells: List<DeskCellView>,
    val tiles: List<OverviewTile>,
    /** The shown tab's terminals in desk order, as the Spaces view shows them. */
    val rows: List<SpacesItem>,
)

/**
 * The overview of [machine]'s space [spaceId], showing [tabId], or the
 * space's first tab when [tabId] is null or that tab is gone. Null when the
 * space is gone (or has no tab left): the overview closes.
 */
fun tabOverviewUi(
    snapshot: HubSnapshot,
    agents: AgentsUi,
    machine: String,
    spaceId: String,
    tabId: String?,
    texts: SpacesTexts,
    /** The pictures read for the shown tab, by terminal address (see [readTabPictures]). */
    pictures: Map<String, String> = emptyMap(),
): TabOverviewUi? {
    val desk = snapshot.machines.firstOrNull { it.name == machine }?.desk ?: return null
    val space = desk.spaces.firstOrNull { it.id == spaceId } ?: return null
    val tab = space.tabs.firstOrNull { it.id == tabId } ?: space.tabs.firstOrNull() ?: return null
    val rows = (agents.needsYou + agents.done + agents.working + agents.idle).associateBy { it.key }
    val others = agents.others.associateBy { it.key }
    fun key(address: String) = AgentKey(machine, address)
    fun attention(address: String) = rows[key(address)]?.attention
    val tabs = space.tabs.map { t ->
        val listed = t.cells.mapNotNull { (it.pane as? DeskPaneView.Terminal)?.address }.map(::key).filter { it in rows || it in others }
        val needsYou = listed.count { rows[it]?.attention.let { a -> a == AgentAttention.NEEDS_YOU || a == AgentAttention.ERROR } }
        OverviewTab(t.id, t.name, mapCells(t, ::attention), texts.tabSpeech(t.name, listed.size, needsYou))
    }
    val tiles = tab.cells.map { cell ->
        val address = (cell.pane as? DeskPaneView.Terminal)?.address
        val agent = address?.let { rows[key(it)] }
        val other = address?.let { others[key(it)] }
        when {
            agent != null -> OverviewTile.Terminal(agent.key, agent.name, agent.icon, agent.attention, cell.x, cell.y, cell.w, cell.h, pictures[address])
            other != null -> OverviewTile.Terminal(other.key, other.name, other.icon, null, cell.x, cell.y, cell.w, cell.h, pictures[address])
            else -> OverviewTile.DeskOnly(cell.x, cell.y, cell.w, cell.h)
        }
    }
    val listed = tab.cells.mapNotNull { (it.pane as? DeskPaneView.Terminal)?.address }.map(::key).mapNotNull { k ->
        rows[k]?.let { SpacesItem.Agent("row:${k.address}", it) } ?: others[k]?.let { SpacesItem.Other("row:${k.address}", it) }
    }
    return TabOverviewUi(machine, space.id, space.name, tabs, tab.id, mapCells(tab, ::attention), tab.cells, tiles, listed)
}

/**
 * The still pictures of one tab (design D11): each of [addresses] read once
 * through [read] (the desk's plain-text screen, or null), off the caller's
 * thread. A read that fails, throws or comes back blank leaves its terminal
 * out of the result; the others keep theirs.
 */
suspend fun readTabPictures(
    addresses: List<String>,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    read: (address: String) -> String?,
): Map<String, String> = withContext(dispatcher) {
    addresses.map { address ->
        async {
            val text = try {
                read(address)
            } catch (e: Exception) {
                null
            }
            text?.takeIf { it.isNotBlank() }?.let { address to it }
        }
    }.awaitAll().filterNotNull().toMap()
}
