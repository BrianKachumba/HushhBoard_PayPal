#!/usr/bin/env python3
"""
Fat-finger evaluation with simulated taps.

Each intended letter becomes a tap = key centre + personal bias + Gaussian scatter, and the key
that registers is the one whose rectangle contains the tap (what a plain keyboard does). We then
compare, on the words that came out wrong:

  * decoding without tap positions (key-neighbour costs only),
  * decoding with tap positions (spatial likelihood),
  * smart key targets (language-aware key choice for border taps) on the raw typed text,
  * autocorrect outcomes with and without tap data, and false corrections on unknown words.

Usage: python3 tools/nlp/eval_touch.py [--tune]
"""
from __future__ import annotations

import itertools
import math
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import hdict  # noqa: E402
import eval as ev  # noqa: E402
import eval_ac as ea  # noqa: E402

SCATTER_X = 0.30
SCATTER_Y = 0.36
BIAS = (0.08, 0.22)  # typical thumb bias: slightly right and low
N_WORDS = 1500


def rect_key(x, y):
    row = min(2, max(0, int(math.floor(y))))
    letters, offset = hdict.QWERTY_ROWS[row]
    col = min(len(letters) - 1, max(0, int(math.floor(x - offset))))
    return letters[col]


def neighbours(ch, prox, radius=1.6):
    c = prox.center(ch)
    return sorted(k for k, p in prox.centers.items() if k != ch and math.hypot(p[0] - c[0], p[1] - c[1]) <= radius)


def simulate(word, rng):
    taps, typed = [], []
    for ch in word:
        cx, cy = hdict.DEFAULT_CENTERS[ch]
        x = cx + BIAS[0] + rng.gauss(0, SCATTER_X)
        y = cy + BIAS[1] + rng.gauss(0, SCATTER_Y)
        taps.append((x, y))
        typed.append(rect_key(x, y))
    return taps, "".join(typed)


def smart_typed(lex, prox, taps, typed, offsets=None):
    out = ""
    for tap, ch in zip(taps, typed):
        out += hdict.smart_target(lex, prox, out, tap, ch, neighbours(ch, prox), offsets)
    return out


def main():
    global SCATTER_X, SCATTER_Y, BIAS
    if "--realistic" in sys.argv:
        SCATTER_X, SCATTER_Y, BIAS = 0.22, 0.27, (0.05, 0.12)
    if os.environ.get("HUSHH_TOUCH"):
        b, sx, sy = (float(v) for v in os.environ["HUSHH_TOUCH"].split(","))
        hdict.TOUCH_SUB_BASE, hdict.TOUCH_SIGMA_X, hdict.TOUCH_SIGMA_Y = b, sx, sy
    lex = hdict.Lexicon(open(ev.DICT, "rb").read())
    prox = hdict.DEFAULT_PROXIMITY
    dec = hdict.Decoder(lex)
    rng = random.Random(5)
    words = [(k, f) for k, f, fl, _ in lex.iter_words()
             if f >= 110 and 3 <= len(k) <= 10 and all(c in hdict.DEFAULT_CENTERS for c in k)
             and not fl & hdict.FLAG_OFFENSIVE]
    words.sort()
    weights = [2 ** ((f - 110) / 25) for _, f in words]
    offsets = {c: BIAS for c in hdict.DEFAULT_CENTERS}  # what a learned per-user offset converges to

    raw_ok = smart_ok = 0
    errors = []
    for _ in range(N_WORDS):
        w = rng.choices(words, weights)[0][0]
        taps, typed = simulate(w, rng)
        smart = smart_typed(lex, prox, taps, typed, offsets)
        raw_ok += typed == w
        smart_ok += smart == w
        if typed != w and lex.lookup(typed) is None:
            errors.append((w, typed, taps))
    print(f"{N_WORDS} words typed with simulated taps (scatter {SCATTER_X}/{SCATTER_Y} key widths, bias {BIAS})")
    print(f"  words typed exactly right:  plain keys {raw_ok / N_WORDS:.1%}   smart targets {smart_ok / N_WORDS:.1%}")

    def score(touch, offs):
        top1 = ac_ok = ac_wrong = 0
        for w, typed, taps in errors:
            ranked = dec.suggest(typed, 8, touches=taps if touch else None, offsets=offs)
            top1 += bool(ranked) and ranked[0].key == w
            ch = hdict.autocorrect_choice(typed, ranked, False, True)
            if ch is not None:
                ac_ok += ch.key == w
                ac_wrong += ch.key != w
        n = len(errors)
        return top1 / n, ac_ok / n, ac_wrong / n

    print(f"  {len(errors)} non-word typos:")
    for label, touch, offs in [("no tap data", False, None), ("tap positions", True, None),
                               ("tap positions + learned offset", True, offsets)]:
        t1, ok, wrong = score(touch, offs)
        print(f"    {label:32} top-1 {t1:.1%}   autocorrect fixed {ok:.1%} wrong {wrong:.1%}")

    # False corrections on unknown words typed with taps (names/slang typed carefully).
    names = [w.lower() for w in ea.oov_list(lex) if all(c in hdict.DEFAULT_CENTERS for c in w.lower())]
    fires = fires_touch = total = 0
    for w in names:
        for _ in range(3):
            taps, typed = simulate(w, rng)
            if typed != w:
                continue
            total += 1
            r0 = dec.suggest(typed, 8)
            r1 = dec.suggest(typed, 8, touches=taps, offsets=offsets)
            fires += hdict.autocorrect_choice(typed, r0, False, True) is not None
            fires_touch += hdict.autocorrect_choice(typed, r1, False, True) is not None
    print(f"  unknown names/slang typed correctly ({total}): false autocorrect "
          f"{fires / total:.1%} without taps, {fires_touch / total:.1%} with taps")


if __name__ == "__main__":
    main()
