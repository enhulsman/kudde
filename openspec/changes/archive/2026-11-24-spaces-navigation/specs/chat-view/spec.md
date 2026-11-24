## MODIFIED Requirements

### Requirement: The switch shows only while the chat can follow
The terminal screen's title bar SHALL show a switch, an icon button read as
"Chat" in the terminal and "Terminal" in the chat (capability spaces, "The
view switch is an icon"), while the terminal's lookup result is Found or
Empty (see the lookup requirement) and the log's health is Ok or Degraded.
It SHALL NOT show for a result of Missed or None, for a log whose health is
Broken when the terminal is opened, or on a machine that is not Linux. A
log that turns Broken while the chat is open SHALL keep the switch (see the
health guard). The terminal SHALL stay the first view: a terminal opened
for the first time shows the terminal.

#### Scenario: An agent with a readable log
- **WHEN** Ezra opens a terminal running Claude Code whose session file and log are found
- **THEN** the terminal shows, the title bar shows the switch with the chat's icon, read as "Chat", and a tap on it shows the conversation

#### Scenario: A plain shell
- **WHEN** Ezra opens a terminal with only a shell in front
- **THEN** no switch is shown

#### Scenario: A Broken log on opening
- **WHEN** Ezra opens a terminal whose agent's log the health guard finds Broken
- **THEN** the terminal shows and there is no switch
