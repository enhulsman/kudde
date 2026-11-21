package dev.kudde.android.ui.spaces

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import dev.kudde.android.ui.agent.AgentMotion
import dev.kudde.android.ui.agent.LocalAgentMotion
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kudde.android.R
import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.agents.AgentRow
import dev.kudde.android.ui.agents.NeedsYouCard
import dev.kudde.android.ui.agents.OtherRow
import dev.kudde.android.ui.agents.ProgramMark
import dev.kudde.android.ui.agents.Ring
import dev.kudde.android.ui.agents.Spinner
import dev.kudde.android.ui.agents.StateSlot
import dev.kudde.android.ui.changes.ChangesTitle
import dev.kudde.android.ui.theme.KuddeBorder
import dev.kudde.android.ui.theme.KuddeRadius
import dev.kudde.android.ui.theme.KuddeSpacing
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.KuddeType
import uniffi.kudde_mobile.AgentAttention

// The tab overview (design D8; render tab-overview,
// design/checkpoints/tab-overview.png; sizes from .otabs, .otab, .otiles,
// .otile and .omap in design/renders/screens.html and components.paneTile in
// design-tokens.json): the Changes screen's back bar titled with the space,
// the space's tabs as chips, and the shown tab's panes as tiles placed as the
// splits place them, or as rows under the tab's map when a tile would be
// smaller than 96 by 64 dp. Name tiles, never live terminals.

/** The tiles' area keeps the render's proportions (380 by 520 dp at 412 dp wide), within the room left. */
private const val TILES_ASPECT = 520f / 380f

@Composable
fun TabOverviewScreen(
    ui: TabOverviewUi,
    clock: State<Long>,
    spin: Boolean,
    onBack: () -> Unit,
    onTab: (tabId: String) -> Unit,
    onOpen: (AgentKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = KuddeTheme.colors
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().background(c.chrome).windowInsetsPadding(WindowInsets.safeDrawing)) {
        ChangesTitle(ui.spaceName, ui.machine, onBack)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(start = KuddeSpacing.s16, end = KuddeSpacing.s16, top = KuddeSpacing.s8, bottom = KuddeSpacing.s12),
            horizontalArrangement = Arrangement.spacedBy(KuddeSpacing.s8),
        ) {
            for (tab in ui.tabs) TabChip(tab, tab.id == ui.shown) { onTab(tab.id) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val width = maxWidth - 32.dp
            val height = minOf(width * TILES_ASPECT, maxHeight - KuddeSpacing.s4 - KuddeSpacing.s16)
            if (tilesFit(ui.cells, width.value, height.value)) {
                Box(Modifier.padding(start = KuddeSpacing.s16, top = KuddeSpacing.s4).size(width, height)) {
                    val gap = 8.dp
                    for ((index, tile) in ui.tiles.withIndex()) {
                        val motion = LocalAgentMotion.current
                        val entry = remember { Animatable(if (motion?.host?.running == true) 0f else 1f) }
                        LaunchedEffect(motion?.host?.running) {
                            if (motion?.host?.running == true && motion.host.motion.name == "pushOverview" && motion.host.durationScale != 0f) {
                                delay(AgentMotion.tileDelay(index))
                                entry.animateTo(1f, AgentMotion.springs.getValue("fastSpatial"))
                            } else if (motion?.host?.running == true && motion.host.motion.name == "popOverview" && motion.host.durationScale != 0f) {
                                entry.animateTo(0f, AgentMotion.springs.getValue("fastEffects"))
                            } else entry.snapTo(1f)
                        }
                        val place = Modifier
                            .graphicsLayer {
                                val t = if (tile is OverviewTile.Terminal && tile.key == motion?.overviewKey) 1f else entry.value
                                val exiting = motion?.host?.running == true && motion.host.motion.name == "popOverview"
                                alpha = if (exiting) t.coerceIn(0f, 1f) else minOf(1f, AgentMotion.tileOpacityRate * t).coerceAtLeast(0f)
                                scaleX = if (exiting) AgentMotion.tileExitScale + (1 - AgentMotion.tileExitScale) * t
                                    else AgentMotion.tileScale + (1 - AgentMotion.tileScale) * t
                                scaleY = scaleX
                            }
                            .onGloballyPositioned { coordinates ->
                                if (tile is OverviewTile.Terminal && tile.key == motion?.overviewKey) {
                                    motion?.host?.overviewTile = coordinates.boundsInRoot()
                                }
                            }
                            .offset(width * tile.x + gap / 2, height * tile.y + gap / 2)
                            .size(width * tile.w - gap, height * tile.h - gap)
                        when (tile) {
                            is OverviewTile.Terminal -> TerminalTile(tile, spin, place) { onOpen(tile.key) }
                            is OverviewTile.DeskOnly -> DeskOnlyTile(place)
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    // `.omap`: the tab's map, larger, on the canvas colour.
                    Box(
                        Modifier
                            .padding(start = KuddeSpacing.s16, end = KuddeSpacing.s16, top = KuddeSpacing.s4, bottom = KuddeSpacing.s8)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(KuddeRadius.button))
                            .background(c.canvas)
                            .border(KuddeBorder.thin, c.border, RoundedCornerShape(KuddeRadius.button))
                            .padding(KuddeSpacing.s12),
                    ) {
                        TabMap(ui.map, width = width - 26.dp, height = 72.dp)
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        for (entry in ui.rows) {
                            item(key = entry.key) {
                                when (entry) {
                                    is SpacesItem.Agent -> {
                                        val open = { onOpen(entry.row.key) }
                                        if (entry.row.attention == AgentAttention.NEEDS_YOU || entry.row.attention == AgentAttention.ERROR) {
                                            NeedsYouCard(entry.row, clock, open)
                                        } else {
                                            AgentRow(entry.row, clock, spin, open)
                                        }
                                    }
                                    is SpacesItem.Other -> OtherRow(entry.row) { onOpen(entry.row.key) }
                                    else -> Unit
                                }
                            }
                        }
                        item(key = "end") { Spacer(Modifier.height(KuddeSpacing.s24)) }
                    }
                }
            }
        }
    }
}

/** `.otab`: the tab's map and name in a 48 dp chip; the shown one on the selected colour with the ring's border. */
@Composable
private fun TabChip(tab: OverviewTab, chosen: Boolean, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.button)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (chosen) c.selected else c.chrome)
            .border(KuddeBorder.thin, if (chosen) c.ring else c.input, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = tab.spoken
                role = Role.Button
                selected = chosen
                onClick { onClick(); true }
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KuddeSpacing.s10),
    ) {
        TabMap(tab.cells)
        BasicText(tab.name, style = KuddeType.settingRow.copy(color = if (chosen) c.fg else c.muted), maxLines = 1)
    }
}

