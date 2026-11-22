# Proposal: a clearer first setup

## Plain summary

**Answered:** 2, one command at the machine (2026-11-20); 4, a
full-screen welcome on first start before anything else, followed by full-screen
setup steps (owner, 2026-11-22); 12, no way out until a real machine
is connected (owner follow-up, 2026-11-22); 9, a one-time title bar hint rather than a
setup screen; 10, confirm the fingerprint on the phone before any password.
On 2026-11-22 also: 1, the order is where the machine is, the phone's key,
connected; 3, no demo for now; 5, Name optional, derived from the Address;
6, one "Setup guide" link; 7, QR pairing and the target-SDK network
permission stay out, alerts become a fourth step after connected (design
D13); 11, the hint shows with any map, the one-cell map included, and says
that sideways moves only when the tab has more panes.
Step 2 offers two choices together, "kudde adds it for you" first and
"Add it yourself" second. The same steps serve Machines > Add machine,
without the welcome, and key rejection on a listed machine.

**Open decision, pick first** (draft for the owner, no approval implied):

8. **Words and look:** approve the complete Texts table, the render plan
   and D13's six picks for the alerts step in design.md (pick); revise them
   before building screens. The command's exact text also awaits approval.

**What the person sees:** first start opens a full-screen welcome with kudde's
mark, a headline, one line, four numbered steps and one main "Set up a
machine" button. "Forced" means that home, Machines and incoming navigation
wait until a real machine is connected. There is no skip or way into the
app before that. The welcome has no back arrow and Back there closes the
app; on a setup step, Back goes one step back. Leaving and returning
resumes the same screen and step on every start until connection succeeds. A missing screen lock is explained after the welcome,
before setup can use the key. Step 1 asks where the machine is and explains
reach failures in place. Step 2 is "This phone's key", with the two choices
side by side (stacked at large text if needed). The first confirms the host
fingerprint on the phone, then uses the password once and never stores it;
the second shows the one command with Copy and Share and then confirmation.
Both install the same restricted key. Step 3 shows the connected machine;
step 4 offers alerts (permission, the UnifiedPush app, a test alert) and,
whatever is chosen there, leads to agents or starting one. Later additions from Machines use those same
screens directly. "Phone key not accepted" offers both choices again, also
for a machine that used to work. The hint can now appear on a one-pane tab,
where its map opens the overview and sideways swipes only bounce.

## Why

The owner tried Herdr's own Android app and found its first setup clearer
than kudde's. It is: one screen says what is needed before anything is
asked. kudde opens on "No machines yet", then a form without hints, then a
fingerprint with no word on where to find the other one, then "Phone key
not accepted", then a line to copy with no word on what to do with it. The
strict path works, but the app tells it as a failure to repair, and the
two desk actions are explained only in the README. Version 1.0 means
"someone else can set the app up without help" (decisions.md, 2026-11-13);
the README still does part of that work.

The older reference trusted the host key on first sight; the newer
2026-11-22 reference confirms it before the password. Its unrestricted key
installation is still not to copy. The job is to make the strict path read as plainly as
theirs.

## What Changes

- A first-start full-screen welcome, then separate setup screens, with no
  skip. Import stays in Machines after the first real connection.
- A phone without a screen lock: "Set a screen lock first" and a button,
  in place of "Could not start the connections".
- "Add machine" becomes a four-step setup, the same for every machine:
  "Where is it?", "This phone's key", "Connected", "Alerts".
- Step 1: field hints, an optional name, and a reason in words when the
  machine cannot be reached (no answer, port refused, name not found, no
  route), with the address editable in place.
- Step 2, "Add it yourself": one command that appends the restricted key line, sets the file
  modes and prints the host key's fingerprint; Copy and Share; then the
  signed-off trust dialog, which now says where the other fingerprint is
  and what a different one means.
- Confirmed host keys survive User edits even before the first connection;
  only one eligible core-startup close waits for a retry. Actionable failures
  appear immediately, including a changed host key.
