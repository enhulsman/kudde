package dev.kudde.android.ui.components

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
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
import dev.kudde.android.ui.hostkey.HostKeyChange
import dev.kudde.android.ui.hostkey.HostKeyChangedActions
import dev.kudde.android.ui.hostkey.HostKeyChangedScreen
import dev.kudde.android.ui.hostkey.HostKeyPrompt
import dev.kudde.android.ui.machines.MachinesActions
import dev.kudde.android.ui.machines.MachinesScreen
import dev.kudde.android.ui.machines.machinesUi
import dev.kudde.android.ui.terminal.ComposeSheet
import dev.kudde.android.testing.focused
import dev.kudde.android.testing.windowRoots
import dev.kudde.android.testing.composeRoots
import uniffi.kudde_mobile.HubSnapshot
import uniffi.kudde_mobile.MachineState
import uniffi.kudde_mobile.MachineView
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

    // Tab and Shift+Tab follow placement order, so the buttons must be placed in input order (Cancel first) in
    // both layouts. Three buttons tell the order from the cycle; the focus manager moves as the Tab key does.
    private val three = listOf("First", "Second", "Third")

    private fun showThree(widthDp: Int): androidx.compose.ui.focus.FocusManager {
        lateinit var manager: androidx.compose.ui.focus.FocusManager
        lateinit var input: androidx.compose.ui.input.InputModeManager
        activity.setContent {
            KuddeTheme(dark = true) {
                manager = androidx.compose.ui.platform.LocalFocusManager.current
                input = androidx.compose.ui.platform.LocalInputModeManager.current
                Box(androidx.compose.ui.Modifier.width(widthDp.dp)) {
                    DialogButtons { three.forEachIndexed { i, label -> DialogButton(label, {}, solid = i == three.lastIndex) } }
                }
            }
        }
        BoundedFrames.settle(minFrames = 3)
        // A keyboard is in use: only then do tappable boxes take focus (in touch mode they do not).
        input.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
        BoundedFrames.settle(minFrames = 2)
        return manager
    }

    private fun focusedLabel(): String? =
        unmergedSemanticsNodes().firstOrNull { it.focused() }?.let { n -> (n.texts() + n.children.flatMap { it.texts() }).firstOrNull() }

    private fun step(manager: androidx.compose.ui.focus.FocusManager, direction: androidx.compose.ui.focus.FocusDirection): String? {
        manager.moveFocus(direction)
        BoundedFrames.settle(minFrames = 2)
        return focusedLabel()
    }

    private fun assertInputOrderTab(manager: androidx.compose.ui.focus.FocusManager) {
        var guard = 0
        while (focusedLabel() != three[0] && guard++ < 6) step(manager, androidx.compose.ui.focus.FocusDirection.Next)
        assertEquals(three[0], focusedLabel())
        assertEquals("Tab", three[1], step(manager, androidx.compose.ui.focus.FocusDirection.Next))
        assertEquals("Tab", three[2], step(manager, androidx.compose.ui.focus.FocusDirection.Next))
        assertEquals("Shift+Tab", three[1], step(manager, androidx.compose.ui.focus.FocusDirection.Previous))
        assertEquals("Shift+Tab", three[0], step(manager, androidx.compose.ui.focus.FocusDirection.Previous))
    }

    @Test fun sideBySideTabFollowsInputOrderSoCancelComesFirst() {
        val manager = showThree(412)
        assertEquals("one row", button("First").boundsInRoot.top, button("Third").boundsInRoot.top, 1f)
        assertTrue("input order left to right", button("First").boundsInRoot.right <= button("Third").boundsInRoot.left)
        assertInputOrderTab(manager)
    }

    @Test fun stackedTabFollowsInputOrderEvenThoughThePrimaryIsOnTop() {
        val manager = showThree(120)
        assertTrue("stacked, last on top", button("Third").boundsInRoot.bottom <= button("Second").boundsInRoot.top)
        assertTrue(button("Second").boundsInRoot.bottom <= button("First").boundsInRoot.top)
        assertInputOrderTab(manager)
    }

    private val change = HostKeyChange("server", "server.example.org:22", "SHA256:Zt7oP1qW3eR5tY7uI9oP2aS4dF6gH8jK0lZ2xC4vB6n", "SHA256:abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG", "ED25519")

    private fun showForget() {
        activity.setContent {
            KuddeTheme(dark = true) {
                HostKeyChangedScreen(change, confirmingForget = true, error = null, actions = HostKeyChangedActions({}, {}, {}, {}))
            }
        }
        BoundedFrames.settle(minFrames = 3)
    }

    private fun showRemove() {
        val res = RuntimeEnvironment.getApplication().resources
        val machine = MachineView("server", "server.example.org", 22u, "dev", "SHA256:example", MachineState.Connected("0.1.15"), 0uL)
        val ui = machinesUi(HubSnapshot(listOf(machine), emptyList(), emptyList(), null), null, 1_000_000L, res).copy(removing = "server")
        activity.setContent { KuddeTheme(dark = true) { MachinesScreen(ui, MachinesActions.Preview.copy(onBack = {})) } }
        BoundedFrames.settle(minFrames = 3)
    }

    private fun assertStackedAndWhole(primary: String) {
        val cancel = button("Cancel").boundsInRoot
        val confirm = button(primary).boundsInRoot
        assertTrue("$primary above Cancel", confirm.bottom <= cancel.top)
        assertEquals("same width", cancel.width, confirm.width, 1f)
        assertWhole(primary)
    }

    private fun assertWhole(primary: String) {
        assertFalse("$primary cut off", labelCutOff(primary))
        assertFalse("Cancel cut off", labelCutOff("Cancel"))
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi") fun theForgetDialogAtDoubleFontOnANarrowPhoneStacksAnd() {
        RuntimeEnvironment.setFontScale(2f)
        showForget()
        assertStackedAndWhole("Forget key")
    }

    @Test fun theForgetDialogAtNormalSizeIsOneRowAnd() {
        showForget()
        assertEquals(button("Cancel").boundsInRoot.top, button("Forget key").boundsInRoot.top, 1f)
    }

    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi") fun theRemoveDialogAtDoubleFontOnANarrowPhoneStacksAnd() {
        RuntimeEnvironment.setFontScale(2f)
        showRemove()
        assertStackedAndWhole("Remove")
    }

    @Test fun theRemoveDialogAtNormalSizeIsOneRowAnd() {
        showRemove()
        assertEquals(button("Cancel").boundsInRoot.top, button("Remove").boundsInRoot.top, 1f)
    }

    /** The terminal paste prompt keeps its own Row of two DialogButtons: at 2x each label stays on one line. */
    @Test fun thePastePromptKeepsEachLabelOnOneLineAtDoubleFont() {
        RuntimeEnvironment.setFontScale(2f)
        activity.setContent {
            KuddeTheme(dark = true) { Box(androidx.compose.ui.Modifier.fillMaxSize()) { ComposeSheet("agent", "text", {}, {}, {}, focus = false) } }
        }
        BoundedFrames.settle(minFrames = 3)
        for (label in listOf("Cancel", "Paste")) {
            val node = unmergedSemanticsNodes().first { it.texts() == listOf(label) }
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            assertEquals("$label on one line", 1, results.first().lineCount)
        }
        val cancel = button("Cancel").boundsInRoot
        val paste = button("Paste").boundsInRoot
        assertEquals("same row", cancel.top, paste.top, 1f)
        assertTrue("Cancel before Paste", cancel.right <= paste.left)
    }
}
