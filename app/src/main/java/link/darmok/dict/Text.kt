package link.darmok.dict

import java.text.Normalizer
import java.util.Locale

/** Text normalisation. Must stay in step with low(), fold() and tokens() in tools/build_db.py. */
object Text {
    private val marks = Regex("\\p{Mn}+")
    private val word = Regex("[\\p{L}\\p{N}]+")

    /** Lowercase, with ß written as ss. */
    fun low(s: String): String = s.lowercase(Locale.ROOT).replace("ß", "ss")

    /** [low], with accents and umlauts removed: "Häuser" becomes "hauser". */
    fun fold(s: String): String =
        marks.replace(Normalizer.normalize(low(s), Normalizer.Form.NFD), "")

    fun tokens(s: String): List<String> = word.findAll(fold(s)).map { it.value }.toList()
}
