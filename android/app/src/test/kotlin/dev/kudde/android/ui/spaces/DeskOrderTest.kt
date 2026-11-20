package dev.kudde.android.ui.spaces

import dev.kudde.android.ui.agents.*
import org.junit.Assert.*
import org.junit.Test
import uniffi.kudde_mobile.*

class DeskOrderTest {
    private fun cell(address: String) = DeskCellView(DeskPaneView.Terminal(address), 0f, 0f, 1f, 1f)
    private fun tab(id: String, vararg addresses: String) = DeskTabView(id, id, addresses.map(::cell))
    private fun space(id: String, vararg tabs: DeskTabView) = DeskSpaceView(id, id, 0u, tabs.toList())
    private fun desk(vararg spaces: DeskSpaceView, loose: List<String> = emptyList()) = DeskView(spaces.toList(), loose, null)
    private fun order(desk: DeskView, current: AgentKey, rows: List<String> = listOf("a", "b", "c", "shell")) = DeskOrder.stops(
        snapshot(listOf(connected("workstation").copy(desk = desk)), rows.map { row(it, AgentAttention.WORKING) }),
        current,
    )
    private fun key(address: String) = AgentKey("workstation", address)

    @Test
    fun nextPaneIncludesShellAndPreviousReverses() {
        val stops = DeskOrder.stops(snapshot(listOf(connected("workstation").copy(desk = desk(space("s", tab("t", "a", "b", "shell"))))),
            listOf(row("a", AgentAttention.WORKING), row("b", AgentAttention.IDLE)), listOf(other("shell"))), key("a"))
        assertEquals(key("b"), DeskOrder.next(stops, key("a"))!!.key)
        assertEquals(key("shell"), DeskOrder.next(stops, key("b"))!!.key)
        assertEquals(key("b"), DeskOrder.previous(stops, key("shell"))!!.key)
    }

    @Test
    fun stopsAtTheTabsEnd() {
        val layout = desk(space("s1", tab("t1", "a", "b"), tab("t2", "c", "shell")), space("s2", tab("t3", "d")))
        val rows = listOf("a", "b", "c", "shell", "d")
        val first = order(layout, key("b"), rows)
        assertNull(DeskOrder.next(first, key("b")))
        assertEquals(key("a"), DeskOrder.previous(first, key("b"))!!.key)
        val second = order(layout, key("c"), rows)
        assertNull(DeskOrder.previous(second, key("c")))
        assertEquals(key("shell"), DeskOrder.next(second, key("c"))!!.key)
    }

    @Test
    fun endsNeverWrap() {
        val stops = order(desk(space("s", tab("t", "a", "b"))), key("b"))
        assertNull(DeskOrder.next(stops, key("b")))
        assertNull(DeskOrder.previous(stops, key("a")))
        assertNull(DeskOrder.next(emptyList(), key("a")))
    }

    @Test
    fun placeholderIsPassedOver() {
        val tab = tab("t", "a", "b").let { it.copy(cells = listOf(it.cells[0], DeskCellView(DeskPaneView.Placeholder("Desk only"), .3f, 0f, .3f, 1f), it.cells[1])) }
        assertEquals(key("b"), DeskOrder.next(order(desk(space("s", tab)), key("a")), key("a"))!!.key)
    }

    @Test
    fun subAgentVacantPaneIsPassedOverDespiteOtherRow() {
        val tab = tab("t", "a", "b").let { it.copy(cells = listOf(it.cells[0], DeskCellView(DeskPaneView.Vacant, .3f, 0f, .3f, 1f), it.cells[1])) }
        val s = snapshot(listOf(connected("workstation").copy(desk = desk(space("s", tab)))), listOf(row("a", AgentAttention.IDLE), row("b", AgentAttention.IDLE)), listOf(other("child")))
        assertEquals(listOf(key("a"), key("b")), DeskOrder.stops(s, key("a")).map { it.key })
    }

    @Test
    fun fromSubAgentTerminalNothingOpens() {
        val stops = order(desk(space("s", tab("t", "a", "b"))), key("child"))
        assertNull(DeskOrder.next(stops, key("child")))
        assertNull(DeskOrder.previous(stops, key("child")))
    }

    @Test
    fun neverLeavesTheMachine() {
        val alone = desk(space("s", tab("t", "a")))
        val full = desk(space("s", tab("t", "a", "b")))
        val s = snapshot(listOf(connected("workstation").copy(desk = alone), unreachable("server", 100).copy(desk = full), connected("third").copy(desk = full)),
            listOf("third", "server", "workstation").flatMap { m -> listOf(row("a", AgentAttention.IDLE, machine = m), row("b", AgentAttention.IDLE, machine = m)) })
        val stops = DeskOrder.stops(s, key("a"))
        assertNull(DeskOrder.next(stops, key("a")))
        assertNull(DeskOrder.previous(stops, key("a")))
    }

