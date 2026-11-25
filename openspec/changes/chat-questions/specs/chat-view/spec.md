## ADDED Requirements

### Requirement: A pending question shows as a card
The chat SHALL show an answerable card at the bottom only while all of these
hold: the desk holds the question hook's record (design D1) for the session
shown, naming an `AskUserQuestion` call that has no result in the log; the
terminal's status is `blocked` (with no status kind, or the kind
`question`); the chat's send target (agent, process, process start, session)
is unchanged; and the terminal's screen shows the record's dialog (its
question text and every option as its numbered row in the call's order,
box-drawing characters and runs of spaces folded), read from the dialog's last header line (☐ or ☒) down and
never from the chat above it, which may hold an earlier question. The step the card answers is the one the screen shows. When
the record is missing, stale (another session, or its call already has a
result in the log) or the screen does not show its dialog, while the status
is `blocked` with the kind `question` or the log holds a call with no result,
the card SHALL show the question text it knows (none when it knows none)
with "Open the terminal" and no choices, and SHALL send nothing; when the
desk holds no record at all for the session, it SHALL also say how to answer
from the phone (the hook text in the table). A status of
another kind (a permission prompt) SHALL show no card. The log SHALL stay
the source of completed calls. The card SHALL show the question's text, its
options numbered from 1 in the call's order, each with its label and,
beneath it, its description, "Something else" (not on a question whose
options have previews, where the dialog has no free-text row) and a "Skip"
button. Labels, descriptions and questions SHALL be shown whole and wrapped,
never truncated; a card taller than the screen SHALL scroll inside its own
area. With no result yet but a status that is not `blocked`, no card SHALL
show. Unknown fields in the call SHALL be ignored. A call the card cannot
show (no questions, more than four, an option without a label, or any shape
the parser cannot read) SHALL show its question text as plain text with
"Open the terminal" and no choices. While a card shows, an `AskUserQuestion`
call with no result SHALL NOT show as a line in the conversation (the card
stands for it); a row left with nothing to show SHALL be hidden.

#### Scenario: A single question
- **WHEN** Claude Code, with the question hook installed, asks "Which database should the todo app use?" with options "SQLite (Recommended)" and "Postgres", the status turns blocked and the screen shows that dialog
- **THEN** a card shows the question, "1." SQLite (Recommended) with its description, "2." Postgres with its description, "Something else" and "Skip"

#### Scenario: A call with no result and no block
- **WHEN** the log holds an AskUserQuestion call with no result and the status is idle
- **THEN** no card shows and the call stays a folded line

#### Scenario: The waiting call's line hides behind its card
- **WHEN** a card shows and the log holds its `AskUserQuestion` call with no result yet
- **THEN** the chat shows no "AskUserQuestion · running" line for it (other calls folded with it stay); when the result arrives, the answered line shows as before

#### Scenario: No hook installed
- **WHEN** the status is blocked with the kind `question` and the desk holds no record for the session
- **THEN** the card reads "The agent asked a question in the terminal. To answer it here, add the question hook on the desk." with "Open the terminal" and no choices, and nothing can be sent from it

#### Scenario: A stale record
- **WHEN** the record names a call whose result is already in the log and the agent waits on a new question
- **THEN** the card shows "Open the terminal" and no choices

#### Scenario: The screen disagrees with the record
- **WHEN** the record holds "Which database should the todo app use?" but the screen shows another question
- **THEN** the card shows "Open the terminal" and no choices

#### Scenario: A permission prompt with a record left over
- **WHEN** the status is blocked with the kind `permission` and an old record exists
- **THEN** no card shows

#### Scenario: A description of 700 characters
- **WHEN** an option's description is 700 characters long
- **THEN** it is shown whole, wrapped, and the card scrolls to reach the options below it

#### Scenario: An unreadable shape
- **WHEN** the call's `questions` holds an option with no label
- **THEN** the card shows the question text and "Open the terminal", and no option can be tapped

