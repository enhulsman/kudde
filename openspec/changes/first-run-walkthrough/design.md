# Design: a clearer first setup

## Context

What a new person meets today, in order (all read 2026-11-20):

1. **The home, empty.** "No machines yet", "Add a machine that runs
   herdr-core to see its agents here." and "Add machine"
   (`AgentsScreen.kt:575-581`, `strings.xml:29,66-67`). Nothing says what is
   needed first. On a phone without a screen lock the first screen is an
   error instead: "Could not start the connections" with the key store's
   own message (`AgentsModel.kt:125-127`, `HubHost.kt:53-64`,
   `KeyManager.kt:51-60`).
2. **The form.** Name, Host, Port (22), User, no hints, one note about
   Tailscale SSH, "Add machine" (`AddMachineScreen.kt:86-146`,
   `strings.xml:153-162`). After the tap: "Connecting to host:port…". A
   machine that cannot be reached sends the person to the Machines screen
   (`MachineFlows.kt:142-143`), where the card reads "Unreachable" with the
   SSH library's own words and a countdown (`MachinesModel.kt:160-167`); a
   wrong address waits out a 20 second timeout (`link.rs:21`). The address
   cannot be corrected: the hub has `update_machine` (`hub/mod.rs:796`),
   and no screen calls it, so a typo means "Remove machine" and a new one.
3. **The host key.** "Trust this machine?" with the fingerprint (render 8,
   `HostKeyViews.kt:62-76`). It says to check the fingerprint and not how;
   the command is only in the README (`README.md:98-100`).
4. **The detour.** After "Trust and connect" the Machines screen shows
   "Phone key not accepted · retrying in 1 s" with a row "Add the phone
   key" (`MachinesModel.kt:180-183`). That row opens the line with "Copy"
   (`PhoneKeyScreen.kt:73-93`). How the line gets to the machine and which
   command appends it is again only in the README (`README.md:101-104`).
   The app then waits for a retry that backs off to 30 seconds
   (`hub/mod.rs:1101-1105`); the card has no "Try again" for this state.
5. **Connected.** The card reads "Connected · herdr-core x.y.z". Nothing
   leads back to the home; there, "No agents yet" has no action
   (`AgentsScreen.kt:591-594`), and a machine that still waits for the
   person shows only as a ring on the Machines button
   (`AgentsModel.kt:124,136-140,170-171`).

So the strict path works, but it is told as a failure to repair, and two of
its three desk actions are explained outside the app. Herdr's own app says
what is needed on one screen before asking for anything; that is the part
to learn from. Its unrestricted key installation is not. The newer reference confirms the
host key before the password, matching the chosen order (see
`research/first-run-walkthrough-2026-11.md`).

Settled and unchanged here: the host key is confirmed before first trust
and before any signature (`ssh.rs:267-268,286-288`), the phone key lives in
secure hardware and is installed as a `restrict,command=` line
(`ssh.rs:137-155`), and the app never handles a password, apart from the
one sign-in of D12, which the person chooses.

## Goals / Non-Goals

**Goals:**
- Before anything is asked: what setup needs, in four points.
- The strict path as three forward steps, with the two desk actions in one
  command and nothing explained only in the README, then alerts as a fourth
  step that never blocks.
- Every way setup stops says what happened and what to do, on the step.
- Setup ends on the first agent, or on the way to start one.

**Non-Goals:**
- Any change to what is trusted or signed, or to the key's restrictions.
- A demo mode, pairing by QR code, finding machines on the network
  (decisions 3 and 7 of the proposal).
- The local network permission (D9).

## Decisions

### D1. First-start welcome, then full-screen setup

Owner's answers of 2026-11-22 supersede the empty-home proposal and settle
decision 12: there is no way out before a real machine is connected. First
start shows a separate full screen before home, Machines or incoming
navigation: kudde's existing ear-tag mark, WL1 headline, WL2 one-line description,
WL4 to WL6 and WL13 as four numbered steps, WL7 as the one main button and WL9 as a help
link if approved. No skip action. No demo button: decision 3 remains open;
any future demo entered from welcome must lead back into setup.

**Forced:** on every start before a real machine connects, show the welcome
if setup has not started, otherwise resume the saved full-screen setup step.
The welcome has no back arrow, and Android Back there closes the app, not
setup. Every step has a back arrow (owner's answer, review part 2: an arrow
that closes the app would surprise). Back closes a dialog or key-install detail
first, then goes one step back: lock notice to welcome, step 1 to welcome (to
the opener for a later machine), step 2 to step 1 with its fields, step 3 to
the home (connected, so setup is done), step 4 to step 3. Returning resumes
where the person left it, the welcome included.
A tapped alert cannot bypass setup. Only a real machine reaching Connected
at step 3 completes first setup; listing, importing, confirming trust or
installing a key alone does not. Normal navigation applies after completion.

Persist app-private first-setup progress (welcome, setup, completed) and a
non-secret checkpoint: machine identifier if listed, furthest step, selected
key-install choice, entry point and unsent form fields. Never persist a
password, refusal counters or an in-flight password session. Reopening a
password choice returns to confirmation/probe with an empty field (D12).
Clear the unfinished checkpoint on successful completion or explicit machine
removal. An update with no progress record may initialize completed only
from a machine's recorded successful connection; a merely listed machine
still needs setup. Removing the last machine after completion does not repeat
the once-only welcome: home uses WL11/WL12 and WL7. Clearing app data starts over.
Preferences are excluded from backup/transfer, like the hint setting.
The machine store, trusted keys and exported list remain unchanged.

The welcome requests no permission and starts no connection. A phone without
a lock sees it first; its main action opens the screen-lock notice before
step 1. Neither a lock failure nor a connection failure offers a bypass.

### D2. Address first, then the phone key

