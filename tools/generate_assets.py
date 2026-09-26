#!/usr/bin/env python3
"""Generate Kinetica dictionary assets.

Produces, per language (--lang en|it|es|pl|cs|nl|de|fr|no, default en):
  app/src/main/assets/dictionaries/<lang>_wordlist.txt   (word TAB freq)
  app/src/main/assets/dictionaries/<lang>_bigrams.txt    (w1 TAB w2 TAB freq)

Primary sources (downloaded):
  - hermitdave/FrequencyWords <lang>_50k.txt (MIT) - unigram frequencies from
    OpenSubtitles 2018. Realistic conversational frequencies, which matter for
    swipe disambiguation ("their" vs "there" is decided mostly by frequency).
  - Bigrams (all languages): counted from the Tatoeba per-language sentence
    corpus (CC BY 2.0 FR, attribution "tatoeba.org"), which matches the
    conversational register of the OpenSubtitles unigrams. English previously
    used Peter Norvig's count_2w.txt, but that data derives from the
    LDC-distributed Google Web Trillion Word Corpus and carries no explicit
    redistribution license, so it cannot be bundled in a public release.

Bigrams are filtered to the unigram vocabulary so every bigram endpoint
resolves to a trie word id at load time.

Offline fallback (English only): /usr/share/dict/words with synthetic Zipf
frequencies. Zipf ranks are alphabetical-order-free (hash-shuffled) so the
fallback does not systematically favor early alphabet words.
"""

from __future__ import annotations

import argparse
import bz2
import collections
import hashlib
import logging
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

LOG = logging.getLogger("generate_assets")

WORDLIST_URL_TMPL = (
    "https://raw.githubusercontent.com/hermitdave/FrequencyWords/"
    "master/content/2018/{lang}/{lang}_50k.txt"
)
TATOEBA_SENTENCES_URL_TMPL = (
    "https://downloads.tatoeba.org/exports/per_language/{code}/"
    "{code}_sentences.tsv.bz2"
)

# Per-language word shape. The trie stores folded a-z + apostrophe; accented
# words survive as display forms, so the Italian pattern admits the accented
# vowels Italian orthography actually uses.
WORD_RE = {
    "en": re.compile(r"^[a-z]+(?:'[a-z]+)*$"),
    "it": re.compile(r"^[a-zàèéìíîòóùú]+(?:'[a-zàèéìíîòóùú]+)*$"),
    # Spanish orthography: acute vowels, diaeresis u (pingüino), ñ. No native
    # apostrophe use, but the shared shape keeps loan contractions loadable.
    "es": re.compile(r"^[a-záéíóúüñ]+(?:'[a-záéíóúüñ]+)*$"),
    # Polish orthography: ogonek vowels and the accented consonants used by
    # native words. The shared apostrophe shape keeps loan forms loadable.
    "pl": re.compile(r"^[a-ząćęłńóśźż]+(?:'[a-ząćęłńóśźż]+)*$"),
    # Czech orthography: acute vowels, caron consonants/vowels, and ring u.
    # The shared apostrophe shape keeps loan forms loadable.
    "cs": re.compile(r"^[a-záčďéěíňóřšťúůýž]+(?:'[a-záčďéěíňóřšťúůýž]+)*$"),
    # Dutch: diaeresis on vowels (beïnvloeden) and acutes on loans (café).
    # The leading-apostrophe clitics ('t, 's, 'n) are rejected by the shared
    # shape, so they never enter the trie - a documented limitation.
    "nl": re.compile(r"^[a-zäëïöüáéíóúè]+(?:'[a-zäëïöüáéíóúè]+)*$"),
    # German: the three umlauts and eszett. Eszett folds to "ss", two letters,
    # so it is the one native character that cannot be popup-inserted mid-word.
    "de": re.compile(r"^[a-zäöüß]+(?:'[a-zäöüß]+)*$"),
    # French: the full accent set plus the œ ligature. Elision attaches the
    # apostrophe to the clitic (l', d', qu'), which the shared shape admits.
    "fr": re.compile(r"^[a-zàâäçéèêëîïôöùûüÿœæ]+(?:'[a-zàâäçéèêëîïôöùûüÿœæ]+)*$"),
    # Norwegian Bokmål: æ ø å are letters of the alphabet, not accents, and each
    # folds onto a-z. é appears in loans (idé).
    "no": re.compile(r"^[a-zæøåé]+(?:'[a-zæøåé]+)*$"),
}
TATOEBA_LANG_CODE = {
    "en": "eng", "it": "ita", "es": "spa", "pl": "pol", "cs": "ces",
    "nl": "nld", "de": "deu", "fr": "fra",
    # Bokmål, not Nynorsk. The corpus is thin at 18k sentences against 90k for
    # Czech, so Norwegian ships a bigram table roughly a tenth the usual size.
    "no": "nob",
}

