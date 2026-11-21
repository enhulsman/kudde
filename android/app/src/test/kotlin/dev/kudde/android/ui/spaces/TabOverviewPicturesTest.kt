package dev.kudde.android.ui.spaces

import android.os.Looper
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.testing.descriptions
import dev.kudde.android.testing.semanticsNodes
import dev.kudde.android.testing.texts
import dev.kudde.android.ui.agent.AgentRouteScenario
import dev.kudde.android.ui.agents.AgentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// spaces spec, "A tab overview shows the splits as tiles": "Tiles show their
// screens", "A picture stays still", "No picture from the desk" (design D11).
// The real route, opened from a terminal, against fake ports; the reads are
// counted and awaited with the rig's frames and bounded real waits, never a
// delay of the test's own (Robolectric's delay() is wall-clock).
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class TabOverviewPicturesTest {
    private val screens = mapOf(
        "t1" to "$ cargo build\n   Compiling kudde",
        "t2" to "> og tags done",
        "t3" to "~/src/website $",
        "t5" to "readme written",
    )

    private fun AgentRouteScenario.reads() = rig.ports.screenReads.map { it.key.address }.sorted()

    private fun AgentRouteScenario.awaitReads(n: Int) =
        rig.await("$n screen reads, now ${rig.ports.screenReads.size}") { rig.ports.screenReads.size >= n }

    /** The picture drawn on the tile of the terminal named [name], or null. */
    private fun pictureOf(name: String): String? = semanticsNodes()
        .firstOrNull { name in it.texts() && it.config.contains(TilePictureKey) }
        ?.config?.get(TilePictureKey)

    private fun AgentRouteScenario.openOverview() {
        rig.ports.screens = { screens[it.address] }
        show()
        overview()
        awaitReads(3)
        rig.frames(4)
        draw()
    }

    @Test
    fun opening_the_overview_reads_every_terminal_of_the_shown_tab_once_off_the_main_thread() = AgentRouteScenario().use {
        it.openOverview()
        it.rig.advance(600)
        assertEquals(listOf("t1", "t2", "t3"), it.reads())
        val main = Looper.getMainLooper().thread
        assertTrue("reads on the main thread", it.rig.ports.screenReads.none { r -> r.thread === main })
        assertEquals("$ cargo build\n   Compiling kudde", pictureOf("Pricing page copy"))
        assertEquals("> og tags done", pictureOf("og tags"))
        assertEquals("~/src/website $", pictureOf("zsh"))
        assertEquals("nothing was opened to read them", 1, it.rig.ports.opens.size)
    }

    @Test
    fun the_picture_is_decoration_and_the_spoken_tile_is_unchanged() = AgentRouteScenario().use {
        it.openOverview()
        val spoken = semanticsNodes().flatMap { n -> n.texts() + n.descriptions() }
        assertFalse("picture text spoken: $spoken", spoken.any { s -> "Compiling" in s || "og tags done" in s })
        assertNotNull(semanticsNodes().firstOrNull { n -> "Pricing page copy" in n.texts() })
    }

    @Test
    fun a_change_on_the_desk_while_it_is_open_does_not_reach_the_pictures() = AgentRouteScenario().use {
        it.openOverview()
        it.rig.ports.screens = { _ -> "printed later" }
        // The desk moves on: a new snapshot arrives, time passes.
        it.rig.publish(it.rig.snapshot.copy())
        it.rig.advance(1_200)
        it.rig.frames(6)
        assertEquals("no re-read while it stays open", listOf("t1", "t2", "t3"), it.reads())
        assertEquals("$ cargo build\n   Compiling kudde", pictureOf("Pricing page copy"))
    }

    @Test
    fun showing_another_tab_reads_that_tabs_terminals() = AgentRouteScenario().use {
        it.openOverview()
        it.tap("Tab 2, 1 terminal")
        it.awaitReads(4)
        it.rig.frames(4)
        assertEquals(listOf("t1", "t2", "t3", "t5"), it.reads())
        assertEquals("readme written", pictureOf("readme"))
    }

    @Test
    fun a_terminal_without_a_picture_shows_as_before_beside_pictured_tiles() = AgentRouteScenario().use {
        it.rig.ports.screens = { k: AgentKey -> if (k.address == "t2") null else if (k.address == "t3") "" else screens[k.address] }
        it.show()
        it.overview()
        it.awaitReads(3)
        it.rig.frames(4)
        it.draw()
        assertEquals("$ cargo build\n   Compiling kudde", pictureOf("Pricing page copy"))
        assertNull("the desk gave nothing", pictureOf("og tags"))
        assertNull("an empty screen gives nothing", pictureOf("zsh"))
        assertNotNull("the tile is still there with its name", semanticsNodes().firstOrNull { n -> "og tags" in n.texts() })
        assertNotNull(semanticsNodes().firstOrNull { n -> "zsh" in n.texts() })
    }
}