Order: where the machine is, then the phone's key, then connected. Each
step is its own full screen. Step 2 shows two choices together: "kudde adds
it for you" first, "Add it yourself" second, each one tappable card, stacked
in that order at every size (the first filled, the second plain). Both
remain available from either choice, clearing any password on switching.
The manual choice opens the command detail within step 2; the password
choice opens host confirmation then the password detail within step 2. Reaching the
machine first costs nothing in strictness: the first connection stops
after the host key and signs nothing (`ssh.rs:286-288`). It buys three
things. Wrong addresses, a VPN that is off and a closed port show up
before the person walks to the desk. The desk step can name the machine
and the user. And the app knows which host key type the machine presents,
so the command prints that one key.
*Over* key first: the command would have to print every host key, and an
unreachable machine would be found only after the desk visit.

### D3. One command, a readable one

Step 2's command, for a machine that presented an ED25519 key (the long
middle line is the app's line as `authorized_keys_line` builds it today):

```sh
(
  umask 077
  mkdir -p ~/.ssh && chmod 700 ~/.ssh &&
  touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys &&
  cat >> ~/.ssh/authorized_keys <<'KUDDE_KEY' &&

restrict,command="sh -c '…'" ecdsa-sha2-nistp256 AAAA… kudde
KUDDE_KEY
  ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
)
```

- One POSIX sh subshell command; also pasteable in bash and zsh on Linux
  and macOS. Uses only `umask`, `mkdir -p`, `chmod`, `touch`, `cat` and
  `ssh-keygen`; no GNU-only flags.
- Short on purpose (owner's answer, review part 3: the earlier version,
  about 25 lines with ownership and file-type checks and two messages of
  its own, felt daunting to paste). The checks bought clearer errors in
  rare setups (a `~/.ssh` owned by someone else or not a directory), not
  safety: sshd's StrictModes refuses those anyway, the shell's own error
  ("Permission denied") shows, and the setup guide's check script explains
  it. No `sudo` or `chown`; ownership is never changed. A `~/.ssh` that is
  a link to a directory the user owns works.
- `~/.ssh` is set to 700 and `authorized_keys` to 600 before appending, so
  an existing 0775 directory and an owner-owned 0400 file both work. All
  steps are chained with `&&`: any failure exits nonzero before the
  fingerprint prints.
- The quoted here-document passes the generated restricted line through
  unchanged. The leading blank line separates an existing unterminated
  key. Repeating the command may append another identical phone line;
  other key bytes remain untouched.
- The host file follows the presented algorithm: `ed25519`, `ecdsa`,
  `rsa`. For other algorithms replace the fingerprint invocation with:
  `for key_file in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf "$key_file" || exit 1; done`.
  Any fingerprint error exits nonzero. The Phone key variant omits only
  that invocation; the checked installation block remains identical.
- The displayed key above is abbreviated for this design. The generated
  command contains the complete `authorized_keys_line`. Reject a comment
  containing CR/LF and choose/check a delimiter absent as a whole line in
  that output (including the closing subshell syntax in command tests).
- Scratch results and the exact verification boundary are recorded in
  `research/first-run-walkthrough-2026-11.md`, section 6. macOS execution
  and StrictModes authentication remain builder checks, not claims from
  this spec-only run.

Built in Rust beside the line (`ssh.rs`), as
`setup_command(spki, comment, host_key_algorithm: Option<String>)`, and
exported like `authorized_keys_line`. Tests take the line back out of the
command and compare it with the generated line, and the throwaway-sshd
suite (`crates/kudde-mobile/tests/sshd.rs`) runs the command through each
shell before connecting.

Considered:
- *A base64 one-liner* (`echo … | base64 -d >> …`): survives any app that
  rewrites quotes, and works in fish. Rejected as the default because the
  person cannot read what they install, which is the point of showing a
  restricted line. Kept in mind if quote damage turns up in use.
- *`echo '…' >>` with escaped quotes*, as Herdr's app does for its plain
  key: one line, but the restricted line holds both kinds of quotes, and
  the escaping is unreadable.
