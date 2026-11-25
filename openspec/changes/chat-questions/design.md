# Design: answer an agent's question from the chat

## Context

The chat reads the agent's session log (`crates/kudde-chat`), sends with
`terminal.send` after checks against the agent process and session
(`send.rs`, `hub/chat.rs`), and refuses while the agent is blocked. Issue #65
(permission prompts in the chat) was closed as a duplicate of #31 and never
built; #31 is parked because a key send cannot check that the same prompt is
still showing. So nothing exists to reuse for dialogs; what exists and is
reused: the log tail and parser, the send target (agent, pid, process start,
session), `terminal.get` and `terminal.read`, the Waiting refusal, the Needs
you card's accessibility pattern, the tool sheet and code box.

## Facts and sources

All from the author's own machine; nothing real is copied here, fixtures are
invented in the same shape. Confidence: **high** = seen in logs or code,
**moderate** = read from the shipped program but not run, **low** = inferred.

### A. The record (high)

Claude Code (2.1.3xx) writes the call as a `tool_use` named `AskUserQuestion`
and the answer as a `tool_result` with the same `tool_use_id`. Of about 270
calls found in session logs:

```json
{"type":"tool_use","id":"toolu_01","name":"AskUserQuestion","input":{"questions":[
  {"question":"Which database should the todo app use?","header":"Database",
   "multiSelect":false,
   "options":[{"label":"SQLite (Recommended)","description":"One file, no server."},
              {"label":"Postgres","description":"Needs a running server.","preview":"..."}]}]}}
```

- `input.questions[]`: 1 to 4 per call (42% had more than one); each has
  `question`, `header` (up to 17 characters seen), `multiSelect` and
  `options[]` of 2 to 4 (`label`, `description`, and `preview` as a string,
  seen on 10% of calls). Two calls carried extra option fields
  (`options_note`, `description_detail`): the parser ignores unknown fields.
  Seen lengths: label to 71 characters, description to 698, question to 599,
  so nothing may be truncated.
- 353 labels carry the text "(Recommended)" inside the label itself; there is
  no separate flag. The card shows labels verbatim.
- Result, one `tool_result` whose text differs by version: "Your questions
  have been answered: "Q"="A"..." or "The user answered: "Q"="A"... Read the
  answers carefully...". The structured `toolUseResult` beside it holds
  `answers` (question text to answer; an array for multi-select in some
  versions, a joined string in others) and optional `annotations` (`preview`,
  `notes`). The app SHALL NOT parse the result text: a call is answered when
  a `tool_result` for its id exists.
- Free text: a single-choice answer that is not one of the labels. 149 of 463
  single answers were (32%).
- Declined: a `tool_result` with `is_error: true` and the text "The user
  doesn't want to proceed with this tool use. The tool use was rejected ...
  the user said:". Both such results seen had typed feedback after it; a
  bare Esc was not among the logs (low: its text is assumed the same).
- One call in 266 had no result (open, or the session ended).

### B. How the terminal dialog is answered (from the program's own code, then the desk)

First read from the installed 2.1.330 binary's JavaScript (not run). Then,
on 2026-11-25 (task 5.1, before the phone), each row was tried on the desk
against a throwaway Claude Code 2.1.331 with `herdr-core keys` and `send
--force`, the same calls the app makes. Defaults only: the dialog's actions
are named keybindings, which a user can rebind in a keybindings file.

| Claim | Confidence |
|---|---|
| Each option row is drawn "N." with its label | high (desk) |
| Single choice without previews: digit N answers option N at once and moves on (next question, the review step, or the end of the call); an Enter after it answers the next question's focused option (seen: `2` `enter` on the first of three questions also set the second to its option 1) | high (desk) |
| Single choice with previews: the options sit beside the preview, a digit only moves the focus, and Enter answers; there is no "Type something" row, and "Chat about this" has no number | high (desk) |
| The free-text row ("Type something.") is n+1; its digit opens it for typing, and a paste with Enter answers with that text | high (desk) |
| Esc declines the call ("Declined to answer"), and the agent's turn ends | high (desk) |
| A "Submit answers" / "Cancel" review step follows the last question of a call with several questions, and the only question of a call with a multi-select one; Enter (or 1) on it sends | high (desk) |
| Multi-select: digit N toggles row N in place and the focus stays; Enter toggles the focused row; rows read "N. [ ]" and "N. [✔]" | high (desk) |
| Multi-select: "Type something" is row n+1 with a box; "Submit" below it has no number; n+2 is "Chat about this" | high (desk) |
| Multi-select with own words: a paste with Enter moves the focus to row n+1, puts the text in it and leaves it unticked (twice); one more Enter ticks it; the row stays in its editor | high (desk, two runs) |
| Multi-select, leaving the question: Tab outside the editor goes to the next question or the review step; inside the editor Tab moves to Submit, and Enter there does the same | high (desk) |
| Left/right move between questions when no editor is open; inside the editor they move the text cursor | high (desk) |
| The dialog may close by itself after an idle timeout (code names `CLAUDE_AFK_TIMEOUT_MS`, with a countdown) | low (default not found) |
| Focus starts on the previously chosen option when a question is revisited, so a plan must not count arrow presses from the top | moderate |

