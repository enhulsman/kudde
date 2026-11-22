## ADDED Requirements

Texts in quotes are the app's wording. Every one of them needs the owner's
approval before it ships; the full list, with the strings they replace, is in
`design.md` under "Texts". Names in the scenarios are invented: the machine
"workstation", the user "dev", the address `192.0.2.10`.

### Requirement: First start shows a full-screen welcome before the app
On a fresh install the app SHALL show a full-screen welcome before home,
Machines, or an incoming navigation destination. It SHALL show kudde's existing
mark, "Set up your first machine", a one-line description, "Set up in four
steps", the four numbered steps of design WL4 to WL6 and WL13, and one main action
"Set up a machine". It SHALL request no permission and start no connection.
On the first visit the main action SHALL open full-screen setup at step 1,
or the screen-lock notice first if needed; with a checkpoint it SHALL resume
that setup step instead. Import SHALL remain in Machines, not on the welcome.

Until a real machine connects at step 3, every start SHALL return to the
welcome or saved setup step before any app destination. There SHALL be no
skip action or way out into home or Machines. Closing the app, pressing Back,
receiving an alert, listing/importing a machine, confirming its key or running
the installation SHALL NOT count as completion. The welcome SHALL have no
back arrow, and Android Back there SHALL close the app rather than skip
setup; every setup step SHALL have a back arrow that goes one step back (see
"Setup survives leaving the app"). Leaving and returning SHALL resume the
same screen and step. Demo scope remains open (decision 3); any future
demo entered from welcome SHALL lead back into setup.

First-setup progress and the non-secret checkpoint in design D1 SHALL survive
cold launches and process death in app-private preferences excluded from
backup/transfer. Neither SHALL contain a password or password-session state.
A real connection at step 3 SHALL record completed and clear the unfinished
checkpoint. An update without a progress record SHALL infer completion only
from a recorded successful machine connection, never merely from a listed
machine. After completion, removing the last machine SHALL show "No machines
yet", its existing explanation and "Set up a machine", without repeating the
once-only welcome. Clearing app data SHALL restore first setup.

#### Scenario: First launch
- **GIVEN** a fresh install, with or without a screen lock
- **WHEN** the person opens kudde
- **THEN** the separate welcome shows the mark, headline, one line, four numbered steps and main setup button before any app screen, connection or permission prompt, with no skip action

#### Scenario: Every start until a real machine connects
- **GIVEN** no real machine has connected and the person has left the welcome or any unfinished setup step
- **WHEN** the person starts kudde again, including after a cold launch or process death, any number of times
- **THEN** the screen they left shows again: the welcome, or the same setup step with its machine, fields and key-install choice; home and Machines remain unavailable until a real connection succeeds

#### Scenario: Back on the welcome closes the app
- **GIVEN** the welcome shows before any real machine has connected
- **WHEN** the person presses Android Back
- **THEN** the app closes without showing home or marking setup completed, and the next start shows the welcome again
- **AND** the welcome shows no back arrow

#### Scenario: Successful first connection
- **GIVEN** setup is unfinished
- **WHEN** a real machine connects and step 3 shows
- **THEN** first setup is marked completed, and step 3 offers "Continue" to step 4, whose routes to agents or starting one are the normal ones

#### Scenario: Existing successful installation
- **GIVEN** a machine has a recorded successful connection and no first-setup progress is stored
- **WHEN** the person updates and opens kudde
- **THEN** home opens without repeating welcome and first setup is marked completed

#### Scenario: Listed does not mean connected
- **GIVEN** a machine is listed but none has ever connected, and no first-setup completion is recorded
- **WHEN** the person opens kudde
- **THEN** welcome or the saved setup step shows, with no way into home merely because a machine exists

#### Scenario: The last machine is removed after completion
- **GIVEN** a real machine connected and first setup completed
- **WHEN** the person removes the last machine and restarts
- **THEN** home shows "No machines yet" and "Set up a machine", not the first-start welcome

#### Scenario: Import after first connection
- **GIVEN** first setup completed through a real connection
- **WHEN** the person opens Machines and imports an exported list with two machines
- **THEN** both are listed without trusted host keys and can use the shared setup steps; import does not supply proof of connection

#### Scenario: An alert cannot bypass first setup
- **GIVEN** no real machine has connected
- **WHEN** kudde is opened through an alert
- **THEN** welcome or the saved setup step shows first; no agent opens ahead of it

#### Scenario: Clearing app data
- **GIVEN** first setup was completed
- **WHEN** app data is cleared and kudde is launched
- **THEN** the full-screen welcome shows again

### Requirement: One link leads to the setup guide
The welcome SHALL offer "Setup guide", and the "herdr-core was not found"
text SHALL offer the same link; it opens the project's setup guide in the
phone's browser. The app SHALL hold the address in one place and SHALL
load nothing from it itself. (Depends on decision 6 of the proposal; left
out if the owner says no.)

#### Scenario: Opening the guide
- **WHEN** the person taps "Setup guide" on the welcome
- **THEN** the browser opens the setup guide, and kudde itself made no network request

#### Scenario: No browser
- **WHEN** no app on the phone can open a web address
- **THEN** nothing happens and the app does not crash

### Requirement: A phone without a screen lock is told so first
After the first-start welcome, or on home after first connection, the app SHALL
show "Set a screen lock first" when the phone has no secure
screen lock (`KeyguardManager.isDeviceSecure` is false), which the phone
key needs: with the reason in words and the action "Set a screen lock", in
place of step 1 or the home error "Could not start the connections";
"Set up a machine" SHALL NOT be offered. The action SHALL
open Android's screen for choosing a PIN, pattern or password, or the
security settings when the phone has no such screen. On each return to the
app the check SHALL run again, and once a lock is set the app SHALL start
its connections without a restart. A start failure with a screen lock set
SHALL keep today's "Could not start the connections" with its reason and
"Try again".

#### Scenario: No screen lock
- **GIVEN** a phone with no PIN, pattern or password
- **WHEN** the person opens kudde for the first time and taps "Set up a machine" on the welcome
- **THEN** the full-screen notice shows "Set a screen lock first", why and "Set a screen lock", with no raw key store error

#### Scenario: A lock is set meanwhile
- **GIVEN** the home shows "Set a screen lock first"
- **WHEN** the person sets a PIN in Android settings and returns to kudde
- **THEN** step 1 opens with no restart of the app and without repeating the welcome

#### Scenario: The phone has no screen for it
- **WHEN** no activity answers the request to choose a screen lock and the person taps "Set a screen lock"
- **THEN** the security settings open, or Android's main settings when those are missing too, and the app does not crash

#### Scenario: Another start failure
- **GIVEN** a phone with a screen lock whose key store fails
- **WHEN** the person opens kudde
- **THEN** after the first welcome main action, or on home after first connection, "Could not start the connections", the reason and "Try again" show; first setup remains on the failure until it can continue

