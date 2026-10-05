# Contributing to Kinetica

## The gate

Every change must leave all three green before it lands:

```bash
./gradlew assembleDebug   # build
./gradlew test            # JVM unit + golden-decode suite
./gradlew lint            # kept at zero errors
```

The JVM suite is the only automatic check; no emulator runs. Anything it
cannot cover (touch, popups, the IME lifecycle) needs a manual test list in
the PR description, with exact steps and the expected result for each.

## Hard rules

- **Engine purity.** Nothing under `engine/` imports Android types; that
  is what keeps the golden-decode tests plain JVM tests.
- **Zero network.** The manifest declares no INTERNET permission and never
  will. No dependency or feature that needs the network.
- **No third-party gesture/prediction libraries.** DTW, trie, resampling,
  and the dual-stream merge are implemented from scratch so every constant
  is understood and tunable. Keep it that way.
- **Commit-only text model.** The word in progress is committed text,
  replaced through batch edits. No `setComposingText` or
  `setComposingRegion`: composing spans behave differently from app to app.
- **Tunables live in `engine/KineticaConstants.kt`**, each with a comment
  explaining the rationale for its value. Geometric values in key-width
  units, times in milliseconds.
- **The release build has to reproduce.** F-Droid rebuilds every tag and
  publishes this project's signed APK only when its build matches ours. AGP,
  Kotlin and `buildToolsVersion` stay pinned to exact versions, and nothing
  that varies at build time (a timestamp, a hostname, a generated id) may
  enter the APK. `signingConfigs` exists only when `keystore.properties`
  does, which lets a build server produce an unsigned APK without patching
  the build file: keep that conditional. `tools/compare_apks.py` checks it:
  two builds of one commit differ only in the signature.

## Tests

- Every bug fix ships with a named regression test that fails on the
  pre-fix code.
- Use *sloppy* (realistic) fixtures, not perfect-center paths:
  `TestData.sloppySwipe` exists because a real bug was invisible to
  perfect-center fixtures. Run word goldens at several overshoot levels.
- Real-dictionary tests guard assets with `assumeTrue` (skip, not fail)
  and keep decode latency inside the existing bounds. Re-run the latency
  tests after any change to pruning, candidate caps or ranking.

## Commit format

```
scope(module): imperative object
```

At most 72 characters in the subject, no emojis, one coherent change per
commit, build+tests+lint green at every commit. Examples from history:

```
engine(merge): split swipes around cross-stream swipes
ui(popup): elevate clipped popups into a render-only PopupWindow
lang(es): add Spanish dictionary, layout, and registration
```

## Adding a language

The recipe, licensing included, is in
[ADDING_A_LANGUAGE.md](ADDING_A_LANGUAGE.md). Only MIT or CC BY compatible
data is accepted; never data under a non-commercial licence.

## Translating the settings

The settings, tips and notices are Android string resources, so a
translation is one folder and no code.

- Copy `app/src/main/res/values/strings.xml` to
  `app/src/main/res/values-<code>/strings.xml`: `values-fr`, `values-de`,
  or `values-pt-rBR` for a region.
- Translate the text between the tags. Never change a `name`.
- Leave out every row marked `translatable="false"`.
- A string you leave out shows in English, so a partial file works.
- The `*_entries` arrays in `arrays.xml` translate too, same count and
  same order. Never the `*_values` arrays: they are what is stored.
- Keep `%1$s`, `%2$d` and the like. Their order may change with the
  sentence.
- Escape apostrophes and double quotes with a backslash, `l\'app`, and a
  `?` or `@` that starts a string.
- Plurals take every quantity your language has. Lint names the missing
  ones.
- The comments in `strings.xml` mark words the keyboard reads back, such
  as `:paste` and the Ctrl key names. Keep those in English.
- English in every language: the key caps (`?123`, `ABC`, `TAP`), the
  Ctrl key names, emoji names and the tutor's practice words. The
  settings search matches the translated titles plus extra English words.
- Add the code to `app/src/main/res/xml/locales_config.xml`, so Android 13
  and later lists the language under App languages.
- `values-it` is a complete example.
- Check with `./gradlew lint test`. `TranslationTest` checks every
  `values-*` folder: names, format arguments, array lengths, quotes and the
  locale list.
