#!/usr/bin/env python3
"""
Downloads the text corpora the next-word model is trained on and writes them as plain text,
one sentence or utterance per line, to /tmp/corpus (the paths tools/nlp/ngrams.py reads).

  python3 tools/nlp/fetch_corpora.py          # clone (sparse, shallow) and extract
  python3 tools/nlp/build_assets.py           # rebuild dictionary + en_ke_sw.hngm + goldens

Sources and licences are listed in DATA_LICENSES.md. Everything here allows commercial use.
"""
from __future__ import annotations

import glob
import json
import os
import subprocess
import sys

WORK = os.environ.get("HUSHH_CORPUS_SRC", "/tmp/hushh-corpus-src")
OUT = "/tmp/corpus"

REPOS = {
    # Mozilla Common Voice sentence collection (CC0-1.0)
    "common-voice": ("https://github.com/common-voice/common-voice", ["server/data/en", "server/data/sw"]),
    # Google Taskmaster-1 and -2 (CC BY 4.0)
    "taskmaster": ("https://github.com/google-research-datasets/Taskmaster", ["TM-1-2019", "TM-2-2020"]),
    # MultiWOZ 2.2 (MIT)
    "multiwoz": ("https://github.com/budzianowski/multiwoz", ["data/MultiWOZ_2.2"]),
}


def clone(name: str, url: str, paths: list[str]) -> str:
    dest = os.path.join(WORK, name)
    if not os.path.isdir(os.path.join(dest, ".git")):
        os.makedirs(WORK, exist_ok=True)
        subprocess.run(["git", "-c", "gc.auto=0", "clone", "--depth=1", "--filter=blob:none", "--sparse", url, dest],
                       check=True)
        subprocess.run(["git", "-C", dest, "-c", "gc.auto=0", "sparse-checkout", "set", *paths], check=True)
    return dest


def clean(line: str) -> str:
    return line.replace("\r", " ").replace("\n", " ").strip()


def write(path: str, lines) -> int:
    n = 0
    with open(path, "w", encoding="utf-8") as f:
        for line in lines:
            line = clean(line)
            if line:
                f.write(line + "\n")
                n += 1
    return n


def common_voice(root: str, lang: str):
    for path in sorted(glob.glob(os.path.join(root, "server/data", lang, "*.txt"))):
        with open(path, encoding="utf-8", errors="replace") as f:
            yield from f


def taskmaster(root: str):
    files = [os.path.join(root, "TM-1-2019", n) for n in ("self-dialogs.json", "woz-dialogs.json")]
    files += sorted(glob.glob(os.path.join(root, "TM-2-2020/data/*.json")))
    for path in files:
        for conv in json.load(open(path, encoding="utf-8")):
            for u in conv.get("utterances", []):
                yield u.get("text", "")


def multiwoz(root: str):
    # train + dev only: the test split stays unseen for evaluation.
    paths = []
    for split in ("train", "dev"):
        paths += sorted(glob.glob(os.path.join(root, "data/MultiWOZ_2.2", split, "dialogues_*.json")))
    for path in paths:
        for dialogue in json.load(open(path, encoding="utf-8")):
            for turn in dialogue.get("turns", []):
                yield turn.get("utterance", "")


def main() -> int:
    roots = {name: clone(name, url, paths) for name, (url, paths) in REPOS.items()}
    os.makedirs(OUT, exist_ok=True)
    print("en_cv", write(os.path.join(OUT, "en_cv.txt"), common_voice(roots["common-voice"], "en")))
    print("sw_cv", write(os.path.join(OUT, "sw_cv.txt"), common_voice(roots["common-voice"], "sw")))

    def dialog():
        yield from taskmaster(roots["taskmaster"])
        yield from multiwoz(roots["multiwoz"])

    print("en_dialog", write(os.path.join(OUT, "en_dialog.txt"), dialog()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
