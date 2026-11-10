#!/usr/bin/env python3
"""
Next-word model for HushhBoard: builder, binary format (HNGM v1), reference predictor, evaluation.

Corpora (see DATA_LICENSES.md):
  English dialogue  Google Taskmaster-1 (CC BY 4.0), MultiWOZ 2.2 (MIT)
  English sentences Mozilla Common Voice text corpus (CC0)
  Swahili sentences Mozilla Common Voice text corpus (CC0)

HNGM v1 (little-endian), tied to one HDIC lexicon build (node indices are word ids;
node 0, the trie root, stands for "start of sentence"):
Header (64 bytes)
  0  char[4] "HNGM"     4 u16 version=1    6 u16 headerSize=64
  8  u32 lexiconNodeCount 12 u32 lexiconWordCount
 16  u32 bigramCount      20 u32 bigramOffset     (12-byte entries, sorted by node)
 24  u32 trigramCount     28 u32 trigramOffset    (16-byte entries, sorted by (node1,node2))
 32  u32 followerCount    36 u32 followerOffset   (4-byte entries)
 40  f32 uniSlope         44 f32 uniIntercept     ln P(word) ~= uniSlope * freq + uniIntercept
 48  u32 wordCount        52 u32 wordTableOffset  (8-byte entries {u32 node, u32 stringOffset}, sorted by node)
 56  u32 poolOffset       60 u32 poolSize         (u16 byteLength + UTF-8 key) - the key of every word above
Bigram context  : u32 node, u32 firstFollower, u16 followerCount, u16 reserved
Trigram context : u32 node1 (two back), u32 node2 (previous), u32 firstFollower, u16 count, u16 reserved
Follower        : u32 packed = node | (q << 24), with ln P(next | context) = -q / 20  (most likely first)
"""
from __future__ import annotations

import math
import os
import random
import re
import struct
import sys
from collections import Counter, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import hdict  # noqa: E402

MAGIC = b"HNGM"
HEADER = 64
Q_SCALE = 20.0
START = 0

BIGRAM_MIN_CONTEXT = 3
BIGRAM_MAX_FOLLOWERS = 12
BIGRAM_MIN_COUNT = 2
TRIGRAM_MIN_CONTEXT = 50
TRIGRAM_MAX_FOLLOWERS = 6
TRIGRAM_MIN_COUNT = 3
DISCOUNT = 0.5

# Relative weight of each corpus (chat-like text matters most for a keyboard).
CORPORA = [
    ("/tmp/corpus/en_dialog.txt", 1.5),
    ("/tmp/corpus/en_cv.txt", 1.0),
    ("/tmp/corpus/sw_cv.txt", 4.0),
]
HELDOUT_EVERY = 50  # 2% of sentences are held out for evaluation

# Hand-written everyday chat (original HushhBoard text, Apache-2.0). Small but exactly what people
# type on a phone, so it is scaled to carry this share of all word-pair evidence. It steers the
# common contexts ("I am", "Habari ya", "Niko") away from the booking-dialogue style of the large
# corpora, which still cover the long tail. Unigram (frequency) statistics are not scaled.
HERE_DATA = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")
CHAT_CORPORA = [
    (os.path.join(HERE_DATA, "chat_en.txt"), 0.30),
    (os.path.join(HERE_DATA, "chat_sw.txt"), 0.12),
]

WORD_RE = re.compile(r"[^\W\d_]+(?:['’-][^\W\d_]+)*")
SENT_SPLIT_RE = re.compile(r"[.!?;:]+|\n")


def tokenize(line: str) -> list[list[str]]:
    out = []
    for chunk in SENT_SPLIT_RE.split(line):
        words = [w.replace("’", "'") for w in WORD_RE.findall(chunk)]
        if words:
            out.append(words)
    return out


class Vocab:
    """Maps surface words to lexicon node indices (cached)."""

    def __init__(self, lex: hdict.Lexicon):
        self.lex = lex
        self.cache: dict[str, int] = {}

    def node(self, word: str) -> int:
        v = self.cache.get(word)
        if v is None:
            hit = self.lex.lookup(word)
            v = hit[0] if hit else -1
            self.cache[word] = v
        return v


