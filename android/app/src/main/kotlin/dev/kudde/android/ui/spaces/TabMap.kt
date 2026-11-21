package dev.kudde.android.ui.spaces

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.kudde.android.R
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.KuddeColors
import uniffi.kudde_mobile.AgentAttention
import uniffi.kudde_mobile.DeskPaneView
import uniffi.kudde_mobile.DeskTabView

// The tab map (design D7; components.tabMap in design-tokens.json, `.tmap` in
// design/renders/screens.html): a small drawing of a tab's splits, one cell per
// pane, placed and sized as the desk places it. State is never shown by the map
// alone: the rows below a tab line carry the marks and words, and the map has a
// spoken name, set by its caller.

/** How a cell is drawn. */
enum class MapKind {
    /** Filled in the needs-you colour. */
    NEEDS_YOU,

    /** Filled in the done colour: finished, unseen. */
    DONE,

    /** Outlined in the working colour. */
    WORKING,

    /** Outlined in the border colour: idle, or a terminal without status. */
    PLAIN,

    /** Dashed: a placeholder or a vacant pane, a pane the phone does not list. */
    DESK_ONLY,

    /** Filled in the text colour: the open terminal, in the title bar. */
    MARKED,
}

/** A cell's place, as fractions of the tab (0 to 1), and how it is drawn. */
data class MapCell(val x: Float, val y: Float, val w: Float, val h: Float, val kind: MapKind)

/**
 * The cells of [tab] as a map draws them: a terminal's from its agent's
 * [attention] (null for a terminal without status, or one not listed), the
 * [marked] terminal filled in the text colour, placeholders and vacant panes
 * drawn apart.
 */
fun mapCells(tab: DeskTabView, attention: (address: String) -> AgentAttention?, marked: String? = null): List<MapCell> =
    tab.cells.map { cell ->
        val kind = when (val pane = cell.pane) {
            is DeskPaneView.Terminal -> when {
                pane.address == marked -> MapKind.MARKED
                else -> when (attention(pane.address)) {
                    AgentAttention.NEEDS_YOU, AgentAttention.ERROR -> MapKind.NEEDS_YOU
                    AgentAttention.DONE -> MapKind.DONE
                    AgentAttention.WORKING -> MapKind.WORKING
                    else -> MapKind.PLAIN
                }
            }
            else -> MapKind.DESK_ONLY
        }
        MapCell(cell.x, cell.y, cell.w, cell.h, kind)
    }

/** "Tab main, 3 terminals, 1 needs you"; the last part only when someone needs the person. */
fun tabMapSpoken(res: Resources, name: String, terminals: Int, needsYou: Int): String {
    val head = res.getQuantityString(R.plurals.tab_map_spoken, terminals, name, terminals)
    return if (needsYou > 0) head + ", " + res.getString(R.string.spaces_spoken_needs_you, needsYou) else head
}

/** The title bar's map: "Tab overview, terminal 2 of 3", counted over the tab's stops. */
fun titleMapSpoken(res: Resources, index: Int, count: Int): String = res.getString(R.string.title_bar_map, index, count)

/**
 * The paints the canvas uses: (fill or null, border). Every cell stands out
 * from the title bar at 3:1 or more; a plain shell is outlined in muted, so it
 * stays visible at low brightness, and only the open terminal is filled in
 * the text colour.
 */
internal fun mapCellPaint(kind: MapKind, c: KuddeColors): Pair<Color?, Color> = when (kind) {
    MapKind.NEEDS_YOU -> c.blocked to c.blocked
    MapKind.DONE -> c.done to c.done
    MapKind.WORKING -> null to c.working
    MapKind.PLAIN, MapKind.DESK_ONLY -> null to c.muted
    MapKind.MARKED -> c.fg to c.fg
}

/**
 * Draws [cells] in [width] by [height]: each cell inset half a dp on every
 * side (a 1 dp gap between neighbours), with a 1 dp border and 2 dp corners.
 * A side that comes out at zero or less is not drawn.
 */
@Composable
fun TabMap(cells: List<MapCell>, modifier: Modifier = Modifier, width: Dp = 28.dp, height: Dp = 20.dp) {
    val c = KuddeTheme.colors
    Canvas(modifier.size(width, height)) {
        val half = 0.5.dp.toPx()
        val line = 1.dp.toPx()
        val radius = CornerRadius(2.dp.toPx())
        val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 1.5.dp.toPx()))
        for (cell in cells) {
            val left = cell.x * size.width + half
            val top = cell.y * size.height + half
            val w = cell.w * size.width - 2 * half
            val h = cell.h * size.height - 2 * half
            if (w <= 0f || h <= 0f) continue
            val (fill, edge) = mapCellPaint(cell.kind, c)
            if (fill != null) drawRoundRect(fill, Offset(left, top), Size(w, h), radius)
            // The border sits inside the cell, as CSS's border-box draws it.
            drawRoundRect(
                edge,
                Offset(left + line / 2, top + line / 2),
                Size((w - line).coerceAtLeast(0f), (h - line).coerceAtLeast(0f)),
                CornerRadius((radius.x - line / 2).coerceAtLeast(0f)),
                style = Stroke(line, pathEffect = if (cell.kind == MapKind.DESK_ONLY) dash else null),
            )
        }
    }
}
