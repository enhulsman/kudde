# kudde

Your coding agents, on your phone. kudde (Dutch for "herd") connects to
herdr-core on your own machines over SSH, lists every agent with the ones that
need you first, shows each as a live terminal or a chat, and lets you answer.
With the app closed, an encrypted alert tells you when an agent needs you or
has finished. Alerts go through your own ntfy server; no Google service is
involved.

kudde is an independent app built on [herdr-core](https://github.com/herdrdev/herdr-core).
It is not made by the herdr project.

<p align="center">
  <img src="docs/screenshots/agents.png" width="260" alt="The agent list on two machines: one agent needs you, two are done, two are working, one is idle">
  <img src="docs/screenshots/chat.png" width="260" alt="An agent as a chat: a screenshot and a question, the agent's tool calls folded into one line, and its formatted answer">
  <img src="docs/screenshots/terminal.png" width="260" alt="An agent's live terminal on the phone: Claude Code asks a question with three options, terminal keys below">
</p>
<p align="center">
  <img src="docs/screenshots/alert.png" width="480" alt="An alert: Plain output flag needs you, with where it sits at the desk and on which machine">
</p>
<p align="center"><sub>Agents on a desktop ("workstation") and a Raspberry Pi in one list, one as a chat, one as its live terminal, and the alert it sent.</sub></p>

<p align="center">
  <img src="docs/screenshots/spaces.png" width="260" alt="Spaces view with tabs and agents in desk order">
  <img src="docs/screenshots/tab-overview.png" width="260" alt="Tab overview with terminal previews">
</p>

**Status:** 1.0. Complete for what it sets out to do, used daily by its
author, tested on a few phones, built on herdr-core 0.1.x.

## What works today

- **Agents:** every agent on every machine in one list, "needs you" first, then
  finished (not yet seen), working and idle. Each goes by its session's name
  ("Pricing page copy") and shows where it sits at the desk ("website / 2").
- **New terminals and agents:** start a terminal, or an agent installed on that
  machine (Claude Code, Codex and the others herdr knows), in one of the desk's
  spaces. It opens on the phone and shows up on the desk as a new tab.
- **Live terminal:** the terminal as the desk draws it, with colours, zoom and
  scrollback. Opening it switches the terminal to the phone's size. Type at the
  desk and the desk gets its size back while the phone keeps watching; exit the
  terminal's screen on the phone and the desk gets it back too.
- **Typing:** the phone keyboard and two rows of terminal keys (esc, ctrl, alt,
  tab, arrows, ^C and more). A compose sheet takes long prompts with
  autocorrect and dictation, and pastes them without pressing enter.
- **Chat:** switch an agent (Claude Code or Codex) between its terminal and a
  chat of its conversation: your messages as bubbles, answers as formatted
  text, tool calls folded into a line you can open, images you can open full
  screen, and a working line while the agent works. Answer from a message
  box, photos included; `/` and `!` commands work as in the agent's own box.
  When the app cannot read an agent's log, the terminal is all you see.
- **Spaces:** Spaces shows your desk’s spaces, tabs and panes in order. Tap a
  tab’s map to see its panes. Swipe sideways on the title bar to move within
  the current tab. Changes is under More options.
- **Changes:** On an agent’s terminal and chat, Changes lists the files not
  committed yet in its repository, with lines added and removed, and shows
  each file's diff wrapped to the screen. It follows the agent as it works and
  only reads: nothing is staged, committed or discarded from the phone.
- **Alerts:** "Needs you" with sound, "Finished" silently, and a quiet warning
  when a machine has gone silent for 13 hours. Alerts name the session and
  where it sits. Tap an alert to open that agent's terminal. Mute per machine, and a test alert that checks the whole
  path.
- **Stay connected** (optional): keeps the connection open in the background,
  with a quiet notification that counts the agents that need you, have
  finished or are working, so the app opens at once. It uses more battery. On
  our test phone, with the screen off, it drew about 22 mA more on average
  (22 mAh an hour; phone batteries hold around 4000–5000 mAh). That phone was
  kept awake by other apps anyway: on a phone that would otherwise sleep, or
  with busier agents, it can be more. While the app is open the connection is
  there either way
  ([measurements](https://github.com/enhulsman/kudde/issues/16)). On
  GrapheneOS, the counts show on the lock screen only if it shows silent
  notifications.
- **Security:** the SSH key is made in the phone's secure hardware and never
  leaves it. It works for 30 minutes after you unlock the phone or use your
  fingerprint. You confirm each machine's host key once,
  and a changed key stops the connection.

## What you need

- An Android phone on Android 12 or newer (arm64), with a screen lock (PIN,
  pattern or password): the SSH key lives in the phone's secure hardware, which
  Android allows only then.
- herdr-core 0.1.15 or newer
  ([herdrdev/herdr-core](https://github.com/herdrdev/herdr-core)) on a Linux
  or WSL machine. Install it as the user your agents run under:
  `curl -fsSL https://releases.herdr.dev/core/install.sh | sh`. The `herdr`
  terminal tool is a different program; the app does not need it.
  Each release of the app is tested against herdr-core 0.1.15 and the newest
  herdr-core of the day, and has been used daily with every version from 0.1.15
  to 0.1.21. A newer herdr-core connects too; an older one is refused with a
  message to update to 0.1.15 or newer.
- An OpenSSH server on that machine the phone can reach (for example over
  Tailscale). Tailscale SSH does not work: it ignores the phone key. If the
  machine runs Tailscale SSH, use its LAN address, or an OpenSSH port
  Tailscale does not take over.
- For alerts: an ntfy server, the ntfy app on the phone, and `kudde-push` on
  each machine.
- To install and update: [Obtainium](https://github.com/ImranR98/Obtainium),
  or adb on a computer.

## Setting up

1. **Install the app** through Obtainium, or with adb if Android blocks
   Obtainium; see [docs/install.md](docs/install.md). Check the first install
   and each update ([how](docs/install.md#checking-a-release)).
2. **Open it.** The first time, a welcome screen explains the four steps, and
   setup stays in front until a real machine is connected: there is no skip.
   If the phone has no screen lock, the app asks you to set one first. Adding
   another machine later, or repairing one that stopped working, uses the same
   steps (Machines, "Add machine", or the machine's card).
3. **The four steps**, under the title "Set up a machine" with a bar that shows
   where you are:
   1. *Where is it?* The machine's address, user and port, and an optional
      name. The app says in plain words when it cannot reach the machine.
   2. *This phone's key.* Two ways to add the phone's public key to that
      machine. "kudde adds it for you" signs in once with the account's
      password, adds the key and signs out; the password is used for that one
      run and never stored. If you confirm a wrong host key there, whoever
      answered gets the password, and nothing in the app shows it, so compare
      the fingerprint the app shows with the machine's own
      (`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` prints it) before you
      trust it. "Add it yourself" gives you one command to run at the machine
      (copy or share it; it holds nothing secret), and then you compare the
      fingerprint it prints. Either way only the phone's public key is added,
      limited to running herdr-core: no shell session, no forwarding.
      herdr-core can still run commands as that user, so treat the key like a
      shell login.
   3. *Connected.* The app checks the machine, says which herdr-core it found
      and how many agents run there.
   4. *Alerts (optional).* Turn on alerts now or later; see
      [docs/kudde-push.md](docs/kudde-push.md) for `kudde-push` on the machine.
   The first connection starts herdr-core, so a card may show "Unreachable"
   once before it connects.
4. **A hint on the first terminal.** The first time a terminal or chat opens,
   a short note under the title bar names its gestures; tap it once and it
   does not come back.

### When setup stops

- The machine keeps its SSH host keys outside `/etc/ssh`, so the fingerprint
  command above prints another key than the one the app shows: compare with
  the key the server really offers (`sshd -T | grep -i hostkey`).
- The account's shell is fish. The command is for sh, bash and zsh: run it in
  one of those, or start `sh` first.
- The account's default file mode is group-writable (a `umask` of 002). sshd
  with `StrictModes` then refuses `~/.ssh` or `authorized_keys`; the command
  sets the modes it needs, but a home directory others can write to is still
  refused.
- The machine says it still refuses the key after the key was added. Run
  `scripts/check-restricted-key.sh` on the machine: it checks that the line is
  there, limited as intended, and that sshd reads that file.

"Finished" alerts are silent by default. To hear them, open Android settings,
go to the app's notifications, open "Finished", and set it to alerting.

## Roadmap

Version 1.0 completes the loop: a view of an agent's git changes, plus the
work that lets someone else set the app up without help.

What is planned and what is being worked on now are tracked in the open:
the [v1.0.0 milestone](https://github.com/enhulsman/kudde/milestone/1)
and the [roadmap board](https://github.com/users/enhulsman/projects/4). Ideas
and bug reports are welcome as issues.

**Not planned:** closing or killing agents, editing spaces and tabs, a file
browser, custom theme files, pasting images into the terminal.

## Repository

- `android/`: the app (Kotlin and Jetpack Compose).
- `crates/`: the Rust core the app uses (SSH, the herdr-core protocol, the
  terminal engine) and `kudde-push`.
- `docs/`: guides for installing, releases and `kudde-push`, phone
  checklists, release notes.
- `openspec/`: the specification, and `decisions.md` the decisions behind it.
- `design/`: the signed-off screen renders.
- `THIRD_PARTY_NOTICES.md`: licences of the bundled material.

## Licence

Apache License 2.0; see [LICENSE](LICENSE). Bundled fonts and other third-party
material keep their own licences, listed in `THIRD_PARTY_NOTICES.md`.
