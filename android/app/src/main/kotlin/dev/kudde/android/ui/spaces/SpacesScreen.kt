package dev.kudde.android.ui.spaces

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kudde.android.R
import dev.kudde.android.ui.agents.AgentRow
import dev.kudde.android.ui.agents.AgentsActions
import dev.kudde.android.ui.agents.AgentsEmpty
import dev.kudde.android.ui.agents.EmptyBlock
import dev.kudde.android.ui.agents.HomeSwitch
import dev.kudde.android.ui.agents.MachineNotice
import dev.kudde.android.ui.agents.NeedsYouCard
import dev.kudde.android.ui.agents.NewEntryUi
import dev.kudde.android.ui.agents.NewSheetActions
import dev.kudde.android.ui.agents.NewTerminalSheet
import dev.kudde.android.ui.agents.NoMachineSnack
import dev.kudde.android.ui.agents.NoMachines
import dev.kudde.android.ui.agents.OtherRow
import dev.kudde.android.ui.agents.Ring
import dev.kudde.android.ui.agents.Section
import dev.kudde.android.ui.agents.Spinner
import dev.kudde.android.ui.agents.StartFailed
import dev.kudde.android.ui.agents.StateSlot
import dev.kudde.android.ui.agents.StorageNotice
import dev.kudde.android.ui.agents.TopBar
import dev.kudde.android.ui.agents.slide
import dev.kudde.android.ui.theme.KuddeLayout
import dev.kudde.android.ui.theme.KuddeRadius
import dev.kudde.android.ui.theme.KuddeSpacing
import dev.kudde.android.ui.theme.KuddeTheme
import dev.kudde.android.ui.theme.KuddeType
import uniffi.kudde_mobile.AgentAttention

// The Spaces view of the home screen (design D6; renders spaces-dark and
// spaces-light, design/checkpoints/spaces-dark.png, spaces-light.png; sizes
// from .shdr, .tline and .tmap in design/renders/screens.html and
// components.spaceHeader, tabLine and tabMap in design-tokens.json). It shares
// the Agents view's top bar, notices, "New" entry, empty states and rows; only
// the order differs: the desk's. Pure: SpacesUi in, calls out.

/** The view's texts and spoken names from the app's resources (the approved table, task 1.2). */
fun spacesTexts(res: Resources) = SpacesTexts(
    unreadableTabs = { res.getQuantityString(R.plurals.spaces_unreadable_tabs, it.toInt(), it.toInt()) },
    spaceSpeech = { name, counts ->
        buildList {
            add(name)
            if (counts.needsYou > 0) add(res.getString(R.string.spaces_spoken_needs_you, counts.needsYou))
            if (counts.done > 0) add(res.getString(R.string.spaces_spoken_done, counts.done))
            if (counts.working > 0) add(res.getString(R.string.spaces_spoken_working, counts.working))
            if (counts.idle > 0) add(res.getString(R.string.spaces_spoken_idle, counts.idle))
        }.joinToString(", ")
    },
    tabSpeech = { name, terminals, needsYou -> tabMapSpoken(res, name, terminals, needsYou) },
)

/**
 * [state] is the route's, held above the open terminal, so Back from a
 * terminal shows the list where it was left (design D6). [onTab] opens the
 * tab overview of a machine's space at a tab. [needsYou] is the count the
 * switch shows on "Agents". [spin]: the working spinners turn and rows slide
 * into new places over 200 ms; false for reduced motion.
 */
