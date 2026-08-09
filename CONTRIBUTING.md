# Contributing to Kinetica

## The gate

Every change must leave all three green before it lands:

```bash
./gradlew assembleDebug   # build
./gradlew test            # JVM unit + golden-decode suite
./gradlew lint            # kept at zero errors
```

The JVM suite is the project's only automatic safety net - there is no
emulator in the loop. Anything the suite cannot cover (touch behavior,
popups, IME lifecycle) needs an explicit manual test list in the PR
description, with exact steps and expected behavior per item.

## Hard rules

- **Engine purity.** Nothing under `engine/` may import Android types. The
  engine's purity is what makes the golden-decode tests plain JVM tests -
  never leak a platform class in, however convenient.
- **Zero network.** The manifest declares no INTERNET permission and never
  will. No dependency or feature that needs the network.
- **No third-party gesture/prediction libraries.** DTW, trie, resampling,
  and the dual-stream merge are implemented from scratch so every constant
  is understood and tunable. Keep it that way.
- **Commit-only text model.** The word in progress is committed text
  replaced via batch edits - do not introduce
  `setComposingText`/`setComposingRegion` (deliberate: OEM composing-span
  quirks).
- **Tunables live in `engine/KineticaConstants.kt`**, each with a comment
  explaining the rationale for its value. Geometric values in key-width
  units, times in milliseconds.

## Tests

- Every bug fix ships with a named regression test that fails on the
  pre-fix code.
- Use *sloppy* (realistic) fixtures, not perfect-center paths:
  `TestData.sloppySwipe` exists because a real bug was invisible to
  perfect-center fixtures. Run word goldens at several overshoot levels.
- Real-dictionary tests guard assets with `assumeTrue` (skip, not fail)
  and must keep decode latency inside the existing bounds - re-run the
  latency tests after any change to pruning, candidate caps, or ranking.

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

The full recipe - licensing constraints included - is in
[ADDING_A_LANGUAGE.md](ADDING_A_LANGUAGE.md). Only MIT/CC BY-compatible
corpora are acceptable; never ingest non-commercial-licensed data.
