#!/usr/bin/env python3
"""
HushhBoard dictionary toolkit (reference implementation).

  * HDIC v1 binary format: builder + reader.
  * Reference decoder: the exact algorithm implemented in Kotlin by
    lib/nlp (dev.hushhboard.nlp.Decoder). Used to tune quality offline and to
    generate golden test expectations that the Kotlin tests must reproduce.

Keep this file and lib/nlp/src/main/kotlin/dev/hushhboard/nlp/* in lock-step:
any change to constants or scoring must be made in both places, then
`python3 tools/nlp/build_assets.py` regenerates the golden file.

HDIC v1 layout (little-endian)
------------------------------
Header (64 bytes):
  0  char[4] magic "HDIC"
  4  u16 version (=1)
  6  u16 headerSize (=64)
  8  u32 nodeCount
 12  u32 nodesOffset
 16  u32 displayCount
 20  u32 displayIndexOffset   (displayCount x {u32 nodeIndex, u32 stringOffset}, sorted by nodeIndex)
 24  u32 stringPoolOffset
 28  u32 stringPoolSize       (entries: u16 byteLength + UTF-8 bytes)
 32  u32 wordCount
 36  u32 metaOffset           (UTF-8 "key=value\n" lines)
 40  u32 metaSize
 44  u32 shortcutCount
 48  u32 shortcutIndexOffset  (shortcutCount x {u32 keyStringOffset, u32 valueStringOffset}, sorted by key;
                              value = expansions joined by TAB; strings live in the string pool)
 52  reserved (zero) up to 64
Node record (12 bytes) at nodesOffset + i*12, nodes in BFS order (children contiguous, sorted by char):
 +0  u16 ch            UTF-16 code unit (root: 0)
 +2  u8  childCount
 +3  u8  flags         see FLAG_*
 +4  u32 firstChild    index of first child (0 if none)
 +8  u8  freq          0..255 log-scale unigram frequency (terminal nodes)
 +9  u8  maxFreq       max freq of any terminal in this subtree (incl. self)
 +10 u16 reserved
"""
from __future__ import annotations

import gzip
import heapq
import math
import struct
import unicodedata
from dataclasses import dataclass, field

MAGIC = b"HDIC"
VERSION = 1
HEADER_SIZE = 64
NODE_SIZE = 12

FLAG_TERMINAL = 0x01
FLAG_OFFENSIVE = 0x02
FLAG_HAS_DISPLAY = 0x04
FLAG_LANG_EN = 0x08
FLAG_LANG_SW = 0x10
FLAG_LANG_KE = 0x20
FLAG_LANG_MASK = FLAG_LANG_EN | FLAG_LANG_SW | FLAG_LANG_KE

MAX_WORD_LENGTH = 32
JOINERS = "'-"


# --------------------------------------------------------------------------------------
# Building
# --------------------------------------------------------------------------------------

def normalize_key(word: str) -> str:
    """Dictionary key: lower-case (locale independent). Must match Kotlin `Text.normalizeKey`."""
    return word.lower()


def is_acceptable_word(word: str) -> bool:
    if not word or len(word) > MAX_WORD_LENGTH:
        return False
    if any(ord(c) > 0xFFFF for c in word):
        return False
    if not word[0].isalpha():
        return False
    for c in word:
        if not (c.isalpha() or c in JOINERS):
            return False
    if word[-1] in JOINERS:
        return False
    return True


@dataclass
class Entry:
    key: str
    freq: int
    flags: int
    display: str | None


@dataclass
class _Node:
    ch: str
    children: dict = field(default_factory=dict)
    terminal: bool = False
    freq: int = 0
    flags: int = 0
    display: str | None = None
    max_freq: int = 0
    index: int = -1


def merge_entries(raw: list[tuple[str, int, int]]) -> dict[str, Entry]:
    """raw: (surface form, freq, flags). Merges surface forms that share a key."""
    by_key: dict[str, list[tuple[str, int, int]]] = {}
    for surface, freq, flags in raw:
        if not is_acceptable_word(surface):
            continue
        by_key.setdefault(normalize_key(surface), []).append((surface, max(0, min(255, freq)), flags))
    out: dict[str, Entry] = {}
    for key, forms in by_key.items():
        freq = max(f for _, f, _ in forms)
        flags = 0
        for _, _, fl in forms:
            flags |= fl
        # A word is only flagged offensive if every variant is.
        if not all(fl & FLAG_OFFENSIVE for _, _, fl in forms):
            flags &= ~FLAG_OFFENSIVE
        lower_forms = [f for s, f, _ in forms if s == key]
        if lower_forms:
            display = None
        else:
            display = max(forms, key=lambda t: (t[1], t[0]))[0]
        out[key] = Entry(key, freq, flags, display)
    return out


