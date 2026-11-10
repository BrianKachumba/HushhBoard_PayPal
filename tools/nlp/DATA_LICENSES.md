# HushhBoard word engine: data sources and licences

The dictionary shipped in `app/src/main/assets/ime/dict/hushh/en_ke_sw.hdict` is built by
`tools/nlp/build_assets.py` from the following sources. All of them allow commercial use and
redistribution inside an app.

| File | Source | Licence | What we use |
|---|---|---|---|
| `data/en_GB_wordlist.combined.gz` | Android Open Source Project, LatinIME `dictionaries/` (via the LineageOS mirror) | Apache-2.0 | English (UK) words, frequencies, offensive-word flags |
| `data/sw_blinkered_counts.tsv`, `data/sw_blinkered_words.txt` | [blinkered-dictionary-sw](https://github.com/blinkered/blinkered-dictionary-sw), Tight Line LLC | CC0-1.0 (public domain) | Swahili word list and per-source occurrence counts. **Only the shipped (CC0) words are included.** `dropped.tsv` (LGPL) is deliberately not used. |
| `data/ke_curated.tsv` | HushhBoard (original work) | Apache-2.0 | Sheng, Kenyan English, brands, places, names |
| `data/en_chat.tsv` | HushhBoard (original work) | Apache-2.0 | Frequency floors for conversational English |
| `data/shortcuts.tsv` | HushhBoard (original work) | Apache-2.0 | Texting shortcuts ("im" → "I'm") |

## Next-word model

`app/src/main/assets/ime/dict/hushh/en_ke_sw.hngm` holds word-sequence statistics (which words
follow which, as quantised probabilities) learned from the corpora below. No sentence or
dialogue text is shipped. `tools/nlp/fetch_corpora.py` downloads them; `build_assets.py` builds
the model (deterministically: the same corpora always give the same file).

| Corpus | Source | Licence | What we use |
|---|---|---|---|
| Common Voice sentences, `server/data/en`, `server/data/sw` | [Mozilla Common Voice](https://github.com/common-voice/common-voice) | CC0-1.0 (the project states that all text in `server/data` is CC0) | English and Swahili sentences |
| Taskmaster-1 (`TM-1-2019`) and Taskmaster-2 (`TM-2-2020`) | [Google Research](https://github.com/google-research-datasets/Taskmaster) | CC BY 4.0, **attribution required** | Conversational English (task dialogues) |
| MultiWOZ 2.2, train and dev splits | [budzianowski/multiwoz](https://github.com/budzianowski/multiwoz) | MIT | Conversational English |

| `tools/nlp/data/chat_en.txt`, `chat_sw.txt` | HushhBoard (original, hand-written) | Apache-2.0 | Everyday texting in English, Swahili and Sheng. Scaled to carry 30% / 12% of word-pair evidence so common chat contexts ("I am", "Habari ya", "Niko") are predicted like a phone keyboard, not like a booking dialogue |

Considered and rejected: MasakhaNER and LAFAND-MT (non-commercial licences), Rogendo/Eng-Swa-Dataset
(no licence).

Attribution for the in-app "Open-source licences" screen:

- *Android Open Source Project, LatinIME dictionaries.* Copyright The Android Open Source Project. Apache License 2.0.
- *Blinkered dictionary: Swahili.* Tight Line LLC. Word list dedicated to the public domain (CC0 1.0).
- *Mozilla Common Voice* sentence corpus. Dedicated to the public domain (CC0 1.0).
- *Taskmaster-1 and Taskmaster-2.* Bill Byrne, Karthik Krishnamoorthi, Chinnadhurai Sankar, Saravanan Ganesh,
  Arvind Neelakantan, Amit Dubey, Kyu-Young Kim and Andy Cedilnik, Google LLC. CC BY 4.0
  (https://creativecommons.org/licenses/by/4.0/). Converted into word-sequence statistics.
- *MultiWOZ 2.2.* Copyright (c) 2019 Paweł Budzianowski. MIT License.

## Rebuilding

```
python3 tools/nlp/fetch_corpora.py  # next-word corpora into /tmp/corpus (skip to keep the shipped model)
python3 tools/nlp/build_assets.py   # dictionary + next-word model + golden test expectations
python3 tools/nlp/eval.py           # quality report (add --tune to grid-search weights, --misses to list failures)
python3 tools/nlp/eval_ngrams.py    # next-word quality on held-out text (every 50th line is never trained on)
python3 tools/nlp/eval_ac_ctx.py    # autocorrect safety with sentence context
```

Any change to scoring constants must be made in both `tools/nlp/hdict.py` and
`lib/nlp/src/main/kotlin/dev/hushhboard/nlp/Decoder.kt`; `GoldenTest` fails if they diverge.

The n-gram model is tied to one dictionary build (it stores dictionary node indices). Rebuild both
together; the app refuses a mismatched pair and falls back to personal predictions only.
Context constants live in both `tools/nlp/ngrams.py` and `NgramModel.kt`; `NgramTest` fails if they diverge.
