#!/usr/bin/env python3
"""
Builds the HushhBoard word-engine assets from the licensed sources in tools/nlp/data.

Outputs
  app/src/main/assets/ime/dict/hushh/en_ke_sw.hdict      combined English + Swahili + Kenyan lexicon
  lib/nlp/src/test/resources/golden_suggestions.tsv       expected top-3 per query (Kotlin must match)

Sources (see tools/nlp/DATA_LICENSES.md)
  data/en_GB_wordlist.combined.gz   AOSP LatinIME, Apache-2.0
  data/sw_blinkered_counts.tsv      blinkered-dictionary-sw, CC0-1.0 (shipped words only)
  data/ke_curated.tsv               HushhBoard original, Apache-2.0
  data/shortcuts.tsv                HushhBoard original, Apache-2.0
  data/en_chat.tsv                  HushhBoard original, Apache-2.0

Usage: python3 tools/nlp/build_assets.py
"""
from __future__ import annotations

import datetime
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)

import hdict  # noqa: E402

ENGLISH_MIN_FREQ = 18
SW_SOURCE_WEIGHTS = {
    "web:co.ke": 3.0,
    "tat": 2.5,
    "lz:swa_news_2020_30K": 2.0,
    "lz:swa_newscrawl_2011_10K": 2.0,
    "web:co.tz": 1.5,
    "web:bongo5.com": 1.5,
    "wiki:sw": 1.0,
    "ia": 0.5,
    "ebible:swhonen": 0.3,
    "ebible:swhulb": 0.3,
    "web:voaswahili.com": 0.2,
}
SW_FULL_WEIGHT_TOKENS = 50_000  # sources smaller than this get proportionally less weight
KE_TIER_FREQ = {0: 215, 1: 185, 2: 160, 3: 135}

OUT_DICT = os.path.join(ROOT, "app/src/main/assets/ime/dict/hushh/en_ke_sw.hdict")
OUT_GOLDEN = os.path.join(ROOT, "lib/nlp/src/test/resources/golden_suggestions.tsv")
OUT_MINI = os.path.join(ROOT, "lib/nlp/src/test/resources/mini.hdict")
OUT_GOLDEN_AC = os.path.join(ROOT, "lib/nlp/src/test/resources/golden_autocorrect.tsv")
OUT_GOLDEN_TOUCH = os.path.join(ROOT, "lib/nlp/src/test/resources/golden_touch.tsv")
OUT_GOLDEN_SMART = os.path.join(ROOT, "lib/nlp/src/test/resources/golden_smart_targets.tsv")
OUT_NGRAMS = os.path.join(ROOT, "app/src/main/assets/ime/dict/hushh/en_ke_sw.hngm")
OUT_GOLDEN_PRED = os.path.join(ROOT, "lib/nlp/src/test/resources/golden_predictions.tsv")
OUT_GOLDEN_CTX = os.path.join(ROOT, "lib/nlp/src/test/resources/golden_context.tsv")

GOLDEN_PRED = ["", "I ", "Thank ", "How are ", "Can you ", "I want to ", "Habari ", "Asante ", "Nataka ",
               "See you ", "I am going to the ", "What time ", "Let me ", "Good ", "Done. ", "Mimi ni ",
               "Can I get a ", "xyzzyq ", "Please send me the "]
GOLDEN_CTX = [("I want to ", "b"), ("I want to ", "go"), ("Thank ", "y"), ("How are ", "y"), ("Habari ", "y"),
              ("See you ", "t"), ("Can you ", "s"), ("Good ", "m"), ("I am going to the ", "s"),
              ("Asante ", "s"), ("What time ", "i"), ("I want to ", "bok"), ("Please send me the ", "mo")]

# typed, sentenceStart. Mix of typos that must be fixed and words that must be left alone.
GOLDEN_AC = [
    ("teh", 1), ("recieve", 1), ("definately", 1), ("seperate", 1), ("becuase", 1), ("thier", 1),
    ("goverment", 1), ("occured", 1), ("writting", 1), ("tommorow", 1), ("dont", 1), ("im", 1), ("i", 1),
    ("youre", 1), ("cant", 1), ("habri", 1), ("asnte", 1), ("kwnai", 1), ("sawq", 1), ("nairobi", 1),
    ("Teh", 1), ("Teh", 0), ("Kibet", 0), ("Kibet", 1), ("wangui", 1), ("mtoi", 1), ("kuandikishwa", 1),
    ("dramatises", 1), ("omw", 1), ("ur", 1), ("KCB", 1), ("TEH", 1), ("abc123", 1), ("hello", 1),
    ("sawa", 1), ("lakini", 1), ("msee", 1), ("wich", 1), ("nto", 1), ("deceived", 1), ("jusy", 1),
    ("wont", 1), ("whats", 1), ("well", 1), ("ill", 1), ("its", 1), ("were", 1), ("id", 1),
]