def build_hdict(entries: dict[str, Entry], meta: dict[str, str],
                shortcuts: dict[str, list[str]] | None = None) -> bytes:
    root = _Node("\0")
    for key, e in entries.items():
        node = root
        for ch in key:
            node = node.children.setdefault(ch, _Node(ch))
        node.terminal = True
        node.freq = e.freq
        node.flags = (e.flags & ~FLAG_HAS_DISPLAY) | FLAG_TERMINAL | (FLAG_HAS_DISPLAY if e.display else 0)
        node.display = e.display

    def compute_max(n: _Node) -> int:
        m = n.freq if n.terminal else 0
        for c in n.children.values():
            m = max(m, compute_max(c))
        n.max_freq = m
        return m

    import sys
    sys.setrecursionlimit(10000)
    compute_max(root)

    order: list[_Node] = [root]
    root.index = 0
    i = 0
    while i < len(order):
        n = order[i]
        for ch in sorted(n.children):
            c = n.children[ch]
            c.index = len(order)
            order.append(c)
        i += 1

    nodes = bytearray(NODE_SIZE * len(order))
    displays: list[tuple[int, str]] = []
    for n in order:
        kids = [n.children[ch] for ch in sorted(n.children)]
        if len(kids) > 255:
            raise ValueError(f"too many children ({len(kids)}) at node {n.ch!r}")
        first = kids[0].index if kids else 0
        struct.pack_into("<HBBIBBH", nodes, n.index * NODE_SIZE,
                         0 if n is root else ord(n.ch), len(kids), n.flags, first, n.freq, n.max_freq, 0)
        if n.display:
            displays.append((n.index, n.display))
    displays.sort()

    pool = bytearray()
    index = bytearray()

    def intern(text: str) -> int:
        off = len(pool)
        b = text.encode("utf-8")
        pool.extend(struct.pack("<H", len(b)) + b)
        return off

    for node_index, s in displays:
        index += struct.pack("<II", node_index, intern(s))
    sc_index = bytearray()
    sc_items = sorted((shortcuts or {}).items())
    for key, values in sc_items:
        sc_index += struct.pack("<II", intern(key), intern("\t".join(values)))

    meta_bytes = "".join(f"{k}={v}\n" for k, v in meta.items()).encode("utf-8")
    nodes_off = HEADER_SIZE
    disp_off = nodes_off + len(nodes)
    sc_off = disp_off + len(index)
    pool_off = sc_off + len(sc_index)
    meta_off = pool_off + len(pool)
    header = struct.pack("<4sHHIIIIIIIIIII", MAGIC, VERSION, HEADER_SIZE, len(order), nodes_off,
                         len(displays), disp_off, pool_off, len(pool), len(entries), meta_off, len(meta_bytes),
                         len(sc_items), sc_off)
    header += b"\0" * (HEADER_SIZE - len(header))
    return bytes(header + nodes + index + sc_index + pool + meta_bytes)


# --------------------------------------------------------------------------------------
# Reading
# --------------------------------------------------------------------------------------

