package dev.kudde.android.ui.terminal

import dev.kudde.android.ui.agents.AgentKey
import java.util.concurrent.Executor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.kudde_mobile.Armed
import uniffi.kudde_mobile.RowKeyTap
import uniffi.kudde_mobile.TermPhase
import uniffi.kudde_mobile.TermState
import uniffi.kudde_mobile.TerminalViewInterface

// One open terminal on the phone: frames and states from the Rust core reach
// the grid on the main thread; input goes to the terminal; the instant-open
// cache shows the last screen only until the live first paint; leaving
// detaches (terminal-input spec, "Leaving the terminal stops leading").
class TerminalSessionTest {
    private val queued = ArrayDeque<Runnable>()
    private val main = Executor { queued += it }
    private fun runMain() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    private val view = FakeTerminalView()
    private val session = TerminalSession(AgentKey("workstation", "t1"), main).also { it.attach(view) }

    @Test
    fun framesApplyOnTheMainThreadInOrder() {
        session.listener.onFrame(frameOf(6, 2, mapOf(0 to cellsOf("one"))))
        session.listener.onFrame(frameOf(6, 2, mapOf(1 to cellsOf("two")), full = false))
        assertTrue(session.grid.isEmpty)
        runMain()
        assertEquals("one\ntwo", session.grid.text())
        assertTrue(session.painted)
        assertEquals(2, session.version)
    }

    @Test
    fun theTerminalTakesThePhonesSizeOnlyWhileItShows() {
        // The chat over the terminal leaves the desk its size (Ezra, 2026-11-16).
        assertTrue(atPhoneSizeOnOpen(chat = false))
        assertFalse(atPhoneSizeOnOpen(chat = true))
        val view = FakeTerminalView()
        session.attach(view)
        session.follow(chat = true)
        session.follow(chat = false)
        assertEquals(listOf("watch", "lead"), view.calls)
    }

    @Test
    fun theCachedScreenShowsUntilTheLiveOneArrives() {
        val cached = frameOf(6, 2, mapOf(0 to cellsOf("cached")))
        assertTrue(session.showCached(cached))
        assertEquals("cached", session.grid.text())
        assertFalse(session.painted)
        // The live first paint replaces it in one step: no blank frame between.
        session.listener.onFrame(frameOf(6, 2, mapOf(0 to cellsOf("live"))))
        runMain()
        assertEquals("live", session.grid.text())
        // A cache read that comes back late is ignored.
        assertFalse(session.showCached(cached))
        assertEquals("live", session.grid.text())
    }

    @Test
    fun aMalformedFrameIsDroppedAndTheScreenKept() {
        session.listener.onFrame(frameOf(6, 2, mapOf(0 to cellsOf("ok"))))
        session.listener.onFrame(byteArrayOf(1, 2, 3))
        runMain()
        assertEquals("ok", session.grid.text())
        assertEquals(1, session.version)
    }

    @Test
    fun statesAndInterruptReportsArrive() {
        session.listener.onState(TermState(TermPhase.Watching, 96u, 44u))
        session.listener.onInterrupt("Sent ctrl+c")
        runMain()
        assertEquals(TermPhase.Watching, session.state?.phase)
        assertEquals("Sent ctrl+c", session.report)
        session.reportShown()
        assertNull(session.report)
    }

    @Test
    fun inputGoesToTheTerminalAndTheHeldModifiersFollow() {
        view.armedAfterTap = Armed(ctrl = true, alt = false)
        session.tap(RowKeyTap.Ctrl)
        assertEquals(Armed(ctrl = true, alt = false), session.armed)
        view.armedAfterTap = Armed(ctrl = false, alt = false)
        session.type("c")
        session.backspace()
        session.paste("a long prompt")
        session.scroll(-3)
        session.live()
        session.interrupt()
        session.phoneSize(GridSize(53, 31))
        assertEquals(Armed(ctrl = false, alt = false), session.armed)
        assertEquals(
            listOf("tap Ctrl", "type c", "backspace", "paste a long prompt", "scroll -3", "live", "interrupt", "size 53x31"),
            view.calls,
        )
    }