### Requirement: Each answer is typed on the desk after its checks
A tap on an option SHALL type its answer at once in a call of one
single-choice question (the desk dialog has no review step there); in every other call
the phone keeps the answers until Send (requirement "The phone keeps the
answers until Send"), which types them one question after the other. Either
way each question's keys SHALL be sent only after, in this
order: `terminal.get` shows the same target and the status still `blocked`;
`terminal.read` shows the call's question text (box-drawing characters and
runs of spaces folded) and each of its options as its numbered row, in the
call's order; then one
`terminal.keys` call carries the whole key list of the step. For a single
choice option N the list SHALL be the digit N alone when no option of the
question has a preview, and the digit N and Enter when one has; it SHALL NOT
count arrow presses, and no key of a step SHALL answer a later question. If either check fails nothing SHALL be sent: a status that is
no longer `blocked` says "Already answered at the desk. Nothing was sent.",
and another agent, process or session in front (or none) or a screen without
the question says "The question changed. Nothing was sent." The card SHALL
show a tapped option as chosen and its buttons as disabled from the tap
until the answer ends. A send SHALL answer the call the card was showing
when it was tapped: one made on a card that has since moved to another call
sends nothing, and the result of a send for a call the card has left changes
nothing on it. After the keys, the app SHALL read the screen again; the
step is done when the dialog shows its next question or step, or is gone
and the log holds the call's result. When none matches, the card SHALL say
"Could not confirm the answer arrived. Check the terminal." with the
terminal button, and SHALL NOT send the keys again. A `terminal.keys` call
whose outcome is not known (no reply, connection lost) SHALL be reported the
same way and SHALL NOT be repeated. A check that cannot be made (its call
fails) SHALL send nothing and SHALL be reported the same way. With no
connection to the machine nothing SHALL be sent and the card SHALL say "Not
connected. Nothing was sent." The app SHALL NOT read the result text of the
call to learn the answer.

#### Scenario: Choosing the second option
- **WHEN** Ezra taps "2. Postgres" on the only question of a call, whose options have no preview, while the same dialog shows on the desk
- **THEN** one `terminal.keys` call sends `2` alone, the card marks Postgres as chosen, and when the log's result arrives the card collapses to "Answered: Database"

#### Scenario: Choosing an option of a question with previews
- **WHEN** Ezra's answer to a question is "2. Postgres" and an option of the question has a preview
- **THEN** when it is typed, one `terminal.keys` call sends `2` and Enter

#### Scenario: The first of three questions
- **WHEN** a call holds three questions without previews and Ezra taps "2. Postgres" on the first
- **THEN** nothing is sent, the desk dialog stays on the first question, and the card moves on to the second with Postgres kept for the first

#### Scenario: The status is no longer blocked
- **WHEN** Ezra taps an option and `terminal.get` reports the status working
- **THEN** no keys are sent and the card says "Already answered at the desk. Nothing was sent."

#### Scenario: The screen shows another dialog
- **WHEN** the screen no longer holds the call's question text at the moment of the check
- **THEN** no keys are sent and the card says "The question changed. Nothing was sent."

#### Scenario: No reply to the keys
- **WHEN** the connection drops while the keys are being sent
- **THEN** the card says "Could not confirm the answer arrived. Check the terminal." and the keys are not sent again

#### Scenario: Another session in front
- **WHEN** Ezra taps an option and the agent in front is now in another session
- **THEN** no keys are sent and the card says "The question changed. Nothing was sent."

#### Scenario: No connection
- **WHEN** Ezra taps an option while the machine is not connected
- **THEN** nothing is sent and the card says "Not connected. Nothing was sent."

#### Scenario: A rebound key
- **WHEN** the keys were sent and the screen then shows neither a next step nor a closed dialog
- **THEN** the card says "Could not confirm the answer arrived. Check the terminal." with the terminal button

### Requirement: Your own words answer through the dialog's text row
"Something else" (offered only on a question without previews) SHALL open a
text field ("Type your answer") with a button, enabled for non-blank text of at most 2000 characters, that answers like an option: at once in a call of one single-choice question ("Send"), kept in the others ("Next"). Typing it SHALL
pass the same checks as an option, send the key that focuses the dialog's
free-text row, then, once the screen shows that row in focus, send the text
as one `terminal.send` with `force`, so that the text and its Enter land in
that row; a screen that never shows it SHALL stop the answer with "Could not
confirm the answer arrived. Check the terminal." and send no text. The text SHALL NOT be sent as
a chat message and no bubble SHALL show for it. Past 2000 characters the
field SHALL say so ("Too long: 2000 characters at most") and its button SHALL stay
disabled. The keyboard SHALL open for this field when "Something else" is
tapped, and SHALL close when the text is sent or the field is dismissed
(Back), which closes the field and keeps the card.

#### Scenario: A typed answer
- **WHEN** Ezra taps "Something else", types "Use Postgres, but through Docker" and taps Send
- **THEN** the checks pass, the row is focused, the text is sent into it, the card collapses to "Answered: Database" when the result arrives, and no message bubble shows