# Simulated taps for golden touch tests: deterministic offsets from QWERTY centres.
GOLDEN_TOUCH = [
    ("tge", [("t", 0, 0), ("g", -0.05, -0.42), ("e", 0, 0)]),        # border tap between h/g -> "the"
    ("hrllo", [("h", 0, 0), ("r", 0.1, 0.44), ("l", 0, 0), ("l", 0, 0), ("o", 0, 0)]),
    ("gpod", [("g", 0, 0), ("p", -0.45, 0.1), ("o", 0, 0), ("d", 0, 0)]),
    ("sawq", [("s", 0, 0), ("a", 0, 0), ("w", 0, 0), ("q", 0.46, 0.4)]),
    ("cpme", [("c", 0, 0), ("p", -0.47, 0.05), ("m", 0, 0), ("e", 0, 0)]),
    ("tine", [("t", 0, 0), ("i", 0, 0), ("n", 0, 0), ("e", 0, 0)]),  # centred taps: keep "tine" reading strong
    ("nlw", [("n", 0, 0), ("l", -0.3, 0.45), ("w", 0, 0)]),
]

# prefix, default key, tap offset from the default key's centre, expected resolved key
GOLDEN_SMART = [
    ("th", "r", (0.45, 0.0)), ("th", "e", (0.0, 0.0)), ("hel", "k", (-0.48, 0.0)), ("qu", "y", (0.45, 0.0)),
    ("sa", "q", (0.45, 0.45)), ("", "t", (0.0, 0.0)), ("xyzq", "a", (0.4, 0.0)), ("goo", "f", (0.48, 0.0)),
    ("ha", "v", (0.0, -0.45)), ("matat", "y", (0.47, 0.0)),
]


def load_english():
    raw = hdict.load_aosp_wordlist(os.path.join(HERE, "data/en_GB_wordlist.combined.gz"))
    return [r for r in raw if r[1] >= ENGLISH_MIN_FREQ]


def load_swahili(reference_freqs: list[int]):
    tokens = {}
    rows = []
    with open(os.path.join(HERE, "data/sw_blinkered_counts.tsv"), encoding="utf-8") as f:
        for line in f:
            if line.startswith("#tokens"):
                for kv in line.split()[1:]:
                    k, v = kv.rsplit("=", 1)
                    tokens[k] = int(v)
                continue
            if line.startswith("#"):
                continue
            word, srcs, counts = line.rstrip("\n").split("\t")
            rows.append((word, srcs.split(","), [int(c) for c in counts.split(",")]))
    eff_w = {s: w * min(1.0, tokens.get(s, 0) / SW_FULL_WEIGHT_TOKENS) for s, w in SW_SOURCE_WEIGHTS.items()}
    total_w = sum(eff_w.values())
    scored = []
    for word, srcs, counts in rows:
        rel = 0.0
        for s, c in zip(srcs, counts):
            if s in eff_w and tokens.get(s):
                rel += eff_w[s] * c / tokens[s]
        scored.append((rel / total_w, word.lower()))
    scored.sort(key=lambda t: (-t[0], t[1]))
    # Rank-match onto the English frequency distribution (proportional ranks).
    ref = sorted(reference_freqs, reverse=True)
    out = []
    n = len(scored)
    for rank, (_, word) in enumerate(scored):
        f = ref[min(len(ref) - 1, int(rank / n * len(ref)))]
        out.append((word, f, hdict.FLAG_LANG_SW))
    return out


def load_curated():
    out = []
    with open(os.path.join(HERE, "data/ke_curated.tsv"), encoding="utf-8") as f:
        for line in f:
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            word, tier = parts[0], int(parts[1])
            if " " in word:
                continue
            flag = hdict.FLAG_LANG_SW if len(parts) > 2 and parts[2] == "sw" else hdict.FLAG_LANG_KE
            out.append((word, KE_TIER_FREQ[tier], flag))
    return out


def load_shortcuts():
    out = {}
    with open(os.path.join(HERE, "data/shortcuts.tsv"), encoding="utf-8") as f:
        for line in f:
            if not line.strip() or line.startswith("#"):
                continue
            parts = line.rstrip("\n").split("\t")
            out[parts[0].lower()] = parts[1:]
    return out


