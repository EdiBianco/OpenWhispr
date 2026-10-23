#!/usr/bin/env python3
"""Check translated Android string resources against values/strings.xml.

Reports missing or extra keys, placeholder mismatches (%1$s, %2$d, %%), and
unescaped apostrophes -- in the default strings as well, because aapt rejects
those only later, while this check is meant to be the fast gate. Exits non-zero
when any problem is found.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "res"
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[sd]|%%")
UNESCAPED_APOSTROPHE = re.compile(r"(?<!\\)'")


def load_strings(path):
    root = ET.parse(path).getroot()
    return {node.get("name"): node.text or "" for node in root.findall("string")}


def main():
    base = load_strings(RES / "values" / "strings.xml")
    problems = []
    for key in sorted(base.keys()):
        if UNESCAPED_APOSTROPHE.search(base[key]):
            problems.append(f"values: unescaped apostrophe in {key}")
    for path in sorted(RES.glob("values-*/strings.xml")):
        locale = path.parent.name
        translated = load_strings(path)
        for key in sorted(base.keys() - translated.keys()):
            problems.append(f"{locale}: missing key {key}")
        for key in sorted(translated.keys() - base.keys()):
            problems.append(f"{locale}: extra key {key}")
        for key in sorted(base.keys() & translated.keys()):
            want = sorted(PLACEHOLDER.findall(base[key]))
            got = sorted(PLACEHOLDER.findall(translated[key]))
            if want != got:
                problems.append(f"{locale}: placeholders differ in {key}: {want} vs {got}")
            if UNESCAPED_APOSTROPHE.search(translated[key]):
                problems.append(f"{locale}: unescaped apostrophe in {key}")
    for problem in problems:
        print(problem)
    print(f"{len(problems)} problem(s)")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())