    // Issue #22: compose text with a line break is refused where the program
    // did not ask for bracketed paste; the sheet keeps the text to edit.
    @Test
    fun aRefusedPasteKeepsTheComposeSheetAndItsText() {
        val screen = TerminalScreenState().apply { composeOpen = true; composeText = "make clean\nrm -rf build" }
        view.pasteTaken = false
        assertEquals(Paste.REFUSED, screen.sendCompose(session::paste))
        assertTrue(screen.composeOpen)
        assertEquals("make clean\nrm -rf build", screen.composeText)
        view.pasteTaken = true
        screen.composeText = "make clean"
        assertEquals(Paste.SENT, screen.sendCompose(session::paste))
        assertFalse(screen.composeOpen)
        assertEquals("", screen.composeText)
        assertEquals(listOf("paste make clean\nrm -rf build", "paste make clean"), view.calls)
    }

    @Test
    fun anEmptyComposeClosesWithoutAPaste() {
        val screen = TerminalScreenState().apply { composeOpen = true }
        assertEquals(Paste.SENT, screen.sendCompose(session::paste))
        assertFalse(screen.composeOpen)
        assertTrue(view.calls.isEmpty())
    }

    @Test
    fun leavingDetachesAndHandsBackTheLastScreen() {
        val frame = frameOf(6, 2, mapOf(0 to cellsOf("last"), 1 to cellsOf("")))
        session.listener.onFrame(frame)
        runMain()
        assertArrayEquals(frame, session.close())
        assertEquals(listOf("detach", "destroy"), view.calls)
        // Nothing reaches the terminal or the grid afterwards.
        session.type("x")
        session.listener.onFrame(frameOf(6, 2, mapOf(0 to cellsOf("late"))))
        runMain()
        assertEquals("last", session.grid.text())
        assertEquals(listOf("detach", "destroy"), view.calls)
        assertNull(session.close())
    }

    @Test
    fun aScreenThatNeverWentLiveIsNotCachedAgain() {
        session.showCached(frameOf(6, 2, mapOf(0 to cellsOf("cached"))))
        assertNull(session.close())
    }

    @Test
    fun theShapeChangesOnlyWithTheSizeOrLiveness() {
        assertNull(session.shape)
        session.listener.onFrame(frameOf(6, 2, mapOf(0 to cellsOf("a"))))
        runMain()
        val first = session.shape
        assertEquals(GridShape(6, 2, live = true), first)
        session.listener.onFrame(frameOf(6, 2, mapOf(1 to cellsOf("b")), full = false))
        runMain()
        assertTrue(first === session.shape)
        session.listener.onFrame(frameOf(6, 2, mapOf(1 to cellsOf("b")), full = false, live = false))
        runMain()
        assertEquals(GridShape(6, 2, live = false), session.shape)
    }

    @Test
    fun rowDamageCollectsUntilDrawn() {
        session.listener.onFrame(frameOf(6, 3, mapOf(0 to cellsOf("a"))))
        runMain()
        assertTrue(session.damage.take().all)
        session.listener.onFrame(frameOf(6, 3, mapOf(1 to cellsOf("b")), full = false))
        session.listener.onFrame(frameOf(6, 3, mapOf(2 to cellsOf("c")), full = false))
        runMain()
        val damage = session.damage.take()
        assertFalse(damage.all)
        assertEquals(setOf(1, 2), damage.rows)
        assertTrue(session.damage.take().isEmpty)
    }

    // spaces spec, "Input during the wait is never lost" (design D5): input to
    // a session without a view first asks the route to open it (the opener's
    // openNow), then goes to the view that opening attached, once and in order.
    private val waiting = TerminalSession(AgentKey("workstation", "b"), main)
    private val opened = FakeTerminalView()
    private var openable = true
    private var asked = 0

    init {
        waiting.onInputWithoutView = {
            asked++
            if (openable) waiting.attach(opened)
        }
    }

    @Test
    fun typingWithoutAViewOpensFirstThenDeliversOnce() {
        waiting.type("y")
        assertEquals(1, asked)
        assertEquals(listOf("type y"), opened.calls)
    }

    @Test
    fun twoKeysInARowOpenOnceAndArriveInOrder() {
        waiting.tap(RowKeyTap.Esc)
        waiting.tap(RowKeyTap.Down)
        assertEquals(1, asked)
        assertEquals(listOf("tap Esc", "tap Down"), opened.calls)
    }

