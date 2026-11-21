package dev.kudde.android.ui.agent

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.kudde.android.testing.*
import dev.kudde.android.ui.chat.Attachment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.kudde_mobile.ChatRow

@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class AgentRouteChatPlaceTest {
    private fun row(i: Int) = ChatRow("row-$i", "said", "Answer $i\nA second line to read.", "", emptyList(), emptyList())
    private val rows = (0..40).map(::row)

    private fun AgentRouteScenario.reading(pending: Boolean = false): Attachment {
        rig.ports.chat(rig.key).current = rig.ports.chat(rig.key).current.copy(rows = rows)
        val photo = Attachment("photo.png", byteArrayOf(1), ImageBitmap(1, 1))
        state.draft = "unfinished draft"
        state.attached = listOf(photo)
        if (pending) state.sent("still sending", 0, rows, "idle", android.os.SystemClock.uptimeMillis())
        // A previously saved place seeds the real list; its next keep is driven by the real route.
        state.stick = false
        state.keep(rows, 4, 17)
        show(chat = true)
        rig.await("real chat list laid out") { draw(); list() != null }
        val scroll = list()!!
        assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 37f))
        rig.frames(12)
        draw()
        rig.frames(2)
        assertFalse("fixture is reading history rather than following bottom", state.stick)
        return photo
    }

    private fun list() = semanticsNodes().firstOrNull { it.config.getOrNull(SemanticsActions.ScrollToIndex) != null }
    private fun place() = semanticsNodes().first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun AgentRouteScenario.draftAndPhotos(photo: Attachment) {
        assertEquals("unfinished draft", fieldText())
        assertSame(photo, state.attached.single())
        assertEquals(1, rig.ports.opens.size)
        assertTrue(opened.view.calls.none { it == "detach" })
    }

    @Test fun placeSurvivesChangesForUnchangedRows() = AgentRouteScenario().use {
        val photo = it.reading()
        val before = place()
        it.changes()
        it.back()
        assertEquals("same chat item and offset after Changes", before, place(), .01f)
        it.draftAndPhotos(photo)
    }

    @Test fun placeSurvivesWithAPendingBubble() = AgentRouteScenario().use {
        val photo = it.reading(pending = true)
        val pending = it.state.pending.single().id
        val before = place()
        it.changes()
        it.rig.advance(400)
        it.back()
        assertEquals("pending bubble does not move the reading place", before, place(), .01f)
        assertEquals(pending, it.state.pending.single().id)
        it.draftAndPhotos(photo)
    }

    @Test fun placeWhenRowsAppendAndWhenHistoryChanges() = AgentRouteScenario().use {
        val photo = it.reading(pending = true)
        // Force the real Messages disposal/keep path, then restore through its real startAt call.
        it.tap("Terminal")
        val saved = it.state.startAt(rows)!!
        assertTrue("fixture reads from a nonzero pixel offset", saved.second > 0)
        val anchor = rows[saved.first].key
        it.tap("Chat")
        var current = rows
        for (changed in listOf(rows + row(41), listOf(row(-2), row(-1)) + rows + row(41))) {
            it.changes()
            it.rig.ports.chat(it.rig.key).current = it.rig.ports.chat(it.rig.key).current.copy(rows = changed)
            it.rig.await("the real poll delivers changed history under Changes") { it.state.poll?.rows == changed }
            it.back()
            it.tap("Terminal")
            val kept = it.state.startAt(changed)!!
            // Today's startAt resolves the saved row key, keeps its pixel offset, and shifts
            // its index for history inserted above. Appending below leaves that index alone.
            assertEquals("startAt preserves the row being read", anchor, changed[kept.first].key)
            assertEquals("startAt preserves its pixel offset", saved.second, kept.second)
            assertEquals("startAt follows prepended history by key", changed.indexOfFirst { r -> r.key == anchor }, kept.first)
            it.tap("Chat")
            it.draftAndPhotos(photo)
            current = changed
        }
        // If that saved row disappears, today's startAt returns null and follows the bottom.
        it.tap("Terminal")
        assertNull("a missing anchor falls back to following bottom today", it.state.startAt(current.filterNot { r -> r.key == anchor }))
        assertTrue(it.state.stick)
    }
}
