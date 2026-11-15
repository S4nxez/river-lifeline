"""Prints the scorecard of exam runs side by side: python benchmark/compare.py HISTORY_DIR LABEL [LABEL ...]."""

import glob
import json
import os
import sys


def latest(history, label):
    found = sorted(glob.glob(os.path.join(history, f"*_{label}.json")))
    return json.load(open(found[-1], encoding="utf-8")) if found else None


def main():
    history, labels = sys.argv[1], sys.argv[2:]
    runs = [(label, latest(history, label)) for label in labels]
    keys = []
    for _, run in runs:
        for key in (run or {}).get("scorecard", {}):
            if key not in keys:
                keys.append(key)
    print("| Score | " + " | ".join(label for label, _ in runs) + " |")
    print("|---|" + "---|" * len(runs))
    for key in keys:
        cells = [str((run or {}).get("scorecard", {}).get(key, "–")) for _, run in runs]
        print(f"| {key} | " + " | ".join(cells) + " |")


if __name__ == "__main__":
    main()