MAX_WORD_LEN = 20
MIN_WORDS = 30_000
# Bounds the VOCABULARY, not the row count: German's case pass gives 1 005
# words two display spellings each, so its asset is 50 293 rows for 49 288
# distinct words. Applied before that pass, deliberately - re-trimming after it
# would drop real words to make room for a second spelling of another.
MAX_WORDS = 50_000
MAX_BIGRAMS = 100_000
# Italian elision produces clitic prefixes (l', un', dell') that Tatoeba
# tokenization splits with the apostrophe attached; strip everything except
# letters and internal apostrophes.
TOKEN_STRIP_RE = re.compile(r"^[^\w']+|[^\w']+$")

# Common contractions, apostrophe-less spelling -> apostrophe spelling. The
# decode engine inserts a dictionary apostrophe for free during trie descent
# (WordPredictor), so once the apostrophe form is a trie word, swiping/tapping
# the plain letters reaches it with no engine change and no apostrophe key.
#
# WHAT THE SOURCE ACTUALLY SHIPS (this comment used to say the source removes
# the apostrophe, and that wrong sentence is why the frequencies shipped 2-3
# orders of magnitude low). FrequencyWords SPLITS at
# the apostrophe and keeps the clitic as its own entry:
#
#     's 14291013   't 9628970   'm 4386306   're 4059719
#     'll 2913428   've 1991871  'd 1109205
#
# WORD_RE rejects a token starting with "'", so none of those reach the asset,
# and the contraction's mass stays on the STEM ("don" 4158644 at rank 28,
# "didn" 1100643, "needn" 5234 - none of which is an English word). The
# apostrophe-less spelling in the list ("dont" 9523, "heres" 163) is only the
# corpus's count of the MISSPELLING, which is what the old code copied.
# Straight apostrophe U+0027 only (Alphabet.encode drops the curly one).
# Stripped forms that are themselves common standalone words (well/we'll,
# ill/I'll, id/I'd, wed/we'd) are deliberately excluded so the real word is
# never displaced; I-pronoun forms stay lowercase like the rest of the list
# (a capital "I" would fail Alphabet.encode - standalone-I casing is separate).
CONTRACTIONS = {
    "en": {
        "dont": "don't", "wont": "won't", "cant": "can't", "isnt": "isn't",
        "arent": "aren't", "wasnt": "wasn't", "werent": "weren't",
        "doesnt": "doesn't", "didnt": "didn't", "havent": "haven't",
        "hasnt": "hasn't", "hadnt": "hadn't", "wouldnt": "wouldn't",
        "couldnt": "couldn't", "shouldnt": "shouldn't", "mustnt": "mustn't",
        "neednt": "needn't", "aint": "ain't", "its": "it's", "thats": "that's",
        "whats": "what's", "hes": "he's", "shes": "she's", "whos": "who's",
        "theres": "there's", "heres": "here's", "wheres": "where's",
        "hows": "how's", "lets": "let's", "youre": "you're",
        "theyre": "they're", "weve": "we've", "youve": "you've",
        "theyve": "they've", "ive": "i've", "im": "i'm", "youll": "you'll",
        "theyll": "they'll", "youd": "you'd", "theyd": "they'd",
    },
}
# How much more often a contraction is written correctly than misspelled, so
# freq(X'y) = misspelling(Xy) * this, capped by the stem (see estimate below).
#
# MEASURED, not chosen. The 16 "n't" forms whose stem is not an English word
# ("don", "didn", "isn", ...) are a sub-population where the TRUE count is
# known exactly - the stem carries it and nothing else does. Eleven of them
# also have their misspelling in the list, so each yields a ratio:
#
#   don't 437  isn't 719  aren't 596  wasn't 1010  doesn't 721  didn't 768
#   haven't 614  wouldn't 968  couldn't 758  shouldn't 701  ain't 391
#
# Geometric mean 673, 1-sigma spread x/div 1.34. LEAVE-ONE-OUT VALIDATION on
# those eleven: worst error 0.026 in fw, against a defect of 0.43 fw - a 16x
# margin, which is what makes a one-constant proxy admissible. fw is
# log-quantized (Trie.freqByteFor), so even a 3x error in the count is worth
# under 0.05 fw. Confirmed twice outside the calibration set: the rule puts
# i'm at 4410169 against the source's own 'm total of 4386306 (0.5%), and the
# 're/'ve/'ll/'d family sums all come in under their clitic totals.
CONTRACTION_PROXY_RATIO = 673