class Lexicon:
    def __init__(self, data: bytes):
        self.d = data
        (magic, ver, hsize, self.node_count, self.nodes_off, self.display_count, self.disp_off,
         self.pool_off, self.pool_size, self.word_count, self.meta_off, self.meta_size,
         self.shortcut_count, self.shortcut_off) = struct.unpack_from("<4sHHIIIIIIIIIII", data, 0)
        if magic != MAGIC or ver != VERSION:
            raise ValueError("bad dictionary")
        meta = data[self.meta_off:self.meta_off + self.meta_size].decode("utf-8")
        self.meta = dict(line.split("=", 1) for line in meta.splitlines() if "=" in line)

    def node(self, i: int):
        return struct.unpack_from("<HBBIBBH", self.d, self.nodes_off + i * NODE_SIZE)

    def child(self, i: int, ch: str) -> int:
        _, cnt, _, first, _, _, _ = self.node(i)
        lo, hi = first, first + cnt - 1
        target = ord(ch)
        while lo <= hi:
            mid = (lo + hi) >> 1
            c = struct.unpack_from("<H", self.d, self.nodes_off + mid * NODE_SIZE)[0]
            if c < target:
                lo = mid + 1
            elif c > target:
                hi = mid - 1
            else:
                return mid
        return -1

    def find(self, key: str) -> int:
        i = 0
        for ch in key:
            i = self.child(i, ch)
            if i < 0:
                return -1
        return i

    def pool_string(self, off: int) -> str:
        ln = struct.unpack_from("<H", self.d, self.pool_off + off)[0]
        return self.d[self.pool_off + off + 2:self.pool_off + off + 2 + ln].decode("utf-8")

    def shortcut(self, key: str) -> list[str]:
        lo, hi = 0, self.shortcut_count - 1
        while lo <= hi:
            mid = (lo + hi) >> 1
            ko, vo = struct.unpack_from("<II", self.d, self.shortcut_off + mid * 8)
            k = self.pool_string(ko)
            if k < key:
                lo = mid + 1
            elif k > key:
                hi = mid - 1
            else:
                return self.pool_string(vo).split("\t")
        return []

    def display(self, node_index: int) -> str | None:
        lo, hi = 0, self.display_count - 1
        while lo <= hi:
            mid = (lo + hi) >> 1
            ni, off = struct.unpack_from("<II", self.d, self.disp_off + mid * 8)
            if ni < node_index:
                lo = mid + 1
            elif ni > node_index:
                hi = mid - 1
            else:
                ln = struct.unpack_from("<H", self.d, self.pool_off + off)[0]
                return self.d[self.pool_off + off + 2:self.pool_off + off + 2 + ln].decode("utf-8")
        return None

    def lookup(self, word: str):
        i = self.find(normalize_key(word))
        if i < 0:
            return None
        _, _, flags, _, freq, _, _ = self.node(i)
        if not flags & FLAG_TERMINAL:
            return None
        return i, freq, flags

    def iter_words(self):
        stack = [(0, "")]
        while stack:
            i, prefix = stack.pop()
            ch, cnt, flags, first, freq, _, _ = self.node(i)
            if flags & FLAG_TERMINAL:
                yield prefix, freq, flags, i
            for k in range(first + cnt - 1, first - 1, -1) if cnt else []:
                stack.append((k, prefix + chr(self.node(k)[0])))


# --------------------------------------------------------------------------------------
# Key proximity (default QWERTY geometry, in key-width units)
# --------------------------------------------------------------------------------------

QWERTY_ROWS = [("qwertyuiop", 0.0), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)]


def default_key_centers() -> dict[str, tuple[float, float]]:
    centers = {}
    for row, (letters, offset) in enumerate(QWERTY_ROWS):
        for col, ch in enumerate(letters):
            centers[ch] = (offset + col + 0.5, row + 0.5)
    return centers


DEFAULT_CENTERS = default_key_centers()


def base_letter(c: str) -> str:
    d = unicodedata.normalize("NFD", c)
    return d[0] if d else c


class Proximity:
    def __init__(self, centers=None):
        self.centers = centers or DEFAULT_CENTERS

    def center(self, c: str):
        return self.centers.get(base_letter(c))

    def distance(self, a: str, b: str) -> float:
        pa = self.centers.get(base_letter(a))
        pb = self.centers.get(base_letter(b))
        if pa is None or pb is None:
            return 99.0
        return math.hypot(pa[0] - pb[0], pa[1] - pb[1])


DEFAULT_PROXIMITY = Proximity()


# --------------------------------------------------------------------------------------
# Decoder (reference). Constants mirror dev.hushhboard.nlp.DecoderConfig.
# --------------------------------------------------------------------------------------

SUB_ACCENT = 0.10
SUB_NEAR = 0.40      # neighbouring keys (<= 1.25 key widths)
SUB_MID = 0.70       # one key apart (<= 2.1 key widths)
SUB_FAR = 1.00
FIRST_CHAR_EXTRA = 0.25
JOINER_COST = 0.12   # missing/extra apostrophe or hyphen
DEL_DOUBLE = 0.35    # typed the same letter twice
DEL_NEAR = 0.55      # extra tap on a neighbouring key
DEL_FAR = 0.85
INS_DOUBLE = 0.40    # missed a double letter ("ocasion")
INS_FAR = 0.85
TRANSPOSE = 0.55
NEAR_DIST = 1.25
MID_DIST = 2.1

