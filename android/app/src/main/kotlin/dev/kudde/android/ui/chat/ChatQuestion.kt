package dev.kudde.android.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
// Design D11, approved verbatim by the owner (2026-11-25).
const val PREVIOUS_QUESTION = "Previous question"
const val NEXT_QUESTION = "Next question"
const val NEXT = "Next"
const val CHANGE = "Change"
// Drafts (Ezra's try, 2026-11-25), to the owner verbatim before they ship.
const val SOME_UNANSWERED = "Some questions have no answer yet. Answer them to send, or skip them all."
const val NO_ANSWER_YET = "No answer yet"
const val ANSWER = "Answer"
fun answerSpoken(question: String) = "Answer the question: $question"

/** Claude Code asks at most four questions in one call. */
const val MAX_QUESTIONS = 4
fun changeSpoken(question: String) = "Change the answer to: $question"
fun noteLine(note: String) = "Note: $note"
fun stoppedText(sent: Int, of: Int) = "Typed $sent of $of answers, then stopped. Check the terminal."
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

fun problemOf(a: ChatAnswer, of: Int = 0): CardProblem? = when (a) {
    ChatAnswer.Done -> null
    ChatAnswer.AlreadyAnswered -> CardProblem(ALREADY_ANSWERED, terminal = false)
    ChatAnswer.Changed -> CardProblem(QUESTION_CHANGED, terminal = false)
    ChatAnswer.Unconfirmed -> CardProblem(NOT_CONFIRMED, terminal = true)
    ChatAnswer.NotConnected -> CardProblem(NOT_CONNECTED, terminal = false)
    // A check that could not be made: the terminal is where to look (owner, 2026-11-24).
    is ChatAnswer.Failed -> CardProblem(NOT_CONFIRMED, terminal = true)
    // Sending the kept answers stopped halfway (D11).
    is ChatAnswer.Stopped -> CardProblem(stoppedText(a.sent.toInt(), of), terminal = true)
}

/** The dialog of this call ends on a review step (several questions, or a multi-select one): the phone keeps the answers until Send (D11). */
fun hasReview(q: ChatQuestion) = q.questions.size > 1 || q.questions.any { it.multi }

/** What the review step lists for [c] to [ask]: the label, the typed text, or the ticked labels and the typed text. */
fun answerText(ask: ChatAsk, c: ChatChoice): String = when (c) {
    is ChatChoice.Pick -> ask.options.getOrNull(c.index.toInt())?.label.orEmpty()
    is ChatChoice.Note -> ask.options.getOrNull(c.index.toInt())?.label.orEmpty()
    is ChatChoice.Other -> c.text
    is ChatChoice.Multi -> (c.indices.sorted().mapNotNull { ask.options.getOrNull(it.toInt())?.label } + listOfNotNull(c.text)).joinToString(", ")
    else -> ""
}

/** A note kept with an option, shown beneath its answer on the review step. */
fun answerNote(c: ChatChoice): String? = (c as? ChatChoice.Note)?.text?.takeIf { it.isNotBlank() }

/**
 * What one card holds besides the poll: the answers kept per question (D11),
 * the question the phone shows, how many answers an earlier send typed, the
 * own-words field, the ticks, the open preview, and whether a send is on its
 * way. All of it belongs to one call: a new call starts it afresh; the
 * dialog's own step (the poll's) does not move the card. Main thread.
 */
class QuestionCard {
    /** The call the card holds answers for. */
    var shownId by mutableStateOf<String?>(null)
        private set
    private var count = 0

    /** The question the phone shows (from 0); [count] is the review step. */
    var at by mutableStateOf(0)
        private set

    /** The answers kept so far, by question. */
    var answers by mutableStateOf<Map<Int, ChatChoice>>(emptyMap())
        private set

    /** Answers an earlier send typed before it stopped; the next send goes on from there. */
    var sent by mutableStateOf(0)
        private set

    var chosen by mutableStateOf<Int?>(null)
        private set
    var sending by mutableStateOf(false)
        private set

