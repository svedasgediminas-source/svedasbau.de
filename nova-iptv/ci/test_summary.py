#!/usr/bin/env python3
"""Compact JUnit XML summary for CI logs.

Usage: test_summary.py <label> <glob> [<glob> ...]
Groups results by module (path before /build/) and prints per-module totals, the grand
total and every failing test case (name + first line of the message).
"""
import glob
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict


def main() -> int:
    label, patterns = sys.argv[1], sys.argv[2:]
    files = sorted({f for p in patterns for f in glob.glob(p, recursive=True)})
    totals = defaultdict(lambda: [0, 0, 0, 0])  # tests, failures, errors, skipped
    failed = []
    skipped = []
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as e:
            failed.append((path, "unparseable XML: %s" % e))
            continue
        module = path.split("/build/")[0].lstrip("./") or "."
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for s in suites:
            t = totals[module]
            t[0] += int(s.get("tests", 0))
            t[1] += int(s.get("failures", 0))
            t[2] += int(s.get("errors", 0))
            t[3] += int(s.get("skipped", 0))
            for case in s.findall("testcase"):
                for kind in ("failure", "error"):
                    el = case.find(kind)
                    if el is not None:
                        msg = (el.get("message") or el.text or "").strip().splitlines()
                        failed.append(("%s.%s" % (case.get("classname"), case.get("name")),
                                       (msg[0] if msg else kind)[:300]))
                if case.find("skipped") is not None:
                    sk = case.find("skipped")
                    reason = (sk.get("message") or sk.text or "").strip().splitlines()
                    skipped.append("SKIPPED %s.%s: %s" % (case.get("classname"), case.get("name"),
                                                          (reason[0] if reason else "")[:200]))
    print("===== %s: %d result files =====" % (label, len(files)))
    grand = [0, 0, 0, 0]
    for module in sorted(totals):
        t = totals[module]
        grand = [a + b for a, b in zip(grand, t)]
        print("%-28s tests=%-4d failures=%-3d errors=%-3d skipped=%d" % (module, *t))
    print("%-28s tests=%-4d failures=%-3d errors=%-3d skipped=%d" % ("TOTAL", *grand))
    for line in skipped[:20]:
        print(line)
    for name, msg in failed[:40]:
        print("FAILED %s: %s" % (name, msg))
    return 0


if __name__ == "__main__":
    sys.exit(main())
