package dev.kudde.android.ui.spaces

import androidx.compose.runtime.mutableLongStateOf
import com.github.takahirom.roborazzi.captureRoboImage
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.ui.agents.agentsUi
import dev.kudde.android.ui.spaces.SpacesFixtures.NOW
import dev.kudde.android.ui.spaces.SpacesFixtures.overviewRender
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// The tab overview against the render tab-overview
// (design/checkpoints/tab-overview.png): the space's tabs as chips, "main" as
// tiles (one beside three, a mod pane's tile reading "Desk only"), and
// "build", six panes side by side, as rows under the tab's map. Graphite, as
// the render; paper from the tokens. Reference phone 412 x 915 dp at density 2.
// Goldens: src/test/snapshots/tab-overview-<case>-<theme>.png.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class TabOverviewScreenshotTest {
    private val res = RuntimeEnvironment.getApplication().resources

    @Test fun tilesGraphite() = capture("tiles", null, dark = true)

    @Test fun tilesPaper() = capture("tiles", null, dark = false)

    @Test fun rowsGraphite() = capture("rows", "tb-build", dark = true)

    @Test fun rowsPaper() = capture("rows", "tb-build", dark = false)

    // Plain-text screens as the desk gives them (design D11); zsh's tile (t3) gets none, so the
    // golden shows a fallback tile beside pictured ones.
    private val pictures = mapOf(
        "t1" to "\$ npm run build\n> website@1.4.0 build\n> astro build\n\n 12:01:44 [build] 18 pages\n 12:01:46 [build] Complete!\n\nAllow Bash: npm run deploy?\n  1. Yes   2. No",
        "t2" to "> add og tags to every page\n\n- layout.astro\n- blog/[slug].astro\n\nDone with 2 of 5 files",
    )

    private fun capture(case: String, tab: String?, dark: Boolean) {
        val ui = tabOverviewUi(overviewRender, agentsUi(overviewRender, NOW, res, SpacesFixtures::utc), "workstation", "s-website", tab, spacesTexts(res), pictures)!!
        captureRoboImage("src/test/snapshots/tab-overview-$case-${if (dark) "graphite" else "paper"}.png") {
            KuddeTheme(dark = dark) {
                TabOverviewScreen(ui, mutableLongStateOf(NOW), spin = false, onBack = {}, onTab = {}, onOpen = {})
            }
        }
    }
}
