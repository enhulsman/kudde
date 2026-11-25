package dev.kudde.android.ui.setup

import dev.kudde.android.testing.KuddeTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import uniffi.kudde_mobile.ReachKind

// Task 4.9: what the password way's panel shows for each state (design D12, D14): the checklist,
// the outcome on the step that failed, the field only where typing again helps, the buttons.
@RunWith(KuddeTestRunner::class)
class PasswordViewTest {
    private val res = RuntimeEnvironment.getApplication().resources
    private fun view(panel: PasswordPanel) = passwordView(panel, "workstation", "dev", "workstation", 22, res)

    // Review finding (7.1): formatting the UI model must never print what was typed.
    @Test fun theUiModelsToStringNeverHoldsThePassword() {
        val state = androidx.compose.foundation.text.input.TextFieldState("hunter2-secret")
        val ui = PasswordUi(view(PasswordPanel.Field(null)), state)
        assertTrue(ui.toString(), !ui.toString().contains("hunter2"))
        val key = KeyUi("workstation", "dev", KeyNotice.None, false, null, false, true, true, false, password = ui)
        assertTrue(!key.toString().contains("hunter2"))
    }

    @Test fun theFieldShowsTheSentenceAndTheLabelWithNoChecklist() {
        val v = view(PasswordPanel.Field(null))
        assertNull(v.marks)
        assertTrue(v.sentence)
        assertEquals("Password for dev on workstation", v.field)
        assertEquals(listOf(PanelButton.AddKey, PanelButton.Manual, PanelButton.Cancel), v.buttons)
    }

    @Test fun aKeyboardInteractivePromptJoinsTheLabelAsTheMachineSentIt() {
        assertEquals("Password for dev on workstation: Verification code:", view(PasswordPanel.Field("Verification code:")).field)
    }

    @Test fun workingIsTheChecklistMidWay() {
        val v = view(PasswordPanel.Working)
        assertEquals(listOf(StepMark.Done, StepMark.Now, StepMark.Todo), v.marks)
        assertNull(v.outcome)
        assertNull(v.field)
        assertEquals(listOf(PanelButton.Manual, PanelButton.Cancel), v.buttons)
    }

    @Test fun aWrongPasswordLandsOnSigningInAndBringsTheFieldBack() {
        val v = view(PasswordPanel.WrongPassword(again = false))
        assertEquals(listOf(StepMark.Failed, StepMark.Todo, StepMark.Todo), v.marks)
        assertEquals(0, v.outcomeRow)
        assertEquals(Outcome("workstation did not accept the password for dev.", "Check it and try again. Nothing was changed there."), v.outcome)
        assertEquals("Password for dev on workstation", v.field)
        assertEquals(listOf(PanelButton.AddKey, PanelButton.Manual, PanelButton.Cancel), v.buttons)
    }

    @Test fun theSecondRefusalWarnsAboutLockouts() {
        val v = view(PasswordPanel.WrongPassword(again = true))
        assertEquals("workstation refused the password again.", v.outcome!!.title)
        assertTrue(v.outcome!!.detail!!.startsWith("Some machines lock an account"))
        assertEquals(0, v.outcomeRow)
    }

    @Test fun noPasswordMethodHasNoFieldAndPointsToTheCommand() {
        val v = view(PasswordPanel.NoPassword)
        assertEquals("workstation does not take a password over SSH.", v.outcome!!.title)
        assertNull(v.field)
        assertEquals(listOf(PanelButton.Manual), v.buttons)
        assertEquals(0, v.outcomeRow)
    }

    @Test fun moreThanAPasswordHasNoField() {
        val v = view(PasswordPanel.MoreThanPassword)
        assertEquals("workstation asks for more than a password, such as a code.", v.outcome!!.title)
        assertNull(v.field)
        assertEquals(listOf(PanelButton.Manual), v.buttons)
    }

    @Test fun aDroppedConnectionLandsOnAddingTheKeyAndAllowsAnotherTry() {
        val v = view(PasswordPanel.Dropped)
        assertEquals(listOf(StepMark.Done, StepMark.Failed, StepMark.Todo), v.marks)
        assertEquals(1, v.outcomeRow)
        assertTrue(v.outcome!!.title.startsWith("The connection to workstation dropped"))
        assertEquals("Password for dev on workstation", v.field)
    }

    @Test fun aScriptThatEndedWithACodeNamesTheCodeAndHasNoField() {
        val v = view(PasswordPanel.ScriptFailed(3))
        assertEquals(listOf(StepMark.Done, StepMark.Failed, StepMark.Todo), v.marks)
        assertEquals("The setup on workstation ended with code 3.", v.outcome!!.title)
        assertNull(v.field)
        assertEquals(listOf(PanelButton.Manual), v.buttons)
    }

    @Test fun stillRefusedShowsAllThreeDoneAndTheTextUnderTheList() {
        val v = view(PasswordPanel.StillRefused)
        assertEquals(listOf(StepMark.Done, StepMark.Done, StepMark.Done), v.marks)
        assertNull(v.outcomeRow)
        assertEquals("kudde added the key, but workstation still refuses it.", v.outcome!!.title)
        assertNull(v.field)
        assertEquals(listOf(PanelButton.TryAgain, PanelButton.Manual), v.buttons)
    }

    @Test fun doneWaitsForTheHubWithAllThreeDone() {
        val v = view(PasswordPanel.Done)
        assertEquals(listOf(StepMark.Done, StepMark.Done, StepMark.Done), v.marks)
        assertEquals("Connecting to workstation…", v.waiting)
    }

    @Test fun probingWaitsAndOffersNothingButTheWayOut() {
        val v = view(PasswordPanel.Probing)
        assertEquals("Connecting to workstation…", v.waiting)
        assertEquals(listOf(PanelButton.Manual, PanelButton.Cancel), v.buttons)
    }

    @Test fun reachFailuresUseTheStepOneWordsAndKeepTheWayOut() {
        val v = view(PasswordPanel.Reach(ReachKind.REFUSED))
        assertEquals("workstation answered, but nothing listens on port 22.", v.outcome!!.title)
        assertEquals(listOf(PanelButton.TryAgain, PanelButton.Manual), v.buttons)
    }
}
