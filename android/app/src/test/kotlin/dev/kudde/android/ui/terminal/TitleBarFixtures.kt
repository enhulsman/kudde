package dev.kudde.android.ui.terminal

import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.agents.ProgramIcon
import dev.kudde.android.ui.agents.connected
import dev.kudde.android.ui.agents.other
import dev.kudde.android.ui.agents.row
import dev.kudde.android.ui.agents.snapshot
import dev.kudde.android.ui.spaces.MapCell
import dev.kudde.android.ui.spaces.MapKind
import uniffi.kudde_mobile.AgentAttention
import uniffi.kudde_mobile.DeskCellView
import uniffi.kudde_mobile.DeskPaneView
import uniffi.kudde_mobile.DeskSpaceView
import uniffi.kudde_mobile.DeskTabView
import uniffi.kudde_mobile.DeskView

// The render terminal-map's title bars (design/renders/screens.html, TERM,
// CODEX and CLAUDE), with invented names: Pricing page copy first of three
// panes in website's tab "main" (og tags working beside it, a shell under
// that); api tests second of two in api's tab "2".
object TitleBarFixtures {
    private fun term(address: String, x: Float, y: Float, w: Float, h: Float) =
        DeskCellView(DeskPaneView.Terminal(address), x, y, w, h)

    val pricing = AgentKey("workstation", "t1")
    val apiTests = AgentKey("workstation", "t6")

    /** website: "main" (t1 | t2 over t3), "solo" (t4 alone); api: "2" (t5 | t6); t7 in no pane. */
    val hub = snapshot(
        machines = listOf(
            connected("workstation").copy(
                desk = DeskView(
                    listOf(
                        DeskSpaceView(
                            "s-website", "website", 0u,
                            listOf(
                                DeskTabView("tb-main", "main", listOf(term("t1", 0f, 0f, .5f, 1f), term("t2", .5f, 0f, .5f, .5f), term("t3", .5f, .5f, .5f, .5f))),
                                DeskTabView("tb-solo", "solo", listOf(term("t4", 0f, 0f, 1f, 1f))),
                            ),
                        ),
                        DeskSpaceView("s-api", "api", 0u, listOf(DeskTabView("tb-2", "2", listOf(term("t5", 0f, 0f, .5f, 1f), term("t6", .5f, 0f, .5f, 1f))))),
                    ),
                    listOf("t7"),
                    null,
                ),
            ),
        ),
        agents = listOf(
            row("t1", AgentAttention.IDLE, name = "Pricing page copy", place = "website / main"),
            row("t2", AgentAttention.WORKING, name = "og tags", place = "website / main", program = "codex"),
            row("t4", AgentAttention.IDLE, name = "readme", place = "website / solo"),
            row("t6", AgentAttention.IDLE, name = "api tests", place = "api / 2", program = "codex"),
            row("t7", AgentAttention.WORKING, name = "changelog", folder = "docs", program = "codex"),
        ),
        others = listOf(
            other("t3", name = "zsh", place = "website / main", program = "zsh"),
            other("t5", name = "zsh", place = "api / 2", program = "zsh"),
        ),
    )

    /** The chat strips' headers and maps, as the render draws them. */
    val pricingHeader = TerminalHeader("Pricing page copy", ProgramIcon.Claude, "website / main · workstation")
    val pricingMap = TitleMap(
        listOf(MapCell(0f, 0f, .5f, 1f, MapKind.MARKED), MapCell(.5f, 0f, .5f, .5f, MapKind.WORKING), MapCell(.5f, .5f, .5f, .5f, MapKind.PLAIN)),
        index = 1, count = 3, spaceId = "s-website", tabId = "tb-main",
    )
    val apiTestsHeader = TerminalHeader("api tests", ProgramIcon.Codex, "api / 2 · workstation")
    val apiTestsMap = TitleMap(
        listOf(MapCell(0f, 0f, .5f, 1f, MapKind.PLAIN), MapCell(.5f, 0f, .5f, 1f, MapKind.MARKED)),
        index = 2, count = 2, spaceId = "s-api", tabId = "tb-2",
    )
}
