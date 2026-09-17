#!/bin/bash
# Dump the on-screen text of the connected device.
#
# Sourced, not run: `. scripts/ui-probe.sh`. `dump` writes the UI hierarchy to a
# file and prints every text/content-desc it found, which is the only way to see
# what a headless device is showing.
export MSYS_NO_PATHCONV=1

dump() {
  local out="${1:-/tmp/ui.xml}"
  adb shell "uiautomator dump /sdcard/ui.xml" >/dev/null 2>&1
  adb shell "cat /sdcard/ui.xml" >"$out" 2>/dev/null
  grep -o 'text="[^"]*"' "$out" | sed 's/text="//;s/"$//' | grep -v '^$' | uniq
}

# Tap by the centre of a node whose text matches, so coordinates never have to
# be guessed twice.
tap_text() {
  local want="$1"
  adb shell "uiautomator dump /sdcard/ui.xml" >/dev/null 2>&1
  adb shell "cat /sdcard/ui.xml" >/tmp/ui.xml 2>/dev/null
  python - "$want" <<'PY'
import re, subprocess, sys
want = sys.argv[1]
xml = open('/tmp/ui.xml', encoding='utf-8', errors='replace').read()
for node in re.finditer(r'<node[^>]*>', xml):
    tag = node.group(0)
    if f'text="{want}"' not in tag:
        continue
    m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', tag)
    if not m:
        continue
    x1, y1, x2, y2 = map(int, m.groups())
    x, y = (x1 + x2) // 2, (y1 + y2) // 2
    subprocess.run(['adb', 'shell', 'input', 'tap', str(x), str(y)], check=False)
    print(f'tapped "{want}" at {x},{y}')
    break
else:
    print(f'not found: {want}')
    sys.exit(1)
PY
}
