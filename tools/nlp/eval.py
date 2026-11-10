#!/usr/bin/env python3
"""
Offline quality evaluation + parameter tuning for the HushhBoard decoder.

Test sets
  1. Real misspellings (English + Kenyan/Swahili), hand-curated below.
  2. Synthetic fat-finger typos: frequent words passed through a keyboard noise model
     (neighbour substitution, missed/extra taps, doubled letters, transpositions).
  3. Completions: prefixes of frequent words; success = target within the top 3.

Usage
  python3 tools/nlp/eval.py            # evaluate current constants
  python3 tools/nlp/eval.py --tune     # grid-search scoring weights (prints best)
"""
from __future__ import annotations

import itertools
import os
import random
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import hdict  # noqa: E402

DICT = os.path.join(HERE, "..", "..", "app/src/main/assets/ime/dict/hushh/en_ke_sw.hdict")

REAL_MISSPELLINGS = """
teh the|recieve receive|definately definitely|wich which|becuase because|thier their|untill until
hapy happy|goign going|tommorow tomorrow|tommorrow tomorrow|dont don't|im i'm|wont won't|cant can't
acount account|adress address|occured occurred|seperate separate|wierd weird|beleive believe
belive believe|freind friend|frist first|realy really|reccomend recommend|neccessary necessary
goverment government|enviroment environment|existance existence|begining beginning|calender calendar
comming coming|concious conscious|dissapoint disappoint|embarass embarrass|finaly finally|foward forward
goin going|grammer grammar|happend happened|harrass harass|hight height|immediatly immediately
independant independent|knowlege knowledge|libary library|lisence licence|maintainance maintenance
mispell misspell|noticable noticeable|ocassion occasion|occurence occurrence|peice piece|perhpas perhaps
posession possession|prefered preferred|probaly probably|publically publicly|recomend recommend
remeber remember|resturant restaurant|rythm rhythm|sence sense|sincerly sincerely|speach speech
succesful successful|suprise surprise|tatoo tattoo|tounge tongue|truely truly|tyrany tyranny
unforseen unforeseen|vaccum vacuum|wether whether|whcih which|wheather weather|writting writing
yesterdy yesterday|yuo you|youre you're|abt about|beacuse because|becasue because|hte the|taht that
waht what|jsut just|knwo know|liek like|mroe more|nto not|ot to|sned send|thnks thanks|thanx thanks
plese please|pleas please|morinng morning|evning evening|tonite tonight|ur your|wat what|wen when
asnte asante|asanta asante|sawq sawa|habri habari|habar habari|karbu karibu|nimefik nimefika
nakupnda nakupenda|kesjo kesho|lakni lakini|sana sana|mambi mambo|niajr niaje
fuliz fuliza|mpesa mpesa|mpsa mpesa|matatu matatu|matau matatu|nairbi nairobi|nairob nairobi
mombsa mombasa|kisumi kisumu|ugaki ugali|chapti chapati|mandazu mandazi|safaricon safaricom
pesa pesa|psea pesa|shilngi shilingi|tafadhli tafadhali|samahni samahani|nyumbni nyumbani
asubuh asubuhi|jioni jioni|usku usiku|kwnai kwani|kumbr kumbe|freahi freshi|msse msee|mbogu mbogi
"""

NOISE_SEED = 7


def parse_real():
    pairs = []
    for chunk in REAL_MISSPELLINGS.replace("\n", "|").split("|"):
        chunk = chunk.strip()
        if not chunk:
            continue
        typed, target = chunk.split()
        pairs.append((typed, target))
    return pairs


def neighbours(ch, centers):
    if ch not in centers:
        return []
    x, y = centers[ch]
    return [c for c, (cx, cy) in centers.items() if c != ch and ((cx - x) ** 2 + (cy - y) ** 2) ** 0.5 <= 1.25]


def add_noise(word, rng, centers):
    w = list(word)
    letters = [i for i, c in enumerate(w) if c in centers]
    if not letters:
        return word
    i = rng.choice(letters)
    op = rng.random()
    if op < 0.45:      # neighbour substitution
        nb = neighbours(w[i], centers)
        if nb:
            w[i] = rng.choice(nb)
    elif op < 0.62:    # missed tap
        del w[i]
    elif op < 0.77:    # extra tap on a neighbour
        nb = neighbours(w[i], centers)
        if nb:
            w.insert(i + 1, rng.choice(nb))
    elif op < 0.87:    # doubled letter
        w.insert(i, w[i])
    else:              # transposition
        if i + 1 < len(w):
            w[i], w[i + 1] = w[i + 1], w[i]
    return "".join(w)


