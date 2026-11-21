package dev.kudde.android.ui.agent

import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.chat.ChatState
import dev.kudde.android.ui.chat.ChatSwitch
import dev.kudde.android.ui.chat.ChatVisit
import dev.kudde.android.ui.terminal.FakeTerminalView
import dev.kudde.android.ui.terminal.GridSize
import dev.kudde.android.ui.terminal.TerminalMetrics
import dev.kudde.android.ui.terminal.cellsOf
import dev.kudde.android.ui.terminal.frameOf
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import uniffi.kudde_mobile.TerminalListener
import uniffi.kudde_mobile.TerminalViewInterface

class AgentKeeperTest {
    private val key = AgentKey("workstation", "t1")
    private val grid = GridSize(53, 31)
    private val estimate = GridSize(60, 40)

    private class Ports : AgentPorts {
        data class Open(val size: GridSize, val lead: Boolean, val view: FakeTerminalView)
        val opens = mutableListOf<Open>()
        var time = 0L
        var running = true
        override fun now() = time
        override fun openChat(key: AgentKey) = null
        override fun openChanges(key: AgentKey) = null
        override fun openTerminal(key: AgentKey, cols: UShort, rows: UShort, lead: Boolean, listener: TerminalListener): TerminalViewInterface? {
            if (!running) return null
            return FakeTerminalView().also { opens += Open(GridSize(cols.toInt(), rows.toInt()), lead, it) }
        }
    }

    private inner class Rig(chat: Boolean = false, step: Boolean = false, processGrid: GridSize? = null) {
        val ports = Ports()
        var lastPhoneGrid = processGrid
        val visit = ChatVisit(key, null, ChatState(), ChatSwitch(chat, null))
        val keeper = AgentKeeper(key, ports, Executor { it.run() }, visit,
            arrivedByStep = step, estimate = estimate,
            lastPhoneGrid = { lastPhoneGrid }, rememberPhoneGrid = { lastPhoneGrid = it })
        val view get() = ports.opens.last().view
        fun reopen() {
            keeper.reopen.onStop()
            assertTrue(keeper.reopen.onIdle { keeper.closeGeneration(keeper.generation(ports.time)) })
            assertTrue(keeper.reopen.onStart())
            keeper.generation(ports.time)
        }
    }

    @Test fun createdByAStepOpensAt400msWhateverTheCover() {
        for (cover in listOf(null, AgentScreen.Changes, AgentScreen.Overview("space", "tab"))) {
            val r = Rig(step = true)
            r.keeper.onTerminalGrid(grid, 0)
            cover?.let(r.keeper.stack::push)
            r.keeper.tick(399)
            assertTrue(r.ports.opens.isEmpty())
            r.keeper.tick(400)
            assertEquals(1, r.ports.opens.size)
            assertEquals(grid, r.ports.opens.single().size)
            assertEquals(cover == null, r.ports.opens.single().lead)
        }
        val chat = Rig(chat = true, step = true)
        chat.keeper.stack.push(AgentScreen.Changes)
        chat.keeper.tick(399)
        assertTrue(chat.ports.opens.isEmpty())
        chat.keeper.tick(400)
        assertFalse(chat.ports.opens.single().lead)
        val input = Rig(step = true)
        input.keeper.onTerminalGrid(grid, 0)
        input.ports.time = 100
        input.keeper.session.type("y")
        assertEquals(listOf("type y"), input.view.calls)
        assertEquals(1, input.ports.opens.size)
    }

    @Test fun pushAndPopNeverDetachNorSendASize() {
        val r = Rig()
        val generation = r.keeper.generation(0)
        r.keeper.onTerminalGrid(grid, 0)
        for (cover in listOf(AgentScreen.Changes, AgentScreen.Overview("space", "tab"))) {
            r.keeper.stack.push(cover)
            assertTrue(r.keeper.stack.pop())
        }
        assertSame(generation, r.keeper.generation(0))
        assertTrue(r.view.calls.isEmpty())
    }

    @Test fun aGridWhileTheTerminalIsNotOnTopIsImpossibleByApi() {
        val r = Rig(chat = true)
        assertThrows(IllegalStateException::class.java) { r.keeper.onTerminalGrid(grid, 0) }
        r.visit.switch.toTerminal()
        r.keeper.stack.push(AgentScreen.Changes)
        assertThrows(IllegalStateException::class.java) { r.keeper.onTerminalGrid(grid, 0) }
        assertNull(r.keeper.phone)
        assertFalse(r.keeper.measured)
        assertTrue(r.view.calls.isEmpty())
    }

