package dev.kudde.android.ui.spaces

import dev.kudde.android.ui.agents.AgentKey
import uniffi.kudde_mobile.DeskPaneView
import uniffi.kudde_mobile.HubSnapshot
import uniffi.kudde_mobile.MachineState

/** A stop's position in the current desk. Loose terminals are never stops. */
data class DeskStop(val key: AgentKey, val spaceId: String, val tabId: String)

/** Stateless: callers read the current snapshot when the gesture ends (design D3). */
object DeskOrder {
    /**
     * The stops of [current]'s own tab, in desk order, where a row exists: the tab on
     * [current]'s machine (connected, with a desk) whose cells hold [current]'s address.
     * Empty when [current] is in no tab (loose, a sub-agent, gone, or its machine is not connected).
     */
    fun stops(snapshot: HubSnapshot, current: AgentKey): List<DeskStop> {
        val machine = snapshot.machines.firstOrNull { it.name == current.machine } ?: return emptyList()
        if (machine.state !is MachineState.Connected) return emptyList()
        val desk = machine.desk ?: return emptyList()
        val listed = buildSet {
            snapshot.agents.forEach { if (it.machine == machine.name) add(it.address) }
            snapshot.others.forEach { if (it.machine == machine.name) add(it.address) }
        }
        for (space in desk.spaces) for (tab in space.tabs) {
            val addresses = tab.cells.mapNotNull { (it.pane as? DeskPaneView.Terminal)?.address }
            if (current.address !in addresses) continue
            return addresses.filter { it in listed }.map { DeskStop(AgentKey(machine.name, it), space.id, tab.id) }
        }
        return emptyList()
    }

    fun next(stops: List<DeskStop>, current: AgentKey): DeskStop? = step(stops, current, 1)

    fun previous(stops: List<DeskStop>, current: AgentKey): DeskStop? = step(stops, current, -1)

    private fun step(stops: List<DeskStop>, current: AgentKey, direction: Int): DeskStop? {
        val index = stops.indexOfFirst { it.key == current }
        if (index < 0) return null
        return stops.getOrNull(index + direction)
    }
}
