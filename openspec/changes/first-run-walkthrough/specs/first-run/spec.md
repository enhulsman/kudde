## ADDED Requirements

Texts in quotes are the app's wording. Every one of them needs the owner's
approval before it ships; the full list, with the strings they replace, is in
`design.md` under "Texts". Names in the scenarios are invented: the machine
"workstation", the user "dev", the address `192.0.2.10`.

### Requirement: The empty home says what setup needs
While no machine is listed, the Agents home SHALL show a welcome in place of
the list: the title "Set up your first machine", one paragraph on what kudde
does, the caption "What you need" over three numbered points (a machine that
runs herdr-core, a way for the phone to reach it, a few minutes at that
machine), and two actions: "Set up a machine", which opens setup at step 1,
and "Import a machine list", which opens the file picker the Machines screen
uses. The welcome SHALL ask for no permission, start no connection and store
nothing; it SHALL show again whenever the list is empty, and SHALL NOT show
once a machine is listed.

#### Scenario: First launch
- **GIVEN** a fresh install on a phone with a screen lock
- **WHEN** the person opens kudde
- **THEN** the home shows "Set up your first machine", the three points under "What you need", "Set up a machine" and "Import a machine list", and no permission prompt appears

#### Scenario: The last machine is removed
- **GIVEN** one machine is listed
- **WHEN** the person removes it
- **THEN** the home shows the welcome again

#### Scenario: A machine is listed but not connected
- **GIVEN** one machine is listed and its setup is not finished
- **WHEN** the person opens the home
- **THEN** the welcome does not show (the unfinished machine does, see "An unfinished setup is easy to find")

#### Scenario: Importing from the welcome
- **WHEN** the person taps "Import a machine list" and picks an exported list with two machines
- **THEN** both machines are listed without host keys, the welcome is gone, and each machine reads as not set up yet

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
The home SHALL show "Set a screen lock first" when the phone has no secure
screen lock (`KeyguardManager.isDeviceSecure` is false), which the phone
key needs: with the reason in words and the action "Set a screen lock", in
place of the welcome and in place of "Could not start the connections";
"Set up a machine" SHALL NOT be offered. The action SHALL
open Android's screen for choosing a PIN, pattern or password, or the
security settings when the phone has no such screen. On each return to the
app the check SHALL run again, and once a lock is set the app SHALL start
its connections without a restart. A start failure with a screen lock set
SHALL keep today's "Could not start the connections" with its reason and
"Try again".

#### Scenario: No screen lock
- **GIVEN** a phone with no PIN, pattern or password
- **WHEN** the person opens kudde for the first time
- **THEN** the home shows "Set a screen lock first", why, and "Set a screen lock"; it shows neither the welcome nor a raw key store error

#### Scenario: A lock is set meanwhile
- **GIVEN** the home shows "Set a screen lock first"
- **WHEN** the person sets a PIN in Android settings and returns to kudde
- **THEN** the home shows the welcome, with no restart of the app

#### Scenario: The phone has no screen for it
- **WHEN** no activity answers the request to choose a screen lock and the person taps "Set a screen lock"
- **THEN** the security settings open, or Android's main settings when those are missing too, and the app does not crash

#### Scenario: Another start failure
- **GIVEN** a phone with a screen lock whose key store fails
- **WHEN** the person opens kudde
- **THEN** the home shows "Could not start the connections", the reason and "Try again", as today

### Requirement: Setup is three steps, the same for every machine
Adding a machine SHALL be one flow of three steps in a fixed order, each on
its own screen under the title "Set up a machine" with "Step n of 3": step 1
"Where is it?", step 2 "At the machine", step 3 "Connected". The welcome's
"Set up a machine" and the Machines screen's "Add machine" SHALL both open
step 1. A step SHALL be reached only when the one before it is done: step 2
only for a machine that is listed, step 3 only for a machine that is
connected.

#### Scenario: From the welcome
- **WHEN** the person taps "Set up a machine"
- **THEN** step 1 shows, titled "Set up a machine", reading "Step 1 of 3" and "Where is it?"

