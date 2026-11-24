## ADDED Requirements

### Requirement: A pending question shows as a card
The chat SHALL show a card at the bottom while the log holds an
`AskUserQuestion` call of the agent in front with no result yet, the
terminal's status is `blocked` and the chat's send target (agent, process,
process start, session) is unchanged. The card SHALL show the question's text,
its options numbered from 1 in the log's order, each with its label and,
beneath it, its description, a "Something else" row and a "Skip" button.
Labels, descriptions and questions SHALL be shown whole and wrapped, never
truncated; a card taller than the screen SHALL scroll inside its own area.
With no result yet but a status that is not `blocked`, no card SHALL show.
Unknown fields in the call SHALL be ignored. A call the card cannot show
(no questions, more than four, an option without a label, or any shape the
parser cannot read) SHALL show its question text as plain text with "Open the
terminal" and no choices.

#### Scenario: A single question
- **WHEN** Claude Code asks "Which database should the todo app use?" with options "SQLite (Recommended)" and "Postgres" and the status turns blocked
- **THEN** a card shows the question, "1." SQLite (Recommended) with its description, "2." Postgres with its description, "Something else" and "Skip"

#### Scenario: A call with no result and no block
- **WHEN** the log holds an AskUserQuestion call with no result and the status is idle
- **THEN** no card shows and the call stays a folded line

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
choice option N the list SHALL be the digit N and Enter; it SHALL NOT count
arrow presses. If either check fails nothing SHALL be sent: a status that is
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
- **WHEN** Ezra taps "2. Postgres" while the same dialog shows on the desk
- **THEN** one `terminal.keys` call sends `2` and Enter, the card marks Postgres as chosen, and when the log's result arrives the card collapses to "Answered: Database"

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
"Something else" SHALL open a text field ("Type your answer") with a "Send"
button, enabled for non-blank text of at most 2000 characters. Send SHALL
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
after the checks, the keys that toggle each ticked option, then, with a
typed answer, the key that focuses the free-text row, the text as one
`terminal.send` with `force` (after the screen still shows the question),
and then (again after that screen check) the key that submits the
question; without a typed answer the toggles and the submit go as one
`terminal.keys` call. The card SHALL report "Could not confirm the answer arrived. Check
the terminal." when the screen does not move on. The keys for this question
kind are not confirmed against the program (design, section B); until the
phone check confirms them, a multi-select question SHALL show "Open the
terminal" with its options as plain text.

#### Scenario: Two of three ticked
- **WHEN** a multi-select question lists A, B and C and Ezra ticks A and C and taps Send
- **THEN** the keys for A, for C and the submit are sent after the checks and the card moves on

#### Scenario: A typed answer joins the ticks
- **WHEN** a multi-select question lists Apple, Banana and Mango, Ezra ticks Apple, types "Berries" in "Something else" and taps the footer's Send
- **THEN** the card reads "2 selected", the Berries row shows ticked, and the keys for Apple, the free-text row, the text "Berries" and the submit are sent in that order after the checks

#### Scenario: Nothing ticked
- **WHEN** no option is ticked and no text is typed
- **THEN** Send is disabled

### Requirement: A preview is shown on request
An option with a `preview` SHALL show a "Preview" button; it SHALL open a
sheet titled "Preview: <label>" with the preview text in the chat's code
box, wrapped and never scrolling sideways, and closing it SHALL leave the
card as it was.

#### Scenario: An option with a preview
- **WHEN** Ezra taps "Preview" under "Postgres"
- **THEN** the sheet opens with the preview text, and closing it returns to the unchanged card

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
