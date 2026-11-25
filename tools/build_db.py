#!/usr/bin/env python3
"""Build app/src/main/assets/dict.db from the Ding dictionary and the Kaikki Wiktionary extract.

Usage: python3 -I tools/build_db.py data/ding/de-en.txt.gz data/kaikki/German.jsonl.gz OUT.db

Tables
  lines(id, de, en)        One Ding line. Both sides hold sub-entries separated by " | ".
  idx(token, score, ref)   Search index. ref = line id * 256 + sub-entry number.
                           Lower score = closer to being the headword itself (see index_side).
                           Lines are numbered longest first, so among equal scores the entries
                           with the most sub-entries (the main senses) come first.
  forms(fold, form, lemma, kind)
                           Inflected form -> dictionary form. `fold` is the folded form (see fold()),
                           `form` is the lowercased spelling when it differs from `fold`, else ''.
                           `kind` indexes KINDS below (and Kinds in Dict.kt).
  meta(key, value)

fold() and tokens() must stay in step with Text.kt.
"""
import gzip
import json
import os
import re
import sqlite3
import sys
import unicodedata

KINDS = [
    "form",  # 0, unused
    "past participle", "past tense", "present tense", "plural", "comparative", "superlative",
    "subjunctive II", "subjunctive I", "imperative", "present participle", "zu-infinitive",
    "genitive", "dative", "accusative", "inflected form",
]

# Placeholder words that say nothing about what an entry is about.
FILLER = {
    "de": {"sich", "etw", "jdn", "jdm", "jds", "jd", "jmd", "jmdn", "jmdm", "jmds"},
    "en": {"to", "sth", "sb"},
}
MAX_SUB = 255
HEADWORD_SCORE = 8  # scores below this: the token is one of at most two words in its phrase

ANNOTATION = re.compile(r"\{[^}]*\}|\[[^\]]*\]|\([^)]*\)")
WORD = re.compile(r"[^\W_]+")


def low(s):
    return s.lower().replace("ß", "ss")


def fold(s):
    s = unicodedata.normalize("NFD", low(s))
    return "".join(c for c in s if unicodedata.category(c) != "Mn")


def tokens(s):
    return WORD.findall(fold(s))


def index_side(text, lang, sub, out):
    """Record the best (lowest) score for every token on one side of a sub-entry."""
    filler = FILLER[lang]
    for n, synonym in enumerate(ANNOTATION.sub(" ", text).split(";")):
        toks = tokens(synonym)
        content = [t for t in toks if t not in filler] or toks
        if not content:
            continue
        # 0..27: words in the phrase, then sub-entry vs. main entry, then first synonym vs. later.
        score = (min(len(content), 7) - 1) * 4 + (2 if sub else 0) + (1 if n else 0)
        for t in content:
            if len(t) <= 40 and out.get(t, 99) > score:
                out[t] = score


def build_lines(db, ding_path):
    version = ""
    entries = []
    with gzip.open(ding_path, "rt", encoding="utf-8") as f:
        for raw in f:
            raw = raw.rstrip("\n")
            if raw.startswith("#"):
                if raw.startswith("# Version:"):
                    version = raw.split(":", 1)[1].strip()
                continue
            if " :: " not in raw:
                continue
            de, en = raw.split(" :: ", 1)
            entries.append(([x.strip() for x in de.split("|")], [x.strip() for x in en.split("|")]))
    entries.sort(key=lambda e: -max(len(e[0]), len(e[1])))  # stable: ties keep file order

    for line_id, (de_subs, en_subs) in enumerate(entries, 1):
        db.execute("INSERT INTO lines VALUES (?,?,?)",
                   (line_id, " | ".join(de_subs), " | ".join(en_subs)))
        for sub in range(min(max(len(de_subs), len(en_subs)), MAX_SUB + 1)):
            best = {}
            if sub < len(de_subs):
                index_side(de_subs[sub], "de", sub, best)
            if sub < len(en_subs):
                index_side(en_subs[sub], "en", sub, best)
            ref = line_id * 256 + sub
            db.executemany("INSERT INTO idx VALUES (?,?,?)",
                           [(t, sc, ref) for t, sc in best.items()])
    return version, len(entries)


SKIP_TAGS = {
    "auxiliary", "table-tags", "inflection-template", "class", "diminutive", "alternative",
    "obsolete", "archaic", "abbreviation", "canonical", "error-unknown-tag", "romanization",
    "multiword-construction", "misspelling", "infinitive-zu-missing",
}
LEADING = {"am", "der", "die", "das", "des", "dem", "den", "ein", "eine", "eines", "einem",
           "einen", "einer", "kein", "keine", "keines", "keinem", "keinen", "keiner"}
GENDERS = {"feminine", "masculine", "neuter"}


