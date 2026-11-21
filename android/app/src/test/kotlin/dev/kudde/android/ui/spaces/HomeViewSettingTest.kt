package dev.kudde.android.ui.spaces

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.testing.click
import dev.kudde.android.testing.semanticsNodes
import dev.kudde.android.testing.stateText
import dev.kudde.android.testing.texts
import dev.kudde.android.ui.agents.AgentsActions
import dev.kudde.android.ui.agents.AgentsScreen
import dev.kudde.android.ui.agents.HomeSwitch
import dev.kudde.android.ui.agents.agentsUi
import dev.kudde.android.ui.agents.connected
import dev.kudde.android.ui.agents.row
import dev.kudde.android.ui.agents.snapshot
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.kudde_mobile.AgentAttention

// spaces spec, "A Spaces view of the home screen": two views behind a switch in
// the top bar, the one used last opening the app ("The view is remembered"),
// as the chat view's choice is kept (ChatViewSettingTest); the switch says
// which view is chosen; Back on either view leaves the app.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class HomeViewSettingTest {
    private val app = RuntimeEnvironment.getApplication()
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()

    private fun prefs(): SharedPreferences = app.getSharedPreferences(HomeViewSetting.FILE, Context.MODE_PRIVATE)

    @After
    fun close() {
        controller.pause().stop().destroy()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // --- The remembered view ---

    @Test
    fun aFreshInstallOpensOnAgents() {
        assertFalse(HomeViewSetting(prefs()).spaces)
    }

    @Test
    fun theViewIsRememberedAcrossARestart() {
        HomeViewSetting.open(app).choose(spaces = true)
        assertTrue(HomeViewSetting.open(app).spaces)
        HomeViewSetting.open(app).choose(spaces = false)
        assertFalse(HomeViewSetting.open(app).spaces)
    }

    @Test
    fun aValueOfAnotherTypeReadsAsAgents() {
        prefs().edit().putString(HomeViewSetting.KEY, "spaces").commit()
        assertFalse(HomeViewSetting(prefs()).spaces)
    }

    // --- The switch ---

    private var chosen: Boolean? = null

    private fun showSwitch(spaces: Boolean, needsYou: Int = 0) {
        activity.setContent { KuddeTheme(dark = true) { HomeSwitch(spaces, needsYou) { chosen = it } } }
        idle()
    }

    private fun word(text: String) = semanticsNodes().single { it.texts().firstOrNull() == text }

    @Test
    fun theSwitchSaysWhichViewIsChosen() {
        showSwitch(spaces = true, needsYou = 1)
        assertEquals("Spaces, selected", spoken(word("Spaces")))
        assertNull(word("Agents").stateText())
        assertEquals("the count shows on Agents while Spaces shows", listOf("Agents", "1"), word("Agents").texts())
        showSwitch(spaces = false, needsYou = 1)
        assertEquals("Agents, selected", spoken(word("Agents")))
        assertEquals("no count on the chosen view", listOf("Agents"), word("Agents").texts())
        assertNull(word("Spaces").stateText())
    }

    /** What TalkBack reads: the text, then the state. */
    private fun spoken(node: androidx.compose.ui.semantics.SemanticsNode) =
        (node.texts() + listOfNotNull(node.stateText())).joinToString(", ")

    @Test
    fun aTapOnTheOtherWordChoosesIt() {
        showSwitch(spaces = false)
        assertTrue(word("Spaces").click())
        assertEquals(true, chosen)
        showSwitch(spaces = true)
        assertTrue(word("Agents").click())
        assertEquals(false, chosen)
    }

    @Test
    fun eachWordIsAtLeast48DpToTheTouch() {
        showSwitch(spaces = true, needsYou = 1)
        val min = 48 * activity.resources.displayMetrics.density
        for (text in listOf("Agents", "Spaces")) {
            val size = word(text).size
            assertTrue("$text is ${size.width} x ${size.height} px", size.width >= min && size.height >= min)
        }
    }

    // --- Back on either view ---

    private val hub = snapshot(listOf(connected("workstation")), listOf(row("t1", AgentAttention.WORKING)))

    @Test
    fun backOnEitherViewLeavesTheApp() {
        var spaces by mutableStateOf(false)
        val agents = agentsUi(hub, 0L, activity.resources, { "" })
        activity.setContent {
            KuddeTheme(dark = true) {
                if (spaces) {
                    SpacesScreen(spacesUi(hub, agents, spacesTexts(activity.resources)), mutableLongStateOf(0L), false, AgentsActions.Preview, onTab = { _, _, _ -> })
                } else {
                    AgentsScreen(agents, false, null, mutableLongStateOf(0L), false, AgentsActions.Preview)
                }
            }
        }
        idle()
        assertFalse("Agents keeps no Back for itself", activity.onBackPressedDispatcher.hasEnabledCallbacks())
        spaces = true
        idle()
        assertFalse("Spaces keeps no Back for itself", activity.onBackPressedDispatcher.hasEnabledCallbacks())
    }
}
