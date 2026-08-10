# Kinetica

An open-source (GPL-3.0) Android keyboard (IME) built around **two-handed
hybrid swipe/tap input**: each thumb can independently swipe or tap,
simultaneously, and both gesture streams merge into a single word prediction.
It is a spiritual successor to the discontinued Nintype (also known as
Keyboard69), built from first principles on Android's public IME APIs - no
forks, no third-party gesture libraries.

**Private by construction: the app declares zero network permission, so
nothing you type can ever leave your device.** All decoding, prediction, and
learning run locally; the personal dictionary is on-device storage only, and
password/private fields disable suggestions, trails, and learning entirely.

Example - typing SOMETHING:

- Left thumb taps `S`, then `E`
- Right thumb swipes `O -> M`, then `T -> H -> I -> N -> G`, overlapping in time
- Kinetica merges `S + OM + E + THING -> "something"`

## Video Example


https://github.com/user-attachments/assets/220ca042-322f-45df-bbd5-04a6b06d4a66



## Features

- Dual-pointer gesture tracking: two independent swipe/tap streams, merged by
  timestamp with bounded ambiguity handling (near-simultaneous order swaps,
  mid-swipe cross-thumb taps for explicit double letters)
- From-scratch swipe decoding: banded dynamic time warping over
  arc-length-resampled paths, an anchored segmental trie search, frequency and
  bigram-context scoring
- Three languages: 46k-word English, 49k-word Italian, and 49k-word Spanish
  dictionaries with real corpus frequencies; 100k bigrams each; switch
  languages in Settings without restarting the IME. Accented words (perché,
  città, señal, también) are matched through their base-key gesture path and
  restored with accents on commit.
- Tap autocorrect (adjacent-key substitutions and transpositions) with three
  aggressiveness levels; in Italian it also restores missing accents
  (perche -> perché)
- Suggestion bar of 3-5 equal-width, independently tappable candidate zones,
  best first (bold), with flick-up fast commit; up to 10 candidates are kept
  and a leftward swipe starting at the bar's right edge cycles to the next
  page of five (position dots at the bottom center). After a commit the same
  bar becomes the correction strip: the committed word is highlighted and
  tapping any other zone (including the literal tap string after an
  autocorrect) replaces it directly - and takes back the personal weight the
  unwanted commit earned; the correction strip pages with the same gesture
- Live tap completions: a partially tapped word surfaces its dictionary
  extensions as pickable suggestions mid-word (t-h offers "the"/"they"), and
  the exact letters you typed are always the last tappable zone, so an
  out-of-dictionary word commits verbatim with one tap. Completions are
  pick-only by design: space never autocorrects onto a completion
- Editing into a committed word reloads it: backspacing to the end of a word
  re-seeds the predictor with its remaining letters, so continued taps or
  swipes correct that word instead of starting a fragment, and the eventual
  commit earns personal weight for the whole word
- Adaptive personal weighting, partitioned per language: every committed word
  earns personal weight that boosts its future ranking (a consistently chosen
  "thou" eventually outranks "you"); long-press a suggestion to reinforce it
  manually (configurable boost), then slide up while holding to boost further
  or slide down to take weight away, with the badge previewing the pending
  tier live and the change applying on lift; reinforced words show a tiered
  badge (up to 7 dots in a hexagon pattern) in the bar
- Long-press alternates on every letter and punctuation key: accents first,
  digits/symbols last (a Settings toggle flips that priority); hold past the
  long-press delay for a popup, slide sideways to choose, lift to commit; a
  plain long-press commits the first alternate; the default alternate is
  hinted in the key's top-right corner at 40% opacity
- Swipe trails with per-key hue cycling, key-contact bursts, press highlights
- Special keys: spacebar slide moves the cursor; backspace hold repeats;
  backspace slide-left stages whole words for deletion reversibly - the
  staged span shows struck-through in a preview chip, sliding back retracts
  it, and only lifting commits the delete (lift at zero = no-op)
- Customizable edge swipes (Settings > Edge swipe shortcuts): any key +
  direction can insert text or open the emoji picker; defaults are
  backspace-up `!`, enter-up `?`, V-down `,`, B-down `.`, X-down emoji