def sentences(path, heldout: bool):
    with open(path, encoding="utf-8", errors="ignore") as f:
        for i, line in enumerate(f):
            if (i % HELDOUT_EVERY == 0) != heldout:
                continue
            for s in tokenize(line):
                yield s


def count(lex: hdict.Lexicon):
    vocab = Vocab(lex)
    uni = Counter()
    bi = Counter()
    tri = Counter()
    total = 0.0
    for path, weight in CORPORA:
        if not os.path.exists(path):
            print("missing corpus", path)
            continue
        for words in sentences(path, heldout=False):
            prev2, prev1 = None, START
            for w in words:
                n = vocab.node(w)
                if n < 0:  # unknown word: context is broken
                    prev2, prev1 = None, None
                    continue
                uni[n] += weight
                total += weight
                if prev1 is not None:
                    bi[(prev1 << 20) | n] += weight
                    if prev2 is not None:
                        tri[(prev2 << 40) | (prev1 << 20) | n] += weight
                prev2, prev1 = prev1, n
    # Hand-written chat: scaled so it carries its share of all word-pair evidence.
    mass = sum(bi.values())
    for path, share in CHAT_CORPORA:
        if not os.path.exists(path):
            print("missing chat corpus", path)
            continue
        sents = []
        with open(path, encoding="utf-8") as f:
            for line in f:
                sents.extend(tokenize(line))
        events = sum(len(w) for w in sents)
        if events == 0:
            continue
        weight = share / (1.0 - share) * mass / events
        for words in sents:
            prev2, prev1 = None, START
            for w in words:
                n = vocab.node(w)
                if n < 0:
                    prev2, prev1 = None, None
                    continue
                uni[n] += 1.0
                total += 1.0
                if prev1 is not None:
                    bi[(prev1 << 20) | n] += weight
                    if prev2 is not None:
                        tri[(prev2 << 40) | (prev1 << 20) | n] += weight
                prev2, prev1 = prev1, n
    return uni, bi, tri, total


def calibrate(lex, uni, total):
    xs, ys = [], []
    for node, c in uni.items():
        if c < 5:
            continue
        freq = lex.node(node)[4]
        xs.append(freq)
        ys.append(math.log(c / total))
    n = len(xs)
    mx, my = sum(xs) / n, sum(ys) / n
    sxx = sum((x - mx) ** 2 for x in xs)
    sxy = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    slope = sxy / sxx
    return slope, my - slope * mx


def select(counter, ctx_of, key_of, min_ctx, max_f, min_c):
    by_ctx = defaultdict(list)
    ctx_total = Counter()
    for k, c in counter.items():
        ctx = ctx_of(k)
        ctx_total[ctx] += c
        if c >= min_c:
            by_ctx[ctx].append((c, key_of(k)))
    out = {}
    for ctx, items in by_ctx.items():
        tot = ctx_total[ctx]
        if tot < min_ctx:
            continue
        items.sort(key=lambda t: (-t[0], t[1]))
        fol = []
        for c, w in items[:max_f]:
            p = max(c - DISCOUNT, 0.01) / tot
            q = min(255, int(round(-math.log(p) * Q_SCALE)))
            fol.append((q, w))
        fol.sort()
        out[ctx] = fol
    return out