#### Scenario: The keyboard comes only for the field
- **WHEN** Ezra taps "Something else"
- **THEN** the field takes the focus and the keyboard opens for it; after Send the keyboard is gone, and after Back the keyboard and the field are gone and the card stays

#### Scenario: Too long
- **WHEN** the text holds 2001 characters
- **THEN** the field reads "Too long: 2000 characters at most" and Send is disabled

#### Scenario: The row does not take the text
- **WHEN** the screen after the send shows neither a next step nor a closed dialog
- **THEN** the card says "Could not confirm the answer arrived. Check the terminal."

### Requirement: Skip declines the question
"Skip" SHALL pass the same checks as an option and then send one Esc through
`terminal.keys`, and SHALL NOT send Esc when a check fails (Esc outside the
dialog interrupts the agent). The call's result with `is_error` SHALL
collapse the card to "Skipped: <header>".

#### Scenario: Skipping
- **WHEN** Ezra taps Skip while the dialog shows
- **THEN** one Esc is sent, the agent carries on, and the card collapses to "Skipped: Database"

#### Scenario: Skipping a dialog that is already gone
- **WHEN** Ezra taps Skip and the status is working at the check
- **THEN** no Esc is sent and the card says "Already answered at the desk. Nothing was sent."

### Requirement: The phone keeps the answers until Send
The card SHALL keep each answer on the phone, in a call with more than one
question or with a multi-select question, and send nothing until Send
answers; the desk dialog stays on its first question until then. The card
SHALL show one question at a time with "Question N of M" and controls to go
to the previous question and, once the question shown has an answer, to the
next one. Answering a question (an option, your own words, "Choose this", or
the multi-select footer's button) SHALL keep that answer and show the next
question without an answer, or the review step when every question has one.
Going back to a question SHALL show its kept answer (the option marked, the
ticks and the typed text restored) and a new answer SHALL replace it. The
review step SHALL show "All questions answered. Send your answers, or skip
them all.", then each question with its kept answer (the option's label;
your own words; on multi-select the ticked labels in the options' order and
the typed text, joined with ", "; a note beneath its option as "Note:
<note>"), each with a control that goes back to that question, then "Send
answers" and "Skip". Send answers SHALL type the answers in the call's
order, each question only after the checks of requirement "Each answer is
typed on the desk after its checks" and once the screen shows that question;
then, once the screen shows the dialog's review step (the line after the
header reads "Review your answers", every question of the call is listed
with the answer the phone typed, and "1. Submit answers" is in focus; words
in a question or an option, another call's review, other answers, or Cancel
in focus are not it), Enter. When a step cannot be confirmed or a check
fails after some answers were typed, the app SHALL send nothing more and the
card SHALL say how many were typed ("Typed N of M answers, then stopped.
Check the terminal." with the terminal button) and keep the answers; a
second Send answers SHALL go on only from the question the screen then
shows, and only when that is the first one not yet typed. A first Send
answers whose screen does not show the first question SHALL send nothing
("The question changed. Nothing was sent."). Skip SHALL stay available on
every step and declines the whole call.

#### Scenario: Three questions
- **WHEN** a call asks three single-choice questions and Ezra taps an option for each
- **THEN** nothing is sent while he answers, the card reads "Question 1 of 3", "Question 2 of 3", "Question 3 of 3", then shows the review step listing the three answers

#### Scenario: Back to an earlier question
- **WHEN** the card shows question 3 and Ezra goes back to question 1
- **THEN** question 1 shows with his answer marked, nothing is sent, and a new tap replaces the answer

#### Scenario: A change from the review step
- **WHEN** the review step lists "Database: SQLite" and Ezra taps its change control and picks Postgres
- **THEN** the card shows the review step again listing Postgres, and nothing has been sent

#### Scenario: Send types everything
- **WHEN** Ezra taps Send answers on the review step of three questions
- **THEN** each answer is typed after its checks and once the screen shows its question, then Enter once the screen shows the dialog's review step with those answers, and the card collapses to "Answered: Database, Login, Tests" when the result arrives

#### Scenario: Sending stops halfway
- **WHEN** the first two answers were typed and the screen never shows the third question
- **THEN** nothing more is sent and the card says "Typed 2 of 3 answers, then stopped. Check the terminal." with the terminal button, keeping the answers

#### Scenario: Sending the rest
- **WHEN** after that the screen shows the third question and Ezra taps Send answers again
- **THEN** only the third answer is typed, then Enter on the dialog's review step

#### Scenario: The desk dialog moved meanwhile
- **WHEN** Ezra taps Send answers and the desk shows the second question because someone answered the first there
- **THEN** nothing is sent and the card says "The question changed. Nothing was sent."

#### Scenario: Other answers on the dialog's review step
- **WHEN** all answers were typed and the dialog's review step lists another answer than the phone typed
- **THEN** Enter is not sent and the card says "Typed 3 of 3 answers, then stopped. Check the terminal."

#### Scenario: One multi-select question
- **WHEN** a call has one multi-select question and Ezra ticks Apple, types "Berries" and taps the footer's button
- **THEN** the card shows the review step listing "Apple, Berries"; Send answers types them and presses Enter on the dialog's review step

### Requirement: A question where you may choose several
A question with `multiSelect` SHALL show its options with checkboxes, the
hint "Choose any number", how many are chosen ("N selected", "0 selected" at
first) and one button in the footer, enabled with at least one box ticked
or a typed answer, that keeps the answer (every call with a multi-select
question has a review step, so it is sent with Send answers). "Something else" SHALL be its last row; a typed
answer there SHALL show as a ticked row with its text, SHALL count as one
chosen, and SHALL go out with the ticked options:
the field gets no button of its own on a multi-select question (a
single-choice question keeps the field's own). When the answer is typed the app SHALL send,
after the checks, the digit alone of each option whose tick the screen
shows otherwise than chosen (a digit toggles its row, so a retry or a tick
made at the desk is not undone) and no digit for the free-text row or the
rows after it; with a typed
answer, once the screen shows the ticks, the text as one `terminal.send`
with `force`, and an Enter when the screen then shows the text in the
free-text row unticked; once the screen shows exactly the planned rows
ticked, the key that leaves the question (Tab; from the free-text row Tab,
then Enter once the screen shows Submit in focus). Each of these steps SHALL wait for the screen it
depends on, and a screen that does not come SHALL stop the answer with
"Could not confirm the answer arrived. Check the terminal.", sending nothing
again. The card SHALL report the same when the screen does not move on. The keys for this question
kind were confirmed on the desk and the phone (design, section B); the core
keeps a switch that, turned off, shows a multi-select question with "Open
the terminal" and its options as plain text, should a later Claude Code
change the dialog.

#### Scenario: Two of three ticked
- **WHEN** a multi-select question lists A, B and C, Ezra ticks A and C and the answers are sent
- **THEN** `1` and `3` are sent after the checks, then, once the screen shows A and C ticked, Tab

#### Scenario: A typed answer joins the ticks
- **WHEN** a multi-select question lists Apple, Banana and Mango, Ezra ticks Apple, types "Berries" in "Something else", and the answers are sent
- **THEN** the card read "2 selected" with the Berries row ticked, and after the checks `1`, the text "Berries" (with an Enter to tick its row when the screen shows it unticked), Tab and Enter are sent in that order, each after the screen it depends on

#### Scenario: A tick the screen does not show
- **WHEN** the ticks were sent and the screen never shows the planned rows ticked
- **THEN** nothing more is sent and the card says "Could not confirm the answer arrived. Check the terminal."

#### Scenario: Nothing ticked
- **WHEN** no option is ticked and no text is typed
- **THEN** the footer's button is disabled

### Requirement: A preview is shown on request
An option with a `preview` SHALL show a "Preview" button; it SHALL open a
sheet titled "Preview: <label>" with the preview text in the chat's code
font, its lines never wrapped (a wide sample scrolls sideways, a long one up
and down), and below it, fixed, a "Note (optional)" field and a "Choose
this" button; closing it SHALL leave the card as it was. "Choose this"
SHALL answer that option, at once or kept as a tap on the option is. Without a note it sends what a tap on the option
sends. With a note, after the same checks, it SHALL send the option's digit
(which moves the dialog's focus there); once the screen shows that option
focused, `n`; once the screen shows the notes field open, the note as one
`terminal.send` with `force`, whose Enter saves the note and answers the
option. Each step SHALL wait for the screen it depends on; a screen that
does not come SHALL stop the answer with "Could not confirm the answer
arrived. Check the terminal.", sending nothing again. A note SHALL be at
most 2000 characters ("Too long: 2000 characters at most", Choose this
disabled). On a multi-select question the sheet SHALL show the sample only.

#### Scenario: An option with a preview
- **WHEN** Ezra taps "Preview" under "Cards"
- **THEN** the sheet opens with the sample unwrapped, "Note (optional)" and "Choose this", and closing it returns to the unchanged card

#### Scenario: Choosing with a note
- **WHEN** Ezra types "Bigger titles please" in the sheet of option 2 and taps "Choose this"
- **THEN** after the checks `2` is sent, then `n` once option 2 shows focused, then the note once the notes field shows, and the card moves on when the screen does

#### Scenario: Choosing without a note
- **WHEN** Ezra taps "Choose this" with the note empty
- **THEN** `2` and Enter are sent, as for a tap on option 2

#### Scenario: A wide sample
- **WHEN** a sample's lines are wider than the sheet
- **THEN** they stay on one line each and the sample scrolls sideways

### Requirement: The card ends with the question
The card SHALL end, with no keys sent, when the call's result arrives, the
status leaves `blocked`, or the target changes. It SHALL then become a quiet
line "Answered: <header>" (or "Skipped: <header>" when the result has
`is_error`) that opens the call's tool sheet; a call with several questions
names every header, joined with ", " ("Answered: Database, Login"). A tap that was already on its
way when the card ended SHALL send nothing (check one fails).

#### Scenario: Answered at the desk
- **WHEN** the question is answered at the desk
- **THEN** the card becomes "Answered: Database" without a tap and without keys

#### Scenario: The agent exits
- **WHEN** the agent exits while the card shows
- **THEN** the card is gone and the folded call line remains

### Requirement: The message box while a question card shows
While a question card shows, the chat's message box SHALL NOT be shown and
its keyboard SHALL NOT open; a keyboard already open when the card appears
SHALL close. The draft SHALL be kept. The only keyboard while the card shows
is the one for "Something else" (and the note in a preview sheet). Every send
from the card (an option, the field's button, the multi-select button, Choose this, Skip,
Send answers) or keeping an answer SHALL close that keyboard, and nothing in the card SHALL take
the focus while an answer is on its way. When the card ends (answered, skipped, or
gone because the agent moved on or the session changed), the message box
SHALL show again with its draft. When no card shows, the refusal for a
blocked agent stays "The agent is waiting for you in the terminal".

#### Scenario: A card replaces the message box
- **WHEN** a question card shows while Ezra has a draft in the message box
- **THEN** the message box and its keyboard are gone, and the card is the last thing above the bottom edge

#### Scenario: No keyboard while the multi-select answer is sent
- **WHEN** Ezra ticks an option, types "Berries" in "Something else", keeps the answer and taps Send answers
- **THEN** the keyboard closes at the tap and stays closed for the whole send

#### Scenario: The message box comes back
- **WHEN** the question is answered at the desk
- **THEN** the card collapses to its quiet line and the message box shows again, holding the draft

#### Scenario: A permission prompt
- **WHEN** the agent is blocked on a permission prompt with no question card
- **THEN** sending still says "The agent is waiting for you in the terminal" with the terminal button

### Requirement: The card's texts
The card SHALL use these texts and no others of its own:

| Where | Text |
|---|---|
| Option row | `N.` + the log's label, the log's description beneath |
| Free-text row | Something else |
| Free-text field hint | Type your answer |
| Free-text button | Send (one single-choice question); Next (draft) in the other calls |
| Free-text too long | Too long: 2000 characters at most |
| Skip button | Skip |
| Step | Question N of M |
| Last step button | Send answers |
| Multi-select hint | Choose any number |
| Multi-select count | `N` selected ("0 selected", "2 selected") |
| Multi-select button | Next (draft) |
| Preview button | Preview |
| Preview sheet title | Preview: `<label>` |
| Preview sheet note hint | Note (optional) |
| Preview sheet button | Choose this |
| Nothing sent: answered | Already answered at the desk. Nothing was sent. |
| Nothing sent: changed | The question changed. Nothing was sent. |
| Nothing sent: not connected | Not connected. Nothing was sent. |
| Not confirmed | Could not confirm the answer arrived. Check the terminal. |
| Unreadable or unsupported | Open the terminal |
| Review step | All questions answered. Send your answers, or skip them all. |
| Review step, each answer | `<question>` above `<answer>`; a note beneath as "Note: `<note>`" (draft) |
| Review step, change control | Change (draft); read as "Change the answer to: `<question>`" (draft) |
| Previous / next question | Previous question, Next question (drafts, read by TalkBack; icons on screen) |
| Sending stopped halfway | Typed N of M answers, then stopped. Check the terminal. (draft) |
| No hook record | The agent asked a question in the terminal. To answer it here, add the question hook on the desk. |
| Collapsed, answered | Answered: `<header>` (several: the headers joined with ", ") |
| Collapsed, declined | Skipped: `<header>` |

#### Scenario: Texts of the card
- **WHEN** the card shows each state above
- **THEN** every fixed text is the one in the table, in the same case
