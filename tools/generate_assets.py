#!/usr/bin/env python3
"""Generate Kinetica dictionary assets.

Produces, per language (--lang en|it|es|pl|cs|nl|de|fr|no|ru|he|ar|uk, default en):
  app/src/main/assets/dictionaries/<lang>_wordlist.txt   (word TAB freq)
  app/src/main/assets/dictionaries/<lang>_bigrams.txt    (w1 TAB w2 TAB freq)

Primary sources (downloaded):
  - hermitdave/FrequencyWords <lang>_50k.txt (MIT) - unigram frequencies from
    OpenSubtitles 2018. Realistic conversational frequencies, which matter for
    swipe disambiguation ("their" vs "there" is decided mostly by frequency).
  - Bigrams (all languages): counted from the Tatoeba per-language sentence
    corpus (CC BY 2.0 FR, attribution "tatoeba.org"), which matches the
    conversational register of the OpenSubtitles unigrams. Peter Norvig's
    count_2w.txt is not used: it derives from the LDC-distributed Google Web
    Trillion Word Corpus and carries no explicit redistribution license.

Bigrams are filtered to the unigram vocabulary so every bigram endpoint
resolves to a trie word id at load time.

Accented languages drop the spellings with accents left off that the subtitle lists
carry as words, judged by Tatoeba's written use (clean_diacritics).

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
import math
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
# Ukrainian is read from the full list: the 50k one is a third Russian once cleaned,
# which leaves too few words (see clean_ukrainian).
WORDLIST_FULL_URL_TMPL = (
    "https://raw.githubusercontent.com/hermitdave/FrequencyWords/"
    "master/content/2018/{lang}/{lang}_full.txt"
)
TATOEBA_SENTENCES_URL_TMPL = (
    "https://downloads.tatoeba.org/exports/per_language/{code}/"
    "{code}_sentences.tsv.bz2"
)

# Per-language word shape. The trie stores folded a-z + apostrophe; accented
# words survive as display forms, so the Italian pattern admits the accented
# vowels Italian orthography uses.
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
    # shape, so they never enter the trie, a known limitation.
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
    # Russian: the 33 letters, ё included (it folds onto е at load). No apostrophe;
    # hyphenated forms (кто-то) are left out, as the Latin lists leave theirs.
    "ru": re.compile(r"^[а-яё]+$"),
    # Hebrew: 22 letters and 5 final forms, written without vowel points. A geresh is
    # normalised to the ASCII apostrophe first (normalise), which the trie keeps.
    "he": re.compile(r"^[\u05D0-\u05EA]+(?:'[\u05D0-\u05EA]+)*$"),
    # Arabic: the letters from hamza to yeh, plus wasla; short vowels and tatweel are
    # stripped first (normalise), since text is written without them.
    "ar": re.compile(r"^[\u0621-\u063A\u0641-\u064A\u0671]+$"),
    # Ukrainian: 33 letters, ґ included (it folds onto г at load), and the apostrophe
    # inside a word (п'ять). No ы, э, ъ or ё, so most Russian stays out.
    "uk": re.compile(
        r"^[абвгґдеєжзиіїйклмнопрстуфхцчшщьюя]+(?:'[абвгґдеєжзиіїйклмнопрстуфхцчшщьюя]+)*$"
    ),
}

# Marks a script writes optionally, removed before a word is matched or counted.
ARABIC_MARKS_RE = re.compile(r"[\u0640\u064B-\u0652\u0670]")
# Ukrainian writes its apostrophe three ways, and marks stress with a combining acute.
UKRAINIAN_APOSTROPHES_RE = re.compile("[\u2019\u02BC\u2018`]")


def normalise(lang: str, word: str) -> str:
    """The spelling a word is stored under: marks stripped, geresh as apostrophe."""
    if lang == "ar":
        return ARABIC_MARKS_RE.sub("", word)
    if lang == "he":
        return word.replace("\u05F3", "'").replace("\u2019", "'")
    if lang == "uk":
        return UKRAINIAN_APOSTROPHES_RE.sub("'", word).replace("\u0301", "")
    return word
TATOEBA_LANG_CODE = {
    "en": "eng", "it": "ita", "es": "spa", "pl": "pol", "cs": "ces",
    "nl": "nld", "de": "deu", "fr": "fra",
    # Bokmål, not Nynorsk. The corpus is thin at 18k sentences against 90k for
    # Czech, so Norwegian ships a bigram table roughly a tenth the usual size.
    "no": "nob",
    "ru": "rus", "he": "heb", "ar": "ara", "uk": "ukr",
}

MAX_WORD_LEN = 20
MIN_WORDS = 30_000
# Bounds the vocabulary, not the row count: German's case pass gives 1 005
# words two display spellings each, so its asset is 50 293 rows for 49 288
# distinct words. Applied before that pass, because re-trimming after it would
# drop real words to make room for a second spelling of another.
MAX_WORDS = 50_000
MAX_BIGRAMS = 100_000
# Italian elision produces clitic prefixes (l', un', dell') that Tatoeba
# tokenization splits with the apostrophe attached; strip everything except
# letters and internal apostrophes.
TOKEN_STRIP_RE = re.compile(r"^[^\w']+|[^\w']+$")

# Common contractions, apostrophe-less spelling -> apostrophe spelling. The
# decoder inserts a dictionary apostrophe for free during trie descent
# (WordPredictor), so once the apostrophe form is a trie word, swiping or
# tapping the plain letters reaches it with no apostrophe key.
#
# FrequencyWords splits at the apostrophe and keeps the clitic as its own entry:
#
#     's 14291013   't 9628970   'm 4386306   're 4059719
#     'll 2913428   've 1991871  'd 1109205
#
# WORD_RE rejects a token starting with "'", so none of those reach the asset,
# and the contraction's mass stays on the stem ("don" 4158644 at rank 28,
# "didn" 1100643, "needn" 5234, none of them English words). The
# apostrophe-less spelling in the list ("dont" 9523, "heres" 163) counts only
# the misspelling, 2-3 orders of magnitude below the contraction.
# Straight apostrophe U+0027 only (Alphabet.encode drops the curly one).
# Stripped forms that are common words themselves (well/we'll, ill/I'll,
# id/I'd, wed/we'd) are excluded so the real word is never displaced; I-pronoun
# forms stay lowercase like the rest of the list (a capital "I" would fail
# Alphabet.encode; standalone-I casing is handled separately).
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
# Measured: for the 16 "n't" forms whose stem is not an English word ("don",
# "didn", "isn", ...) the stem carries the true count. Eleven of them also have
# their misspelling in the list, so each yields a ratio:
#
#   don't 437  isn't 719  aren't 596  wasn't 1010  doesn't 721  didn't 768
#   haven't 614  wouldn't 968  couldn't 758  shouldn't 701  ain't 391
#
# Geometric mean 673, 1-sigma spread x/div 1.34. Leave-one-out on those eleven:
# worst error 0.026 fw against a 0.43 fw defect, a 16x margin, so one constant
# is enough. fw is log-quantized (Trie.freqByteFor), so even a 3x error in the
# count is worth under 0.05 fw. Outside the calibration set the rule puts i'm
# at 4410169 against the source's 'm total of 4386306 (0.5%), and the
# 're/'ve/'ll/'d family sums all stay under their clitic totals.
CONTRACTION_PROXY_RATIO = 673

# Frequency for a contraction with neither a misspelling nor an exclusive stem
# to estimate from. Only reachable for a language with no CONTRACTION_ANALOGY
# entry; a floor, so the form is not dropped.
CONTRACTION_FALLBACK_FREQ = 200

# they've/they'll/they'd have no misspelling in the list and their stem
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
# Both spellings of every US/UK pair are already in the FrequencyWords list,
# the US form 1.0x to 4.2x more frequent, so British spelling needs no new
# words and no new corpus, only a re-rank. The asset written here is the pair
# list; DictionaryLoader swaps the two counts when the setting is on, so the
# British form takes the American one's frequency. A swap, not a multiplier,
# because the ratios span 1.0x to 4.2x and no single constant serves both
# "realise" and "catalogue".
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
# "your", "hors" -> "hours" and "por" -> "pour" on this wordlist.
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
    # Derivations a user would notice missing: favourite, neighbourhood,
    # behavioural, labourer.
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
# Pairs whose two spellings are different words in British usage, so a swap
# would replace one word with another.
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

    Only pairs whose US form is the more frequent are returned. Four of the
    158 candidates are already the other way round ("dialogue" 5 975 against
    "dialog" 399), and swapping those would demote the British spelling.
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


def parse_unigrams(
    raw: str, word_re: re.Pattern[str], lang: str = "en"
) -> list[tuple[str, int]]:
    """FrequencyWords format: 'word count' per line, already sorted by count."""
    rows: list[tuple[str, int]] = []
    for line in raw.splitlines():
        parts = line.strip().split()
        if len(parts) != 2:
            continue
        word, count = normalise(lang, parts[0].lower()), parts[1]
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
# without this a German user shift-taps every noun. Tatoeba preserves case, so
# the information is in the corpus already downloaded for the bigrams.
#
# It ships as a display form on a lowercase trie key, the mechanism that turns
# "perche" into "perché": AccentFolder.fold case-folds, so "Haus" reaches the
# "haus" node and resurfaces with its capital at emit time. The trie and the
# swipe geometry are unchanged.
#
# Which positions count: sentence-initial tokens are skipped, since every
# German sentence starts with a capital. So is any token whose predecessor did
# not end in a letter: dialogue capitalizes after quotes, dashes and colons,
# and counting those made "nein", "hey", "na" and "hallo" look like nouns at
# ratios of 0.6 to 0.87 on this corpus.
CASE_MIN_SIGHTINGS = 5
# The distribution is strongly bimodal, so the threshold is not a judgement call:
# of 37 420 mid-sentence words, 11 823 sit below 0.1 and 10 776 at or above
# 0.9, with about 1 100 in between.
CASE_ALWAYS_UPPER = 0.9
CASE_ALWAYS_LOWER = 0.3
# Control sets: the three populations the thresholds must separate. The
# generator fails instead of writing an asset that gets them wrong.
CASE_CONTROL_LOWER = (
    "der", "die", "das", "und", "ist", "nicht", "haben", "sein", "werden",
    "können", "auch", "schon", "immer", "gut", "machen",
)
CASE_CONTROL_UPPER = (
    "haus", "zeit", "mann", "frau", "jahr", "tag", "welt", "geld",
    "kind", "arbeit", "hand", "auto", "wasser",
)
# Words that are both a noun and something else, so both spellings are real and
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
    """Rewrite display spellings; the trie key and the total count are unchanged."""
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


# ---------------------------------------------------------------------------
# Ukrainian cleaning (uk only)
# ---------------------------------------------------------------------------
# FrequencyWords' Ukrainian comes from subtitles labelled Ukrainian, and many are
# Russian. The letter filter drops words with ы, э, ъ or ё (3 206 of the 50k list); a
# Russian word spelled with letters both languages share passes it. Measured on the
# full list against FrequencyWords' Russian and Tatoeba's 188 826 Ukrainian sentences:
# a word in both lists that Ukrainian text never uses is Russian or a transliterated
# name (`как`, `надо`, `понимаю`, `вечер`, `тайлер`), at every frequency band sampled.
# A word far commoner in Russian (ratio of relative frequencies 2 or more) that
# Ukrainian text uses only in passing is Russian too (`что` 5 sightings, `он` 113,
# against about 19 000 and 7 000 expected). Shared words in real Ukrainian use keep:
# `на`, `так`, `книга`, `задача`.
UK_RUSSIAN_RATIO = 2.0
UK_MIN_SHARE_OF_EXPECTED = 0.2
# FrequencyWords split every apostrophe word into its two halves (`п` 13 794, `ять`
# 972) and kept no apostrophe form. The words come back from Tatoeba, scaled to
# FrequencyWords counts by the median ratio of the words both carry; the halves that
# Ukrainian text only ever shows inside an apostrophe word are dropped.
UK_SCALE_MIN_SIGHTINGS = 5
# One-letter words. FrequencyWords keeps every split head and every Russian `и`, `с`.
UK_ONE_LETTER_WORDS = frozenset("авзійоуяжб")
# The list also carries mis-decoded subtitle text, high in the ranking (`кбй` 959,
# `пґп` 837, `еянбй` 867), which Ukrainian text never uses. Of the words Tatoeba never
# shows, one with no vowel, or with a letter pair Tatoeba's Ukrainian writes fewer than
# five times, is dropped. Five is the smallest floor that catches `еянбй` (its `бй` is
# seen 3 times); the words it costs are mostly foreign names (`хюррем`, `морґан`).
UK_VOWELS = frozenset("аеєиіїоуюя")
UK_MIN_PAIR_SIGHTINGS = 5

UK_CONTROL_KEEP = ("що", "це", "він", "на", "так", "я", "не", "все", "привіт", "дякую", "добре", "книга")
# The last three are not far commoner in Russian, so only Tatoeba's silence
# drops them.
UK_CONTROL_DROP = (
    "что", "как", "мне", "он", "нет", "только", "спасибо", "привет",
    "желание", "проверить", "обещаю",
)
UK_CONTROL_APOSTROPHE = ("п'ять", "м'ясо", "сім'я", "ім'я", "пам'ять", "комп'ютер")
UK_CONTROL_HALVES = ("п", "м", "ять", "ясо", "ютер", "комп")
UK_CONTROL_JUNK = ("кбй", "пґп", "еянбй", "мпх", "пєп")


def tatoeba_ukrainian(compressed: bytes, word_re: re.Pattern[str]) -> tuple[
    collections.Counter[str], set[str]
]:
    """Standalone word counts, and every half of an apostrophe word, over Tatoeba ukr."""
    tokens: collections.Counter[str] = collections.Counter()
    halves: set[str] = set()
    text = bz2.decompress(compressed).decode("utf-8", errors="replace")
    for line in text.splitlines():
        parts = line.split("\t", 2)
        if len(parts) != 3:
            continue
        for tok in parts[2].split():
            t = TOKEN_STRIP_RE.sub("", normalise("uk", tok.lower())).strip("'")
            if not t or not word_re.match(t):
                continue
            tokens[t] += 1
            if "'" in t:
                halves.update(t.split("'"))
    return tokens, halves


def parse_counts(raw: str, word_re: re.Pattern[str], lang: str) -> dict[str, int]:
    """A FrequencyWords list as a dict, uncapped: the cleaning runs before the cap."""
    out: dict[str, int] = {}
    for line in raw.splitlines():
        parts = line.strip().split()
        if len(parts) != 2 or not parts[1].isdigit():
            continue
        word = normalise(lang, parts[0].lower())
        if len(word) > MAX_WORD_LEN or not word_re.match(word):
            continue
        out[word] = out.get(word, 0) + int(parts[1])
    return out


def clean_ukrainian(
    ukrainian: dict[str, int],
    russian: dict[str, int],
    tokens: collections.Counter[str],
    halves: set[str],
) -> list[tuple[str, int]]:
    """The Ukrainian list with Russian, split halves and stray letters out, apostrophe words in."""
    uk_total = sum(ukrainian.values())
    ru_total = sum(russian.values())
    ratios = sorted(
        c / tokens[w]
        for w, c in ukrainian.items()
        if tokens[w] >= UK_SCALE_MIN_SIGHTINGS
    )
    scale = ratios[len(ratios) // 2]
    pairs: collections.Counter[str] = collections.Counter()
    for w, n in tokens.items():
        for a, b in zip(w, w[1:]):
            pairs[a + b] += n
    dropped = collections.Counter()
    kept: dict[str, int] = {}
    for w, c in ukrainian.items():
        if len(w) == 1 and w not in UK_ONE_LETTER_WORDS:
            dropped["one letter"] += 1
            continue
        if w in halves and tokens[w] == 0:
            dropped["apostrophe half"] += 1
            continue
        if tokens[w] == 0 and (
            not (set(w) & UK_VOWELS)
            or any(pairs[a + b] < UK_MIN_PAIR_SIGHTINGS for a, b in zip(w, w[1:]))
        ):
            dropped["mis-decoded"] += 1
            continue
        if w in russian:
            if tokens[w] == 0:
                dropped["russian, unused"] += 1
                continue
            ratio = (russian[w] / ru_total) / (c / uk_total)
            if ratio >= UK_RUSSIAN_RATIO and tokens[w] < UK_MIN_SHARE_OF_EXPECTED * c / scale:
                dropped["russian, rare"] += 1
                continue
        kept[w] = c
    # The full list does hold a few apostrophe words, typed with a backtick and seen once
    # (`ім`я` 1), so the larger of the two counts is kept.
    added = 0
    for w, n in tokens.items():
        scaled = max(1, round(n * scale))
        if "'" in w and scaled > kept.get(w, 0):
            kept[w] = scaled
            added += 1
    LOG.info(
        "ukrainian: %d of %d kept, scale %.2f, %s, %d apostrophe words added",
        len(kept), len(ukrainian), scale, dict(dropped), added,
    )
    rows = sorted(kept.items(), key=lambda r: -r[1])
    return rows[:MAX_WORDS]


def check_ukrainian_controls(rows: list[tuple[str, int]]) -> list[str]:
    """Control-set violations; empty when the cleaning did what it is for."""
    words = {w for w, _ in rows}
    bad = [f"{w} is Ukrainian but was dropped" for w in UK_CONTROL_KEEP if w not in words]
    bad += [f"{w} is Russian but was kept" for w in UK_CONTROL_DROP if w in words]
    bad += [f"{w} was not rebuilt" for w in UK_CONTROL_APOSTROPHE if w not in words]
    bad += [f"{w} is half a word but was kept" for w in UK_CONTROL_HALVES if w in words]
    bad += [f"{w} is mis-decoded text but was kept" for w in UK_CONTROL_JUNK if w in words]
    return bad


# ---------------------------------------------------------------------------
# Diacritics (it, pl, es, cs, fr, de, nl, no)
# ---------------------------------------------------------------------------
# Subtitle lists carry spellings with the accents left off, as words: Polish `sie`
# 87 050 beside `się`, Italian `perche` 489 341 against `perché` 417 508. Folded onto
# the accented word's trie node they are offered, and can lead. Subtitle counts cannot
# tell them from real pairs (`ze`/`że`, `e`/`è`), so written use decides: Tatoeba's
# sentences for the language, counted by exact spelling. Folding mirrors AccentFolder.
DIACRITIC_LANGS = ("it", "pl", "es", "cs", "fr", "de", "nl", "no")
_FOLD = str.maketrans({
    ch: base
    for chars, base in (
        ("àáâäãåąæ", "a"), ("èéêëęě", "e"), ("ìíîï", "i"), ("òóôöõø", "o"), ("ùúûüů", "u"),
        ("çćč", "c"), ("ď", "d"), ("ñńň", "n"), ("ýÿ", "y"), ("ł", "l"), ("ř", "r"),
        ("śš", "s"), ("ť", "t"), ("žźż", "z"),
    )
    for ch in chars
})


def fold_word(word: str) -> str:
    """The trie key: lowercase, accents folded, ß and œ as two letters."""
    return word.lower().replace("ß", "ss").replace("œ", "oe").translate(_FOLD)


# A spelling Tatoeba writes at this share of its key or below is a typo of a sibling.
DIACRITIC_TYPO_RATE = 0.005
# The smallest share a real word of the pair holds: Czech `rada` (advice) is 1.9% beside
# `ráda`, so 3% keeps it, and 3% is the least that drops Polish `pojsc` (0 of 161).
DIACRITIC_REAL_SHARE = 0.03
DIACRITIC_ALPHA = 0.01
# Too few sightings to test: a plain spelling written at most once in a key seen this
# often is dropped too, unless subtitles carry it at twice the language's usual rate
# of left-off accents. That guard keeps Spanish `acuso` beside `acusó` (14.8x) and
# French `avise` beside `avisé`, and costs `envie`, `confie`.
DIACRITIC_TAIL_MIN_SIGHTINGS = 20
DIACRITIC_TAIL_RATIO = 2.0
# An accented spelling Tatoeba never writes, under 1% of its sibling, is a corrupt
# subtitle form (`moźe`, `będzię`, `píù`).
DIACRITIC_CORRUPT_SHARE = 0.01

DIACRITIC_CONTROL_KEEP = {
    "it": ("e", "da", "si", "ne", "la", "papa", "meta", "faro", "fini", "tento"),
    "pl": ("ze", "ja", "te", "maja", "piec", "pokaz", "racje", "role", "operacje"),
    "es": ("esta", "el", "tu", "si", "aun", "solo", "donde", "paso", "acuso", "curo"),
    "fr": ("a", "ou", "la", "du", "sur", "passe", "arrive", "amuses", "avise"),
    "cs": ("je", "se", "na", "byt", "rada", "tvar", "lez"),
    "de": ("schon", "musste", "wurde", "konnte", "dass", "spulen", "muhen"),
    "nl": ("een", "één", "ze"),
    "no": ("for", "vare", "bar", "sa", "do", "bade", "rad"),
}
DIACRITIC_CONTROL_DROP = {
    "it": ("perche", "piu", "gia", "cosi", "puo", "cioe", "percio", "tornero"),
    "pl": ("sie", "moze", "juz", "byc", "pojsc", "jesli", "wiec", "czesc", "diabla", "sprawdzic"),
    "es": ("tambien", "aqui", "asi", "dia", "estan", "capitan", "podriamos"),
    "fr": ("ca", "etait", "etre", "tres", "ete", "meme", "cherie", "espere"),
    "cs": ("neni", "ja", "ted", "dekuji", "proste"),
    "de": ("fur", "uber", "mussen", "konnen", "ausserdem"),
    "nl": ("ideeen", "italie"),
    "no": ("pa", "ma", "na", "fa", "ogsa", "forste"),
}


def _poisson_le(k: int, lam: float) -> float:
    """P(X <= k) for a Poisson mean, summed in log space so a large mean cannot underflow."""
    if k < 0:
        return 0.0
    if lam <= 0:
        return 1.0
    logs = [i * math.log(lam) - lam - math.lgamma(i + 1) for i in range(k + 1)]
    top = max(logs)
    return min(1.0, math.exp(top) * sum(math.exp(x - top) for x in logs))


def _is_typo(written: int, seen: int) -> bool:
    """Written count fits the typo rate and does not fit a real word's share."""
    fits_typo = written == 0 or 1.0 - _poisson_le(written - 1, DIACRITIC_TYPO_RATE * seen) >= DIACRITIC_ALPHA
    return fits_typo and _poisson_le(written, DIACRITIC_REAL_SHARE * seen) < DIACRITIC_ALPHA


