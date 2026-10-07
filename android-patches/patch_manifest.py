#!/usr/bin/env python3
"""Insert the permissions/features needed for in-WebView camera access
into the generated AndroidManifest.xml. Safe to run multiple times."""
import sys

PERMISSIONS = [
    '    <uses-permission android:name="android.permission.CAMERA" />',
    '    <uses-permission android:name="android.permission.FLASHLIGHT" />',
    '    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />',
]
FEATURES = [
    '    <uses-feature android:name="android.hardware.camera" android:required="false" />',
    '    <uses-feature android:name="android.hardware.camera.autofocus" android:required="false" />',
]


def main(path):
    with open(path, "r", encoding="utf-8") as f:
        content = f.read()

    lines_to_add = [l for l in PERMISSIONS + FEATURES if l.strip() not in content]

    if not lines_to_add:
        print("Manifest already patched, nothing to do.")
        return

    marker = "<application"
    idx = content.find(marker)
    if idx == -1:
        print("Could not find <application> tag; aborting.", file=sys.stderr)
        sys.exit(1)

    insertion = "\n".join(lines_to_add) + "\n\n    "
    patched = content[:idx] + insertion + content[idx:]
    # Pozwala na lokalny test przez ws:// w tej samej sieci Wi-Fi.
    # W produkcji zalecane jest użycie wss:// i wyłączenie tej opcji.
    patched = patched.replace('<application', '<application android:usesCleartextTraffic="true"', 1)

    with open(path, "w", encoding="utf-8") as f:
        f.write(patched)

    print("Patched manifest with:")
    for l in lines_to_add:
        print("  " + l.strip())


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: patch_manifest.py <path-to-AndroidManifest.xml>", file=sys.stderr)
        sys.exit(1)
    main(sys.argv[1])
