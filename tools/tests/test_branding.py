"""Branding input boundaries using the committed final assets and a synthetic SDK."""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("branding", ROOT / "tools/branding.py")
BRANDING = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BRANDING)


class BrandingTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="forge branding ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.sdk = self.root / "sdk"
        (self.sdk / "lib").mkdir(parents=True)
        self.product = self.sdk / "product-info.json"
        self.product.write_text(json.dumps({"productCode": "IC", "buildNumber": "252.28539.97"}))
        self.jar = self.sdk / "lib/app.jar"
        with ZipFile(self.jar, "w") as archive:
            archive.writestr("idea/IdeaApplicationInfo.xml", f'''<component xmlns="{BRANDING.NS}">
                <version major="2025" minor="2.6.3"/><company name="Upstream"/>
                <build number="IC-__BUILD__" date="__BUILD_DATE__" majorReleaseDate="20250731"/>
                <logo/><icon/><icon-eap/><names script="idea"/>
                <essential-plugin>com.intellij.java</essential-plugin></component>''')
        self.output = self.root / "output"

    def test_overlay_preserves_platform_contract_and_does_not_modify_sdk(self):
        before = self.jar.read_bytes()
        BRANDING.prepare(self.sdk, ROOT / "installer/branding", self.output, "252.28539.97")
        self.assertEqual(before, self.jar.read_bytes())
        document = ET.parse(self.output / "idea/IdeaApplicationInfo.xml")
        ns = {"a": BRANDING.NS}
        self.assertEqual("com.intellij.java", document.findtext("a:essential-plugin", namespaces=ns))
        self.assertEqual("idea", document.find("a:names", ns).attrib["script"])
        self.assertEqual("20250731", document.find("a:build", ns).attrib["majorReleaseDate"])
        self.assertEqual("IC-252.28539.97", document.find("a:build", ns).attrib["number"])
        manifest = json.loads((self.output / "branding-manifest.json").read_text())
        self.assertEqual(set(BRANDING.ASSETS), set(manifest["assets"]))
        self.assertEqual(set(BRANDING.ASSETS) | {"idea/IdeaApplicationInfo.xml", "branding-manifest.json"},
                         {path.relative_to(self.output).as_posix() for path in self.output.rglob("*") if path.is_file()})

    def test_wrong_platform_fails_before_creating_an_overlay(self):
        self.product.write_text(json.dumps({"productCode": "IC", "buildNumber": "252.0.1"}))
        with self.assertRaisesRegex(ValueError, "pinned Community build"):
            BRANDING.prepare(self.sdk, ROOT / "installer/branding", self.output, "252.28539.97")
        self.assertFalse(self.output.exists())

    def test_small_icon_has_distinct_logical_size_and_matching_artwork(self):
        assets = ROOT / "installer/branding"
        large = ET.parse(assets / "karyoforge.svg").getroot()
        small = ET.parse(assets / "karyoforge_16.svg").getroot()
        self.assertEqual(("64", "16"), (large.attrib["width"], small.attrib["width"]))
        self.assertEqual(large.attrib["viewBox"], small.attrib["viewBox"])
        self.assertEqual([ET.tostring(node) for node in large], [ET.tostring(node) for node in small])


if __name__ == "__main__":
    unittest.main()
