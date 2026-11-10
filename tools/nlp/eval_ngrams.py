#!/usr/bin/env python3
"""
Held-out evaluation of next-word prediction and context-aware completion.

  next-word : after a space, is the real next word among the top-1/top-3 predictions?
  completion: after typing 1-3 letters of the next word, is it in the top 3 suggestions,
              with and without context?

Usage: python3 tools/nlp/eval_ngrams.py [model.hngm]
"""
from __future__ import annotations

import os
import random
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import hdict  # noqa: E402
import ngrams  # noqa: E402

DICT = os.path.join(HERE, "..", "..", "app/src/main/assets/ime/dict/hushh/en_ke_sw.hdict")


def ranked_with_context(dec, lex, ctx, typed):
    cands = dec.candidates(typed)
    for c in cands:
        node = lex.find(c.key)
        c.score = dec.score(c, typed.lower()) + (ctx.boost(node, c.freq) if ctx else 0.0)
    if ctx is not None:
        have = {c.key for c in cands}
        for node, lp in ctx.logp.items():
            key = lex_key(lex, node)
            if key is None or key in have or not key.startswith(typed.lower()):
                continue
            _, _, flags, _, freq, _, _ = lex.node(node)
            if flags & hdict.FLAG_OFFENSIVE:
                continue
            comp = len(key) - len(typed)
            c = hdict.Candidate(key, lex.display(node) if flags & hdict.FLAG_HAS_DISPLAY else None,
                                freq, flags, 0.0, comp)
            c.score = dec.score(c, typed.lower()) + ctx.boost(node, freq)
            cands.append(c)
    cands.sort(key=lambda c: (-c.score, c.key))
    return cands


_key_cache = {}


def lex_key(lex, node):
    if node in _key_cache:
        return _key_cache[node]
    _key_cache.clear() if len(_key_cache) > 200000 else None
    return None


def build_key_map(lex):
    m = {}
    for key, freq, flags, node in lex.iter_words():
        m[node] = key
    return m


def main():
    if os.environ.get("HUSHH_CTX"):
        w, mx = (float(v) for v in os.environ["HUSHH_CTX"].split(","))
        ngrams.CTX_WEIGHT, ngrams.CTX_MAX_BOOST = w, mx
    lex = hdict.Lexicon(open(DICT, "rb").read())
    model_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(DICT), "en_ke_sw.hngm")
    model = ngrams.Ngrams(open(model_path, "rb").read(), lex)
    dec = hdict.Decoder(lex)
    vocab = ngrams.Vocab(lex)
    keymap = build_key_map(lex)
    global lex_key
    lex_key = lambda _lex, node: keymap.get(node)
    rng = random.Random(3)

    results = {}
    for path, _ in ngrams.CORPORA:
        name = os.path.basename(path)
        sents = [s for s in ngrams.sentences(path, heldout=True)]
        rng.shuffle(sents)
        sents = sents[:int(os.environ.get('HUSHH_N', '1500'))]
        nw1 = nw3 = nw_total = 0
        comp = {k: [0, 0, 0] for k in (1, 2, 3)}  # [with ctx ok, without ctx ok, total]
        for words in sents:
            prev2, prev1 = None, ngrams.START
            for i, w in enumerate(words):
                node = vocab.node(w)
                if node < 0:
                    prev2, prev1 = None, None
                    continue
                if prev1 is not None:
                    ctx = ngrams.Context(model, prev2, prev1)
                    preds = [n for n, _ in ctx.predictions(3)]
                    nw_total += 1
                    nw1 += bool(preds) and preds[0] == node
                    nw3 += node in preds
                    key = keymap[node]
                    if len(key) >= 4 and i % 3 == 0:  # sample completions to keep it fast
                        for k in (1, 2, 3):
                            typed = key[:k]
                            with_ctx = [c.key for c in ranked_with_context(dec, lex, ctx, typed)[:3]]
                            without = [c.key for c in ranked_with_context(dec, lex, None, typed)[:3]]
                            comp[k][0] += key in with_ctx
                            comp[k][1] += key in without
                            comp[k][2] += 1
                prev2, prev1 = prev1, node
        results[name] = (nw1, nw3, nw_total, comp)
        line = f"{name:16} next-word top1 {nw1 / nw_total:.1%} top3 {nw3 / nw_total:.1%} (n={nw_total})"
        for k in (1, 2, 3):
            ok, base, tot = comp[k]
            line += f" | {k} letter{'s' if k > 1 else ''}: {base / tot:.0%}->{ok / tot:.0%}"
        print(line, flush=True)


if __name__ == "__main__":
    main()
