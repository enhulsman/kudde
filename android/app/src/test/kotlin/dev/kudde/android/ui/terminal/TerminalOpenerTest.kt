package dev.kudde.android.ui.terminal

import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.kudde_mobile.RowKeyTap

// The terminal opens once the screen knows the phone's grid (the core types at
// that size); later sizes go out once the keyboard has settled.
class TerminalOpenerTest {
    private val session = TerminalSession(AgentKey("workstation", "t1")) { it.run() }
    private val view = FakeTerminalView()
    private val opened = mutableListOf<GridSize>()
    private var hubRunning = true
    private val opener = TerminalOpener(session) { size ->
        if (hubRunning) view.also { opened += size } else null
    }

    @Test
    fun opensAtTheFirstPhoneGridThenSendsSettledSizes() {
        opener.onPhoneGrid(GridSize(53, 45), 0)
        opener.tick(300)
        assertEquals(listOf(GridSize(53, 45)), opened)
        assertEquals(emptyList<String>(), view.calls)
        opener.onPhoneGrid(GridSize(53, 38), 1000)
        opener.onPhoneGrid(GridSize(53, 31), 1016)
        opener.tick(1100)
        opener.tick(1300)
        assertEquals(listOf("size 53x31"), view.calls)
        assertEquals(1, opened.size)
    }

    @Test
    fun withoutAHubItTriesAgainOnTheNextLayout() {
        hubRunning = false
        opener.onPhoneGrid(GridSize(53, 45), 0)
        assertEquals(emptyList<GridSize>(), opened)
        hubRunning = true
        opener.onPhoneGrid(GridSize(53, 45), 10)
        assertEquals(listOf(GridSize(53, 45)), opened)
    }

    // spaces spec, "Passing through leaves the desk's sizes alone" and "Input
    // during the wait is never lost" (design D5). Each terminal shown gets its
    // own session and opener, as the route keys them; leaving disposes the
    // opener and closes the session. Times are the test's clock, never real time.
    private var clock = 0L
    private val log = mutableListOf<String>()
    private var running = true

    private inner class Shown(val name: String, dwell: Long) {
        val view = FakeTerminalView()
        val session = TerminalSession(AgentKey("workstation", name)) { it.run() }
        val opener: TerminalOpener = TerminalOpener(session, arrivalMs = clock, dwellMs = dwell) {
            if (running) view.also { log += "$name@$clock" } else null
        }

        init {
            session.onInputWithoutView = { opener.openNow(clock) }
            // The screen lays out at once and reports the phone's grid.
            opener.onPhoneGrid(GridSize(53, 45), clock)
        }

        fun tick(ms: Long) {
            clock = ms
            opener.tick(ms)
        }

        fun leave(ms: Long) {
            clock = ms
            opener.dispose()
            session.close()
        }
    }

    private fun stepTo(name: String, ms: Long): Shown {
        clock = ms
        return Shown(name, TerminalOpener.STEP_DWELL_MS)
    }

    @Test
    fun threeQuickStepsOpenOnlyTheLast() {
        val b = stepTo("B", 0)
        b.tick(299)
        b.leave(300)
        val c = stepTo("C", 300)
        c.tick(599)
        c.leave(600)
        val d = stepTo("D", 600)
        d.tick(999)
        assertEquals(emptyList<String>(), log)
        d.tick(1000)
        assertEquals(listOf("D@1000"), log)
        assertEquals("B and C never attached", emptyList<String>(), b.view.calls + c.view.calls)
    }

    @Test
    fun threeSlowStepsOpenEachAfterItsWait() {
        val b = stepTo("B", 0)
        b.tick(400)
        b.leave(450)
        val c = stepTo("C", 450)
        c.tick(849)
        c.tick(850)
        c.leave(900)
        val d = stepTo("D", 900)
        d.tick(1300)
        assertEquals(listOf("B@400", "C@850", "D@1300"), log)
        assertEquals("detached when left", listOf("detach", "destroy"), b.view.calls)
        assertEquals(listOf("detach", "destroy"), c.view.calls)
    }

    @Test
    fun twoQuickStepsUpOpenOnlyTheLast() {
        val q = stepTo("Q", 0)
        q.tick(199)
        q.leave(200)
        val r = stepTo("R", 200)
        r.tick(599)
        r.tick(600)
        assertEquals(listOf("R@600"), log)
        assertEquals(emptyList<String>(), q.view.calls)
    }

