## 1. Facts to settle first
- [x] 1.1 **[owner]** Answer the eight decisions in `proposal.md`; record them in `decisions.md`. (Answered 2026-11-21, all as picked.)
- [x] 1.2 **[owner]** Sign off the texts table in the chat-view delta (they are public UI text). (Approved 2026-11-24 word for word, with the spoken wording in the accessibility delta.)
- [x] 1.3 Search the issues (open and closed) for overlaps before filing follow-ups (Codex questions, permission prompts stay #31). (2026-11-24: no issue covers Codex questions or a keys guard in herdr-core, so both follow-ups in 6.2 are new; #31 keeps permission prompts and #65 is its closed duplicate; #71 asks herdr-core for a send guard for finished agents, the same kind of upstream guard, and a keys guard could be asked for beside it.)
- [ ] 1.4 Move #51 to In Progress when work starts.

## 2. Tests first (Rust, `crates/kudde-chat`)
Fixtures are invented JSONL with the shapes in `design.md` section A.
- [x] 2.1 Parse: single, multi-question (4), multi-select, option preview, unknown option fields, label at 71 and description at 698 characters, no questions, option without a label, more than four questions.
- [x] 2.2 Pending state: a call with no result is pending; a result closes it; `is_error` marks it declined; a result text in either known wording is not parsed; a call with no result at the end of the log stays pending.
- [x] 2.3 Key plan (pure): option N gives digit N alone without previews and digit N and Enter with previews; the free row; Skip is Esc; Send answers is Enter; multi-select in checked steps (digits alone, the text, an Enter while its row shows unticked, Tab or Tab and Enter, Enter on a one-question review); never an arrow; never a digit above 9. (2026-11-25: corrected from the 5.1 desk round; regression test for the first of three questions answering the second.)
- [x] 2.4 Screen match: the question text found through box characters and folded spaces; a different question not found; an option label missing; a wrapped question; two similar questions in one call.
- [x] 2.5 Progress read from the screen: next question, review step, gone.

## 3. Build (Rust)
- [x] 3.1 Expose the pending question and its state through the chat's bridge; reuse the send target.
- [x] 3.2 Hub tests with the fake core, written first: checks in order, no keys on status or screen mismatch, one `terminal.keys` call per step, no repeat after an unknown outcome, free text through `terminal.send` with force, Skip sends nothing when a check fails, a tap racing the end of the card sends nothing.
- [x] 3.3 The answer path in `hub/chat.rs`: `terminal.get`, `terminal.read`, `terminal.keys`, re-read.
- [x] 3.4 Run clippy, fmt and the crate suites as CI does.
- [x] 3.5 The question hook (owner, 2026-11-25; design D1, section E): the hook script in `contrib/claude-code/`, its reader script, and the record parser (session, tool, id, the call's shape), tests first; a shell test of the script.
- [x] 3.6 The card from the record: hub tests with the fake core first (record plus blocked plus the screen gives a card; no record, a stale record, another session or a screen that disagrees gives "Open the terminal"; a permission prompt gives none); answers use the held record.
- [x] 3.7 Notes on a preview option (D9): key plan and hub steps with screen checks (digit, `n`, the note with force), tests first.
- [x] 3.8 `docs/claude-code-question-hook.md`: the manual install, in neutral wording.

## 4. Android (tests first in each)
- [x] 4.1 State: card, step, chosen, disabled-while-sending, refusal texts; the message-box refusal.
- [x] 4.2 Compose card, free-text field, preview sheet, collapsed line; Robolectric tests for every scenario.
- [x] 4.3 TalkBack tree test (order, announcements once, no focus steal) in Robolectric; the owner listens at 5.2.
- [x] 4.4 Roborazzi golden of the card at two font scales and the long description (still flags only, no looping animation).
- [x] 4.5 Check the card against `design/` and `design-tokens.json`; no new colours. (No render of this card exists in `design/`. 2026-11-24: the owner rejected the placeholder look (the needs-you stripe); a redesign comes first. The look is all in `ChatQuestionLook.kt`. 2026-11-25: replaced by direction A (owner's pick), with the message box hidden while a card waits; goldens of 4.4 recorded again, plus multi-select and the own-words field. 2026-11-25: signed off by the owner from the round-6 goldens; renders in `design/checkpoints/question-*.png`.)
- [x] 4.6 The preview sheet (look A, owner 2026-11-25): unwrapped sample scrolling sideways, "Note (optional)", "Choose this"; no "Something else" on a question with previews; Robolectric tests, then goldens in paper and graphite (short, long, note, large text).
- [x] 4.7 After the phone round (coordinator, 2026-11-25): no keyboard while an answer is on its way (the multi-select Send and Choose this included), and no "AskUserQuestion · running" line while its card shows; Robolectric tests first.
- [x] 4.8 Owner, 2026-11-25: the review-step and no-hook body texts (approved verbatim), the no-hook flag from the reader's "missing" only, and multi-select switched on; Rust and Robolectric tests first, goldens `review` and `no-hook`.

## 5. Review and phone check
- [x] 5.1 [phone] Stage a real Claude Code in plan mode on the test phone's desk (title through /rename, crop the status bar) and have it ask: one single-choice question; one with a preview; three questions; one multi-select; one answered by free text; one skipped. Record per case: the keys as the screen shows them, any extra delay from the quiet rule, and the result text. Update the "low" rows in `design.md` section B. (2026-11-25: all six cases passed with the hook as the source; the dialog-only screen check was fixed during the round; results in `design.md` section B.)
- [x] 5.2 **[owner]** Listen to the card with TalkBack on the phone and the test phone's second profile left alone (install `--user 0`). (2026-11-25: passed; the desk got Noodle bar, and Coffee and Cake. Feedback: no way back to an earlier question, and the review step does not show the answers: group 7.)
- [x] 5.3 [phone] Answer at the desk while the card shows; tap while the desk answers; confirm "Already answered at the desk" and that no key reached the next prompt. (2026-11-25: the desk answer collapsed the card; in two races (tap 0.2 s after the desk) nothing reached the next prompt, its dialog untouched and the input empty. "Already answered at the desk" did not show: the card ended with the same poll; a question for the owner.)
- [x] 5.4 Run `/codex:adversarial-review` against main (it sends input to a terminal) and handle what it finds. (2026-11-25: seven findings; six fixed tests first, the window is a herdr-core follow-up; `design.md`, "The adversarial review".)
- [x] 5.5 Update `docs/phone-testing.md` with the staged-question check.

## 7. The phone keeps the answers until Send (Ezra, 2026-11-25; design D11)
- [x] 7.1 Spec deltas, design D11, the proposal's decision 1 and `decisions.md`.
- [x] 7.2 Core, tests first: `send_answers` (call id, answers in order, `from`): each question after its checks and its screen, the dialog's review step checked against the typed answers before Enter; `Stopped { sent }` halfway; a resume only from question `from + 1`; one single-choice question still answers at once.
- [x] 7.3 Android, tests first: the card keeps answers per question, previous and next, the review list with a change control per answer, Send answers, the halfway notice, Skip and the preview sheet unchanged; TalkBack (announced once, no focus steal, the list readable); goldens.
- [x] 7.4 The texts marked draft go to the owner verbatim before they replace the placeholders. (2026-11-25: all approved word for word.)
- [x] 7.5 **[owner]** Ten minutes with the debug build before the review and the phone round. (2026-11-25: three findings, 7.7.)
- [x] 7.7 After Ezra's try, tests first: the "Next" button row of a multi-select question before another one, and a resume of a question typed in part; free forward with "No answer yet" on the review step and Send answers waiting for every answer; a step lands the focus on what it shows, at once, and is no longer announced.
- [x] 7.8 The texts marked draft from 7.7 go to the owner verbatim. (2026-11-25: approved word for word.)
- [x] 7.9 Ezra's second try, tests first: after the last question the review step comes, gaps and all, never an earlier question. Desk finding for the owner's Skip question: Claude Code's review accepts Submit with a question unanswered ("⚠ Some questions are unanswered"; the result leaves it out).
- [x] 7.10 **[owner]** Skip on a question step, and Send with gaps. (2026-11-25, Ezra: "Skip question" moves on without an answer, "Skip all" on the review step, Send with gaps through Tab; texts approved word for word.)
- [x] 7.11 Tests first: `Answer::Pass` as Tab, the gap-aware review check (warning line, only the answered questions, none of another call's), all-skipped sends nothing; on the phone "Skip question", "Skip all", Send answers with one answer, TalkBack stops; goldens.
- [x] 7.12 Ezra's third try, tests first: sending faster (the send's held-back reply, shorter looks; measured before and after on the desk), the folded line without skipped questions, the focus on the folded line after Send.
- [x] 7.6 A second `/codex:adversarial-review` against main, then a phone round on the test phone. (2026-11-25: review in design "The second adversarial review"; phone round with r16 passed, design "The phone round after the second review".)

## 6. Records
- [x] 6.1 `openspec validate chat-questions --strict`, then archive on the feature branch as the last content commit.
- [ ] 6.2 Issue text for any follow-up (Codex questions, a keys guard in herdr-core) in public wording.
- [ ] 6.3 Closes #51 in the PR.
