package dev.kudde.android.ui.agent

import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.chat.ChatVisit
import dev.kudde.android.ui.terminal.GridSize
import dev.kudde.android.ui.terminal.SizeSettler
import dev.kudde.android.ui.terminal.TerminalActions
import dev.kudde.android.ui.terminal.TerminalMetrics
import dev.kudde.android.ui.terminal.TerminalOpener
import dev.kudde.android.ui.terminal.TerminalReopen
import dev.kudde.android.ui.terminal.TerminalScreenState
import dev.kudde.android.ui.terminal.TerminalSession
import java.util.concurrent.Executor

/**
 * One agent visit, independent of composition. The route owns effects and delays.
 * Until the screen host migration, [legacyRoute] preserves the layered
 * route's attach rule and disables automatic seeds; it uses the opener directly.
 * The destination API below is ready for a host that measures only its top.
 */
class AgentKeeper(
    private val key: AgentKey,
    private val ports: AgentPorts,
    private val main: Executor,
    var chat: ChatVisit,
    private val arrivedByStep: Boolean = false,
    zoom: Float? = null,
    private val estimate: GridSize? = null,
    private val lastPhoneGrid: () -> GridSize? = { null },
    private val rememberPhoneGrid: (GridSize) -> Unit = {},
    private val changed: () -> Unit = {},
    private val legacyRoute: Boolean = false,
) {
    val reopen = TerminalReopen()
    val screen = TerminalScreenState(zoom)
    var phone: GridSize? = null
        private set
    private var released = false
    private var leaving = false
    private var heldActions: TerminalActions? = null
    private var actionsOwner: Any? = null

    val stack = AgentStack {
        // A mid-settle offer must be dropped even if push and pop precede a tick.
        dropPending(current)
        changed()
    }
    val top: AgentScreen get() = stack.top(chat.switch.chat)
    val measured: Boolean get() = current.measured
    val session: TerminalSession get() = current.session

    /** Session, opener and hook are replaced together; an old set is single-use. */
    class Generation internal constructor(
        val number: Int,
        val session: TerminalSession,
        val opener: TerminalOpener,
        val onInputWithoutView: () -> Unit,
    ) {
        internal var closed = false
        internal var opened = false
        internal var measured = false
        internal var leading = false
        internal var sent: GridSize? = null
        internal var settler: SizeSettler? = null
    }

    private var current = makeGeneration(ports.now())

    init {
        if (!legacyRoute && chat.switch.chat) seed(current, ports.now())
    }

    /** Called after observing [TerminalReopen.generation]; reopening never dwells. */
    fun generation(nowMs: Long): Generation {
        if (!released && current.number != reopen.generation) {
            closeGeneration(current)
            current = makeGeneration(nowMs)
            if (!legacyRoute && (phone != null || chat.switch.chat)) seed(current, nowMs)
        }
        return current
    }

    private fun makeGeneration(nowMs: Long): Generation {
        val session = TerminalSession(key, main)
        val switch = chat.switch
        val dwell = if (reopen.generation == 0 && arrivedByStep) TerminalOpener.STEP_DWELL_MS else 0L
        lateinit var next: Generation
        val opener = TerminalOpener(session, arrivalMs = nowMs, dwellMs = dwell) { size ->
            val lead = if (legacyRoute) !switch.chat else top == AgentScreen.Terminal && next.measured
            ports.openTerminal(key, size.cols.toUShort(), size.rows.toUShort(), lead, session.listener)?.also {
                next.opened = true
                next.leading = lead
                next.sent = if (lead) size else null
                dropPending(next)
            }
        }
        val hook = { opener.openNow(ports.now()) }
        next = Generation(reopen.generation, session, opener, hook)
        session.onInputWithoutView = hook
        return next
    }

    private fun seed(generation: Generation, nowMs: Long) {
        val size = phone ?: lastPhoneGrid() ?: estimate ?: return
        generation.opener.onPhoneGrid(size, nowMs)
    }

    /** Keeps the existing route's grid observation without activating destination rules. */
    fun rememberGrid(size: GridSize) {
        if (released || phone == size) return
        phone = size
        changed()
    }

    /** Actions outlive destinations, like the screen and chat visit. */
    fun actions(owner: Any = this, create: () -> TerminalActions): TerminalActions {
        val held = heldActions
        if (held != null && actionsOwner == owner) return held
        return create().also {
            actionsOwner = owner
            heldActions = it
        }
    }

    /** Only a visible terminal may measure; seeds never set [measured] or [phone]. */
    fun onTerminalGrid(size: GridSize, nowMs: Long) {
        if (released) return
        check(top == AgentScreen.Terminal) { "Only the top terminal may measure a grid" }
        val generation = generation(nowMs)
        generation.measured = true
        rememberGrid(size)
        rememberPhoneGrid(size)
        if (!generation.opened) {
            generation.opener.onPhoneGrid(size, nowMs)
        } else if (!generation.leading) {
            // Rust stores this size while watching; lead then takes the real grid.
            generation.session.phoneSize(size)
            generation.sent = size
            generation.session.follow(chat = false)
            generation.leading = true
            dropPending(generation)
        } else {
            generation.settler?.offer(size, nowMs)
        }
    }

    /** A base change under a cover does nothing. Returning may use a measured grid. */
    fun terminalShown(nowMs: Long) {
        if (released) return
        val generation = generation(nowMs)
        when (top) {
            AgentScreen.Terminal -> if (generation.measured) phone?.let { onTerminalGrid(it, nowMs) }
            AgentScreen.Chat -> {
                dropPending(generation)
                if (generation.opened && generation.leading) {
                    generation.session.follow(chat = true)
                    generation.leading = false
                }
            }
            else -> Unit
        }
    }

    /** Dwell still opens under a cover; only an on-top terminal settles sizes. */
    fun tick(nowMs: Long) {
        if (released) return
        val generation = generation(nowMs)
        if (!generation.opened) generation.opener.tick(nowMs)
        if (top == AgentScreen.Terminal) generation.settler?.tick(nowMs)
        else dropPending(generation)
    }

    private fun dropPending(generation: Generation) {
        generation.settler = SizeSettler(TerminalMetrics.SETTLE_MS, opened = generation.sent) { size ->
            generation.session.phoneSize(size)
            generation.sent = size
        }
    }

    /** Reads the live stack, including a preceding Back before the next frame. */
    fun back(leave: () -> Unit) {
        if (released || leaving) return
        if (!stack.pop()) {
            leaving = true
            leave()
        }
    }

    fun disposeOpener(generation: Generation) {
        generation.opener.dispose()
        generation.session.onInputWithoutView = null
    }

    /** Used by cache disposal and idle effects alike, so a set detaches once. */
    fun closeGeneration(generation: Generation): ByteArray? {
        if (generation.closed) return null
        generation.closed = true
        disposeOpener(generation)
        return generation.session.close()
    }

    /** Leaving the agent, from any destination. The caller stores the returned frame. */
    fun release(): ByteArray? {
        if (released) return null
        released = true
        return closeGeneration(current)
    }
}