def load_chat_floor():
    out = {}
    with open(os.path.join(HERE, "data/en_chat.tsv"), encoding="utf-8") as f:
        for line in f:
            if not line.strip() or line.startswith("#"):
                continue
            word, floor = line.rstrip("\n").split("\t")
            out[word] = int(floor)
    return out


def build_entries():
    en = load_english()
    floors = load_chat_floor()
    present = {w for w, _, _ in en}
    en = [(w, max(f, floors.get(w, 0)), fl) for w, f, fl in en]
    en += [(w, f, hdict.FLAG_LANG_EN) for w, f in floors.items() if w not in present]
    en_top = sorted((f for _, f, _ in en), reverse=True)[:50_000]
    sw = load_swahili(en_top)
    ke = load_curated()
    return hdict.merge_entries(en + sw + ke), (len(en), len(sw), len(ke))


GOLDEN_QUERIES = [
    # completions
    "hel", "th", "tha", "beca", "tomo", "tod", "pls", "appr", "Hel", "HEL",
    # English typos
    "teh", "recieve", "definately", "wich", "becuase", "thier", "untill", "hapy", "helo", "goign",
    "tommorow", "dont", "im", "wont", "cant", "acount", "adress", "occured", "seperate", "wierd",
    # Kenyan / Swahili / Sheng
    "asnte", "sawq", "nairobi", "mpesa", "fuliz", "habar", "nimefik", "tutaon", "kesh", "niaj",
    "poa", "msee", "matat", "ugal", "kama", "lakin", "asubu", "safaricom",
]