- *The app signs in with a password once and installs the line* (Termius,
  JuiceSSH, Herdr's app since 1.0.6): no desk visit. First rejected here;
  added on 2026-11-20 at the owner's request as a second way beside the
  command, which stays (D12).
- *A helper on the machine that shows a QR code* (Whip, Moshi, Sherpr):
  the strongest check, since no eye compares anything. It needs a program
  on the machine, the camera and a scanner. Its own change if wanted.

Known limits: fish has no here-documents (the step says "For sh, bash and
zsh."); an `sshd` with host keys outside `/etc/ssh` prints nothing to
compare, which the README covers.

### D4. Trust stays render 8's dialog

"Compare the fingerprint" opens the signed-off confirm, with two changes
to its text: the first paragraph says the command printed the host key,
and a note says what a different fingerprint means. A deliberate dialog
for the one act that cannot be undone by a retry fits the strict path, and
it needs no new look. Every way to that dialog now passes step 2, so the
command is always one "Cancel" away (`MachineFlows.openHostKey` opens step
2 for `HostKeyUnconfirmed`; a changed key keeps its own screen).
*Over* the fingerprint inline on step 2: clearer by one tap, but a second
presentation of the same act and a retired render.
Not proposed: typing the last characters of the fingerprint, which would
force the comparison; it is friction the owner did not ask for, and the
QR helper is the better form of that idea.

### D5. The hub says which kind of failure

`MachineState::Unreachable { detail }` gains `kind: ReachKind`
(`NoAnswer`, `Refused`, `NameNotFound`, `NoRoute`, `Other`,
`CoreStartupClosed`). Socket failure kinds are set where the
SSH error is mapped (`ssh.rs:223-237`, `link.rs:93-102,107-126`):

| Kind | From |
|---|---|
| `NoAnswer` | the dial timeout, `io::ErrorKind::TimedOut` |
| `Refused` | `ConnectionRefused` |
| `NameNotFound` | a failed lookup (how tokio reports it on Android is to be pinned by a test with a `.invalid` name) |
| `NoRoute` | `HostUnreachable`, `NetworkUnreachable` |
| `Other` | everything else, with `detail` as today |
| `CoreStartupClosed` | the narrow authenticated, pre-hello command close in D6; created in the hub, not mapped from a socket error |

`detail` stays for `Other` and for logs. One function turns a kind into
its two lines of text; the setup step and the machine card both call it.
The home's notice line keeps today's "unreachable" (render 1). `CoreStartupClosed` uses Other's reason wording when the grace flag is false. A failure after the connection was up (`hub/mod.rs`
1284-1299) is `Other`.

### D6. Shared setup, with a resumable screen for each step

Machines > Add machine opens step 1 directly, without welcome. A card with
"Phone key not accepted" offers "kudde adds it for you" then "Add it yourself",
each opening the corresponding detail in the same full-screen step 2 for
that listed machine. This includes previously connected and imported
machines. Do not add a duplicate or discard a confirmed pin. Both choices
are visible together on the card and on step 2 (the password choice is
unavailable for the rest of a visit after R16), with the same large-text
stacking rule as D2. Switching choice clears the password. A changed host
key goes through the existing mismatch/forget flow before any password.
The success and no-password fallback are the same as in first setup.


`Screen.AddMachine` and `Screen.PhoneKey` become `Screen.Setup`. The step
is worked out from the machine, and only ever moves forward by itself:

- no machine named: step 1;
- a machine with no confirmed host key (`MachineView.host_key`) that is
  not presenting one (`HostKeyUnconfirmed`): step 1, with its address,
  user and port filled in;
- connected: step 3, held until the person leaves it, so a connection
  that drops while it shows does not bounce them back; step 4 is reached
  only by "Continue" on step 3, never worked out from state (D13);
- anything else: step 2.

A saved furthest step takes precedence over these initial-state defaults.
The flow keeps the furthest step it has shown for the machine, so the
"connecting" moments after a return from the background stay on step 2.
Keep screen, machine, step, entry point, selected choice and typed fields
in the D1 checkpoint as well as saved-instance state, so cold launches and
process death resume too. Keep whether this visit added the machine for the
Back cleanup on step 1, which removes a machine this visit added that was
never reached and keeps the typed fields, in first setup and later additions
alike. Back lowers the saved step to the screen it opens (step 2 to step 1,
step 1 to the welcome), so the furthest-step rule never pushes the person
forward past a Back. An alert cannot
bypass first setup; after completion it may open its agent without losing
a later machine's setup.

Only the first *eligible startup close* after explicit host confirmation
is held back. It is a candidate startup race, not proof that the core was
starting. Add `ReachKind::CoreStartupClosed` only for a closed stream before
hello after successful SSH authentication, with no nonzero exit status,
no nonblank diagnostic stderr and no missing-core marker. The existing
`command_ended` timeout/no exit result qualifies; exit 127 or the missing
marker stays `NotInstalled`, and other diagnostic exits stay `Other`.
Network/dial errors and handshake parse errors never qualify.

The hub owns the one-attempt grace, not a UI flag. On explicit confirmation,
arm only the first attempt of the newly spawned epoch. At its settlement,
set a new `MachineView.startup_retry_pending` boolean only if the outcome
is `CoreStartupClosed` and a retry was scheduled. Consume the allowance
regardless of that first outcome. Keep the boolean through the scheduled
retry's `attempting` call (which retains the old failure and clears
`next_retry_ms`); clear it atomically at the next settlement, even if the
state and detail repeat. Old-epoch outcomes cannot alter it. Clear it on
pause, restart, manual retry or connection; do not persist it or arm it
on resume/process restore. It defaults false in bindings and test fixtures.
No UI inference from error strings, deadlines, `Connecting`, elapsed time
or number of snapshots. Skipped/repeated snapshots cannot renew grace.
`NetworkOff`, `HostKeyMismatch`, auth, key rejection, missing core and
version failures always show immediately; mismatch uses the existing
changed-key screen and confirmed forget flow.

"Try again" calls the hub's `retry`. Host and port unchanged means the
confirmed pin is preserved, even with `lastConnectedMs == null`. Remove/add
is allowed only for an unpinned, never-connected machine, to follow an edited
address with its derived name. If pinned or previously connected, call
`update_machine`, keep the listed name and lock Name; User remains editable.
The existing `machines.rs:227-231` clears the pin for host/port changes.
No new rename operation or stored-data migration is needed. Test a pin
confirmed before `KeyRejected` and before `NotInstalled`: editing User keeps
A and a subsequent B must still be a mismatch. The locked Name is tested
in each case, not treated as permission to remove/add.

Name: `validateMachine` gains the derived name (text before the first dot
of a host name; the whole text for an IPv4 or IPv6 address), checked
against the same rules and reported on the Name field.

### D7. The screen lock check

`isDeviceSecure` is read before step 1 and on every return. D1 welcome
precedes the notice on first launch; home after first setup completes keeps the notice too.
False wins over the hub's start error (which, with no lock, is the key
store's "Secure lock screen must be enabled…"). The action sends
`ACTION_BIOMETRIC_ENROLL` with device credential allowed (API 30, which
also offers a PIN), then `ACTION_SECURITY_SETTINGS`, then
`ACTION_SETTINGS`. `HubHost.ensureStarted` already runs on each return
(`HubHost.kt:78-97`).

Not in this change, recorded for an issue: removing the screen lock later
destroys the key for good (`AndroidKeystoreBackend.kt:65`), and the app
then says only "the phone key can no longer be used".

### D8. Copy and Share

Copy as today (`PhoneKeyScreen.kt:110-114`): the button reads "Copied",
no toast, the clip not sensitive. Share: `ACTION_SEND`, `text/plain`,
`EXTRA_TEXT` the command, `EXTRA_TITLE` "kudde setup command",
`Intent.createChooser`. No new permission, no new dependency.

### D9. The local network permission is not in this change

Android 17's `ACCESS_LOCAL_NETWORK` applies to apps that target SDK 37.
kudde targets 36 (`android/app/build.gradle.kts:24`), keeps its access
through `INTERNET`, and per Android's guide must not declare or request
the permission yet. Herdr's app met the prompt because it targets the
newer SDK. So nothing can be built or tested for it now. One phone check
confirms it (tasks 6.3).