    @Test
    fun aTapOnTheTerminalOpensAndIsNoClick() {
        waiting.lead()
        assertEquals(1, asked)
        assertEquals(listOf("lead"), opened.calls)
    }

    @Test
    fun everyKindOfInputEndsTheWait() {
        val inputs: List<TerminalSession.() -> Unit> = listOf(
            { type("y") }, { backspace() }, { tap(RowKeyTap.Enter) }, { interrupt() }, { lead() },
            { click(1, 1) }, { scroll(-3) }, { live() }, { wantInput() }, { paste("x") },
        )
        for (input in inputs) {
            val session = TerminalSession(AgentKey("workstation", "b"), main)
            var calls = 0
            session.onInputWithoutView = { calls++ }
            session.input()
            assertEquals(1, calls)
        }
    }

    @Test
    fun followingTheChatAndThePhoneSizeAreNotInput() {
        waiting.follow(chat = true)
        waiting.follow(chat = false)
        waiting.phoneSize(GridSize(53, 31))
        assertEquals(0, asked)
        assertEquals(emptyList<String>(), opened.calls)
    }

    @Test
    fun theComposeTextIsPastedOnceAndTheSheetCloses() {
        val screen = TerminalScreenState().apply { composeOpen = true; composeText = "run the tests" }
        assertEquals(Paste.SENT, screen.sendCompose(waiting::paste))
        assertEquals(listOf("paste run the tests"), opened.calls)
        assertFalse(screen.composeOpen)
        assertEquals("", screen.composeText)
    }

    @Test
    fun whenNothingCanBeOpenedTheTextStaysAndIsNotReportedSent() {
        openable = false
        val screen = TerminalScreenState().apply { composeOpen = true; composeText = "run the tests" }
        val result = screen.sendCompose(waiting::paste)
        assertEquals(Paste.NOT_ATTACHED, result)
        assertTrue(screen.composeOpen)
        assertEquals("run the tests", screen.composeText)
        assertEquals(dev.kudde.android.R.string.compose_not_attached, composeNotSent(result))
        assertEquals(dev.kudde.android.R.string.compose_lines_refused, composeNotSent(Paste.REFUSED))
        assertNull(composeNotSent(Paste.SENT))
        waiting.type("y")
        waiting.lead()
        assertEquals("keys and taps do nothing", emptyList<String>(), opened.calls)
    }

    @Test
    fun inputThenLeavingDeliversOnceAndNothingAfter() {
        waiting.type("y")
        waiting.close()
        waiting.type("z")
        assertEquals(Paste.NOT_ATTACHED, waiting.paste("late"))
        assertEquals(1, asked)
        assertEquals(listOf("type y", "detach", "destroy"), opened.calls)
    }

    @Test
    fun leavingWithoutInputNeverOpens() {
        waiting.close()
        assertEquals(0, asked)
        assertEquals(emptyList<String>(), opened.calls)
    }
}

class FakeTerminalView : TerminalViewInterface, AutoCloseable {
    val calls = mutableListOf<String>()
    var armedAfterTap = Armed(ctrl = false, alt = false)

    override fun armed() = armedAfterTap
    override fun backspace(): Armed {
        calls += "backspace"
        return armedAfterTap
    }
    override fun detach() {
        calls += "detach"
    }
    override fun interrupt() {
        calls += "interrupt"
    }
    override fun live() {
        calls += "live"
    }
    /** What the core answers a paste: false refuses it (a line break without bracketed paste). */
    var pasteTaken = true
    override fun paste(text: String): Boolean {
        calls += "paste $text"
        return pasteTaken
    }
    override fun phoneSize(cols: UShort, rows: UShort) {
        calls += "size ${cols}x$rows"
    }
    override fun scroll(rows: Int) {
        calls += "scroll $rows"
    }
    override fun lead() {
        calls += "lead"
    }
    override fun watch() {
        calls += "watch"
    }
    override fun click(col: UShort, row: UShort) {
        calls += "click $col $row"
    }
    override fun tap(key: RowKeyTap): Armed {
        calls += "tap ${key::class.simpleName}"
        return armedAfterTap
    }
    override fun typeText(text: String): Armed {
        calls += "type $text"
        return armedAfterTap
    }
    override fun close() {
        calls += "destroy"
    }
}
