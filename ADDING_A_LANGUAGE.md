# Adding a language to Kinetica

The engine (trie, DTW, merge, scoring) is layout- and locale-agnostic; a
language is data plus registration. This recipe was derived from a file-by-file
audit and is the single source of truth for the process. Spanish is the
reference implementation: grepping the tree for `"es"` alongside `"it"` shows
one complete pass through every step below.

Hard rail: the matching alphabet is a–z + apostrophe (`Alphabet.SIZE = 27`);
accented Latin letters are folded onto a–z by `AccentFolder` and restored via
the `forms` mechanism. `fold` also folds CASE, so a wordlist may carry a
capitalized display form on a lowercase key (German nouns do). Latin-script
languages fit; non-Latin scripts are a major engine change and out of scope
here.

Nine languages ship: en, it, es, pl, cs, nl, de, fr, no. Spanish is still the
smallest complete example; German is the one with extra machinery.

## 1. License the data first

Only these sources are approved (see the kinetica-dictionaries skill and
README "Data sources"; document every new corpus there AND in
THIRD_PARTY_NOTICES before bundling):

- Wordlist: hermitdave **FrequencyWords** `<lang>_50k.txt` (OpenSubtitles
  2018), MIT.
- Bigrams: **Tatoeba** sentence corpus for the language (ISO-639-3 code),
  CC BY 2.0 FR, attribute "tatoeba.org". **Check the sentence count first**:
  Norwegian Bokmål has 18k against German's 780k and ships a bigram table a
  ninth the usual size. That is a lost boost rather than a defect (bundled
  bigrams cover only ~35% of real prose transitions even in English), but it
  belongs in the language's row.
- AOSP LatinIME wordlists (HeliBoard mirror): Apache-2.0, on-device
  **import only**, never bundled.
- FORBIDDEN: Paisà, itWaC, any non-commercial-licensed corpus, FUTO
  (Source First) code or data.

## 2. Generate the dictionary assets

In `tools/generate_assets.py`:
- Add the language to the `--lang` choices.
- Add `WORD_RE["<lang>"]`: the language's full letter set including accented
  characters (mirror the en/it patterns).
- Add `TATOEBA_LANG_CODE["<lang>"] = "<iso3>"` (e.g. es→spa, pt→por, fr→fra).

Run `python3 tools/generate_assets.py --lang <lang>`. Output (into
`app/src/main/assets/dictionaries/`): `<lang>_wordlist.txt` (word TAB count,
30–50k words, MAX_WORD_LEN 20) and `<lang>_bigrams.txt` (w1 TAB w2 TAB count,
≤100k pairs, endpoints filtered to the vocabulary).

## 3. Author the layout

`app/src/main/assets/layouts/qwerty_<lang>.json`: copy `qwerty_it.json`
(same normalized geometry) and edit:
- `name`, `locale` (e.g. `es_ES`).
- `"nativeAccents": true`: **required for any language whose own alphabet uses
  accented letters**, which is every language that needs a layout of its own.
  It tells `LayoutMutations.withoutForeignAlternates` to leave the accents alone,
  so a user who enables "Hide accented letters on long-press" (a setting for
  English, where every accent on the keyboard is foreign) does not lose `ñ` or
  `è`. Omitting it makes the language's own letters trimmable.
- Per-key `alternates` arrays: this is where ALL accents live (there is no
  Kotlin accent table): e.g. Spanish `a → ["á","@"]`, `n → ["ñ","!"]`,
  `?123`-layer additions like `¿ ¡` go in `symbols.json` alternates only if
  wanted. Order freely; `LayoutMutations.withNumberPriority` re-partitions
  letters-vs-symbols generically. `hint` (or first alternate) is the key's
  hint char. Keep at least one non-letter alternate on every key that carries
  accents. The trim above declines to empty a popup, so a key whose alternates
  are all accents keeps all of them, which is a silent exception rather than a
  bug but is not what anyone wants.

QWERTZ and QZERTY use the shared **Letter arrangement** setting; author the
base QWERTY JSON once. Accents follow their letters through the swap, and
the setting applies to every language. Cover the expected arrangements in
the language's golden tests (`TestData.qwertzGeometry()` is available).

An arrangement with different rows needs a different JSON and gesture
geometry, and its golden decodes must use that layout's geometry, not
`TestData.qwertyGeometry()`. **AZERTY is the worked example**:
`azerty_fr.json` runs rows of 10/10/6 with `M` on the home row, declares
`"fixedArrangement": true` so `withLetterArrangement` leaves it alone, and is
selected by `alphaLayoutName()` resolving `<arrangement>_<lang>.json` before
the `qwerty_<lang>` template. Its goldens run on `TestData.azertyGeometry()`.
A language with no file for the selected arrangement falls through to its
ordinary layout, which is what keeps the setting global and the file
per-language.

