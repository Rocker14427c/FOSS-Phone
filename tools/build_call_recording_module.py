#!/usr/bin/env python3
"""Package a signed Fossify Phone APK as an opt-in Magisk privileged-app module.

Requires Python 3 and Android SDK build-tools' aapt2. Does not modify a device,
request root, sign an APK, disable SELinux, or download any executable code.
"""

import argparse
import os
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
VOICE_PERMISSIONS = (
    "android.permission.CALL_AUDIO_INTERCEPTION",
    "android.permission.MODIFY_PHONE_STATE",
)
APK_ENTRY = "system/priv-app/FossifyPhone/FossifyPhone.apk"
VOICE_PACKAGE = "org.fossify.phone.voice_trial.debug"
VOICE_APK_ENTRY = "system/priv-app/FossifyPhoneVoiceTrial/FossifyPhoneVoiceTrial.apk"

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


def read_apk_metadata(apk: Path, aapt2: str, voice_changer: bool = False) -> tuple[str, str]:
    result = subprocess.run(
        [aapt2, "dump", "badging", str(apk)],
        check=True, capture_output=True, text=True,
    )
    metadata = parse_badging(result.stdout, voice_changer)
    resources = subprocess.run(
        [aapt2, "dump", "resources", str(apk)],
        check=True, capture_output=True, text=True,
    )
    require_recording_enabled(resources.stdout)
    if voice_changer:
        require_voice_trials_enabled(resources.stdout)
    return metadata


def require_recording_enabled(resources: str) -> None:
    feature = re.search(
        r"^\s*resource [^\n]*\bbool/show_call_recording[^\n]*\n(.*?)(?=^\s*resource |\Z)",
        resources, re.M | re.S,
    )
    if not feature or not re.search(r"^\s*\(\)\s+(?:\(bool\)\s+)?true\s*$", feature[1], re.M):
        raise ValueError("Call recording must be enabled in the APK; use core/foss, not gplay")


def require_voice_trials_enabled(resources: str) -> None:
    feature = re.search(
        r"^\s*resource [^\n]*\bbool/experimental_voice_changer\b[^\n]*\n(.*?)(?=^\s*resource |\Z)",
        resources, re.M | re.S,
    )
    if not feature or not re.search(r"^\s*\(\)\s+(?:\(bool\)\s+)?true\s*$", feature[1], re.M):
        raise ValueError("Voice trial module requires an APK built with -PexperimentalVoiceChanger=true")


def parse_badging(badging: str, voice_changer: bool = False) -> tuple[str, str]:
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)'", badging, re.M)
    allowed = (VOICE_PACKAGE,) if voice_changer else ("org.fossify.phone", "org.fossify.phone.debug")
    if not package or package[1] not in allowed:
        raise ValueError(f"Expected APK package in {allowed}; voice trials must use the isolated lab package")
    permissions = set(re.findall(r"^uses-permission(?:-\S+)?: name='([^']+)'", badging, re.M))
    required = REQUIRED_PERMISSIONS | (set(VOICE_PERMISSIONS) if voice_changer else set())
    missing = required - permissions
    if missing:
        raise ValueError(f"APK is missing required permissions: {', '.join(sorted(missing))}; rebuild this branch first")
    return package[1], package[2]


