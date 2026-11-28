package dev.kudde.android.ui.setup

import android.content.res.Resources
import dev.kudde.android.R
import uniffi.kudde_mobile.ReachKind

/** One row of the password way's checklist (design D14). */
enum class StepMark { Done, Now, Todo, Failed }

/** The buttons the panel can offer; the first one shown is the filled one. */
enum class PanelButton { AddKey, Manual, Cancel, TryAgain }

/** An outcome's two lines (R9 to R16): what happened, and what to do. */
data class Outcome(val title: String, val detail: String?)

/**
 * What the panel of the password way shows for one [PasswordPanel] state: the checklist ([marks], null
 * before anything runs), the [outcome] under the row it failed on ([outcomeRow], null for a block under the
 * whole list), the field's label only where typing again helps, a waiting line, and the buttons.
 */
data class PasswordView(
    val marks: List<StepMark>?,
    val outcome: Outcome?,
    val outcomeRow: Int?,
    val field: String?,
    val sentence: Boolean,
    val waiting: String?,
    val buttons: List<PanelButton>,
)

private val fieldButtons = listOf(PanelButton.AddKey, PanelButton.Manual, PanelButton.Cancel)

fun passwordView(panel: PasswordPanel, machine: String, user: String, host: String, port: Int, res: Resources): PasswordView {
    fun label(prompt: String? = null) =
        if (prompt.isNullOrBlank() || prompt.trim().removeSuffix(":").trim().equals("password", ignoreCase = true)) res.getString(R.string.r_field_label, machine, user)
        else res.getString(R.string.r_field_label_prompt, machine, user, prompt)
    fun failed(row: Int, title: String, detail: String?, field: Boolean, buttons: List<PanelButton>) = PasswordView(
        marks = List(3) { if (it < row) StepMark.Done else if (it == row) StepMark.Failed else StepMark.Todo },
        outcome = Outcome(title, detail), outcomeRow = row,
        field = if (field) label() else null, sentence = false, waiting = null, buttons = buttons,
    )
    return when (panel) {
        PasswordPanel.Hidden -> PasswordView(null, null, null, null, false, null, emptyList())
        PasswordPanel.Probing -> PasswordView(
            null, null, null, null, false, res.getString(R.string.a_connecting, machine), listOf(PanelButton.Manual, PanelButton.Cancel),
        )
        is PasswordPanel.Field -> PasswordView(null, null, null, label(panel.prompt), true, null, fieldButtons)
        PasswordPanel.Working -> PasswordView(
            listOf(StepMark.Now, StepMark.Todo, StepMark.Todo), null, null, null, false, null, listOf(PanelButton.Manual, PanelButton.Cancel),
        )
        PasswordPanel.WorkingKey -> PasswordView(
            listOf(StepMark.Done, StepMark.Now, StepMark.Todo), null, null, null, false, null, listOf(PanelButton.Manual, PanelButton.Cancel),
        )
        is PasswordPanel.WrongPassword ->
            if (panel.again) failed(0, res.getString(R.string.r_again_title, machine), res.getString(R.string.r_again_detail), true, fieldButtons)
            else failed(0, res.getString(R.string.r_wrong_title, machine, user), res.getString(R.string.r_wrong_detail), true, fieldButtons)
        PasswordPanel.NoPassword ->
            failed(0, res.getString(R.string.r_none_title, machine), res.getString(R.string.r_none_detail), false, listOf(PanelButton.Manual))
        PasswordPanel.MoreThanPassword ->
            failed(0, res.getString(R.string.r_more_title, machine), res.getString(R.string.r_more_detail), false, listOf(PanelButton.Manual))
        PasswordPanel.Dropped ->
            failed(1, res.getString(R.string.r_dropped_title, machine), res.getString(R.string.r_dropped_detail), true, fieldButtons)
        is PasswordPanel.ScriptFailed ->
            failed(1, res.getString(R.string.r_code_title, machine, panel.code), res.getString(R.string.r_code_detail), false, listOf(PanelButton.Manual))
        is PasswordPanel.Reach -> PasswordView(
            null, reachOutcome(panel.kind, host, port, res), null, null, false, null, listOf(PanelButton.TryAgain, PanelButton.Manual),
        )
        PasswordPanel.NetworkOff -> PasswordView(
            null, Outcome(res.getString(R.string.setup_reach_no_route, host), res.getString(R.string.setup_reach_no_route_help)), null, null, false, null,
            listOf(PanelButton.TryAgain, PanelButton.Manual),
        )
        PasswordPanel.Cancelled -> PasswordView(
            null, Outcome(res.getString(R.string.x_cancelled_title, machine), null), null, null, false, null, listOf(PanelButton.TryAgain, PanelButton.Manual),
        )
        PasswordPanel.CancelledSignedIn -> PasswordView(
            listOf(StepMark.Done, StepMark.Todo, StepMark.Todo),
            Outcome(res.getString(R.string.x_signed_in_title), res.getString(R.string.x_signed_in_detail, machine)), null, null, false, null,
            listOf(PanelButton.TryAgain, PanelButton.Manual),
        )
        PasswordPanel.CancelledKeyAdded -> PasswordView(
            List(3) { StepMark.Done },
            Outcome(res.getString(R.string.x_key_added_title), res.getString(R.string.x_key_added_detail, machine)), null, null, false,
            res.getString(R.string.a_connecting, machine), emptyList(),
        )
        // The hub shows its own changed-key screen; nothing of the panel remains.
        is PasswordPanel.ChangedKey -> PasswordView(null, null, null, null, false, null, emptyList())
        PasswordPanel.Done -> PasswordView(
            List(3) { StepMark.Done }, null, null, null, false, res.getString(R.string.a_connecting, machine), emptyList(),
        )
        PasswordPanel.StillRefused -> PasswordView(
            List(3) { StepMark.Done },
            Outcome(res.getString(R.string.r_refused_title, machine), res.getString(R.string.r_refused_detail)),
            null, null, false, null, listOf(PanelButton.TryAgain, PanelButton.Manual),
        )
    }
}

private fun reachOutcome(kind: ReachKind, host: String, port: Int, res: Resources): Outcome = when (kind) {
    ReachKind.NO_ANSWER -> Outcome(res.getString(R.string.setup_reach_no_answer, host, port), res.getString(R.string.setup_reach_no_answer_help))
    ReachKind.REFUSED -> Outcome(res.getString(R.string.setup_reach_refused, host, port), res.getString(R.string.setup_reach_refused_help))
    ReachKind.NAME_NOT_FOUND -> Outcome(res.getString(R.string.setup_reach_name_not_found, host, port), res.getString(R.string.setup_reach_name_not_found_help))
    ReachKind.NO_ROUTE -> Outcome(res.getString(R.string.setup_reach_no_route, host, port), res.getString(R.string.setup_reach_no_route_help))
    ReachKind.OTHER, ReachKind.CORE_STARTUP_CLOSED -> Outcome(res.getString(R.string.setup_reach_other, host, ""), null)
}