## 4. Register the language

- `settings/Prefs.kt`: add the code to `ALL_LANGUAGES` (canonical cycle
  order).
- `res/values/arrays.xml`: add the display name to `language_entries` and
  the code to `language_values` (keep indexes aligned).
- `res/values/strings.xml`: add the `subtype_<lang>` label; update
  `pref_enabled_languages_summary`.
- `res/xml/method.xml`: add an IME `<subtype>` for the locale.
- `engine/DictionaryMerger.kt`: add the language's regex to the
  `WORD_RES` table (mirrors the generator's `WORD_RE`); without it an AOSP
  import silently filters the language's accented words.
- `keys/StandaloneLetters.kt`: register the language's one-letter function
  words and test tapped-word autospace. Unregistered languages use the English
  set; dictionary membership cannot distinguish these words from initials.
  **Register even an empty set.** German has no one-letter word, and falling
  back to English would space and capitalize a lone `a` or `i` mid-word.
- `DictionarySettingsActivity` and `alphaLayoutName()` pick the language up
  automatically from `ALL_LANGUAGES` and the bundled layout list.

## 5. Verify accent folding

Every accented letter in the new `WORD_RE` must fold to a–z in
`engine/AccentFolder.kt` (es/pt/de/fr are already covered except verify
œ/æ for French). If you extend the map, extend `AccentFoldingTest` in the
same commit. Words whose display differs from the folded form get accent
restoration for free via `forms` (the "perche" → "perché" mechanism).

## 5a. Case, if the language capitalizes mid-sentence

Only German needs this so far. `tools/generate_assets.py` counts each word's
capitalized against lowercase occurrences in Tatoeba and rewrites the DISPLAY
spelling; the trie key stays lowercase.

- Count **mid-clause only**: skip the first token, and skip any token whose
  predecessor does not end in a letter. Subtitle dialogue capitalizes after
  quotes, dashes and colons, which made `nein`, `hey`, `na` and `hallo` read
  as nouns at ratios of 0.6 to 0.87.
- Above `CASE_ALWAYS_UPPER` the word takes the capital, below
  `CASE_ALWAYS_LOWER` it stays lowercase, and **in between it ships BOTH
  spellings** with its frequency split by the observed ratio. `WordPredictor`
  offers every variant as its own candidate, so `Leben`/`leben` and
  `Recht`/`recht` both work and the corpus decides which leads.
- Three control sets guard the thresholds and the generator FAILS rather than
  write an asset that gets them wrong. Add the language's own controls.

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
- Re-run `decodeLatencyIsBounded`: a new dictionary shape can shift the
  candidate fan-out.

**Pick golden words by measuring, not by taste.** A common word can still lose
to a commoner rival, and a doubled letter is the usual reason: `støtte` loses
to `store`, `mann` to `man`. Probe the candidates first and use the ones that
lead at every overshoot value; record the ones that do not rather than
quietly dropping them.

**Three test files enumerate languages and will silently under-test a new one:**
`keys/AutoCapitalizationTest.kt`, `keys/StandaloneLettersTest.kt` and
`layout/LayoutMutationsTest.kt`'s bundled-layout list. Extend all three.

## 7. Device pass (developer, ~5 min)

Language appears in Settings and the enabled-languages set; language-cycle
chord (`?123`+L) reaches it; spacebar overlay shows the code; a few taps and
swipes decode sensibly; dictionary settings show/import/export it; personal
words learn into it (automatic, `user_words` is keyed word+lang); switching
away and back preserves learned words.

## Known multi-language limitations (documented, not blockers)

- Language auto-detect is pairwise: only the first enabled non-active
  language participates (`KineticaIME` builds one `secondaryPredictor`).
- Enabled-languages cycle order is the canonical `ALL_LANGUAGES` order, not
  user-orderable.
- A language whose own alphabet letters fold onto other letters that are
  themselves words pays for it. Norwegian is the first: æ and å fold to `a`
  and ø to `o`, so `være` shares a node with `vare` and `før` with `for`.
  630 of 49 123 keys carry more than one spelling and the frequency order
  decides which is shown. Measure the count and write it down.
- Apostrophe contractions/elisions: the matching alphabet already includes
  `'` and the decode engine inserts a dictionary apostrophe for free, so a
  language's fixed contractions work by adding the apostrophe forms to its
  wordlist (English "don't"/"aren't"; see `generate_assets.py`
  `CONTRACTIONS`/`augment_contractions`). Productive elisions (Italian
  "nell'immagine") are written with the optional apostrophe key
  (`LayoutMutations.withApostropheKey`, `pref_apostrophe_key`). A former
  Italian-only two-word `ItalianElision` table has been removed.