def permission_xml(package: str, voice_changer: bool = False) -> bytes:
    root = ET.Element("permissions")
    app = ET.SubElement(root, "privapp-permissions", package=package)
    for permission in PRIVILEGED_PERMISSIONS + (VOICE_PERMISSIONS if voice_changer else ()):
        ET.SubElement(app, "permission", name=permission)
    ET.indent(root)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def hidden_api_xml(package: str) -> bytes:
    root = ET.Element("config")
    ET.SubElement(root, "hidden-api-whitelisted-app", package=package)
    ET.indent(root)
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def build_module(apk: Path, output: Path, package: str, version_code: str, voice_changer: bool = False) -> None:
    allowed = (VOICE_PACKAGE,) if voice_changer else ("org.fossify.phone", "org.fossify.phone.debug")
    if package not in allowed:
        raise ValueError("Module type and APK package do not match")
    if output.resolve() == apk.resolve():
        raise ValueError("Output must not overwrite the input APK")
    # Avoid overwriting an existing release or producing a module from a non-APK file.
    with zipfile.ZipFile(apk) as archive:
        if "AndroidManifest.xml" not in archive.namelist():
            raise ValueError("Input does not contain an APK manifest")
    output.parent.mkdir(parents=True, exist_ok=True)
    module_prop = (
        f"id={'fossify_phone_voice_trial' if voice_changer else 'fossify_phone_call_audio'}\n"
        f"name={'Fossify Phone EXPERIMENTAL voice trial' if voice_changer else 'Fossify Phone privileged call audio'}\n"
        f"version={version_code}\nversionCode={version_code}\n"
        "author=FOSS-Phone contributors\n"
        f"description=Systemless privileged install of {package}. "
        "Requests call-audio access; device/ROM support still required.\n"
    )
    files = {
        "module.prop": module_prop,
        "customize.sh": CUSTOMIZE + (
            '\n[ "$API" -ge 33 ] || abort "Voice trials require Android 13 or newer."\n'
            'ui_print "EXPERIMENTAL voice trials: call audio may be interrupted."\n'
            if voice_changer else ""
        ),
        "META-INF/com/google/android/update-binary": INSTALLER,
        "META-INF/com/google/android/updater-script": "#MAGISK\n",
        f"system/etc/permissions/privapp-permissions-{package}.xml": permission_xml(package, voice_changer),
    }
    if voice_changer:
        # Per-system-app exemption only, never a global hidden_api_policy/SELinux change.
        files[f"system/etc/sysconfig/voice-trial-{package}.xml"] = hidden_api_xml(package)
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in files.items():
            info = zipfile.ZipInfo(name)
            mode = 0o755 if name.endswith("update-binary") else 0o644
            info.external_attr = (0o100000 | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
        archive.write(apk, VOICE_APK_ENTRY if voice_changer else APK_ENTRY)


def require_ci_release_mode(properties: str, github_actions: bool, voice_changer: bool) -> None:
    """An old workflow must not publish a voice-demo version with effects disabled."""
    voice_release = re.search(r"^RELEASE_KIND=voice-demo\s*$", properties, re.M) is not None
    if github_actions and voice_release and not voice_changer:
        raise ValueError(
            "Voice-demo release blocked: the legacy recorder workflow builds the wrong flavor. "
            "Activate ci/build-and-release-voice-demo.yml; use EXPERIMENTAL_VOICE_CHANGER=1 "
            "and --experimental-voice-changer. No demo artifact was published."
        )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="Signed core/foss APK (not the Google Play flavor)")
    parser.add_argument("output", type=Path, help="New module ZIP, e.g. build/fossify-call-audio.zip")
    parser.add_argument("--aapt2", default="aapt2", help="Path to Android SDK build-tools/aapt2")
    parser.add_argument("--experimental-voice-changer", action="store_true",
                        help="Opt in to extra call interception/mode permissions and scoped SystemApi access")
    args = parser.parse_args()
    try:
        properties = (Path(__file__).resolve().parents[1] / "gradle.properties").read_text()
        require_ci_release_mode(properties, os.environ.get("GITHUB_ACTIONS") == "true", args.experimental_voice_changer)
        package, version_code = read_apk_metadata(args.apk, args.aapt2, args.experimental_voice_changer)
        build_module(args.apk, args.output, package, version_code, args.experimental_voice_changer)
    except (OSError, ValueError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Cannot create module: {error}\n")
    guide = "docs/voice-changer.md" if args.experimental_voice_changer else "docs/call-recording.md"
    print(f"Created {args.output} for {package}. See {guide} before installing.")


if __name__ == "__main__":
    main()