- Two-page symbols layer (currency, math, brackets on page 2, `=\<` and
  `?123` keys switch pages with a 1/2 indicator) plus a phone-style numpad
  (`?123` tap / slide-right; slide-left on enter returns to letters;
  re-entering symbols always lands on page 1)
- Settings from the keyboard: hold `?123` and slide onto the gear
- Chord shortcuts: hold `?123` and tap a letter to insert its expansion
  (managed in Settings; default is zero chords, fully opt-in per letter)
- Multilingual mid-typing: an ordered set of enabled languages, cycled by a
  chord (hold `?123` + tap the configured letter, default L) without leaving
  the current field; with several languages enabled the active language code
  shows at the spacebar's bottom-center; an experimental "auto-detect
  language per word" toggle (default off) decodes swiped words against both
  enabled languages and prefers the other language only when it is clearly
  more confident
- Contraction/elision writing: English contractions ("don't", "aren't",
  "here's" ...) are in the dictionary and decode straight from the
  apostrophe-free letters (the engine inserts the dictionary apostrophe for
  free); an optional apostrophe key (Settings, off by default) adds a tappable
  "'" right of "L" for writing any elided or contracted word ("nell'immagine",
  "don't") without the symbols layer
- Peck-type mode (Settings toggle or a configurable `?123`-chord): disables
  swipe decoding, suggestions and autocorrect entirely so every tap inserts
  its letter exactly - for slang and out-of-dictionary text the engine keeps
  mangling; the spacebar shows TAP while active
- Configurable comma key (Settings): keep it, remove it (the spacebar widens
  to absorb its slot), or repurpose it as a custom character, a short text,
  or an editor action (paste / select all); a repurposed key keeps "," as its
  first long-press alternate
- Emoji: a Settings toggle (default off) makes the picker the first
  long-press option on the comma key; X-key swipe-down always opens the
  category-tabbed picker regardless of the toggle
- Dictionary management (Settings > Dictionary): per-language base and
  personal dictionary info; on-device import of an AOSP-format
  `wordlist.combined` merged against the bundled wordlist (no Python
  needed); export/import of the personal dictionary as JSON; reset of the
  personal dictionary per language
- Theming: bundled dark theme, Android 12+ Material You wallpaper colors, or
  a full palette derived from one custom primary color; trail color can
  follow the theme accent
- Five layout modes: full, right-aligned, left-aligned, split, one-handed
- Adjustable height (drag the handle above the suggestion bar, or Settings)
- Autospace after swiped words with configurable delay and spacebar indicator
- Zen mode: disables all animation work for battery/GPU savings
- On-device learning: every committed word (and any unknown word) feeds a
  private user dictionary that merges into predictions
- Privacy: **zero network permission**; password fields disable suggestions,
  trails, and learning

## Requirements

- JDK 17
- Android SDK: platform 34, build-tools 34.0.0 (command-line tools suffice)
- Gradle 8.7 via the bundled wrapper (no Gradle install needed)
- minSdk 26 (Android 8.0), targetSdk 34

### Toolchain from zero (macOS, Homebrew)

```bash
brew install openjdk@17
brew install --cask android-commandlinetools
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses
sdkmanager --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-34" "build-tools;34.0.0"
echo "sdk.dir=$ANDROID_HOME" > local.properties
```

On Linux or Windows any JDK 17 plus the Android command-line tools work the
same way: install SDK platform 34 and build-tools 34.0.0, then point
`sdk.dir` in `local.properties` (or `ANDROID_HOME`) at the SDK root.

## Build and install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Release build

Release APKs are signed with a local keystore that never enters version
control. One-time setup:

```bash
mkdir -p keystore
keytool -genkeypair -v -keystore keystore/kinetica-release.keystore \
  -alias kinetica -keyalg RSA -keysize 2048 -validity 10000
cat > keystore.properties <<EOF
storeFile=keystore/kinetica-release.keystore
storePassword=<your store password>
keyAlias=kinetica
keyPassword=<your key password>
EOF
```

Then:

