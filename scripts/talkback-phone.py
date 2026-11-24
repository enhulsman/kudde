#!/usr/bin/env python3
"""Drive TalkBack against a disposable kudde layout on the configured test phone."""
import argparse
import contextlib
import fcntl
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
import uuid
import zipfile

HERE = Path(__file__).resolve().parent
APP = "dev.kudde.android.debug"
PROBE = "dev.kudde.talkbackprobe"
TALKBACK = "com.google.android.marvin.talkback"
TALKBACK_SETTINGS = TALKBACK + "/com.android.talkback.TalkBackPreferencesActivity"
TALKBACK_SERVICE = TALKBACK + "/com.google.android.marvin.talkback.TalkBackService"
SPEECH = re.compile(r'SpeechControllerImpl: Speaking fragment text="(.*)", utteranceId=([^, ]+)')


def heard(log):
    utterances = {}
    for line in log.splitlines():
        match = SPEECH.search(line)
        if match:
            text, utterance = match.groups()
            utterances.setdefault(utterance, []).append(text)
    return [" ".join(fragments) for fragments in utterances.values()]


def require_once(utterances, *parts):
    matches = [text for text in utterances if all(part.casefold() in text.casefold() for part in parts)]
    if len(matches) != 1:
        raise AssertionError(f"Expected one reading of {' / '.join(parts)}, observed {len(matches)}")


def run(*args, data=None, timeout=15, check=True):
    command = [str(arg) for arg in args]
    result = subprocess.run(command, input=data, capture_output=True, text=True, timeout=timeout)
    if result.returncode and check:
        raise RuntimeError(f"{Path(command[0]).name} {command[1]} failed (exit {result.returncode})")
    return result.stdout


def build_probe(work):
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        sdk = Path(shutil.which("adb")).resolve().parents[1]
    sdk = Path(sdk)

    def version(path):
        return tuple(int(part) for part in re.findall(r"\d+", path.parent.name))

    platforms = list(sdk.glob("platforms/android-*/android.jar"))
    tools = list(sdk.glob("build-tools/*/aapt2"))
    if not platforms or not tools:
        raise RuntimeError("Android SDK platform and build tools are required")
    jar = max(platforms, key=version)
    tool = max(tools, key=version).parent
    source = HERE / "talkback-probe"
    classes = work / "classes"
    classes.mkdir()
    apk = work / "probe.apk"
    run("javac", "--release", "11", "-cp", jar, "-d", classes, source / "Probe.java", timeout=40)
    run(tool / "d8", "--lib", jar, "--min-api", 26, "--output", work / "dex.zip",
        classes / "dev/kudde/talkbackprobe/Probe.class", timeout=40)
    run(tool / "aapt2", "link", "--manifest", source / "AndroidManifest.xml", "-I", jar,
        "--min-sdk-version", 26, "--target-sdk-version", 35, "-o", work / "unsigned.apk")
    with zipfile.ZipFile(work / "unsigned.apk", "a") as apk_file, zipfile.ZipFile(work / "dex.zip") as dex:
        apk_file.writestr("classes.dex", dex.read("classes.dex"))
    run(tool / "zipalign", 4, work / "unsigned.apk", work / "aligned.apk")
    # This key lives only for this run and signs only the temporary helper.
    password = "temporary-probe"
    key = work / "probe.jks"
    run("keytool", "-genkeypair", "-keystore", key, "-storepass", password, "-keypass", password,
        "-alias", "probe", "-keyalg", "RSA", "-validity", 2, "-dname", "CN=Local Android test", timeout=30)
    run(tool / "apksigner", "sign", "--ks", key, "--ks-pass", f"pass:{password}", "--out", apk, work / "aligned.apk")
    return apk