It becomes real with the move to target 37, as its own change. For that
change, already known: no dialog appears by itself, a blocked TCP
connection just times out, VPN traffic is said to be exempt (not tested on
a phone), and the request belongs on "Continue" in step 1 for a
local-range address, with this reason shown first: "Android asks before an
app may reach devices on your Wi-Fi. kudde uses this only to connect to
the machines you add." After a denial the step should name the permission
at once and not run a 20 second attempt.

### D10. One link out

"Setup guide" on the welcome, and under "herdr-core was not found", opens
the repository's README at "Setting up" in the browser, and kudde.dev's
Setup page once the site (#83) serves one. One constant. The app has no
other link today; this one is decision 6 of the proposal.

### D11. The title bar explains itself once, in a hint

Answered on 2026-11-20 (Ezra, proposal decision 9, option c, not the
pick): no screen in setup. The first time a terminal or its chat opens, a
short hint under the title bar names the bar's gestures. They are defined
by the change `spaces-navigation` (its decision 1): a sideways swipe walks
the panes of the open tab and bounces at the tab's ends; up and down walk
the agents that need the person (agent-list); a tap on the tab's map opens
the tab overview. The hint explains those three and nothing else, is built
only after they are on main, and changes its words if they change.

- **When.** Keep the map-visible rule of the 2026-11-20 answer. On
  refactor/screen-stack, spaces-navigation 8.2 (2026-11-21) now gives a
  terminal alone in its tab a one-cell map whose tap opens the overview.
  Therefore show on the first mapped terminal or chat, including one pane;
  no pane means no map and leaves it due. This is the revised pick for
  decision 11; the multi-pane delay is no longer justified by map absence.
  G2 explicitly says a one-pane tab only bounces sideways; up/down still
  follows the needs-you list and G4 still opens that tab's overview.
- **Where.** A block directly under the title bar, the screen's width less
  its margins. It covers the terminal's or the chat's top rows only, never
  the bottom where the keys, the compose sheet and the chat's message box
  are; it does not open or close the keyboard and takes no input focus.
  It never covers the terminal's cursor row, whatever decision 11 says: a
  fresh prompt sits in the top rows, so when the cursor row falls under
  the block, the block sits just above the keys instead, and it moves
  again if the cursor moves under it. Touches outside it reach the screen
  as usual, so the gestures it names work while it shows, and typing goes
  to the terminal.
- **Closing.** A tap anywhere on it closes it and sets the flag; it never
  shows again, in the terminal or the chat. Leaving the screen without
  that tap leaves it due, so it shows on the next open.
- **The flag.** `TitleBarHintSetting`, built like `ChatViewSetting`
  (`ui/chat/ChatViewSetting.kt`) and `StayConnectedSetting`: its own
  app-private preferences file `title_bar_hint`, one boolean `seen`; an
  absent value or one of another type reads as false; written with
  `apply()`; one instance on `KuddeApp` beside the others
  (`HerdrApp.kt:127-129`). The data extraction rules already exclude every
  preferences file from backup and transfer, so a new phone shows the hint
  once more. Nothing in the app resets it; clearing the app's data does.
  People who update from a version without it see it once, which is
  wanted: the gestures are new to them too.
- **TalkBack.** With touch exploration on, swipes belong to TalkBack, so
  the hint names the title bar's actions that `spaces-navigation` gives
  for them ("Every step has a way without a swipe": "Next terminal",
  "Previous terminal", "Next agent that needs you", "Previous agent that
  needs you") and the map, as G6, in place of G2 to G4. One element with
  the hint's whole text, read once when it appears (announced politely,
  not again while it shows), focusable in the reading order right after
  the title bar; its action is named "Got it" and a double tap closes it
  like a tap. Focus is not moved to it.
- **Animations.** With the system's animations off
  (`ANIMATOR_DURATION_SCALE` 0, read as `AgentsRoute.kt:53` and
  `ChatRoute.kt:139` already do), it appears and goes at once; otherwise
  it fades in and out over the app's short duration. It does not act out
  the bounce.

*Over* the screen after step 3 (the earlier pick): the owner's answer; the
hint sits where the gestures are used, at the cost of one stored flag.

### D12. kudde can run the setup itself, with the password once

Added on 2026-11-20 at the owner's request (Herdr's own app offers this):
step 2 has two choices together, password-once first. kudde signs in once with the
account's password, does exactly what the command does, signs out and
forgets the password; from then on the phone key connects as for everyone
else. The command lives in "Add it yourself", always one choice away, including
from the password detail and when a machine takes no password.

**The trade-off.** With the command nothing secret crosses the network,
and the fingerprint to compare is on the desk's own screen. With this way
the password goes to whatever answers at the address. If that is not the
desk (a hostile network, a spoofed name), whoever answered has the
account's password: a full login, unlike the phone key, which runs only
herdr-core. So the host key is settled before anything is sent, and the
password goes only to that key. Proposal decision 10, answered (a): the
confirm of render 8 with this way's words shows the fingerprint and the
one line that prints it at the desk, for whoever wants to compare;
confirming is trust on first use, and the password field shows after it.

**A wrong host key fails silently.** An impostor that relays to the real
desk can use the password there to install the phone key itself; the
phone key then connects, step 3 shows, and nothing in the app looks
wrong. Only a comparison at the desk catches it. So R3 warns rather than
reassures, and the README says the same (5.1).

**The steps.**

1. "kudde adds it for you" (R1) opens the confirm (R3 to R5) for a
   machine whose host key is not confirmed yet, or goes straight to 2
   when it is. Confirming is the same `confirm_host_key` as "Trust and
   connect", so the hub starts its phone-key attempt; its expected first
   outcome, the key not accepted, shows as this panel, not as K1. Every
   other outcome shows as D6 says, and a connection (the key was there
   already) opens step 3 and drops the panel. "Cancel" trusts nothing and
   sends nothing.