```bash
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Without `keystore.properties`, `assembleRelease` still builds and produces an
unsigned APK (not installable until signed).

Run the unit tests (the whole prediction engine is pure Kotlin and tested on
the JVM, including golden decodes against the real dictionary):

```bash
./gradlew test
```

### Enabling the keyboard

Open the Kinetica launcher icon and follow the three steps: enable Kinetica in
the system keyboard list, select it as the current keyboard, then try the test
field. Settings are reachable from the same screen or from the system IME
settings entry.

## Architecture

```
MotionEvent
   -> KeyboardView          touch routing: letters -> engine, space/backspace -> controllers
   -> GestureEngine         <=2 pointer streams tracked by pointerId, tap/swipe classified at lift
   -> InputToken            TapToken (anchor) | SwipeToken (resampled path + key contacts)
   -> WordComposer          token buffer per word, decode snapshots on a dedicated thread
   -> WordPredictor         merge alternatives -> anchored segmental trie search -> DTW -> top 5
   -> KineticaIME           commit-only text model via InputConnection, suggestions, autocorrect
```

Source layout (package `com.kinetica.keyboard`):

| Package | Contents |
|---|---|
| `engine/` | Pure-Kotlin core: `GestureEngine`, `GestureStream`, `DtwMatcher`, `Trie`, `WordPredictor`, `WordComposer`, `BigramTable`, `MergeAlternatives`, `AccentFolder`, `DictionaryLoader`, `DictionaryMerger`, models |
| `ime/` | `KineticaIME` (InputMethodService), `InputConnectionHelper`, `EditorState` |
| `ui/` | `KeyboardView`, `SuggestionBarView`, `InputContainerView`, `EmojiPickerView`, `KeyboardTheme`, `TrailRenderer`, `BurstRenderer` |
| `layout/` | JSON layout model, loader, mutations (emoji on comma, number priority), the five layout-mode transforms |
| `keys/` | `ShiftState`, spacebar/backspace controllers, `EdgeSwipeDetector`, `EdgeSwipeBindings` |
| `settings/` | `SettingsActivity`, `ChordSettingsActivity`, `EdgeSwipeSettingsActivity`, `DictionarySettingsActivity`, preference fragment, `Prefs`, `KeyboardConfig` |
| `data/` | Room database: user dictionary (personal weights, per language), chord shortcuts; `DictionaryStore` for imported wordlists |
| `onboarding/` | Launcher activity with the enable flow |

### Algorithm notes

- **Coordinates** are key-width units (px / key width): density-, height- and
  layout-mode-independent.
- **Swipe matching**: observed paths and ideal word paths (polylines through
  key centers, consecutive duplicates removed) are resampled to 32 points at
  uniform arc length, then compared with Sakoe-Chiba banded DTW (radius 4,
  endpoint-anchored and endpoint-weighted, early-abandoning against the
  current top-5 score floor).
- **Candidate generation**: DFS over a flat-array trie (2 ints per node,
  children contiguous and letter-sorted; ~1.4 MB for 46k words). Taps are
  exact anchors; swipes are segments pruned by start/end key neighborhoods,
  near-path membership, path-order monotonicity, and an ideal-path length
  band. Each letter records one resample index per distinct pass of the path
  within its radius (not a single global nearest index), so revisited letters
  (the second e of "however") and keys the path merely flies over cannot
  break the monotonicity prune. DTW runs only on complete surviving words.
- **Dual-stream merge**: tokens sort by gesture start time. Cross-stream
  tokens starting within 120 ms also try the swapped order; a tap landing
  inside the other thumb's swipe also tries a split of that swipe around the
  tap (this is how a cross-thumb tap doubles a letter: `h-e-l-o` swipe + `l`
  tap decodes as `hel + l + o` = hello).
- **Scoring**: `frequency_weight * geometric_term(d) * bigram_multiplier *
  personal_boost`, where `geometric_term(d) = 1 / (1 + min(d, 0.50))^3.75`
  saturates: inside half a key width the shape of your gesture is informative
  and is scored steeply, and past that a d=0.6 match and a d=1.5 match are both
  "this is not the shape you drew", so frequency decides between them. Both
  multipliers are then weighted by the fit of the candidate they apply to -
  full strength inside the saturation cap, fading to nothing one whole key
  width out - so a frequent or heavily reinforced word cannot out-argue a
  clearly better-fitting one. Frequencies are log-quantized from the corpus and
  bigram boosts are normalized per preceding word.
- **Accents**: the trie stores accent-folded keys (a-z + apostrophe); nodes
  whose spelling differs from the folded key, or that several spellings share
  ("senti"/"sentì"), carry per-variant display forms with their own
  frequencies, emitted as separate candidates.
- Decode latency budget is < 100 ms from pointer lift; measured single-digit
  milliseconds on the full dictionary (see `RealDictionaryTest`).
- **Reversible backspace slide**: the staged-deletion preview is drawn inside
  the IME's own window (a chip above the backspace key showing the span
  struck-through), NOT via `setComposingRegion` on the editor. Kinetica's
  text model is deliberately commit-only, and composing-region styling is
  unreliable across apps (some editors drop or restyle composing spans;
  autocomplete fields react to composition changes as if the user typed).
  The trade-off: the highlight appears above the keyboard, not inside the
  text field itself, but it renders identically in every app. In password
  fields the preview shows bullets, never the actual characters.

## Data sources

Regenerate the bundled dictionaries with `python3 tools/generate_assets.py
--lang en|it|es` (add `--dry-run` to preview):

- Word frequencies (all languages):
  [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords)
  `en_50k` / `it_50k` / `es_50k` (OpenSubtitles 2018), MIT License.
- Bigrams (all languages): counted from the [Tatoeba](https://tatoeba.org)
  per-language sentence corpora (`eng_sentences.tsv` ~2.03M sentences,
  `ita_sentences.tsv` ~975k, `spa_sentences.tsv` ~441k), licensed
  [CC BY 2.0 FR](https://creativecommons.org/licenses/by/2.0/fr/),
  attribution: tatoeba.org. Conversational register, which matches the
  OpenSubtitles-derived unigrams. (English previously used Peter Norvig's
  `count_2w.txt`; that data derives from the LDC-distributed Google Web
  Trillion Word Corpus and carries no explicit redistribution license, so it
  was regenerated from Tatoeba before the public release.)
- Emoji data: hand-curated `assets/emoji_data.json` (423 plain Unicode emoji
  with names and search keywords); no external dataset. ZWJ sequences are
  deliberately excluded (unsupported devices render them as two glyphs, worse
  than a tofu box); a handful of post-Unicode-13 entries (🥹 🫠 🫡 🫶 🫰 🫵)
  may show as tofu on Android 8-11 devices without updated emoji fonts.

### Open keyboard dictionaries: licensing survey

Considered as higher-quality replacements for the OpenSubtitles-derived
wordlists (July 2026):

| Project | Code license | Dictionary data | Verdict |
|---|---|---|---|
| [HeliBoard](https://github.com/Helium314/HeliBoard) | Apache-2.0 | [Helium314/aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries) (repo LICENSE: GPL-3.0); the `main_*` wordlists are AOSP LatinIME dictionaries (Apache-2.0 at origin) mirrored via OpenBoard; experimental lists CC BY 4.0 | Cleanest import path: raw `wordlist.combined` format with per-word `f=0..255` log frequency, `flags` (abbreviation, possibly_offensive) and per-word next-word bigram ranks; `main_en_US` and `main_it` both exist |
| [FUTO Keyboard](https://github.com/futo-org/android-keyboard) | FUTO Source First 1.1 (non-commercial redistribution limits, not OSI-open) | Same restrictive terms apply to repo contents | Rejected: incompatible with open redistribution |
| [FlorisBoard](https://github.com/florisboard/florisboard) | Apache-2.0 | Ships no frequency wordlists usable for import | Nothing to import |

`tools/generate_assets.py --merge-aosp <wordlist.combined>` can merge an
AOSP-derived list into the primary wordlist (frequencies are de-quantized
onto the raw-count scale, abbreviation/offensive entries dropped). The same
merge now also runs on-device: Settings > Dictionary > "Import improved
dictionary" accepts a user-supplied `wordlist.combined` through the system
file picker (`DictionaryMerger` is a Kotlin port of the Python logic), writes
the merged list to app-internal storage, and loads it instead of the bundled
asset; "Remove imported dictionary" reverts. The bundled assets intentionally
remain pure OpenSubtitles/Tatoeba until a merged dictionary has been
validated on-device against the golden decode tests. Attribution for an AOSP
merge is Apache-2.0 (retain the license notice; this section serves as that
notice).

### Adaptive personal weighting

`personal_boost = 1 + 0.15 * ln(1 + personal_count)`, applied to the score
above and weighted by the candidate's own geometric fit like every other
multiplier.

Every final commit increments the word's personal count (Room-persisted per
language - counts never mix across languages - capped at the top 5000
words at load). Correcting a commit through the suggestion bar transfers the
weight: the replacement earns the count and the replaced word gives its back.
The constants: the frequency-weight gap between a top-frequency word and a
mid-frequency rival is ~1.4x, so 20 commits (`1 + 0.15*ln(21) = 1.46`) flip
such a ranking, 5 commits produce a visible climb, and the logarithm keeps
any single word from swallowing the strip (bigram context tops out at 2.5x
and stays competitive). Because the boost is weighted by the candidate's own
fit, a heavily reinforced word can show a full badge and still not take a
gesture it does not match - reinforcement buys ranking among plausible words,
not against geometry. Long-pressing a suggestion adds a configurable boost
(+1/+5/+10) immediately. Reinforced words show a tiered badge of up to 7
dots (1 center + 6 hexagon corners); tier thresholds double per level
(counts 1, 2, 4, 8, 16, 32, 64), so early uses advance visibly, tier 7 lands
at 64 uses, and equal visual steps match the ln-shaped ranking boost - a +10
manual boost jumps several tiers at once by construction. Learned counts also
merge into the trie at load (`count * 1000` against raw corpus counts) so
out-of-vocabulary words become first-class candidates.

## License

Copyright (C) 2026 Elia Zanella

Kinetica is free software: you can redistribute it and/or modify it under the
terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

Kinetica is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
A PARTICULAR PURPOSE. See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with
this program. If not, see <https://www.gnu.org/licenses/>.

The full text is in [LICENSE](LICENSE); the SPDX identifier is
`GPL-3.0-or-later`. Bundled dictionary data carries its own permissive
licenses (MIT for the FrequencyWords wordlists, CC BY 2.0 FR for the
Tatoeba-derived bigrams); the full attributions live in
[THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) and in-app under
Settings > Open-source licenses.

### Attribution requirements

If you redistribute Kinetica, modified or not, the GPL asks you to keep the
license notice, state your changes, and make the corresponding source
available to whoever receives your build. On top of that the bundled data
carries its own obligations, all of which are satisfied by shipping
`THIRD_PARTY_NOTICES` (the in-app licenses screen renders that same file, so
an unmodified build already complies):

| What | Licence | What you must do |
|---|---|---|
| Word frequencies (`*_wordlist.txt`) | MIT (hermitdave/FrequencyWords) | Retain the MIT notice |
| Bigrams (`*_bigrams.txt`) | CC BY 2.0 FR (Tatoeba) | Credit `tatoeba.org` |
| An AOSP dictionary you merge in | Apache-2.0 | Retain the Apache-2.0 notice |
| The app itself | GPL-3.0 | Licence notice, source offer, state changes |

Emoji metadata is hand-curated for this project and carries no third-party
obligation.

### A note on the name and icon

Kinetica is the name I use for this project, and the launcher icon is my own
artwork. The GPL covers the code, and forks are genuinely welcome - please
rebrand them. Use a different app name and a different icon so users can tell
your build from mine, and so bug reports and reviews land in the right place.

No trademark is registered and none is being asserted; this is a request for
clarity, not a legal restriction. The code itself is yours to use under the
GPL, name aside.

## Support

If Kinetica is useful to you, you can [buy me a coffee](https://ko-fi.com/ez_eta).

Entirely optional. The app is and stays free, declares no network permission,
shows no ads, and will never ask you for anything at runtime.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the build/test/lint gate, commit
conventions, and the regression-test rules; [ADDING_A_LANGUAGE.md](ADDING_A_LANGUAGE.md) documents
the end-to-end recipe for contributing a new language.
