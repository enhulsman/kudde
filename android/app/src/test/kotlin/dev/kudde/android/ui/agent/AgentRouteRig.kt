package dev.kudde.android.ui.agent

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.RecomposerInfo
import androidx.compose.runtime.snapshots.Snapshot
import dev.kudde.android.KuddeApp
import dev.kudde.android.testing.BoundedFrames
import dev.kudde.android.testing.PopupSafeClock
import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.agents.AgentsHome
import dev.kudde.android.ui.chat.ChatStates
import dev.kudde.android.ui.chat.ChatViewSetting
import dev.kudde.android.ui.terminal.ZoomMemory
import dev.kudde.android.ui.spaces.SpacesFixtures
import dev.kudde.android.ui.terminal.FrameCipher
import dev.kudde.android.ui.terminal.TerminalCache
import dev.kudde.android.ui.terminal.FakeTerminalView
import dev.kudde.android.ui.terminal.cellsOf
import dev.kudde.android.ui.terminal.frameOf
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAccessibilityManager
import org.robolectric.shadows.ShadowChoreographer
import uniffi.kudde_mobile.ChangesInterface
import uniffi.kudde_mobile.ChangesList
import uniffi.kudde_mobile.ChangesView
import uniffi.kudde_mobile.ChatFound
import uniffi.kudde_mobile.ChatHealth
import uniffi.kudde_mobile.ChatInterface
import uniffi.kudde_mobile.ChatPoll
import uniffi.kudde_mobile.ChatRow
import uniffi.kudde_mobile.ChatSent
import uniffi.kudde_mobile.ChatUpload
import uniffi.kudde_mobile.HubSnapshot
import uniffi.kudde_mobile.TermPhase
import uniffi.kudde_mobile.TermState
import uniffi.kudde_mobile.TerminalListener
import uniffi.kudde_mobile.TerminalViewInterface

/** Fixtures and a bounded clock only. The tests themselves compose the production routes. */
class AgentRouteRig {
    val app = RuntimeEnvironment.getApplication() as KuddeApp
    val key = AgentKey("workstation", "t1")
    val ports = FakeAgentPorts()
    var home = AgentsHome(app.hubHost, now = ports::now) { true }
    var snapshot = SpacesFixtures.overviewRender
        private set

    private var restore: (() -> Unit)? = null
    private var cacheDir: File? = null
    lateinit var cache: TerminalCache
        private set
    private var priorRecomposers = emptySet<RecomposerInfo>()
    private val recomposers = mutableSetOf<RecomposerInfo>()

