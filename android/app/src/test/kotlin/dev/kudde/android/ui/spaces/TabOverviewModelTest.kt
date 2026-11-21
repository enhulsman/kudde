package dev.kudde.android.ui.spaces

import android.content.res.Resources
import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.agents.agentsUi
import dev.kudde.android.ui.agents.ProgramIcon
import dev.kudde.android.ui.spaces.SpacesFixtures.NOW
import dev.kudde.android.ui.spaces.SpacesFixtures.overviewRender
import dev.kudde.android.ui.spaces.SpacesFixtures.term
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import dev.kudde.android.testing.KuddeTestRunner
import org.robolectric.RuntimeEnvironment
import uniffi.kudde_mobile.AgentAttention
import uniffi.kudde_mobile.DeskCellView
import uniffi.kudde_mobile.DeskSpaceView
import uniffi.kudde_mobile.DeskView
import uniffi.kudde_mobile.HubSnapshot

// spaces spec, "A tab overview shows the splits as tiles" (design D8): whether
// every tile is at least 96 by 64 dp, else rows; the tiles and rows; and which
// tab shows when the shown tab or its space goes. Plain Kotlin over the model;
// Robolectric only for the resources the spoken names come from.
@RunWith(KuddeTestRunner::class)
class TabOverviewModelTest {
    private val res: Resources = RuntimeEnvironment.getApplication().resources

    /** [n] panes side by side, as a row splits them. */
    private fun sideBySide(n: Int) = (0 until n).map { term("t$it", it.toFloat() / n, 0f, 1f / n, 1f) }

    /** [n] panes one over the other. */
    private fun stacked(n: Int) = (0 until n).map { term("t$it", 0f, it.toFloat() / n, 1f, 1f / n) }

    /** The tiles' height on the reference phone (the render's 380 x 520 at 412 dp). */
    private val height = 520f

    private fun fits(cells: List<DeskCellView>, phoneWidthDp: Float) = tilesFit(cells, overviewAreaWidth(phoneWidthDp), height)

    @Test
    fun panesSideBySideFitAsTilesUpToTheTileWidth() {
        // A tile is its share of the area (the phone's width less 16 dp each side) less the 8 dp between tiles.
        val widest = mapOf(360f to 3, 412f to 3, 600f to 5)
        for ((phone, most) in widest) {
            for (n in 1..8) {
                assertEquals("$n panes side by side at $phone dp", n <= most, fits(sideBySide(n), phone))
            }
        }
    }

    @Test
    fun panesOneOverTheOtherFitUpToTheTileHeight() {
        for (phone in listOf(360f, 412f, 600f)) {
            for (n in 1..8) {
                assertEquals("$n panes stacked at $phone dp", n <= 7, fits(stacked(n), phone))
            }
        }
    }

    @Test
    fun theRendersTabsFitOrNot() {
        assertTrue("one beside three", fits(SpacesFixtures.MAIN.cells, 412f))
        assertEquals("six side by side give rows", false, fits(SpacesFixtures.BUILD.cells, 412f))
    }

    @Test
    fun aSideOfZeroNeverFits() {
        assertEquals(false, fits(listOf(term("a", 0f, 0f, 1f, 0f)), 600f))
        assertEquals(false, tilesFit(emptyList(), 380f, 520f))
    }

    // --- The tiles and the rows ---

    private fun ui(snapshot: HubSnapshot = overviewRender, tab: String? = null, space: String = "s-website") =
        tabOverviewUi(snapshot, agentsUi(snapshot, NOW, res, SpacesFixtures::utc), "workstation", space, tab, spacesTexts(res))

    @Test
    fun theOverviewHoldsTheSpaceItsTabsAndTheShownTabsTiles() {
        val model = ui()!!
        assertEquals("website", model.spaceName)
        assertEquals("workstation", model.machine)
        assertEquals(listOf("main", "2", "build"), model.tabs.map { it.name })
        assertEquals("the first tab without a choice", "tb-main", model.shown)
        assertEquals(listOf(MapKind.NEEDS_YOU, MapKind.WORKING, MapKind.PLAIN, MapKind.DESK_ONLY), model.tabs[0].cells.map { it.kind })
        assertEquals("the mod pane is drawn, not counted", "Tab main, 3 terminals, 1 needs you", model.tabs[0].spoken)
        val tiles = model.tiles
        assertEquals(4, tiles.size)
        val pricing = tiles[0] as OverviewTile.Terminal
        assertEquals(AgentKey("workstation", "t1"), pricing.key)
        assertEquals("Pricing page copy", pricing.name)
        assertEquals(ProgramIcon.Claude, pricing.icon)
        assertEquals(AgentAttention.NEEDS_YOU, pricing.attention)
        assertEquals(listOf(0f, 0f, .5f, 1f), listOf(pricing.x, pricing.y, pricing.w, pricing.h))
        val shell = tiles[2] as OverviewTile.Terminal
        assertEquals("zsh", shell.name)
        assertNull("a terminal without status has no state", shell.attention)
        assertTrue("a mod pane reads Desk only", tiles[3] is OverviewTile.DeskOnly)
    }