COST_WEIGHT = 8.0
FREQ_SPAN = 14.0
COMPLETION_BASE = 0.8
COMPLETION_PER_CHAR = 0.2
VALID_TYPED_BONUS = 0.5
SHORTCUT_COST = 0.1
SHORTCUT_RANK_STEP = 0.5
SHORTCUT_MIN_FREQ = 185
COMPLETIONS_PER_ROOT = 6
MAX_COMPLETION_ROOTS = 12

# Touch model (key-width units). Spread of taps around a key's centre, and the minimum cost of a
# substitution when real tap coordinates are known.
TOUCH_SIGMA_X = 0.33
TOUCH_SIGMA_Y = 0.40
TOUCH_SUB_BASE = 0.15


def max_cost_for(n: int) -> float:
    if n <= 1:
        return 0.0
    if n == 2:
        return 0.45
    if n == 3:
        return 0.9
    if n == 4:
        return 1.1
    if n <= 6:
        return 1.5
    if n <= 9:
        return 1.9
    return 2.3


def completion_max_cost_for(n: int) -> float:
    if n <= 2:
        return 0.1
    if n == 3:
        return 0.45
    return 0.9


def penalty(cost: float, completion_chars: int) -> float:
    p = COST_WEIGHT * cost
    if completion_chars > 0:
        p += COMPLETION_BASE + COMPLETION_PER_CHAR * completion_chars
    return p


def freq_logp(freq: int) -> float:
    return (freq - 255) / 255.0 * FREQ_SPAN


@dataclass
class Candidate:
    key: str
    display: str | None
    freq: int
    flags: int
    cost: float
    completion_chars: int
    score: float = 0.0
    shortcut: bool = False