#### Scenario: A second machine
- **GIVEN** one machine is connected
- **WHEN** the person taps "Add machine" on the Machines screen
- **THEN** the same step 1 shows

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
Step 2 SHALL show one command to run on the machine, as the user of step 1,
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
phone key screen uses today. Besides the command, step 2 SHALL offer to
run the same installation from the phone with the account's password once
(see "The app can run the setup with the password once"); the command SHALL
stay on the step either way. The app SHALL NOT ask for a password anywhere
else.

The Phone key row under "This phone" SHALL show the same command without
the host key part, and "Copy only the key line" for people who manage
`authorized_keys` another way.

#### Scenario: The command for a machine that presented an Ed25519 key
- **GIVEN** workstation presented an ED25519 host key in step 1
- **WHEN** step 2 shows
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
- **WHEN** step 2 shows
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
enforced"; nothing in this change loosens it). Step 2 SHALL offer "Compare
the fingerprint" once the machine has presented a host key; it SHALL open
the confirm of render 8, which SHALL say that the command printed the
machine's host key, show the key type and fingerprint, and say what a
different fingerprint means. "Trust and connect" SHALL confirm exactly the
fingerprint the dialog showed. While the machine has not been reached, the
step SHALL say so in place of the button. A machine whose host key waits
for confirmation SHALL lead to step 2 from its card, so the command is at
hand, never to a dialog alone.

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
- **THEN** step 2 shows for that machine, with the command and "Compare the fingerprint"

#### Scenario: No signature before trust
- **GIVEN** a machine added in step 1 and never trusted
- **WHEN** the app connects, reconnects and returns from the background any number of times
- **THEN** the phone key signs nothing for that machine and no "Unlock the SSH key" prompt appears for it

### Requirement: The app can run the setup with the password once
Step 2 SHALL offer "Run it from this phone" under the command. It SHALL
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
- **WHEN** the person taps "Run it from this phone", confirms the fingerprint, types the right password and taps "Add the key"
- **THEN** `authorized_keys` for dev holds the restricted line exactly as the command adds it, the session ends, step 2 reads "Connecting to workstation…", and step 3 shows

#### Scenario: The dialog warns
- **WHEN** the trust dialog of "Run it from this phone" shows
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
- **WHEN** the person taps "Run it from this phone" and confirms the fingerprint
- **THEN** no password field shows, step 2 reads "workstation does not take a password over SSH." and points to the command, which is still on the step

#### Scenario: More than a password
- **GIVEN** the machine asks a second prompt or a prompt that echoes, such as a code
- **WHEN** the person taps "Add the key"
- **THEN** the session ends without answering it, and step 2 reads "workstation asks for more than a password, such as a code." and points to the command

#### Scenario: The person refuses the host key
- **GIVEN** the trust dialog of "Run it from this phone" shows
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

### Requirement: Step 3 leads to the first agent
Step 3 SHALL say that the machine is connected and name its herdr-core
version. When the machine lists agents, it SHALL say how many and offer
"Show my agents", which opens the home. When it lists none, it SHALL say
so, say that an agent started at the desk in herdr shows up by itself, and
offer "Start a terminal or agent", which opens the "New terminal" sheet on
that machine, and "Done". Step 3 SHALL say in one line where alerts are
turned on, and SHALL NOT ask for the notification permission.

#### Scenario: Agents are running
- **GIVEN** workstation runs two agents
- **WHEN** step 3 shows
- **THEN** it reads "workstation is connected.", the herdr-core version and "2 agents are running there.", with "Show my agents"

#### Scenario: To the list
- **WHEN** the person taps "Show my agents"
- **THEN** the home shows both agents, the ones that need the person first

#### Scenario: No agents yet
- **GIVEN** workstation runs no agent
- **WHEN** step 3 shows
- **THEN** it reads "No agents are running there yet.", how they show up, "Start a terminal or agent" and "Done"

#### Scenario: Starting one
- **WHEN** the person taps "Start a terminal or agent"
- **THEN** the "New terminal" sheet opens with workstation chosen

#### Scenario: An agent appears while step 3 shows
- **GIVEN** step 3 reads "No agents are running there yet."
- **WHEN** an agent starts on workstation
- **THEN** the step reads "1 agent is running there." and offers "Show my agents"

#### Scenario: The connection drops on step 3
- **WHEN** the machine goes out of reach while step 3 shows
- **THEN** the step says so in the words of the failure table and keeps "Done"; it does not go back to step 2