@Composable
fun SpacesScreen(
    ui: SpacesUi,
    clock: State<Long>,
    spin: Boolean,
    actions: AgentsActions,
    onTab: (machine: String, spaceId: String, tabId: String) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    needsYou: Int = 0,
    onHomeView: (spaces: Boolean) -> Unit = {},
    new: NewEntryUi = NewEntryUi(enabled = true),
    newActions: NewSheetActions = NewSheetActions.Preview,
) {
    val c = KuddeTheme.colors
    Box(modifier.fillMaxSize().background(c.chrome)) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            TopBar(ui.machinesBadge, new.enabled, newActions.onNew, actions.onMachines) {
                HomeSwitch(spaces = true, needsYou = needsYou, onChoose = onHomeView)
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = state) {
                ui.storageNotice?.let { text -> item(key = "storage") { StorageNotice(text) } }
                for (notice in ui.notices) {
                    item(key = "notice/${notice.machine}") { MachineNotice(notice, actions.onMachines) }
                }
                when (ui.empty) {
                    AgentsEmpty.NoMachines -> item(key = "empty") { NoMachines(actions.onAddMachine) }
                    AgentsEmpty.StartFailed -> item(key = "empty") { StartFailed(ui.startError.orEmpty(), actions.onRetry) }
                    else -> Unit
                }
                if (ui.emptyText != null) {
                    item(key = "empty-spaces") {
                        EmptyBlock(stringResource(R.string.spaces_empty_title), stringResource(R.string.spaces_empty_body))
                    }
                }
                for (entry in ui.items) {
                    item(key = entry.key) {
                        val moves = slide(spin)
                        when (entry) {
                            is SpacesItem.MachineHeader -> Section(entry.name, c.muted, null, moves)
                            is SpacesItem.SpaceHeader -> SpaceHeaderRow(entry, spin, moves)
                            is SpacesItem.TabLine -> TabLineRow(entry, moves) { onTab(entry.machine, entry.spaceId, entry.tab.id) }
                            is SpacesItem.Agent -> {
                                val open = { actions.onOpen(entry.row.key) }
                                if (entry.row.attention == AgentAttention.NEEDS_YOU || entry.row.attention == AgentAttention.ERROR) {
                                    NeedsYouCard(entry.row, clock, open, moves)
                                } else {
                                    AgentRow(entry.row, clock, spin, open, moves)
                                }
                            }
                            is SpacesItem.Other -> OtherRow(entry.row, moves) { actions.onOpen(entry.row.key) }
                            is SpacesItem.LooseHeader -> Section(stringResource(R.string.spaces_loose), c.dimText, null, moves)
                            is SpacesItem.LayoutNote -> Note(stringResource(R.string.spaces_layout_unreadable), moves)
                        }
                    }
                }
                item(key = "end") { Spacer(Modifier.height(KuddeSpacing.s24)) }
            }
        }
        if (new.noMachine) NoMachineSnack(newActions.onNoMachineGone)
        new.sheet?.let { NewTerminalSheet(it, newActions) }
    }
}

/**
 * `.shdr` (components.spaceHeader): the space's name in 16/500, its most
 * urgent state's mark at the right in the row's 18 dp slot; spoken as its
 * name and each state's count. A count of unreadable tabs follows under it.
 */
@Composable
private fun SpaceHeaderRow(item: SpacesItem.SpaceHeader, spin: Boolean, modifier: Modifier) {
    val c = KuddeTheme.colors
    Column(modifier.padding(horizontal = KuddeLayout.listPaddingX).fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clearAndSetSemantics {
                    contentDescription = item.spokenText
                    heading()
                }
                .padding(start = KuddeLayout.rowPaddingX, end = KuddeLayout.rowPaddingX, top = KuddeSpacing.s6),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KuddeSpacing.s12),
        ) {
            BasicText(
                item.name,
                Modifier.weight(1f),
                style = KuddeType.rowName.copy(color = c.fg, fontWeight = FontWeight.W500),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(Modifier.width(StateSlot), contentAlignment = Alignment.Center) {
                when (item.attention) {
                    AgentAttention.NEEDS_YOU, AgentAttention.ERROR -> Box(Modifier.size(9.dp).background(c.blocked, CircleShape))
                    AgentAttention.DONE -> Box(Modifier.size(9.dp).background(c.done, CircleShape))
                    AgentAttention.WORKING -> Spinner(spin)
                    AgentAttention.IDLE -> Ring()
                    null -> Unit
                }
            }
        }
        item.unreadableText?.let {
            BasicText(
                it,
                Modifier.padding(start = KuddeLayout.rowPaddingX, end = KuddeLayout.rowPaddingX, bottom = KuddeSpacing.s4),
                style = KuddeType.secondary.copy(color = c.dimText),
            )
        }
    }
}

/**
 * `.tline` (components.tabLine): the tab's map at the rows' mark position,
 * then its name in the secondary type, muted; a tap opens the tab overview.
 * Spoken as the map's name ("Tab main, 3 terminals, 1 needs you").
 */
@Composable
private fun TabLineRow(item: SpacesItem.TabLine, modifier: Modifier, onClick: () -> Unit) {
    val c = KuddeTheme.colors
    Row(
        modifier
            .padding(horizontal = KuddeLayout.listPaddingX)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(KuddeRadius.row))
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = item.spokenText
                role = Role.Button
                onClick { onClick(); true }
            }
            .padding(horizontal = KuddeLayout.rowPaddingX),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KuddeSpacing.s12),
    ) {
        TabMap(item.cells)
        BasicText(item.tab.name, style = KuddeType.secondary.copy(color = c.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "Layout could not be read": a machine whose document is not a layout. */
@Composable
private fun Note(text: String, modifier: Modifier) {
    BasicText(
        text,
        modifier.padding(horizontal = KuddeLayout.listPaddingX + KuddeLayout.rowPaddingX, vertical = KuddeSpacing.s8),
        style = KuddeType.secondary.copy(color = KuddeTheme.colors.dimText),
    )
}
