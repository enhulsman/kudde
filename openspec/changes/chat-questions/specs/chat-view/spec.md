## ADDED Requirements

### Requirement: A pending question shows as a card
The chat SHALL show an answerable card at the bottom only while all of these
hold: the desk holds the question hook's record (design D1) for the session
shown, naming an `AskUserQuestion` call that has no result in the log; the
terminal's status is `blocked` (with no status kind, or the kind
`question`); the chat's send target (agent, process, process start, session)
is unchanged; and the terminal's screen shows the record's dialog (its
question text and every option label, box-drawing characters and runs of
spaces folded), read from the dialog's last header line (☐ or ☒) down and
never from the chat above it, which may hold an earlier question. The step the card answers is the one the screen shows. When
the record is missing, stale (another session, or its call already has a
result in the log) or the screen does not show its dialog, while the status
is `blocked` with the kind `question` or the log holds a call with no result,
the card SHALL show the question text it knows (none when it knows none)
with "Open the terminal" and no choices, and SHALL send nothing. A status of
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
"Open the terminal" and no choices.

#### Scenario: A single question
- **WHEN** Claude Code, with the question hook installed, asks "Which database should the todo app use?" with options "SQLite (Recommended)" and "Postgres", the status turns blocked and the screen shows that dialog
- **THEN** a card shows the question, "1." SQLite (Recommended) with its description, "2." Postgres with its description, "Something else" and "Skip"

#### Scenario: A call with no result and no block
- **WHEN** the log holds an AskUserQuestion call with no result and the status is idle
- **THEN** no card shows and the call stays a folded line

#### Scenario: No hook installed
- **WHEN** the status is blocked with the kind `question` and the desk holds no record for the session
- **THEN** the card shows "Open the terminal" and no choices, and nothing can be sent from it

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

### Requirement: A tapped option answers on the desk
A tap on an option SHALL send the keys that answer it only after, in this
order: `terminal.get` shows the same target and the status still `blocked`;
`terminal.read` shows the call's question text (box-drawing characters and
runs of spaces folded) and the first label of each of its options; then one
`terminal.keys` call carries the whole key list of the step. For a single
choice option N the list SHALL be the digit N alone when no option of the
question has a preview, and the digit N and Enter when one has; it SHALL NOT
count arrow presses, and no key of a step SHALL answer a later question. If either check fails nothing SHALL be sent: a status that is
no longer `blocked` says "Already answered at the desk. Nothing was sent.",
and another agent, process or session in front (or none) or a screen without
the question says "The question changed. Nothing was sent." The card SHALL
show a tapped option as chosen and its buttons as disabled from the tap
until the step ends. After the keys, the app SHALL read the screen again; the
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
- **WHEN** Ezra taps "2. Postgres" on a question whose options have no preview while the same dialog shows on the desk
- **THEN** one `terminal.keys` call sends `2` alone, the card marks Postgres as chosen, and when the log's result arrives the card collapses to "Answered: Database"

#### Scenario: Choosing an option of a question with previews
- **WHEN** Ezra taps "2. Postgres" and an option of the question has a preview
- **THEN** one `terminal.keys` call sends `2` and Enter

#### Scenario: The first of three questions
- **WHEN** a call holds three questions without previews and Ezra taps "2. Postgres" on the first
- **THEN** only `2` is sent, the dialog moves to the second question with nothing chosen there, and the card moves on to it

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
text field ("Type your answer") with a "Send" button, enabled for non-blank text of at most 2000 characters. Send SHALL
pass the same checks as an option, send the key that focuses the dialog's
free-text row, then send the text as one `terminal.send` with `force`, so
that the text and its Enter land in that row. The text SHALL NOT be sent as
a chat message and no bubble SHALL show for it. Past 2000 characters the
field SHALL say so ("Too long: 2000 characters at most") and Send SHALL stay
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

### Requirement: Several questions are answered one at a time
A call with more than one question SHALL show one question at a time with
"Question N of M", following the question the screen shows. A tap answers
that question on the desk at once; the card then shows the next question. When the dialog shows
its review step ("Submit answers"), the card SHALL show "Send answers" and
"Skip" and nothing else, and Send answers SHALL send Enter after the same
checks. The card SHALL follow the screen: a question answered at the desk
moves the card on, and no keys for it are sent from the card.

#### Scenario: Three questions
- **WHEN** a call asks three single-choice questions and Ezra taps an option for each
- **THEN** the card reads "Question 1 of 3", "Question 2 of 3", "Question 3 of 3", then shows "Send answers", and the call ends when Ezra taps it

