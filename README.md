# Wörterbuch

A small offline German–English dictionary for Android. Type in either language; inflected German
forms ("ging", "Häusern", "fing an") lead to their dictionary form.

- Package: `link.darmok.dict`
- Android 8.0 (API 26) and up; built against API 35
- Kotlin and the Android framework only, no libraries
- Gruvbox colours, light or dark to match the system setting
- Selecting text in another app offers "Wörterbuch" in the selection menu

## Data

The app ships one SQLite file, `app/src/main/assets/dict.db` (about 83 MB), built from:

- **Ding** German–English dictionary, © Frank Richter, GPL version 2 or later.
  <https://ftp.tu-chemnitz.de/pub/Local/urz/ding/de-en-devel/de-en.txt.gz>
- **Wiktionary** inflection tables by way of Kaikki.org, CC BY-SA 4.0.
  <https://kaikki.org/dictionary/German/kaikki.org-dictionary-German.jsonl.gz>

Neither the downloads nor the database are in git. To build the database, download each file into
its own folder and run the script:

```sh
mkdir -p data/ding data/kaikki
curl -L -o data/ding/de-en.txt.gz https://ftp.tu-chemnitz.de/pub/Local/urz/ding/de-en-devel/de-en.txt.gz
curl -L -o data/kaikki/German.jsonl.gz https://kaikki.org/dictionary/German/kaikki.org-dictionary-German.jsonl.gz
python3 -I tools/build_db.py data/ding/de-en.txt.gz data/kaikki/German.jsonl.gz app/src/main/assets/dict.db
```

It takes about 40 seconds. The table layout is described at the top of `tools/build_db.py`.
`Text.kt` and the script normalise words the same way (lowercase, ß as ss, umlauts removed); change
them together.

Because the APK contains GPL and CC BY-SA data, anyone you give it to must also get those terms
and the attribution (the app's ⓘ screen shows it).

## Build and install

You need JDK 17 and the Android SDK (platform 35), with `sdk.dir` set in `local.properties` or
`ANDROID_HOME` in the environment.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch after each install the app copies the database out of the APK, which takes a second
or two.

## How lookup works

`Dict.kt` does all of it:

1. One word: entries that contain the word, best first (the word alone as a headword, then short
   phrases, then example sentences).
2. If the word is an inflected form, a line at the top names its dictionary form and that form's
   entries are merged in.
3. Then words that start with what was typed, alphabetically.
4. Several words: sub-entries that contain all of them. Two words that make a separable verb
   ("fing an") also find the joined verb ("anfangen").
5. If nothing matches, "ae", "oe" and "ue" are read as umlauts.

## Licence

The app's code is licensed under the GNU General Public License, version 3 (see `LICENSE`).
The dictionary data keeps its own licences, listed under Data above.