def build(lex: hdict.Lexicon, path_out: str):
    uni, bi, tri, total = count(lex)
    slope, intercept = calibrate(lex, uni, total)
    bigrams = select(bi, lambda k: k >> 20, lambda k: k & 0xFFFFF,
                     BIGRAM_MIN_CONTEXT, BIGRAM_MAX_FOLLOWERS, BIGRAM_MIN_COUNT)
    trigrams = select(tri, lambda k: k >> 20, lambda k: k & 0xFFFFF,
                      TRIGRAM_MIN_CONTEXT, TRIGRAM_MAX_FOLLOWERS, TRIGRAM_MIN_COUNT)
    followers = bytearray()
    fcount = 0
    bi_entries = bytearray()
    for ctx in sorted(bigrams):
        fol = bigrams[ctx]
        bi_entries += struct.pack("<IIHH", ctx, fcount, len(fol), 0)
        for q, w in fol:
            followers += struct.pack("<I", w | (q << 24))
            fcount += 1
    tri_entries = bytearray()
    for ctx in sorted(trigrams):
        n1, n2 = ctx >> 20, ctx & 0xFFFFF
        fol = trigrams[ctx]
        tri_entries += struct.pack("<IIIHH", n1, n2, fcount, len(fol), 0)
        for q, w in fol:
            followers += struct.pack("<I", w | (q << 24))
            fcount += 1
    # Keys of every word that can be predicted (the trie cannot map a node back to its text).
    keys = key_map(lex)
    used = set()
    for fol in list(bigrams.values()) + list(trigrams.values()):
        for _, w in fol:
            used.add(w)
    pool = bytearray()
    table = bytearray()
    for node in sorted(used):
        b = keys[node].encode("utf-8")
        table += struct.pack("<II", node, len(pool))
        pool += struct.pack("<H", len(b)) + b
    bi_off = HEADER
    tri_off = bi_off + len(bi_entries)
    f_off = tri_off + len(tri_entries)
    t_off = f_off + len(followers)
    p_off = t_off + len(table)
    header = struct.pack("<4sHHIIIIIIIIffIIII", MAGIC, 1, HEADER, lex.node_count, lex.word_count,
                         len(bigrams), bi_off, len(trigrams), tri_off, fcount, f_off, slope, intercept,
                         len(used), t_off, p_off, len(pool))
    assert len(header) == HEADER
    data = bytes(header + bi_entries + tri_entries + followers + table + pool)
    with open(path_out, "wb") as f:
        f.write(data)
    print(f"tokens={total:,.0f} bigram contexts={len(bigrams):,} trigram contexts={len(trigrams):,} "
          f"followers={fcount:,} size={len(data) / 1e6:.2f}MB uni: lnP = {slope:.4f}*f + {intercept:.3f}")
    return data


class Ngrams:
    def __init__(self, data: bytes, lex: hdict.Lexicon):
        self.d = data
        (magic, ver, hs, nodes, words, self.bi_n, self.bi_off, self.tri_n, self.tri_off,
         self.f_n, self.f_off, self.slope, self.intercept, self.w_n, self.w_off, self.p_off, self.p_size) = \
            struct.unpack_from("<4sHHIIIIIIIIffIIII", data, 0)
        assert magic == MAGIC and ver == 1
        if nodes != lex.node_count or words != lex.word_count:
            raise ValueError("n-gram model does not match the dictionary")

    def _followers(self, first, count):
        out = []
        for i in range(first, first + count):
            packed = struct.unpack_from("<I", self.d, self.f_off + i * 4)[0]
            out.append((packed & 0xFFFFFF, -(packed >> 24) / Q_SCALE))
        return out

    def bigram(self, prev1: int):
        lo, hi = 0, self.bi_n - 1
        while lo <= hi:
            mid = (lo + hi) >> 1
            node, first, cnt, _ = struct.unpack_from("<IIHH", self.d, self.bi_off + mid * 12)
            if node < prev1:
                lo = mid + 1
            elif node > prev1:
                hi = mid - 1
            else:
                return self._followers(first, cnt)
        return []

    def trigram(self, prev2: int, prev1: int):
        target = (prev2, prev1)
        lo, hi = 0, self.tri_n - 1
        while lo <= hi:
            mid = (lo + hi) >> 1
            n1, n2, first, cnt, _ = struct.unpack_from("<IIIHH", self.d, self.tri_off + mid * 16)
            if (n1, n2) < target:
                lo = mid + 1
            elif (n1, n2) > target:
                hi = mid - 1
            else:
                return self._followers(first, cnt)
        return []

    def key_of(self, node: int):
        lo, hi = 0, self.w_n - 1
        while lo <= hi:
            mid = (lo + hi) >> 1
            n, off = struct.unpack_from("<II", self.d, self.w_off + mid * 8)
            if n < node:
                lo = mid + 1
            elif n > node:
                hi = mid - 1
            else:
                ln = struct.unpack_from("<H", self.d, self.p_off + off)[0]
                return self.d[self.p_off + off + 2:self.p_off + off + 2 + ln].decode("utf-8")
        return None

    def uni_logp(self, freq: int) -> float:
        return self.slope * freq + self.intercept


