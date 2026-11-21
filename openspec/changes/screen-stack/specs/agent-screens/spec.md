## ADDED Requirements

### Requirement: One screen of an open agent at a time
The app SHALL show one screen of an open agent at a time: the terminal,
the chat, the Changes screen or the tab overview. Only the screen on top SHALL
exist for touch, focus and the screen reader; a screen that is not on top
MUST NOT receive a touch, hold focus, show a cursor or expose a node to
the screen reader, also while a transition between two screens runs. Back
SHALL leave the top screen: from Changes or the tab overview to the view
under it, from the terminal or the chat out of the agent, after the screen's
own inner steps (compose sheet, selection, image viewer, tool sheet, a diff)
as today. At most one cover (Changes or the overview) SHALL be shown over the
terminal or the chat.

#### Scenario: A swipe on an inert part of the tab overview never steps the terminal
- **GIVEN** Ezra has a terminal open in a tab of three panes and opened the tab overview from the title bar's map
- **WHEN** he swipes sideways across the top of the screen, where the terminal's title bar sits when it shows and the overview has nothing to touch
- **THEN** no pane is opened, no step haptic plays, the overview stays, and the terminal behind it is unchanged

#### Scenario: A swipe on an interactive part of the overview does not step the terminal either
- **GIVEN** the tab overview is open
- **WHEN** he swipes sideways across a tab tile or the tab chips
- **THEN** only the overview reacts (scrolls or nothing), no pane is opened and no step haptic plays

#### Scenario: A swipe on Changes never steps the terminal
- **GIVEN** Ezra opened Changes from the terminal's menu
- **WHEN** he swipes sideways or up across the top of the screen, on an empty part and on a row
- **THEN** no other terminal or agent opens and Changes stays

#### Scenario: The screen reader hears only the top screen
- **GIVEN** TalkBack is on and Ezra opened Changes over the chat
- **WHEN** the screen's nodes are read, at rest and halfway through the transition
- **THEN** only the Changes screen's nodes exist for the reader; none of the chat's or the terminal's

#### Scenario: Back from Changes returns to the view it was opened from
- **GIVEN** Ezra opened Changes from the chat, then from the terminal on another visit
- **WHEN** he goes back each time
- **THEN** the chat shows the first time and the terminal the second

#### Scenario: Back from the base leaves the agent
- **GIVEN** Ezra has an agent's chat open with nothing over it
- **WHEN** he goes back
- **THEN** the home screen shows, as today

#### Scenario: A cancelled Back gesture changes nothing
- **GIVEN** Changes is open over the terminal
- **WHEN** Ezra starts the Back gesture, drags part way and lets go to cancel
- **THEN** Changes stays, the terminal is not detached and no size is sent

#### Scenario: A second Back before the screen has changed leaves once
- **GIVEN** Changes is open over the terminal
- **WHEN** two Back presses arrive with no frame between them
- **THEN** the first returns to the terminal, the second leaves the agent, and the agent is left once

#### Scenario: Back with the keyboard up only puts the keyboard down
- **GIVEN** the keyboard is up over the chat
- **WHEN** Ezra goes back once
- **THEN** the keyboard goes down and the agent stays open

### Requirement: Only the top screen holds the keyboard
When the top screen of an open agent changes, focus SHALL be cleared and
the phone's keyboard SHALL go down, before the change shows. A screen SHALL
take focus on arrival only when the person asked for it in that visit to the
screen (the compose sheet opening, the keyboard key, the chip); returning to
a screen is not a request. The keyboard MUST NOT come back while Changes or
the tab overview shows unless the person asks for it on that screen.

#### Scenario: The keyboard stays down under Changes
- **GIVEN** Ezra has the chat open, the message field focused and the keyboard up
- **WHEN** he opens Changes from the title bar's menu
- **THEN** the keyboard goes down and stays down, also after the frames that follow, no text cursor shows anywhere, and no node holds focus while Changes shows

#### Scenario: Back to the chat does not reopen the keyboard
- **GIVEN** the scenario above has run
- **WHEN** Ezra goes back
- **THEN** the chat shows with its draft and the keyboard stays down until he taps the field

#### Scenario: The terminal's keyboard goes down for the overview
- **GIVEN** Ezra has the terminal open with its keyboard up
- **WHEN** he opens the tab overview from the map
- **THEN** the keyboard goes down, and nothing typed afterwards reaches the terminal

#### Scenario: A compose sheet with text comes back without the keyboard
- **GIVEN** Ezra typed text in the compose sheet and opened the overview from the map
- **WHEN** he goes back to the terminal
- **THEN** the sheet shows with its text, no node holds focus and the keyboard stays down until he taps the field

#### Scenario: Quick changes leave no keyboard behind
- **GIVEN** the chat's keyboard is up
- **WHEN** Ezra opens Changes and goes back twice in quick succession, with the keyboard's own hide callback arriving late
- **THEN** the keyboard is down and no node holds focus once the frames have run