2. A probe connection, pinned to the confirmed fingerprint, asks with the
   `none` method and reads the methods the machine offers from its
   refusal (russh 0.65.2 `Handle::authenticate_none`, `AuthResult::Failure
   { remaining_methods, .. }`). No `password` and no `keyboard-interactive`
   (or a `none` that succeeds): "takes no password" (R10) with "Add it yourself" available, and no field. With `keyboard-interactive` only, the probe
   starts it and reads the first prompt's text, which becomes the field's
   label after R6 (a machine whose first prompt asks for a code shows
   that). A probe that meets another host key ends during key exchange
   and shows the changed-key screen. Otherwise it disconnects and the
   field shows.
3. "Add the key" dials again, pinned. `HostKeyCheck::check_server_key`
   (`ssh.rs:519-533`) refuses any other key during key exchange, before
   any authentication message, so a changed key sends neither password
   nor user name and shows the changed-key screen. It signs in with
   `password` when offered, else `keyboard-interactive`. The password is
   sent at most once across all rounds: the first round's one prompt that
   does not echo gets it, empty rounds get nothing, at most three rounds.
   Any non-empty prompt after the password was sent (a code from a
   second-factor module), a prompt that echoes, more than one prompt, or
   `partial_success` stops: R11. A refusal counts toward a limit per visit
   to step 2: after the second, the text is R15, which names lockouts
   (`pam_faillock`, fail2ban) and points to the command; "Add the key"
   stays for a typo, nothing retries by itself.
4. On the session: one `exec` of `sh -s`, the script written to its
   standard input, then end of input. The script is `setup_command(spki,
   comment, None)` byte for byte: the Phone key variant of D3, whose
   installation block is identical and which leaves out only the
   fingerprint line. So 2.3's shell tests cover what runs, and the line is
   the same restricted line. Through `sh -s`, the account's login shell
   (fish too) only starts sh. The script is one parenthesised command, so
   sh reads all of it before running any of it: a cut-off transfer is a
   syntax error and changes nothing. Exit 0 is done; any other exit shows
   its code (R14). Standard error is never shown: the command prints no
   messages of its own, and shell start-up files may print anything. Then the session
   disconnects. It runs once per tap, with no automatic retry, within 20
   seconds to dial (as `link.rs:21`) and 60 in all; running out of time
   after signing in reads as R12. The session asks for nothing but that
   one `exec`: no pty, no environment variables, no agent or X11
   forwarding, no port forwarding.
5. Done: the app calls the hub's `retry`, the step reads "Connecting to
   …" and moves on to step 3 as after the command. Exit 0 does not prove
   sshd reads that file (another `AuthorizedKeysFile`, StrictModes
   refusing the home directory's modes, a `ForceCommand`): when the phone
   key is still refused after a done run, step 2 shows R16, with "Try
   again" and "Add it yourself", and never offers the password again in that
   visit.

| Outcome | Step 2 shows |
|---|---|
| Not reached before signing in | the reach failure of D5, with "Try again" |
| Another host key | the changed-key screen, nothing sent |
| Wrong password | R9, R15 from the second refusal; field empty, "Add the key" again |
| No password method | R10, no field |
| More than a password | R11 |
| Dropped or out of time after signing in | R12: whether the key went in is not known; repeating preserves other keys (D3) |
| The script ended with another code | R14 |
| Done | "Connecting to …", then step 3 |
| Done, but the phone key is still refused | R16, never the password again |

**The password.** Never stored, logged, copied or saved by Android:
- the field is Compose Foundation's `BasicSecureTextField`: masked with
  `TextObfuscationMode.Hidden` (not even the last character shows), cut
  and copy disabled, the password keyboard, no reveal button. While the
  panel shows the window has `FLAG_SECURE`, cleared when it closes, so no
  screenshot or screen recording holds the field. Its state
  is `remember { TextFieldState() }`, never `rememberTextFieldState()` or
  `rememberSaveable`, which keep text in the saved instance state; after
  a process death the field is empty;
- autofill is off for it: the hosting view gets `importantForAutofill =
  IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` while the panel shows
  (restored after), and `AutofillManager.cancel()` runs when it closes,
  so no password manager fills it or offers to save it; the field's
  semantics also carry the password content type, for Compose's own
  autofill path (low confidence that it matters; 6.5 is the proof);
- the text is cleared (`clearText()`) when "Add the key" starts the call,
  when the panel closes and when step 2 is left. JVM strings cannot be
  wiped, so the honest promise is: kudde keeps no reference after the
  call;
- in Rust it is a type whose `Debug` prints `<redacted>`, with no
  `Display`, never part of an `SshError` detail, wiped on drop with
  `zeroize` (1.9.1, already in `Cargo.lock` through russh, now a direct
  dependency). russh zeroizes the password method on drop and redacts it
  in `Debug` (0.65.2 `src/auth.rs`, `impl Drop for Method`). Not wiped,
  said plainly: the uniffi `RustBuffer` that carries it across, and the
  keyboard-interactive answers, which russh sends as a plain
  `Vec<String>`. kudde-mobile installs no logger, so russh's `log` output
  goes nowhere;
- the app has no crash reporter; Recents keeps no picture on Android 13+
  (`MainActivity.kt:40`).

Built as `host_key_fingerprint_command(algorithm)` (the one line R4 shows,
also used inside `setup_command`, so one source) and
`setup_with_password(host, port, user, pinned, password, spki, comment)`
plus the probe, exported over uniffi. The phone key signs nothing here.

### D13. Step 4 offers alerts, and never blocks

Owner's answer of 2026-11-22: setting up alerts becomes a fourth step after
Connected (it was a non-goal before). What it builds on is already there:
the machine card's Alerts row (`MachinesModel.kt`, `AlertSetup` Off,
Waiting, NoDistributor, Failed; not allowed; registered; the test alert
through `kudde-push`), the permission request at alert setup
(android-client-v1 `alerts`), and registration on every connected machine
once alerts are on. Step 4 is that row's flow on a full screen, with words
for someone who has not met UnifiedPush.

Picks for the owner (drafts, part of decision 8's review; confidence
moderate that each is what he wants):