# Frequency for a contraction with neither a misspelling nor an exclusive stem
# to estimate from. Only reachable for a language with no CONTRACTION_ANALOGY
# entry; kept as a floor rather than dropping the form.
CONTRACTION_FALLBACK_FREQ = 200

# they've/they'll/they'd have no misspelling in the list AND their stem
# ("they") is a real word, so neither input exists. Estimate from the same
# clitic's you-form, scaled by how often each pronoun is misspelled at all
# ("theyre" vs "youre"). Every number comes from the list itself.
#   form -> (sibling misspelling, this pronoun, sibling pronoun)
CONTRACTION_ANALOGY = {
    "theyve": ("youve", "theyre", "youre"),
    "theyll": ("youll", "theyre", "youre"),
    "theyd": ("youd", "theyre", "youre"),
}


# ---------------------------------------------------------------------------
# British spelling variants (en only)
# ---------------------------------------------------------------------------
# Both spellings of every US/UK pair are ALREADY in the FrequencyWords list,
# the US form 1.0x to 4.2x more frequent, so British spelling needs no new
# words and no new corpus - only a re-rank. The asset written here is the pair
# list; DictionaryLoader swaps the two counts when the setting is on, which
# makes the British form exactly as frequent as the American one was. A swap
# rather than a multiplier because the measured ratios span 1.0x to 4.2x, so
# no single constant serves both "realise" and "catalogue", and a swap needs
# no swept value at all.
#
# Regular suffix families are generated by rule. Nothing in English ends
# -ize, -yze or -eled by accident, so these are safe to apply mechanically.
GB_SUFFIX_RULES = [
    ("ization", "isation"), ("izations", "isations"),
    ("ize", "ise"), ("izes", "ises"), ("ized", "ised"), ("izing", "ising"),
    ("izer", "iser"), ("izers", "isers"),
    ("yze", "yse"), ("yzes", "yses"), ("yzed", "ysed"), ("yzing", "ysing"),
    ("eled", "elled"), ("eling", "elling"), ("eler", "eller"),
    ("elers", "ellers"),
]
# Irregular families are a curated list of British stems, because the rules
# that would generate them are not safe: a bare -or -> -our produced "yor" ->
# "your", "hors" -> "hours" and "por" -> "pour" on this very wordlist.
GB_STEMS = [
    "colour", "favour", "honour", "labour", "neighbour", "behaviour",
    "flavour", "humour", "rumour", "harbour", "armour", "vapour",
    "endeavour", "savour", "splendour", "candour", "odour", "valour",
    "tumour", "parlour", "saviour", "demeanour", "clamour",
    "centre", "theatre", "litre", "fibre", "sabre", "sombre", "calibre",
    "lustre", "spectre", "manoeuvre",
    "defence", "offence", "pretence",
    "dialogue", "catalogue", "monologue", "analogue",
    "aluminium", "grey", "plough", "sceptical", "mould", "smoulder",
    "jewellery", "woollen", "pyjamas", "axe", "moustache", "sulphur",
    "marvellous", "travelled", "jeweller",
]
GB_STEM_SUFFIXES = (
    "", "s", "ed", "ing", "ly", "less", "ful", "able", "ist", "ists",
    # Derivations that were missing on the first pass, each found by checking
    # the generated pairs against the British spellings a user would notice:
    # favourite, neighbourhood, behavioural, labourer.
    "ite", "ites", "al", "ally", "hood", "hoods", "er", "ers", "ous", "y",
)
# Pairs whose two forms differ by more than a suffix rule can express.
GB_IRREGULAR = [
    ("maneuver", "manoeuvre"), ("maneuvers", "manoeuvres"),
    ("maneuvered", "manoeuvred"), ("maneuvering", "manoeuvring"),
]
GB_STEM_ENDINGS = [
    ("our", "or"), ("re", "er"), ("ence", "ense"), ("ogue", "og"),
    ("ium", "um"), ("grey", "gray"), ("ough", "ow"), ("ould", "old"),
    ("llous", "lous"), ("lled", "led"), ("ller", "ler"),
    ("yjamas", "ajamas"), ("xe", "x"), ("oustache", "ustache"),
    ("phur", "fur"), ("llery", "lry"), ("llen", "len"), ("sceptical", "skeptical"),
]
# Pairs whose two spellings are DIFFERENT WORDS in British usage. Excluded on
# purpose; the reason is the reason the list exists rather than a rule.
GB_EXCLUDE = {
    # programme is a broadcast or a schedule, a program is software
    "program", "programme",
    # practice is the noun, practise the verb; likewise licence and license
    "practice", "practise", "licence", "license",
    # a meter measures, a metre is a length, in both dialects
    "meter", "metre", "meters", "metres",
    # a dependant is a person, dependent is the adjective
    "dependant", "dependent",
    # each of these has a second, unrelated sense in British English
    "tire", "tyre", "curb", "kerb", "story", "storey", "check", "cheque",
    "draft", "draught", "council", "counsel", "councillor", "counsellor",
}


