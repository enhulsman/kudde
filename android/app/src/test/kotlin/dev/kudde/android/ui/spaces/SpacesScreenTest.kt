package dev.kudde.android.ui.spaces

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.ui.agents.AgentsActions
import dev.kudde.android.ui.agents.agentsUi
import dev.kudde.android.ui.agents.connected
import dev.kudde.android.ui.agents.row
import dev.kudde.android.ui.agents.snapshot
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.kudde_mobile.AgentAttention
import uniffi.kudde_mobile.DeskCellView
import uniffi.kudde_mobile.DeskPaneView
import uniffi.kudde_mobile.DeskSpaceView
import uniffi.kudde_mobile.DeskTabView
import uniffi.kudde_mobile.DeskView
import uniffi.kudde_mobile.HubSnapshot

// spaces spec, "The Spaces view stays put when the desk changes" ("A space is
// added above") and "A Spaces view of the home screen" ("Back from a
// terminal"): the row at the top stays where it is when a space is added above
// it, and the list's place outlives a terminal opened over it (its state lives
// above the open terminal, design D6, as AgentsRoute holds it).
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class SpacesScreenTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()

    @After
    fun close() {
        controller.pause().stop().destroy()
        idle()
    }

    // A frame passes too, so a scroll request or a closed window is laid out.
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20))

    private fun space(name: String) = DeskSpaceView(
        "s-$name", name, 0u,
        listOf(DeskTabView("tb-$name", "1", listOf(DeskCellView(DeskPaneView.Terminal("t-$name"), 0f, 0f, 1f, 1f)))),
    )

    /** Spaces named [names], one tab of one working agent each. */
    private fun desk(names: List<String>): HubSnapshot = snapshot(
        listOf(connected("workstation").copy(desk = DeskView(names.map(::space), emptyList(), null))),
        names.map { row("t-$it", AgentAttention.WORKING, name = "agent $it", folder = it) },
    )

    private fun ui(s: HubSnapshot) = spacesUi(s, agentsUi(s, 0L, activity.resources, { "" }), spacesTexts(activity.resources))

    private fun firstVisibleKey(state: LazyListState) = state.layoutInfo.visibleItemsInfo.first().key

    private val names = listOf("api", "docs", "infra", "website", "scripts", "mobile", "notes", "backups", "design", "blog", "shop", "status")
    private fun headerKey(model: SpacesUi, name: String) =
        model.items.single { it is SpacesItem.SpaceHeader && it.name == name }.key

    @Test
    fun aSpaceAddedAboveLeavesTheTopRowInPlace() {
        var model by mutableStateOf(ui(desk(names)))
        val state = LazyListState()
        activity.setContent {
            KuddeTheme(dark = true) {
                SpacesScreen(model, mutableLongStateOf(0L), false, AgentsActions.Preview, onTab = { _, _, _ -> }, state = state)
            }
        }
        idle()
        val third = headerKey(model, "infra")
        kotlinx.coroutines.runBlocking { state.scrollToItem(model.items.indexOfFirst { it.key == third }) }
        idle()
        assertEquals(third, firstVisibleKey(state))
        val offset = state.firstVisibleItemScrollOffset
        model = ui(desk(listOf("landing") + names))
        idle()
        assertEquals("the third space's header is still at the top", third, firstVisibleKey(state))
        assertEquals(offset, state.firstVisibleItemScrollOffset)
    }

    @Test
    fun backFromATerminalReturnsToTheSamePlace() {
        val model = ui(desk(names))
        var open by mutableStateOf(false)
        // As AgentsRoute: the list's state is held above the terminal that replaces the list.
        val state = LazyListState()
        activity.setContent {
            KuddeTheme(dark = true) {
                if (open) {
                    Box {}
                } else {
                    SpacesScreen(model, mutableLongStateOf(0L), false, AgentsActions.Preview, onTab = { _, _, _ -> }, state = state)
                }
            }
        }
        idle()
        val fourth = headerKey(model, "website")
        kotlinx.coroutines.runBlocking { state.scrollToItem(model.items.indexOfFirst { it.key == fourth }, scrollOffset = 7) }
        idle()
        assertEquals(fourth, firstVisibleKey(state))
        open = true
        idle()
        open = false
        idle()
        assertEquals("the fourth space where it was", fourth, firstVisibleKey(state))
        assertEquals(7, state.firstVisibleItemScrollOffset)
        assertTrue(state.layoutInfo.visibleItemsInfo.isNotEmpty())
    }
}
