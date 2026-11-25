package dev.kudde.android.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import dev.kudde.android.R
import uniffi.kudde_mobile.ChatAnswer
import uniffi.kudde_mobile.ChatAsk
import uniffi.kudde_mobile.ChatChoice
import uniffi.kudde_mobile.ChatOption
import uniffi.kudde_mobile.ChatQuestion

// The card that answers Claude Code's multiple-choice question from the chat
// (chat-questions; chat-view delta "A pending question shows as a card" and
// after it; accessibility delta "A question card is read in order"). The hub
// decides when it shows (the poll's question) and sends the keys; this file
// draws it and keeps what was tapped. Texts are the signed-off table.

const val QUESTION_FROM_AGENT = "Question from the agent"
const val SOMETHING_ELSE = "Something else"
const val TYPE_YOUR_ANSWER = "Type your answer"
const val SEND = "Send"
const val TOO_LONG = "Too long: 2000 characters at most"
const val SKIP = "Skip"
const val SEND_ANSWERS = "Send answers"
const val CHOOSE_ANY = "Choose any number"
const val PREVIEW = "Preview"
const val ALREADY_ANSWERED = "Already answered at the desk. Nothing was sent."
const val QUESTION_CHANGED = "The question changed. Nothing was sent."
const val NOT_CONFIRMED = "Could not confirm the answer arrived. Check the terminal."
const val OPEN_TERMINAL = "Open the terminal"
const val NOT_CONNECTED = "Not connected. Nothing was sent."
const val NOTE_HINT = "Note (optional)"
const val CHOOSE_THIS = "Choose this"
const val ALL_ANSWERED = "All questions answered. Send your answers, or skip them all."
const val NO_HOOK = "The agent asked a question in the terminal. To answer it here, add the question hook on the desk."

/** The longest own answer, in characters. */
const val OTHER_MAX = 2000

/** The tool whose done call is the card's quiet line. */
const val QUESTION_TOOL = "AskUserQuestion"

fun stepLine(n: Int, of: Int) = "Question $n of $of"

fun previewTitle(label: String) = "Preview: $label"

fun canSendOther(text: String) = text.isNotBlank() && text.length <= OTHER_MAX

/** Options with previews: the dialog has no free-text row then, so no "Something else" (owner, 2026-11-25). */
fun hasPreviews(a: ChatAsk) = a.options.any { it.preview != null }

/** "Choose this" in the preview sheet: the option, with the note when one was typed. */
fun chooseThis(i: Int, note: String): ChatChoice =
    if (note.isBlank()) ChatChoice.Pick(i.toUInt()) else ChatChoice.Note(i.toUInt(), note)

fun otherProblem(text: String): String? = if (text.length > OTHER_MAX) TOO_LONG else null

/**
 * What TalkBack reads for option [i] (from 0) of [of]: one stop; [checked]
 * for a multi-select option, null for a single choice.
 */
fun optionSpoken(i: Int, of: Int, o: ChatOption, checked: Boolean? = null): String = listOfNotNull(
    "Option ${i + 1} of $of",
    o.label,
    o.description.takeIf { it.isNotBlank() },
    "Preview available".takeIf { o.preview != null },
    checked?.let { if (it) "checked" else "not checked" },
).joinToString(", ")

/** Why nothing was sent, or that it was not confirmed, and whether the terminal helps. */
data class CardProblem(val text: String, val terminal: Boolean)

fun problemOf(a: ChatAnswer): CardProblem? = when (a) {
    ChatAnswer.Done -> null
    ChatAnswer.AlreadyAnswered -> CardProblem(ALREADY_ANSWERED, terminal = false)
    ChatAnswer.Changed -> CardProblem(QUESTION_CHANGED, terminal = false)
    ChatAnswer.Unconfirmed -> CardProblem(NOT_CONFIRMED, terminal = true)
    ChatAnswer.NotConnected -> CardProblem(NOT_CONNECTED, terminal = false)
    // A check that could not be made: the terminal is where to look (owner, 2026-11-24).
    is ChatAnswer.Failed -> CardProblem(NOT_CONFIRMED, terminal = true)
}

/**
 * What one card holds besides the poll: the option tapped, whether an answer
 * is on its way, the own-words field and the open preview. All of it belongs
 * to one step of one call: a new step or call starts it afresh. Main thread.
 */
class QuestionCard {
    private var shown: Triple<String, UInt, Boolean>? = null
    var chosen by mutableStateOf<Int?>(null)
        private set
    var sending by mutableStateOf(false)
        private set

    /** The step's answer arrived: the card stays disabled until the poll shows the next step. */
    private var done by mutableStateOf(false)
    var problem by mutableStateOf<CardProblem?>(null)
        private set
    var otherOpen by mutableStateOf(false)
    var otherText by mutableStateOf("")
    var preview by mutableStateOf<ChatOption?>(null)

    /** The note typed in the open preview sheet. */
    var note by mutableStateOf("")