### Requirement: Setup is four steps, the same for every machine
Adding a machine SHALL be one flow of four steps in a fixed order, each on
its own screen under the title "Set up a machine" with "Step n of 4": step 1
"Where is it?", step 2 "This phone's key", step 3 "Connected", step 4
"Alerts". The welcome's
"Set up a machine" and the Machines screen's "Add machine" SHALL both open
step 1. A step SHALL be reached only when the one before it is done: step 2
only for a machine that is listed, step 3 only for a machine that is
connected, step 4 only from step 3. Steps 1 to 3 SHALL NOT be skipped; step
4 SHALL always offer a way on without alerts.

#### Scenario: From the welcome
- **GIVEN** no saved setup checkpoint
- **WHEN** the person taps "Set up a machine"
- **THEN** step 1 shows, titled "Set up a machine", reading "Step 1 of 4" and "Where is it?"

#### Scenario: A second machine
- **GIVEN** one machine is connected
- **WHEN** the person taps "Add machine" on the Machines screen
- **THEN** the same full-screen step 1 shows directly, without the welcome, and follows the same key choices, connected screen and alerts step

#### Scenario: Steps cannot be skipped
- **GIVEN** step 1 shows and nothing was reached yet
- **WHEN** the screen is restored after the app was closed by the system
- **THEN** step 1 shows again with what was typed; step 2 does not show for a machine that is not listed

### Requirement: Step 1 asks where the machine is
Step 1 SHALL ask for Address, User, Port and Name, in that order. Port SHALL
start at 22. Name SHALL be optional: left empty, the machine is named after
the address (the part before the first dot of a host name, the whole address
when it is an IP address), and the field SHALL show that name as its hint
while empty. The field checks SHALL be today's (`MachineForm.kt`), shown
from the first tap on "Continue" and then as the person types; a derived
name that is already taken SHALL be reported on the Name field. Address and
User SHALL show a hint of what to type. "Continue" SHALL list the machine
and start reaching it, showing "Connecting to <address>:<port>…" with the
fields locked; after 5 seconds without an answer the step SHALL add "Still
no answer. This can take 20 seconds." Reaching SHALL stop before any use of
the phone key: the first connection only reads the machine's host key.

#### Scenario: The shortest path
- **WHEN** the person types `workstation.example.net` as Address and `dev` as User and taps "Continue"
- **THEN** a machine named "workstation" is listed with port 22 and user dev, and the step reads "Connecting to workstation.example.net:22…"

#### Scenario: An IP address names the machine
- **WHEN** the person types `192.0.2.10` and `dev` and leaves Name empty
- **THEN** the machine is named "192.0.2.10"

#### Scenario: The derived name is taken
- **GIVEN** a machine named "workstation" is listed
- **WHEN** the person sets up `workstation.example.org` with Name empty
- **THEN** nothing is listed, and the Name field says "There is already a machine called workstation."

#### Scenario: Checks before anything is sent
- **WHEN** the person taps "Continue" with Address empty and Port 70000
- **THEN** both fields show their check, nothing is listed and nothing connects

#### Scenario: The machine is reached
- **GIVEN** step 1 reads "Connecting to workstation.example.net:22…"
- **WHEN** the machine presents its host key
- **THEN** step 2 shows for that machine, and the phone key has signed nothing

#### Scenario: A slow answer
- **GIVEN** step 1 has been connecting for 5 seconds
- **THEN** it also reads "Still no answer. This can take 20 seconds."

### Requirement: A machine that cannot be reached says why
When reaching a machine fails, in step 1 or later, the step SHALL stay on
screen with the fields open again and SHALL say what happened and what to
check, by kind:

| Kind | When | The step says |
|---|---|---|
| No answer | the connection timed out | "No answer from <address>." and to check the network or VPN and the address |
| Refused | the address answered and refused the port | "<address> answered, but nothing listens on port <port>." and to check the OpenSSH server and the port |
| Name not found | the name lookup failed | "The phone cannot find a machine called <address>." and to check the spelling and the VPN |
| No route | the network says the address is out of reach | "This network has no way to <address>." and to join the same Wi-Fi or turn the VPN on |
| Network off | the system refuses the app any socket (GrapheneOS) | today's "Network permission is off" with "Open settings" |
| Other | anything else | "Could not connect to <address>: <reason>", with the reason as reported |

The hub SHALL report the kind with the failure, so no screen has to read it
out of an error text. The step SHALL offer "Try again", and SHALL NOT send
the person to the Machines screen on a failure. A changed Address, User,
Port or Name SHALL change the machine setup is about and SHALL NOT list a
second one. A confirmed host key SHALL be preserved whenever host and port
are unchanged, regardless of `lastConnectedMs`, including User or Name edits
before the first successful authentication or core handshake. A changed host
or port SHALL clear the pin and require comparison again. Only an unpinned,
never-connected machine may be removed and added again for a new name;
otherwise use `update_machine`, keep the listed name and lock the Name field.
A derived name follows an edited address only on that unpinned replacement. The Machines screen's
card SHALL use the same words for the same kind, and the card of a machine
that cannot be reached SHALL offer "Change the address", which opens step
1 with that machine's fields filled in.

#### Scenario: Wrong address on the same network
- **WHEN** the person sets up `192.0.2.99`, where nothing answers, and 20 seconds pass
- **THEN** step 1 reads "No answer from 192.0.2.99." with what to check, the fields can be edited, and "Try again" shows

#### Scenario: The SSH server is not running
- **WHEN** the machine refuses the connection on port 22
- **THEN** step 1 reads "192.0.2.10 answered, but nothing listens on port 22." and to check the OpenSSH server and the port

#### Scenario: The VPN is off
- **WHEN** the person sets up `workstation`, a name only the VPN knows, with the VPN off
- **THEN** step 1 reads "The phone cannot find a machine called workstation." and names the VPN as a thing to check

#### Scenario: Fixing the address
- **GIVEN** step 1 reads "No answer from 192.0.2.99." for the machine "192.0.2.99"
- **WHEN** the person changes Address to `192.0.2.10` and taps "Try again"
- **THEN** one machine is listed, named "192.0.2.10" with that address, and it is being reached

#### Scenario: Moving a machine that worked before
- **GIVEN** workstation has connected before and its card now reads "No answer from 192.0.2.10."
- **WHEN** the person taps "Change the address" on the card, types `192.0.2.20` in step 1 and taps "Try again"
- **THEN** workstation keeps its name, its trusted host key is forgotten, and step 2 asks to compare the fingerprint again

#### Scenario: Trust survives an edit before authentication succeeds
- **GIVEN** fingerprint A was confirmed, `lastConnectedMs` is null and the machine is `KeyRejected`
- **WHEN** User changes with host and port unchanged and setup retries
- **THEN** the same listed machine still pins A; a presented B is `HostKeyMismatch`, with both fingerprints and only the existing confirmed forget action