/** `.otile` (components.paneTile): popover, 1 dp border, 12 corners, padding 12; mark and state over the name. */
@Composable
private fun TerminalTile(tile: OverviewTile.Terminal, spin: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    val shape = RoundedCornerShape(KuddeRadius.button)
    Column(
        modifier
            .clip(shape)
            .background(c.popover)
            .border(KuddeBorder.thin, c.border, shape)
            // Keep clickable's focus, keyboard, indication and TalkBack action. Cancel its
            // pointer gesture as soon as movement exceeds slop, even within the tile.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var dragged = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointer = event.changes.firstOrNull { it.id == down.id }
                        if (pointer == null || (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                            dragged = true
                        }
                        if (dragged) event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}
            .padding(KuddeSpacing.s12),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ProgramMark(tile.icon, size = 20.dp)
            Spacer(Modifier.weight(1f))
            Box(Modifier.width(StateSlot), contentAlignment = Alignment.Center) {
                when (tile.attention) {
                    AgentAttention.NEEDS_YOU, AgentAttention.ERROR -> Box(Modifier.size(9.dp).background(c.blocked, CircleShape))
                    AgentAttention.DONE -> Box(Modifier.size(9.dp).background(c.done, CircleShape))
                    AgentAttention.WORKING -> Spinner(spin)
                    AgentAttention.IDLE -> Ring()
                    null -> Unit
                }
            }
        }
        val motion = LocalAgentMotion.current
        BasicText(tile.name, modifier = Modifier.graphicsLayer {
            if (motion != null && tile.key == motion.overviewKey && motion.host.running && motion.host.motion.name == "pushOverview") {
                alpha = motion.host.progress.coerceIn(0f, 1f)
            }
        }, style = KuddeType.rowName.copy(color = c.fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** `.otile.ph`: transparent, a 1 dp dashed border in the input colour, "Desk only" centred; no tap. */
@Composable
private fun DeskOnlyTile(modifier: Modifier) {
    val c = KuddeTheme.colors
    Box(
        modifier.drawBehind {
            val line = 1.dp.toPx()
            val r = KuddeRadius.button.toPx()
            drawRoundRect(
                c.input,
                Offset(line / 2, line / 2),
                Size(size.width - line, size.height - line),
                CornerRadius(r - line / 2),
                style = Stroke(line, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        BasicText(stringResource(R.string.desk_only), style = KuddeType.secondary.copy(color = c.dimText))
    }
}
