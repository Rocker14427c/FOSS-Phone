#!/usr/bin/env python3
"""Package a signed Fossify Phone APK as an opt-in Magisk privileged-app module.

Requires Python 3 and Android SDK build-tools' aapt2. Does not modify a device,
request root, sign an APK, disable SELinux, or download any executable code.
"""

import argparse
import re
import subprocess
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

CAPTURE_PERMISSION = "android.permission.CAPTURE_AUDIO_OUTPUT"
REQUIRED_PERMISSIONS = {
    CAPTURE_PERMISSION,
    "android.permission.RECORD_AUDIO",
    "android.permission.FOREGROUND_SERVICE_MICROPHONE",
}
PRIVILEGED_PERMISSIONS = (CAPTURE_PERMISSION, "android.permission.CALL_PRIVILEGED")
APK_ENTRY = "system/priv-app/FossifyPhone/FossifyPhone.apk"

INSTALLER = """#!/sbin/sh
umask 022
OUTFD=$2
ZIPFILE=$3
ui_print() { echo "$1"; }
if [ ! -f /data/adb/magisk/util_functions.sh ]; then
  ui_print "Install this module from the Magisk app, not recovery."
  exit 1
fi
. /data/adb/magisk/util_functions.sh
install_module
exit 0
"""

CUSTOMIZE = """# Loaded by the Magisk module installer.
[ "$BOOTMODE" = true ] || abort "Install from the Magisk app, not recovery."
[ "$API" -ge 26 ] || abort "Android 8 or newer is required."
ui_print "Privileged call-audio access for Fossify Phone"
ui_print "Use the same signed APK as the installed app. Reboot after installation."
ui_print "ROM/audio-driver support is still required. SELinux is not modified."
set_perm_recursive "$MODPATH" 0 0 0755 0644
"""


def read_apk_metadata(apk: Path, aapt2: str) -> tuple[str, str]:
    result = subprocess.run(
        [aapt2, "dump", "badging", str(apk)],
        check=True, capture_output=True, text=True,
    )
    metadata = parse_badging(result.stdout)
    resources = subprocess.run(
        [aapt2, "dump", "resources", str(apk)],
        check=True, capture_output=True, text=True,
    )
    require_recording_enabled(resources.stdout)
    return metadata


def require_recording_enabled(resources: str) -> None:
    feature = re.search(
        r"^\s*resource [^\n]*\bbool/show_call_recording[^\n]*\n(.*?)(?=^\s*resource |\Z)",
        resources, re.M | re.S,
    )
    if not feature or not re.search(r"^\s*\(\)\s+(?:\(bool\)\s+)?true\s*$", feature[1], re.M):
        raise ValueError("Call recording must be enabled in the APK; use core/foss, not gplay")


def parse_badging(badging: str) -> tuple[str, str]:
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", badging, re.M)
    if not package or package[1] not in ("org.fossify.phone", "org.fossify.phone.debug"):
        raise ValueError("Expected an org.fossify.phone or org.fossify.phone.debug APK")
    permissions = set(re.findall(r"^uses-permission(?:-\S+)?: name='([^']+)'", badging, re.M))
    missing = REQUIRED_PERMISSIONS - permissions
    if missing:
        raise ValueError(f"APK is missing recording permissions: {', '.join(sorted(missing))}; rebuild this branch first")
    return package[1], package[2]


def permission_xml(package: str) -> bytes:
    root = ET.Element("permissions")
    app = ET.SubElement(root, "privapp-permissions", package=package)
    for permission in PRIVILEGED_PERMISSIONS:
        ET.SubElement(app, "permission", name=permission)
    ET.indent(root)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def build_module(apk: Path, output: Path, package: str, version_code: str) -> None:
    if output.resolve() == apk.resolve():
        raise ValueError("Output must not overwrite the input APK")
    # Avoid overwriting an existing release or producing a module from a non-APK file.
    with zipfile.ZipFile(apk) as archive:
        if "AndroidManifest.xml" not in archive.namelist():
            raise ValueError("Input does not contain an APK manifest")
    output.parent.mkdir(parents=True, exist_ok=True)
    module_prop = (
        "id=fossify_phone_call_audio\n"
        "name=Fossify Phone privileged call audio\n"
        f"version={version_code}\nversionCode={version_code}\n"
        "author=FOSS-Phone contributors\n"
        f"description=Systemless privileged install of {package}. "
        "Requests call-audio access; device/ROM support still required.\n"
    )
    files = {
        "module.prop": module_prop,
        "customize.sh": CUSTOMIZE,
        "META-INF/com/google/android/update-binary": INSTALLER,
        "META-INF/com/google/android/updater-script": "#MAGISK\n",
        f"system/etc/permissions/privapp-permissions-{package}.xml": permission_xml(package),
    }
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in files.items():
            info = zipfile.ZipInfo(name)
            mode = 0o755 if name.endswith("update-binary") else 0o644
            info.external_attr = (0o100000 | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
        archive.write(apk, APK_ENTRY)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="Signed core/foss APK (not the Google Play flavor)")
    parser.add_argument("output", type=Path, help="New module ZIP, e.g. build/fossify-call-audio.zip")
    parser.add_argument("--aapt2", default="aapt2", help="Path to Android SDK build-tools/aapt2")
    args = parser.parse_args()
    try:
        package, version_code = read_apk_metadata(args.apk, args.aapt2)
        build_module(args.apk, args.output, package, version_code)
    except (OSError, ValueError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Cannot create module: {error}\n")
    print(f"Created {args.output} for {package}. See docs/call-recording.md before installing.")


if __name__ == "__main__":
    main()