### Requirement: The title bar explains itself once
The app SHALL show a hint under the title bar the first time a terminal
or its chat opens while the title bar shows the tab's map, that is in a
tab of more than one pane (decision 11, answered as picked; the scenarios follow its
pick). The hint SHALL name the bar's gestures as capability
spaces (change `spaces-navigation`) and agent-list define them, and no
others: a sideways swipe walks the panes of the open tab and the bar
bounces at its ends; a swipe up opens the next agent that needs the
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
- **WHEN** the person opens a terminal in a tab of one pane, where the title bar shows no map
- **THEN** no hint shows, and it is still due for the first terminal in a tab of more panes

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
- **GIVEN** the hint shows
- **WHEN** the person swipes sideways on the title bar
- **THEN** the next pane of the tab opens, as without the hint

#### Scenario: Typing is not blocked
- **GIVEN** the hint shows and the person taps the chat's message box
- **WHEN** they type
- **THEN** the text goes into the message box, and the hint covers no part of it or of the keyboard

#### Scenario: What it explains
- **WHEN** the hint shows
- **THEN** it names the sideways swipe inside the tab with its bounce, up and down through the agents that need the person, and the tap on the tab's map, and nothing else

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
"Check the host key" and "Add the phone key" as today, "Change the
address" for one that cannot be reached, "Continue setup" otherwise.

#### Scenario: Left after step 1
- **GIVEN** workstation was reached in step 1 and the person closed the app
- **WHEN** they open kudde the next day
- **THEN** the home reads "workstation is not connected yet" with "Continue setup", which opens step 2 for workstation

#### Scenario: Left with a wrong address
- **GIVEN** the app was closed while step 1 read "No answer from 192.0.2.99."
- **WHEN** the person opens kudde and taps "Continue setup"
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
Setup SHALL be where the person left it when they return: the same step,
the same machine and, in step 1, the typed fields, after a switch to
another app, 60 seconds in the background, a rotation, and a process
death. (The person has to leave the app during step 2, to get the command
to the machine.) A step SHALL
NOT go back to an earlier one by itself; "Change the address" is the only
way from step 2 to step 1. Back SHALL close a dialog first; from step 1
it SHALL return to where setup was opened, taking off the list a machine
that this visit added and that was never reached; from step 2 and step 3
it SHALL open the Machines screen with the machine listed. Nothing else a
person typed or added SHALL be dropped, except a password, which is never
kept (see "The password is never kept").

#### Scenario: Sending the command to yourself
- **GIVEN** step 2 for workstation
- **WHEN** the person shares the command to a chat app, spends three minutes at the desk and returns
- **THEN** step 2 for workstation shows, reconnects by itself and offers "Compare the fingerprint" again

#### Scenario: The system closed the app meanwhile
- **GIVEN** step 2 for workstation, and Android ends the app's process in the background
- **WHEN** the person returns
- **THEN** step 2 for workstation shows

#### Scenario: Back from step 1
- **GIVEN** step 1 with fields typed and nothing listed
- **WHEN** the person presses back
- **THEN** the screen setup was opened from shows, and no machine was added

#### Scenario: Back after a failed try
- **GIVEN** step 1 reads "No answer from 192.0.2.99." for a machine this visit added
- **WHEN** the person presses back
- **THEN** that machine is no longer listed

#### Scenario: Reconnecting does not go back a step
- **GIVEN** step 2 for workstation, not trusted yet, and the app returns from the background
- **WHEN** the app is connecting again and has no host key to show yet
- **THEN** step 2 stays, and step 1 does not show

#### Scenario: Back from step 2
- **WHEN** the person presses back on step 2
- **THEN** the Machines screen shows workstation with its link back into setup

#### Scenario: A tapped alert during setup
- **GIVEN** step 2 for workstation, and another machine sends an alert
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
- **THEN** it reads "Step 2 of 3, At the machine", not the command

#### Scenario: A failure with TalkBack
- **WHEN** "workstation did not accept the phone key." appears
- **THEN** TalkBack reads it once

#### Scenario: Largest font
- **GIVEN** the system font size at 2x
- **WHEN** each step shows
- **THEN** every text is whole and every button can be reached by scrolling
