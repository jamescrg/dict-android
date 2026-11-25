package link.darmok.dict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** One sub-entry of a Ding line. */
class Hit(val lineId: Int, val sub: Int, val de: String, val en: String)

/** "[lemma] is the dictionary form of what was typed"; [kind] indexes [Dict.KINDS]. */
class FormHint(val lemma: String, val kind: Int)

class Results(val hints: List<FormHint>, val hits: List<Hit>)

/**
 * The dictionary database built by tools/build_db.py, which documents the tables.
 * Not thread-safe: use it from one thread.
 */
class Dict private constructor(private val db: SQLiteDatabase) {

    private class Posting(val score: Int, val ref: Int)

    private class Candidate(val score: Int, val viaLemma: Boolean, val ref: Int)

    fun meta(key: String): String =
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else ""
        }

    /** Every sub-entry of a line, as (German, English) pairs. */
    fun line(lineId: Int): List<Pair<String, String>> {
        val (de, en) = loadLines(listOf(lineId))[lineId] ?: return emptyList()
        return List(maxOf(de.size, en.size)) { de.getOrElse(it) { "" } to en.getOrElse(it) { "" } }
    }

    fun search(raw: String): Results {
        val tokens = Text.tokens(raw)
        if (tokens.isEmpty()) return Results(emptyList(), emptyList())
        val found = lookUp(raw, tokens)
        if (found.hits.isNotEmpty() || found.hints.isNotEmpty()) return found
        // Typed without umlauts: "ueber" for "über".
        val respelled = tokens.map { it.replace("ae", "a").replace("oe", "o").replace("ue", "u") }
        return if (respelled == tokens) found else lookUp(respelled.joinToString(" "), respelled)
    }

    private fun lookUp(raw: String, tokens: List<String>): Results {
        val content = tokens.filter { it !in FILLER }.ifEmpty { tokens }
        return if (content.size == 1) {
            word(content[0], if (tokens.size == 1) Text.low(raw.trim()) else content[0])
        } else {
            phrase(content, lastIsPrefix = !raw.endsWith(" "))
        }
    }

    /** [spelled] is the word as typed, lowercased but with its umlauts. */
    private fun word(token: String, spelled: String): Results {
        val hints = formHints(token, spelled)
        val candidates = LinkedHashMap<Int, Candidate>()
        for (p in postings(token, 60)) candidates[p.ref] = Candidate(p.score, false, p.ref)
        for (hint in hints) {
            val lemma = Text.tokens(hint.lemma).firstOrNull() ?: continue
            for (p in postings(lemma, 25)) {
                candidates.getOrPut(p.ref) { Candidate(p.score, true, p.ref) }
            }
        }
        val lines = loadLines(candidates.values.map { it.ref shr 8 })
        // Best first; among equals, what was typed before its lemma, and "schön" before "schon"
        // when "schön" was typed.
        val ranked = candidates.values.mapNotNull { c ->
            val hit = hit(c.ref, lines) ?: return@mapNotNull null
            val exact = Text.low(hit.de).contains(spelled) || Text.low(hit.en).contains(spelled)
            Triple(c, exact, hit)
        }.sortedWith(
            compareBy<Triple<Candidate, Boolean, Hit>> { it.first.score }
                .thenBy { it.first.viaLemma }
                .thenBy { !it.second }
                .thenBy { it.first.ref }
        ).map { it.third }.toMutableList()

        if (ranked.size < 40 && token.length >= 2) {
            // Words that start with what was typed, in alphabetical order.
            val more = completions(token, 40).filter { it.ref !in candidates }
            val moreLines = loadLines(more.map { it.ref shr 8 })
            more.mapNotNullTo(ranked) { hit(it.ref, moreLines) }
        }
        return Results(hints, ranked)
    }

    private fun phrase(tokens: List<String>, lastIsPrefix: Boolean): Results {
        val hints = ArrayList<FormHint>()
        val first = ArrayList<Int>()
        if (tokens.size == 2) {
            // A separable verb written apart: "fing an" is "anfangen".
            for (hint in formHints(tokens[0], tokens[0])) {
                val joined = tokens[1] + Text.fold(hint.lemma)
                val found = postings(joined, 25).filter { it.score < HEADWORD_SCORE }
                if (found.isEmpty()) continue
                hints += FormHint(tokens[1] + hint.lemma, hint.kind)
                found.mapTo(first) { it.ref }
            }
        }

        // Sub-entries that contain every word, ordered by their summed scores.
        var totals: Map<Int, Int>? = null
        for ((i, token) in tokens.withIndex()) {
            val prefix = lastIsPrefix && i == tokens.lastIndex && token.length >= 3
            val scores = scoresByRef(token, prefix)
            val soFar = totals
            totals = if (soFar == null) scores else {
                val both = HashMap<Int, Int>()
                for ((ref, score) in soFar) scores[ref]?.let { both[ref] = score + it }
                both
            }
            if (totals.isEmpty()) break
        }
        val all = totals.orEmpty().entries
            .sortedWith(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .take(100).map { it.key }
        val refs = (first + all).distinct()
        val lines = loadLines(refs.map { it shr 8 })
        return Results(hints, refs.mapNotNull { hit(it, lines) })
    }

    private fun formHints(token: String, spelled: String): List<FormHint> {
        class Row(val spelling: String, val lemma: String, val kind: Int)

        val rows = ArrayList<Row>()
        db.rawQuery("SELECT form, lemma, kind FROM forms WHERE fold = ?", arrayOf(token)).use {
            while (it.moveToNext()) {
                rows += Row(it.getString(0).ifEmpty { token }, it.getString(1), it.getInt(2))
            }
        }
        // "hätte" and "hatte" fold to the same key; keep to the one typed if it is there.
        val exact = rows.filter { it.spelling == spelled }
        return exact.ifEmpty { rows }
            .sortedBy { it.kind }
            .distinctBy { Text.fold(it.lemma) }
            .take(4)
            .map { FormHint(it.lemma, it.kind) }
    }

    private fun postings(token: String, limit: Int): List<Posting> {
        val out = ArrayList<Posting>()
        db.rawQuery(
            "SELECT score, ref FROM idx WHERE token = ? ORDER BY score, ref LIMIT $limit",
            arrayOf(token)
        ).use { while (it.moveToNext()) out += Posting(it.getInt(0), it.getInt(1)) }
        return out
    }

    private fun completions(prefix: String, limit: Int): List<Posting> {
        val out = ArrayList<Posting>()
        db.rawQuery(
            "SELECT score, ref FROM idx WHERE token > ? AND token < ? AND score < $HEADWORD_SCORE " +
                "ORDER BY token, score, ref LIMIT $limit",
            arrayOf(prefix, prefix + END)
        ).use { while (it.moveToNext()) out += Posting(it.getInt(0), it.getInt(1)) }
        return out
    }

    /** The best score of [token] (or of words starting with it) in each sub-entry. */
    private fun scoresByRef(token: String, prefix: Boolean): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        val cursor = if (prefix) {
            db.rawQuery(
                "SELECT score, ref FROM idx WHERE token >= ? AND token < ?",
                arrayOf(token, token + END)
            )
        } else {
            db.rawQuery("SELECT score, ref FROM idx WHERE token = ?", arrayOf(token))
        }
        cursor.use {
            while (it.moveToNext()) {
                val score = it.getInt(0)
                val ref = it.getInt(1)
                val old = out[ref]
                if (old == null || score < old) out[ref] = score
            }
        }
        return out
    }

    private fun loadLines(ids: Collection<Int>): Map<Int, Pair<List<String>, List<String>>> {
        val out = HashMap<Int, Pair<List<String>, List<String>>>()
        for (chunk in ids.distinct().chunked(200)) {
            db.rawQuery(
                "SELECT id, de, en FROM lines WHERE id IN (${chunk.joinToString(",")})", null
            ).use {
                while (it.moveToNext()) {
                    out[it.getInt(0)] = it.getString(1).split(" | ") to it.getString(2).split(" | ")
                }
            }
        }
        return out
    }

    private fun hit(ref: Int, lines: Map<Int, Pair<List<String>, List<String>>>): Hit? {
        val (de, en) = lines[ref shr 8] ?: return null
        val sub = ref and 255
        return Hit(ref shr 8, sub, de.getOrElse(sub) { "" }, en.getOrElse(sub) { "" })
    }

    companion object {
        /** Matches KINDS in tools/build_db.py. */
        val KINDS = listOf(
            "form", "past participle", "past tense", "present tense", "plural", "comparative",
            "superlative", "subjunctive II", "subjunctive I", "imperative", "present participle",
            "zu-infinitive", "genitive", "dative", "accusative", "inflected form",
        )

        /** Matches HEADWORD_SCORE in tools/build_db.py. */
        private const val HEADWORD_SCORE = 8

        /** Placeholder words the index leaves out of phrases ("to go", "sich erinnern"). */
        private val FILLER = setOf(
            "sich", "etw", "jdn", "jdm", "jds", "jd", "jmd", "jmdn", "jmdm", "jmds",
            "to", "sth", "sb",
        )

        /** Sorts after every token that starts with the prefix it is appended to. */
        private const val END = "￿"

        /**
         * Opens the database, first copying it out of the APK if this install hasn't yet.
         * Slow the first time (the file is about 80 MB): call it off the main thread.
         */
        fun open(context: Context): Dict {
            val updated = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            val dir = context.noBackupFilesDir
            val file = File(dir, "dict-$updated.db")
            if (!file.exists()) {
                dir.listFiles()?.forEach { if (it.name.startsWith("dict-")) it.delete() }
                val partial = File(dir, "dict-partial")
                context.assets.open("dict.db").use { input ->
                    partial.outputStream().use { input.copyTo(it, 1 shl 16) }
                }
                if (!partial.renameTo(file)) throw java.io.IOException("Couldn't move $partial")
            }
            return Dict(SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY))
        }
    }
}