    @Test fun chatBaseOpensWatchingWithTheSeed() {
        val r = Rig(chat = true, processGrid = grid)
        assertEquals(grid, r.ports.opens.single().size)
        assertFalse(r.ports.opens.single().lead)
        r.keeper.tick(800)
        assertFalse(r.keeper.measured)
        assertNull(r.keeper.phone)
        assertTrue(r.view.calls.isEmpty())
        val cold = Rig(chat = true)
        assertEquals(estimate, cold.ports.opens.single().size)
        r.visit.switch.toTerminal()
        r.keeper.onTerminalGrid(GridSize(50, 30), 900)
        r.lastPhoneGrid = GridSize(70, 50)
        r.visit.switch.toChat()
        r.reopen()
        assertEquals(GridSize(50, 30), r.ports.opens.last().size)
        assertFalse(r.ports.opens.last().lead)
        assertFalse(r.keeper.measured)
    }

    @Test fun theSeedIsOnlyUsedWhenTheBaseAtCreationIsTheChat() {
        val r = Rig(processGrid = grid)
        r.keeper.tick(800)
        assertTrue(r.ports.opens.isEmpty())
        r.keeper.onTerminalGrid(estimate, 801)
        assertEquals(estimate, r.ports.opens.single().size)
        assertTrue(r.ports.opens.single().lead)
        assertEquals(estimate, r.lastPhoneGrid)
        // A later base decision does not retroactively seed the first generation.
        val later = Rig(processGrid = grid)
        later.visit.switch.toChat()
        later.keeper.terminalShown(900)
        later.keeper.tick(1000)
        assertTrue(later.ports.opens.isEmpty())
        val unmeasured = Rig(processGrid = grid)
        unmeasured.reopen()
        unmeasured.keeper.tick(1000)
        assertTrue("a terminal-base reopen still needs its first real grid", unmeasured.ports.opens.isEmpty())
    }

    @Test fun leadOnlyFromOnTerminalGridOrTerminalShown() {
        val r = Rig(chat = true)
        r.visit.switch.toTerminal()
        r.keeper.terminalShown(10)
        r.keeper.tick(800)
        assertTrue(r.view.calls.isEmpty())
        r.keeper.onTerminalGrid(grid, 900)
        assertEquals(listOf("size 53x31", "lead"), r.view.calls)
        r.visit.switch.toChat()
        r.keeper.terminalShown(901)
        assertEquals("watch", r.view.calls.last())
        r.visit.switch.toTerminal()
        r.keeper.terminalShown(902)
        assertEquals(listOf("size 53x31", "lead"), r.view.calls.takeLast(2))
        assertEquals(1, r.ports.opens.size)
    }

    @Test fun aBaseChangeUnderACoverSendsNothing() {
        val r = Rig(chat = true)
        r.keeper.stack.push(AgentScreen.Changes)
        r.visit.switch.toTerminal()
        r.keeper.terminalShown(0)
        r.keeper.tick(400)
        assertTrue(r.view.calls.isEmpty())
        r.keeper.stack.pop()
        r.keeper.terminalShown(401)
        assertTrue(r.view.calls.isEmpty())
        r.keeper.onTerminalGrid(grid, 402)
        assertEquals(listOf("size 53x31", "lead"), r.view.calls)
    }

    @Test fun theFirstTerminalGridSendsPhoneSizeThenLead() {
        val r = Rig(chat = true)
        r.visit.switch.toTerminal()
        r.keeper.onTerminalGrid(grid, 100)
        r.keeper.onTerminalGrid(grid, 101)
        r.keeper.tick(1000)
        assertEquals(listOf("size 53x31", "lead"), r.view.calls)
        assertTrue(r.keeper.measured)
        assertEquals(grid, r.keeper.phone)
        assertEquals(grid, r.lastPhoneGrid)
    }

    @Test fun tickDoesNothingWhileTheTerminalIsNotOnTop() {
        val r = Rig()
        r.keeper.onTerminalGrid(grid, 0)
        val changed = GridSize(53, 40)
        r.keeper.onTerminalGrid(changed, 10)
        r.keeper.stack.push(AgentScreen.Changes)
        r.keeper.tick(1000)
        assertTrue(r.view.calls.isEmpty())
        r.keeper.stack.pop()
        r.keeper.tick(2000)
        assertTrue("pending offer was dropped, not delayed", r.view.calls.isEmpty())
        r.keeper.onTerminalGrid(changed, 2001)
        r.keeper.tick(2001 + TerminalMetrics.SETTLE_MS)
        r.keeper.tick(3000)
        assertEquals(listOf("size 53x40"), r.view.calls)
        r.keeper.onTerminalGrid(grid, 3001)
        r.visit.switch.toChat()
        r.keeper.terminalShown(3002)
        r.keeper.tick(4000)
        assertEquals(listOf("size 53x40", "watch"), r.view.calls)
    }

