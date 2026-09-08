#!/usr/bin/env python3
"""Load private device JSON without evaluating shell code. Defaults to preview."""
import argparse
import json
from pathlib import Path
import shlex
import shutil
import subprocess
import sys


def load_device(path):
    data = json.loads(path.read_text())
    def integer(value, low, high):
        if type(value) is not int or not low <= value <= high:
            raise ValueError("Invalid numeric device setting")
        return value
    serial = data["serial"]
    if not isinstance(serial, str) or not serial.strip():
        raise ValueError("Missing device serial")
    width = integer(data["screenWidth"], 1, 20000)
    height = integer(data["screenHeight"], 1, 20000)
    bounds, taps = data["keyboardBounds"], data["pinTaps"]
    if not isinstance(bounds, list) or len(bounds) != 4 or not isinstance(taps, list) or len(taps) != 6:
        raise ValueError("Configure keyboardBounds and six pinTaps for this device")
    left, top, right, bottom = [integer(v, 0, 20000) for v in bounds]
    if not (0 <= left < right <= width and 0 <= top < bottom <= height):
        raise ValueError("Keyboard bounds must be inside the display")
    for point in taps:
        if not isinstance(point, list) or len(point) != 2:
            raise ValueError("Each PIN tap needs two coordinates")
        x, y = [integer(v, 0, 20000) for v in point]
        if not (left <= x < right and top <= y < bottom):
            raise ValueError("PIN tap outside keyboard")
    delay = integer(data.get("pinDelayMs", 300), 50, 1000)
    return serial, {
        "screenWidth": width, "screenHeight": height, "pinDelayMs": delay,
        "pinConfig": ";".join(",".join(map(str, group)) for group in [bounds, *taps]),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path(__file__).resolve().parents[1] / "config/device.local.json")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--steps", type=int, choices=range(1, 10), default=2)
    parser.add_argument("--product", default="")
    parser.add_argument("--max-total", default="")
    timing = parser.add_mutually_exclusive_group()
    timing.add_argument("--at")
    timing.add_argument("--start-delay-ms", type=int)
    parser.add_argument("--poll-ms", type=int, default=20)
    parser.add_argument("--pin-delay-ms", type=int)
    args = parser.parse_args()
    try:
        serial, settings = load_device(args.config)
    except (OSError, ValueError, KeyError, TypeError):
        parser.error("Cannot load device configuration; check its fields. Values omitted for privacy.")
    settings.update({"class": "com.hym.pointer.PointerSequenceTest",
                     "execute": str(args.execute).lower(), "steps": args.steps,
                     "product": args.product, "maxTotal": args.max_total, "pollMs": args.poll_ms})
    if args.at:
        settings["at"] = args.at
    if args.start_delay_ms is not None:
        settings["startDelayMs"] = args.start_delay_ms
    if args.pin_delay_ms is not None:
        settings["pinDelayMs"] = args.pin_delay_ms
    if not args.execute:
        # Preview validates local configuration but need not transmit PIN coordinates.
        settings.pop("pinConfig")
    adb = shutil.which("adb")
    if adb is None:
        parser.error("adb not found; add Android SDK platform-tools to PATH")
    command = ["am", "instrument", "-w"]
    for key, value in settings.items():
        command.extend(["-e", key, str(value)])
    command.append("com.hym.pointer.test/androidx.test.runner.AndroidJUnitRunner")
    # adb shell uses a remote shell. Quote each argument there as well as avoiding
    # a host shell. Do not print the command: PIN coordinates encode a credential.
    print("Running device flow" if args.execute else "Preview only", flush=True)
    try:
        return subprocess.run([adb, "-s", serial, "shell", shlex.join(command)], check=False).returncode
    except OSError:
        print("Unable to start adb; command details omitted", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