def gb_variants(counts: dict[str, int]) -> list[tuple[str, str]]:
    """US -> UK spelling pairs where both forms are in the list.

    Only pairs whose US form is currently the MORE frequent are returned. Four
    of the 158 candidates are already the other way round ("dialogue" 5 975
    against "dialog" 399), and swapping those would demote the British
    spelling, which is the opposite of the point.
    """
    pairs: dict[str, str] = {}

    def add(us: str, gb: str) -> None:
        if us in GB_EXCLUDE or gb in GB_EXCLUDE or us == gb:
            return
        if us in pairs or us not in counts or gb not in counts:
            return
        if counts[us] <= counts[gb]:
            return
        pairs[us] = gb

    for us in list(counts):
        for a, b in GB_SUFFIX_RULES:
            if us.endswith(a):
                add(us, us[: -len(a)] + b)
                break
    for stem in GB_STEMS:
        us_stem = None
        for a, b in GB_STEM_ENDINGS:
            if stem.endswith(a):
                us_stem = stem[: -len(a)] + b
                break
        if us_stem is None:
            continue
        for suf in GB_STEM_SUFFIXES:
            add(us_stem + suf, stem + suf)
        # "humour" inflects as "humorous", dropping the u in both dialects
        if stem.endswith("our"):
            add(stem[:-3] + "orous", stem[:-3] + "ourous")
    for us, gb in GB_IRREGULAR:
        add(us, gb)
    return sorted(pairs.items())


def write_gb_variants(out_dir: Path, counts: dict[str, int]) -> Path:
    pairs = gb_variants(counts)
    path = out_dir / "en_gb_variants.txt"
    with path.open("w", encoding="utf-8") as fh:
        for us, gb in pairs:
            fh.write(f"{us}\t{gb}\n")
    LOG.info("wrote %s (%d pairs)", path, len(pairs))
    return path


def fetch(url: str, timeout: int = 120, binary: bool = False) -> str | bytes:
    LOG.info("downloading %s", url)
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        data = resp.read()
    return data if binary else data.decode("utf-8", errors="replace")


def parse_unigrams(raw: str, word_re: re.Pattern[str]) -> list[tuple[str, int]]:
    """FrequencyWords format: 'word count' per line, already sorted by count."""
    rows: list[tuple[str, int]] = []
    for line in raw.splitlines():
        parts = line.strip().split()
        if len(parts) != 2:
            continue
        word, count = parts[0].lower(), parts[1]
        if not count.isdigit():
            continue
        if len(word) > MAX_WORD_LEN or not word_re.match(word):
            continue
        rows.append((word, int(count)))
        if len(rows) >= MAX_WORDS:
            break
    return rows


def fallback_unigrams(dict_path: Path) -> list[tuple[str, int]]:
    """Synthetic Zipf frequencies over the system word list."""
    LOG.warning("using offline fallback %s with synthetic Zipf frequencies", dict_path)
    word_re = WORD_RE["en"]
    words = sorted(
        {
            w.lower()
            for w in dict_path.read_text().splitlines()
            if len(w) <= MAX_WORD_LEN and word_re.match(w.lower())
        }
    )
    # Stable pseudo-random rank so frequency is not correlated with alphabet order.
    ranked = sorted(words, key=lambda w: hashlib.md5(w.encode()).hexdigest())
    ranked = ranked[:MAX_WORDS]
    rows = [(w, max(1, int(1_000_000 / (rank + 1)))) for rank, w in enumerate(ranked)]
    LOG.info("fallback produced %d words", len(rows))
    return sorted(rows, key=lambda r: -r[1])


