import tempfile
import unittest
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

from build_call_recording_module import (
    APK_ENTRY, VOICE_APK_ENTRY, VOICE_PACKAGE, PRIVILEGED_PERMISSIONS, REQUIRED_PERMISSIONS, VOICE_PERMISSIONS,
    build_module, parse_badging, require_voice_trials_enabled, require_ci_release_mode,
)


class VoiceTrialModuleTest(unittest.TestCase):
    def badging(self, permissions, package="org.fossify.phone.debug"):
        return f"package: name='{package}' versionCode='25'\n" + "".join(
            f"uses-permission: name='{permission}'\n" for permission in permissions
        )

    def test_voice_grants_are_explicit_not_required_for_existing_recording_apk(self):
        old_apk = self.badging(REQUIRED_PERMISSIONS)
        self.assertEqual(parse_badging(old_apk), ("org.fossify.phone.debug", "25"))
        with self.assertRaises(ValueError):
            parse_badging(old_apk, voice_changer=True)
        complete = REQUIRED_PERMISSIONS | set(VOICE_PERMISSIONS)
        self.assertEqual(parse_badging(self.badging(complete, VOICE_PACKAGE), True), (VOICE_PACKAGE, "25"))
        for permission in VOICE_PERMISSIONS:
            with self.assertRaises(ValueError):
                parse_badging(self.badging(complete - {permission}, VOICE_PACKAGE), True)

    def test_voice_trial_cannot_be_packaged_over_original_app(self):
        permissions = REQUIRED_PERMISSIONS | set(VOICE_PERMISSIONS)
        with self.assertRaises(ValueError):
            parse_badging(self.badging(permissions), voice_changer=True)
        with self.assertRaises(ValueError):
            parse_badging(self.badging(permissions, VOICE_PACKAGE), voice_changer=False)
        # Direct library callers cannot bypass package isolation either.
        with self.assertRaises(ValueError):
            build_module(Path("unused.apk"), Path("unused.zip"), "org.fossify.phone.debug", "25", True)

    def test_legacy_ci_cannot_publish_disabled_demo(self):
        properties = "VERSION_CODE=26\nRELEASE_KIND=voice-demo\n"
        with self.assertRaises(ValueError):
            require_ci_release_mode(properties, github_actions=True, voice_changer=False)
        require_ci_release_mode(properties, github_actions=True, voice_changer=True)
        require_ci_release_mode(properties, github_actions=False, voice_changer=False)
        require_ci_release_mode("VERSION_CODE=25\n", github_actions=True, voice_changer=False)

    def test_requires_lab_build_resource_not_similarly_named_resource(self):
        template = "resource 0x7f050010 bool/experimental_voice_changer\n  () {value}\n"
        for value in ("true", "(bool) true"):
            require_voice_trials_enabled(template.format(value=value))
        for data in ("", template.format(value="false"),
                     template.format(value="true").replace("voice_changer", "voice_changer_other"),
                     "resource 0x7f050010 bool/experimental_voice_changer\n  (night) true\n"):
            with self.assertRaises(ValueError):
                require_voice_trials_enabled(data)

    def test_default_module_never_adds_voice_grants_or_api_exemption(self):
        self.verify_archive(False)

    def test_voice_module_scopes_api_exemption_and_permissions_to_exact_apk(self):
        self.verify_archive(True)

    def verify_archive(self, enabled):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "test.apk"
            module = Path(directory) / "test.zip"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("AndroidManifest.xml", "synthetic test fixture")
            package = VOICE_PACKAGE if enabled else "org.fossify.phone.debug"
            build_module(apk, module, package, "25", enabled)
            with zipfile.ZipFile(module) as archive:
                self.assertEqual(archive.read(VOICE_APK_ENTRY if enabled else APK_ENTRY), apk.read_bytes())
                module_id = "fossify_phone_voice_trial" if enabled else "fossify_phone_call_audio"
                self.assertIn(f"id={module_id}\n", archive.read("module.prop").decode())
                if enabled:
                    self.assertNotIn(APK_ENTRY, archive.namelist())
                permissions = ET.fromstring(archive.read(
                    f"system/etc/permissions/privapp-permissions-{package}.xml"
                )).find("privapp-permissions")
                self.assertEqual(permissions.attrib["package"], package)
                expected = set(PRIVILEGED_PERMISSIONS) | (set(VOICE_PERMISSIONS) if enabled else set())
                self.assertEqual({child.attrib["name"] for child in permissions}, expected)
                api_files = [path for path in archive.namelist() if "/sysconfig/" in path]
                if enabled:
                    self.assertEqual(len(api_files), 1)
                    config = ET.fromstring(archive.read(api_files[0]))
                    self.assertEqual(config.tag, "config")
                    self.assertEqual(len(config), 1)
                    self.assertEqual(config[0].tag, "hidden-api-whitelisted-app")
                    self.assertEqual(config[0].attrib, {"package": package})
                    self.assertIn('"$API" -ge 33', archive.read("customize.sh").decode())
                else:
                    self.assertEqual(api_files, [])
                for path in archive.namelist():
                    self.assertNotIn(path, ("service.sh", "post-fs-data.sh", "sepolicy.rule", "system.prop"))