def build_sets(lex, n_syn=600, n_comp=400):
    rng = random.Random(NOISE_SEED)
    words = [(k, f, fl) for k, f, fl, _ in lex.iter_words()
             if f >= 95 and len(k) >= 3 and k.isalpha() and not fl & hdict.FLAG_OFFENSIVE]
    words.sort()
    weights = [2 ** ((f - 95) / 25) for _, f, _ in words]
    centers = hdict.DEFAULT_CENTERS
    syn = []
    while len(syn) < n_syn:
        k, f, fl = rng.choices(words, weights)[0]
        typo = add_noise(k, rng, centers)
        if typo != k and lex.lookup(typo) is None:
            syn.append((typo, k))
    comp = []
    long_words = [(k, f, fl) for k, f, fl in words if len(k) >= 5]
    lw = [2 ** ((f - 95) / 25) for _, f, _ in long_words]
    while len(comp) < n_comp:
        k, f, fl = rng.choices(long_words, lw)[0]
        cut = rng.randint(3, len(k) - 1)
        comp.append((k[:cut], k))
    return syn, comp


def candidates_for(dec, queries):
    cache = {}
    t0 = time.time()
    for q, _ in queries:
        if q not in cache:
            cache[q] = dec.candidates(q)
    return cache, time.time() - t0


def rank(cands, target, typed, params, lang_prior):
    cw, fs, cb, cpc, vb = params
    tl = typed.lower()
    scored = []
    for c in cands:
        s = (c.freq - 255) / 255.0 * fs + lang_prior(c.flags) - cw * c.cost
        if c.completion_chars > 0:
            s -= cb + cpc * c.completion_chars
        if c.cost == 0.0 and c.completion_chars == 0 and c.key == tl:
            s += vb
        scored.append((-s, c.key))
    scored.sort()
    keys = [k for _, k in scored]
    return keys.index(target) if target in keys else 99


def evaluate(cache, sets, params, lang_prior):
    out = {}
    for name, pairs in sets.items():
        top1 = top3 = 0
        for typed, target in pairs:
            r = rank(cache[typed], target, typed, params, lang_prior)
            top1 += r == 0
            top3 += r < 3
        out[name] = (top1 / len(pairs), top3 / len(pairs))
    return out


def main():
    lex = hdict.Lexicon(open(DICT, "rb").read())
    dec = hdict.Decoder(lex)
    real = parse_real()
    syn, comp = build_sets(lex)
    sets = {"real": real, "synthetic": syn, "completion": comp}
    allq = real + syn + comp
    cache, secs = candidates_for(dec, allq)
    print(f"{len(cache)} queries, python candidate search {secs / len(cache) * 1000:.1f} ms/query")
    base = (hdict.COST_WEIGHT, hdict.FREQ_SPAN, hdict.COMPLETION_BASE, hdict.COMPLETION_PER_CHAR,
            hdict.VALID_TYPED_BONUS)

    def show(params):
        res = evaluate(cache, sets, params, dec.lang_prior)
        return " ".join(f"{k}: top1={v[0]:.3f} top3={v[1]:.3f}" for k, v in res.items()), res

    print("current", base, show(base)[0])
    if "--tune" in sys.argv:
        best = None
        grid = itertools.product([5, 6.5, 8, 9.5], [14, 18, 22], [0.0, 0.4, 0.8], [0.0, 0.1, 0.2], [0.5, 1.5])
        for params in grid:
            _, res = show(params)
            # objective: correction top1 (real+syn) and completion top3, real weighted highest
            obj = 0.4 * res["real"][0] + 0.3 * res["synthetic"][0] + 0.3 * res["completion"][1]
            if best is None or obj > best[0]:
                best = (obj, params)
        print("best", best[1], f"obj={best[0]:.3f}", show(best[1])[0])
    if "--misses" in sys.argv:
        for typed, target in real:
            r = rank(cache[typed], target, typed, base, dec.lang_prior)
            if r != 0:
                top = sorted(cache[typed], key=lambda c: -dec.score(c, typed.lower()))[:3]
                print(f"  {typed:12} want {target:12} rank={r:2}  got {[c.key for c in top]}")


if __name__ == "__main__":
    main()