    /** The answer arrived: the card stays disabled until the poll ends it. */
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

    /** The multi-select button: something chosen, and the typed answer within its limit. */
    val canSendMulti: Boolean get() = chosenCount > 0 && otherText.length <= OTHER_MAX

    /** The phone shows the review step. */
    val onReview: Boolean get() = count > 0 && at >= count

    /** Moving on is free (Ezra's try): any question, then the review step. */
    val canNext: Boolean get() = !onReview

    /** Every question has an answer: Send answers is possible. */
    val allAnswered: Boolean get() = count > 0 && (0 until count).all { it in answers }

    /** A step the person took (not a new call): the card moves the focus to what it shows. */
    var stepped by mutableStateOf(0)
        private set

    /** On the review step after a change: the answer to put the focus on. */
    var focusRow by mutableStateOf<Int?>(null)
        private set

    /** The question shown was opened from the review step: an answer goes back there. */
    private var fromReview = false

    fun toggle(i: Int) {
        if (!busy) ticked = if (i in ticked) ticked - i else ticked + i
    }

    /** Buttons are disabled: an answer is on its way or arrived. */
    val busy: Boolean get() = sending || done

    /** The poll's card: a new call forgets everything; the dialog's step does not move the card. */
    fun follow(q: ChatQuestion?) {
        if (q?.id == shownId) return
        // A card that never held a call has nothing to forget (what was set on it before stays).
        val fresh = shownId == null && answers.isEmpty() && !sending && !done
        shownId = q?.id
        count = q?.questions?.size ?: 0
        if (fresh && q != null) return
        answers = emptyMap()
        sent = 0
        sending = false
        done = false
        problem = null
        stepped = 0
        fromReview = false
        show(0)
    }

    /** Shows question [i] (or the review step at [count]) with its kept answer restored. */
    fun show(i: Int) {
        at = i.coerceIn(0, count.coerceAtLeast(0))
        val a = answers[at]
        chosen = when (a) {
            is ChatChoice.Pick -> a.index.toInt()
            is ChatChoice.Note -> a.index.toInt()
            else -> null
        }
        ticked = (a as? ChatChoice.Multi)?.indices?.map { it.toInt() }?.toSet().orEmpty()
        otherText = when (a) {
            is ChatChoice.Other -> a.text
            is ChatChoice.Multi -> a.text.orEmpty()
            else -> ""
        }
        otherOpen = false
        preview = null
        note = ""
    }

    fun previous() {
        if (!busy && at > 0) step(at - 1)
    }

    fun next() {
        if (!busy && canNext) step(at + 1)
    }

    /** "Change" on the review step: question [i], whose answer then comes back to the review step. */
    fun change(i: Int) {
        if (busy || !onReview) return
        step(i)
        fromReview = true
    }

    private fun step(i: Int, row: Int? = null) {
        fromReview = false
        show(i)
        focusRow = row
        stepped++
    }

    /**
     * Keeps [choice] for the question shown and moves on: to the next
     * question without an answer, or the review step when every question
     * has one. Nothing is sent (D11).
     */
    fun keep(choice: ChatChoice) {
        if (busy || onReview) return
        answers = answers + (at to choice)
        problem = null
        val was = at
        if (fromReview) return step(count, row = was)
        val open = ((at + 1 until count) + (0 until at)).firstOrNull { it !in answers }
        step(open ?: count)
    }

    /** The kept answers in the call's order, for Send answers. */
    fun answersToSend(): List<ChatChoice> = (0 until count).mapNotNull { answers[it] }

    /** Opens the preview sheet of option [o] with an empty note. */
    fun openPreview(o: ChatOption) {
        preview = o
        note = ""
    }

    /** The call the send on its way was tapped on; the route sends it for this call. */
    var sentFor: Pair<String, UInt>? = null
        private set