    @Test
    fun openedFromStateListUsesDeskOrder() {
        val stops = order(desk(space("s", tab("t", "a", "c", "b"))), key("c"), listOf("c", "b", "a"))
        assertEquals(key("b"), DeskOrder.next(stops, key("c"))!!.key)
    }

    @Test
    fun reorderedAroundCurrentUsesNewLayout() {
        assertEquals(key("c"), DeskOrder.next(order(desk(space("s", tab("t", "a", "b", "c"))), key("b")), key("b"))!!.key)
        assertEquals(key("a"), DeskOrder.next(order(desk(space("s", tab("t", "c", "b", "a"))), key("b")), key("b"))!!.key)
    }

    @Test
    fun movedOpenPaneUsesItsNewTab() {
        val stops = order(desk(space("s", tab("main", "b", "c"), tab("2", "a"), tab("3", "shell"))), key("a"))
        assertEquals("2", stops.single { it.key == key("a") }.tabId)
        assertNull(DeskOrder.next(stops, key("a")))
    }

    @Test
    fun looseTerminalHasNoNextOrPrevious() {
        val stops = order(desk(space("s", tab("t", "b", "c")), loose = listOf("a", "shell")), key("a"))
        assertTrue(stops.isEmpty())
        assertNull(DeskOrder.previous(stops, key("a")))
        assertNull(DeskOrder.next(stops, key("a")))
    }

    @Test
    fun currentOutsideStopsAndMissingRowsHaveNoInventedStop() {
        val stops = order(desk(space("s", tab("t", "a", "missing", "b")), loose = listOf("missing")), key("a"))
        assertEquals(listOf(key("a"), key("b")), stops.map { it.key })
        assertNull(DeskOrder.next(stops, key("gone")))
        assertNull(DeskOrder.previous(stops, key("gone")))
    }

    @Test
    fun absentDeskAndDisconnectedCurrentHaveNoStops() {
        val s = snapshot(listOf(connected("workstation"), unreachable("server", 100).copy(desk = desk(space("s", tab("t", "a"))))), listOf(row("a", AgentAttention.IDLE)))
        assertTrue(DeskOrder.stops(s, key("a")).isEmpty())
    }

    @Test
    fun tabOfOnePaneHasNoNextAndNoPrevious() {
        val stops = order(desk(space("s", tab("t1", "b"), tab("t2", "a"), tab("t3", "c"))), key("a"))
        assertEquals(listOf(DeskStop(key("a"), "s", "t2")), stops)
        assertNull(DeskOrder.next(stops, key("a")))
        assertNull(DeskOrder.previous(stops, key("a")))
    }

    @Test
    fun stopsHoldOnlyTheOwnTab() {
        val workstation = connected("workstation").copy(desk = desk(
            space("s1", tab("t0", "x"), tab("t1", "a", "b", "c"), tab("t2", "d")),
            space("s2", tab("t3", "e")),
            loose = listOf("shell"),
        ))
        val server = connected("server").copy(desk = desk(space("s1", tab("t1", "a", "b", "f"))))
        val rows = listOf("x", "a", "b", "c", "d", "e", "shell").map { row(it, AgentAttention.IDLE) } +
            listOf("a", "b", "f").map { row(it, AgentAttention.IDLE, machine = "server") }
        val stops = DeskOrder.stops(snapshot(listOf(workstation, server), rows), key("b"))
        assertEquals(listOf(DeskStop(key("a"), "s1", "t1"), DeskStop(key("b"), "s1", "t1"), DeskStop(key("c"), "s1", "t1")), stops)
    }

    @Test
    fun terminalOfMachineNotConnectedHasNoStops() {
        val layout = desk(space("s", tab("t", "a", "b")))
        val s = snapshot(listOf(unreachable("server", 100).copy(desk = layout), connected("workstation").copy(desk = layout)),
            listOf("a", "b").flatMap { listOf(row(it, AgentAttention.IDLE, machine = "server"), row(it, AgentAttention.IDLE)) })
        assertTrue(DeskOrder.stops(s, AgentKey("server", "a")).isEmpty())
        assertNull(DeskOrder.next(DeskOrder.stops(s, AgentKey("server", "a")), AgentKey("server", "a")))
    }
}