# ---------------------------------------------------------------------------
# German noun capitalization (de only)
# ---------------------------------------------------------------------------
# German capitalizes every noun, but FrequencyWords is entirely lowercased, so
# without this a German user shift-taps every noun they write. Tatoeba
# PRESERVES case, so the information is recoverable from the corpus already
# downloaded for the bigrams.
#
# It ships as a DISPLAY form on a lowercase trie key, which is the mechanism
# that already turns "perche" into "perché": AccentFolder.fold case-folds, so
# "Haus" reaches the "haus" node and resurfaces with its capital at emit time.
# Nothing about the trie or the swipe geometry changes.
#
# WHICH POSITIONS COUNT. Sentence-initial tokens are skipped, because every
# German sentence starts with a capital and the first token says nothing. So is
# any token whose PREDECESSOR did not end in a letter: subtitle dialogue
# capitalizes after quotes, dashes and colons, and counting those made "nein",
# "hey", "na" and "hallo" look like nouns at ratios of 0.6 to 0.87. That was
# measured on this corpus, not guessed.
CASE_MIN_SIGHTINGS = 5
# The distribution is strongly bimodal, so the threshold is not a judgement call:
# measured on 37 420 mid-sentence words, 11 823 sit below
# 0.1 and 10 776 at or above 0.9, with about 1 100 in between.
CASE_ALWAYS_UPPER = 0.9
CASE_ALWAYS_LOWER = 0.3
# Control sets. Not samples: these are the three populations the thresholds
# have to separate, and the generator FAILS rather than write an asset that
# gets them wrong.
CASE_CONTROL_LOWER = (
    "der", "die", "das", "und", "ist", "nicht", "haben", "sein", "werden",
    "können", "auch", "schon", "immer", "gut", "machen",
)
CASE_CONTROL_UPPER = (
    "haus", "zeit", "mann", "frau", "jahr", "tag", "welt", "geld",
    "kind", "arbeit", "hand", "auto", "wasser",
)
# Words that are BOTH a noun and something else, so both spellings are real and
# both must ship: "Leben"/"leben" (life, to live), "Sie"/"sie" (formal you,
# she), "Morgen"/"morgen" (morning, tomorrow), "Weg"/"weg" (way, away),
# "Liebe"/"liebe" (love, I love). Every variant is offered as its own candidate
# by WordPredictor, so splitting the count by the observed ratio lets the
# suggestion list rank them the way the corpus does.
CASE_CONTROL_BOTH = ("leben", "sie", "morgen", "weg", "liebe")


def tatoeba_case_counts(
    compressed: bytes, vocab: set[str], word_re: re.Pattern[str]
) -> dict[str, tuple[int, int]]:
    """Per word, (capitalized, lowercase) counts in mid-clause position only."""
    cap: collections.Counter[str] = collections.Counter()
    low: collections.Counter[str] = collections.Counter()
    text = bz2.decompress(compressed).decode("utf-8", errors="replace")
    for line in text.splitlines():
        parts = line.split("\t", 2)
        if len(parts) != 3:
            continue
        raw = parts[2].split()
        for i in range(1, len(raw)):
            # Only mid-clause: a capital after punctuation is positional.
            prev = raw[i - 1]
            if not prev or not prev[-1].isalpha():
                continue
            stripped = TOKEN_STRIP_RE.sub("", raw[i])
            if not stripped:
                continue
            lower = stripped.lower()
            if lower not in vocab or not word_re.match(lower):
                continue
            if stripped[0].isupper():
                cap[lower] += 1
            else:
                low[lower] += 1
    return {w: (cap[w], low[w]) for w in set(cap) | set(low)}


def case_decision(counts: tuple[int, int]) -> str:
    """One of "upper", "lower" or "both" for a word's (cap, low) counts."""
    cap, low = counts
    seen = cap + low
    if seen < CASE_MIN_SIGHTINGS:
        return "lower"
    ratio = cap / seen
    if ratio >= CASE_ALWAYS_UPPER:
        return "upper"
    if ratio <= CASE_ALWAYS_LOWER:
        return "lower"
    return "both"


