"""Drive the connected device from the command line.

A development tool, not app code: it exists because a headless phone's screen is
only visible through `uiautomator`, which is flaky enough that every call needs a
retry and a fresh dump, and doing that by hand in a shell loop is where the time
goes.

    python scripts/devtap.py dump                 # print every text on screen
    python scripts/devtap.py tap "Good Post"      # tap the node with that text
    python scripts/devtap.py long "maybe"         # long-press it
    python scripts/devtap.py find Post            # list matching nodes + coords
"""
import re
import subprocess
import sys
import time

DEVICE_XML = "/sdcard/ui.xml"


def adb(*args: str) -> str:
    return subprocess.run(
        ["adb", *args], capture_output=True, text=True, check=False
    ).stdout


def dump(attempts: int = 4) -> str:
    """The current UI hierarchy. Retried: `uiautomator` fails on a busy screen."""
    for _ in range(attempts):
        adb("shell", "uiautomator", "dump", DEVICE_XML)
        xml = adb("shell", "cat", DEVICE_XML)
        if "<hierarchy" in xml:
            return xml
        time.sleep(1)
    raise SystemExit("could not dump the screen (is a device connected and awake?)")


def nodes(xml: str):
    for tag in re.findall(r"<node[^>]*>", xml):
        text = re.search(r'text="([^"]*)"', tag)
        bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
        if not text or not bounds:
            continue
        x1, y1, x2, y2 = map(int, bounds.groups())
        yield text.group(1), ((x1 + x2) // 2, (y1 + y2) // 2), tag


def main() -> None:
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    command = sys.argv[1]
    xml = dump()

    if command == "dump":
        seen = []
        for text, _, _ in nodes(xml):
            if text and text not in seen:
                seen.append(text)
        print("\n".join(seen))
        return

    if len(sys.argv) < 3:
        raise SystemExit(f"{command} needs a text argument")
    want = sys.argv[2]

    for text, (x, y), _ in nodes(xml):
        if want.lower() not in text.lower():
            continue
        print(f"{text!r} at {x},{y}")
        if command in ("tap", "long"):
            if command == "long":
                adb("shell", "input", "swipe", str(x), str(y), str(x), str(y), "700")
            else:
                adb("shell", "input", "tap", str(x), str(y))
        return

    raise SystemExit(f"no node matching {want!r}")


if __name__ == "__main__":
    main()