    @Test fun releaseDetachesOnceAndReturnsTheFrame() {
        val r = Rig()
        r.keeper.onTerminalGrid(grid, 0)
        val frame = frameOf(6, 2, mapOf(0 to cellsOf("last"), 1 to cellsOf("")))
        r.keeper.session.listener.onFrame(frame)
        r.keeper.stack.push(AgentScreen.Changes)
        assertArrayEquals(frame, r.keeper.release())
        assertNull(r.keeper.release())
        assertNull(r.keeper.session.onInputWithoutView)
        assertEquals(listOf("detach", "destroy"), r.view.calls)
        r.keeper.session.type("late")
        assertEquals(listOf("detach", "destroy"), r.view.calls)
    }

    @Test fun aReleasedKeeperNeverOpens() {
        val r = Rig(step = true)
        r.keeper.onTerminalGrid(grid, 0)
        val old = r.keeper.generation(0)
        r.keeper.release()
        r.keeper.tick(400)
        r.keeper.terminalShown(500)
        r.keeper.onTerminalGrid(grid, 500)
        old.opener.openNow(600)
        r.keeper.session.type("late")
        r.reopen()
        r.keeper.tick(1000)
        assertSame(old, r.keeper.generation(1000))
        assertTrue(r.ports.opens.isEmpty())
    }

    @Test fun theGenerationSwapReplacesSessionOpenerAndHookTogether() {
        val r = Rig(step = true)
        r.keeper.onTerminalGrid(grid, 0)
        r.keeper.tick(400)
        val old = r.keeper.generation(400)
        val screen = r.keeper.screen
        val chat = r.keeper.chat
        val stack = r.keeper.stack
        r.keeper.screen.composeText = "kept"
        r.keeper.stack.push(AgentScreen.Changes)
        val frame = frameOf(6, 2, mapOf(0 to cellsOf("last"), 1 to cellsOf("")))
        old.session.listener.onFrame(frame)
        r.ports.time = 900
        r.reopen()
        val next = r.keeper.generation(900)
        assertNotSame(old.session, next.session)
        assertNotSame(old.opener, next.opener)
        assertNotSame(old.onInputWithoutView, next.onInputWithoutView)
        assertNull(old.session.onInputWithoutView)
        assertSame(next.onInputWithoutView, next.session.onInputWithoutView)
        assertEquals(900L, next.opener.dueAtMs)
        assertSame(screen, r.keeper.screen)
        assertSame(chat, r.keeper.chat)
        assertSame(stack, r.keeper.stack)
        assertEquals("kept", r.keeper.screen.composeText)
        assertArrayEquals(frame, r.keeper.reopen.takeSeed())
        assertFalse(r.keeper.measured)
        assertEquals(2, r.ports.opens.size)
        assertFalse(r.ports.opens.last().lead)
        assertEquals(grid, r.ports.opens.last().size)
        assertEquals(1, r.ports.opens.first().view.calls.count { it == "detach" })
    }

    @Test fun theOldGenerationsOpenerNeverOpens() {
        val r = Rig(step = true)
        r.keeper.onTerminalGrid(grid, 0)
        val old = r.keeper.generation(0)
        val oldHook = old.onInputWithoutView
        r.reopen()
        old.opener.tick(400)
        old.opener.openNow(400)
        oldHook()
        assertEquals("only the replacement opened", 1, r.ports.opens.size)
        assertFalse(r.ports.opens.single().lead)
        assertTrue(r.view.calls.isEmpty())
        r.keeper.session.type("new")
        assertEquals(listOf("type new"), r.view.calls)
    }

    @Test fun backReadsTheCurrentStack() {
        val r = Rig()
        var leaves = 0
        r.keeper.stack.push(AgentScreen.Changes)
        r.keeper.back { leaves++ }
        assertNull(r.keeper.stack.cover)
        assertEquals(0, leaves)
        r.keeper.back { leaves++ }
        r.keeper.back { leaves++ }
        assertEquals(1, leaves)
        val chat = Rig(chat = true)
        chat.keeper.stack.push(AgentScreen.Overview("space", "tab"))
        chat.keeper.back { leaves++ }
        assertEquals(AgentScreen.Chat, chat.keeper.top)
        chat.keeper.back { leaves++ }
        assertEquals(2, leaves)
    }
}