class Decoder:
    def __init__(self, lexicon: Lexicon, proximity: Proximity | None = None, lang_priors=None):
        self.lex = lexicon
        self.prox = proximity or Proximity()
        self.lang_priors = lang_priors or {FLAG_LANG_EN: 0.0, FLAG_LANG_SW: -1.2, FLAG_LANG_KE: -0.6}

    # -- edit costs ---------------------------------------------------------------
    def sub_cost(self, t: str, c: str, tap=None, offsets=None) -> float:
        if t == c:
            return 0.0
        if c in JOINERS or t in JOINERS:
            return SUB_FAR
        if base_letter(t) == base_letter(c):
            return SUB_ACCENT
        if tap is not None:
            ct = self.prox.center(t)
            cc = self.prox.center(c)
            if ct is not None and cc is not None:
                nats = touch_nats(tap, cc, offsets, c) - touch_nats(tap, ct, offsets, t)
                if nats < 0.0:
                    nats = 0.0
                cost = TOUCH_SUB_BASE + nats / COST_WEIGHT
                return SUB_FAR if cost > SUB_FAR else cost
        d = self.prox.distance(t, c)
        if d <= NEAR_DIST:
            return SUB_NEAR
        if d <= MID_DIST:
            return SUB_MID
        return SUB_FAR

    def del_cost(self, typed: str, i: int) -> float:
        """Cost of typed[i] being an extra keystroke."""
        t = typed[i]
        if t in JOINERS:
            return JOINER_COST
        if i >= 1:
            p = typed[i - 1]
            if p == t:
                return DEL_DOUBLE
            if self.prox.distance(p, t) <= NEAR_DIST:
                return DEL_NEAR
        return DEL_FAR

    def ins_cost(self, c: str, prev: str | None) -> float:
        """Cost of candidate char c missing from the typed input."""
        if c in JOINERS:
            return JOINER_COST
        if prev is not None and prev == c:
            return INS_DOUBLE
        return INS_FAR

    def lang_prior(self, flags: int) -> float:
        best = None
        for bit, prior in self.lang_priors.items():
            if flags & bit:
                best = prior if best is None else max(best, prior)
        return 0.0 if best is None else best

    # -- search -------------------------------------------------------------------
    def candidates(self, typed: str, allow_offensive: bool = False, touches=None, offsets=None,
                   exclude=None) -> list[Candidate]:
        t = typed.lower()
        n = len(t)
        if n == 0:
            return []
        max_cost = max_cost_for(n)
        comp_max = completion_max_cost_for(n)
        max_depth = n + 3

        found: dict[str, Candidate] = {}
        roots: list[tuple[float, int, int, str]] = []  # (prefixCost, -maxFreq, node, path)

        row0 = [0.0] * (n + 1)
        for i in range(1, n + 1):
            row0[i] = row0[i - 1] + self.del_cost(t, i - 1)

        lex = self.lex

        sub_cache = {}

        def sub_at(i, c):
            k = (i, c)
            v = sub_cache.get(k)
            if v is None:
                tap = touches[i] if touches is not None and i < len(touches) else None
                v = self.sub_cost(t[i], c, tap, offsets)
                sub_cache[k] = v
            return v

        def consider(key, node_index, freq, flags, cost, comp_chars):
            if exclude and key in exclude:
                return
            if flags & FLAG_OFFENSIVE and not allow_offensive and not (key == t and cost == 0.0):
                return
            prev = found.get(key)
            cand = Candidate(key, lex.display(node_index) if flags & FLAG_HAS_DISPLAY else None,
                             freq, flags, cost, comp_chars)
            if prev is None or penalty(cost, comp_chars) < penalty(prev.cost, prev.completion_chars):
                found[key] = cand

        # Iterative DFS: (node, depth, path, prev_row, prev_prev_row, best_root_cost_on_path)
        stack = [(0, 0, "", row0, None, math.inf)]
        while stack:
            node_i, depth, path, row, prow, root_cost = stack.pop()
            ch, cnt, flags, first, freq, maxf, _ = lex.node(node_i)
            if depth > 0:
                if flags & FLAG_TERMINAL and row[n] <= max_cost:
                    consider(path, node_i, freq, flags, row[n], 0)
                # A node is a completion root if the whole typed word aligns to its prefix
                # cheaply enough, and more cheaply than any root above it on this path.
                if row[n] <= comp_max and row[n] < root_cost - 1e-9:
                    roots.append((row[n], -maxf, node_i, path))
                    root_cost = row[n]
            if depth >= max_depth or cnt == 0:
                continue
            for k in range(first + cnt - 1, first - 1, -1):
                kch, _, _, _, _, kmax, _ = lex.node(k)
                c = chr(kch)
                prev_c = path[-1] if path else None
                new = [0.0] * (n + 1)
                new[0] = row[0] + self.ins_cost(c, prev_c)
                best = new[0]
                for i in range(1, n + 1):
                    sub = sub_at(i - 1, c)
                    if i == 1 and depth == 0 and sub > 0.0:
                        sub += FIRST_CHAR_EXTRA
                    v = row[i - 1] + sub
                    v2 = new[i - 1] + self.del_cost(t, i - 1)
                    if v2 < v:
                        v = v2
                    v3 = row[i] + self.ins_cost(c, prev_c)
                    if v3 < v:
                        v = v3
                    if prow is not None and i >= 2 and prev_c is not None and \
                            t[i - 1] == prev_c and t[i - 2] == c and t[i - 1] != t[i - 2]:
                        v4 = prow[i - 2] + TRANSPOSE
                        if v4 < v:
                            v = v4
                    new[i] = v
                    if v < best:
                        best = v
                if best > max(max_cost, comp_max):
                    continue
                stack.append((k, depth + 1, path + c, new, row, root_cost))

        # Completions: best-first by frequency below each root. Heap items are
        # (-priority, kind, nodeIndex, path) with kind 0 = emit word, 1 = expand subtree,
        # so at equal priority a word is emitted before a subtree is expanded.
        roots.sort()
        for prefix_cost, _, root_i, root_path in roots[:MAX_COMPLETION_ROOTS]:
            heap = [(-lex.node(root_i)[5], 1, root_i, root_path)]
            emitted = 0
            while heap and emitted < COMPLETIONS_PER_ROOT:
                negp, kind, ni, p = heapq.heappop(heap)
                if kind == 0:
                    _, _, flags, _, freq, _, _ = lex.node(ni)
                    consider(p, ni, freq, flags, prefix_cost, len(p) - len(root_path))
                    emitted += 1
                    continue
                ch, cnt, flags, first, freq, maxf, _ = lex.node(ni)
                if flags & FLAG_TERMINAL and ni != root_i:
                    heapq.heappush(heap, (-freq, 0, ni, p))
                for k in range(first, first + cnt):
                    kch, _, _, _, _, kmax, _ = lex.node(k)
                    heapq.heappush(heap, (-kmax, 1, k, p + chr(kch)))
        # Texting shortcuts ("im" -> "I'm"): offered as candidates, never auto-applied here.
        for rank_i, expansion in enumerate(lex.shortcut(t)):
            key = normalize_key(expansion)
            hit = lex.lookup(expansion) if " " not in expansion else None
            if hit is not None:
                _, freq, flags = hit
            else:
                freq, flags = 0, FLAG_LANG_EN
            freq = max(freq, SHORTCUT_MIN_FREQ)
            cost = SHORTCUT_COST + SHORTCUT_RANK_STEP / COST_WEIGHT * rank_i
            cand = Candidate(key, expansion if expansion != key else None, freq, flags & ~FLAG_OFFENSIVE, cost, 0,
                             shortcut=True)
            if exclude and key in exclude:
                continue
            prev = found.get(key)
            if prev is None or freq_logp(freq) - penalty(cost, 0) > \
                    freq_logp(prev.freq) - penalty(prev.cost, prev.completion_chars):
                found[key] = cand
        found.pop("", None)
        return list(found.values())

    def score(self, c: Candidate, typed_lower: str) -> float:
        s = freq_logp(c.freq) + self.lang_prior(c.flags) - penalty(c.cost, c.completion_chars)
        if c.cost == 0.0 and c.completion_chars == 0 and c.key == typed_lower:
            s += VALID_TYPED_BONUS
        return s

    def suggest(self, typed: str, max_count: int = 8, allow_offensive=False, touches=None, offsets=None,
                exclude=None) -> list[Candidate]:
        cands = self.candidates(typed, allow_offensive, touches, offsets, exclude)
        tl = typed.lower()
        for c in cands:
            c.score = self.score(c, tl)
        cands.sort(key=lambda c: (-c.score, c.key))
        return cands[:max_count]


