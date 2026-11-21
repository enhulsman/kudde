## ADDED Requirements

### Requirement: One screen of an open agent at a time
The app SHALL show one screen of an open agent at a time: the terminal,
the chat, the Changes screen or the tab overview. Only the screen on top SHALL
exist for touch, focus and the screen reader; a screen that is not on top
MUST NOT receive a touch, hold focus, show a cursor or expose a node to
the screen reader. Back SHALL leave the top screen: from Changes or the
tab overview to the view under it, from the terminal or the chat out of
the agent, after the screen's own inner steps (compose sheet, selection,
image viewer, tool sheet, a diff) as today.

#### Scenario: A swipe on the tab overview never steps the terminal
- **GIVEN** Ezra has a terminal open in a tab of three panes and opened the tab overview from the title bar's map
- **WHEN** he swipes sideways across the top of the screen, where the terminal's title bar sits when it shows
- **THEN** no pane is opened, no step haptic plays, the overview stays, and the terminal behind it is unchanged

#### Scenario: A swipe on Changes never steps the terminal
- **GIVEN** Ezra opened Changes from the terminal's menu
- **WHEN** he swipes sideways or up across the top of the screen
- **THEN** no other terminal or agent opens and Changes stays

#### Scenario: The screen reader hears only the top screen
- **GIVEN** TalkBack is on and Ezra opened Changes over the chat
- **WHEN** the screen's nodes are read
- **THEN** only the Changes screen's nodes exist; none of the chat's or the terminal's

#### Scenario: Back from Changes returns to the view it was opened from
- **GIVEN** Ezra opened Changes from the chat, then from the terminal on another visit
- **WHEN** he goes back each time
- **THEN** the chat shows the first time and the terminal the second, as each was

#### Scenario: Back from the base leaves the agent
- **GIVEN** Ezra has an agent's chat open with nothing over it
- **WHEN** he goes back
- **THEN** the home screen shows, as today

### Requirement: Only the top screen holds the keyboard
When the top screen of an open agent changes, focus SHALL be cleared and
the phone's keyboard SHALL go down. A screen SHALL take focus on arrival
only when the person asked for it on that screen (the compose sheet
opening, the keyboard key, the chip). The keyboard MUST NOT come back
while Changes or the tab overview shows unless the person asks for it on
that screen.

#### Scenario: The keyboard stays down under Changes
- **GIVEN** Ezra has the chat open, the message field focused and the keyboard up
- **WHEN** he opens Changes from the title bar's menu
- **THEN** the keyboard goes down and stays down, no text cursor shows anywhere, and no node holds focus while Changes shows

#### Scenario: Back to the chat does not reopen the keyboard
- **GIVEN** the scenario above has run
- **WHEN** Ezra goes back
- **THEN** the chat shows with its draft and the keyboard stays down until he taps the field

#### Scenario: The terminal's keyboard goes down for the overview
- **GIVEN** Ezra has the terminal open with its keyboard up
- **WHEN** he opens the tab overview from the map
- **THEN** the keyboard goes down, and nothing typed afterwards reaches the terminal

### Requirement: An open menu or the gauge's detail blocks what is below
The title bar's menu and the context gauge's detail SHALL open in a window
of their own over the screen. While one is open, a touch outside its panel
SHALL close it and MUST NOT reach anything below; Back SHALL close it and
nothing else. The gauge's detail SHALL show the same text as today and
MUST NOT move the chat's content when it opens or closes.

#### Scenario: A swipe outside the menu only closes it
- **GIVEN** the title bar's menu is open over the terminal
- **WHEN** Ezra swipes sideways across the title bar outside the menu's panel
- **THEN** the menu closes, no pane is opened and nothing is sent to the terminal

#### Scenario: A tap outside the gauge's detail only closes it
- **GIVEN** the gauge's detail is open in the chat
- **WHEN** Ezra taps on a message below it
- **THEN** the detail closes and the message's tap does not happen

#### Scenario: The gauge's detail does not push the chat
- **WHEN** Ezra taps the gauge in the chat
- **THEN** the detail shows under the title bar and the messages stay where they were, without moving down or back

#### Scenario: Back closes the gauge's detail only
- **GIVEN** the gauge's detail is open
- **WHEN** Ezra goes back
- **THEN** the detail closes and the chat stays open

### Requirement: Leaving a screen of the agent keeps the agent's state
Moving between the screens of one open agent SHALL keep the terminal
attached at the size it had, and SHALL keep the compose text, the
terminal's zoom and pan, the chat's draft, photos and scroll place. Coming
back to the terminal or the chat SHALL show it at once, without a wait and
without attaching again. Leaving the agent SHALL release it as today:
detach, the desk takes its size back, and the last screen is cached.

#### Scenario: Changes over the terminal keeps the desk's size
- **GIVEN** Ezra has a terminal open at the phone's size with the keyboard up
- **WHEN** he opens Changes, the keyboard goes down, and he goes back after a minute
- **THEN** no size was sent to the desk while Changes showed, the terminal shows at once without attaching again, and its new size goes out only once the keyboard has settled, as today

#### Scenario: Changes over the chat keeps the desk's size
- **GIVEN** Ezra has an agent's chat open, its terminal watched at the desk's size
- **WHEN** he opens Changes and goes back
- **THEN** the terminal was never taken over at the phone's size and stayed attached throughout

#### Scenario: The compose text survives the overview
- **GIVEN** Ezra typed text in the compose sheet, closed nothing, and opened the overview from the map
- **WHEN** he goes back to the terminal
- **THEN** the compose text is still there

#### Scenario: An agent opened on its chat still follows the terminal
- **GIVEN** the chat is the chosen view and the terminal of a Codex agent has never been shown in this run
- **WHEN** Ezra opens that agent
- **THEN** the terminal is attached watching at the desk's size, the chat reads its screen for queued messages, and the desk's size does not change

### Requirement: Nothing else about an open agent changes
What an open agent shows and does SHALL stay as it was before this
change, apart from the requirements above: the steps on the title bar and
their haptics and bounce, the 400 ms wait after a step and input during
it, the switch between terminal and chat, the keyboard going down on a
switch to another terminal, the screen reader's texts and actions, and
the look of every screen. The swap between screens SHALL be an instant
cut, with no animation, until a designed transition is signed off.

#### Scenario: A step still waits 400 ms
- **GIVEN** Ezra is in the first terminal of a tab of two panes
- **WHEN** he swipes to the next pane of the tab
- **THEN** the next pane shows its last screen and is attached after 400 ms, as today

#### Scenario: The switch to the chat and back
- **WHEN** Ezra taps the chat icon and then the terminal icon
- **THEN** the chat shows, then the terminal, each at once, with the keyboard down, as today

#### Scenario: The screens look the same
- **WHEN** the terminal, the chat, Changes and the overview are captured in their existing golden tests
- **THEN** every golden matches, except the gauge's detail now drawn where its window places it