1. **Step 4 never blocks.** First setup is complete at step 3 (D1,
   decision 12 unchanged). Step 4 always shows the routes on (C4, or C6 and
   C7), which act as "not now"; there is no separate skip button. *Over* a
   "Not now" button: one more button, and the routes are needed anyway.
2. **Step 3 ends with "Continue".** The routes to agents move to step 4, so
   setup still ends on the first agent or the way to start one. *Over*
   keeping them on step 3 too: two screens offering the same exit.
3. **The test goes by itself once.** After "Turn on alerts" and a written
   registration, kudde sends one test alert without another tap: the
   person asked for alerts, and the test proves the whole path, kudde-push
   included. When alerts were already on (a later machine), the step offers
   "Send test alert" and sends nothing unasked.
4. **Not resumed.** Step 4 is not in the checkpoint; after a process death
   the home opens, and the card's Alerts row holds the same state. Back on
   step 4 returns to step 3.
5. **The welcome lists four steps** (WL3, WL6 reworded, WL13 "(optional)"), so it does not promise three and show four.
6. **No store link for ntfy.** D10 keeps one link out (the setup guide);
   AL3 and AL8 name ntfy and the guide covers installing it and kudde-push.

The step counter is a four-segment bar, read as "Step n of 4" on every step.
The hub needs nothing
new: registration and the test request already go through the `kudde-push`
document.

### D14. The look of setup: a step bar, choice cards, a checklist, one earned moment

Decided by the owner on 2026-11-25 from two drawn directions (reports 049):
everything from the warmer one except its bar weight.

- **Step bar** (replaces "Step n of 4"): four segments, 3 dp high, the current
  one 2.2 times as wide, done and current in fg, the rest in border. One
  element for TalkBack, "Step n of 4".
- **Heading** 27 sp on setup steps; **choice cards** radius 20, stacked at every
  size, the first filled with the primary button's colours, the second plain;
  no second colour anywhere.
- **Checklist** replaces R8: S1 to S3, a tick in the connected colour when done,
  a turning ring while working, a cross in the blocked colour on the step that
  failed with the outcome text under it; a polite live region.
- **Earned moment** on step 3: a tick disc in a soft ring of the connected
  colour, once, with S4 under C2 for the first machine only.
- **Motion** is small and never loops except the working ring: the current bar
  segment sweeps in (200 ms), a tick draws (180 ms), the success disc pops once
  (250 ms). With animations off nothing moves.
- **Token**: `successHalo` (connected at 18%) in `design-tokens.json`, both
  themes; every other value is an existing token. The connected colour now also
  fills ticks and the success disc, in first setup only.

## New and changed screens (each needs the owner's sign-off)

All use existing tokens, Geist, chrome background, buttons, MonoBox and
SubScreenTopBar. The welcome uses the existing ear-tag mark; no new mark or
illustration. These are planned renders, not images drawn by this spec task.
Numeric labels below are legacy references only: 13 to 23 are taken by Changes;
use the stable ids below when drawing, leaving existing renders untouched.

| Status / legacy reference | Stable id | Screen and composition |
|---|---|---|
| Changed plan 13 | first-run-welcome | Full screen, existing mark, headline, one line, four numbered steps, one main WL7 button; WL9 help link, no skip action |
| Changed plan 14 | first-run-lock | Full-screen lock notice after welcome, without a bypass; home notice variant after completion |
| Changed plan 15 | first-run-address | Full-screen step 1, ordered fields, waiting and reach failures |
| New (replaces old 20) | first-run-key-choices | Full-screen step 2 with heading, machine/user, two stacked choice cards R1/R17, R2/R18 descriptions, R19 restriction note; large-text variant |
| Changed plan 16 | first-run-manual | Manual detail within full-screen step 2, command, Copy/Share, restriction notes, compare button, both choices |
| Changed plan 17 | first-run-key-refused | Shared step 2 after rejection, both choices password-once first, retry; R16 variant after installation |
| Changed plan 18 | first-run-connected | Full-screen step 3, machine/version, count, Continue |
| New | first-run-alerts | Full-screen step 4: what alerts need, Turn on alerts, the test's waiting and result lines, each stop with its action; the routes on (agents/start/Done) always shown; the already-on variant with Send test alert |
| Changed existing 8 | first-run-trust | Existing trust dialog with manual wording D1/D2 |
| Changed existing 1 | first-run-home | Home with unfinished setup notice; empty-home after first connection WL11/WL12 variant |
| Changed plan 19 | first-run-hint | Terminal and chat, one-cell and multi-cell maps, G2 explaining one-pane bounce; TalkBack G6, cursor and input uncovered |
| Changed plan 21 | first-run-password-trust | Trust dialog with R3/R4 and fingerprint command, R5 before any password field |
| Changed plan 22 | first-run-password | Full-screen step 2 password detail, R6/R7/Cancel, the checklist (S1 to S3) and R9 to R16 outcomes on the step that failed, manual choice available |
| New | machines-key-choices | Listed rejected-key card with both choices in order, Add machine entry; previously connected and imported variants |
| Gone | old welcome-on-home / old 20 | No welcome block repeated on empty home; no command-first screen with password action beneath it; no Getting around setup screen |

Goldens in graphite and paper; welcome, both choice layouts, all four steps,
password detail and hint at 2x text. Each changed or new render needs owner
sign-off before production screens. No signed-off checkpoint changes here.

## Texts (approved by the owner, review parts 1 to 5, 2026-11-22)

`%1$s` and the like are filled in by the app. "Was" names the string it
replaces in `strings.xml`.

**Welcome and home**