    /** A multi-select question's ticked options. */
    var ticked by mutableStateOf<Set<Int>>(emptySet())
        private set

    /** The typed answer, when there is one to send. */
    val typedAnswer: String? get() = otherText.takeIf { it.isNotBlank() }

    /** Ticks plus the typed answer: the multi-select count. */
    val chosenCount: Int get() = ticked.size + (if (typedAnswer != null) 1 else 0)

    /** The multi-select Send: something chosen, and the typed answer within its limit. */
    val canSendMulti: Boolean get() = chosenCount > 0 && otherText.length <= OTHER_MAX

    fun toggle(i: Int) {
        if (!busy) ticked = if (i in ticked) ticked - i else ticked + i
    }

    /** Buttons are disabled: an answer is on its way or arrived for this step. */
    val busy: Boolean get() = sending || done

    /** The poll's card: a new call or step forgets what was tapped. */
    fun follow(q: ChatQuestion?) {
        val key = q?.let { Triple(it.id, it.step, it.review) }
        if (key == shown) return
        shown = key
        chosen = null
        sending = false
        done = false
        problem = null
        otherOpen = false
        otherText = ""
        preview = null
        note = ""
        ticked = emptySet()
    }

    /** Opens the preview sheet of option [o] with an empty note. */
    fun openPreview(o: ChatOption) {
        preview = o
        note = ""
    }

    /** A tap: false while an answer is on its way. */
    fun start(choice: ChatChoice): Boolean {
        if (busy) return false
        chosen = when (choice) {
            is ChatChoice.Pick -> choice.index.toInt()
            is ChatChoice.Note -> choice.index.toInt()
            else -> null
        }
        sending = true
        problem = null
        return true
    }

    fun finished(a: ChatAnswer) {
        sending = false
        problem = problemOf(a)
        done = a == ChatAnswer.Done
        if (!done) chosen = null
    }
}

/**
 * What TalkBack hears of the card, each once: the card's arrival with its
 * question, a step change ("Send answers" for the last), and a refusal when
 * it appears.
 */
class CardHeard {
    private var id: String? = null
    private var step: Pair<UInt, Boolean>? = null
    private var problem: String? = null

    fun next(q: ChatQuestion?, problem: String?): List<String> {
        if (q == null) {
            id = null
            step = null
            this.problem = null
            return emptyList()
        }
        val out = mutableListOf<String>()
        val at = q.step to q.review
        if (q.id != id) {
            out += "$QUESTION_FROM_AGENT. ${shownText(q)}"
        } else if (at != step) {
            // The last step is heard as its button (owner, 2026-11-24).
            out += if (q.review) SEND_ANSWERS else stepLine(q.step.toInt() + 1, q.questions.size)
        }
        id = q.id
        step = at
        if (problem != null && problem != this.problem) out += problem
        this.problem = problem
        return out
    }

    private fun shownText(q: ChatQuestion): String =
        q.questions.getOrNull(q.step.toInt())?.text ?: q.questions.firstOrNull()?.text ?: q.texts.firstOrNull()
            ?: NO_HOOK.takeIf { q.hookMissing }.orEmpty()
}

/**
 * The card at the bottom of the chat, in the message box's place; it scrolls
 * inside its own area when taller. What it shows and how it reads is here;
 * how it looks is all in ChatQuestionLook.kt.
 */
