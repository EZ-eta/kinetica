# Adding a language to Kinetica

The engine (trie, DTW, merge, scoring) is layout- and locale-agnostic; a
language is data plus registration. This recipe was derived from a file-by-file
audit and is the single source of truth for the process. Spanish is the
reference implementation: grepping the tree for `"es"` alongside `"it"` shows
one complete pass through every step below.

Hard rail: the matching alphabet is a–z + apostrophe (`Alphabet.SIZE = 27`);
accented Latin letters are folded onto a–z by `AccentFolder` and restored via
the `forms` mechanism. Latin-script languages fit; non-Latin scripts are a
major engine change and out of scope here.

## 1. License the data first

Only these sources are approved (see the kinetica-dictionaries skill and
README "Data sources"; document every new corpus there AND in
THIRD_PARTY_NOTICES before bundling):

- Wordlist: hermitdave **FrequencyWords** `<lang>_50k.txt` (OpenSubtitles
  2018) — MIT.
- Bigrams: **Tatoeba** sentence corpus for the language (ISO-639-3 code) —
  CC BY 2.0 FR, attribute "tatoeba.org".
- AOSP LatinIME wordlists (HeliBoard mirror) — Apache-2.0, on-device
  **import only**, never bundled.
- FORBIDDEN: Paisà, itWaC, any non-commercial-licensed corpus, FUTO
  (Source First) code or data.

## 2. Generate the dictionary assets

In `tools/generate_assets.py`:
- Add the language to the `--lang` choices.
- Add `WORD_RE["<lang>"]` — the language's full letter set including accented
  characters (mirror the en/it patterns).
- Add `TATOEBA_LANG_CODE["<lang>"] = "<iso3>"` (e.g. es→spa, pt→por, fr→fra).

Run `python3 tools/generate_assets.py --lang <lang>`. Output (into
`app/src/main/assets/dictionaries/`): `<lang>_wordlist.txt` (word TAB count,
30–50k words, MAX_WORD_LEN 20) and `<lang>_bigrams.txt` (w1 TAB w2 TAB count,
≤100k pairs, endpoints filtered to the vocabulary).

## 3. Author the layout

`app/src/main/assets/layouts/qwerty_<lang>.json` — copy `qwerty_it.json`
(same normalized geometry) and edit:
- `name`, `locale` (e.g. `es_ES`).
- `"nativeAccents": true` — **required for any language whose own alphabet uses
  accented letters**, which is every language that needs a layout of its own.
  It tells `LayoutMutations.withoutForeignAlternates` to leave the accents alone,
  so a user who enables "Hide accented letters on long-press" (a setting for
  English, where every accent on the keyboard is foreign) does not lose `ñ` or
  `è`. Omitting it makes the language's own letters trimmable.
- Per-key `alternates` arrays — this is where ALL accents live (there is no
  Kotlin accent table): e.g. Spanish `a → ["á","@"]`, `n → ["ñ","!"]`,
  `?123`-layer additions like `¿ ¡` go in `symbols.json` alternates only if
  wanted. Order freely; `LayoutMutations.withNumberPriority` re-partitions
  letters-vs-symbols generically. `hint` (or first alternate) is the key's
  hint char. Keep at least one non-letter alternate on every key that carries
  accents — the trim above declines to empty a popup, so a key whose alternates
  are all accents keeps all of them, which is a silent exception rather than a
  bug but is not what anyone wants.

A non-QWERTY arrangement (e.g. AZERTY for French) is just a different JSON,
but it changes gesture geometry — its golden decodes must use that layout's
geometry, not `TestData.qwertyGeometry()`.

## 4. Register the language

- `settings/Prefs.kt` — add the code to `ALL_LANGUAGES` (canonical cycle
  order).
- `res/values/arrays.xml` — add the display name to `language_entries` and
  the code to `language_values` (keep indexes aligned).
- `res/values/strings.xml` — add the `subtype_<lang>` label; update
  `pref_enabled_languages_summary`.
- `res/xml/method.xml` — add an IME `<subtype>` for the locale.
- `engine/DictionaryMerger.kt` — add the language's regex to the
  `WORD_RES` table (mirrors the generator's `WORD_RE`); without it an AOSP
  import silently filters the language's accented words.
- `DictionarySettingsActivity` and `alphaLayoutName()` pick the language up
  automatically from `ALL_LANGUAGES` and the bundled layout list.

## 5. Verify accent folding

Every accented letter in the new `WORD_RE` must fold to a–z in
`engine/AccentFolder.kt` (es/pt/de/fr are already covered except verify
œ/æ for French). If you extend the map, extend `AccentFoldingTest` in the
same commit. Words whose display differs from the folded form get accent
restoration for free via `forms` (the "perche" → "perché" mechanism).

## 6. Golden tests (required before the language is "supported")

Mirror the existing patterns (`RealDictionaryTest` + the
`loadItalian()`/`assumeTrue` template in `ReversalSplitTest`):
- Real-asset load: word count ≥ 30k, trie under the memory budget, a handful
  of common words present.
- 4–6 common words swipe-decode top-1 as `TestData` swipes **on the
  language's own layout geometry** (build the geometry from the new layout's
  key positions if it differs from QWERTY).
- One accented word restores its accent through decode (forms path), and one
  through tap-autocorrect (fold-reaches-node, `isWord(folded)` false).
- `DictionaryMergerTest` case for the new `wordPattern` (AOSP import keeps
  accented words).
- Re-run `decodeLatencyIsBounded` — a new dictionary shape can shift the
  candidate fan-out.

## 7. Device pass (developer, ~5 min)

Language appears in Settings and the enabled-languages set; language-cycle
chord (`?123`+L) reaches it; spacebar overlay shows the code; a few taps and
swipes decode sensibly; dictionary settings show/import/export it; personal
words learn into it (automatic — `user_words` is keyed word+lang); switching
away and back preserves learned words.

## Known multi-language limitations (documented, not blockers)

- Language auto-detect is pairwise: only the first enabled non-active
  language participates (`KineticaIME` builds one `secondaryPredictor`).
- Enabled-languages cycle order is the canonical `ALL_LANGUAGES` order, not
  user-orderable.
- Apostrophe contractions/elisions: the matching alphabet already includes
  `'` and the decode engine inserts a dictionary apostrophe for free, so a
  language's fixed contractions work by adding the apostrophe forms to its
  wordlist (English "don't"/"aren't"; see `generate_assets.py`
  `CONTRACTIONS`/`augment_contractions`). Productive elisions (Italian
  "nell'immagine") are written with the optional apostrophe key
  (`LayoutMutations.withApostropheKey`, `pref_apostrophe_key`). A former
  Italian-only two-word `ItalianElision` table has been removed.