# --------------------------------------------------------------------------------------
# Context model used by the decoder. Mirrors dev.hushhboard.nlp.ContextModel.
# --------------------------------------------------------------------------------------

BACKOFF = math.log(0.4)
CTX_WEIGHT = 0.8
CTX_MAX_BOOST = 6.0
PREDICT_MIN_LOGP = -7.0


class Context:
    """Next-word distribution for a fixed context (previous one or two words)."""

    def __init__(self, model: Ngrams, prev2: int | None, prev1: int | None):
        self.model = model
        self.logp: dict[int, float] = {}
        if prev1 is None:
            return
        for node, lp in model.bigram(prev1):
            self.logp[node] = lp + BACKOFF
        if prev2 is not None:
            for node, lp in model.trigram(prev2, prev1):
                if lp > self.logp.get(node, -1e9):
                    self.logp[node] = lp

    def boost(self, node: int, freq: int) -> float:
        lp = self.logp.get(node)
        if lp is None:
            return 0.0
        gain = lp - self.model.uni_logp(freq)
        if gain <= 0.0:
            return 0.0
        return CTX_WEIGHT * min(gain, CTX_MAX_BOOST / CTX_WEIGHT)

    def predictions(self, limit: int, lex=None, allow_offensive: bool = False):
        items = sorted(((lp, node) for node, lp in self.logp.items() if lp >= PREDICT_MIN_LOGP),
                       key=lambda t: (-t[0], t[1]))
        out = []
        for lp, node in items:
            if lex is not None and not allow_offensive and lex.node(node)[2] & hdict.FLAG_OFFENSIVE:
                continue
            out.append((node, lp))
            if len(out) >= limit:
                break
        return out


def suggest_with_context(dec, lex, ctx, keymap, typed: str, max_count: int = 8, allow_offensive=False):
    """Decoder candidates re-ranked by context, plus context words that complete what was typed.
    Mirrors the context part of dev.hushhboard.nlp.WordEngine.suggest."""
    tl = typed.lower()
    cands = dec.candidates(typed, allow_offensive)
    for c in cands:
        node = lex.find(c.key) if " " not in c.key else -1
        c.score = dec.score(c, tl) + (ctx.boost(node, c.freq) if ctx is not None and node > 0 else 0.0)
    if ctx is not None:
        have = {c.key for c in cands}
        for node in sorted(ctx.logp):
            key = keymap.get(node)
            if key is None or key in have or not key.startswith(tl):
                continue
            _, _, flags, _, freq, _, _ = lex.node(node)
            if flags & hdict.FLAG_OFFENSIVE and not allow_offensive:
                continue
            c = hdict.Candidate(key, lex.display(node) if flags & hdict.FLAG_HAS_DISPLAY else None,
                                freq, flags, 0.0, len(key) - len(tl))
            c.score = dec.score(c, tl) + ctx.boost(node, freq)
            cands.append(c)
    cands.sort(key=lambda c: (-c.score, c.key))
    return cands[:max_count]


def key_map(lex):
    return {node: key for key, _, _, node in lex.iter_words()}


def context_nodes(lex, vocab: Vocab, text_before: str):
    """(prev2, prev1) node ids for the text before the cursor; START at a sentence start."""
    sents = tokenize(text_before)
    stripped = text_before.rstrip(" ")
    if not sents or (stripped and stripped[-1] in ".!?\n"):
        return None, START
    words = sents[-1]
    p1 = vocab.node(words[-1])
    if p1 < 0:
        return None, None
    if len(words) == 1:
        return START, p1
    p2 = vocab.node(words[-2])
    return (p2 if p2 >= 0 else None), p1