def _italian_tense_pair(plain: str, spellings: set[str]) -> bool:
    """`tento`/`tentò`: present and past of one verb. Subtitles strip Italian accents half
    the time, so the ratio guard cannot see these; a future (`tornero`) has r before the o."""
    return (
        len(plain) > 2
        and plain[-1] == "o"
        and plain[-2] not in "aeiour"
        and plain[:-1] + "ò" in spellings
    )


def tatoeba_tokens(compressed: bytes, lang: str) -> collections.Counter[str]:
    """Every token of the language's Tatoeba sentences, lowercased, by exact spelling."""
    tokens: collections.Counter[str] = collections.Counter()
    text = bz2.decompress(compressed).decode("utf-8", errors="replace")
    for line in text.splitlines():
        parts = line.split("\t", 2)
        if len(parts) != 3:
            continue
        for tok in parts[2].split():
            t = TOKEN_STRIP_RE.sub("", normalise(lang, tok.lower()))
            if t:
                tokens[t] += 1
    return tokens


def clean_diacritics(
    lang: str, rows: list[tuple[str, int]], tokens: collections.Counter[str]
) -> tuple[list[tuple[str, int]], dict[str, str]]:
    """Rows with left-off-accent and corrupt spellings removed, and what each was moved to.

    A removed spelling's count goes to its key's most-written spelling, in place, so every
    other row keeps its text and position.
    """
    groups: dict[str, dict[str, int]] = collections.defaultdict(dict)
    for w, c in rows:
        low = w.lower()
        groups[fold_word(w)][low] = groups[fold_word(w)].get(low, 0) + c
    plain_drops: dict[str, str] = {}
    other_drops: dict[str, str] = {}
    tail: list[tuple[str, str, float]] = []
    ratios: list[float] = []
    for key, spellings in groups.items():
        if len(spellings) < 2 or all(s == key for s in spellings):
            continue
        seen = sum(tokens[s] for s in spellings)
        best = max(spellings, key=lambda s: (tokens[s], spellings[s]))
        if tokens[best] == 0:
            continue
        for s, count in spellings.items():
            if s == best:
                continue
            if s == key:
                accented = sum(c for t, c in spellings.items() if t != key)
                if lang == "it" and _italian_tense_pair(s, set(spellings)):
                    continue
                if _is_typo(tokens[s], seen):
                    plain_drops[s] = best
                    ratios.append(count / accented)
                elif tokens[s] <= 1 and seen >= DIACRITIC_TAIL_MIN_SIGHTINGS:
                    tail.append((s, best, count / accented))
            elif tokens[s] == 0 and count < DIACRITIC_CORRUPT_SHARE * spellings[best]:
                other_drops[s] = best
    # The language's usual rate of left-off accents, from the spellings Tatoeba settled.
    usual = sorted(ratios)[len(ratios) // 2] if ratios else 0.0
    tail_drops = {s: b for s, b, r in tail if usual > 0 and r < DIACRITIC_TAIL_RATIO * usual}
    drops = {**plain_drops, **tail_drops, **other_drops}
    moved: dict[str, int] = collections.Counter()
    for w, c in rows:
        if w.lower() in drops:
            moved[drops[w.lower()]] += c
    out: list[tuple[str, int]] = []
    credited: set[str] = set()
    receivers: dict[str, str] = {}
    for w, c in rows:
        low = w.lower()
        if low in drops:
            continue
        # One display form per spelling takes the moved count: the most frequent.
        if low in moved and low not in receivers:
            receivers[low] = max((x for x in rows if x[0].lower() == low), key=lambda x: x[1])[0]
        if receivers.get(low) == w and low not in credited:
            credited.add(low)
            c += moved[low]
        out.append((w, c))
    LOG.info(
        "%s diacritics: %d left-off spellings, %d by the tail rule (usual rate %.3f), %d corrupt, %d rows out",
        lang, len(plain_drops), len(tail_drops), usual, len(other_drops), len(rows) - len(out),
    )
    return out, drops


def check_diacritic_controls(lang: str, rows: list[tuple[str, int]]) -> list[str]:
    """Control-set violations; empty when the cleaning kept the real pairs and dropped the rest."""
    words = {w.lower() for w, _ in rows}
    bad = [f"{w} is a word but was dropped" for w in DIACRITIC_CONTROL_KEEP.get(lang, ()) if w not in words]
    bad += [f"{w} is a left-off accent but was kept" for w in DIACRITIC_CONTROL_DROP.get(lang, ()) if w in words]
    return bad


def clean_existing(lang: str, out_dir: Path, blob: bytes, dry_run: bool) -> int:
    """Offline over the committed <lang>_wordlist.txt: the diacritics pass, rewritten in place."""
    path = out_dir / f"{lang}_wordlist.txt"
    rows = [
        (w, int(c))
        for w, c in (line.split("\t") for line in path.read_text(encoding="utf-8").splitlines())
    ]
    cleaned, drops = clean_diacritics(lang, rows, tatoeba_tokens(blob, lang))
    violations = check_diacritic_controls(lang, cleaned)
    if violations:
        for v in violations:
            LOG.error("diacritic control: %s", v)
        return 1
    if dry_run:
        for s, b in sorted(drops.items()):
            LOG.info("  %s -> %s", s, b)
        LOG.info("dry run: %s %d -> %d rows", path, len(rows), len(cleaned))
        return 0
    path.write_text("".join(f"{w}\t{c}\n" for w, c in cleaned), encoding="utf-8")
    LOG.info("cleaned %s: %d -> %d rows", path, len(rows), len(cleaned))
    return 0


def tatoeba_bigrams(
    compressed: bytes, vocab: set[str], word_re: re.Pattern[str], lang: str = "en"
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
                TOKEN_STRIP_RE.sub("", normalise(lang, tok.lower()))
                for tok in parts[2].split()
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

    The min covers three cases. Where the stem is not an English word the
    proxy overshoots and the min returns the stem, the exact answer ("don't"
    4158644, "needn't" 5234). Where the stem is a real word it caps the
    estimate ("won't" 571621). Where the misspelling is absent the stem is all
    there is.

    Accepted: "can't" lands ~3x high (the 't residual says ~1.1M) and
    "it's"/"let's" take their whole stem, so the 's family over-allocates
    against the 14291013 available. All three are within 0.05 fw, inside twice
    the proxy's validated error.
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
    in the MIT FrequencyWords list, so no new corpus or licence is involved. An
    apostrophe form already present is left untouched unless [refresh], which
    recomputes it in place so a re-run corrects a stale value.
    """
    mapping = CONTRACTIONS.get(lang)
    if not mapping:
        return rows
    freq = {w: c for w, c in rows}
    # Estimate every form against the original counts, so a refreshed form can
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

    With [refresh] this is also the item-18 repair path. It runs offline
    because every input the estimator needs (stems, misspellings) is in the
    committed asset, so the diff is only the contraction rows and measurements
    taken against the asset stay valid.
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


def ukrainian_unigrams(
    args: argparse.Namespace, word_re: re.Pattern[str]
) -> tuple[list[tuple[str, int]], bytes]:
    """The cleaned Ukrainian list, and the Tatoeba blob it was cleaned against."""
    raw = (
        args.wordlist_file.read_text(encoding="utf-8")
        if args.wordlist_file
        else fetch(WORDLIST_FULL_URL_TMPL.format(lang="uk"))
    )
    raw_ru = (
        args.russian_file.read_text(encoding="utf-8")
        if args.russian_file
        else fetch(WORDLIST_FULL_URL_TMPL.format(lang="ru"))
    )
    blob = (
        args.bigrams_file.read_bytes()
        if args.bigrams_file
        else fetch(TATOEBA_SENTENCES_URL_TMPL.format(code="ukr"), binary=True)
    )
    tokens, halves = tatoeba_ukrainian(blob, word_re)
    rows = clean_ukrainian(
        parse_counts(raw, word_re, "uk"),
        parse_counts(raw_ru, WORD_RE["ru"], "ru"),
        tokens,
        halves,
    )
    return rows, blob


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--lang",
        choices=("en", "it", "es", "pl", "cs", "nl", "de", "fr", "no", "ru", "he", "ar", "uk"),
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
        "--russian-file",
        type=Path,
        help="uk only: local copy of the FrequencyWords ru_full.txt the Russian "
        "cleaning compares against (skips download)",
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
        "--clean-diacritics",
        action="store_true",
        help="offline over the committed <lang>_wordlist.txt: drop spellings with "
        "accents left off, judged by Tatoeba's written use (--bigrams-file or a download)",
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

    if args.clean_diacritics:
        if lang not in DIACRITIC_LANGS:
            LOG.error("%s has no diacritics pass", lang)
            return 1
        blob = (
            args.bigrams_file.read_bytes()
            if args.bigrams_file
            else fetch(TATOEBA_SENTENCES_URL_TMPL.format(code=TATOEBA_LANG_CODE[lang]), binary=True)
        )
        return clean_existing(lang, args.out_dir, blob, args.dry_run)

    blob: bytes | None = None
    try:
        if lang == "uk":
            unigrams, blob = ukrainian_unigrams(args, word_re)
            violations = check_ukrainian_controls(unigrams)
            if violations:
                for v in violations:
                    LOG.error("ukrainian control: %s", v)
                return 1
        else:
            raw_unigrams = (
                args.wordlist_file.read_text(encoding="utf-8")
                if args.wordlist_file
                else fetch(WORDLIST_URL_TMPL.format(lang=lang))
            )
            unigrams = parse_unigrams(raw_unigrams, word_re, lang)
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
        primary_lower = {w.lower() for w in primary}
        primary_keys = {fold_word(w) for w in primary}
        max_count = max(c for _, c in unigrams)
        # AOSP's Italian list holds `perche` and `cosi`: a plain spelling of a key the primary
        # holds only with accents is the pseudo-word clean_diacritics removes.
        merged = [
            (w, c)
            for w, c in parse_aosp_combined(args.merge_aosp, max_count)
            if w not in primary
            and len(w) <= MAX_WORD_LEN
            and word_re.match(w)
            and not (fold_word(w) == w and w not in primary_lower and w in primary_keys)
        ]
        LOG.info("aosp merge: %d new words from %s", len(merged), args.merge_aosp)
        unigrams = sorted(unigrams + merged, key=lambda r: -r[1])

    unigrams = augment_contractions(unigrams, lang, refresh=args.refresh_contractions)

    vocab = {w for w, _ in unigrams}
    try:
        code = TATOEBA_LANG_CODE[lang]
        if blob is None:
            blob = (
                args.bigrams_file.read_bytes()
                if args.bigrams_file
                else fetch(TATOEBA_SENTENCES_URL_TMPL.format(code=code), binary=True)
            )
        bigrams = tatoeba_bigrams(blob, vocab, word_re, lang)
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
        if lang in DIACRITIC_LANGS:
            unigrams, _ = clean_diacritics(lang, unigrams, tatoeba_tokens(blob, lang))
            violations = check_diacritic_controls(lang, unigrams)
            if violations:
                for v in violations:
                    LOG.error("diacritic control: %s", v)
                return 1
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