def check_case_controls(
    case_counts: dict[str, tuple[int, int]], vocab: set[str]
) -> list[str]:
    """Control-set violations; empty when the thresholds separated the three."""
    bad = []
    for w in CASE_CONTROL_LOWER:
        if w in vocab and case_decision(case_counts.get(w, (0, 0))) != "lower":
            bad.append(f"{w} is a function word but was capitalized")
    for w in CASE_CONTROL_UPPER:
        if w in vocab and case_decision(case_counts.get(w, (0, 0))) != "upper":
            bad.append(f"{w} is a noun but was not capitalized")
    for w in CASE_CONTROL_BOTH:
        if w in vocab and case_decision(case_counts.get(w, (0, 0))) != "both":
            bad.append(f"{w} has two real spellings but only one was kept")
    return bad


def apply_case_forms(
    unigrams: list[tuple[str, int]], case_counts: dict[str, tuple[int, int]]
) -> list[tuple[str, int]]:
    """Rewrite DISPLAY spellings; the trie key and the total count are unchanged."""
    out: list[tuple[str, int]] = []
    for w, c in unigrams:
        counts = case_counts.get(w)
        decision = "lower" if counts is None else case_decision(counts)
        if decision == "lower":
            out.append((w, c))
            continue
        upper = w[0].upper() + w[1:]
        if decision == "upper":
            out.append((upper, c))
            continue
        cap, low = counts  # type: ignore[misc]
        ratio = cap / (cap + low)
        # Split the word's own frequency by the ratio the corpus shows, so
        # neither spelling is invented and their order is the corpus's.
        out.append((upper, max(1, round(c * ratio))))
        out.append((w, max(1, round(c * (1.0 - ratio)))))
    return out


def tatoeba_bigrams(
    compressed: bytes, vocab: set[str], word_re: re.Pattern[str]
) -> list[tuple[str, str, int]]:
    """Count adjacent in-vocabulary word pairs over Tatoeba sentences.

    Format: 'id<TAB>lang<TAB>sentence' per line, bz2-compressed.
    """
    counts: collections.Counter[tuple[str, str]] = collections.Counter()
    text = bz2.decompress(compressed).decode("utf-8", errors="replace")
    sentences = 0
    for line in text.splitlines():
        parts = line.split("\t", 2)
        if len(parts) != 3:
            continue
        sentences += 1
        tokens = [
            t
            for t in (
                TOKEN_STRIP_RE.sub("", tok.lower()) for tok in parts[2].split()
            )
            if t and word_re.match(t) and t in vocab
        ]
        for w1, w2 in zip(tokens, tokens[1:]):
            counts[(w1, w2)] += 1
    LOG.info("tatoeba: %d sentences, %d distinct bigrams", sentences, len(counts))
    # Hapax pairs are mostly tokenization noise and would bloat the asset.
    rows = [(w1, w2, c) for (w1, w2), c in counts.items() if c >= 2]
    rows.sort(key=lambda r: -r[2])
    return rows[:MAX_BIGRAMS]


def parse_aosp_combined(path: Path, max_primary_count: int) -> list[tuple[str, int]]:
    """Words from an AOSP LatinIME wordlist.combined file (HeliBoard mirror).

    The format stores log-quantized frequencies f in 0..255. They are mapped
    back onto the primary list's raw-count scale via count = M^(f/255), the
    inverse of the quantizer, so merged words rank sensibly against
    OpenSubtitles counts. Abbreviations and possibly-offensive entries are
    skipped (they would surface in suggestions with no way to filter later).
    """
    rows: list[tuple[str, int]] = []
    word_re_any = re.compile(r"\bword=([^,]+),f=(\d+)")
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line.startswith("word="):
            continue
        if "abbreviation" in line or "possibly_offensive=true" in line:
            continue
        m = word_re_any.match(line)
        if not m:
            continue
        word = m.group(1).lower()
        f = int(m.group(2))
        count = max(1, int(max_primary_count ** (f / 255.0)))
        rows.append((word, count))
    return rows


def contraction_freq(misspelled: str, contracted: str, freq: dict[str, int]) -> int:
    """Estimated corpus count of a contraction: min(misspelling * R, stem).

    One rule covers three cases, which is what the min buys. Where the stem is
    not an English word the proxy overshoots and the min returns the STEM,
    which is then the exact answer ("don't" 4158644, "needn't" 5234). Where the
    stem is a real word the min caps the estimate at it ("won't" 571621). Where
    the misspelling is absent the stem is all there is.

    Known and accepted: "can't" lands ~3x high
    (the 't residual says ~1.1M) and "it's"/"let's" take their whole stem, so
    the 's family over-allocates against the 14291013 available. All three are
    within 0.05 fw, i.e. inside twice the proxy's own validated error.
    """
    stem = contracted.split("'")[0]
    proxy: float | None = None
    if misspelled in freq:
        proxy = freq[misspelled] * CONTRACTION_PROXY_RATIO
    elif misspelled in CONTRACTION_ANALOGY:
        sibling, mine, theirs = CONTRACTION_ANALOGY[misspelled]
        if sibling in freq and mine in freq and theirs in freq:
            proxy = freq[sibling] * CONTRACTION_PROXY_RATIO * freq[mine] / freq[theirs]
    ceiling = freq.get(stem)
    if proxy is not None and ceiling is not None:
        return int(min(proxy, ceiling))
    if proxy is not None:
        return int(proxy)
    return ceiling if ceiling is not None else CONTRACTION_FALLBACK_FREQ


