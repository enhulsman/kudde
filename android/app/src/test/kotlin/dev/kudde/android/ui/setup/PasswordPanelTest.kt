package dev.kudde.android.ui.setup

import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.kudde.android.testing.BoundedFrames
import dev.kudde.android.testing.KuddeTestRunner
import dev.kudde.android.testing.click
import dev.kudde.android.testing.composeRoots
import dev.kudde.android.testing.descriptions
import dev.kudde.android.testing.semanticsNodes
import dev.kudde.android.testing.texts
import dev.kudde.android.testing.windowRoots
import dev.kudde.android.ui.theme.KuddeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

// Task 4.9, spec "The app can run the setup with the password once" and "The password is never kept":
// the panel's semantics, the field's protections, the window's and the autofill's while it shows.
@RunWith(KuddeTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xhdpi")
class PasswordPanelTest {
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val activity = controller.get()

    @After fun close() { controller.pause().stop().destroy() }

    private val res get() = activity.resources
    private val field = TextFieldState()
    private val taps = mutableListOf<String>()
    private val actions = KeyActions(
        {}, { taps += "choose ${it.name}" }, {}, {}, {}, {}, {},
        onAddKey = { taps += "add" }, onClosePassword = { taps += "cancel" }, onPasswordTryAgain = { taps += "again" },
    )
    private var panel by mutableStateOf<PasswordPanel>(PasswordPanel.Field(null))
    private var showing by mutableStateOf(true)
    private var autofillCancels = 0

    private fun ui() = KeyUi(
        "workstation", "dev", KeyNotice.None, false, null, false, true, true, false,
        password = if (showing) PasswordUi(passwordView(panel, "workstation", "dev", "workstation", 22, res), field) else null,
    )

    private fun show() {
        activity.setContent {
            KuddeTheme(dark = true) {
                // The turning ring loops forever and would hold the test's clock: motion is off here, as in the goldens.
                CompositionLocalProvider(LocalPasswordSeams provides PasswordSeams(cancelAutofill = { autofillCancels++ }), LocalSetupMotion provides false) {
                    KeyStep(ui(), actions)
                }
            }
        }
        BoundedFrames.settle(minFrames = 3)
    }

    private fun settle() = BoundedFrames.settle(minFrames = 3)
    private fun nodes() = semanticsNodes()
    private fun fieldNode(): SemanticsNode? = nodes().firstOrNull { it.config.contains(SemanticsProperties.Password) }
    private fun button(label: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null && it.texts().firstOrNull() == label }

    @Test fun theFieldIsNamedByR6AndCarriesPasswordSemantics() {
        show()
        val node = fieldNode()
        assertNotNull(node)
        assertTrue("Password for dev on workstation" in node!!.descriptions() || node.texts().contains("Password for dev on workstation") ||
            nodes().any { "Password for dev on workstation" in it.texts() })
        assertEquals(ContentType.Password, node.config.getOrNull(SemanticsProperties.ContentType))
    }

    @Test fun typedTextIsNeverInTheSemanticsTree() {
        show()
        field.setTextAndPlaceCursorAtEnd("hunter2-secret")
        settle()
        val everything = nodes().flatMap { it.texts() + it.descriptions() }
        assertTrue(everything.toString(), everything.none { it.contains("hunter2") })
        // What a screen reader is given for the field: flagged as a password, and the typed text is not in it.
        val node = fieldNode()!!
        val host = windowRoots().flatMap(::composeRoots).first { r -> r.semanticsOwner.rootSemanticsNode.id != -1 } as View
        val info = host.accessibilityNodeProvider!!.createAccessibilityNodeInfo(node.id)!!
        assertTrue(info.isPassword)
        assertTrue(info.text.toString(), info.text?.toString()?.contains("hunter2") != true)
    }

    @Test fun theFieldOffersNoCopyOrCut() {
        show()
        field.setTextAndPlaceCursorAtEnd("hunter2-secret")
        settle()
        val node = fieldNode()!!
        assertNull(node.config.getOrNull(SemanticsActions.CopyText))
        assertNull(node.config.getOrNull(SemanticsActions.CutText))
    }

    @Test fun workingShowsTheChecklistInOneLiveRegion() {
        panel = PasswordPanel.Working
        show()
        val texts = nodes().flatMap { it.texts() }
        assertTrue(texts.containsAll(listOf("Signing in as dev", "Adding the key", "Signing out")))
        assertNull("no field while it runs", fieldNode())
        val live = nodes().filter { it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite }
        assertEquals(1, live.size)
    }

    @Test fun aWrongPasswordIsAnnouncedOnceAndTheFieldIsBack() {
        panel = PasswordPanel.WrongPassword(again = false)
        show()
        val live = nodes().filter { it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite }
        assertEquals("one live region holds the checklist and its outcome", 1, live.size)
        assertEquals(1, nodes().count { n -> n.texts().any { it == "workstation did not accept the password for dev." } })
        assertNotNull(fieldNode())
    }

    @Test fun theButtonsDoWhatTheyNameAndNothingMore() {
        panel = PasswordPanel.Field(null)
        show()
        assertTrue(button("Add the key")!!.click())
        assertTrue(button("Add it yourself")!!.click())
        assertTrue(button("Cancel")!!.click())
        assertEquals(listOf("add", "choose MANUAL", "cancel"), taps)
    }

    @Test fun theWindowIsSecureOnlyWhileThePanelShows() {
        show()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        showing = false
        settle()
        assertFalse(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    @Test fun aWindowThatWasSecureStaysSecure() {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        show()
        showing = false
        settle()
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    /** The views hosting each composition: the parent of Compose's own view. */
    private fun hosts(): List<View> = windowRoots().flatMap(::composeRoots).mapNotNull { (it as View).parent as? View }

    @Test fun autofillIsOffForTheHostingViewWhileThePanelShowsAndRestoredAfter() {
        show()
        val before = hosts().map { it.importantForAutofill }
        assertTrue(before.toString(), before.all { it == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS })
        showing = false
        settle()
        assertTrue(hosts().none { it.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS })
    }

    @Test fun autofillIsCancelledWhenThePanelCloses() {
        show()
        assertEquals(0, autofillCancels)
        showing = false
        settle()
        assertEquals(1, autofillCancels)
    }
}
