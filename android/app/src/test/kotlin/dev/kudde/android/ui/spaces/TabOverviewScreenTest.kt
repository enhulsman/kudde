package dev.kudde.android.ui.spaces

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableLongStateOf
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.testing.click
import dev.kudde.android.testing.clickable
import dev.kudde.android.testing.descriptions
import dev.kudde.android.testing.semanticsNodes
import dev.kudde.android.testing.texts
import dev.kudde.android.ui.agents.AgentKey
import dev.kudde.android.ui.agents.agentsUi
import dev.kudde.android.ui.spaces.SpacesFixtures.NOW
import dev.kudde.android.ui.spaces.SpacesFixtures.overviewRender
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// spaces spec, "A tab overview shows the splits as tiles": a tap on a tile
// opens its terminal, a placeholder tile does nothing, a tap on another tab
// shows its tiles without opening anything, and Back returns to the opener.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class TabOverviewScreenTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()
    private val opened = mutableListOf<AgentKey>()
    private val tabs = mutableListOf<String>()
    private var backs = 0

    @After
    fun close() {
        controller.pause().stop().destroy()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun show(tab: String? = null) {
        val res = activity.resources
        val ui = tabOverviewUi(overviewRender, agentsUi(overviewRender, NOW, res, SpacesFixtures::utc), "workstation", "s-website", tab, spacesTexts(res))!!
        activity.setContent {
            KuddeTheme(dark = true) {
                TabOverviewScreen(ui, mutableLongStateOf(NOW), spin = false, onBack = { backs++ }, onTab = { tabs += it }, onOpen = { opened += it })
            }
        }
        idle()
    }

    private fun node(text: String) = semanticsNodes().single { it.texts().contains(text) || text in it.descriptions() }

    @Test
    fun aTapOnATileOpensItsTerminal() {
        show()
        assertTrue(node("og tags").click())
        assertEquals(listOf(AgentKey("workstation", "t2")), opened)
    }

    @Test
    fun aPlaceholderTileReadsDeskOnlyAndDoesNothing() {
        show()
        val desk = node("Desk only")
        assertFalse("no click on a placeholder", desk.clickable())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun aTapOnAnotherTabShowsItWithoutOpeningAnything() {
        show()
        assertTrue(node("Tab 2, 1 terminal").click())
        assertEquals(listOf("tb-2"), tabs)
        assertTrue(opened.isEmpty())
    }

    @Test
    fun aRowOfTheFallbackOpensItsTerminal() {
        show("tb-build")
        assertTrue(node("htop").click())
        assertEquals(listOf(AgentKey("workstation", "b4")), opened)
    }

    @Test
    fun backReturnsToTheOpener() {
        show()
        activity.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(1, backs)
        assertTrue(opened.isEmpty())
    }
}