| Id | Text | Was |
|---|---|---|
| WL1 | Set up your first machine | No machines yet |
| WL2 | See which agents need you, read their work and reply on your own machines. | Add a machine that runs herdr-core to see its agents here. |
| WL3 | Set up in four steps | new (was "three steps" before D13) |
| WL4 | 1 · Find your machine / Linux or WSL running herdr-core and OpenSSH, on the same Wi-Fi or VPN as your phone. | new |
| WL5 | 2 · Add this phone's key / kudde uses your password once, or you run one command yourself. | new |
| WL6 | 3 · Connect / kudde checks the machine and shows how many agents run there. | new (D13 replaces "Open your agents") |
| WL13 | 4 · Turn on alerts (optional) / Hear when an agent needs you, also with kudde closed. | new (D13) |
| WL7 | Set up a machine | Add machine |
| WL8 | Import a machine list | existing Machines action, not on welcome |
| WL11 | No machines yet | retained on empty home after first connection |
| WL12 | Add a machine that runs herdr-core to see its agents here. | retained on empty home after first connection |
| WL9 | Setup guide | new (decision 6) |
| L1 | Set a screen lock first | Could not start the connections |
| L2 | kudde keeps its SSH key in the phone's secure hardware. Android allows that only on a phone with a PIN, pattern or password. | the key store's message |
| L3 | Set a screen lock | Try again |
| U1 | %1$s · finish setup | new |
| U2 | %1$s is not connected yet / Its setup is not finished. / Continue setup | No agents yet |
| U3 | New terminal (button under "No agents yet") | new on this block |
| U4 | Continue setup / Opens the step this machine is at (card row, when herdr-core is missing or too old) | no row |

**Step 1**

| Id | Text | Was |
|---|---|---|
| S1 | Set up a machine | Add machine |
| S2 | Step %1$d of 4 | new |
| A1 | Where is it? | new |
| A2 | Address · hint: workstation or 192.0.2.10 · help: The machine's name on your network or VPN, or its IP address. | Host |
| A3 | User · hint: your login name there | User |
| A4 | Port | Port |
| A5 | Name · hint: the derived name, or "optional" · help: How kudde lists this machine. Leave it empty to use the address. | Name |
| A6 | Tailscale SSH does not work with the phone key: use an address and port where OpenSSH answers. | The machine needs an OpenSSH server. Tailscale SSH ignores the phone key. |
| A7 | Continue | Add machine |
| A8 | Connecting to %1$s… | unchanged |
| A9 | Still no answer. This can take 20 seconds. | new |

