#!/usr/bin/env python3
"""
Autocorrect safety/recall evaluation and tuning (precision first).

Positives  : real + synthetic typos. Outcome per item: corrected-to-target / wrong-fire / left alone.
Negatives  : valid words the dictionary does not know (held-out rare words, names, slang).
             Any change is a false correction - the worst thing a keyboard can do.

Usage:  python3 tools/nlp/eval_ac.py [--tune]
"""
from __future__ import annotations

import itertools
import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import hdict  # noqa: E402
import eval as ev  # noqa: E402

# Real-world words people type that a dictionary will not have. Must never be "corrected".
OOV_NEGATIVES = """
Kibet Jelagat Moraa Nyaboke Kemunto Ombati Onyancha Mokaya Gathoni Wangui Wanjohi Kimani Ndungu
Gachanja Muriuki Nyokabi Wairegi Chelimo Kipkorir Jepchirchir Cherono Langat Rotich Korir Sang
Okoth Owuor Ouma Nyongesa Simiyu Wekesa Makokha Khisa Nasimiyu Mutiso Kioko Nzioka Mwikali Kalekye
Zawadi Baraka Imani Neema Amani Furaha Tumaini Jabali Shujaa Wambugu Kamande Gitau Macharia Njuguna
msoto rada gava fala kuomoka ngeta mbisha wueh sanse niko-poa mzinga ndovu kanjo tinted mbleina
kumanya kuhanda kuboeka form-ni kubonga kusanya ganjo mkokoteni kuchapa mtoi ngware mathree kadem
lmao smh tbh ikr ngl idc imo fomo yolo bae lowkey highkey fr wyd hbu omw
Instagram TikTok WhatsApp Twitter Spotify YouTube Netflix Uber Bolt Glovo Jumia Kilimall Naivas
Quickmart Carrefour Chandarana Java Artcaffe Kenchic KPLC Zuku Faiba Mpesa-tu Lipwa
"""

NEG_RARE_SAMPLE = 400
POS_SYNTH = 600


def oov_list(lex):
    out = []
    for w in OOV_NEGATIVES.split():
        if lex.lookup(w) is None and hdict.is_plain_word(w):
            out.append(w)
    return out


def build(lex, dec):
    rng = random.Random(11)
    real = [(t, g) for t, g in ev.parse_real() if lex.lookup(t) is None]
    syn, _ = ev.build_sets(lex, n_syn=POS_SYNTH, n_comp=1)
    positives = real + syn
    rare = [(k, f) for k, f, fl, _ in lex.iter_words()
            if 18 <= f <= 90 and len(k) >= 3 and k.isalpha() and not fl & hdict.FLAG_OFFENSIVE]
    rare.sort()
    held_out = [k for k, _ in rng.sample(rare, NEG_RARE_SAMPLE)]
    names = oov_list(lex)
    items = []
    for typed, target in positives:
        items.append(("pos", typed, target, dec.suggest(typed, 8), False))
    for w in held_out:
        items.append(("neg-rare", w, None, dec.suggest(w, 8, exclude={w}), False))
    for w in names:
        items.append(("neg-oov", w, None, dec.suggest(w, 8), False))
    return items, len(positives), len(held_out), len(names)


def run(items, params):
    hdict.AC_MIN_SCORE, hdict.AC_SCORE_SLOPE, hdict.AC_MIN_GAP, hdict.AC_COST_SCALE = params
    stats = {"pos_ok": 0, "pos_wrong": 0, "pos_none": 0, "neg-rare": 0, "neg-oov": 0}
    examples = {"pos_wrong": [], "neg-rare": [], "neg-oov": []}
    for kind, typed, target, ranked, valid in items:
        choice = hdict.autocorrect_choice(typed, ranked, valid, sentence_start=True)
        if kind == "pos":
            if choice is None:
                stats["pos_none"] += 1
            elif choice.key == target:
                stats["pos_ok"] += 1
            else:
                stats["pos_wrong"] += 1
                examples["pos_wrong"].append((typed, target, choice.key))
        elif choice is not None:
            stats[kind] += 1
            examples[kind].append((typed, choice.key))
    return stats, examples


def report(stats, n_pos, n_rare, n_oov):
    return (f"fixed {stats['pos_ok'] / n_pos:.1%}  wrong-fix {stats['pos_wrong'] / n_pos:.1%}  "
            f"left {stats['pos_none'] / n_pos:.1%} | false-fix rare {stats['neg-rare'] / n_rare:.1%}  "
            f"names/slang {stats['neg-oov'] / max(1, n_oov):.1%}")


def main():
    lex = hdict.Lexicon(open(ev.DICT, "rb").read())
    dec = hdict.Decoder(lex)
    items, n_pos, n_rare, n_oov = build(lex, dec)
    print(f"positives={n_pos} held-out rare={n_rare} names/slang={n_oov}")
    base = (hdict.AC_MIN_SCORE, hdict.AC_SCORE_SLOPE, hdict.AC_MIN_GAP, hdict.AC_COST_SCALE)
    stats, ex = run(items, base)
    print("current", base, report(stats, n_pos, n_rare, n_oov))
    if "--tune" in sys.argv:
        best = None
        for params in itertools.product([-9.0, -10.0, -11.0, -12.0], [0.0, 0.5, 1.0, 1.5],
                                        [1.0, 1.5, 2.0, 2.5], [0.8, 1.0, 1.2]):
            st, _ = run(items, params)
            wrong = st["pos_wrong"] / n_pos
            false_rare = st["neg-rare"] / n_rare
            false_oov = st["neg-oov"] / max(1, n_oov)
            # Precision-first: harmful events cost 4x a missed fix.
            obj = st["pos_ok"] / n_pos - 5.0 * wrong - 2.5 * false_rare - 2.5 * false_oov
            if best is None or obj > best[0]:
                best = (obj, params, st)
        print("best", best[1], report(best[2], n_pos, n_rare, n_oov))
        stats, ex = run(items, best[1])
        run(items, base)
    if "--examples" in sys.argv:
        for k, v in ex.items():
            print(k, v[:25])


if __name__ == "__main__":
    main()