### Requirement: An open menu blocks what is below
The title bar's menu SHALL open in a window of its own over the screen.
While it is open, a touch outside its panel SHALL close it and MUST NOT
reach anything below; a touch inside the panel SHALL NOT close it; Back SHALL
close it and nothing else.

#### Scenario: A swipe outside the menu only closes it
- **GIVEN** the title bar's menu is open over the terminal
- **WHEN** Ezra swipes sideways across the title bar outside the menu's panel
- **THEN** the menu closes, no pane is opened and nothing is sent to the terminal

#### Scenario: A tap inside the menu's panel does not close it
- **GIVEN** the title bar's menu is open
- **WHEN** Ezra taps the panel's empty padding
- **THEN** the menu stays open

#### Scenario: Back closes the menu only
- **GIVEN** the title bar's menu is open over the chat
- **WHEN** Ezra goes back, by key and by the Back gesture
- **THEN** the menu closes and the chat stays open

### Requirement: Leaving a screen of the agent keeps the agent's state
Moving between the screens of one open agent SHALL keep the terminal
attached, and SHALL keep the compose text, the terminal's zoom and pan, the
chat's draft, photos and scroll place. A terminal text selection and an
expanded chat card SHALL end when their screen is left, and the terminal's
scroll-back SHALL return to the live end. Coming back to the terminal or the
chat SHALL not attach again and SHALL not wait. The desk SHALL be taken over
at the phone's size only while the terminal is on screen and has measured a
real grid; no size, and no takeover, SHALL be sent while it is not on top.
Leaving the agent SHALL release it as today: detach, the desk takes its size
back, and the last screen is cached.

#### Scenario: Changes over the terminal keeps the desk's size
- **GIVEN** Ezra has a terminal open at the phone's size, its size settled, with the keyboard up
- **WHEN** he opens Changes, the keyboard goes down, and he goes back after a minute
- **THEN** no size was sent to the desk while Changes showed, the terminal shows without attaching again, and its new size goes out only once the keyboard has settled, as today

#### Scenario: A size still settling when Changes opens is not sent under it
- **GIVEN** the terminal's size changed a moment ago and has not yet been sent
- **WHEN** Ezra opens Changes
- **THEN** nothing is sent while Changes shows, and on going back the terminal's current size is sent once

#### Scenario: Changes over the chat keeps the desk's size
- **GIVEN** Ezra has an agent's chat open, its terminal watched at the desk's size
- **WHEN** he opens Changes and goes back
- **THEN** the terminal was never taken over at the phone's size and stayed attached throughout

#### Scenario: An answer deciding the view while Changes is open does not take the desk
- **GIVEN** an agent opened on its chat before the desk answered, and Ezra opened Changes
- **WHEN** the first answer arrives and decides the terminal is the view, before and after 400 ms
- **THEN** no size and no takeover is sent while Changes shows; going back to the terminal takes the desk once, at the terminal's measured size, sent before the takeover

#### Scenario: An agent opened on its chat still follows the terminal
- **GIVEN** the chat is the chosen view and the terminal of a Codex agent has never been shown in this run
- **WHEN** Ezra opens that agent
- **THEN** the terminal is attached watching at the desk's size, the chat reads its screen for queued messages, and the desk's size does not change

#### Scenario: The compose text survives the overview
- **GIVEN** Ezra typed text in the compose sheet and opened the overview from the map
- **WHEN** he goes back to the terminal
- **THEN** the compose text is still there

#### Scenario: The chat's place survives Changes
- **GIVEN** Ezra scrolled up in a chat, with an unsent bubble pending and new messages arriving
- **WHEN** he opens Changes and goes back
- **THEN** the list is where he left it, with the draft and photos unchanged

#### Scenario: Selection and cards end on leaving
- **GIVEN** a terminal text selection is on, and an agent card is expanded in the chat
- **WHEN** he opens Changes from each and goes back
- **THEN** the selection is gone and the card is folded

#### Scenario: Leaving the agent under a cover releases it once
- **GIVEN** Changes is open
- **WHEN** the agent disappears from the hub
- **THEN** the agent closes, the terminal detaches once and its last screen is cached, and nothing opens afterwards

#### Scenario: Returning from the background under Changes
- **GIVEN** Stay connected is on and Changes is open over the terminal
- **WHEN** the app stays in the background past the idle limit and comes back
- **THEN** the terminal reopens watching at the last size, Changes is still shown, and going back to the terminal takes the desk once

#### Scenario: Turning the phone returns to the view without the cover
- **GIVEN** Changes is open over the chat with a draft
- **WHEN** the phone is rotated
- **THEN** the chat shows with its draft and scroll place, Changes is closed, and the terminal has been detached and attached again, as today

