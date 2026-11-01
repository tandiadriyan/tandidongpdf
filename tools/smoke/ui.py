#!/usr/bin/env python3
"""Finds a node in a `uiautomator dump` by text or content description and prints its center.

Usage: ui.py ui.xml "Label"     exact match
       ui.py ui.xml "Apply*"    prefix match
       ui.py ui.xml "*respond*" substring match
Exits with status 1 when nothing matches.
"""
import re
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    path, query = sys.argv[1], sys.argv[2]
    contains = len(query) > 2 and query.startswith("*") and query.endswith("*")
    prefix = query.endswith("*") and not contains
    q = query[1:-1] if contains else (query[:-1] if prefix else query)
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        return 1
    for node in root.iter("node"):
        for attr in ("text", "content-desc"):
            value = node.get(attr) or ""
            if (contains and q in value) or (prefix and value.startswith(q)) or (not prefix and not contains and value == q):
                nums = [int(n) for n in re.findall(r"\d+", node.get("bounds", ""))]
                if len(nums) == 4 and nums[2] > nums[0] and nums[3] > nums[1]:
                    print((nums[0] + nums[2]) // 2, (nums[1] + nums[3]) // 2)
                    return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