def main():
    entries, (n_en, n_sw, n_ke) = build_entries()
    today = datetime.date.today().isoformat()
    meta = {
        "name": "HushhBoard English+Kenyan+Swahili",
        "locales": "en,sw",
        "version": "1",
        "built": today,
        "sources": "AOSP LatinIME en_GB (Apache-2.0); blinkered-dictionary-sw (CC0-1.0); HushhBoard ke_curated (Apache-2.0)",
    }
    shortcuts = load_shortcuts()
    data = hdict.build_hdict(entries, meta, shortcuts)
    os.makedirs(os.path.dirname(OUT_DICT), exist_ok=True)
    with open(OUT_DICT, "wb") as f:
        f.write(data)
    lex = hdict.Lexicon(data)
    print(f"en={n_en} sw={n_sw} ke={n_ke} merged={len(entries)} nodes={lex.node_count} "
          f"displays={lex.display_count} size={len(data)/1e6:.2f}MB -> {os.path.relpath(OUT_DICT, ROOT)}")

    # Small fixture for fast unit tests of the binary format.
    mini_raw = [("the", 222, hdict.FLAG_LANG_EN), ("they", 190, hdict.FLAG_LANG_EN),
                ("then", 185, hdict.FLAG_LANG_EN), ("there", 188, hdict.FLAG_LANG_EN),
                ("I", 196, hdict.FLAG_LANG_EN), ("don't", 185, hdict.FLAG_LANG_EN),
                ("Nairobi", 185, hdict.FLAG_LANG_KE), ("M-Pesa", 185, hdict.FLAG_LANG_KE),
                ("sawa", 185, hdict.FLAG_LANG_KE | hdict.FLAG_LANG_SW), ("asante", 170, hdict.FLAG_LANG_SW),
                ("damn", 120, hdict.FLAG_LANG_EN | hdict.FLAG_OFFENSIVE)]
    mini = hdict.build_hdict(hdict.merge_entries(mini_raw), {"name": "mini", "version": "1"},
                             {"im": ["I'm"], "ur": ["your", "you're"]})
    os.makedirs(os.path.dirname(OUT_MINI), exist_ok=True)
    with open(OUT_MINI, "wb") as f:
        f.write(mini)

    dec = hdict.Decoder(lex)
    lines = ["# query\ttop-3 (display forms, typed casing applied). Generated by tools/nlp/build_assets.py"]
    for q in GOLDEN_QUERIES:
        res = dec.suggest(q, 3)
        words = [hdict.apply_case(q, c.display or c.key) for c in res]
        lines.append(q + "\t" + "\t".join(words))
    with open(OUT_GOLDEN, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("golden ->", os.path.relpath(OUT_GOLDEN, ROOT))

    # Autocorrect decisions
    ac_lines = ["# typed\tsentenceStart\texpected ('-' = leave alone). Generated by tools/nlp/build_assets.py"]
    for typed, start in GOLDEN_AC:
        ranked = dec.suggest(typed, 8)
        hit = lex.lookup(typed)
        ch = hdict.autocorrect_choice(typed, ranked, hit is not None, bool(start), typed_freq=hit[1] if hit else 0)
        out = hdict.apply_case(typed, ch.display or ch.key) if ch else "-"
        ac_lines.append(f"{typed}\t{start}\t{out}")
    with open(OUT_GOLDEN_AC, "w", encoding="utf-8") as f:
        f.write("\n".join(ac_lines) + "\n")

    # Touch-aware suggestions
    t_lines = ["# typed\ttaps (x,y;...)\ttop-3. Generated by tools/nlp/build_assets.py"]
    for typed, taps_spec in GOLDEN_TOUCH:
        taps = []
        for ch, dx, dy in taps_spec:
            cx, cy = hdict.DEFAULT_CENTERS[ch]
            taps.append((round(cx + dx, 4), round(cy + dy, 4)))
        res = dec.suggest(typed, 3, touches=taps)
        words = [hdict.apply_case(typed, c.display or c.key) for c in res]
        tap_s = ";".join(f"{x},{y}" for x, y in taps)
        t_lines.append(f"{typed}\t{tap_s}\t" + "\t".join(words))
    with open(OUT_GOLDEN_TOUCH, "w", encoding="utf-8") as f:
        f.write("\n".join(t_lines) + "\n")

    # Smart key targets
    prox = hdict.DEFAULT_PROXIMITY
    import math
    s_lines = ["# prefix\tdefault\ttapX\ttapY\texpected. Generated by tools/nlp/build_assets.py"]
    for prefix, default, (dx, dy) in GOLDEN_SMART:
        cx, cy = hdict.DEFAULT_CENTERS[default]
        tap = (round(cx + dx, 4), round(cy + dy, 4))
        keys = sorted(k for k, p in prox.centers.items()
                      if k != default and math.hypot(p[0] - cx, p[1] - cy) <= 1.6)
        got = hdict.smart_target(lex, prox, prefix, tap, default, keys)
        s_lines.append(f"{prefix}\t{default}\t{tap[0]}\t{tap[1]}\t{got}")
    with open(OUT_GOLDEN_SMART, "w", encoding="utf-8") as f:
        f.write("\n".join(s_lines) + "\n")
    # Next-word model (needs the corpora in /tmp/corpus; see DATA_LICENSES.md)
    import ngrams
    if all(os.path.exists(p) for p, _ in ngrams.CORPORA):
        model_bytes = ngrams.build(lex, OUT_NGRAMS)
    else:
        print("corpora not found: keeping the existing n-gram model")
        model_bytes = open(OUT_NGRAMS, "rb").read()
    model = ngrams.Ngrams(model_bytes, lex)
    vocab = ngrams.Vocab(lex)
    keymap = ngrams.key_map(lex)
    p_lines = ["# textBefore (quoted)\ttop-3 next-word predictions (display forms). Generated by build_assets.py"]
    for before in GOLDEN_PRED:
        p2, p1 = ngrams.context_nodes(lex, vocab, before)
        ctx = ngrams.Context(model, p2, p1)
        words = [lex.display(n) or keymap[n] for n, _ in ctx.predictions(3, lex)]
        p_lines.append(f'"{before}"\t' + "\t".join(words))
    with open(OUT_GOLDEN_PRED, "w", encoding="utf-8") as f:
        f.write("\n".join(p_lines) + "\n")
    c_lines = ["# textBefore (quoted)\ttyped\ttop-3 with context. Generated by build_assets.py"]
    for before, typed in GOLDEN_CTX:
        p2, p1 = ngrams.context_nodes(lex, vocab, before)
        ctx = ngrams.Context(model, p2, p1)
        res = ngrams.suggest_with_context(dec, lex, ctx, keymap, typed, 3)
        c_lines.append(f'"{before}"\t{typed}\t' + "\t".join(hdict.apply_case(typed, c.display or c.key) for c in res))
    with open(OUT_GOLDEN_CTX, "w", encoding="utf-8") as f:
        f.write("\n".join(c_lines) + "\n")
    print("predictions:", " | ".join(l.replace("\t", " -> ", 1).replace("\t", ", ") for l in p_lines[1:]))
    print("context:", " | ".join(l.replace("\t", " ") for l in c_lines[1:]))

    print("autocorrect:", "  ".join(l.replace("\t", ":") for l in ac_lines[1:]))
    print("touch:", " | ".join(l.split("\t")[0] + "->" + ",".join(l.split("\t")[2:]) for l in t_lines[1:]))
    print("smart:", "  ".join(l.replace("\t", ":") for l in s_lines[1:]))
    for line in lines[1:]:
        print("  ", line.replace("\t", " | "))


if __name__ == "__main__":
    main()
