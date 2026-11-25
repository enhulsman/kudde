# Phone testing

How an agent runs phone checks (the `4.x` tasks of a change) on its own. The
real names, the PIN and the network details are in `local/setup.md`.

## The phones

- **The test phone**: an old phone kept on wireless adb with a throwaway PIN,
  for agents to drive alone: unlock, tap through the app, lock-screen checks,
  forced Doze. It has no SIM.
- **The daily phone**: the person's own. Install there only when asked (owner
  profile only, see AGENTS.md), and never drive it with `scripts/phone.sh`.

What the test phone cannot check: Wi-Fi to mobile data (no SIM, and Wi-Fi off
ends wireless adb), a GrapheneOS-only behaviour (it runs stock Android), and
anything that needs root on the desk (a firewall rule: ask the person). A
battery cost is measured on it as with and without over several nights, after
the person unplugs it; Wi-Fi only, so a lower bound.

## Rules

- Never type into a real agent's terminal. Use the fake agent below.
- Never act on the test phone's other notifications. It still receives the
  person's own alerts, such as SSH approval requests: leave them alone.
- Undo what you force: `scripts/phone.sh doze off` after a Doze check.
- Install with `scripts/phone.sh install`: owner profile only.

## Driving it

`scripts/phone.sh` (its header lists the commands). Things learned the hard way:

- `ui` reads the screen through uiautomator, which fails while the app shows a
  spinner or other animation. Take a `shot` and read the image instead; tap by
  coordinates (`scripts/phone.sh input tap X Y`, screen pixels).
- The lock screen drops back to the always-on display within seconds, so
  `unlock` sends wake, swipe and PIN in one adb shell. A swipe that starts too
  low hits the lock-screen shortcuts.
- `adb shell input text " word"` loses the leading space.
- `note` prints the status notification; `alerts` lists the app's
  notifications. A notification swiped away leaves the live list but stays in
  dumpsys's "attention state" section.

## TalkBack speech

With kudde debug installed on the configured test phone, run:

```sh
scripts/talkback-phone.py
```

The runner uses Python 3, a JDK and the Android SDK tools already used for
the app. The test phone needs Google TalkBack in English, Android's `uinput`
tool and a connection from kudde to the local herdr-core with a desktop
layout. Use the same test phone configuration as `phone.sh`. Enable USB or
wireless debugging and leave the phone in portrait.

The runner creates two fake panes, opens the real TalkBack Actions menu,
and checks that `Next terminal` speaks the next pane's name and place once.
At the last pane the tab-end message must also be spoken once. Pass
`--reduced-motion` to run with the app's animation scale set to zero. It counts
distinct utterance IDs from TalkBack's speech log, including utterances split
into fragments. It exits nonzero on a missing or repeated reading.

The test briefly enables verbose TalkBack logging on the test phone and
keeps only the test result. It removes the helper and fake panes and restores
the phone settings when it exits, including after a failed check. Do not
use `phone.sh ui` during the run: its UI dump pauses TalkBack.

## A fake agent

`scripts/fake-agent.sh start` opens terminal `phonecheck` on the desk; `set
working|blocked|idle|error` changes what it reports. Working then idle makes it
"done" on the phone until opened there. Typing into it from the phone is
harmless: it echoes. `stop` removes it. On a desk with a Herdr app
layout kudde-push sends no alert for it (it alerts only for terminals in a
Herdr app pane), so check alerts there with a real agent's quiet "Finished"
alert, for example the checking agent's own turn ending. On a machine with no
Herdr app layout, `set blocked` gives a "Needs you" alert after the grace delay.

## A staged question (Claude Code)

The question card (chat-questions) needs a real Claude Code asking with its
question tool; the fake agent cannot. Stage a throwaway one of your own:

1. Write a settings file in a scratch directory that holds only the question
   hook (`docs/claude-code-question-hook.md`, the `PreToolUse` entry pointing
   at `contrib/claude-code/kudde-question-hook.sh`). Never add it to the
   person's own Claude Code settings.
2. Start Claude Code from a scratch directory with
   `--settings <that file> --permission-mode plan` through
   `herdr-core run --bg --no-parent`, with no `--name` (a terminal name
   shows instead of the session title). Title it with `/rename <title>`.
3. On the test phone, open its chat by name and check the title before any
   tap. Unset `HC_*` when sending to it from inside a terminal, so the text
   arrives as the phone sends it.
4. Ask it to use its question tool with a shape spelled out: header, text,
   options, single or multi-select, several questions in one call. While it
   waits, `herdr-core get` shows the status `blocked` with the kind
   `question`, and a record for the session appears under
   `${XDG_STATE_HOME:-~/.local/state}/kudde/questions/`.
5. Cases worth a round: a single choice; a preview with a note; three
   questions and the review step; multi-select with ticks and a typed answer;
   free text; Skip; no hook (start one without `--settings`); the desk
   answering while the card shows, and a phone tap just after the desk
   answers (`herdr-core keys` on the throwaway, then the tap): nothing may
   reach the next prompt.
6. Read the result from the throwaway's screen (`herdr-core read`) and the
   collapsed line on the phone. Crop the status bar from screenshots.
7. Afterwards: `herdr-core kill` and `rm` the throwaway, and delete its
   record files.

## Measuring on the desk

- **SSH logins**: `ss -tn sport = :<ssh port>` lists the phone's connections;
  a new remote port means a new login. Log it every few seconds in the
  background for a long check.
- **Terminal attach and detach**: `herdr-core get phonecheck` shows `cols` and
  `rows`; the phone's size while attached, the desk's again after the detach.
  Rows alone change when the keyboard opens or closes.
- **Cutting the phone's connection** (key-window checks): find the
  `sshd: <user>@notty` process with a `herdr-core connect` child that started
  when the phone connected, confirm the phone's remote port is unchanged, then
  kill that process. Never one that could be another phone's.
- **Doze**: `scripts/phone.sh doze on` with the screen off; `dumpsys deviceidle`
  shows `mState=IDLE`. A forced Doze on a charging phone stands in for a real
  night; the real night is the battery check.
- The key window is 30 minutes from the last unlock: a check that needs it
  closed waits 31 minutes with the phone locked.
- Broadcasts: `dumpsys activity broadcasts history` shows whether a broadcast
  reached the app's receiver (how the not-exported unlock receiver was found).