Phone round (2026-11-25, task 5.1, Pixel 7 Pro, Claude Code 2.1.332, the
question read from the hook): the same keys sent by the app through its card
matched every row above. Tap to answer, end to end: single choice about 3 s;
preview with a note about 1 s (the note lands in the call's annotations);
three questions step by step, then the review step and Send answers;
multi-select with two ticks and own words about 7 s ("Apple, Mango, Berries");
free text about 1 s; Skip gives "Skipped: Theme". No desk typing happened, so
the quiet rule added nothing beyond those times. Found and fixed there: the
screen check read the chat above the dialog, which could match an earlier
question; it now reads the dialog only.

### C. What the chat knows today (high, from the code)

herdr-core reports `blocked` for the terminal, with `kind` and `msg` when the
program's own status says so (the list's Needs you card shows
"Question: <msg>" from a fixture; whether the real Claude Code hook sends
`kind: question` was not seen, so the card here does not rely on it). The
chat shows the call as a folded tools line with the input JSON as its title,
never done; `Refusal::Waiting` blocks sending with "The agent is waiting for
you in the terminal"; no working line shows while blocked.

### D. Codex (moderate)

Codex 0.169's binary holds `request_user_input` ("one to three short
questions", options, "Other (write an answer)", "None of the above", notes
with Tab) and a TUI view for it. No rollout log on this machine holds a call
(one `request_user_input_async` call had a different shape: title and string
options). So its record and keys are unknown: an owner question, out for now.

### E. Where a waiting question can be read (desk, 2026-11-25)

Throwaway Claude Code 2.1.331 agents in plan mode, each with its own
`--settings` file; the user's settings untouched.

- Without a hook the log holds nothing of a waiting call: 1.5 minutes in
  one case, 20 s in another, not even the turn's thinking line. After the
  answer the call line and its result arrive together. (high)
- A PreToolUse hook matched on `AskUserQuestion` gets `tool_input` whole (all
  questions, options, descriptions, previews, `multiSelect`), with
  `tool_use_id`, `session_id`, `transcript_path` and `cwd`. (high)
- The hook runs and ends before the dialog is drawn: with `sleep 3` in it,
  the dialog came 3.03 s after the hook started. (high)
- With the matched hook the call line also reaches the log, about 0.1 s
  after the dialog (two runs). The app does not rely on that side effect.
  (moderate)
- Notes on a preview question: `n` opens "Notes: Add notes on this design…"
  for the focused option; a paste with Enter saves the note and answers.
  (high, one run)

## Decisions

**D1. The question comes from a hook; the screen confirms it.** (Owner,
2026-11-25, replacing "the log is the content source".) Claude Code 2.1.331
writes the `AskUserQuestion` call to the session log only together with its
result: on the desk the log ended at the prompt for 1.5 minutes while the
dialog waited, and the call line (carrying the asking time) and its result
were appended in one go after the answer (section E). So the log cannot show
a waiting question. A PreToolUse hook matched on `AskUserQuestion` gets the
whole call before the dialog is drawn (section E). The hook script
(`contrib/claude-code/kudde-question-hook.sh`) saves its input as the
session's record on the desk; the chat reads it with `machine.exec`. A card
can answer only when (1) the record names this session and a call with no
result in the log, (2) the terminal's status is `blocked` (no status kind, or
the kind `question`), (3) the chat's send target (agent, pid, process start,
session) is unchanged, and (4) the screen shows the record's dialog (its
question text and every option label, folded); the step is the one the
screen shows. When the record is missing, stale or disagrees with the screen
while a question waits (the status kind `question`, or a call in the log
with no result), the card shows what it knows of the question with "Open the
terminal" and sends nothing. The log stays the source of completed calls
(the quiet line) and of the "answered" check. Installing the hook is a
manual step in this change (docs/claude-code-question-hook.md); a settings
button that adds it is a separate change.

**D2. Sending is check, then one `terminal.keys` call.** Pressing a choice
does, in order: `terminal.get` (same target, still `blocked`), `terminal.read`
(the screen shows the call's question text, whitespace and box characters
folded, and at least the first label of each option), then one
`terminal.keys` with the whole key list for that step. The core offers no
status guard for keys (`terminal.send` has `if`, only for idle or working),
so the window between the read and the keys is real; decision 7 in the
proposal puts it to the owner. An unconfirmed keys call (no reply, lost
connection) is treated like an unconfirmed send: the card says so and does
not repeat the keys.

