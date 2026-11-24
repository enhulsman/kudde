package dev.kudde.android.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
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
const val ANSWER_ABOVE_FIRST = "Answer the question above first"

/** The longest own answer, in characters. */
const val OTHER_MAX = 2000

/** The tool whose done call is the card's quiet line. */
const val QUESTION_TOOL = "AskUserQuestion"

fun stepLine(n: Int, of: Int) = "Question $n of $of"

fun previewTitle(label: String) = "Preview: $label"

fun canSendOther(text: String) = text.isNotBlank() && text.length <= OTHER_MAX

fun otherProblem(text: String): String? = if (text.length > OTHER_MAX) TOO_LONG else null

/** What TalkBack reads for option [i] (from 0) of [of]: one stop. */
fun optionSpoken(i: Int, of: Int, o: ChatOption): String = listOfNotNull(
    "Option ${i + 1} of $of",
    o.label,
    o.description.takeIf { it.isNotBlank() },
    "Preview available".takeIf { o.preview != null },
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
    }

    /** A tap: false while an answer is on its way. */
    fun start(choice: ChatChoice): Boolean {
        if (busy) return false
        chosen = (choice as? ChatChoice.Pick)?.index?.toInt()
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
        q.questions.getOrNull(q.step.toInt())?.text ?: q.questions.firstOrNull()?.text ?: q.texts.firstOrNull().orEmpty()
}

/**
 * The card at the bottom of the chat; it scrolls inside its own area when
 * taller. What it shows and how it reads is here; how it looks is all in
 * ChatQuestionLook.kt (the look is being redesigned).
 */
@Composable
fun QuestionCard(
    q: ChatQuestion,
    card: QuestionCard,
    onAnswer: (ChatChoice) -> Unit,
    onTerminal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CardSurface(modifier) {
        val ask = q.questions.getOrNull(q.step.toInt())
        when {
            !q.readable -> {
                for (t in q.texts) BasicText(t, style = QuestionLook.plainText())
                CardButton(OPEN_TERMINAL, enabled = true, onClick = onTerminal)
            }
            q.review -> CardRow {
                CardButton(SEND_ANSWERS, enabled = !card.busy, solid = true) { if (card.start(ChatChoice.Submit)) onAnswer(ChatChoice.Submit) }
                CardButton(SKIP, enabled = !card.busy) { if (card.start(ChatChoice.Skip)) onAnswer(ChatChoice.Skip) }
            }
            ask == null -> CardButton(OPEN_TERMINAL, enabled = true, onClick = onTerminal)
            else -> Ask(q, ask, card, onAnswer, onTerminal)
        }
        card.problem?.let { p ->
            BasicText(p.text, style = QuestionLook.problemText())
            if (p.terminal) CardButton(SHOW_TERMINAL, enabled = true, onClick = onTerminal)
        }
    }
}

@Composable
private fun Ask(q: ChatQuestion, ask: ChatAsk, card: QuestionCard, onAnswer: (ChatChoice) -> Unit, onTerminal: () -> Unit) {
    if (q.questions.size > 1) {
        BasicText(stepLine(q.step.toInt() + 1, q.questions.size), style = QuestionLook.stepText())
    }
    BasicText(ask.text, style = QuestionLook.questionText())
    if (!ask.answerable) {
        // Multi-select until its keys are confirmed: the options as plain text.
        for ((i, o) in ask.options.withIndex()) OptionText(i, o)
        CardButton(OPEN_TERMINAL, enabled = true, onClick = onTerminal)
        return
    }
    for ((i, o) in ask.options.withIndex()) {
        val spoken = optionSpoken(i, ask.options.size, o)
        val chosen = card.chosen == i
        val enabled = !card.busy
        val pick = { if (card.start(ChatChoice.Pick(i.toUInt()))) onAnswer(ChatChoice.Pick(i.toUInt())) }
        Column {
            Box(
                Modifier.optionSurface(chosen)
                    .clickable(enabled = enabled) { pick() }
                    .clearAndSetSemantics {
                        contentDescription = spoken
                        role = Role.Button
                        if (chosen) selected = true
                        if (!enabled) disabled()
                        onClick { pick(); true }
                    }
                    .optionPadding(),
            ) { OptionText(i, o) }
            if (o.preview != null) PreviewButtonPlace { CardButton(PREVIEW, enabled = true) { card.preview = o } }
        }
    }
    if (card.otherOpen) Other(card, onAnswer)
    CardRow {
        if (!card.otherOpen) CardButton(SOMETHING_ELSE, enabled = !card.busy) { card.otherOpen = true }
        CardButton(SKIP, enabled = !card.busy) { if (card.start(ChatChoice.Skip)) onAnswer(ChatChoice.Skip) }
    }
}

/** The own-words field: "Type your answer" and Send, enabled for 1 to 2000 characters. */
@Composable
private fun Other(card: QuestionCard, onAnswer: (ChatChoice) -> Unit) {
    val text = card.otherText
    OtherLayout(
        field = {
            OtherField(empty = text.isEmpty()) { style, cursor ->
                BasicTextField(
                    text, { card.otherText = it },
                    textStyle = style,
                    cursorBrush = cursor,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        problem = otherProblem(text),
        send = {
            CardButton(SEND, enabled = !card.busy && canSendOther(text), solid = true) {
                val choice = ChatChoice.Other(text)
                if (card.start(choice)) onAnswer(choice)
            }
        },
    )
}