def touch_nats(tap, center, offsets=None, ch=None) -> float:
    """Negative log-likelihood (nats, up to a constant) of a tap given an intended key."""
    cx, cy = center
    if offsets is not None and ch is not None:
        o = offsets.get(base_letter(ch))
        if o is not None:
            cx += o[0]
            cy += o[1]
    dx = (tap[0] - cx) / TOUCH_SIGMA_X
    dy = (tap[1] - cy) / TOUCH_SIGMA_Y
    return 0.5 * (dx * dx + dy * dy)


# --------------------------------------------------------------------------------------
# Autocorrect decision. Mirrors dev.hushhboard.nlp.AutoCorrector.
# --------------------------------------------------------------------------------------

AC_MIN_SCORE = -10.0      # plausibility floor for a 4-letter word...
AC_SCORE_SLOPE = 1.0      # ...relaxed per extra letter (long strings are rarely valid unknown words)
AC_MIN_GAP = 2.5
AC_COST_SCALE = 1.0
AC_JOINER_ONLY_COST = 0.15  # corrections allowed for 2-letter words and probable names
AC_APOSTROPHE_MARGIN = 80   # frequency units (0..255 scale)
AC_JOINER_GAP = 1.0         # apostrophe/hyphen-only fixes ("im" -> "I'm") are very reliable

EN_SUFFIXES = ("s", "es", "ed", "d", "r", "st", "ing", "er", "ers", "est", "ly", "ies", "ied", "ier", "ment", "ness", "'s")
SW_INFIXES = ("m", "ni", "ku", "wa", "ki", "vi", "li", "zi", "tu", "mw", "ji")
SW_PREFIXES = ("ku", "ni", "u", "a", "tu", "m", "wa", "li", "ki", "vi", "zi", "i", "ya", "ha", "si", "hu")
SW_TENSES = ("ta", "li", "na", "me", "ki", "ka", "nge", "ngali", "sha")


def common_prefix_len(a: str, b: str) -> int:
    n = 0
    for x, y in zip(a, b):
        if x != y:
            break
        n += 1
    return n