def kind_of(tags, pos):
    t = set(tags)
    if "participle" in t and "past" in t:
        return 1
    if "participle" in t and "present" in t:
        return 10
    if "infinitive-zu" in t:
        return 11
    if "infinitive" in t:
        return 0
    if "subjunctive-ii" in t:
        return 7
    if "subjunctive-i" in t or "subjunctive" in t:
        return 8
    if "imperative" in t:
        return 9
    if "preterite" in t or "past" in t:
        return 2
    if "present" in t:
        return 3
    if "comparative" in t:
        return 5
    if "superlative" in t:
        return 6
    if pos == "noun":
        if "plural" in t:
            return 4
        if "genitive" in t:
            return 12
        if "dative" in t:
            return 13
        if "accusative" in t:
            return 14
    return 15


INFLECTING = {"verb", "noun", "adj"}


def read_forms(kaikki_path):
    """(lowercased form, lemma) -> best kind, from inflection tables and form-of entries."""
    pairs = {}

    def add(form, lemma, tags, pos):
        t = set(tags)
        if t & SKIP_TAGS:
            return
        if pos == "noun" and t and t <= GENDERS:
            return  # the feminine/masculine counterpart, a different word
        words = form.split()
        if len(words) > 1:
            if "includes-article" in t or words[0].lower() in LEADING:
                words = words[-1:]
            else:
                return
        if not words or " " in lemma or not WORD.fullmatch(words[0].replace("-", "")):
            return
        form = low(words[0])
        if fold(form) == fold(lemma):
            return
        kind = kind_of(tags, pos)
        if kind and pairs.get((form, lemma), 99) > kind:
            pairs[(form, lemma)] = kind

    with gzip.open(kaikki_path, "rt", encoding="utf-8") as f:
        for line in f:
            e = json.loads(line)
            word, pos = e.get("word", ""), e.get("pos", "")
            if pos not in INFLECTING:
                continue
            for fm in e.get("forms", ()):
                add(fm.get("form", ""), word, fm.get("tags", ()), pos)
            for sense in e.get("senses", ()):
                for target in sense.get("form_of", ()):
                    add(word, target.get("word", ""), sense.get("tags", ()), pos)
    return pairs


def build_forms(db, kaikki_path):
    pairs = read_forms(kaikki_path)
    headwords = {r[0] for r in db.execute("SELECT DISTINCT token FROM idx WHERE score < ?", (HEADWORD_SCORE,))}
    by_form = {}
    for (form, lemma), kind in pairs.items():
        by_form.setdefault(form, []).append((lemma, kind))

    rows = {}
    for (form, lemma), kind in pairs.items():
        targets = [(lemma, kind)]
        if fold(lemma) not in headwords:
            # e.g. gegangene -> gegangen -> gehen: follow one more step.
            targets = [(l2, k2) for l2, k2 in by_form.get(low(lemma), ())
                       if fold(l2) in headwords and fold(l2) != fold(form)]
        for target, k in targets:
            key = (fold(form), "" if form == fold(form) else form, target)
            if rows.get(key, 99) > k:
                rows[key] = k
    db.executemany("INSERT INTO forms VALUES (?,?,?,?)",
                   [(a, b, c, k) for (a, b, c), k in rows.items()])
    return len(rows)


def main():
    ding_path, kaikki_path, out_path = sys.argv[1:4]
    if os.path.exists(out_path):
        os.remove(out_path)
    db = sqlite3.connect(out_path)
    db.executescript("""
        PRAGMA page_size = 4096;
        PRAGMA journal_mode = OFF;
        PRAGMA synchronous = OFF;
        CREATE TABLE lines(id INTEGER PRIMARY KEY, de TEXT NOT NULL, en TEXT NOT NULL);
        CREATE TABLE idx(token TEXT NOT NULL, score INTEGER NOT NULL, ref INTEGER NOT NULL,
                         PRIMARY KEY(token, score, ref)) WITHOUT ROWID;
        CREATE TABLE forms(fold TEXT NOT NULL, form TEXT NOT NULL, lemma TEXT NOT NULL,
                           kind INTEGER NOT NULL, PRIMARY KEY(fold, form, lemma)) WITHOUT ROWID;
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
    """)
    version, n_lines = build_lines(db, ding_path)
    n_forms = build_forms(db, kaikki_path)
    db.executemany("INSERT INTO meta VALUES (?,?)", [
        ("ding_version", version), ("lines", str(n_lines)), ("forms", str(n_forms)),
    ])
    db.commit()
    db.execute("PRAGMA journal_mode = DELETE")
    db.execute("VACUUM")
    db.close()
    print(f"{out_path}: {n_lines} lines, {n_forms} forms, Ding {version}, "
          f"{os.path.getsize(out_path) / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