class Phone:
    def __init__(self, serial):
        self.adb = ["adb", "-s", serial]

    def call(self, *args, **kwargs):
        if args[0] == "shell":
            return run(*self.adb, "shell", shlex.join(map(str, args[1:])), **kwargs)
        return run(*self.adb, *args, **kwargs)

    def probe(self, phase, **parameters):
        extras = [arg for item in parameters.items() for arg in ("-e", *item)]
        output = self.call("shell", "am", "instrument", "-w", "--user", 0,
                           "-e", "phase", phase, *extras, f"{PROBE}/.Probe", timeout=30)
        if f"PASS {phase}" not in output.splitlines():
            reason = next((line for line in output.splitlines() if line.startswith("FAIL ")), "")
            raise AssertionError(f"Phone probe {phase} failed. {reason}")
        return output

    def setting(self, namespace, key, value=None):
        if value is None:
            return self.call("shell", "settings", "get", namespace, key).strip()
        if value == "null":
            self.call("shell", "settings", "delete", namespace, key)
        else:
            self.call("shell", "settings", "put", namespace, key, value)

    def set_temporarily(self, stack, namespace, key, value):
        original = self.setting(namespace, key)

        def restore():
            self.setting(namespace, key, original)
            if self.setting(namespace, key) != original:
                raise RuntimeError(f"Unable to restore test phone setting {key}")

        stack.callback(restore)
        self.setting(namespace, key, value)

    def log_level(self, level=None):
        self.call("shell", "am", "start", "-f", "0x10008000", "-n", TALKBACK_SETTINGS)
        output = self.probe("log", **({"level": level} if level else {}))
        match = re.search(r"^Log level (\w+)$", output, re.M)
        if not match:
            raise RuntimeError("Unable to read the TalkBack developer log level")
        return match[1]

    def gesture(self, size, positions, twice=False):
        width, height = size
        axes = {"ABS_MT_SLOT": (0, 2), "ABS_MT_TRACKING_ID": (0, 65535),
                "ABS_MT_POSITION_X": (0, width - 1), "ABS_MT_POSITION_Y": (0, height - 1),
                "ABS_MT_PRESSURE": (0, 255)}
        commands = []

        def append(command, **values):
            commands.append(dict(id=1, command=command, **values))

        def inject(events):
            append("inject", events=events + ["EV_SYN", "SYN_REPORT", 0])

        append("register", name="Kudde test touchscreen", vid=6353, pid=9242, bus="usb",
               configuration=[{"type": "UI_SET_EVBIT", "data": ["EV_KEY", "EV_ABS"]},
                              {"type": "UI_SET_KEYBIT", "data": ["BTN_TOUCH", "BTN_TOOL_FINGER"]},
                              {"type": "UI_SET_ABSBIT", "data": list(axes)},
                              {"type": "UI_SET_PROPBIT", "data": ["INPUT_PROP_DIRECT"]}],
               abs_info=[dict(code=axis, info=dict(value=0, minimum=lo, maximum=hi, fuzz=0, flat=0, resolution=0))
                         for axis, (lo, hi) in axes.items()])
        append("delay", duration=1000)
        for repetition in range(2 if twice else 1):
            for slot, (x, y) in enumerate(positions):
                events = ["EV_ABS", "ABS_MT_SLOT", slot, "EV_ABS", "ABS_MT_TRACKING_ID", 1 + repetition * 3 + slot,
                          "EV_ABS", "ABS_MT_POSITION_X", x, "EV_ABS", "ABS_MT_POSITION_Y", y,
                          "EV_ABS", "ABS_MT_PRESSURE", 65]
                if slot == 0:
                    events += ["EV_KEY", "BTN_TOUCH", 1, "EV_KEY", "BTN_TOOL_FINGER", 1]
                inject(events)
                append("delay", duration=24 if len(positions) > 1 else 45)
            for slot in reversed(range(len(positions))):
                events = ["EV_ABS", "ABS_MT_SLOT", slot, "EV_ABS", "ABS_MT_TRACKING_ID", -1]
                if slot == 0:
                    events += ["EV_KEY", "BTN_TOUCH", 0, "EV_KEY", "BTN_TOOL_FINGER", 0]
                inject(events)
                append("delay", duration=65 if twice and repetition == 0 else 25)
        append("delay", duration=1100)
        self.call("shell", "uinput", "-", data="".join(json.dumps(item) + "\n" for item in commands))

    def action(self, size, pane, label):
        width, height = size
        positions = [(width * x // 1440, height * 850 // 3120) for x in (440, 540, 640)]
        for attempt in range(2):
            self.probe("focus", pane=pane)
            self.gesture(size, positions)
            try:
                output = self.probe("choice", action=label)
                break
            except AssertionError as failure:
                if attempt or "Could not find TalkBack Actions menu" not in str(failure):
                    raise
        match = re.search(r"^Choice (\d+) (\d+)$", output, re.M)
        if not match:
            raise RuntimeError("The TalkBack action did not expose tap coordinates")
        return tuple(map(int, match.groups()))

    @contextlib.contextmanager
    def speech(self):
        pid = self.call("shell", "pidof", TALKBACK).strip().split()
        if len(pid) != 1:
            raise RuntimeError("TalkBack is not running in a single process")
        command = [*self.adb, "logcat", f"--pid={pid[0]}", "-v", "threadtime", "-T", "1"]
        with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True) as process:
            lines = []

            def collect():
                for line in process.stdout:
                    if SPEECH.search(line):
                        lines.append(line)

            reader = threading.Thread(target=collect, daemon=True)
            reader.start()
            try:
                time.sleep(.2)
                yield lines
            finally:
                process.terminate()
                reader.join(timeout=3)
                if reader.is_alive():
                    process.kill()
                    reader.join(timeout=3)


def rpc(method, **params):
    requests = [{"jsonrpc": "2.0", "id": 0, "method": "hello", "params": {"generation": 1, "framed": False}},
                {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}]
    output = run("herdr-core", "connect", data="".join(json.dumps(item) + "\n" for item in requests))
    result = next((item for item in map(json.loads, output.splitlines()) if item.get("id") == 1), {})
    if "result" not in result:
        error = result.get("error", {})
        if error.get("data", {}).get("kind") == "conflict":
            return None
        raise RuntimeError(f"herdr-core {method} failed")
    return result["result"]


def update_layout(space, remove=False):
    for _ in range(5):
        doc = rpc("doc.get", name="herdr-app")
        layout = doc["value"]
        if not isinstance(layout, dict) or not isinstance(layout.get("spaces"), list):
            raise RuntimeError("The local herdr-core has no desktop layout to stage against")
        others = [existing for existing in layout["spaces"] if existing.get("id") != space["id"]]
        if remove and len(others) == len(layout["spaces"]):
            return
        layout["spaces"] = others if remove else others + [space]
        if rpc("doc.set", name="herdr-app", version=doc["version"], value=layout) is not None:
            return
    raise RuntimeError("The Herdr layout changed during fixture setup or cleanup")


def fixture(stack, work, shells=False, same_names=False):
    suffix = secrets.token_hex(4)
    place = f"talkback-{suffix}"
    folder = work / place
    folder.mkdir()
    # Ignore real agents. The temporary layout and these terminals are the only mutations.
    space = dict(id="sp" + uuid.uuid4().hex, folder=str(folder), name=place, tabs=[])
    panes = []
    names = [f"tb-pane-{suffix}"] * 2 if same_names else [f"tb-source-{suffix}", f"tb-target-{suffix}"]
    for name in names:
        fifo = folder / f"{name}-{len(panes)}"
        os.mkfifo(fifo)
        if same_names:
            # herdr-core refuses a repeated --name, so both panes run one program under this name
            # and show it as their title, as two unnamed shells in a tab do.
            program = folder / name
            if not program.exists():
                program.symlink_to(shutil.which("sleep"))
            command = ["--no-parent", "--no-notify", "--cwd", folder, program, "86400"]
        else:
            command = ["--name", name, "--no-parent", "--no-notify", "--cwd", folder,
                       *(["sh", "-c", "exec sleep 86400"] if shells else
                         ["env", f"FAKE_AGENT_FIFO={fifo}", HERE / "fake-agent.sh", "--loop"])]
        output = run("herdr-core", "run", "--json", "--bg", *command)
        terminal = json.loads(output)["address"]

        def stop(terminal=terminal):
            run("herdr-core", "kill", terminal, check=False)
            run("herdr-core", "rm", terminal, check=False)
            active = json.loads(run("herdr-core", "ls"))["terminals"]
            if any(item.get("address") == terminal for item in active):
                raise RuntimeError("Could not remove a test terminal")

        stack.callback(stop)
        panes.append(dict(content={"terminal": terminal}, id="p" + uuid.uuid4().hex,
                          type="block", size={"weight": 0.5}))
    space["tabs"].append(dict(id="tb" + uuid.uuid4().hex, name="build", root={"type": "row", "children": panes}))
    stack.callback(update_layout, space, True)
    update_layout(space)
    return place, names


def exercise(phone, size, place, names):
    failures = []
    source, target = names
    for pane, action, expected_map, expected_words, label in [
        (source, "Next terminal", "Tab overview, terminal 2 of 2", (target, place), "next pane title and place"),
        (target, "Next terminal", "Tab overview, terminal 2 of 2", ("No more terminals in this tab",), "tab-end message"),
    ]:
        try:
            coordinates = phone.action(size, pane, action)
            if not (0 < coordinates[0] < size[0] and 0 < coordinates[1] < size[1]):
                raise RuntimeError("The TalkBack action is outside the portrait display")
            with phone.speech() as lines:
                phone.gesture(size, [coordinates], twice=True)
                phone.probe("pane", map=expected_map)
                # Include delayed TalkBack focus recovery and utterances, not just the first callback.
                time.sleep(float(os.environ.get("TALKBACK_LISTEN", "2")))
            utterances = heard("".join(lines))
            if os.environ.get("TALKBACK_VERBOSE"):
                for line in lines:
                    print("  " + line.rstrip()[:200], flush=True)
            if not utterances:
                raise RuntimeError("TalkBack did not expose speech in the test interval")
            require_once(utterances, *expected_words)
            print(f"PASS {label} spoken once", flush=True)
        except (AssertionError, RuntimeError) as failure:
            failures.append(f"{label}: {failure}")
    if failures:
        raise AssertionError("\n".join(failures))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reduced-motion", action="store_true", help="disable Android animations for this run")
    parser.add_argument("--shells", action="store_true", help="stage plain shells instead of fake agents")
    parser.add_argument("--same-names", action="store_true", help="give both panes the same name, as two shells often have")
    parser.add_argument("--hold", action="store_true",
                        help="stage the fake panes with TalkBack on, then wait for a hand check until the printed stop file exists")
    options = parser.parse_args()
    lock = Path(tempfile.gettempdir()) / f"kudde-talkback-{os.getuid()}.lock"
    with lock.open("a") as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise RuntimeError("A local TalkBack phone check is already running") from exc
        run_phone_check(options)


def run_phone_check(options):
    for name in ("adb", "javac", "keytool", "herdr-core"):
        if not shutil.which(name):
            raise RuntimeError(f"Required executable not found: {name}")
    serial = run(HERE / "phone.sh", "--print-serial").strip()
    phone = Phone(serial)
    if phone.call("get-state").strip() != "device":
        raise RuntimeError("The configured ADB test phone is offline")
    for package in (APP, TALKBACK):
        if not phone.call("shell", "pm", "path", "--user", 0, package, check=False).strip():
            raise RuntimeError(f"Required on the test phone: {package}")
    if phone.call("shell", "pm", "path", "--user", 0, PROBE, check=False).strip():
        raise RuntimeError("A TalkBack helper is already installed on this phone")
    if not phone.call("shell", "command", "-v", "uinput", check=False).strip():
        raise RuntimeError("The test phone needs Android uinput for TalkBack gestures")
    dimensions = re.findall(r"(\d+)x(\d+)", phone.call("shell", "wm", "size"))
    if not dimensions:
        raise RuntimeError("Could not read the test phone display size")
    size = tuple(map(int, dimensions[-1]))

    def interrupted(*_):
        raise KeyboardInterrupt()

    signal.signal(signal.SIGTERM, interrupted)
    with tempfile.TemporaryDirectory(prefix="kudde-talkback-") as temporary, contextlib.ExitStack() as restore:
        work = Path(temporary)
        print("Building temporary TalkBack probe", flush=True)
        apk = build_probe(work)
        def uninstall():
            phone.call("shell", "pm", "uninstall", "--user", 0, PROBE, check=False)
            if phone.call("shell", "pm", "path", "--user", 0, PROBE, check=False).strip():
                raise RuntimeError("Could not uninstall the temporary TalkBack helper")

        restore.callback(uninstall)
        phone.call("install", "--user", 0, apk, timeout=60)
        print("Preparing fake panes on the configured test phone", flush=True)
        place, names = fixture(restore, work, options.shells, options.same_names)
        # Unlock and launch before enabling speech logs. The staged screens are safe test input.
        run(HERE / "phone.sh", "unlock", timeout=20)
        run(HERE / "phone.sh", "launch")
        restore.callback(run, HERE / "phone.sh", "launch")
        phone.probe("open", pane=names[0])
        phone.set_temporarily(restore, "system", "screen_off_timeout", "300000")
        if options.reduced_motion:
            phone.set_temporarily(restore, "global", "animator_duration_scale", "0")
        key = "enabled_accessibility_services"
        previous = phone.setting("secure", key)
        services = [] if previous in ("", "null") else previous.split(":")
        if not any(service.startswith(TALKBACK + "/") for service in services):
            phone.set_temporarily(restore, "secure", key, ":".join([*services, TALKBACK_SERVICE]))
        phone.set_temporarily(restore, "secure", "accessibility_enabled", "1")
        print("Preparing TalkBack speech logging", flush=True)
        original = phone.log_level()

        def restore_log():
            phone.log_level(original)
            if phone.log_level() != original:
                raise RuntimeError("Could not restore the original TalkBack log level")

        restore.callback(restore_log)
        phone.log_level("VERBOSE")
        if phone.log_level() != "VERBOSE":
            raise RuntimeError("TalkBack did not accept verbose speech logging")
        run(HERE / "phone.sh", "launch")
        if options.hold:
            stop = Path(tempfile.gettempdir()) / f"kudde-talkback-{os.getuid()}.stop"
            stop.unlink(missing_ok=True)
            print(f"Staged {names[0]} and {names[1]} in space {place}; touch {stop} to clean up", flush=True)
            with phone.speech() as lines:
                try:
                    while not stop.exists():
                        time.sleep(1)
                    stop.unlink(missing_ok=True)
                finally:
                    for line in lines:
                        print("  " + line.rstrip()[:200], flush=True)
            return
        print("Checking speech through the TalkBack Actions menu", flush=True)
        exercise(phone, size, place, names)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("TalkBack check interrupted", file=sys.stderr)
        sys.exit(130)
    except (AssertionError, RuntimeError, subprocess.TimeoutExpired) as failure:
        print(f"TalkBack check failed: {failure}", file=sys.stderr)
        sys.exit(1)
