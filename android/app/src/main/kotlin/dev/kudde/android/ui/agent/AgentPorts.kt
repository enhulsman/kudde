package dev.kudde.android.ui.agent

import android.os.SystemClock
import dev.kudde.android.KuddeApp
import dev.kudde.android.ui.agents.AgentKey
import uniffi.kudde_mobile.ChangesInterface
import uniffi.kudde_mobile.ChatInterface
import uniffi.kudde_mobile.TerminalListener
import uniffi.kudde_mobile.TerminalViewInterface

/** The native objects and monotonic time used by one visit to an agent. */
interface AgentPorts {
    fun openTerminal(key: AgentKey, cols: UShort, rows: UShort, lead: Boolean, listener: TerminalListener): TerminalViewInterface?
    fun openChat(key: AgentKey): ChatInterface?
    fun openChanges(key: AgentKey): ChangesInterface?
    fun now(): Long
}

/** Reads the current hub on each open, including after a failed start or a reconnect. */
class HubPorts(private val app: KuddeApp) : AgentPorts {
    override fun openTerminal(key: AgentKey, cols: UShort, rows: UShort, lead: Boolean, listener: TerminalListener): TerminalViewInterface? =
        app.hubHost.hub?.openTerminal(key.machine, key.address, cols, rows, lead, listener)

    override fun openChat(key: AgentKey): ChatInterface? =
        app.hubHost.hub?.openChat(key.machine, key.address)

    override fun openChanges(key: AgentKey): ChangesInterface? =
        app.hubHost.hub?.openChanges(key.machine, key.address)

    override fun now(): Long = SystemClock.uptimeMillis()
}
