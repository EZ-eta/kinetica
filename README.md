# Kinetica

[![F-Droid](https://img.shields.io/f-droid/v/com.kinetica.keyboard?logo=fdroid)](https://f-droid.org/packages/com.kinetica.keyboard/)
[![Latest release](https://img.shields.io/github/v/release/EZ-eta/kinetica?logo=github)](https://github.com/EZ-eta/kinetica/releases/latest)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)
[![Support on Ko-fi](https://img.shields.io/badge/support-Ko--fi-FF5A16?logo=kofi&logoColor=white)](https://ko-fi.com/ez_eta)

An open-source (GPL-3.0) Android keyboard built for **two thumbs at once**. Each thumb swipes or
taps on its own, at the same time as the other, and both streams merge into one word. A spiritual
successor to the discontinued Nintype (also known as Keyboard69), written from scratch on Android's
public IME APIs: no fork, no third-party gesture library.

**No network permission.** Nothing you type can leave the phone. Decoding, prediction and learning
run on the device; password and private fields turn off suggestions, trails and learning.

Typing SOMETHING:

- the left thumb taps `S`, then `E`;
- the right thumb swipes `O -> M`, then `T -> H -> I -> N -> G`, overlapping in time;
- Kinetica merges `S + OM + E + THING` into "something".

## Video

https://github.com/user-attachments/assets/7bfdabe4-c265-4b40-8db1-c9eb0d9b14d7

## Themes

<img width="3978" height="1630" alt="Themes" src="https://github.com/user-attachments/assets/2f3935ab-51e2-45b7-befe-e52cf2b9b96a" />

## New in 3.0

- Two-thumb words are read right more often, long words most of all; words up to 40 letters, in
  one stroke or two thumbs.
- A setup on first open, and settings in two groups with search and a field to try the keyboard.
- Day and night looks: the night one shows while the phone is in dark theme.
- An editing page: arrows, selection, word moves, undo and redo, a slide up on `?123` away.
- 1 906 emoji with search in 14 languages.
- Voice input through a voice keyboard you install; Kinetica itself records nothing.
- A new number pad, long-press lists on the symbol pages, cut line, an ISO date.
- Long-press letters stay in the word (`Grüße`), and the letter menus follow your languages.
- Optional: a shortcuts button on the bar, a held shift to swap the slide step, a space that
  leaves the symbols.

The full list is in the [3.0 release notes](https://github.com/EZ-eta/kinetica/releases/tag/v3.0.0).

## Install

[<img src="https://f-droid.org/badge/get-it-on.png" alt="Get it on F-Droid" height="80">](https://f-droid.org/packages/com.kinetica.keyboard/)

F-Droid is the recommended channel: it updates in the background and verifies the build. A new
version reaches it a few days after it is tagged here.

Or take `kinetica-<version>.apk` from the
[latest release](https://github.com/EZ-eta/kinetica/releases/latest):

```bash
adb install -r kinetica-*.apk
```

Then open the Kinetica icon. A short setup turns the keyboard on and asks for your languages,
height, automatic space and theme, then offers a two-thumb practice; Help and about runs it again.
A debug build has a different signing key, so Android refuses to upgrade it: back up first
(Settings > Backup), then uninstall it.

Release APKs are signed with the project's key by the tagged
[release workflow](.github/workflows/release.yml), so the build behind every APK is public.
F-Droid ships Kinetica as a [reproducible build](https://f-droid.org/docs/Reproducible_Builds): it
rebuilds the tag, checks its output against the APK above and publishes the same signature. Either
channel can install and update over the other.

## Features

### Two thumbs

- Two independent swipe/tap streams, one per thumb, merged by contact time. Each thumb is read with
  its own cursor, so a word can go back and forth between them in any order.
- A tap from one thumb inside the other's swipe can double a letter (`h-e-l-o` swipe + `l` tap is
  "hello"), and one tap can stand for a doubled letter (`ottima`, `tutti`).
- Long words up to 40 letters, drawn in one stroke or shared between the thumbs, and learned as you
  write them: German and Polish compounds included.
- Swipe decoding written from scratch: banded dynamic time warping over resampled paths, an anchored
  trie search, frequency and word-pair context.
- One stray tap inside a word is read as no letter when nothing else fits. A touch between keys or
  in the split gap types the nearest letter.
- A thumb left resting on the keys, a third finger while both thumbs draw, and a number-row digit
  brushed mid-swipe type nothing. A held letter opens no menu while the other thumb swipes beside it.
- Practise two thumbs (Help and about): five words drawn over the keyboard, thumb by thumb, typed in
  a practice field.

### Suggestions

- Up to ten candidates; a page holds as many as fit, and the bar swipes sideways to the next one.
  Flick a word up to put it in at once.
- Words sit side by side with a small gap (Suggestion bar > *Compact suggestions*, on), in a text
  size of your choice.
- After a commit the bar becomes a correction strip: tap another word and it replaces the one just
  written, taking its learned weight with it.
- Next-word suggestions while nothing is being typed (Typing, on). After a word, one to three of its
  corrections stay on the left of the bar and the next words fill the rest (Typing, on).
- Recent words: the last two words stay on the bar, each with the words it beat; tap one to swap it
  in (off by default). They have their own text size and a swipe direction to choose.
- *Bold the word space writes* (Suggestion bar, off): only the word a space would write is bold, and
  the letters you tapped come second.
- *Mark new and personal words* (Suggestion bar, off): `+` learned when you write it, `?` unknown,
  `C` your own; no mark for a dictionary word.
- *Cut long words in the middle* (Suggestion bar, off): `organi…zione` keeps the start and the end,
  where many words start alike.
- A tapped word with one stray or one missing letter is offered on the bar, never changed for you.
- Tap completions mid-word (`t-h` offers "the", "they"); the letters you typed are always the last
  zone (second with *Bold the word space writes*), so a word the dictionary lacks goes in with one
  tap.
- Tap autocorrect with three levels. It restores missing accents (`perche` -> `perché`,
  `pozno` -> `późno`), and backspace right after it puts back what you typed.
- In a web address or email field, or beside a completion the app has selected, a word goes in
  as typed.
- A retype button (off by default) takes the word back, and the bar then offers what else the
  gesture could have been.

### Editing

- Tap inside a word you wrote and the bar offers what it had when you wrote it, then the word's
  spelling neighbours; a pick replaces the whole word.
- Backspace to the end of a word to keep working on it with taps or swipes.
- Slide along the spacebar to move the cursor, by letter or by word, with a tick per step. Hold
  shift with the other thumb to swap the step, on the spacebar and the backspace slide (Shortcuts
  and gestures > Swipes and slides > *Hold shift to swap the slide step*, off).
- An editing page, opened by a slide up on `?123`: Select, Select all, Copy, Paste, Ctrl, Cut, Tab,
  Home, End, the arrows, word left and right, Page up and down, Undo, Redo and forward delete. Held keys
  repeat. Any key can be changed, up to four rows (Keys > Editing page).
- Slide left from backspace to mark words for deletion; slide back to keep them, lift to delete.
  The distance per word can be set, and the slide can stop at punctuation, so a link goes a piece
  at a time (Shortcuts and gestures > Swipes and slides). A word step stops at each line break.
- A tap on backspace can take back the last tap or swipe of the word in progress, or of the word
  just written with its automatic space (Typing, off).
- Swipe shift up for `ABC`, right for `Abc`, down for `abc`, or hold it for the menu: on the word at
  the cursor or on selected text. The swipes are edge swipes like any other, so they can be changed.
  A double tap on shift locks caps, at a sentence start too.
- Enter searches, sends or goes where the app asks for it, and its label says which; hold it for a
  new line (Typing, on). In a chat box, *Hold enter to send* swaps the two (Typing, off).
- Keys that fit the field: `@` on the comma in an email field, `/` in a web address, `.com`, `.net`
  and `.org` on the period in both, no capital at their start (Typing, on). Number and phone fields
  can open on the number pad (Typing, off).
- Clean links: tracking parameters like `utm_source`, `fbclid` or YouTube's `si` come out of a link,
  as a shortcut action or on the keyboard's own paste (Typing, off). The rest of the link stays as
  written.
- A new field opens on the letters, wherever the last one was left (Typing, on).
- *Letters after a space on symbols* (Typing, off): a space on a symbols page goes back to the
  letters. The number pad stays.
- Tidy spaces: never two spaces in a row and none before a closing quote (Typing > Spacing, off).
- The marks that take back an automatic space are a list you can edit (Typing > Spacing > *No space
  before*), and a band on the bar shows the wait before the space lands.

### Shortcuts

- Chords: hold `?123` or the spacebar and tap a key. Each chord picks its trigger, so `?123`+`l` and
  space+`l` are two chords. Defaults: `?123`+`l` switches language, `?123`+`p` toggles peck-type;
  both can be removed. Hold the trigger still and each key shows its chord (Shortcuts and gestures,
  on); a small drift keeps them showing.
- Edge swipes: swipe off any key in any direction. Defaults: backspace up `!`, enter up `?`, and
  shift up, right and down for the word's case.
- Text expansions: a short trigger becomes longer text, fired by the Expand action. A trigger can
  have several targets, picked on the bar, and targets can chain. A capital in the trigger can be
  matched exactly, ignored, or carried into the text (`Andor` gives `And/or`), set on the Text
  expansions screen. A space inside a trigger is an error, with a button to remove it.
- A chord, an edge swipe or an expansion can type text, run an action (paste, copy, undo and redo,
  Tab, the arrows, Home, End, Esc, forward delete, the date, clean links, copy or cut the line,
  emoji search, voice input, the editing page) or press Ctrl and a key (Ctrl+A, Ctrl+Del). The date
  is the phone's format or ISO, `2025-12-31` (Suggestion bar > *Date format for the date
  shortcut*).
- Shortcuts on the bar when there is nothing to suggest, and in the `?123` hold menu; both sets are
  chosen and ordered in Suggestion bar. While the chords show, the menu sits on the spacebar so it
  covers none of them.
- *Shortcuts button on the suggestion bar* (Suggestion bar, off): one tap brings the shortcuts back
  over the words until the next key, another brings the words back.
- Settings from the keyboard: hold `?123` and slide onto the gear.
- Voice input: the `∿` action, on the bar, a chord or an edge swipe, hands the field to a voice
  keyboard you install, such as Voxscribe or FUTO Voice Input (source-available, uses the network
  for its models). Kinetica records nothing and asks for no microphone permission. With none turned
  on it opens Settings > Voice input.

### Keys and layout

- Long-press menus on every letter and punctuation key: a row or a 3x3, 4x3 or 5x3 grid. Each
  letter's list can be edited, filled from every enabled language with Default, or given a shortcut
  (`:paste`). Swipe up on the top row, or down on the bottom row, for the key's corner character.
- The letter menus follow your languages: turn German on and `s` offers `ß`, turn it off and `ß`
  leaves. A list you wrote keeps everything else as written.
- Numbers row above the letters (Keys, off). The keyboard grows so the letters keep their size.
- Middle row width (Size and layout): spreads `a s d f g h j k l` toward the edges, for hands used to
  a board without the half-key indent.
- Layout modes: full width, right, left, split, one-handed on either side. Landscape has its own
  height and can be split to the edges, centred or full width. An unfolded phone or a tablet held
  upright uses the landscape arrangement (Size and layout, on).
- Landscape keeps its own margins, middle row width, handle, bar height and text sizes, in its own
  group of Size and layout.
- Height by dragging the handle above the bar, side and bottom margins, a live size preview.
- An optional apostrophe key right of `L`, for `don't` or `nell'immagine` without the symbols page.
- Two symbol pages and a number pad with `+ ±`, `* /`, `= ≠ ≈` and the currency signs on long
  presses; a configurable comma and period key.
- The symbol pages can be rewritten as text, a line per row, and every symbol key can have its own
  long-press list (Keys > Symbol pages).
- Emoji: 1 906 of them, Unicode 16 with sequences and flags. Search in 14 languages from the pane's
  Search or the Emoji search shortcut: a panel takes the bar's place and the keys type the query;
  what you pick joins Frequently used. Also on the comma's long-press (Keys, off).
- Words per minute on the spacebar while you type (Keys, off).

### Look

- Themes: dark, light, Material You, or a palette from one colour you type as `#RRGGBB`. Zen
  mode turns animations off.
- Key borders, on or off, in the theme's colour and a width of your choice.
- A background image under the keyboard, fitted separately for portrait and landscape, with its
  own opacity and the keys' (Theme and look).
- A swipe shows as a trail, as the keys it crosses lighting up, or both; in one colour, a colour
  you type, the theme's colour, or rainbow. The trail can stay until the word ends (Theme and look,
  off).
- Day and night looks (Theme and look, off): a second look, with its own theme, colours, image,
  trail and borders, shows while the phone is in dark theme. Switching it on starts it as a copy
  of the day look.

### Languages

- English, Italian, Spanish, Polish, Czech, Dutch, German, French, Norwegian and Romanian;
  Russian, Ukrainian, Hebrew and Arabic are experimental, each with its own board. 46k to 50k words
  per language with real corpus frequencies, and up to 100k word pairs.
- Switch with a chord without leaving the field; the active language shows on the spacebar.
- Accented words are drawn on their base keys and come out with their accents. A spelling with the
  accents left off is offered only where written text uses it: `pojsc` corrects to `pójść`, while
  `ze` and `że` both stay.
- A letter from a long-press menu stays in the word: `ß` in `Grüße`, `ő`, `ğ`, `ē`. Italian `e`
  also offers the schwa, `ə` and `ɜ`, learned like any other spelling.
- Languages in suggestions (Languages): one at a time (the default); mix,
  the active language and the next enabled one in one list; or follow the language I type, up to
  three as equals, the one you are typing weighs most (experimental).
- British spelling for English.
- A language is data plus registration, so new ones can be contributed:
  [ADDING_A_LANGUAGE.md](ADDING_A_LANGUAGE.md).

### Learning and privacy

- Every committed word earns weight for its language; a word you keep choosing outranks a more
  common one. Hold a suggestion and slide up or down to change its weight, one badge tier per step;
  slide past the lowest tier to block it.
- A word you keep capitalizing yourself, a name or `GitHub`, comes out with its capital. Writing it
  in lowercase counts against the capital, and shift down on it forgets the capital. Long words you
  have taught it complete from their first letters (`fakeco` offers `FakeCompanyName`); tapped as
  `Fake`, shift, `CompanyName`, it stays one word past the automatic space.
- Phrase learning (optional): which word you tend to type after which.
- Dictionary: learned and blocked words per language, with search; import of an AOSP word list,
  merged on the phone.
- Backup: every setting, learned word and shortcut in one text file, the background image too if
  you tick it, restored with progress; the last restore can be undone.

### Settings

- Two groups on the home screen, Everyday and More, each row with an icon, a summary and its
  current value. A search row on top knows several words for each setting.
- *Try the keyboard here*: a field on every settings screen, to see a change at once. It learns
  nothing.
- Changed settings (More): every setting that differs from its default, each with Open and Reset,
  and Reset all, which leaves the languages alone.
- In English and Italian, by the phone's language or Android 13's per-app language;
  [CONTRIBUTING.md](CONTRIBUTING.md) says how to add a translation.
- Learn Kinetica: short tips from the basics up.

## Worth a look in Settings

- **Get rid of a word for good.** Hold it on the bar and slide past the lowest tier, or add it in
  Settings > Dictionary > *Blocked words*. A blocked word is never offered again and never learned
  back. With Typing > Learning > *Blocking a word deletes it*, the word you just wrote also leaves
  the text.
- **Peck-type mode** turns off swiping, suggestions and autocorrect, so every tap types exactly its
  letter. For slang, codes and anything the dictionary keeps fighting.
- **Automatic spaces after tapped words too.** Typing > Spacing > *Autospace tapped words too*. A
  word the dictionary holds gets its space after a pause, and the next letter takes it back if the
  word was not over. Three sliders set the delays.
- **A shorter keyboard.** *Keyboard height* (Size and layout) goes down to 10% of the screen,
  *Suggestion bar height* shrinks the bar and its text, and *Resize handle height* at 0 removes the
  grip.
- **Long-press without the accents.** Keys > Long-press menus > *Hide accented letters on
  long-press* leaves digits, symbols and the letters of your enabled languages, so Polish keeps `ą`
  on the English keys.
- **A much bigger dictionary.** Settings > Dictionary > *Import improved dictionary* takes an AOSP
  `wordlist.combined` and merges it with the bundled list, three to five times the words. Get the
  `main_<code>.combined` file for your language from
  [aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries) under `wordlists/`.
- **Back to a default.** More > *Changed settings* lists what you moved, with a way back for each.

## Help it grow

- A new language is a word list, its word pairs and a registration:
  [ADDING_A_LANGUAGE.md](ADDING_A_LANGUAGE.md). Romanian arrived this way.
- The settings are in English and Italian; [CONTRIBUTING.md](CONTRIBUTING.md) says how to add a
  third language.
- A word it keeps getting wrong, or an app where something breaks: open an
  [issue](https://github.com/EZ-eta/kinetica/issues) with the app's name and what you typed.

## Requirements

- JDK 17
- Android SDK: platform 34, build-tools 34.0.0 (the command-line tools are enough)
- Gradle 8.7 through the bundled wrapper
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

On Linux or Windows, any JDK 17 and the Android command-line tools work the same way: install
platform 34 and build-tools 34.0.0, then point `sdk.dir` in `local.properties` (or `ANDROID_HOME`)
at the SDK.

## Build and install

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug build installs as Kinetica DEV beside the release app, with its own data.

### Release build

Release APKs are signed with a local keystore that never enters version control. Once:

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

Without `keystore.properties`, `assembleRelease` builds an unsigned APK.

The prediction engine is pure Kotlin and tested on the JVM, including golden decodes against the
real dictionaries:

```bash
./gradlew test
```

## Architecture

```
MotionEvent
   -> KeyboardView          touch routing: letters to the engine, space and backspace to controllers
   -> GestureEngine         up to two pointer streams, tap or swipe decided at lift
   -> InputToken            TapToken (anchor) | SwipeToken (resampled path + key contacts)
   -> WordComposer          the word's tokens, decoded on a dedicated thread
   -> WordPredictor         merge orders -> trie search per thumb -> DTW -> ranked candidates
   -> KineticaIME           commit-only text model through InputConnection, the bar, autocorrect
```

| Package | Contents |
|---|---|
| `engine/` | Pure Kotlin: `GestureEngine`, `GestureStream`, `MergeAlternatives`, `WordPredictor`, `ThumbBeam`, `DtwMatcher`, `Trie`, `Alphabet` (one per script), `WordComposer`, `BigramTable`, `AccentFolder`, `DictionaryLoader`, `DictionaryMerger`, models |
| `ime/` | `KineticaIME` (the InputMethodService), editor state, the selection and commit records |
| `ui/` | `KeyboardView`, `SuggestionBarView`, `InputContainerView`, `EmojiPickerView`, theming, trails |
| `layout/` | JSON layouts, mutations (numbers row, apostrophe key, middle row), layout-mode transforms |
| `keys/` | Shift, spacebar and backspace controllers, edge swipes, editor actions, key combinations, the editing page |
| `settings/` | The settings screens, `Prefs`, `KeyboardConfig`, backup |
| `data/` | Room: learned words per language, word pairs, blocked words, chords, expansions; imported word lists |
| `onboarding/` | The setup and the two-thumb tutor |

### Algorithm notes

- **Coordinates** are in key widths, so density, height and layout mode drop out.
- **Swipe matching**: the observed path and each word's ideal path (through key centres) are
  resampled to 32 points by arc length and compared with Sakoe-Chiba banded DTW (radius 4, endpoints
  anchored and weighted, abandoned early against the current floor).
- **Candidates**: a search over a flat trie (two ints per node, children sorted, a 6-bit letter code
  so Cyrillic and Arabic fit). Taps are exact anchors; a swipe is a segment pruned by its start and
  end keys, the path's order, and an ideal-length band. A letter records every pass of the path near
  it, so a revisited letter (the second `e` of "however") still matches. DTW runs on complete words
  only.
- **Two thumbs**: tokens are ordered by contact time, and a near-tie (100 ms) also tries the swapped
  order. A further pass gives each thumb its own cursor and a short hand-back, and a best-first beam
  reads both thumbs in one search; a hand-over that waits long costs its reading, up to a cap. Their
  readings join the same ranked list.
- **Scoring**: `frequency_weight * geometric_term(d) * bigram_multiplier * personal_boost`, with
  `geometric_term(d) = 1 / (1 + min(d, 0.50))^3.75`. Inside half a key width the shape of the gesture
  is scored steeply; past it, a d=0.6 and a d=1.5 match both mean "not this shape", and frequency
  decides. A long buffer counts its fit further before it saturates. The context and personal
  multipliers fade with the candidate's fit, so a common or heavily learned word cannot beat a
  clearly better match.
- **Accents**: the trie stores accent-folded keys per script; spellings that differ from their key
  ("senti", "sentì") carry their own frequencies and come out as separate candidates.
- Decoding takes a few milliseconds on the JVM, about ten for the longest words, against a 100 ms
  budget (see `RealDictionaryTest`).
- **The backspace slide** shows the marked text in a chip above the key, not as a composing region in
  the editor: the text model is commit-only because composing spans behave differently from app to
  app. In password fields the chip shows bullets.

## Data sources

Regenerate a language's dictionary with
`python3 tools/generate_assets.py --lang <code>` (`--dry-run` to preview); the codes are
en, it, es, pl, cs, nl, de, fr, no, ro, ru, uk, he and ar.

- Word frequencies: [hermitdave/FrequencyWords](https://github.com/hermitdave/FrequencyWords)
  (OpenSubtitles 2018), MIT. The 50k list per language; Ukrainian from the full list.
- Word pairs: counted from the [Tatoeba](https://tatoeba.org) sentence corpus of each language,
  [CC BY 2.0 FR](https://creativecommons.org/licenses/by/2.0/fr/), attribution tatoeba.org. The same
  conversational register as the word lists. Tatoeba also decides which accent-less spellings stay
  and which German nouns are capitalized.
- Emoji: every Unicode 16.0 emoji without a skin tone (1 906), from `emoji-test.txt`; search words
  from [Unicode CLDR](https://cldr.unicode.org) 46, Unicode License v3. Built by
  `tools/generate_emoji.py`. An emoji the phone's font cannot draw as one glyph is left out.
- Action icons: [Material Symbols](https://github.com/google/material-design-icons) (Outlined),
  Apache-2.0.

### Open keyboard dictionaries

Considered as replacements for the OpenSubtitles word lists (July 2026):

| Project | Code licence | Dictionary data | Verdict |
|---|---|---|---|
| [HeliBoard](https://github.com/Helium314/HeliBoard) | Apache-2.0 | [Helium314/aosp-dictionaries](https://codeberg.org/Helium314/aosp-dictionaries): the `main_*` lists are AOSP LatinIME dictionaries (Apache-2.0) | The import path: `wordlist.combined` with per-word frequency and flags |
| [FUTO Keyboard](https://github.com/futo-org/android-keyboard) | FUTO Source First 1.1, not OSI-open | Same terms | Rejected: incompatible with open redistribution |
| [FlorisBoard](https://github.com/florisboard/florisboard) | Apache-2.0 | No frequency word lists | Nothing to import |

`tools/generate_assets.py --merge-aosp <wordlist.combined>` merges an AOSP list into a primary word
list; the same merge runs on the phone from Settings > Dictionary, written to app storage and loaded
in place of the bundled list. Abbreviations, offensive entries and spellings that drop a bundled
word's accents are left out. The bundled lists stay OpenSubtitles and Tatoeba: AOSP's Italian list,
merged in, decoded worse in the project's tests. An AOSP merge is Apache-2.0; this section is its
notice.

### Personal weighting

`personal_boost = 1 + 0.15 * ln(1 + personal_count)`, weighted by the candidate's fit like every
other multiplier.

- Every commit adds to the word's count for its language; counts never mix across languages. A
  correction on the bar moves the count to the replacement.
- 20 commits (`1 + 0.15 * ln(21) = 1.46`) overturn the frequency gap between a common word and a
  middling one; the logarithm keeps any one word from taking over.
- Badges show up to 7 dots; each tier doubles the count (1, 2, 4 ... 64), which matches the
  logarithm. A slide on the bar moves one tier per step.
- Learned words merge into the trie at load (every word written twice or more, up to 20 000 per
  language), so a word the dictionary lacks becomes a full candidate.

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

The full text is in [LICENSE](LICENSE); the SPDX identifier is `GPL-3.0-or-later`. The bundled
data and icons carry their own permissive licences (MIT for the FrequencyWords lists, CC BY 2.0 FR
for the Tatoeba word pairs, Unicode License v3 for the emoji data, Apache-2.0 for the icons); the
attributions are in [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) and in the app under Help and about >
Open-source licenses.

### Attribution requirements

If you redistribute Kinetica, modified or not, the GPL asks you to keep the licence notice, state
your changes and make the source available to whoever receives your build. The bundled data adds its
own obligations, all met by shipping `THIRD_PARTY_NOTICES` (the in-app licences screen shows that
file, so an unmodified build already complies):

| What | Licence | What you must do |
|---|---|---|
| Word frequencies (`*_wordlist.txt`) | MIT (hermitdave/FrequencyWords) | Keep the MIT notice |
| Word pairs (`*_bigrams.txt`) | CC BY 2.0 FR (Tatoeba) | Credit `tatoeba.org` |
| Emoji set and search words (`emoji/`) | Unicode License v3 (Unicode, CLDR) | Keep the Unicode notice |
| Action icons (`ic_action_*`) | Apache-2.0 (Material Symbols) | Keep the Apache-2.0 notice |
| An AOSP dictionary you merge in | Apache-2.0 | Keep the Apache-2.0 notice |
| The app | GPL-3.0 | Licence notice, source offer, state changes |

### The name and the icon

Kinetica is the name I use for this project, and the launcher icon is my own artwork. The GPL covers
the code and forks are welcome: please give them a different name and icon, so users can tell your
build from mine and bug reports land in the right place.

No trademark is registered or asserted; this is a request for clarity, not a legal restriction.

## Support

If Kinetica is useful to you, you can [buy me a coffee](https://ko-fi.com/ez_eta).

Entirely optional. The app is free and stays free, has no network permission, shows no ads, and
never asks for anything.

## Contributing

[CONTRIBUTING.md](CONTRIBUTING.md) has the build, test and lint gate, the commit format, the test
rules and how to translate the settings;
[ADDING_A_LANGUAGE.md](ADDING_A_LANGUAGE.md) is the recipe for a new language.
