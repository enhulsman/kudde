# Proposal: answer an agent's question from the chat

## Plain summary

**Decisions that are yours** (my pick first in each; none is built before you
answer):

1. **A question call with several questions** (Claude Code asks 1 to 4 in one
   call; 42% of the about 270 calls I looked at had more than one).
   - *(pick)* One card, one question at a time ("Question 2 of 3"). A tap
     answers that question on the desk at once and moves on; the last step
     is a "Send answers" button that presses Claude Code's own Submit.
   - All questions stacked in one scrolling card, one Send at the end.
   - Several questions: terminal only.
2. **Questions where you may choose several options** (8% of calls had one).
   - *(pick)* Supported: checkboxes and a Send button. If the phone check
     shows the keys do not work as I read them, this ships as "answer in the
     terminal" and the rest still ships.
   - Terminal only from the start.
3. **"Something else" (your own words).** A third of the single-choice
   answers in the logs were free text, so this is not an edge case.
   - *(pick)* A field in the card; what you type goes into Claude Code's own
     "Type something" row on the desk.
   - Not in this change: only the listed options.
4. **Skip.**
   - *(pick)* Skip presses Esc in the dialog: Claude Code tells the agent you
     declined, and the agent carries on. It shows only while the same dialog
     is on screen.
   - No Skip button (Skip stays a terminal thing).
5. **Option previews** (some options carry a block of text to compare, 10% of
   calls).
   - *(pick)* A "Preview" button on that option opens the text in a sheet.
   - A line "Open the terminal to compare" instead; smaller to build.
6. **Codex.** Codex 0.169 has a similar tool (`request_user_input`: one to
   three short questions, options, "Other (write an answer)"), but no log on
   this machine holds a real one, so I cannot say what its record looks like.
   - *(pick)* Claude Code only now; Codex follows as its own issue once a
     real log exists.
   - Try Codex now from the binary's strings alone (I advise against).
7. **The safety window (this is why #31 was parked).** The core can send keys
   to a terminal but cannot check, at the moment it types, that the same
   dialog is still showing. The app checks right before sending (the agent
   and session are the same, the status is still blocked, the screen still
   shows this question), so what remains is the time of one round trip
   (unmeasured; I expect a few hundred ms) in which an answer at the desk
   could send the keys to whatever came next.
   - *(pick)* Accept that window, say it in the spec, and use a guard from
     herdr-core the day it offers one for keys.
   - Do not build until herdr-core has such a guard (an upstream request;
     `decisions.md` says nothing waits on upstream).
8. **The dialog was answered at the desk first.**
   - *(pick)* The card turns into a quiet line ("Answered: header") and a
     tap that was already on its way sends nothing and says "Already
     answered at the desk. Nothing was sent."
   - The card just disappears.

**Also:** #65 ("Answer permission prompts in the chat") was closed as a
duplicate of #31 and never built, so no permission-prompt code exists to reuse.
This change builds its own small path and does not answer permission prompts
(Allow/Deny stays #31, which can reuse the safety checks written here).

**What changes for you:** when Claude Code asks one of its multiple-choice
questions, the chat shows a card at the bottom: the question, the options
numbered 1 to 4 with their descriptions beneath, a "Something else" row for
your own words and a Skip button. Tapping an option answers on the desk and
the agent carries on; you no longer switch to the terminal. TalkBack reads
the question, then each option with its number and description. Anything the
card cannot do safely (a changed question, an unusual shape, an answer the
app cannot confirm) says so and offers the terminal, as today. Until then
nothing changes: the call still shows as a folded line and the message box
still refuses while the agent waits.

## Why

Issue #51: when Claude Code asks with its multiple-choice dialog
(AskUserQuestion), the chat cannot answer it, so you switch to the terminal
and pick there; found during the chat view's day of real use (#17). Today the
chat shows the call as a folded tool line that never finishes, the agent list shows the status as blocked, and sending is refused with "The agent
is waiting for you in the terminal". The author's own logs hold about 270 such calls, so it is a routine stop, not a rarity.

## What changes

- The chat parser reads a pending AskUserQuestion call's questions, options,
  descriptions, previews and multi-select flag from the log, and says which
  call is still unanswered.
- A card at the bottom of the chat shows the pending question and takes the
  answer; the keys that answer it on the desk are computed in Rust and sent
  with `terminal.keys` after a screen check.
- A free-text path pastes into the dialog's own text row; Skip sends Esc.
- The message box says "Answer the question above first" while a card shows.
- TalkBack reads the card in order (accessibility delta).
- No change to the log reading, the terminal view, the list's cards or the
  Changes view. No new permission, no new herdr-core call (`terminal.keys`,
  `terminal.read` and `terminal.get` are in the supported range already).

## Out of scope

Codex questions (decision 6); permission prompts (#31); notes on an answer
(Claude Code's "n to add notes"); the "Chat about this" row; answering a
question that appeared before the chat opened more than the log's tail
holds; a question opened by a subagent (its log is a separate file the chat
does not follow).

## Impact

Specs: `chat-view` (added requirements), `accessibility` (one added).
Code: `crates/kudde-chat` (question parse, pending state, key plan, screen
match), `crates/kudde-mobile/src/hub/chat.rs` (answer path), Kotlin
`ui/chat` (card, sheet, states). Docs: `docs/phone-testing.md` gains the
staged-question check. Issue: closes #51.
