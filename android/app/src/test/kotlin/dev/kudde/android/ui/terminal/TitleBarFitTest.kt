package dev.kudde.android.ui.terminal

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.ImageBitmap
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.testing.descriptions
import dev.kudde.android.testing.semanticsNodes
import dev.kudde.android.testing.texts
import dev.kudde.android.ui.chat.ChatImageSource
import dev.kudde.android.ui.chat.ChatScreen
import dev.kudde.android.ui.chat.ChatState
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import uniffi.kudde_mobile.ChatFound
import uniffi.kudde_mobile.ChatHealth
import uniffi.kudde_mobile.ChatPoll

// spaces spec, "The title bar fits its controls" (design D10, "The fit";
// decision 10): where the bar runs out of width the gauge hides first and the
// map stays; the name gives way below eight of its characters only once the
// gauge is hidden, never below its first character and the ellipsis; the map,
// the switch and the menu button never hide. [titleBarFit] is the rule, given
// the width left beside the fixed controls; the last test runs it in the bar.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h915dp-xhdpi")
class TitleBarFitTest {
    // Widths in px: the room beside the fixed controls, the gauge with its gap,
    // the name's first eight characters with the ellipsis, its first one with the ellipsis.
    private val eight = 100
    private val floor = 20
    private val gauge = 40

    @Test
    fun everythingShowsWhereItFits() {
        assertEquals(TitleFit(gauge = true, nameWidth = 260), titleBarFit(room = 300, gauge = gauge, eightChars = eight, floor = floor))
    }

    @Test
    fun theNameMayEndInAnEllipsisBesideTheGaugeWhileEightCharactersFit() {
        assertEquals(TitleFit(gauge = true, nameWidth = 100), titleBarFit(room = 140, gauge = gauge, eightChars = eight, floor = floor))
    }

    @Test
    fun theGaugeHidesFirst() {
        // With the gauge the name would keep fewer than eight characters: the gauge goes, the name takes its room.
        assertEquals(TitleFit(gauge = false, nameWidth = 139), titleBarFit(room = 139, gauge = gauge, eightChars = eight, floor = floor))
    }

    @Test
    fun theNameShrinksBelowEightCharactersOnlyOnceTheGaugeIsHidden() {
        assertEquals(TitleFit(gauge = false, nameWidth = 70), titleBarFit(room = 70, gauge = gauge, eightChars = eight, floor = floor))
        assertEquals("without a gauge the same", TitleFit(gauge = false, nameWidth = 70), titleBarFit(room = 70, gauge = null, eightChars = eight, floor = floor))
    }

    @Test
    fun neverBelowTheFirstCharacterAndTheEllipsis() {
        assertEquals(TitleFit(gauge = false, nameWidth = floor), titleBarFit(room = 5, gauge = gauge, eightChars = eight, floor = floor))
    }

    @Test
    fun aShortNameNeedsOnlyItsOwnWidth() {
        // "api tests" is longer than eight characters; "zsh" is its own width.
        assertEquals(TitleFit(gauge = true, nameWidth = 50), titleBarFit(room = 90, gauge = gauge, eightChars = 30, floor = floor))
    }

    // --- In the bar: 360 dp at 2x, the chat with everything to show ---

    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()

    @After
    fun close() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        RuntimeEnvironment.setFontScale(1f)
    }

    @Test
    fun atTheLargestFontOnANarrowPhoneTheGaugeHidesAndTheRestShowsWhole() {
        RuntimeEnvironment.setFontScale(2f)
        val recreated = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val act = recreated.get()
            val poll = ChatPoll(
                found = ChatFound.FOUND, answered = true, agent = "claude", title = "", version = "", status = "idle",
                health = ChatHealth.Ok, problem = "", exited = false, canSend = true, cut = false, rows = emptyList(),
                gaugeUsed = 283_000u, gaugeWindow = null, gaugePercent = null, queued = emptyList(), timings = "",
            )
            val images = object : ChatImageSource {
                override fun cached(key: String): ImageBitmap? = null
                override suspend fun load(key: String): ImageBitmap? = null
            }
            act.setContent {
                KuddeTheme(dark = true) {
                    ChatScreen(TitleBarFixtures.pricingHeader, TerminalActions({}, {}, { false }, {}), poll, ChatState(), images, { 0L },
                        onTerminal = {}, onPick = {}, onSend = {}, animate = false, onChanges = {},
                        map = TitleBarFixtures.pricingMap, onMap = {})
                }
            }
            shadowOf(Looper.getMainLooper()).idle()
            val nodes = semanticsNodes()
            assertFalse("the gauge is hidden", nodes.any { it.texts() == listOf("283k") })
            val min = 48 * act.resources.displayMetrics.density
            val width = act.window.decorView.width
            val fixed = listOf("Back", "Tab overview, terminal 1 of 3", "Terminal", "More options").map { name ->
                nodes.single { name in it.descriptions() }.boundsInWindow
            }
            for (b in fixed) assertTrue("$b whole and 48 dp", b.width >= min && b.height >= min && b.left >= 0 && b.right <= width)
            for ((a, b) in fixed.zipWithNext()) assertTrue("$a and $b overlap", a.right <= b.left)
            val title = nodes.single { it.texts().contains(TitleBarFixtures.pricingHeader.name) }.boundsInWindow
            assertTrue("the name sits between Back and the map", title.left >= fixed[0].right && title.right <= fixed[1].left)
        } finally {
            recreated.pause().stop().destroy()
        }
    }
}
