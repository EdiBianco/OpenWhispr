#!/usr/bin/env python3
"""Bump OpenWispr's version in one step.

Updates versionName and versionCode in app/build.gradle.kts and opens a new
section at the top of CHANGELOG.md, so the release workflow (which reads
versionName for the tag and the changelog for the in-app "what's new" notes)
never disagrees with itself.

Usage: python3 scripts/bump_version.py 4.1.0
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "app" / "build.gradle.kts"
CHANGELOG = ROOT / "CHANGELOG.md"

SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")


def parse(version):
    match = SEMVER.match(version)
    if not match:
        sys.exit(f"not a version: {version!r} (expected x.y.z)")
    return tuple(int(part) for part in match.groups())


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__.strip())

    new_version = sys.argv[1]
    parse(new_version)

    gradle = GRADLE.read_text()
    current_name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    if not current_name:
        sys.exit("could not find versionName in app/build.gradle.kts")
    old_version = current_name.group(1)
    if parse(new_version) <= parse(old_version):
        sys.exit(f"{new_version} is not newer than the current {old_version}")

    current_code = re.search(r"versionCode\s*=\s*(\d+)", gradle)
    if not current_code:
        sys.exit("could not find versionCode in app/build.gradle.kts")
    new_code = int(current_code.group(1)) + 1

    gradle = gradle.replace(
        f'versionName = "{old_version}"', f'versionName = "{new_version}"'
    )
    gradle = re.sub(
        r"versionCode\s*=\s*\d+", f"versionCode = {new_code}", gradle, count=1
    )
    GRADLE.write_text(gradle)

    changelog = CHANGELOG.read_text()
    if f"## {new_version}" in changelog:
        sys.exit(f"CHANGELOG.md already has a section for {new_version}")

    anchor = changelog.index("\n## ")
    section = (
        f"\n## {new_version}\n"
        f"- Describe what changed for the user here.\n"
    )
    CHANGELOG.write_text(changelog[:anchor] + section + changelog[anchor:])

    print(f"versionName {old_version} -> {new_version}")
    print(f"versionCode -> {new_code}")
    print("CHANGELOG.md: new section opened — write the notes before pushing.")


if __name__ == "__main__":
    main()