def is_inflection_pair(a: str, b: str) -> bool:
    """Both words share a stem of 4+ letters and differ only by common English endings."""
    p = common_prefix_len(a, b)
    if p < 4:
        return False
    ra, rb = a[p:], b[p:]
    if ra == rb:
        return False
    return (ra == "" or ra in EN_SUFFIXES) and (rb == "" or rb in EN_SUFFIXES)


def is_swahili_morph_variant(a: str, b: str) -> bool:
    """Valid Swahili derivations of each other: passive (-wa), applicative (-ia/-ea), object infix."""
    if len(a) == len(b):
        return False
    short, long_ = (a, b) if len(a) < len(b) else (b, a)
    if len(short) < 4:
        return False
    # passive / applicative: "...a" -> "...wa", "...ia", "...ea"
    if short.endswith("a") and long_[:len(short) - 1] == short[:-1]:
        if long_[len(short) - 1:] in ("wa", "ia", "ea", "iwa", "ewa", "ika", "eka", "isha", "esha", "ana"):
            return True
    # object marker infix after the subject/tense prefix: walio|tangulia -> walio|m|tangulia
    for infix in SW_INFIXES:
        if len(long_) - len(short) != len(infix):
            continue
        for i in range(2, len(short) - 2):
            if long_[:i] == short[:i] and long_[i:i + len(infix)] == infix and long_[i + len(infix):] == short[i:]:
                return True
    return False


def far_first_letter(prox, a: str, b: str) -> bool:
    """First letters differ and are not neighbouring keys (and it is not a swap of the first two)."""
    if not a or not b or base_letter(a[0]) == base_letter(b[0]):
        return False
    if len(a) > 1 and len(b) > 1 and a[0] == b[1] and a[1] == b[0]:
        return False
    if b[0] in JOINERS or a[0] in JOINERS:
        return False
    return prox.distance(a[0], b[0]) > NEAR_DIST


def shares_swahili_affix(a: str, b: str) -> bool:
    """Words that differ only in a Swahili subject/tense prefix are both likely valid."""
    if len(a) < 5 or len(b) < 5:
        return False
    # same verb root at the end, different grammatical prefix
    for k in range(4, min(len(a), len(b)) + 1):
        if a[-k:] != b[-k:]:
            k -= 1
            break
    else:
        k = min(len(a), len(b))
    if k < 4:
        return False
    pa, pb = a[:-k] if k < len(a) else "", b[:-k] if k < len(b) else ""
    return pa != pb and len(pa) <= 5 and len(pb) <= 5


def ac_min_score(n: int) -> float:
    return AC_MIN_SCORE - AC_SCORE_SLOPE * max(0, n - 4)


def ac_max_cost(n: int) -> float:
    if n <= 2:
        return AC_JOINER_ONLY_COST
    if n == 3:
        return 0.6 * AC_COST_SCALE
    if n == 4:
        return 0.9 * AC_COST_SCALE
    if n <= 6:
        return 1.2 * AC_COST_SCALE
    return 1.5 * AC_COST_SCALE


def is_plain_word(typed: str) -> bool:
    return bool(typed) and typed[0].isalpha() and all(c.isalpha() or c in JOINERS for c in typed)