    @Test
    fun theRowsAreTheTabsTerminalsInDeskOrder() {
        val model = ui(tab = "tb-build")!!
        assertEquals("tb-build", model.shown)
        assertEquals(
            listOf("build fixes", "npm run dev", "npm test", "flaky test", "htop", "zsh"),
            model.rows.map {
                when (it) {
                    is SpacesItem.Agent -> it.row.name
                    is SpacesItem.Other -> it.row.name
                    else -> error("not a row: $it")
                }
            },
        )
        assertEquals(6, model.map.size)
    }

    @Test
    fun aVacantPaneOrATerminalWithoutARowIsDeskOnly() {
        val snapshot = overviewRender.copy(others = overviewRender.others.filter { it.address != "t3" })
        assertTrue("t3 has no row now", ui(snapshot)!!.tiles[2] is OverviewTile.DeskOnly)
    }

    // --- Which tab shows when the desk changes ---

    private fun without(tabId: String) = overviewRender.copy(
        machines = overviewRender.machines.map { m ->
            m.copy(desk = m.desk!!.copy(spaces = m.desk!!.spaces.map { s -> s.copy(tabs = s.tabs.filter { it.id != tabId }) }))
        },
    )

    @Test
    fun theShownTabStaysWhileItLives() {
        assertEquals("tb-2", ui(tab = "tb-2")!!.shown)
        assertEquals("tb-2", ui(without("tb-build"), tab = "tb-2")!!.shown)
    }

    @Test
    fun whenTheShownTabIsClosedTheSpacesFirstTabShows() {
        assertEquals("tb-main", ui(without("tb-2"), tab = "tb-2")!!.shown)
    }

    @Test
    fun whenTheSpaceIsClosedThereIsNoOverview() {
        val gone = overviewRender.copy(
            machines = overviewRender.machines.map { it.copy(desk = DeskView(emptyList<DeskSpaceView>(), emptyList(), null)) },
        )
        assertNull(ui(gone, tab = "tb-main"))
        assertNull("another machine's space is not this one", ui(space = "s-api"))
        assertNull("nor without a desk", ui(overviewRender.copy(machines = overviewRender.machines.map { it.copy(desk = null) })))
    }

    // --- Tile pictures (spaces spec, "Tiles show their screens", "No picture from the desk") ---

    @Test
    fun aTerminalTileCarriesItsPictureAndTheOthersCarryNone() {
        val pictures = mapOf("t1" to "$ cargo build\n   Compiling", "t3" to "~/src $")
        val tiles = tabOverviewUi(overviewRender, agentsUi(overviewRender, NOW, res, SpacesFixtures::utc), "workstation", "s-website", null, spacesTexts(res), pictures)!!.tiles
        val byAddress = tiles.filterIsInstance<OverviewTile.Terminal>().associateBy { it.key.address }
        assertEquals("$ cargo build\n   Compiling", byAddress.getValue("t1").picture)
        assertNull("no picture from the desk: the tile as before", byAddress.getValue("t2").picture)
        assertEquals("~/src $", byAddress.getValue("t3").picture)
        assertTrue("a placeholder is still Desk only", tiles.last() is OverviewTile.DeskOnly)
    }

    @Test
    fun tilesWithoutPicturesAreAsTheyWereBefore() {
        val plain = ui()!!.tiles
        val none = tabOverviewUi(overviewRender, agentsUi(overviewRender, NOW, res, SpacesFixtures::utc), "workstation", "s-website", null, spacesTexts(res), emptyMap())!!.tiles
        assertEquals(plain, none)
    }

    @Test
    fun failedAndEmptyReadsLeaveOnlyThoseTilesWithoutAPicture() = runBlocking {
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        val got = readTabPictures(listOf("t1", "t2", "t3", "t4")) { address ->
            asked += address
            when (address) {
                "t1" -> "build log"
                "t2" -> null
                "t3" -> "  \n "
                else -> error("the desk is gone")
            }
        }
        assertEquals(mapOf("t1" to "build log"), got)
        assertEquals("every terminal was asked once", listOf("t1", "t2", "t3", "t4"), asked.sorted())
    }

    @Test
    fun theReadsRunOffTheCallersThread() = runBlocking {
        val caller = Thread.currentThread()
        val threads = java.util.concurrent.CopyOnWriteArrayList<Thread>()
        readTabPictures(listOf("t1", "t2")) { threads += Thread.currentThread(); "x" }
        assertEquals(2, threads.size)
        assertTrue(threads.none { it === caller })
    }
}