- After trust: "did not accept the phone key" and "herdr-core was not
  found" on the step, each with what to do and "Try again"; the step moves
  on by itself when the machine connects.
- Step 3: the machine is connected, how many agents run there, "Continue".
- Step 4: alerts, which never block: "Turn on alerts" asks the
  notification permission, registers with the UnifiedPush app and sends one
  test alert; each stop says what to do; "Show my agents" or "Start a
  terminal or agent" always show.
- The home shows a machine whose setup is unfinished, with a way back in;
  "No agents yet" gets a "New terminal" button.
- The hub reports which kind of failure it met, so the screens need not
  read error texts.
- Step 2, first choice: "kudde adds it for you", beside "Add it yourself". After the host key is
  confirmed (decision 10), kudde asks for the account's password, signs in
  once over SSH (password or keyboard-interactive), runs the same
  installation as the command, signs out and forgets the password. A
  machine that takes no password says so and points to the command.
- Machines: Add machine uses the same steps without welcome; key rejection
  offers both key-install choices, password-once first.
- The first time a terminal or its chat opens with a map, including one cell: a hint under the title bar
  with its gestures as `spaces-navigation` defines them, closed by a tap
  for good; built after that change.
- README: "Setting up" follows the app's steps; the broken herdr-core
  link is corrected.

## Capabilities

### New Capabilities
- `first-run`: the welcome, the screen-lock notice, the three setup
  steps, the setup command and the app running it with a password once,
  the title bar hint, what each failure says, the way back into an
  unfinished setup, and setup with a screen reader and large text.

### Modified Capabilities
- None. `machine-connection` (in `android-client-v1`) keeps every rule:
  host keys confirmed before first trust, the restricted line, no
  signature before trust. This change adds the command around that line
  and changes words on the trust dialog. The password way (D12) sends
  nothing secret before the host key is confirmed, installs the same
  restricted line, and never signs with the phone key.

## Impact

- Rust (`crates/kudde-mobile`): `setup_command` beside
  `authorized_keys_line`; `MachineState::Unreachable` gains a kind; both
  cross the uniffi boundary. The throwaway-sshd tests run the command in
  sh, bash and zsh. A one-shot password session (D12) with russh's own
  password and keyboard-interactive methods, tested against an in-process
  russh server. `zeroize` (1.9.1, already in `Cargo.lock` through russh)
  becomes a direct dependency of kudde-mobile; no new crate enters the
  lock.
- Android: `MachineFlows` (one `Setup` screen in place of `AddMachine`
  and `PhoneKey`), the form, the home's empty states and notices, the
  machine card's texts and links, `strings.xml`. No new permission, no
  new dependency.
- Design: the new, changed and retired render plan is in design.md;
  `design-tokens.json` entries and goldens in both themes follow sign-off.
- Docs: README "Setting up" and "What you need"; release notes.
- Stored data: the hint's "seen" (D11), and first-setup completion status and non-secret
  resume checkpoint (D1/D6), in app-private preferences excluded from backup.
  Machine storage and pins are unchanged; no password is ever stored. People who already have
  machines with a recorded successful connection do not repeat the welcome,
  but do see the hint once. Merely listing a machine does not finish setup.
- Phone checks: a full first setup on the test phone, the password way
  against a desk with password login on and one with it off, and one look
  at Android 17 for the local network question. The screen-lock notice is
  checked in Robolectric only: removing a test phone's lock would destroy
  its key.
- Depends on `spaces-navigation` for the hint only: its tab
  walk with the bounce, the map in the title bar and the tab overview must
  be on main first. Everything else ships without it.
- The earlier session estimate is withdrawn: full-screen first-start state
  and Machines repair need a fresh implementation estimate after sign-off.

## Sources

`research/first-run-walkthrough-2026-11.md` (what exists today with paths,
Herdr's app, other clients and SSH apps, Android's guidance); closed
issues #20 and #21, which checked first-run errors and the docs but did
not reshape the flow.