    /**
     * A send from the card drawn for [on] (an answer at once, Skip, or Send
     * answers): false while one is on its way, or when the card has moved to
     * another call since that frame (5.4 review).
     */
    fun start(choice: ChatChoice, on: ChatQuestion? = null): Boolean {
        if (busy) return false
        if (on != null && shownId != null && on.id != shownId) return false
        val id = on?.id ?: shownId
        sentFor = id?.let { it to at.toUInt() }
        chosen = when (choice) {
            is ChatChoice.Pick -> choice.index.toInt()
            is ChatChoice.Note -> choice.index.toInt()
            else -> chosen
        }
        sending = true
        problem = null
        return true
    }

    /** The send's result; one for a call the card has left ([forKey] another call) changes nothing. */
    fun finished(a: ChatAnswer, forKey: Pair<String, UInt>? = null) {
        if (forKey != null && forKey.first != shownId) return
        sending = false
        done = a == ChatAnswer.Done
        if (a is ChatAnswer.Stopped) sent = a.sent.toInt()
        problem = problemOf(a, count)
        if (!done && !onReview) chosen = answers[at]?.let { (it as? ChatChoice.Pick)?.index?.toInt() ?: (it as? ChatChoice.Note)?.index?.toInt() }
    }
}

/**
 * What TalkBack hears of the card, each once: the card's arrival with its
 * question and a refusal when it appears. A step on the phone moves the
 * focus instead ([QuestionCard.stepped]).
 */
class CardHeard {
    private var id: String? = null
    private var step: Int? = null
    private var problem: String? = null

    /** [at]: the question the phone shows; the question count is the review step. */
    fun next(q: ChatQuestion?, problem: String?, at: Int = 0): List<String> {
        if (q == null) {
            id = null
            step = null
            this.problem = null
            return emptyList()
        }
        val out = mutableListOf<String>()
        // A step on the phone is heard where the focus lands (the new question, or the
        // review step), so only the card's arrival is announced (Ezra's try, 2026-11-25).
        if (q.id != id) out += "$QUESTION_FROM_AGENT. ${shownText(q, at)}"
        id = q.id
        step = at
        if (problem != null && problem != this.problem) out += problem
        this.problem = problem
        return out
    }

