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

Still for the phone round: the same keys sent by the app through its card,
the delay the "quiet" rule adds, and the result texts.

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

## Decisions

**D1. Detection needs three witnesses.** A card shows only when (1) the log
holds an `AskUserQuestion` tool_use with no tool_result yet, (2) the
terminal's status is `blocked`, and (3) the chat's send target (agent, pid,
process start, session) is unchanged. The log is the content source and the
status the "still open" source; neither alone is enough (a log without a
result also describes a crashed session; blocked alone says nothing about
which dialog). A pending call with status not blocked shows no card (the
folded line stays).

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
questions with descriptions up to 700 characters. Going back is the desk's
(tab left); the card shows only the question the screen shows.

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

**D9. Previews reuse the code box.** A preview string shows in a sheet in the
chat's code-box style, wrapped, never scrolling sideways; the first line of a
preview is not parsed.

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
  input to a terminal the person cannot see).
- **The quiet rule.** `terminal.keys` waits "until nobody has typed for ~3 s".
  Someone typing at the desk delays the keys by up to 3 s after the screen
  check: a second look at the screen after the delay is not possible through
  one call. Unmeasured; the phone check records it. If it is large, step
  keys go through the attached terminal channel (the keys row's path)
  instead, with the same checks.
- **Rebound keys or a changed dialog** in a later Claude Code: D4 notices
  and stops; a compatibility note joins the supported-version list.
- **Free-text paste and multi-select** are low confidence (section B).
- **Long text**: a 700-character description wraps; a card taller than the
  screen scrolls inside its own area, with the options reachable.

## Test and phone strategy

Fixtures are invented JSONL with the shapes above (single, multi-question,
multi-select, preview, extra field, free-text result, declined result, no
result). Rust unit tests cover parse, pending state, the plan and the screen
match; hub tests cover the checks and refusals with the fake core; Robolectric
covers the card and its TalkBack tree; a Roborazzi golden covers the card at
two font scales. The key sequences are confirmed on the phone with a staged
real Claude Code in plan mode (a question is asked on request), because the
fake agent draws no dialog.