    @Test
    fun leftJustBeforeTheWaitEndsIsNeverOpened() {
        val b = stepTo("B", 0)
        b.tick(399)
        b.leave(399)
        b.tick(400)
        assertEquals(emptyList<String>(), log)
        assertEquals(emptyList<String>(), b.view.calls)
    }

    @Test
    fun stillThereWhenTheWaitEndsIsOpenedThen() {
        val b = stepTo("B", 0)
        b.tick(400)
        assertEquals(listOf("B@400"), log)
        b.leave(401)
        assertEquals("a terminal that was attached is detached", listOf("detach", "destroy"), b.view.calls)
    }

    @Test
    fun aGridReportedAfterTheWaitOpensAtOnce() {
        clock = 0
        val b = Shown("B", TerminalOpener.STEP_DWELL_MS)
        clock = 500
        b.opener.onPhoneGrid(GridSize(53, 40), 500)
        assertEquals(listOf("B@500"), log)
    }

    @Test
    fun aTapOpensAtOnce() {
        clock = 0
        Shown("B", dwell = 0)
        assertEquals(listOf("B@0"), log)
    }

    @Test
    fun typingDuringTheWaitOpensThenAndIsDeliveredOnce() {
        val b = stepTo("B", 0)
        clock = 150
        b.session.type("y")
        assertEquals(listOf("B@150"), log)
        b.tick(400)
        assertEquals("opened once", listOf("B@150"), log)
        assertEquals(listOf("type y"), b.view.calls)
    }

    @Test
    fun twoKeysInARowArriveInOrderOnceEach() {
        val b = stepTo("B", 0)
        clock = 100
        b.session.tap(RowKeyTap.Esc)
        clock = 120
        b.session.tap(RowKeyTap.Down)
        assertEquals(listOf("B@100"), log)
        assertEquals(listOf("tap Esc", "tap Down"), b.view.calls)
    }

    @Test
    fun aTapOnTheTerminalDuringTheWaitOpensAndSendsNoClick() {
        val b = stepTo("B", 0)
        clock = 200
        // Not yet typing: the screen takes a tap as lead(), never as a click.
        b.session.lead()
        assertEquals(listOf("B@200"), log)
        assertEquals(listOf("lead"), b.view.calls)
    }

    @Test
    fun theComposeTextSentDuringTheWaitIsPastedOnceAndTheSheetCloses() {
        val b = stepTo("B", 0)
        val screen = TerminalScreenState().apply { composeOpen = true; composeText = "run the tests" }
        clock = 250
        assertEquals(Paste.SENT, screen.sendCompose(b.session::paste))
        assertEquals(listOf("B@250"), log)
        assertEquals(listOf("paste run the tests"), b.view.calls)
        assertFalse(screen.composeOpen)
        assertEquals("", screen.composeText)
    }

    @Test
    fun withNothingToOpenTheSheetAndTextStayWithTheNewMessage() {
        running = false
        val b = stepTo("B", 0)
        val screen = TerminalScreenState().apply { composeOpen = true; composeText = "run the tests" }
        clock = 250
        val result = screen.sendCompose(b.session::paste)
        assertEquals(Paste.NOT_ATTACHED, result)
        assertTrue(screen.composeOpen)
        assertEquals("run the tests", screen.composeText)
        assertEquals(R.string.compose_not_attached, composeNotSent(result))
        assertNotEquals(R.string.compose_lines_refused, composeNotSent(result))
        b.session.type("y")
        assertEquals("keys do nothing", emptyList<String>(), b.view.calls)
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun inputThenLeavingDeliversOnceAndNothingAfter() {
        val b = stepTo("B", 0)
        clock = 150
        b.session.type("y")
        b.leave(300)
        clock = 310
        b.session.type("z")
        b.tick(400)
        assertEquals(listOf("B@150"), log)
        assertEquals(listOf("type y", "detach", "destroy"), b.view.calls)
    }

    @Test
    fun leavingWithoutInputNeverOpens() {
        val b = stepTo("B", 0)
        b.leave(200)
        b.tick(400)
        assertEquals(emptyList<String>(), log)
        assertEquals(emptyList<String>(), b.view.calls)
    }

    @Test
    fun aDisposedOpenerNeverOpens() {
        val b = stepTo("B", 0)
        b.opener.dispose()
        clock = 500
        b.opener.onPhoneGrid(GridSize(53, 40), 500)
        b.opener.tick(500)
        b.opener.openNow(500)
        assertEquals(emptyList<String>(), log)
    }
}
