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

    // Plain-text screens as the desk gives them (design D11), wide as the desk's: a build log of
    // 40 lines at 170 columns inside a box frame, and a Claude-Code-like screen with a rule line
    // and a prompt. zsh's tile (t3) gets none, so the golden shows a fallback tile beside them.
    private val buildLog: String = run {
        val inner = 168
        val frame = "\u2502 %-${inner}s \u2502"
        val body = (1..38).map { i ->
            frame.format(
                "12:01:%02d [build] page %d of 38 written to dist/blog/post-%d/index.html (%d kB, minified, gzip %d kB), no warnings from the image or link checks".format(i, i, i, 20 + i, 6 + i % 5),
            )
        }
        listOf("\u256d" + "\u2500".repeat(inner + 2) + "\u256e") + body + listOf("\u2570" + "\u2500".repeat(inner + 2) + "\u256f")
    }.joinToString("\n")

    private val claudeScreen = listOf(
        "> add og tags to every page",
        "",
        "I'll add the tags to the shared layout first, then check each page that overrides the head, so none is missed.",
        "",
        "- layout.astro: title, description, og:image from the page's front matter",
        "- blog/[slug].astro: per-post image, falls back to the site image",
        "",
        "Edited 2 of 5 files. Next: pages/about.astro, pages/index.astro and the 404 page, then a build to check the output.",
        "",
        "\u2500".repeat(150),
        "> ",
        "\u2500".repeat(150),
        "  ? for shortcuts",
    ).joinToString("\n")

    private val pictures = mapOf("t1" to buildLog, "t2" to claudeScreen)

    private fun capture(case: String, tab: String?, dark: Boolean) {
        val ui = tabOverviewUi(overviewRender, agentsUi(overviewRender, NOW, res, SpacesFixtures::utc), "workstation", "s-website", tab, spacesTexts(res), pictures)!!
        captureRoboImage("src/test/snapshots/tab-overview-$case-${if (dark) "graphite" else "paper"}.png") {
            KuddeTheme(dark = dark) {
                TabOverviewScreen(ui, mutableLongStateOf(NOW), spin = false, onBack = {}, onTab = {}, onOpen = {})
            }
        }
    }
}