#### Scenario: Trust survives an edit before the core handshake succeeds
- **GIVEN** fingerprint A was confirmed, `lastConnectedMs` is null and the machine is `NotInstalled`
- **WHEN** step 1 is opened for editing
- **THEN** Name is filled and locked; changing User preserves A, and changing host or port clears A and requires comparison again

#### Scenario: A reason the app does not know
- **WHEN** the connection fails with a reason outside the table
- **THEN** the step reads "Could not connect to 192.0.2.10: " followed by that reason

### Requirement: One command at the machine adds the key and shows the host key
The "Add it yourself" detail of step 2 SHALL show one command to run on the machine, as the user of step 1,
and SHALL say so with the machine's name and the user. The command SHALL:
create `~/.ssh` when missing and correct its mode to 700 even when it
already exists; verify that this directory and any existing `authorized_keys`
are owned by the current user and are ordinary directory/file paths, not
symlinks, or refuse with the plain message in D3; establish mode 600 on
`authorized_keys` before appending (including an owner-owned 0400 file); append to
`~/.ssh/authorized_keys` exactly the line the app generates today (`restrict`
plus the forced herdr-core command, machine-connection spec, "The key is
restricted to herdr-core on each machine"), on a line of its own also when
the file did not end in a line break; set that file's mode to 600; and
print the fingerprint of the machine's host key of the type the machine
presented in step 1, only after every ownership/type check, creation,
permission correction and append succeeded. Any installation failure SHALL
exit nonzero, print a plain refusal on stderr and print no fingerprint.
Ownership is refused, never silently left or changed through privilege escalation.
The command SHALL contain no secret and no other
action, SHALL work pasted as it is into sh, bash and zsh, and SHALL be
built next to the line (one source for both). Under the command the step
SHALL say what it does and what the key is limited to, in the words the
phone key screen uses today. Step 2 SHALL keep "Add it yourself" available from the password detail
(see "The app can run the setup with the password once"); selecting it SHALL
show the command without requiring a password or losing this machine's step. The app SHALL NOT ask for a password anywhere
else.

The Phone key row under "This phone" SHALL show the same command without
the host key part, and "Copy only the key line" for people who manage
`authorized_keys` another way.

#### Scenario: The command for a machine that presented an Ed25519 key
- **GIVEN** workstation presented an ED25519 host key in step 1
- **WHEN** the person chooses "Add it yourself" on step 2
- **THEN** it reads "On workstation, signed in as dev, paste this into a terminal and press Enter:", and the command's fingerprint invocation prints the fingerprint of `/etc/ssh/ssh_host_ed25519_key.pub`

#### Scenario: Running it on a clean account
- **GIVEN** a user with no `~/.ssh`
- **WHEN** the command runs in sh, in bash and in zsh
- **THEN** `~/.ssh` has mode 700, `authorized_keys` has mode 600 and holds exactly the app's restricted line, the host key's fingerprint is printed, and a connection with the phone key runs herdr-core's `connect`

#### Scenario: An authorized_keys without a final line break
- **GIVEN** `authorized_keys` ends in another key with no line break after it
- **WHEN** the command runs
- **THEN** both keys work: the phone's line starts on its own line

#### Scenario: Running it twice
- **WHEN** the command runs a second time
- **THEN** the phone key still works, and no other key in the file is changed

#### Scenario: An existing directory has mode 0775
- **GIVEN** the current user owns `~/.ssh` with mode 0775
- **WHEN** the command runs
- **THEN** the directory becomes 700, the file becomes 600, the fingerprint prints and authentication works with sshd `StrictModes yes`

#### Scenario: An existing file is read-only
- **GIVEN** the current user owns `authorized_keys` with mode 0400
- **WHEN** the command runs
- **THEN** its mode becomes 600 before appending, existing contents survive and the fingerprint prints only after the append succeeds

#### Scenario: Installation cannot finish
- **GIVEN** directory creation, chmod or append fails, or either path is a symlink, a wrong type or owned by another user
- **WHEN** the command runs
- **THEN** it exits nonzero with the applicable D3 refusal on stderr and no fingerprint; ownership is not changed

#### Scenario: The line and the command cannot drift
- **WHEN** the forced command changes in the code
- **THEN** the command shows the new line without a second edit, and a test that extracts the line from the command and compares it with the generated line passes

#### Scenario: A host key type the app has no file name for
- **GIVEN** the machine presented a host key of a type other than ED25519, ECDSA or RSA
- **WHEN** the manual detail of step 2 shows
- **THEN** the command's fingerprint invocation prints the fingerprints of every `/etc/ssh/ssh_host_*_key.pub`

#### Scenario: From the Phone key row
- **WHEN** the person opens Phone key under "This phone"
- **THEN** the command adds the key and prints no host key, and "Copy only the key line" copies the restricted line alone

### Requirement: The command can be copied and shared
Step 2 SHALL offer "Copy", which puts the command on the clipboard and
reads "Copied" after, and "Share", which hands the command as plain text to
Android's share sheet. The step SHALL say that sending it to yourself is
fine because nothing in it is secret. The clip SHALL NOT be marked
sensitive. Copy and Share SHALL give byte for byte the command shown.

#### Scenario: Copy
- **WHEN** the person taps "Copy"
- **THEN** the clipboard holds the command exactly as shown, and the button reads "Copied"

#### Scenario: Share
- **WHEN** the person taps "Share"
- **THEN** Android's share sheet opens with the command as plain text, the title "kudde setup command" in its preview, and no subject

#### Scenario: No app can take the share
- **WHEN** no app on the phone accepts shared text
- **THEN** the app does not crash, and "Copy" still works

### Requirement: The host key is compared before it is trusted
The app SHALL trust a machine's host key only after the person confirms the
fingerprint it shows, and SHALL NOT sign with the phone key before that
(unchanged from machine-connection, "Host keys are confirmed once and then
enforced"; nothing in this change loosens it). The manual detail of step 2 SHALL offer "Compare
the fingerprint" once the machine has presented a host key; it SHALL open
the confirm of render 8, which SHALL say that the command printed the
machine's host key, show the key type and fingerprint, and say what a
different fingerprint means. "Trust and connect" SHALL confirm exactly the
fingerprint the dialog showed. While the machine has not been reached, the
step SHALL say so in place of the button. A machine whose host key waits
for confirmation SHALL lead to step 2 from its card, with both key choices and the manual command one choice away, never to a
dialog alone.

#### Scenario: Comparing
- **GIVEN** step 2 for workstation, which presented an ED25519 key
- **WHEN** the person taps "Compare the fingerprint"
- **THEN** the dialog reads "Trust this machine?", says the command printed the host key, shows "ED25519" and the `SHA256:` fingerprint, "After this, a different key stops the connection." and what a different fingerprint means, with "Cancel" and "Trust and connect"

#### Scenario: Trusting
- **WHEN** the person taps "Trust and connect"
- **THEN** that fingerprint is confirmed for workstation, the dialog closes, and step 2 reads "Connecting to workstation…"

#### Scenario: Cancelling
- **WHEN** the person taps "Cancel"
- **THEN** nothing is trusted, step 2 stays, and the machine still waits for its host key

#### Scenario: The key changes while the dialog is open
- **GIVEN** the dialog shows fingerprint A
- **WHEN** the machine presents fingerprint B before the person taps
- **THEN** the dialog shows B, and "Trust and connect" confirms only what it shows

#### Scenario: Not reached yet
- **GIVEN** step 2 after a return from the background, while the app reconnects
- **THEN** the step reads "Connecting to workstation…" where the button was, and the button comes back when the host key arrives

#### Scenario: From the card
- **GIVEN** an imported machine whose card reads "Host key not confirmed yet"
- **WHEN** the person taps "Check the host key"
- **THEN** step 2 shows for that machine, with both key choices; "Add it yourself" shows the command and "Compare the fingerprint"

#### Scenario: No signature before trust
- **GIVEN** a machine added in step 1 and never trusted
- **WHEN** the app connects, reconnects and returns from the background any number of times
- **THEN** the phone key signs nothing for that machine and no "Unlock the SSH key" prompt appears for it

### Requirement: Both key-install choices are shared with Machines
Full-screen step 2 SHALL show "This phone's key", the machine and user, and
two choices together: "kudde adds it for you" first, "Add it yourself" second,
with R2/R18 descriptions and R19's shared restriction note. The two choices
SHALL appear side by side where text fits and stack in that order at large
text or narrow widths. Choosing either SHALL open its detail within step 2;
the other choice SHALL remain available, with any password cleared on switching.
Exception: after R16, the password choice SHALL be unavailable for the rest
of that visit, as required below; the manual choice stays available.
Neither choice SHALL install a key merely by being displayed or selected.

Machines > Add machine SHALL use the same full-screen steps 1 to 3 without
the welcome. A listed machine whose key is rejected SHALL offer these same
two choices together on its card, in the same order and responsive layout,
and each SHALL open the corresponding step 2 detail for that machine. This
SHALL apply to imported, never-connected and previously connected machines.
It SHALL NOT create a duplicate machine or discard a confirmed host pin.
The password path SHALL require host confirmation if unconfirmed and SHALL
reject a changed pin before authentication; it SHALL NOT offer a way around
the existing mismatch and confirmed-forget flow. All password safeguards,
manual fallback, retries and completion SHALL be the same as in first setup.

#### Scenario: Both choices before an error
- **GIVEN** workstation has presented its host key in step 1
- **WHEN** step 2 opens
- **THEN** both choices and descriptions show together, password-once first, without asking for a password or requiring a failed phone-key attempt

#### Scenario: Switching to manual
- **GIVEN** the password detail with text typed
- **WHEN** the person chooses "Add it yourself"
- **THEN** the field is cleared and the command, Copy, Share and fingerprint comparison show for the same machine at step 2

#### Scenario: Adding from Machines
- **WHEN** the person chooses Add machine in Machines and reaches the machine
- **THEN** step 1 led directly to the shared step 2 with both choices, no welcome was shown, and either successful path leads to step 3

#### Scenario: A listed key is refused
- **GIVEN** workstation's card reads "Phone key not accepted"
- **WHEN** the person views Machines
- **THEN** both choices show on that card, "kudde adds it for you" first; either opens its step 2 detail for workstation without adding a machine

#### Scenario: Repair with password once
- **GIVEN** workstation connected before, its phone key is now rejected and fingerprint A is pinned
- **WHEN** the person chooses "kudde adds it for you" on its card and supplies the password to a server presenting A
- **THEN** the shared installation runs once, forgets the password, retries with the restricted key and opens step 3 on connection, keeping the same machine and pin

#### Scenario: Repair by hand
- **GIVEN** workstation's phone key is rejected
- **WHEN** the person chooses "Add it yourself" on its card
- **THEN** the same command, Copy/Share and comparison flow show, and running the command then connecting opens step 3

#### Scenario: Repair meets another host key
- **GIVEN** a listed machine pins A and rejects the phone key
- **WHEN** its password choice meets B
- **THEN** no password or user name is sent and the existing changed-key screen shows; the repair action does not replace A

#### Scenario: Imported machine still needs confirmation
- **GIVEN** an imported machine has no confirmed host key
- **WHEN** the password choice is opened from its setup
- **THEN** confirmation of the presented fingerprint comes before the probe and password field

#### Scenario: Both choices at large text
- **GIVEN** 2x font or a width too narrow for adjacent choices
- **WHEN** step 2 or a rejected-key card shows
- **THEN** the choices stack password-once first, without clipping, and both remain reachable by scrolling

### Requirement: The app can run the setup with the password once
Step 2 SHALL offer "kudde adds it for you" as its first choice beside
"Add it yourself". It SHALL
send no password and no user name to a machine before its host key is
confirmed: for a machine whose host key is not confirmed yet it SHALL first
show the trust dialog with that key's type and fingerprint and the one
line that prints it at the machine, and confirming it SHALL be the same
trust as "Trust and connect" (decision 10, answered: confirmed on the
phone, trust on first use). The dialog SHALL say that a key that is not
the machine's gives the password away and that setup can still look as if
it worked. Every connection on this path
SHALL accept only the confirmed key and SHALL end before authentication
when another key is presented. Before showing the password field the app
SHALL learn from the machine which sign-in methods it offers; with neither
`password` nor `keyboard-interactive` it SHALL say so in plain words and
point to the command, without a field; with `keyboard-interactive` only,
the field's label SHALL carry the machine's first prompt as it sent it.
Signing in SHALL use `password` when offered, else `keyboard-interactive`.
The password SHALL be sent at most once across all rounds, to a single
non-echoing prompt; any non-empty prompt after it, a prompt that echoes,
several prompts or a second factor SHALL stop with a plain refusal. From
the second refused password in one visit to step 2 the refusal SHALL warn
that machines may lock the account and point to the command. Once signed
in, the app SHALL run, once, exactly the Phone key variant of the setup
command (the command of "One command at the machine" without the host key
part, byte for byte) through `sh -s`, and request nothing else on the
session (no pty, environment, agent, X11 or port forwarding), then
disconnect; it SHALL NOT retry by itself. Of the machine's standard error
it SHALL show only a line exactly equal to one of the command's own two
refusals. Running out of time after signing in SHALL read as a dropped
connection whose result is unknown. The line it installs SHALL be the same
restricted line. When the phone key is still refused after a run that
ended well, the step SHALL say that kudde added the key but the machine
still refuses it, name the likely causes, and SHALL NOT offer the password
again in that visit. While this way's panel shows,
the hub's "key not accepted" SHALL show as the panel, not as the text of
"After trust, each outcome says what to do"; a connection SHALL open step 3
and drop the panel. On success the hub SHALL retry at once with the phone
key, and setup continues as after the command. The phone key SHALL sign
nothing on this path.

#### Scenario: Straight through with a password
- **GIVEN** step 2 for workstation, whose machine takes passwords, and the host key not confirmed yet
- **WHEN** the person taps "kudde adds it for you", confirms the fingerprint, types the right password and taps "Add the key"
- **THEN** `authorized_keys` for dev holds the restricted line exactly as the command adds it, the session ends, step 2 reads "Connecting to workstation…", and step 3 shows

#### Scenario: The dialog warns
- **WHEN** the trust dialog of "kudde adds it for you" shows
- **THEN** it reads that if the key is not the machine's, whoever answered gets the password and setup can still look as if it worked, and it shows the line that prints the fingerprint at the machine

#### Scenario: Keyboard-interactive only
- **GIVEN** the machine offers only `keyboard-interactive`, with one prompt "Password:" that does not echo
- **WHEN** the person confirms the fingerprint, types the right password and taps "Add the key"
- **THEN** the field was labelled with "Password:", the password was sent once, the setup ran, and step 3 shows

#### Scenario: A code is asked first
- **GIVEN** the machine's first keyboard-interactive prompt asks for a verification code
- **WHEN** the person types into the field labelled with that prompt and a second non-empty prompt follows
- **THEN** that second prompt gets no answer, the session ends, and step 2 reads "workstation asks for more than a password, such as a code."

#### Scenario: A second wrong password
- **GIVEN** the machine refused the password once in this visit
- **WHEN** it refuses it again
- **THEN** step 2 reads "workstation refused the password again." with the lockout warning and the command, and nothing is retried by itself

#### Scenario: The probe meets another key
- **GIVEN** fingerprint A is confirmed
- **WHEN** the probe connection meets fingerprint B
- **THEN** it ends during key exchange with no user name sent, no field shows, and the changed-key screen shows with both fingerprints

#### Scenario: Added, but still refused
- **GIVEN** the setup ran with exit 0, and the machine's SSH server reads keys from another file
- **WHEN** the hub's retry with the phone key is refused
- **THEN** step 2 reads "kudde added the key, but workstation still refuses it." with the likely causes, "Try again" and the command, and no password field

#### Scenario: Other output is never shown
- **GIVEN** dev's shell start-up file prints a line on standard error
- **WHEN** the setup ends with code 2
- **THEN** step 2 reads "The setup on workstation ended with code 2." and that line is not shown

#### Scenario: Wrong password
- **WHEN** the machine refuses the password
- **THEN** step 2 reads "workstation did not accept the password for dev." with what to do, the field is empty, nothing was run, and "Add the key" can be tapped again

#### Scenario: Password login off
- **GIVEN** the machine's SSH server offers neither `password` nor `keyboard-interactive`
- **WHEN** the person taps "kudde adds it for you" and confirms the fingerprint
- **THEN** no password field shows, step 2 reads "workstation does not take a password over SSH." and points to "Add it yourself", which opens the command without another password prompt

#### Scenario: More than a password
- **GIVEN** the machine asks a second prompt or a prompt that echoes, such as a code
- **WHEN** the person taps "Add the key"
- **THEN** the session ends without answering it, and step 2 reads "workstation asks for more than a password, such as a code." and points to the command

#### Scenario: The person refuses the host key
- **GIVEN** the trust dialog of "kudde adds it for you" shows
- **WHEN** the person taps "Cancel"
- **THEN** nothing is trusted, no password field shows, and no connection carried a user name or password

#### Scenario: Another host key answers
- **GIVEN** fingerprint A is confirmed and the password typed
- **WHEN** the machine presents fingerprint B on the connection for the password
- **THEN** the connection ends during key exchange, no authentication message is sent, and the changed-key screen shows with both fingerprints

#### Scenario: The key is already there
- **GIVEN** the phone's line is already in dev's `authorized_keys`
- **WHEN** the person confirms the fingerprint on this path
- **THEN** the hub's phone-key attempt connects, step 3 shows and the password field is gone, empty; had the password been sent first, the script adds an identical line and no other key changes

#### Scenario: The network drops before signing in
- **WHEN** the connection for the password cannot be made or ends before authentication
- **THEN** step 2 shows the reach failure in the words of "A machine that cannot be reached says why", with "Try again", and nothing was run

#### Scenario: The network drops mid-way
- **GIVEN** the password was accepted
- **WHEN** the connection ends before the setup's exit status arrives
- **THEN** step 2 reads "The connection to workstation dropped while the key was being added." with "Try again", and a script cut off in transfer ran no part of itself; running out of time after signing in reads the same

#### Scenario: The setup refuses at the machine
- **GIVEN** dev's `~/.ssh` is a link
- **WHEN** the app runs the setup
- **THEN** step 2 reads "The setup on workstation stopped:" followed by the command's own refusal, and the connection has ended

#### Scenario: One run, then gone
- **WHEN** the setup finishes, whatever its outcome
- **THEN** exactly one session ran exactly one `sh -s` with the Phone key variant of the command, requested no pty, environment, agent, X11 or port forwarding, and has been disconnected

### Requirement: The password is never kept
The password SHALL be used for the one sign-in and SHALL NOT be stored,
logged, put on the clipboard, put into a crash report, an error text or the
saved state of a screen. Its field SHALL be masked without showing even the
last character typed, SHALL NOT allow cut or copy, SHALL keep the window
out of screenshots and screen recording while it shows, SHALL be excluded from autofill so no password manager fills it or
offers to save it, and SHALL be emptied when "Add the key" starts, when the
panel closes and when step 2 is left. After a process death the field
SHALL be empty. Code that holds it SHALL print it as `<redacted>` when
formatted for debugging. TalkBack SHALL name the field "Password for
<user> on <machine>" and SHALL NOT read the typed characters back as text.

#### Scenario: No save prompt
- **GIVEN** a password manager is the phone's autofill service
- **WHEN** the person types the password, taps "Add the key" and setup finishes
- **THEN** the manager offers neither to fill the field nor to save the password

#### Scenario: No screenshot
- **GIVEN** the password panel shows
- **WHEN** the person takes a screenshot
- **THEN** Android refuses it or records no content of the window

#### Scenario: Copy is refused
- **WHEN** the person long-presses the typed password
- **THEN** no copy or cut action is offered, and the clipboard is unchanged

#### Scenario: The system closes the app
- **GIVEN** a password typed but not sent
- **WHEN** Android ends the app's process in the background and the person returns
- **THEN** step 2 for workstation shows with the password field empty, and the saved state held no password

#### Scenario: Nothing left after
- **WHEN** setup through the password has finished, failed or been cancelled
- **THEN** the field is empty, the app's preferences, machine list and logs hold no password, and no error text contains it

### Requirement: After trust, each outcome says what to do
After "Trust and connect", step 2 SHALL follow the connection and show one
of:

| Outcome | Step 2 shows |
|---|---|
| Connected | step 3 |
| The key's 30 minutes have run out | Android's "Unlock the SSH key" prompt; after a cancel, "Phone key locked" with "Unlock" |
| The machine refuses the key | "<machine> did not accept the phone key.", to run the command there as <user>, that quotes changed by a chat or notes app break it, and "Try again" |
| herdr-core is missing | "herdr-core was not found on <machine>.", to install it there as <user>, and "Try again" |
| herdr-core is too old | today's wording with both versions and "Try again" |
| The app is too old | today's wording |
| Not reached | the wording of "A machine that cannot be reached says why", with "Change the address", which returns to step 1 with the fields filled |

Only a retriable close before the core's first hello, after SSH authentication
and without a missing-core result, nonzero exit status or diagnostic stderr,
on the first attempt started by confirmation SHALL be held back for one retry. The hub SHALL
mark that outcome explicitly as `CoreStartupClosed` with
`startup_retry_pending = true` only while its one following retry is pending
or running. The step SHALL keep "Connecting to <machine>…" for that interval.
The next settlement SHALL clear the flag and show its result even if it is
another identical close. Attempt boundaries SHALL be hub settlements, never
an assumed intervening `Connecting` snapshot; automatic retries keep the old
failure state while clearing `next_retry_ms`.

All outcomes needing a person SHALL show on the first occurrence, including
`KeyRejected`, `NotInstalled`, `NeedsUserAuth`, `NetworkOff`,
`HostKeyUnconfirmed`, `HostKeyMismatch`, `TooOld` and `AppTooOld`, as SHALL
ordinary reach failures. A changed key SHALL use the existing changed-key
screen with both fingerprints and the confirmed forget action. Pause,
restart, manual retry and process death SHALL end any suppression; they
SHALL NOT grant a new grace attempt without a fresh explicit confirmation. "Try again"
SHALL start an attempt at once. The step SHALL advance to step 3 by itself
when the machine connects, also when that happens through the hub's own
retry.

#### Scenario: Straight through
- **GIVEN** the command ran on workstation and herdr-core is installed
- **WHEN** the person taps "Trust and connect" within 30 minutes of unlocking the phone
- **THEN** step 3 shows without another tap and without a failure in between

#### Scenario: herdr-core starts on the first connection
- **GIVEN** the first post-confirmation attempt settles `Unreachable(CoreStartupClosed)` with `next_retry_ms` set and `startup_retry_pending` true
- **WHEN** retry starts (same state and flag, `next_retry_ms` becomes null), then connects
- **THEN** step 2 read "Connecting to workstation…" throughout and step 3 shows

#### Scenario: The retry also closes before hello
- **GIVEN** the first eligible close is hidden
- **WHEN** the retry settles the same `CoreStartupClosed` with a new retry deadline and the flag false, without `Connecting` in between
- **THEN** the failure and recovery action show; repeated snapshots of either settlement do not consume or renew grace

#### Scenario: A first outcome has no automatic retry
- **WHEN** the first post-confirmation attempt settles `NetworkOff` or `HostKeyMismatch` with `next_retry_ms` null
- **THEN** Network off shows with Open settings, or the changed-key screen shows with both fingerprints and its confirmed forget action, immediately

#### Scenario: Other first outcomes need the person
- **WHEN** the first attempt settles `NeedsUserAuth`, `HostKeyUnconfirmed`, `TooOld`, `AppTooOld`, or an ordinary reach failure
- **THEN** its prompt, comparison, version message or reach recovery shows at once; no following attempt is awaited

#### Scenario: Suppression is interrupted
- **GIVEN** one eligible startup close is hidden
- **WHEN** the app pauses, the process dies, the machine restarts or the person retries manually
- **THEN** suppression ends and cannot be restored or rearmed by a saved screen

#### Scenario: The command was not run
- **GIVEN** the manual detail of step 2 shows
- **WHEN** the machine refuses the phone key on the first attempt
- **THEN** step 2 reads "workstation did not accept the phone key." with what to do and "Try again", and the command is still on screen

#### Scenario: Fixed at the desk
- **GIVEN** step 2 reads "workstation did not accept the phone key."
- **WHEN** the person runs the command on the machine and does not touch the phone
- **THEN** step 3 shows after the hub's next retry

#### Scenario: herdr-core missing
- **WHEN** the machine answers that herdr-core is not installed
- **THEN** step 2 reads "herdr-core was not found on workstation." with what to do and "Try again"

#### Scenario: The key prompt is cancelled
- **GIVEN** the last unlock was more than 30 minutes ago
- **WHEN** the person cancels "Unlock the SSH key"
- **THEN** step 2 reads "Phone key locked" with "Unlock", and no prompt comes back by itself

### Requirement: Step 3 says the machine is connected
Step 3 SHALL say that the machine is connected and name its herdr-core
version. When the machine lists agents, it SHALL say how many; when it
lists none, it SHALL say so and say that an agent started at the desk in
herdr shows up by itself. Its one action SHALL be "Continue", which opens
step 4. Step 3 SHALL NOT ask for the notification permission.

#### Scenario: Agents are running
- **GIVEN** workstation runs two agents
- **WHEN** step 3 shows
- **THEN** it reads "workstation is connected.", the herdr-core version and "2 agents are running there.", with "Continue"

#### Scenario: No agents yet
- **GIVEN** workstation runs no agent
- **WHEN** step 3 shows
- **THEN** it reads "No agents are running there yet." and how they show up, with "Continue"

#### Scenario: An agent appears while step 3 shows
- **GIVEN** step 3 reads "No agents are running there yet."
- **WHEN** an agent starts on workstation
- **THEN** the step reads "1 agent is running there."

#### Scenario: The connection drops on step 3
- **WHEN** the machine goes out of reach while step 3 shows
- **THEN** the step says so in the words of the failure table and keeps "Continue"; it does not go back to step 2

### Requirement: Step 4 offers alerts and leads to the first agent
Step 4 SHALL say what alerts do (an agent that needs the person or has
finished, also while kudde is closed) and what they need: a UnifiedPush app
such as ntfy on the phone, and kudde-push on the machine. Its main action
"Turn on alerts" SHALL ask for the notification permission, the only place
in setup that asks, then register with the UnifiedPush app; once this
machine's `kudde-push` document holds the registration, the step SHALL send
a test alert by itself and say whether it arrived within 30 seconds. When
alerts are already on on this phone, step 4 SHALL say so, register this
machine as the machine card does, and offer "Send test alert" instead of
"Turn on alerts". Each way it stops SHALL be said on the step with what to
do: no UnifiedPush app, notifications not allowed (with "Allow", which opens
the app's notification settings), waiting for the UnifiedPush app, and no
test alert within 30 seconds (naming kudde-push on that machine and the
setup guide), each with "Try again" where trying again can help.

Step 4 SHALL NOT block: whatever its state, it SHALL show the routes on.
When the machine lists agents, "Show my agents", which opens the home; when
it lists none, "Start a terminal or agent", which opens the "New terminal"
sheet on that machine, and "Done", which opens the home. Leaving step 4
without alerts SHALL change nothing; the machine card's Alerts row offers
the same later. Step 4 SHALL NOT be part of the resume checkpoint: first
setup is complete at step 3, so after a process death kudde opens the home.
Back on step 4 SHALL return to step 3.

#### Scenario: Alerts turned on
- **GIVEN** step 4 for workstation, ntfy installed, kudde-push running on workstation, and alerts off on this phone
- **WHEN** the person taps "Turn on alerts" and allows notifications
- **THEN** kudde registers with ntfy, writes the registration to workstation's `kudde-push`, a test alert from workstation arrives, and the step says alerts reach this phone

#### Scenario: Not now
- **GIVEN** step 4 for workstation, which runs two agents, with alerts off
- **WHEN** the person taps "Show my agents"
- **THEN** the home shows both agents, the ones that need the person first, no permission was asked, and workstation's card reads alerts off with "Turn on"

#### Scenario: No agents yet
- **GIVEN** workstation runs no agent
- **WHEN** step 4 shows
- **THEN** it offers "Turn on alerts", "Start a terminal or agent" and "Done"

#### Scenario: Starting one
- **WHEN** the person taps "Start a terminal or agent" on step 4
- **THEN** the "New terminal" sheet opens with workstation chosen

#### Scenario: No UnifiedPush app
- **GIVEN** no UnifiedPush app is installed
- **WHEN** the person taps "Turn on alerts"
- **THEN** the step says alerts need a UnifiedPush app such as ntfy, offers "Try again", and still shows the routes on

#### Scenario: Notifications refused
- **WHEN** the person taps "Turn on alerts" and denies the notification permission
- **THEN** the step says notifications are not allowed and offers "Allow"; no registration is written

#### Scenario: kudde-push does not answer
- **GIVEN** alerts are registered with ntfy and kudde-push does not run on workstation
- **WHEN** 30 seconds pass after the test was sent
- **THEN** the step says no test alert came, names kudde-push on workstation and the setup guide, and offers "Try again"

#### Scenario: Alerts already on
- **GIVEN** alerts are on on this phone for another machine and workstation was just added from Machines
- **WHEN** step 4 shows
- **THEN** it says alerts are on, registers workstation, offers "Send test alert", and sends nothing until the person taps it

#### Scenario: Leaving the app on step 4
- **GIVEN** step 4 for workstation during first setup
- **WHEN** Android ends the app's process in the background and the person returns
- **THEN** the home shows, first setup stays completed, and workstation's card holds the alerts state

### Requirement: The title bar explains itself once
The app SHALL show a hint under the title bar the first time a terminal
or its chat opens while the title bar shows the tab's map, including a
one-pane tab (revised decision 11 pick, following spaces-navigation 8.2).
A terminal in no pane has no map and SHALL leave the hint due. The hint SHALL name the bar's gestures as capability
spaces (change `spaces-navigation`) and agent-list define them, and no
others: a sideways swipe walks the panes of the open tab and the bar
bounces at its ends, including only bouncing in a one-pane tab; a swipe up opens the next agent that needs the
person and down goes back; a tap on the tab's map opens the tab overview.
The hint SHALL NOT cover the keys, the compose sheet or the chat's message
box, SHALL NOT cover the terminal's cursor row (it moves to just above
the keys when the cursor sits under it), SHALL NOT open or close the
keyboard or take the input focus, and touches outside it and typing SHALL
reach the screen as usual. A tap on it SHALL close
it and store one "seen" flag in the app's private preferences, after which
it SHALL NOT show again in a terminal or a chat; leaving without that tap
SHALL leave it due. With touch exploration on, it SHALL name the title
bar's actions that replace the swipes and the map, not the swipes.
TalkBack SHALL read it once when it appears, and it SHALL stay focusable,
with a double tap closing it like a tap. With the
system's animations off it SHALL appear and go without animation. It SHALL
be built only once those gestures are on main.

#### Scenario: The first terminal
- **GIVEN** the hint has never been closed
- **WHEN** the person opens a terminal on workstation in a tab of two panes
- **THEN** the hint shows under the title bar, and the keys at the bottom stay in view and usable

#### Scenario: A tab of one pane
- **GIVEN** the hint has never been closed
- **WHEN** the person opens a terminal in a tab of one pane with its one-cell map
- **THEN** the hint shows, explains that sideways only bounces there, and tapping the map opens that tab's overview

#### Scenario: A terminal outside a tab
- **GIVEN** the hint has never been closed
- **WHEN** a terminal in no pane opens with no map
- **THEN** the hint does not show and remains due

#### Scenario: Sideways in a one-pane tab
- **GIVEN** the hint shows on a one-pane tab
- **WHEN** the person swipes sideways on the title bar
- **THEN** the bar bounces and the same terminal stays open

#### Scenario: Typing in a terminal
- **GIVEN** the hint is due and a fresh terminal's prompt sits in its top row
- **WHEN** the hint appears and the person types a command with the keys
- **THEN** the cursor row and the typed text stay in view, uncovered, and the text goes to the terminal

#### Scenario: Closed for good
- **GIVEN** the hint shows
- **WHEN** the person taps it, then opens another terminal, a chat, and kudde again after a restart
- **THEN** the hint shows none of those times

#### Scenario: Left without a tap
- **GIVEN** the hint shows
- **WHEN** the person goes back without tapping it and opens a chat in a tab of two panes
- **THEN** the hint shows over the chat, and its message box is not covered

#### Scenario: The gestures work under it
- **GIVEN** the hint shows in a tab of two panes, on its first pane
- **WHEN** the person swipes sideways on the title bar
- **THEN** the next pane of the tab opens, as without the hint

#### Scenario: Typing is not blocked
- **GIVEN** the hint shows and the person taps the chat's message box
- **WHEN** they type
- **THEN** the text goes into the message box, and the hint covers no part of it or of the keyboard

#### Scenario: What it explains
- **WHEN** the hint shows
- **THEN** it names the sideways swipe inside the tab with its bounce (only a bounce with one pane), up and down through the agents that need the person, and the tap on the tab's map, and nothing else

#### Scenario: With TalkBack
- **WHEN** the hint appears with TalkBack on
- **THEN** TalkBack reads it once, naming the actions "Next terminal", "Previous terminal" and "Next agent that needs you" and the map instead of swipes; it can be reached by swiping through the screen, and a double tap closes it

#### Scenario: Animations off
- **GIVEN** the system's animations are off
- **WHEN** the hint appears and is tapped
- **THEN** it shows and goes at once, without a fade

#### Scenario: Stored flag
- **WHEN** the hint is closed
- **THEN** one boolean is written to its own app-private preferences file, which is excluded from backup like the others, and nothing else is stored

### Requirement: An unfinished setup is easy to find
The home SHALL show each machine whose setup is unfinished: one that has
never connected and is not being connected right now, or one that waits
for the person (its host key not confirmed, its key not accepted,
herdr-core missing or too old). Each gets a notice line with its name and
"finish setup", which opens setup at the machine's step: step 1 while it
has no confirmed host key and has not presented one, step 2 otherwise.
When no machine has agents to show and one is unfinished, the home's
empty text SHALL name that machine and offer "Continue setup", not "No
agents yet". When machines are connected and none runs an agent, "No
agents yet" SHALL also offer "New terminal". On the Machines screen, the
card of an unfinished machine SHALL offer a row that opens the same step:
"Check the host key" for unconfirmed trust, the two key-install choices
for a rejected key, "Change the
address" for one that cannot be reached, "Continue setup" otherwise.

#### Scenario: Left after step 1
- **GIVEN** workstation was reached in step 1 and the person closed the app
- **WHEN** they open kudde the next day
- **THEN** step 2 for workstation resumes before home until the first real connection; for later machine additions, home offers "Continue setup"

#### Scenario: Left with a wrong address
- **GIVEN** the app was closed while step 1 read "No answer from 192.0.2.99."
- **WHEN** the person opens kudde (or taps "Continue setup" for a later machine addition)
- **THEN** step 1 shows with the address, user and port of that machine filled in, and the reason it could not be reached

#### Scenario: One machine works, one waits
- **GIVEN** server is connected with agents and workstation's key is not accepted
- **WHEN** the person opens the home
- **THEN** the agents of server show, and above them a line "workstation · finish setup" that opens step 2 for workstation

#### Scenario: A machine that worked before is only away
- **GIVEN** server has connected before and is switched off
- **THEN** the home shows today's "server unreachable" line, not "finish setup"

#### Scenario: Connected, no agents
- **GIVEN** every listed machine is connected and none runs an agent
- **THEN** the home reads "No agents yet" with its text and "New terminal"

### Requirement: Setup survives leaving the app
Except after successful connection advances to step 3, setup SHALL be where
the person left it when they return: the same step,
the same machine and, in step 1, the typed fields, after a switch to
another app, 60 seconds in the background, a rotation, and a process
death and a cold launch, including the selected key-install detail. (The person has to leave the app during step 2, to get the command
to the machine.) A step SHALL
NOT go back to an earlier one by itself; only Back and "Change the address"
lead from step 2 to step 1. Back, by the arrow or by Android Back, SHALL close
a dialog first, then a key-install detail to its two-choice screen, and
otherwise go one step back:

| From | Back opens |
|---|---|
| the welcome | nothing: the app closes (no arrow is shown) |
| the screen-lock notice | the welcome |
| step 1 | the welcome during first setup; the screen setup was opened from for a later machine |
| step 2 | step 1, with that machine's address, user and port filled in |
| step 3 | the home with its agents (the machine is connected, so setup is done) |
| step 4 | step 3 |

Back from step 1 SHALL remove only a machine added by that visit which was
never reached, and keep the typed fields in the checkpoint, so the main action
on the welcome, or setup opened again, resumes step 1 with them. The saved
step SHALL follow Back down, so a restart resumes the screen Back opened. Before
first setup completes, Back SHALL NOT open home or Machines. No other typed or
added data SHALL be dropped, except a password, which is never kept (see "The
password is never kept").

#### Scenario: Sending the command to yourself
- **GIVEN** the manual detail of step 2 for workstation
- **WHEN** the person shares the command to a chat app, spends three minutes at the desk and returns
- **THEN** step 2 for workstation shows, reconnects by itself and offers "Compare the fingerprint" again

#### Scenario: The system closed the app meanwhile
- **GIVEN** step 2 for workstation, and Android ends the app's process in the background
- **WHEN** the person returns
- **THEN** step 2 for workstation shows

#### Scenario: Back from step 1 during first setup
- **GIVEN** first setup is unfinished and step 1 shows with fields typed and nothing listed
- **WHEN** the person taps the back arrow, then taps "Set up a machine"
- **THEN** the welcome showed first, and step 1 opens again with the same fields

#### Scenario: Back from step 1 of a later machine
- **GIVEN** step 1 opened from Machines after first setup completed, with fields typed and nothing listed
- **WHEN** the person presses back
- **THEN** the screen setup was opened from shows, no machine was added and the unsent fields remain in the checkpoint for resuming

#### Scenario: Back after a failed try
- **GIVEN** step 1 reads "No answer from 192.0.2.99." for a machine this visit added, during first setup or a later addition
- **WHEN** the person presses back
- **THEN** that machine is no longer listed

#### Scenario: Reconnecting does not go back a step
- **GIVEN** step 2 for workstation, not trusted yet, and the app returns from the background
- **WHEN** the app is connecting again and has no host key to show yet
- **THEN** step 2 stays, and step 1 does not show

#### Scenario: Back from step 2
- **GIVEN** step 2 choices show for workstation, during first setup or a later addition
- **WHEN** the person presses back
- **THEN** step 1 shows with workstation's address, user and port filled in, and workstation stays listed

#### Scenario: Back from a key-install detail
- **GIVEN** the manual detail of step 2 for workstation
- **WHEN** the person presses back
- **THEN** step 2's two choices show, still for workstation

#### Scenario: Back then a restart
- **GIVEN** first setup is unfinished and step 2 shows for workstation
- **WHEN** the person presses back, closes kudde and starts it again
- **THEN** step 1 for workstation shows with its fields, not step 2, home or Machines

#### Scenario: Back from step 3
- **GIVEN** step 3 shows workstation connected
- **WHEN** the person presses back
- **THEN** the home shows with workstation's agents

#### Scenario: Back on the screen-lock notice
- **GIVEN** a phone without a screen lock and the notice after the welcome
- **WHEN** the person presses back
- **THEN** the welcome shows

#### Scenario: A tapped alert during setup
- **GIVEN** first setup completed earlier, step 2 of a later addition for workstation, and another machine sends an alert
- **WHEN** the person taps the alert
- **THEN** that agent opens, and workstation stays listed with its way back into setup

### Requirement: Setup works with a screen reader and large text
Each step SHALL be announced with its number and heading. The command SHALL
be one element named "Setup command" that a screen reader can read on
request, never read by itself on arrival. A failure that appears on a step
SHALL be announced once. At the largest system font size every step SHALL
scroll, with no text cut off and no button off screen without scrolling.

#### Scenario: Arriving on step 2 with TalkBack
- **WHEN** step 2 opens with TalkBack on
- **THEN** it reads "Step 2 of 4, This phone's key", not the command

#### Scenario: A failure with TalkBack
- **WHEN** "workstation did not accept the phone key." appears
- **THEN** TalkBack reads it once

#### Scenario: Largest font
- **GIVEN** the system font size at 2x
- **WHEN** each step shows
- **THEN** every text is whole and every button can be reached by scrolling