def augment_contractions(
    rows: list[tuple[str, int]], lang: str, refresh: bool = False
) -> list[tuple[str, int]]:
    """Add apostrophe contraction forms, re-sorted by descending frequency.

    Each form's frequency is estimated by [contraction_freq] from rows already
    present in the MIT FrequencyWords list (no new corpus is ingested -
    licensing-clean). An apostrophe form already present is left untouched
    unless [refresh], which recomputes it in place: a stale value is precisely
    the item-18 defect, so re-running must be able to correct one.
    """
    mapping = CONTRACTIONS.get(lang)
    if not mapping:
        return rows
    freq = {w: c for w, c in rows}
    # Estimate every form against the ORIGINAL counts, so a refreshed form can
    # never feed another form's estimate (order-independent, re-runnable).
    added: list[tuple[str, int]] = []
    refreshed: dict[str, int] = {}
    for misspelled, contracted in mapping.items():
        value = contraction_freq(misspelled, contracted, freq)
        if contracted not in freq:
            added.append((contracted, value))
        elif refresh and freq[contracted] != value:
            refreshed[contracted] = value
    if not added and not refreshed:
        return rows
    LOG.info(
        "%s: added %d contraction forms, refreshed %d", lang, len(added), len(refreshed)
    )
    rows = [(w, refreshed.get(w, c)) for w, c in rows]
    return sorted(rows + added, key=lambda r: -r[1])


