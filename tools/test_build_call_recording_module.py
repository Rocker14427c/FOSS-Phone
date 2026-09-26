import tempfile
import unittest
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

from build_call_recording_module import (
    APK_ENTRY,
    CAPTURE_PERMISSION,
    PRIVILEGED_PERMISSIONS,
    REQUIRED_PERMISSIONS,
    build_module,
    parse_badging,
    require_recording_enabled,
)


class CallRecordingModuleTest(unittest.TestCase):
    def test_reads_actual_release_and_debug_package(self):
        for package in ("org.fossify.phone", "org.fossify.phone.debug"):
            badging = (
                f"package: name='{package}' versionCode='24' versionName='1.12.0'\n"
                + "".join(f"uses-permission: name='{permission}'\n" for permission in REQUIRED_PERMISSIONS)
            )
            self.assertEqual(parse_badging(badging), (package, "24"))

    def test_rejects_google_play_and_accepts_enabled_resource(self):
        template = (
            "    resource 0x7f050010 bool/show_call_recording\n"
            "      () {value}\n"
            "    resource 0x7f050011 bool/other\n"
            "      () true\n"
        )
        require_recording_enabled(template.format(value="true"))
        require_recording_enabled(template.format(value="(bool) true"))
        for output in (template.format(value="false"), "", "bool/show_call_recording () true"):
            with self.assertRaises(ValueError):
                require_recording_enabled(output)

    def test_rejects_wrong_package_and_missing_permission(self):
        for badging in (
            "package: name='org.other.app' versionCode='1'\n"
            f"uses-permission: name='{CAPTURE_PERMISSION}'\n",
            "package: name='org.fossify.phone' versionCode='24'\n",
            "not valid badging",
        ):
            with self.assertRaises(ValueError):
                parse_badging(badging)

    def test_module_contains_exact_apk_and_matching_partition_allowlist(self):
        for package in ("org.fossify.phone", "org.fossify.phone.debug"):
            with self.subTest(package=package), tempfile.TemporaryDirectory() as directory:
                apk = Path(directory) / "phone.apk"
                output = Path(directory) / "module.zip"
                # A synthetic ZIP is sufficient for testing packaging, not installation.
                with zipfile.ZipFile(apk, "w") as archive:
                    archive.writestr("AndroidManifest.xml", b"test manifest")
                apk_bytes = apk.read_bytes()
                build_module(apk, output, package, "24")
                with zipfile.ZipFile(output) as archive:
                    self.assertEqual(archive.read(APK_ENTRY), apk_bytes)
                    xml = ET.fromstring(archive.read(
                        f"system/etc/permissions/privapp-permissions-{package}.xml"
                    ))
                    app = xml.find("privapp-permissions")
                    self.assertEqual(app.attrib["package"], package)
                    self.assertEqual(
                        {element.attrib["name"] for element in app},
                        set(PRIVILEGED_PERMISSIONS),
                    )
                    self.assertIn(package, archive.read("module.prop").decode())
                    self.assertEqual(archive.read("META-INF/com/google/android/updater-script"), b"#MAGISK\n")
                    self.assertNotIn("sepolicy.rule", archive.namelist())
                with self.assertRaises(FileExistsError):
                    build_module(apk, output, package, "24")
                with self.assertRaises(ValueError):
                    build_module(apk, apk, package, "24")
                self.assertEqual(apk.read_bytes(), apk_bytes)

    def test_rejects_zip_without_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "not-an-apk.zip"
            output = Path(directory) / "module.zip"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("other-file", "test")
            with self.assertRaises(ValueError):
                build_module(apk, output, "org.fossify.phone", "24")
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