def autocorrect_choice(typed: str, ranked: list, typed_valid: bool, sentence_start: bool,
                       rejected=frozenset(), prox=None, typed_freq: int = 0):
    """ranked: candidates sorted best-first with .score, .cost, .completion_chars, .key, .display.
    Returns the candidate to apply, or None."""
    if not is_plain_word(typed):
        return None
    prox = prox or DEFAULT_PROXIMITY
    tl = typed.lower()
    if tl in rejected:
        return None
    letters = [c for c in typed if c.isalpha()]
    if len(letters) >= 2 and all(c.isupper() for c in letters):
        return None  # acronyms / shouting: leave alone
    if typed_valid:
        for c in ranked:
            # A much more common apostrophe form: "cant" -> "can't", "wont" -> "won't" (not "well" -> "we'll").
            if c.completion_chars == 0 and c.key != tl and c.key.replace("'", "") == tl and \
                    c.freq - typed_freq >= AC_APOSTROPHE_MARGIN:
                return c
        for c in ranked:
            # Fix the casing of words that exist only capitalised ("i" -> "I", "nairobi" -> "Nairobi").
            if c.key == tl and c.cost == 0.0 and c.completion_chars == 0:
                if c.display and c.display != typed and typed == tl and c.display.lower() == tl:
                    return c
                return None
        return None
    # The word is finished (space/punctuation was pressed), so only whole-word readings compete.
    full = [c for c in ranked if c.completion_chars == 0 and c.key != tl]
    if not full:
        return None
    best = full[0]
    n = len(tl)
    max_cost = ac_max_cost(n)
    probable_name = typed[0].isupper() and not sentence_start
    if probable_name:
        max_cost = min(max_cost, AC_JOINER_ONLY_COST)
    if best.cost > max_cost or best.score < ac_min_score(n):
        return None
    if " " in best.key:
        return None  # never auto-expand multi-word shortcuts ("omw")
    if best.shortcut and best.key.replace("'", "").replace("-", "") != tl:
        return None  # "ur" -> "your" is offered in the bar, never applied automatically
    if is_inflection_pair(tl, best.key):
        return None  # "dramatises" vs "dramatised": probably a valid form we don't know
    if best.flags & (FLAG_LANG_SW | FLAG_LANG_KE) and not best.flags & FLAG_LANG_EN:
        # Swahili builds words from prefixes/suffixes; unknown forms are usually valid.
        if shares_swahili_affix(tl, best.key) or is_swahili_morph_variant(tl, best.key):
            return None

    if best.flags & (FLAG_LANG_SW | FLAG_LANG_KE) and len(tl) == len(best.key) + 1 and tl.startswith(best.key):
        return None  # "wangui" vs "wangu", "mtoi" vs "mto": names and word forms, not typos
    if far_first_letter(prox, tl, best.key):
        return None
    joiner_only = best.key.replace("'", "").replace("-", "") == tl
    gap = AC_JOINER_GAP if joiner_only else AC_MIN_GAP
    if len(full) > 1 and best.score - full[1].score < gap:
        return None
    return best


# --------------------------------------------------------------------------------------
# Smart key targets. Mirrors dev.hushhboard.nlp.SmartTargets.
# --------------------------------------------------------------------------------------

ST_AMBIGUITY_NATS = 1.5   # only keys this close (spatially) to the best key compete
ST_LM_WEIGHT = 0.5
ST_SWITCH_MARGIN = 0.4
ST_MISSING_LOGP = freq_logp(0) - 2.0


def next_char_logp(lex: Lexicon, prefix: str, ch: str) -> float | None:
    node = lex.find(prefix)
    if node < 0:
        return None
    child = lex.child(node, ch)
    if child < 0:
        return ST_MISSING_LOGP
    return freq_logp(lex.node(child)[5])


def smart_target(lex: Lexicon, prox: Proximity, prefix: str, tap, default: str, keys, offsets=None) -> str:
    """keys: letters to consider (normally the default key and its neighbours)."""
    base_nats = touch_nats(tap, prox.center(default), offsets, default)
    best_ch, best_total = default, None
    default_lm = next_char_logp(lex, prefix, default)
    if default_lm is None:
        return default
    best_total = -base_nats + ST_LM_WEIGHT * default_lm
    for k in keys:
        if k == default or prox.center(k) is None:
            continue
        nats = touch_nats(tap, prox.center(k), offsets, k)
        if nats - base_nats > ST_AMBIGUITY_NATS:
            continue
        lm = next_char_logp(lex, prefix, k)
        total = -nats + ST_LM_WEIGHT * lm
        if total > best_total + ST_SWITCH_MARGIN:
            best_ch, best_total = k, total
    return best_ch


def apply_case(typed: str, display: str) -> str:
    letters = [c for c in typed if c.isalpha()]
    if len(letters) >= 2 and all(c.isupper() for c in letters):
        return display.upper()
    if typed and typed[0].isupper():
        return display[:1].upper() + display[1:]
    return display


def load_aosp_wordlist(path: str) -> list[tuple[str, int, int]]:
    out = []
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            if not line.startswith(" word="):
                continue
            attrs = dict(kv.split("=", 1) for kv in line.strip().split(",") if "=" in kv)
            if attrs.get("not_a_word") == "true":
                continue
            word = attrs["word"]
            freq = int(attrs.get("f", "0"))
            flags = FLAG_LANG_EN
            if attrs.get("possibly_offensive") == "true" or "offensive" in attrs.get("flags", ""):
                flags |= FLAG_OFFENSIVE
            out.append((word, freq, flags))
    return out