@Composable
fun QuestionCard(
    q: ChatQuestion,
    card: QuestionCard,
    onAnswer: (ChatChoice) -> Unit,
    onTerminal: () -> Unit,
    modifier: Modifier = Modifier,
    focusOther: Boolean = true,
) {
    val ask = q.questions.getOrNull(q.step.toInt()).takeIf { q.readable && !q.review }
    fun answer(choice: ChatChoice) {
        if (card.start(choice)) onAnswer(choice)
    }
    // Back closes the own-words field (and its keyboard), keeping the card.
    BackHandler(enabled = card.otherOpen) { card.otherOpen = false }
    // An answer on its way, from any button or TalkBack: no keyboard until the card moves on.
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(card.busy) {
        if (card.busy) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    CardSurface(
        modifier,
        body = {
            if (!q.readable) for (t in q.texts) QuestionTitle(t)
            if (!q.readable && q.hookMissing) CardText(NO_HOOK)
            if (q.readable && q.review) CardText(ALL_ANSWERED)
            if (ask != null) {
                if (q.questions.size > 1) StepLine(stepLine(q.step.toInt() + 1, q.questions.size))
                QuestionTitle(ask.text)
                if (ask.multi && ask.answerable) Hint(CHOOSE_ANY)
            }
            card.problem?.let { p -> Notice(p.text, if (p.terminal) onTerminal else null) }
            if (ask != null) Options(ask, card, focusOther, ::answer)
        },
        footer = {
            when {
                q.review -> Footer(
                    left = {},
                    right = {
                        SolidButton(SEND_ANSWERS, enabled = !card.busy) { answer(ChatChoice.Submit) }
                        OutlineButton(SKIP, enabled = !card.busy) { answer(ChatChoice.Skip) }
                    },
                )
                ask == null || !ask.answerable -> Footer(left = {}, right = { OutlineButton(OPEN_TERMINAL, enabled = true, onClick = onTerminal) })
                ask.multi -> Footer(
                    fullRule = true,
                    endsWithCircle = true,
                    left = { CountText(pluralSelected(card.chosenCount)) },
                    right = {
                        OutlineButton(SKIP, enabled = !card.busy) { answer(ChatChoice.Skip) }
                        // The one send: the ticks and the typed answer together (owner, 2026-11-25).
                        SendCircle(enabled = !card.busy && card.canSendMulti) {
                            answer(ChatChoice.Multi(card.ticked.sorted().map { it.toUInt() }, card.typedAnswer))
                        }
                    },
                    below = otherProblem(card.otherText),
                )
                // A question with previews has no free-text row in the dialog: no Something else there.
                hasPreviews(ask) -> Footer(left = {}, right = { OutlineButton(SKIP, enabled = !card.busy) { answer(ChatChoice.Skip) } })
                else -> Footer(
                    left = { if (card.otherOpen) Other(card, focusOther, ::answer) else SomethingElse(enabled = !card.busy) { card.otherOpen = true } },
                    right = { OutlineButton(SKIP, enabled = !card.busy) { answer(ChatChoice.Skip) } },
                    below = otherProblem(card.otherText).takeIf { card.otherOpen },
                    fieldOpen = card.otherOpen,
                )
            }
        },
    )
}

@Composable
private fun pluralSelected(n: Int): String = stringResource(R.string.chat_question_selected, n)

/** The options: tappable rows for a single choice, ticks for several, plain rows when the card cannot answer. */
@Composable
private fun Options(ask: ChatAsk, card: QuestionCard, focusOther: Boolean, answer: (ChatChoice) -> Unit) {
    val n = ask.options.size
    for ((i, o) in ask.options.withIndex()) {
        val enabled = !card.busy && ask.answerable
        val checked = if (ask.multi) i in card.ticked else null
        val chosen = !ask.multi && card.chosen == i
        val act = {
            if (ask.multi) card.toggle(i) else answer(ChatChoice.Pick(i.toUInt()))
        }
        val spoken = optionSpoken(i, n, o, checked.takeIf { ask.answerable })
        OptionRow(
            first = i == 0,
            chosen = chosen,
            lead = { if (ask.multi) CheckBox(checked == true) else Keycap(i + 1) },
            label = o.label,
            description = o.description,
            // One stop per option: its number, label, description and state.
            click = if (!ask.answerable) Modifier.clearAndSetSemantics { contentDescription = spoken } else Modifier
                .clickable(enabled = enabled) { act() }
                .clearAndSetSemantics {
                    contentDescription = spoken
                    role = Role.Button
                    if (chosen) selected = true
                    if (!enabled) disabled()
                    onClick { act(); true }
                },
            trailing = if (o.preview != null && ask.answerable) ({ PreviewPill { card.openPreview(o) } }) else null,
        )
    }
    if (ask.multi && ask.answerable) {
        // The typed answer is one more ticked row; the footer's Send takes it with the ticks.
        when {
            card.otherOpen -> OtherRow { Other(card, focusOther, answer, multi = true) }
            card.typedAnswer != null -> TypedRow(
                card.otherText,
                enabled = !card.busy,
                spoken = listOf(SOMETHING_ELSE, card.otherText, "checked").joinToString(", "),
            ) { card.otherOpen = true }
            else -> SomethingElseRow(enabled = !card.busy) { card.otherOpen = true }
        }
    }
}

/**
 * The own-words field: it takes the focus (and the keyboard) when it opens;
 * Send gives both back. Send is enabled for 1 to 2000 characters.
 */
@Composable
private fun RowScope.Other(card: QuestionCard, takeFocus: Boolean, answer: (ChatChoice) -> Unit, multi: Boolean = false) {
    val text = card.otherText
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (takeFocus) focus.requestFocus() }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }
    OtherField(
        empty = text.isEmpty(),
        focused = focused,
        lead = { if (multi) CheckBox(text.isNotBlank()) else Pencil() },
        // On multi-select the footer's one Send takes the text with the ticks.
        send = if (multi) null else ({
            SendCircle(enabled = !card.busy && canSendOther(text), small = true) {
                focusManager.clearFocus()
                keyboard?.hide()
                answer(ChatChoice.Other(text))
            }
        }),
    ) { style, cursor ->
        BasicTextField(
            text, { card.otherText = it },
            // While the answer is on its way the field holds its text but takes no focus.
            enabled = !card.busy,
            textStyle = style,
            cursorBrush = cursor,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused },
        )
    }
}