def augment_existing(lang: str, out_dir: Path, dry_run: bool, refresh: bool) -> int:
    """Offline: load the committed <lang>_wordlist.txt, add contraction forms,
    rewrite it. No download and no bigram regeneration.

    With [refresh] this is also the item-18 repair path, and offline is the
    point: every input the estimator needs (stems, misspellings) is already in
    the committed asset, so the diff is exactly the contraction rows and every
    device measurement taken against this asset stays valid.
    """
    path = out_dir / f"{lang}_wordlist.txt"
    if not path.exists():
        LOG.error("wordlist not found: %s", path)
        return 1
    rows: list[tuple[str, int]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        parts = line.split("\t")
        if len(parts) != 2 or not parts[1].isdigit():
            continue
        rows.append((parts[0], int(parts[1])))
    before = dict(rows)
    rows = augment_contractions(rows, lang, refresh=refresh)
    if dry_run:
        for word, count in sorted(rows, key=lambda r: -r[1]):
            if "'" in word and before.get(word) != count:
                LOG.info("  %-11s %10s -> %10d", word, before.get(word, "-"), count)
        LOG.info("dry run: %s %d -> %d rows", path, len(before), len(rows))
        return 0
    path.write_text("".join(f"{w}\t{c}\n" for w, c in rows), encoding="utf-8")
    LOG.info("augmented %s: %d -> %d rows", path, len(before), len(rows))
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--lang",
        choices=("en", "it", "es", "pl", "cs", "nl", "de", "fr", "no"),
        default="en",
    )
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=Path(__file__).resolve().parent.parent
        / "app/src/main/assets/dictionaries",
    )
    parser.add_argument(
        "--wordlist-file",
        type=Path,
        help="local copy of the FrequencyWords <lang>_50k.txt (skips download)",
    )
    parser.add_argument(
        "--bigrams-file",
        type=Path,
        help="local copy of the Tatoeba <code>_sentences.tsv.bz2 (skips download)",
    )
    parser.add_argument(
        "--merge-aosp",
        type=Path,
        help="AOSP wordlist.combined file (e.g. from the Apache-2.0 "
        "AOSP-derived main_* lists mirrored in Helium314/aosp-dictionaries); "
        "words absent from the primary list are merged in",
    )
    parser.add_argument(
        "--augment-existing",
        action="store_true",
        help="offline: add contraction forms to the committed <lang>_wordlist.txt "
        "and rewrite it (no download, no bigram regeneration)",
    )
    parser.add_argument(
        "--refresh-contractions",
        action="store_true",
        help="recompute contraction frequencies already in the list instead of "
        "leaving them untouched (the frequency-estimate repair)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="report what would be written without writing files",
    )
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    lang = args.lang
    word_re = WORD_RE[lang]

    if args.augment_existing:
        return augment_existing(
            lang, args.out_dir, args.dry_run, args.refresh_contractions
        )

    try:
        raw_unigrams = (
            args.wordlist_file.read_text(encoding="utf-8")
            if args.wordlist_file
            else fetch(WORDLIST_URL_TMPL.format(lang=lang))
        )
        unigrams = parse_unigrams(raw_unigrams, word_re)
        if len(unigrams) < MIN_WORDS:
            raise ValueError(f"only {len(unigrams)} usable words from primary source")
    except (urllib.error.URLError, ValueError, OSError) as exc:
        if lang != "en":
            LOG.error("wordlist unavailable for %s (%s); no fallback exists", lang, exc)
            return 1
        LOG.warning("primary wordlist unavailable (%s)", exc)
        unigrams = fallback_unigrams(Path("/usr/share/dict/words"))

    if args.merge_aosp:
        primary = {w for w, _ in unigrams}
        max_count = max(c for _, c in unigrams)
        merged = [
            (w, c)
            for w, c in parse_aosp_combined(args.merge_aosp, max_count)
            if w not in primary
            and len(w) <= MAX_WORD_LEN
            and word_re.match(w)
        ]
        LOG.info("aosp merge: %d new words from %s", len(merged), args.merge_aosp)
        unigrams = sorted(unigrams + merged, key=lambda r: -r[1])

    unigrams = augment_contractions(unigrams, lang, refresh=args.refresh_contractions)

    vocab = {w for w, _ in unigrams}
    try:
        code = TATOEBA_LANG_CODE[lang]
        blob = (
            args.bigrams_file.read_bytes()
            if args.bigrams_file
            else fetch(TATOEBA_SENTENCES_URL_TMPL.format(code=code), binary=True)
        )
        bigrams = tatoeba_bigrams(blob, vocab, word_re)
        # Same corpus, same download: German noun capitalization comes off the
        # case Tatoeba preserves and FrequencyWords threw away.
        if lang == "de":
            case_counts = tatoeba_case_counts(blob, vocab, word_re)
            violations = check_case_controls(case_counts, vocab)
            if violations:
                for v in violations:
                    LOG.error("case control: %s", v)
                LOG.error("case thresholds did not separate the control sets")
                return 1
            decisions = collections.Counter(
                case_decision(c) for c in case_counts.values()
            )
            LOG.info(
                "case: %d capitalized, %d two-spelling, %d lowercase",
                decisions["upper"], decisions["both"], decisions["lower"],
            )
            unigrams = apply_case_forms(unigrams, case_counts)
    except (urllib.error.URLError, OSError) as exc:
        LOG.warning("bigram source unavailable (%s); writing empty bigram table", exc)
        bigrams = []

    LOG.info("unigrams: %d (min required %d)", len(unigrams), MIN_WORDS)
    LOG.info("bigrams:  %d", len(bigrams))
    if len(unigrams) < MIN_WORDS:
        LOG.error("wordlist below required minimum")
        return 1

    if args.dry_run:
        LOG.info("dry run: would write to %s", args.out_dir)
        return 0

    args.out_dir.mkdir(parents=True, exist_ok=True)
    wordlist_path = args.out_dir / f"{lang}_wordlist.txt"
    bigrams_path = args.out_dir / f"{lang}_bigrams.txt"
    wordlist_path.write_text(
        "".join(f"{w}\t{c}\n" for w, c in unigrams), encoding="utf-8"
    )
    # English also carries the US -> UK spelling pair list, derived from the
    # wordlist that was just written, so the two can never drift apart.
    if lang == "en":
        write_gb_variants(args.out_dir, dict(unigrams))
    bigrams_path.write_text(
        "".join(f"{a}\t{b}\t{c}\n" for a, b, c in bigrams), encoding="utf-8"
    )
    LOG.info("wrote %s (%d rows)", wordlist_path, len(unigrams))
    LOG.info("wrote %s (%d rows)", bigrams_path, len(bigrams))
    return 0


if __name__ == "__main__":
    sys.exit(main())
