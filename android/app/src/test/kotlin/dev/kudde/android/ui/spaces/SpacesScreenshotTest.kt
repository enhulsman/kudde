package dev.kudde.android.ui.spaces

import androidx.compose.runtime.mutableLongStateOf
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.ui.agents.AgentsActions
import dev.kudde.android.ui.agents.agentsUi
import dev.kudde.android.ui.spaces.SpacesFixtures.NOW
import dev.kudde.android.ui.spaces.SpacesFixtures.spacesRender
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The Spaces view against the renders spaces-dark and spaces-light
// (design/checkpoints/spaces-dark.png, spaces-light.png): workstation's space
// "website" with its tab "main" (the needs-you card, a working agent and a
// shell; a mod pane in the map only), changelog under "Not in a space", and
// server out of reach with its last layout, its row "as of 12:05". At 130%
// and at the largest font size (2x), and with reduced motion (the spinner's
// still quarter arc, which every golden draws: a turning one never settles).
// Reference phone 412 x 915 dp at density 2, without system bars.
// Goldens: src/test/snapshots/spaces-<case>.png.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class SpacesScreenshotTest {
    private val res = RuntimeEnvironment.getApplication().resources

    @Test fun renderGraphite() = capture("spaces-graphite", dark = true)

    @Test fun renderPaper() = capture("spaces-paper", dark = false)

    @Test fun font130Graphite() = withFont(1.3f) { capture("spaces-font130-graphite", dark = true) }

    @Test fun largestFontGraphite() = withFont(2f) { capture("spaces-font2x-graphite", dark = true) }

    @Test fun reducedMotionGraphite() = capture("spaces-reduced-motion-graphite", dark = true, spin = false)

    @Test
    fun nothingToListGraphite() {
        val empty = SpacesFixtures.spacesRender.copy(
            machines = spacesRender.machines.take(1).map { it.copy(desk = null) },
            agents = emptyList(),
            others = emptyList(),
        )
        capture("spaces-empty-graphite", dark = true, snapshot = empty)
    }

    private fun withFont(scale: Float, block: () -> Unit) {
        RuntimeEnvironment.setFontScale(scale)
        try {
            block()
        } finally {
            RuntimeEnvironment.setFontScale(1f)
        }
    }

    private fun capture(name: String, dark: Boolean, spin: Boolean = false, snapshot: uniffi.kudde_mobile.HubSnapshot = spacesRender) {
        val agents = agentsUi(snapshot, NOW, res, SpacesFixtures::utc)
        val ui = spacesUi(snapshot, agents, spacesTexts(res))
        val needsYou = agents.needsYou.size
        captureRoboImage("src/test/snapshots/$name.png") {
            KuddeTheme(dark = dark) {
                SpacesScreen(
                    ui = ui,
                    clock = mutableLongStateOf(NOW),
                    spin = spin,
                    actions = AgentsActions.Preview,
                    onTab = { _, _, _ -> },
                    needsYou = needsYou,
                )
            }
        }
    }
}
