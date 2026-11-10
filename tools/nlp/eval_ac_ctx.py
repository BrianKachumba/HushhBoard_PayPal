#!/usr/bin/env python3
"""Autocorrect safety and recall *in sentence context* (held-out sentences), with vs without context boosts."""
from __future__ import annotations
import os, random, sys
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import hdict, ngrams, eval as ev, eval_ac as ea, eval_touch as et  # noqa: E402

def ranked(dec, lex, ctx, typed, exclude=None):
    cands = dec.candidates(typed, exclude=exclude)
    for c in cands:
        c.score = dec.score(c, typed.lower()) + (ctx.boost(lex.find(c.key), c.freq) if ctx else 0.0)
    cands.sort(key=lambda c: (-c.score, c.key))
    return cands[:8]

def main():
    lex = hdict.Lexicon(open(ev.DICT, "rb").read())
    model = ngrams.Ngrams(open(sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(ev.DICT), "en_ke_sw.hngm"), "rb").read(), lex)
    dec = hdict.Decoder(lex); vocab = ngrams.Vocab(lex); rng = random.Random(21)
    names = [w.lower() for w in ea.oov_list(lex)]
    pos, neg_rare, neg_name = [], [], []
    for path, _ in ngrams.CORPORA:
        sents = list(ngrams.sentences(path, heldout=True)); rng.shuffle(sents)
        for words in sents[:600]:
            nodes = [vocab.node(w) for w in words]
            if len(words) < 3 or min(nodes) < 0: continue
            i = rng.randrange(1, len(words))
            prev1 = nodes[i - 1]; prev2 = nodes[i - 2] if i >= 2 else ngrams.START
            ctx = ngrams.Context(model, prev2, prev1)
            target = words[i].lower()
            if len(target) >= 3 and target.isalpha():
                typo = ev.add_noise(target, rng, hdict.DEFAULT_CENTERS)
                if typo != target and lex.lookup(typo) is None:
                    pos.append((typo, target, ctx))
                info = lex.lookup(target)
                if info and 18 <= info[1] <= 90:
                    neg_rare.append((target, ctx))
            neg_name.append((rng.choice(names), ctx))
    for use_ctx in (False, True):
        ok = wrong = 0
        for typo, target, ctx in pos:
            ch = hdict.autocorrect_choice(typo, ranked(dec, lex, ctx if use_ctx else None, typo), False, True)
            if ch: ok += ch.key == target; wrong += ch.key != target
        fr = sum(hdict.autocorrect_choice(w, ranked(dec, lex, ctx if use_ctx else None, w, exclude={w}), False, True) is not None
                 for w, ctx in neg_rare)
        fn = sum(hdict.autocorrect_choice(w, ranked(dec, lex, ctx if use_ctx else None, w), False, True) is not None
                 for w, ctx in neg_name)
        print(f"{'with context   ' if use_ctx else 'without context'}: fixed {ok/len(pos):.1%} wrong {wrong/len(pos):.1%} "
              f"(n={len(pos)}) | false-fix rare {fr/len(neg_rare):.1%} (n={len(neg_rare)}) names/slang {fn/len(neg_name):.1%} (n={len(neg_name)})")

if __name__ == "__main__":
    main()
