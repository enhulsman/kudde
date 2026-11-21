## 1. Facts to settle first
- [ ] 1.1 **[owner]** Answer the eight decisions in `proposal.md`; record them in `decisions.md`.
- [ ] 1.2 **[owner]** Sign off the texts table in the chat-view delta (they are public UI text).
- [ ] 1.3 Search the issues (open and closed) for overlaps before filing follow-ups (Codex questions, permission prompts stay #31).
- [ ] 1.4 Move #51 to In Progress when work starts.

## 2. Tests first (Rust, `crates/kudde-chat`)
Fixtures are invented JSONL with the shapes in `design.md` section A.
- [ ] 2.1 Parse: single, multi-question (4), multi-select, option preview, unknown option fields, label at 71 and description at 698 characters, no questions, option without a label, more than four questions.
- [ ] 2.2 Pending state: a call with no result is pending; a result closes it; `is_error` marks it declined; a result text in either known wording is not parsed; a call with no result at the end of the log stays pending.
- [ ] 2.3 Key plan (pure): option N gives digit N and Enter; the free row; Skip is Esc; Send answers is Enter; multi-select shape; never an arrow; never a digit above 9.
- [ ] 2.4 Screen match: the question text found through box characters and folded spaces; a different question not found; an option label missing; a wrapped question; two similar questions in one call.
- [ ] 2.5 Progress read from the screen: next question, review step, gone.

## 3. Build (Rust)
- [ ] 3.1 Expose the pending question and its state through the chat's bridge; reuse the send target.
- [ ] 3.2 Hub tests with the fake core, written first: checks in order, no keys on status or screen mismatch, one `terminal.keys` call per step, no repeat after an unknown outcome, free text through `terminal.send` with force, Skip sends nothing when a check fails, a tap racing the end of the card sends nothing.
- [ ] 3.3 The answer path in `hub/chat.rs`: `terminal.get`, `terminal.read`, `terminal.keys`, re-read.
- [ ] 3.4 Run clippy, fmt and the crate suites as CI does.

## 4. Android (tests first in each)
- [ ] 4.1 State: card, step, chosen, disabled-while-sending, refusal texts; the message-box refusal.
- [ ] 4.2 Compose card, free-text field, preview sheet, collapsed line; Robolectric tests for every scenario.
- [ ] 4.3 TalkBack tree test (order, announcements once, no focus steal) in Robolectric; the owner listens at 5.2.
- [ ] 4.4 Roborazzi golden of the card at two font scales and the long description (still flags only, no looping animation).
- [ ] 4.5 Check the card against `design/` and `design-tokens.json`; no new colours.

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
