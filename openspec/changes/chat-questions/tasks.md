## 1. Facts to settle first
- [x] 1.1 **[owner]** Answer the eight decisions in `proposal.md`; record them in `decisions.md`. (Answered 2026-11-21, all as picked.)
- [x] 1.2 **[owner]** Sign off the texts table in the chat-view delta (they are public UI text). (Approved 2026-11-24 word for word, with the spoken wording in the accessibility delta.)
- [x] 1.3 Search the issues (open and closed) for overlaps before filing follow-ups (Codex questions, permission prompts stay #31). (2026-11-24: no issue covers Codex questions or a keys guard in herdr-core, so both follow-ups in 6.2 are new; #31 keeps permission prompts and #65 is its closed duplicate; #71 asks herdr-core for a send guard for finished agents, the same kind of upstream guard, and a keys guard could be asked for beside it.)
- [ ] 1.4 Move #51 to In Progress when work starts.

## 2. Tests first (Rust, `crates/kudde-chat`)
Fixtures are invented JSONL with the shapes in `design.md` section A.
- [x] 2.1 Parse: single, multi-question (4), multi-select, option preview, unknown option fields, label at 71 and description at 698 characters, no questions, option without a label, more than four questions.
- [x] 2.2 Pending state: a call with no result is pending; a result closes it; `is_error` marks it declined; a result text in either known wording is not parsed; a call with no result at the end of the log stays pending.
- [x] 2.3 Key plan (pure): option N gives digit N and Enter; the free row; Skip is Esc; Send answers is Enter; multi-select shape; never an arrow; never a digit above 9.
- [x] 2.4 Screen match: the question text found through box characters and folded spaces; a different question not found; an option label missing; a wrapped question; two similar questions in one call.
- [x] 2.5 Progress read from the screen: next question, review step, gone.

## 3. Build (Rust)
- [x] 3.1 Expose the pending question and its state through the chat's bridge; reuse the send target.
- [x] 3.2 Hub tests with the fake core, written first: checks in order, no keys on status or screen mismatch, one `terminal.keys` call per step, no repeat after an unknown outcome, free text through `terminal.send` with force, Skip sends nothing when a check fails, a tap racing the end of the card sends nothing.
- [x] 3.3 The answer path in `hub/chat.rs`: `terminal.get`, `terminal.read`, `terminal.keys`, re-read.
- [x] 3.4 Run clippy, fmt and the crate suites as CI does.

## 4. Android (tests first in each)
- [x] 4.1 State: card, step, chosen, disabled-while-sending, refusal texts; the message-box refusal.
- [x] 4.2 Compose card, free-text field, preview sheet, collapsed line; Robolectric tests for every scenario.
- [x] 4.3 TalkBack tree test (order, announcements once, no focus steal) in Robolectric; the owner listens at 5.2.
- [x] 4.4 Roborazzi golden of the card at two font scales and the long description (still flags only, no looping animation).
- [x] 4.5 Check the card against `design/` and `design-tokens.json`; no new colours. (No render of this card exists in `design/`. 2026-11-24: the owner rejected the placeholder look (the needs-you stripe); a redesign comes first. The look is all in `ChatQuestionLook.kt`. 2026-11-25: replaced by direction A (owner's pick), with the message box hidden while a card waits; goldens of 4.4 recorded again, plus multi-select and the own-words field. 2026-11-25: signed off by the owner from the round-6 goldens; renders in `design/checkpoints/question-*.png`.)

## 5. Review and phone check
- [ ] 5.1 [phone] Stage a real Claude Code in plan mode on the test phone's desk (title through /rename, crop the status bar) and have it ask: one single-choice question; one with a preview; three questions; one multi-select; one answered by free text; one skipped. Record per case: the keys as the screen shows them, any extra delay from the quiet rule, and the result text. Update the "low" rows in `design.md` section B.
- [ ] 5.2 **[owner]** Listen to the card with TalkBack on the phone and the test phone's second profile left alone (install `--user 0`).
- [ ] 5.3 [phone] Answer at the desk while the card shows; tap while the desk answers; confirm "Already answered at the desk" and that no key reached the next prompt.
- [ ] 5.4 Run `/codex:adversarial-review` against main (it sends input to a terminal) and handle what it finds.
- [ ] 5.5 Update `docs/phone-testing.md` with the staged-question check.

## 6. Records
- [ ] 6.1 `openspec validate chat-questions --strict`, then archive on the feature branch as the last content commit.
- [ ] 6.2 Issue text for any follow-up (Codex questions, a keys guard in herdr-core) in public wording.
- [ ] 6.3 Closes #51 in the PR.
