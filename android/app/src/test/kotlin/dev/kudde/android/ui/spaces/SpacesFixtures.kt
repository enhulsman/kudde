package dev.kudde.android.ui.spaces

import dev.kudde.android.ui.agents.connected
import dev.kudde.android.ui.agents.other
import dev.kudde.android.ui.agents.row
import dev.kudde.android.ui.agents.snapshot
import dev.kudde.android.ui.agents.unreachable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import uniffi.kudde_mobile.AgentAttention
import uniffi.kudde_mobile.DeskCellView
import uniffi.kudde_mobile.DeskPaneView
import uniffi.kudde_mobile.DeskSpaceView
import uniffi.kudde_mobile.DeskTabView
import uniffi.kudde_mobile.DeskView

// The renders' desk (design/renders/screens.html, spaces-dark and
// tab-overview), with invented names: workstation's space "website" with the
// tab "main" (Pricing page copy on the left; og tags, a shell and a mod pane
// stacked on the right), "2" and "build"; changelog in no pane; server
// unreachable for 2 h with its space "infra" as last seen.
object SpacesFixtures {
    /** 14:07 UTC, the renders' clock. */
    const val NOW = 1_800_000_000_000L + 6 * 3_600_000L + 7 * 60_000L
    const val SECOND = 1_000L
    const val MINUTE = 60 * SECOND
    const val HOUR = 60 * MINUTE

    fun utc(ms: Long): String =
        DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC))

    fun term(address: String, x: Float, y: Float, w: Float, h: Float) = DeskCellView(DeskPaneView.Terminal(address), x, y, w, h)
    fun mod(x: Float, y: Float, w: Float, h: Float) = DeskCellView(DeskPaneView.Placeholder("browser"), x, y, w, h)

    val MAIN = DeskTabView(
        "tb-main", "main",
        listOf(
            term("t1", 0f, 0f, .5f, 1f),
            term("t2", .5f, 0f, .5f, 1f / 3),
            term("t3", .5f, 1f / 3, .5f, 1f / 3),
            mod(.5f, 2f / 3, .5f, 1f / 3),
        ),
    )
    val TWO = DeskTabView("tb-2", "2", listOf(term("t5", 0f, 0f, 1f, 1f)))
    val BUILD = DeskTabView("tb-build", "build", (0 until 6).map { term("b$it", it / 6f, 0f, 1f / 6, 1f) })

    /** The Spaces render: "main" only, as spaces-dark shows it. */
    val spacesRender = snapshot(
        machines = listOf(
            connected("workstation").copy(
                desk = DeskView(listOf(DeskSpaceView("s-website", "website", 0u, listOf(MAIN))), listOf("t4"), null),
            ),
            unreachable("server", NOW - 2 * HOUR - 2 * MINUTE).copy(
                desk = DeskView(
                    listOf(DeskSpaceView("s-infra", "infra", 0u, listOf(DeskTabView("tb-1", "1", listOf(term("t9", 0f, 0f, 1f, 1f)))))),
                    emptyList(),
                    null,
                ),
            ),
        ),
        agents = listOf(
            row("t1", AgentAttention.NEEDS_YOU, name = "Pricing page copy", place = "website / main", kind = "Permission",
                request = "Bash: npm run build", since = NOW - 20 * SECOND),
            row("t2", AgentAttention.WORKING, name = "og tags", place = "website / main", program = "codex", progress = 60, since = NOW - 2 * MINUTE),
            row("t4", AgentAttention.WORKING, name = "changelog", folder = "docs", program = "codex", since = NOW - 9 * MINUTE),
            row("t9", AgentAttention.IDLE, machine = "server", name = "backup notes", place = "infra / 1", since = NOW - 2 * HOUR - 4 * MINUTE),
        ),
        others = listOf(other("t3", name = "zsh", place = "website / main", program = "zsh")),
    )

    /** The tab overview render: website's three tabs; "build" holds six panes side by side. */
    val overviewRender = snapshot(
        machines = listOf(
            connected("workstation").copy(
                desk = DeskView(listOf(DeskSpaceView("s-website", "website", 0u, listOf(MAIN, TWO, BUILD))), emptyList(), null),
            ),
        ),
        agents = listOf(
            row("t1", AgentAttention.NEEDS_YOU, name = "Pricing page copy", place = "website / main", kind = "Permission",
                request = "Bash: npm run build", since = NOW - 20 * SECOND),
            row("t2", AgentAttention.WORKING, name = "og tags", place = "website / main", program = "codex", since = NOW - 2 * MINUTE),
            row("t5", AgentAttention.DONE, name = "readme", place = "website / 2", since = NOW - 3 * MINUTE),
            row("b0", AgentAttention.WORKING, name = "build fixes", place = "website / build", since = NOW - MINUTE),
            row("b3", AgentAttention.DONE, name = "flaky test", place = "website / build", program = "codex", since = NOW - 5 * MINUTE),
        ),
        others = listOf(
            other("t3", name = "zsh", place = "website / main", program = "zsh"),
            other("b1", name = "npm run dev", place = "website / build", program = "npm"),
            other("b2", name = "npm test", place = "website / build", program = "npm"),
            other("b4", name = "htop", place = "website / build", program = "htop"),
            other("b5", name = "zsh", place = "website / build", program = "zsh"),
        ),
    )
}
