package dev.kudde.android.ui.components

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import dev.kudde.android.testing.BoundedFrames
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.testing.clickable
import dev.kudde.android.testing.semanticsNodes
import dev.kudde.android.testing.texts
import dev.kudde.android.testing.unmergedSemanticsNodes
import dev.kudde.android.ui.hostkey.HostKeyPrompt
import dev.kudde.android.ui.hostkey.TrustDialog
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Task 6.2 (font 2x): a dialog's two buttons sit side by side while they fit and stack, primary first and
// full width, when they do not; no label is ever cut off ("Trust and" for "Trust and connect").
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class DialogButtonsTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()

    @After fun close() {
        RuntimeEnvironment.setFontScale(1f)
        controller.pause().stop().destroy()
    }

    private fun showTrust(compare: String? = null) {
        activity.setContent {
            KuddeTheme(dark = true) {
                Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    TrustDialog(HostKeyPrompt("workstation", "workstation:22", "ED25519", "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"), null, {}, {}, compareCommand = compare)
                }
            }
        }
        BoundedFrames.settle(minFrames = 3)
    }

    private fun button(label: String): SemanticsNode =
        semanticsNodes().first { it.clickable() && it.texts().firstOrNull() == label }

    private fun labelCutOff(label: String): Boolean {
        val node = unmergedSemanticsNodes().first { it.texts() == listOf(label) }
        val results = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        val layout = results.first()
        // didOverflowWidth is true for a sub-pixel rounding; a real cut-off is a line wider than its box.
        return layout.getLineRight(layout.lineCount - 1) - layout.getLineLeft(layout.lineCount - 1) > layout.size.width + 2 ||
            (0 until layout.lineCount).any { layout.getLineRight(it) - layout.getLineLeft(it) > layout.size.width + 2 }
    }

    @Test fun atNormalSizeTheButtonsSitSideBySideWithCancelFirst() {
        showTrust()
        val cancel = button("Cancel").boundsInRoot
        val trust = button("Trust and connect").boundsInRoot
        assertEquals("same row", cancel.top, trust.top, 1f)
        assertTrue("Cancel on the left", cancel.right <= trust.left)
    }

    @Test fun atDoubleFontTheButtonsStackWithThePrimaryFirstAndNoLabelIsCutOff() {
        RuntimeEnvironment.setFontScale(2f)
        showTrust()
        val cancel = button("Cancel").boundsInRoot
        val trust = button("Trust and connect").boundsInRoot
        assertTrue("primary above Cancel", trust.bottom <= cancel.top)
        assertEquals("same width", cancel.width, trust.width, 1f)
        assertFalse("Trust and connect cut off", labelCutOff("Trust and connect"))
        assertFalse("Cancel cut off", labelCutOff("Cancel"))
    }

    @Test fun thePasswordWaysTrustDialogHasTheSameStacking() {
        RuntimeEnvironment.setFontScale(2f)
        showTrust(compare = "ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub")
        val primary = "Trust and continue"
        val cancel = button("Cancel").boundsInRoot
        val trust = button(primary).boundsInRoot
        assertTrue(trust.bottom <= cancel.top)
        assertFalse(labelCutOff(primary))
    }
}
