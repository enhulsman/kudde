package dev.kudde.android.ui.agent

import org.junit.Assert.*
import org.junit.Test

class AgentStackTest {
    @Test fun topForEachBaseWithNoCover() {
        val stack = AgentStack()
        assertEquals(AgentScreen.Terminal, stack.top(chat = false))
        assertEquals(AgentScreen.Chat, stack.top(chat = true))
        assertNull(stack.cover)
    }

    @Test fun pushChangesThenOverviewIsIgnoredWhileACoverShows() {
        val stack = AgentStack()
        val overview = AgentScreen.Overview("space", "tab")
        stack.push(AgentScreen.Changes)
        stack.push(overview)
        assertEquals(AgentScreen.Changes, stack.top(chat = true))
        assertEquals(AgentScreen.Changes, stack.top(chat = false))
        assertTrue(stack.pop())
        stack.push(overview)
        stack.push(AgentScreen.Changes)
        assertEquals(overview, stack.top(chat = false))
        assertTrue(stack.pop())
        assertEquals(AgentScreen.Terminal, stack.top(chat = false))
    }

    @Test fun theSameCoverPushedTwiceIsIgnored() {
        for (cover in listOf(AgentScreen.Changes, AgentScreen.Overview("space", "tab"))) {
            val stack = AgentStack()
            stack.push(cover)
            stack.push(cover)
            assertEquals(cover, stack.cover)
            assertTrue(stack.pop())
            assertFalse(stack.pop())
        }
    }

    @Test fun terminalOrChatAsACoverIsRejected() {
        for (base in listOf(AgentScreen.Terminal, AgentScreen.Chat)) {
            val stack = AgentStack()
            assertThrows(IllegalArgumentException::class.java) { stack.push(base) }
            assertNull(stack.cover)
            stack.push(AgentScreen.Changes)
            assertThrows(IllegalArgumentException::class.java) { stack.push(base) }
            assertEquals(AgentScreen.Changes, stack.cover)
        }
    }

    @Test fun popReturnsFalseWithNoCover() {
        val stack = AgentStack()
        assertFalse(stack.pop())
        assertEquals(AgentScreen.Chat, stack.top(chat = true))
    }
}