    private fun shownText(q: ChatQuestion, at: Int): String =
        q.questions.getOrNull(at)?.text ?: q.questions.firstOrNull()?.text ?: q.texts.firstOrNull()
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
    val held = q.readable && hasReview(q)
    // The route follows the poll; a card shown on its own (previews, tests) follows its call here.
    LaunchedEffect(q.id, card.shownId) { if (card.shownId != q.id) card.follow(q) }
    val ask = q.questions.getOrNull(card.at).takeIf { q.readable && !card.onReview }
    fun send(choice: ChatChoice) {
        if (card.start(choice, q)) onAnswer(choice)
    }
    // An answer: kept on the phone in a call with a review step (D11), sent at once in the others.
    fun answer(choice: ChatChoice) {
        if (held && choice !is ChatChoice.Skip) card.keep(choice) else send(choice)
    }
    // Back closes the own-words field (and its keyboard), keeping the card.
    BackHandler(enabled = card.otherOpen) { card.otherOpen = false }
    // A send on its way, from any button or TalkBack: no keyboard.
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(card.busy) {
        if (card.busy) {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }
    // A step the person took lands the focus on what it shows, at once (Ezra's try,
    // 2026-11-25): the new question, the review step, or the answer just changed.
    val titleFocus = remember { FocusRequester() }
    val reviewFocus = remember { FocusRequester() }
    val rowFocus = remember { List(MAX_QUESTIONS) { FocusRequester() } }
    LaunchedEffect(card.stepped) {
        if (card.stepped == 0) return@LaunchedEffect
        keyboard?.hide()
        val row = card.focusRow
        val target = when {
            !card.onReview -> titleFocus
            row != null && row < MAX_QUESTIONS -> rowFocus[row]
            else -> reviewFocus
        }
        runCatching { target.requestFocus() }
    }
    CardSurface(
        modifier,
        body = {
            if (!q.readable) for (t in q.texts) QuestionTitle(t)
            if (!q.readable && q.hookMissing) CardText(NO_HOOK)
            if (q.readable && card.onReview) {
                CardText(if (card.allAnswered) ALL_ANSWERED else SOME_UNANSWERED, Modifier.focusRequester(reviewFocus).focusable())
                for ((i, a) in q.questions.withIndex()) {
                    val c = card.answers[i]
                    AnswerRow(
                        a.text,
                        c?.let { answerText(a, it) } ?: NO_ANSWER_YET,
                        c?.let(::answerNote)?.let(::noteLine),
                        enabled = !card.busy,
                        answered = c != null,
                        modifier = rowFocus.getOrNull(i)?.let { Modifier.focusRequester(it).focusable() } ?: Modifier,
                    ) { card.change(i) }
                }
            }
            if (ask != null) {
                val step = stepLine(card.at + 1, q.questions.size).takeIf { q.questions.size > 1 }
                if (step != null) {
                    StepNav(
                        step,
                        back = { card.previous() }.takeIf { card.at > 0 && !card.busy },
                        forward = { card.next() }.takeIf { card.canNext && !card.busy },
                    )
                }
                // The step is read with the question, one stop that a step lands the focus on.
                QuestionTitle(
                    ask.text,
                    Modifier.focusRequester(titleFocus).focusable()
                        .clearAndSetSemantics { contentDescription = listOfNotNull(step, ask.text).joinToString(". ") },
                )
                if (ask.multi && ask.answerable) Hint(CHOOSE_ANY)
            }
            card.problem?.let { p -> Notice(p.text, if (p.terminal) onTerminal else null) }
            if (ask != null) Options(ask, card, focusOther, held, ::answer)
        },
        footer = {
            when {
                q.readable && card.onReview -> Footer(
                    left = {},
                    right = {
                        SolidButton(SEND_ANSWERS, enabled = !card.busy && card.allAnswered) { send(ChatChoice.Submit) }
                        OutlineButton(SKIP, enabled = !card.busy) { send(ChatChoice.Skip) }
                    },
                )
                ask == null || !ask.answerable -> Footer(left = {}, right = { OutlineButton(OPEN_TERMINAL, enabled = true, onClick = onTerminal) })
                ask.multi -> Footer(
                    fullRule = true,
                    endsWithCircle = true,
                    left = { CountText(pluralSelected(card.chosenCount)) },
                    right = {
                        OutlineButton(SKIP, enabled = !card.busy) { send(ChatChoice.Skip) }
                        // The ticks and the typed answer together (owner, 2026-11-25), kept until Send answers.
                        SendCircle(enabled = !card.busy && card.canSendMulti, label = NEXT) {
                            answer(ChatChoice.Multi(card.ticked.sorted().map { it.toUInt() }, card.typedAnswer))
                        }
                    },
                    below = otherProblem(card.otherText),
                )
                // A question with previews has no free-text row in the dialog: no Something else there.
                hasPreviews(ask) -> Footer(left = {}, right = { OutlineButton(SKIP, enabled = !card.busy) { send(ChatChoice.Skip) } })
                else -> Footer(
                    left = { if (card.otherOpen) Other(card, focusOther, held, ::answer) else SomethingElse(enabled = !card.busy) { card.otherOpen = true } },
                    right = { OutlineButton(SKIP, enabled = !card.busy) { send(ChatChoice.Skip) } },
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
private fun Options(ask: ChatAsk, card: QuestionCard, focusOther: Boolean, held: Boolean, answer: (ChatChoice) -> Unit) {
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
        // The typed answer is one more ticked row; the footer's button takes it with the ticks.
        when {
            card.otherOpen -> OtherRow { Other(card, focusOther, held, answer, multi = true) }
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
 * its button gives both back, enabled for 1 to 2000 characters.
 */
@Composable
private fun RowScope.Other(card: QuestionCard, takeFocus: Boolean, held: Boolean, answer: (ChatChoice) -> Unit, multi: Boolean = false) {
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
            // Kept until Send answers in a call with a review step ("Next"), sent at once otherwise.
            SendCircle(enabled = !card.busy && canSendOther(text), small = true, label = if (held) NEXT else SEND) {
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