    /** Called by @Before, never by a field initializer. Every replacement has an exact inverse. */
    fun install() {
        check(restore == null) { "rig is already installed" }
        val wasPaused = ShadowChoreographer.isPaused()
        val motion = Settings.Global.getString(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        val oldCache = app.terminalCache
        val oldStates = app.chatStates
        val oldZoom = app.terminalZoom
        val oldChoice = app.chatView
        val oldSnapshot = snapshotFlow().value
        val oldIdle = idleSignal().value
        val manager = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = manager.isEnabled
        val exploration = manager.isTouchExplorationEnabled
        val events = accessibilityEvents().toList()
        val preferences = "agent_route_" + UUID.randomUUID().toString()
        priorRecomposers = Recomposer.runningRecomposers.value
        restorePlatform = {
            try {
                accessibilityEvents().apply { clear(); addAll(events) }
            } finally {
                ShadowChoreographer.setPaused(wasPaused)
            }
        }
        restore = {
            replace("terminalCache", oldCache)
            replace("chatStates", oldStates)
            replace("terminalZoom", oldZoom)
            replace("chatView", oldChoice)
            snapshotFlow().value = oldSnapshot
            idleSignal().value = oldIdle
            app.getSharedPreferences(preferences, Context.MODE_PRIVATE).edit().clear().commit()
            app.deleteSharedPreferences(preferences)
            Settings.Global.putString(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, motion)
            shadowOf(manager).setTouchExplorationEnabled(exploration)
            shadowOf(manager).setEnabled(enabled)
        }
        try {
            PopupSafeClock.paused()
            cacheDir = File(app.cacheDir, preferences).apply { mkdirs() }
            cache = TerminalCache(cacheDir!!, object : FrameCipher {
                override fun seal(plain: ByteArray) = plain.copyOf()
                override fun open(sealed: ByteArray) = sealed.copyOf()
            }, System::currentTimeMillis)
            replace("terminalCache", cache)
            replace("chatStates", ChatStates())
            replace("terminalZoom", ZoomMemory())
            replace("chatView", ChatViewSetting(app.getSharedPreferences(preferences, Context.MODE_PRIVATE)))
            Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            publish(snapshot)
        } catch (failure: Throwable) {
            try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private var restorePlatform: (() -> Unit)? = null

    private fun replace(name: String, value: Any) =
        app.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(app, value)

    @Suppress("UNCHECKED_CAST")
    private fun snapshotFlow(): MutableStateFlow<HubSnapshot> =
        app.hubHost.javaClass.getDeclaredField("state").apply { isAccessible = true }
            .get(app.hubHost) as MutableStateFlow<HubSnapshot>

    @Suppress("UNCHECKED_CAST")
    private fun idleSignal(): MutableStateFlow<Int> =
        app.javaClass.getDeclaredField("idle").apply { isAccessible = true }.get(app) as MutableStateFlow<Int>

    @Suppress("UNCHECKED_CAST")
    private fun accessibilityEvents(): MutableList<AccessibilityEvent> =
        ShadowAccessibilityManager::class.java.getDeclaredField("sentAccessibilityEvents")
            .apply { isAccessible = true }.get(null) as MutableList<AccessibilityEvent>

    /** The snapshot is normally published by HubHost's native callback; no native hub in this rig. */
    fun publish(value: HubSnapshot) {
        snapshot = value
        snapshotFlow().value = value
    }

    /** The activity must already be destroyed. Finish IO and its main-thread cancellations before restoring globals. */
    fun close() {
        val undo = restore ?: return
        restore = null
        try {
            ports.stopIo()
            await("route recomposers and fake hub IO finish teardown") {
                ports.io.inFlight == 0 && recomposers.all(::shutDown)
            }
            app.terminalCacheExecutor.submit {}.get(2, TimeUnit.SECONDS)
        } finally {
            try {
                undo()
                cacheDir?.deleteRecursively()
                // Restoring app state and accessibility can themselves enqueue Compose work.
                Snapshot.sendApplyNotifications()
                BoundedFrames.settle(minFrames = 4)
            } finally {
                restorePlatform?.invoke()
                restorePlatform = null
            }
        }
    }

    @Suppress("UNCHECKED_CAST", "DEPRECATION")
    private fun shutDown(info: RecomposerInfo): Boolean =
        (info.state as StateFlow<Recomposer.State>).value == Recomposer.State.ShutDown

    fun frames(n: Int = 4) {
        recomposers += Recomposer.runningRecomposers.value - priorRecomposers
        BoundedFrames.step(n)
        recomposers += Recomposer.runningRecomposers.value - priorRecomposers
    }

    fun advance(ms: Long) {
        var remaining = (ms + BoundedFrames.FRAME_MS - 1) / BoundedFrames.FRAME_MS
        while (remaining > 0) {
            val n = remaining.coerceAtMost(BoundedFrames.MAX_FRAMES.toLong()).toInt()
            frames(n)
            remaining -= n
        }
    }

    /** IO and coroutine default delays need wall-clock time as well as Robolectric frames. */
    fun await(reason: String, realWaitMs: Long = 500, ready: () -> Boolean) {
        require(realWaitMs in 1..5_000) { "wall-clock budget must be bounded" }
        val sleepMs = (realWaitMs + 99) / 100
        repeat(100) {
            frames(2)
            if (ready()) return
            Thread.sleep(sleepMs)
        }
        throw AssertionError("Not observed after 200 bounded frames: $reason; " +
            "open=${home.openKey}, step=${home.arrivedByStep}, now=${ports.now()}, " +
            "attaches=${ports.opens.size}, IO=${ports.io.inFlight}, " +
            "route recomposers=${recomposers.size}, shut down=${recomposers.count(::shutDown)}")
    }
}

class FakeAgentPorts : AgentPorts {
    val io = AgentIoTracker()
    fun stopIo() = io.stop()
    data class Open(
        val key: AgentKey,
        val cols: UShort,
        val rows: UShort,
        val lead: Boolean,
        val listener: TerminalListener,
        val view: FakeTerminalView = FakeTerminalView(),
    ) {
        /** Rows back from live; the wire offset is the first row in the whole history. */
        var offset = 0
        var phase = if (lead) TermPhase.Typing else TermPhase.Watching

        fun paint(text: String = "terminal output") {
            listener.onState(TermState(phase, cols, rows))
            listener.onFrame(frameOf(
                cols.toInt(), rows.toInt(), mapOf(0 to cellsOf(text)),
                live = offset == 0, total = rows.toInt() + 120, offset = 120 - offset,
            ))
        }
    }

    val opens = mutableListOf<Open>()
    val chats = mutableMapOf<AgentKey, FakeAgentChat>()
    val changes = mutableListOf<AgentKey>()
    var clock: () -> Long = SystemClock::uptimeMillis
    override fun now(): Long = clock()
    fun chat(key: AgentKey) = chats.getOrPut(key) { FakeAgentChat(io) }

    override fun openTerminal(key: AgentKey, cols: UShort, rows: UShort, lead: Boolean, listener: TerminalListener): TerminalViewInterface {
        val opened = Open(key, cols, rows, lead, listener)
        opens += opened
        opened.paint()
        return object : TerminalViewInterface by opened.view, AutoCloseable {
            override fun scroll(rows: Int) {
                opened.view.scroll(rows)
                opened.offset = (opened.offset - rows).coerceIn(0, 120)
                opened.paint("scrollback ${opened.offset}")
            }
            override fun lead() {
                opened.view.lead()
                opened.phase = TermPhase.Typing
                opened.paint()
            }
            override fun watch() {
                opened.view.watch()
                opened.phase = TermPhase.Watching
                opened.paint()
            }
            override fun live() {
                opened.view.live()
                opened.offset = 0
                opened.paint()
            }
            override fun close() = opened.view.close()
        }
    }

    override fun openChat(key: AgentKey): ChatInterface = chat(key)

    override fun openChanges(key: AgentKey): ChangesInterface {
        changes += key
        return object : ChangesInterface {
            private val signal = io.signal()
            private val view = ChangesView(ChangesList.NotARepo, null, null)
            override fun read() = io.call { view }
            override fun open(path: String?) = io.call { view }
            override fun waitChange(timeoutMs: UInt): Boolean = io.waitForEvent(signal)
            override fun leave() = io.call { signal.release() }
        }
    }
}

class FakeAgentChat(private val io: AgentIoTracker) : ChatInterface {
    @Volatile var current = ChatPoll(
        found = ChatFound.FOUND, answered = true, agent = "claude", title = "", version = "", status = "idle",
        health = ChatHealth.Ok, problem = "", exited = false, canSend = true, cut = false,
        rows = emptyList(), gaugeUsed = null, gaugeWindow = null, gaugePercent = null,
        queued = emptyList(), timings = "",
    )
    private val signal = io.signal()
    override fun held(): ChatPoll = current
    override fun poll(event: Boolean): ChatPoll = io.call { current }
    override fun watch(event: Boolean): ChatPoll = io.call { current }
    override fun older(): ChatPoll = io.call { current }
    override fun waitChange(timeoutMs: UInt): Boolean = io.waitForEvent(signal)
    override fun leave() = io.call { signal.release() }
    override fun image(key: String): ByteArray? = null
    override fun enterAgain() = false
    override fun send(text: String): ChatSent = ChatSent.Typed
    override fun sendWithImages(text: String, images: List<ChatUpload>): ChatSent = ChatSent.Typed
    fun answer(row: ChatRow) {
        current = current.copy(rows = current.rows + row)
        signal.release()
    }
}

/** Tracks the blocking fake calls so no IO completion can arrive after Robolectric resets its looper. */
class AgentIoTracker {
    private val active = AtomicInteger()
    private val signals = CopyOnWriteArrayList<Semaphore>()
    @Volatile private var stopped = false
    val inFlight: Int get() = active.get()

    fun signal(): Semaphore = Semaphore(0).also { signals += it }

    fun <T> call(block: () -> T): T {
        active.incrementAndGet()
        try {
            return block()
        } finally {
            active.decrementAndGet()
        }
    }

    fun waitForEvent(signal: Semaphore): Boolean = call {
        if (stopped) false else signal.tryAcquire(50, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        stopped = true
        signals.forEach { it.release() }
    }
}

/** Advance an exact interval, including a partial final frame for the 399/400 ms boundary. */
fun AgentRouteRig.advanceExactly(ms: Long) {
    require(ms >= 0)
    val full = ms / BoundedFrames.FRAME_MS
    repeat((full / BoundedFrames.MAX_FRAMES).toInt()) { frames(BoundedFrames.MAX_FRAMES) }
    frames((full % BoundedFrames.MAX_FRAMES).toInt())
    val tail = ms % BoundedFrames.FRAME_MS
    if (tail != 0L) {
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(tail))
    }
}

/** A listed file for real ChangesRoute hit tests; no navigation or screen state in the fake. */
fun FakeAgentPorts.withListedChanges(): AgentPorts {
    val delegate = this
    return object : AgentPorts by delegate {
        override fun openChanges(key: AgentKey): ChangesInterface {
            changes += key
            val signal = io.signal()
            val view = ChangesView(ChangesList.Listed(listOf(
                uniffi.kudde_mobile.ChangesEntry("docs/guide.md", "changed", 2uL, 1uL, true),
            ), false), null, null)
            return object : ChangesInterface {
                override fun read() = io.call { view }
                override fun open(path: String?) = io.call { view }
                override fun waitChange(timeoutMs: UInt) = io.waitForEvent(signal)
                override fun leave() = io.call { signal.release() }
            }
        }
    }
}

/**
 * Lets [ms] pass on both clocks: Robolectric's (frames) and the wall clock, because the
 * routes' delay() calls (the 400 ms dwell, the size settle) run on a real-time timer in
 * Compose, not on Robolectric's looper. Bounded: at most 2 s of real time.
 */
fun AgentRouteRig.advanceBoth(ms: Long) {
    require(ms in 0..2_000) { "wall-clock budget must be bounded" }
    val start = System.nanoTime()
    advance(ms)
    while ((System.nanoTime() - start) / 1_000_000 < ms + 50) {
        Thread.sleep(10)
        frames(1)
    }
    frames(2)
}
