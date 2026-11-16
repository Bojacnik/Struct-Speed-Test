#!/usr/bin/env python3
"""Merges result CSVs from the C# and Java runners into one Markdown report.

Usage: tools/compare.py results/csharp.csv results/java.csv [more.csv ...] > results/summary.md
"""
import csv
import sys
from collections import OrderedDict


def load(paths):
    runs = OrderedDict()  # label -> {(size, case): row}
    for path in paths:
        with open(path, newline="") as f:
            for row in csv.DictReader(f):
                label = f"{row['lang']} ({row['runtime']})"
                runs.setdefault(label, {})[(int(row["size"]), row["case"])] = row
    return runs


def num(row, key):
    value = row.get(key, "") if row else ""
    return float(value) if value not in ("", None) else None


def fmt(value, digits=2):
    return "–" if value is None else f"{value:,.{digits}f}"


def main(paths):
    runs = load(paths)
    labels = list(runs)
    keys = [k for run in runs.values() for k in run]
    sizes = sorted({size for size, case in keys if case != "memory.footprint"})
    cases = list(OrderedDict.fromkeys(case for _, case in keys if case != "memory.footprint"))

    out = ["# Struct speed race", "", "Columns:", ""]
    out += [f"- **{chr(65 + i)}** = {label}" for i, label in enumerate(labels)]
    cols = [chr(65 + i) for i in range(len(labels))]
    out += ["", "Time is the median nanoseconds per element (lower is better, fastest in **bold**). "
                "Allocation is heap bytes allocated per element during the timed rounds.", ""]

    for size in sizes:
        out += [f"## {size:,} elements", ""]
        out.append("| Case | " + " | ".join(f"{c} ns/op" for c in cols) + " | " + " | ".join(f"{c} alloc B/op" for c in cols) + " |")
        out.append("|---|" + "---:|" * (2 * len(cols)))
        for case in cases:
            rows = [runs[label].get((size, case)) for label in labels]
            if not any(rows):
                continue
            times = [num(r, "ns_per_op") for r in rows]
            best = min((t for t in times if t is not None), default=None)
            cells = [("**" + fmt(t) + "**") if t is not None and t == best and len([x for x in times if x is not None]) > 1 else fmt(t) for t in times]
            allocs = [fmt(num(r, "alloc_bytes_per_op"), 1) for r in rows]
            out.append(f"| {case} | " + " | ".join(cells) + " | " + " | ".join(allocs) + " |")
        out.append("")

    footprints = [(label, num(row, "bytes_per_element"), size)
                  for label, run in runs.items() for (size, case), row in run.items() if case == "memory.footprint"]
    if footprints:
        out += ["## Memory footprint", "", "| Runtime | Bytes per element | Measured with |", "|---|---:|---:|"]
        out += [f"| {label} | {fmt(value, 1)} | {size:,} elements |" for label, value, size in footprints]
        out.append("")

    print("\n".join(out))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(sys.argv[1:])