**When the machine cannot be reached** (on the step and on the machine's card)

| Id | Text | Was |
|---|---|---|
| F1 | No answer from %1$s. / Check that the phone is on the same Wi-Fi or VPN as the machine, and that the address is right. | Unreachable · timed out |
| F2 | %1$s answered, but nothing listens on port %2$d. / Check that the OpenSSH server is running there, and the port. | Unreachable · connection failed: … |
| F3 | The phone cannot find a machine called %1$s. / Check the spelling. With a VPN such as Tailscale, check that it is on, on this phone. | Unreachable · connection failed: … |
| F4 | This network has no way to %1$s. / Join the same Wi-Fi as the machine, or turn the VPN on. | Unreachable · connection failed: … |
| F5 | Could not connect to %1$s: %2$s | Unreachable · … |
| F6 | Try again | unchanged |
| F7 | Change the address (step 2 after trust; a row on the card of a machine that cannot be reached) | new |

**Step 2**

| Id | Text | Was |
|---|---|---|
| M1 | This phone's key | new |
| M2 | On %1$s, signed in as %2$s, paste this into a terminal and press Enter: | Add this line to ~/.ssh/authorized_keys of the user you sign in as on %1$s. |
| M3 | the command of D3 (approved 2026-11-22 in its short form; it prints no messages of its own) | the line |
| M4 | Copy / Copied | unchanged |
| M5 | Share | new |
| M6 | Sending it to yourself in a note or a message is fine: nothing in it is secret. | new |
| M7 | It adds this phone's key, then prints the machine's host key. With this key, SSH runs only herdr-core: no terminal session, no forwarding. herdr-core can still run commands as that user, so treat the key like a shell login. | With this key, SSH runs only herdr-core: … |
| M8 | For sh, bash and zsh. | new |
| M9 | Compare the fingerprint | Check the host key (card row) |
| M10 | Setup command (screen reader name) · kudde setup command (share title) | new |
| D1 | First connection to %1$s. The command you ran there printed its host key. Trust it only if the fingerprint is the same: | First connection to %1$s. Check that this fingerprint matches the machine's host key. |
| D2 | Not the same? Do not trust it. Something else answered at that address: Tailscale SSH, or another SSH server on that port. | new |
| K1 | %1$s did not accept the phone key. / Choose how to add it, signed in as %2$s. If you already ran the command, check that its quotes arrived unchanged, then try again. | Phone key not accepted |
| K2 | herdr-core was not found on %1$s. / Install it there as %2$s, then try again. The setup guide has the command. | herdr-core not found on this machine |

Unchanged in the dialog: "Trust this machine?", "After this, a different
key stops the connection.", "Cancel", "Trust and connect". Unchanged after
trust: "Phone key locked", "Unlock", and the too-old texts.

**Step 3, Machines, Phone key**

| Id | Text | Was |
|---|---|---|
| C1 | Connected | new |
| C2 | %1$s is connected. | new |
| C3 | 1 agent is running there. / %1$d agents are running there. | new |
| C5 | No agents are running there yet. One you start at your desk in herdr shows up here by itself. | new |
| C9 | Continue (step 3's one action; D13) | new |
| P1 | Add it yourself (machine card choice) | One line for this machine's authorized_keys |
| P2 | To let this phone into a machine, run this there, signed in as the user your agents run under: | Add this line to ~/.ssh/authorized_keys of the user you sign in as on each machine. |
| P3 | Copy only the key line | Copy |

**Step 4, alerts** (D13; approved 2026-11-22. C4, C6 and C7 moved here
from step 3; C8 is gone, step 4 replaces it. The existing alert texts in
`strings.xml` are reused where named.)

| Id | Text | Was |
|---|---|---|
| AL1 | Alerts (optional) | new (step heading; "(optional)" added in review part 3 so the routes read as the way past it) |
| AL2 | kudde can tell you when an agent needs you or has finished, also while the app is closed. | new |
| AL3 | It needs two things: a UnifiedPush app such as ntfy on this phone, and kudde-push running on %1$s. The setup guide shows how to install kudde-push. | new |
| AL4 | Turn on alerts | `alerts_turn_on` "Turn on" on the card |
| AL5 | Sending a test alert from %1$s… | `alerts_test_waiting` "Waiting for the test alert" |
| AL6 | Alerts reach this phone. | `alert_test_text`, same words |
| AL7 | No test alert from %1$s within 30 seconds. / Check that kudde-push runs there; the setup guide shows how. | `alerts_test_missed` |
| AL8 | Alerts need a UnifiedPush app, such as ntfy. Install one, then try again. | `alerts_no_distributor` |
| AL9 | Waiting for the UnifiedPush app… | `alerts_waiting` |
| AL10 | Notifications are not allowed for kudde. / Allow | `alerts_not_allowed`, `alerts_allow` |
| AL11 | Alerts are on on this phone. / Send test alert | new / `alerts_test` |
| C4 | Show my agents | new (from step 3) |
| C6 | Start a terminal or agent | new (from step 3) |
| C7 | Done | new (from step 3) |

**The title bar hint** (approved 2026-11-22; they describe the
gestures of `spaces-navigation` and change if those change; they replace
the "Getting around" screen's G1 to G6)

| Id | Text | Was |
|---|---|---|
| G1 | Move around from this bar | new |
| G2 | Swipe sideways for other panes in this tab. At either end it bounces; with one pane it only bounces. | new |
| G3 | Swipe up: the next agent that needs you. Down: back. | new |
| G4 | Tap the map: every pane of the tab. | new |
| G5 | Got it (also the TalkBack action's name) | new |
| G6 | With TalkBack, the title bar's actions move around: Next terminal, Previous terminal, Next agent that needs you, Previous agent that needs you. Double tap the map for every pane of the tab. (in place of G2 to G4 while touch exploration is on) | new |

**The two key-install choices and password detail** (D12; approved 2026-11-22; decision 10
answered (a))

| Id | Text | Was |
|---|---|---|
| R1 | kudde adds it for you | new, first choice on step 2 and rejected-key cards |
| R17 | Add it yourself | new, second choice on step 2 and rejected-key cards |
| R18 | Run one command at the machine. kudde never sees a password. | new manual-choice description |
| R19 | Both ways add only this phone's public key, limited to herdr-core. Existing keys stay in place. | new shared choice note |
| S1 | Signing in as %1$s | new, checklist step 1 (approved by Ezra 2026-11-25) |
| S2 | Adding the key | new, checklist step 2 (approved) |
| S3 | Signing out | new, checklist step 3 (approved) |
| S4 | Your first machine is set up. | new, under C2 on step 3 only for the first machine that connects (approved) |
| R2 | kudde signs in once as %2$s with that account's password, adds the same key and signs out. The password is not stored. | new |
| R3 | First connection to %1$s. If this key is not your machine's, whoever answered gets the password, and setup can still look as if it worked. Trust it only if you are sure. | new (dialog's first paragraph) |
| R4 | To compare, run this at %1$s: (then the line, e.g. `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`) | new |
| R5 | Trust and continue | new |
| R6 | Password for %2$s on %1$s | new (field label; with keyboard-interactive, followed by the machine's first prompt as it sent it) |
| R7 | Add the key | new |
| R8 | (replaced by the checklist S1 to S3) | the working line is gone |
| R9 | %1$s did not accept the password for %2$s. / Check it and try again. Nothing was changed there. | new |
| R10 | %1$s does not take a password over SSH. / Choose "Add it yourself" to run the command at the machine: it needs none. | new |
| R11 | %1$s asks for more than a password, such as a code. / Choose "Add it yourself" to run the command at the machine. | new |
| R12 | The connection to %1$s dropped while the key was being added. / kudde cannot tell whether it went in. Try again: adding it twice does no harm. | new |
| R14 | The setup on %1$s ended with code %2$d. / Choose "Add it yourself" to run the command at the machine to see why. | new |
| R15 | %1$s refused the password again. / Some machines lock an account after a few wrong tries. To be safe, choose "Add it yourself" to run the command at the machine. | new |
| R16 | kudde added the key, but %1$s still refuses it. / The SSH server may read keys from another file, or refuse the home directory's permissions (StrictModes). The setup guide's check script finds out which. | new |

## Risks / Trade-offs

- **A chat or notes app rewrites the command's quotes.** The key is then
  not accepted, which is safe, and K1 names the cause. The base64 form
  (D3) is the fallback if it happens in use.
- **The person taps "Trust and connect" without comparing.** As today; the
  dialog now says where the other fingerprint is and what a difference
  means. The QR helper would remove the judgment; it is not in this change.
- **A wrong host key now costs a password.** With the command, trusting
  a wrong key lets an impostor talk to kudde; with D12 it hands over the
  account's password. Decision 10 settles how much the person must check;
  on a VPN such as Tailscale, with its own keys, the risk is small, on a
  shared Wi-Fi it is not.
- **An account barred from passwords that still offers them** reads as a
  wrong password (R9, then R15). Root is the common case: OpenSSH's
  default `PermitRootLogin prohibit-password` offers `password` and
  refuses it for root whatever is typed. The command still works there.
- **Two words for one thing while both exist.** "Add machine" stays on the
  Machines screen and opens "Set up a machine". Accepted: the first is a
  list action, the second the flow's title.
- **Render 8 changes text.** Its golden and checkpoint are re-recorded
  and signed off again.
- **Step derived from state can surprise.** A machine that connects by a
  background retry jumps to step 3 while the person reads step 2. Wanted:
  it is the "advances by itself" people expect from pairing flows.
- **The name lookup's error text differs per platform**, so
  `NameNotFound` may fall to `Other` on some phones. `Other` still shows
  the reason; a phone check records what the test phones report.

## Migration

The machine list, host keys and phone key are untouched. D1 adds first-run
status and a non-secret resume checkpoint; D11 adds the hint seen flag.
These preferences are excluded from backup and transfer. An installed app with machines never sees the welcome. The
README's "Setting up" is rewritten to the app's steps, and its herdr-core
link (`README.md:75`, which points at a repository that does not exist)
is corrected.