#### Scenario: The second question answered at the desk
- **WHEN** the card shows question 2 and the desk answers it
- **THEN** the card shows question 3 (or Send answers) without sending anything

### Requirement: A question where you may choose several
A question with `multiSelect` SHALL show its options with checkboxes, the
hint "Choose any number", how many are chosen ("N selected", "0 selected" at
first) and one "Send" button in the footer, enabled with at least one box
ticked or a typed answer. "Something else" SHALL be its last row; a typed
answer there SHALL show as a ticked row with its text, SHALL count as one
chosen, and SHALL go out with the ticked options through the footer's Send:
the field gets no send button of its own on a multi-select question (a
single-choice question keeps the field's own Send). Tapping Send SHALL send,
after the checks, the digit of each ticked option alone (a digit toggles its
row) and no digit for the free-text row or the rows after it; with a typed
answer, once the screen shows the ticks, the text as one `terminal.send`
with `force`, and an Enter when the screen then shows the text in the
free-text row unticked; once the screen shows exactly the planned rows
ticked, the key that leaves the question (Tab, or Tab and Enter from the
free-text row); and in a call of one question, once the screen shows the
review step, Enter. Each of these steps SHALL wait for the screen it
depends on, and a screen that does not come SHALL stop the answer with
"Could not confirm the answer arrived. Check the terminal.", sending nothing
again. The card SHALL report the same when the screen does not move on. The keys for this question
kind are not confirmed against the program (design, section B); until the
phone check confirms them, a multi-select question SHALL show "Open the
terminal" with its options as plain text.

#### Scenario: Two of three ticked
- **WHEN** a multi-select question lists A, B and C and Ezra ticks A and C and taps Send
- **THEN** `1` and `3` are sent after the checks, then, once the screen shows A and C ticked, Tab, and the card moves on

#### Scenario: A typed answer joins the ticks
- **WHEN** a multi-select question lists Apple, Banana and Mango, Ezra ticks Apple, types "Berries" in "Something else" and taps the footer's Send
- **THEN** the card reads "2 selected", the Berries row shows ticked, and after the checks `1`, the text "Berries" (with an Enter to tick its row when the screen shows it unticked), Tab and Enter, and on the review step Enter are sent in that order, each after the screen it depends on

#### Scenario: A tick the screen does not show
- **WHEN** the ticks were sent and the screen never shows the planned rows ticked
- **THEN** nothing more is sent and the card says "Could not confirm the answer arrived. Check the terminal."

#### Scenario: Nothing ticked
- **WHEN** no option is ticked and no text is typed
- **THEN** Send is disabled

### Requirement: A preview is shown on request
An option with a `preview` SHALL show a "Preview" button; it SHALL open a
sheet titled "Preview: <label>" with the preview text in the chat's code
font, its lines never wrapped (a wide sample scrolls sideways, a long one up
and down), and below it, fixed, a "Note (optional)" field and a "Choose
this" button; closing it SHALL leave the card as it was. "Choose this"
SHALL answer that option. Without a note it sends what a tap on the option
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
is the one for "Something else". When the card ends (answered, skipped, or
gone because the agent moved on or the session changed), the message box
SHALL show again with its draft. When no card shows, the refusal for a
blocked agent stays "The agent is waiting for you in the terminal".

#### Scenario: A card replaces the message box
- **WHEN** a question card shows while Ezra has a draft in the message box
- **THEN** the message box and its keyboard are gone, and the card is the last thing above the bottom edge

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
| Free-text button | Send |
| Free-text too long | Too long: 2000 characters at most |
| Skip button | Skip |
| Step | Question N of M |
| Last step button | Send answers |
| Multi-select hint | Choose any number |
| Multi-select count | `N` selected ("0 selected", "2 selected") |
| Multi-select button | Send |
| Preview button | Preview |
| Preview sheet title | Preview: `<label>` |
| Preview sheet note hint | Note (optional) |
| Preview sheet button | Choose this |
| Nothing sent: answered | Already answered at the desk. Nothing was sent. |
| Nothing sent: changed | The question changed. Nothing was sent. |
| Nothing sent: not connected | Not connected. Nothing was sent. |
| Not confirmed | Could not confirm the answer arrived. Check the terminal. |
| Unreadable or unsupported | Open the terminal |
| Collapsed, answered | Answered: `<header>` (several: the headers joined with ", ") |
| Collapsed, declined | Skipped: `<header>` |

#### Scenario: Texts of the card
- **WHEN** the card shows each state above
- **THEN** every fixed text is the one in the table, in the same case
