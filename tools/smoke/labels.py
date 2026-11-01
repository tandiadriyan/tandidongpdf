#!/usr/bin/env python3
"""Prints the visible text and content descriptions from a `uiautomator dump`, compactly."""
import sys
import xml.etree.ElementTree as ET

try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    print("(no dump)")
    sys.exit(0)
seen = []
for node in root.iter("node"):
    for attr in ("text", "content-desc"):
        v = (node.get(attr) or "").strip().replace("\n", " ")
        if v and v not in seen:
            seen.append(v)
print(" | ".join(s[:40] for s in seen[:60]))
