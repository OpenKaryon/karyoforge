#!/usr/bin/env python3
"""Build a resource-only branding overlay from the pinned, unmodified IDE."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import xml.etree.ElementTree as ET
from zipfile import ZipFile


ASSETS = ("karyoforge.svg", "karyoforge_16.svg", "karyoforge.ico",
          "karyoforge_logo.png", "karyoforge_logo@2x.png")
NS = "http://jetbrains.org/intellij/schema/application-info"


def png_size(data: bytes) -> tuple[int, int]:
    if data[:16] != b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR":
        raise ValueError("Invalid branding PNG header")
    return struct.unpack(">II", data[16:24])


def prepare(sdk: Path, assets: Path, output: Path, expected_build: str) -> None:
    if not re.fullmatch(r"\d+\.\d+\.\d+", expected_build):
        raise ValueError("Expected an exact IntelliJ build number")
    product = json.loads((sdk / "product-info.json").read_text(encoding="utf-8"))
    if product["productCode"] != "IC" or product["buildNumber"] != expected_build:
        raise ValueError("Branding input does not match the pinned Community build")
    with ZipFile(sdk / "lib/app.jar") as archive:
        entry = archive.getinfo("idea/IdeaApplicationInfo.xml")
        document = ET.fromstring(archive.read(entry))
        build_date = "".join(f"{number:02d}" for number in entry.date_time[:5])
    if document.tag != f"{{{NS}}}component":
        raise ValueError("Unexpected upstream application metadata")

    def element(name: str):
        value = document.find(f"{{{NS}}}{name}")
        if value is None:
            raise ValueError(f"Missing upstream metadata: {name}")
        return value

    element("company").set("name", "OpenKaryon")
    element("company").set("url", "https://github.com/OpenKaryon")
    element("build").set("number", "IC-" + expected_build)
    element("build").set("date", build_date)
    element("names").attrib.update(product="KaryoForge", fullname="KaryoForge", edition="",
                                   motto="From reads to results")
    element("logo").set("url", "/karyoforge_logo.png")
    for name in ("icon", "icon-eap"):
        element(name).attrib.update(svg="/karyoforge.svg", **{"svg-small": "/karyoforge_16.svg"})

    policy_name = "consents-OpenKaryon.json"
    policy = (assets / policy_name).read_bytes()
    records = json.loads(policy)
    required = {"rsch.send.usage.stat", "eap", "ai.data.collection.and.use.policy"}
    if ({row["consentId"] for row in records} != required or len(records) != len(required)
            or any(row.get("accepted") is not False or row.get("deleted") is not True for row in records)):
        raise ValueError("OpenKaryon must define unavailable, unaccepted native telemetry consents")

    # Validate the complete explicit input set before creating any output.
    content = {name: (assets / name).read_bytes() for name in ASSETS}
    for name, size in (("karyoforge.svg", "64"), ("karyoforge_16.svg", "16")):
        svg = ET.fromstring(content[name])
        if svg.attrib.get("viewBox") != "0 0 64 64" or any(
                svg.attrib.get(key) != size for key in ("width", "height")):
            raise ValueError(f"Unexpected SVG geometry: {name}")
    for name, size in (("karyoforge_logo.png", (760, 440)),
                       ("karyoforge_logo@2x.png", (1520, 880))):
        if png_size(content[name]) != size:
            raise ValueError(f"Unexpected splash dimensions: {name}")
    if content["karyoforge.ico"][:4] != b"\x00\x00\x01\x00":
        raise ValueError("Invalid Windows icon header")
    ET.register_namespace("", NS)
    metadata = ET.tostring(document, encoding="utf-8", xml_declaration=True) + b"\n"
    if b"__BUILD" in metadata:
        raise ValueError("Unresolved upstream build placeholder")
    (output / "idea").mkdir(parents=True, exist_ok=True)
    (output / "idea/IdeaApplicationInfo.xml").write_bytes(metadata)
    (output / policy_name).write_bytes(policy)
    for name, data in content.items():
        (output / name).write_bytes(data)
    manifest = {"platformBuild": "IC-" + expected_build,
                "consentPolicy": {"resource": policy_name, "sha256": hashlib.sha256(policy).hexdigest()},
                "assets": {name: hashlib.sha256(data).hexdigest() for name, data in content.items()}}
    (output / "branding-manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"Prepared KaryoForge branding for IC-{expected_build}: {len(content)} final assets")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--assets", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--build", required=True)
    args = parser.parse_args()
    prepare(args.sdk, args.assets, args.output, args.build)


if __name__ == "__main__":
    main()