### Requirement: The screen reader keeps hearing answers when they arrive
Live answers in the chat SHALL be announced once, when they arrive, whether
the chat or a cover is on top, and SHALL NOT be announced again on return.
No new spoken text SHALL be added by this change.

#### Scenario: An answer arrives under Changes
- **GIVEN** TalkBack is on and Changes is open over the chat
- **WHEN** a new answer arrives
- **THEN** it is announced once now, and not again when Ezra goes back

### Requirement: Moving between screens is designed motion
A change between the screens of an open agent SHALL animate with a motion
specific to that change, with the directions the owner approved and the
values of the signed-off prototype, all read from one table: terminal and
chat fade through each other with a slight scale and no slide; Changes slides
in from the right while the screen behind shifts left a little and dims; Back
from Changes is the predictive Back; the tab overview is the current screen
shrinking into its tile and a chosen tab growing that tile to full screen; the
title bar's menu grows from the button it came from. Motion SHALL use spring
specs. Back SHALL be an interruptible, seekable gesture: following it moves
the transition, letting go commits it, cancelling returns it with nothing
changed. A new change or a second Back during a motion SHALL take over from
where it is. The outgoing screen SHALL take no touch, no focus and no
screen-reader visit from the moment the change starts, and no touch SHALL
reach a screen underneath during or after a transition. With the system's
"remove animations" on, every change SHALL be an instant cut.

#### Scenario: Terminal and chat fade through each other
- **WHEN** Ezra taps the chat icon and then the terminal icon
- **THEN** each change plays the fade-through with a slight scale from the table, with no sideways movement

#### Scenario: Changes slides in from the right
- **WHEN** Ezra opens Changes from the terminal or the chat
- **THEN** Changes enters from the right while the screen behind shifts left a little and dims, as the table gives for opening Changes

#### Scenario: The Back gesture follows the thumb and finishes on release
- **GIVEN** Changes is open
- **WHEN** Ezra drags the Back gesture half way and lets go past the commit point
- **THEN** the screen followed his thumb, shrank to a card with the base showing behind, and finishes: the base shows, Changes is gone

#### Scenario: Pulling the Back gesture back cancels
- **GIVEN** Changes is open
- **WHEN** Ezra drags the Back gesture half way and pulls back to cancel
- **THEN** Changes returns to full screen, and nothing popped, detached or was sent

#### Scenario: The overview shrinks into its tile and a tab grows from it
- **WHEN** Ezra opens the tab overview from the map, then chooses the tab shown
- **THEN** the screen shrinks into its tile on opening, and choosing the tab grows that tile to full screen

#### Scenario: The menu grows from its button
- **WHEN** Ezra opens the title bar's menu
- **THEN** the menu grows from the three-dot button and closes back into it

#### Scenario: Reduced motion is a cut
- **GIVEN** the system's "remove animations" is on
- **WHEN** Ezra makes any change between screens, or finishes or cancels the Back gesture
- **THEN** each change is an instant cut in one frame, and a cancelled gesture still changes nothing

#### Scenario: No touch reaches the screen underneath during or after a transition
- **GIVEN** a change from Changes back to the terminal is half way, and again once it has finished
- **WHEN** Ezra touches where a Changes row or the terminal's title bar is
- **THEN** during the motion nothing is opened, stepped or sent by the outgoing screen, and after it only the screen on top reacts

#### Scenario: A second change takes over a running one
- **GIVEN** a change is half way
- **WHEN** another change or a Back starts
- **THEN** it continues from the current progress, without a jump and without queueing

### Requirement: Nothing else about an open agent changes
What an open agent shows and does SHALL stay as it was before this
change, apart from the requirements above: the steps on the title bar and
their haptics and bounce, the 400 ms wait after a step and input during
it, the switch between terminal and chat, the keyboard going down on a
switch to another terminal, the screen reader's texts and actions, and
the look of every screen at rest.

#### Scenario: A step still waits 400 ms
- **GIVEN** Ezra is in the first terminal of a tab of two panes
- **WHEN** he swipes to the next pane of the tab, and again leaves it before and at 400 ms
- **THEN** leaving before 400 ms attaches nothing and detaches nothing for it, and at 400 ms the next pane is attached, with the old one detached first, as today

#### Scenario: Input during the wait opens first
- **GIVEN** Ezra stepped to the next pane
- **WHEN** he types within the 400 ms
- **THEN** the pane is attached first and then receives the text

#### Scenario: The switch to the chat and back
- **WHEN** Ezra taps the chat icon and then the terminal icon
- **THEN** the chat shows, then the terminal, each with its motion, with the keyboard down

#### Scenario: The screens look the same
- **WHEN** the terminal, the chat, Changes and the overview are captured at rest in their existing golden tests
- **THEN** every golden matches
