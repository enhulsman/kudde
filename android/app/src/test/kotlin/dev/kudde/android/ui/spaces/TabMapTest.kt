package dev.kudde.android.ui.spaces

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.Contrast
import dev.kudde.android.ui.theme.ThemeName
import dev.kudde.android.ui.theme.kuddeColors
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.kudde_mobile.AgentAttention
import uniffi.kudde_mobile.DeskCellView
import uniffi.kudde_mobile.DeskPaneView
import uniffi.kudde_mobile.DeskTabView

// spaces spec, "A tab map draws a tab's splits" (design D7, components.tabMap):
// one cell per pane, placed as the splits place it, drawn by state; a
// placeholder and a vacant pane drawn apart; the open terminal's cell filled in
// the text colour (the title bar's map). Each golden shows the map at its own
// 28 x 20 dp and at four times that, so the cells can be judged by eye.
// Goldens: src/test/snapshots/tab-map-<case>-<theme>.png.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class TabMapTest {
    private val res = RuntimeEnvironment.getApplication().resources

    private fun term(address: String, x: Float, y: Float, w: Float, h: Float) =
        DeskCellView(DeskPaneView.Terminal(address), x, y, w, h)
    private fun mod(x: Float, y: Float, w: Float, h: Float) = DeskCellView(DeskPaneView.Placeholder("browser"), x, y, w, h)
    private fun vacant(x: Float, y: Float, w: Float, h: Float) = DeskCellView(DeskPaneView.Vacant, x, y, w, h)
    private fun tab(vararg cells: DeskCellView) = DeskTabView("main", "main", cells.toList())

    private val attention = mapOf(
        "blocked" to AgentAttention.NEEDS_YOU,
        "error" to AgentAttention.ERROR,
        "done" to AgentAttention.DONE,
        "working" to AgentAttention.WORKING,
        "idle" to AgentAttention.IDLE,
    )
    private fun cells(tab: DeskTabView, marked: String? = null) = mapCells(tab, { attention[it] }, marked)

    private val one = tab(term("idle", 0f, 0f, 1f, 1f))
    private val two = tab(term("idle", 0f, 0f, .5f, 1f), term("working", .5f, 0f, .5f, 1f))
    private val oneBesideTwo = tab(
        term("blocked", 0f, 0f, .5f, 1f),
        term("working", .5f, 0f, .5f, .5f),
        term("idle", .5f, .5f, .5f, .5f),
    )
    private val withPlaceholder = tab(term("idle", 0f, 0f, 1f / 3, 1f), mod(1f / 3, 0f, 1f / 3, 1f), term("working", 2f / 3, 0f, 1f / 3, 1f))
    private val everyState = tab(
        term("blocked", 0f, 0f, .2f, 1f),
        term("done", .2f, 0f, .2f, 1f),
        term("working", .4f, 0f, .2f, 1f),
        term("idle", .6f, 0f, .2f, 1f),
        vacant(.8f, 0f, .2f, 1f),
    )

    // --- The model ---

    @Test
    fun oneBesideTwoKeepsThePlacesOfTheSplits() {
        val drawn = cells(oneBesideTwo)
        assertEquals(
            listOf(
                MapCell(0f, 0f, .5f, 1f, MapKind.NEEDS_YOU),
                MapCell(.5f, 0f, .5f, .5f, MapKind.WORKING),
                MapCell(.5f, .5f, .5f, .5f, MapKind.PLAIN),
            ),
            drawn,
        )
    }

    @Test
    fun aSinglePaneIsOneCell() {
        assertEquals(listOf(MapCell(0f, 0f, 1f, 1f, MapKind.PLAIN)), cells(one))
    }

    @Test
    fun eachStateHasItsOwnCellAndAnErrorNeedsThePerson() {
        assertEquals(
            listOf(MapKind.NEEDS_YOU, MapKind.DONE, MapKind.WORKING, MapKind.PLAIN, MapKind.DESK_ONLY),
            cells(everyState).map { it.kind },
        )
        assertEquals(MapKind.NEEDS_YOU, cells(tab(term("error", 0f, 0f, 1f, 1f))).single().kind)
        assertEquals("a terminal without a row is plain", MapKind.PLAIN, cells(tab(term("gone", 0f, 0f, 1f, 1f))).single().kind)
    }

    @Test
    fun aPlaceholderIsDrawnApart() {
        assertEquals(listOf(MapKind.PLAIN, MapKind.DESK_ONLY, MapKind.WORKING), cells(withPlaceholder).map { it.kind })
    }

    @Test
    fun theMarkedCellIsTheOpenTerminalWhateverItsState() {
        assertEquals(
            listOf(MapKind.NEEDS_YOU, MapKind.MARKED, MapKind.PLAIN),
            cells(oneBesideTwo, marked = "working").map { it.kind },
        )
    }

    // --- Its spoken name ---

    @Test
    fun theSpokenNameGivesTheTabTheTerminalsAndWhoNeedsYou() {
        assertEquals("Tab main, 3 terminals, 1 needs you", tabMapSpoken(res, "main", 3, 1))
        assertEquals("the last part only when someone needs you", "Tab main, 2 terminals", tabMapSpoken(res, "main", 2, 0))
        assertEquals("Tab 1, 1 terminal", tabMapSpoken(res, "1", 1, 0))
    }

    @Test
    fun theTitleBarsMapIsSpokenAsTheOverviewAndThePlace() {
        assertEquals("Tab overview, terminal 2 of 3", titleMapSpoken(res, 2, 3))
        assertEquals("Tab overview, terminal 1 of 1", titleMapSpoken(res, 1, 1))
    }

    // Owner, 2026-11-21: a plain shell's cell must be easy to see, also at low
    // brightness; the open terminal is the one cell filled in the text colour.
    @Test
    fun everyCellStandsOutFromTheTitleBarAndTheSpacesViewInBothThemes() {
        for (theme in ThemeName.entries) for (contrast in Contrast.entries) {
            val c = kuddeColors(theme, contrast)
            for (kind in MapKind.entries) {
                val (fill, edge) = mapCellPaint(kind, c)
                for (ground in listOf(c.chrome, c.bg)) {
                    assertTrue("$theme $contrast $kind edge", ratio(edge.compositeOver(ground), ground) >= 3.0)
                    fill?.let { assertTrue("$theme $contrast $kind fill", ratio(it.compositeOver(ground), ground) >= 3.0) }
                }
            }
        }
    }

    @Test
    fun aPlainShellIsOutlinedInMutedAndTheOpenTerminalIsTheOnlyCellFilledInTheTextColour() {
        for (theme in ThemeName.entries) for (contrast in Contrast.entries) {
            val c = kuddeColors(theme, contrast)
            assertEquals(null to c.muted, mapCellPaint(MapKind.PLAIN, c))
            assertEquals(c.fg to c.fg, mapCellPaint(MapKind.MARKED, c))
            for (kind in MapKind.entries.filter { it != MapKind.MARKED }) {
                assertTrue("$theme $kind is not filled like the mark", mapCellPaint(kind, c).first != c.fg)
            }
        }
    }

    private fun ratio(a: Color, b: Color): Double {
        fun luminance(c: Color): Double {
            fun linear(v: Float): Double = if (v <= .04045f) v.toDouble() / 12.92 else ((v.toDouble() + .055) / 1.055).pow(2.4)
            return .2126 * linear(c.red) + .7152 * linear(c.green) + .0722 * linear(c.blue)
        }
        val l1 = luminance(a)
        val l2 = luminance(b)
        return (maxOf(l1, l2) + .05) / (minOf(l1, l2) + .05)
    }

    // --- Goldens ---

    @Test fun oneCellGraphite() = capture("one", one, dark = true)

    @Test fun twoSideBySideGraphite() = capture("two", two, dark = true)

    @Test fun oneBesideTwoGraphite() = capture("one-beside-two", oneBesideTwo, dark = true)

    @Test fun placeholderGraphite() = capture("placeholder", withPlaceholder, dark = true)

    @Test fun eachStateGraphite() = capture("states", everyState, dark = true)

    @Test fun eachStatePaper() = capture("states", everyState, dark = false)

    @Test fun markedGraphite() = capture("marked", oneBesideTwo, dark = true, marked = "blocked")

    @Test fun markedPaper() = capture("marked", oneBesideTwo, dark = false, marked = "blocked")

    private fun capture(case: String, tab: DeskTabView, dark: Boolean, marked: String? = null) {
        val drawn = cells(tab, marked)
        captureRoboImage("src/test/snapshots/tab-map-$case-${if (dark) "graphite" else "paper"}.png") {
            KuddeTheme(dark = dark) {
                Row(
                    Modifier.background(KuddeTheme.colors.chrome).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TabMap(drawn)
                    TabMap(drawn, width = 112.dp, height = 80.dp)
                }
            }
        }
    }
}