**D3. The key plan is a pure function in Rust**, tested against fixtures
(keys from the 5.1 desk round, section B). Single choice option i is
`[digit(i+1)]` alone when no option has a preview (the digit answers; an
Enter after it would answer the next question), and `[digit(i+1), enter]`
with previews (the digit only focuses). "Something else" is `[digit(n+1)]`,
then the text goes through `terminal.send` with `force: true` (the core
refuses a blocked program otherwise; the text lands in the focused row), and
Enter is part of that send; Skip is `[esc]`; the last step's Send answers is
`[enter]` after the screen shows "Submit answers". Multi-select (owner,
2026-11-25: a typed answer joins the ticks and goes out with the one send)
runs in steps with a screen check before each: the ticks as digits alone in
one `terminal.keys` (a digit toggles; an Enter would untoggle); the screen
shows them; with own words, the text through `terminal.send` with `force`,
then, once the screen shows the text in row n+1, an Enter if that row shows
unticked; the screen shows exactly the planned ticks; `[tab]` (or `[tab,
enter]` from the free row's editor) leaves the question; in a call of one
question the screen then shows the review step and `[enter]` sends it.
Digits above 9 are never planned (options are at most 4 plus the free row),
no digit ever names the free row or "Chat about this" on a multi-select
question, and the plan never counts arrows from the top.

**D4. The screen decides progress, not the app's memory.** After each step
the app reads the screen again: the dialog is on the next question (tab
mark), on the Submit step, or gone (and the log then shows the result). If
the screen matches none, the card says "Check the terminal" (no guessing, no
repeat). This keeps a step answered at the desk, or a rebound key, from
sending more keys.

**D5. One card, stepped.** Several questions show "Question 2 of 3" and one
question's options at a time, because a phone screen cannot hold four
questions with descriptions up to 700 characters. (2026-11-25, D11: the
steps are the phone's own; going back is on the phone.)

**D6. Free text is the dialog's own row.** The text is pasted into the focused
free-text row, not sent as a chat message: a chat message would be
refused while blocked, and Claude Code treats a typed answer as the
question's answer, not a new turn. Limit: 2000 characters, the field says
when it is over; the core's "quiet ~3 s" rule applies (see risks).

**D7. Skip is Esc, checked first.** Esc outside the dialog interrupts the
running agent, so Skip passes the same two checks as an answer and nothing
else; with no match it sends nothing.

**D8. The card ends from the log, not from a timer.** It ends when the
tool_result for its call arrives (answered, or declined with is_error), when
the status leaves blocked, or when the target changes. It then collapses to a
quiet line, "Answered: <header>" or "Skipped: <header>", opening the existing
tool sheet. The app does not display what was answered from its own memory.

**D9. Previews open a sheet with a note (owner, 2026-11-25, look A).** The
"Preview" pill opens a sheet titled "Preview: <label>": the sample full width
in the code font, its lines never wrapped (ASCII layouts break when wrapped;
a wide sample scrolls sideways), and at the bottom a "Note (optional)" field
and "Choose this". Choose this answers that option; with a note the keys are
the option's digit (with previews a digit only moves the focus), `n` once
the screen shows that option focused, then the note through `terminal.send`
with `force` once the screen shows the notes field (its Enter saves the note
and answers). Claude Code returns the note as `annotations[<question>].notes`
beside the chosen preview. A question with previews has no free-text row in
the dialog, so the card does not offer "Something else" there. On a
multi-select question the sheet shows the sample only.

**D11. The phone keeps the answers until Send (owner, 2026-11-25, after the
TalkBack listen; replaces "a tap answers that question on the desk at once"
from decision 1).** In a call with several questions or a multi-select
question (the calls whose desk dialog has a review step) the card keeps each
answer on the phone: Question N of M with previous and next controls, a
review step listing every answer with a control back to its question, and
Send answers, which types the answers in order and then presses the dialog's
own Submit. A call of one single-choice question has no review step on the
desk and still answers at the tap. The desk dialog stays on its first
question until Send (as in Anthropic's own app). Reviews behind it: reports
058 (Codex and Fable), both for reworking inside #51.

How Send types (the core, `send_answers`): the phone passes the call id, the
answers in order and how many of them were already typed (`from`, 0 at
first). For each question from `from` on: the target check (same agent,
process, session, still blocked), the screen shows that question (ordered
numbered rows), then that question's keys exactly as one answer at the tap
sends them (D3, D6, D9), and the screen moving on. Then, where the dialog
has a review step, the screen must show it listing every question with the
answer typed (the review shows the label, the typed text, or the ticked
labels and typed text joined with ", "; a note is not listed, so its option's
label is checked), with "1. Submit answers" in focus; then Enter and the
result in the log. Every screen is read again before each step; nothing is
sent twice.

Stopping halfway: when a step's check fails or cannot be confirmed after k
answers were typed, the core returns `Stopped { sent: k }` and sends nothing
more. The card keeps the answers and says "Typed k of n answers, then
stopped. Check the terminal." (draft) with the terminal button. Send answers
again calls `send_answers` with `from = k`; the core goes on only if the
screen shows question k+1 (or the review step when k = n), else "The
question changed. Nothing was sent." So a retry never retypes an answer the
dialog already holds, and an answer changed on the phone after the stop is
not sent for a question already typed: the dialog's review step then lists
another answer and Enter is not sent. With k = 0 the card shows the plain
refusal it would show for one tap. Why not resume from wherever the screen
is: the phone cannot tell whose answers the dialog holds for questions it
did not type itself; the review check covers what it did type.

The card's steps no longer follow the screen. The poll's step stays the
dialog's (for Send's checks and the hub's kept step); the card resets only
on a new call. A desk user answering on the desk while the phone holds
answers makes Send refuse (the dialog is not on the first question).

**D10. Everything else is refused to the terminal.** An unknown shape
(no questions, more than 4, an option without a label, more than 4 options
without the free row), a version where the screen check never matches, or
Codex: the card shows the question as read-only text and "Open the terminal".

## Risks and open items

- **The window (decision 7).** One round trip between read and keys. A
  worse variant: the desk user answers and a new prompt of another kind opens
  inside the window; digit then Enter would answer it. Mitigation: digits
  rather than arrows (a digit typed into an empty input box is only text),
  but an Enter submits it. Confidence the residual harm is small: moderate;
  it should go through the adversarial Codex review (security class: sends
  input to a terminal the person cannot see). The review (task 5.4,
  2026-11-25) rated it high: with the quiet rule's wait, a desk user who
  closes the dialog meanwhile can have a queued Enter, Esc or pasted text
  reach whatever opens next. Not closable in this change: the guard belongs
  in herdr-core (check the expected screen right before it writes, after any
  wait), a follow-up issue. The phone round 5.3 raced a desk answer twice
  and no key reached the next prompt.
- **The quiet rule.** `terminal.keys` waits "until nobody has typed for ~3 s".
  Someone typing at the desk delays the keys by up to 3 s after the screen
  check: a second look at the screen after the delay is not possible through
  one call. Unmeasured; the phone check records it. If it is large, step
  keys go through the attached terminal channel (the keys row's path)
  instead, with the same checks.
- **Rebound keys or a changed dialog** in a later Claude Code: D4 notices
  and stops; a compatibility note joins the supported-version list.
- **Free-text paste and multi-select** were confirmed on the desk and the phone (section B); the owner lifted the multi-select gate on 2026-11-25 (the switch stays, off as a way back).
- **Long text**: a 700-character description wraps; a card taller than the
  screen scrolls inside its own area, with the options reachable.

### The adversarial review (task 5.4, 2026-11-25)

Codex, read-only, against main. Its findings and what was done:

- The window above (high): follow-up in herdr-core; recorded as a risk.
- Review detected from any "Submit answers" (high): fixed. The review step
  is read as its own screen: "Review your answers" after the header, every
  question of the call listed, "1. Submit answers" in focus (captured on the
  desk, 2.1.332).
- A replaced record authorising an old card's index (high): the screen match
  now needs each option as its numbered row in the call's order, so a new
  call with the same labels reordered is not the old card's. The record is
  not read again at the tap: with the ordered match, a call that passes
  shows the same question with the same rows the card shows (moderate).
- Text and Enter without the focus seen (high): fixed. The free-text paste
  waits for its row in focus; from the multi-select editor, Tab, then Enter
  once Submit shows in focus (both captured on the desk). The note path
  already waits for its option in focus before `n`.
- An old Android frame answering the new step (high): fixed. A tap carries
  the call and step it was made on; a result for a step the card has left
  changes nothing.
- A retry undoing ticks (medium): fixed. The digits are the rows whose tick
  the screen shows otherwise.
- The hook following links (medium): fixed. The hook writes only into a
  directory of the user's own that nobody else may write, through a
  `mktemp` file; the reader refuses a linked or foreign record ("unsafe",
  which is not a missing hook).

## Test and phone strategy

Fixtures are invented JSONL with the shapes above (single, multi-question,
multi-select, preview, extra field, free-text result, declined result, no
result). Rust unit tests cover parse, pending state, the plan and the screen
match; hub tests cover the checks and refusals with the fake core; Robolectric
covers the card and its TalkBack tree; a Roborazzi golden covers the card at
two font scales. The key sequences are confirmed on the phone with a staged
real Claude Code in plan mode (a question is asked on request), because the
fake agent draws no dialog.
