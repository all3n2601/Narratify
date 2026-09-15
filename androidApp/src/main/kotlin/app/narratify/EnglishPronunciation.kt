package app.narratify

/** Neighbouring words, used to choose between pronunciations of one spelling. */
internal data class WordContext(
    val previous: String? = null,
    val next: String? = null,
    /** True when a comma, dash, or full stop follows immediately: the word carries a phrase end. */
    val followedByPunctuation: Boolean = false,
)

/** English pronunciation over a read-only dictionary: inflections, heteronyms, then fallbacks. */
internal class EnglishArpabetEncoder(private val dictionary: CmuDictionaryEncoder) : ArpabetEncoder {
    override fun encode(word: String, context: WordContext): List<String> {
        val key = word.lowercase().replace('’', '\'').trim('\'', '-', '_')
        if (key.isEmpty()) return emptyList()
        val variants = dictionary.variants(key)
        if (variants.isNotEmpty()) {
            Heteronyms.reduced(key, variants, context)?.let { return it }
            return Heteronyms.choose(key, variants, context)
        }
        inflected(key)?.let { return it }
        val parts = key.split('-', '_').filter(String::isNotBlank)
        if (parts.size > 1) return parts.flatMap { encode(it, WordContext()) }
        if (isInitialism(word)) return CmuDictionaryEncoder.spell(key)
        return LetterToSound.phones(key).ifEmpty { CmuDictionaryEncoder.spell(key) }
    }

    /** "FBI" is read letter by letter; "Narratify" is not. */
    private fun isInitialism(word: String): Boolean =
        word.length in 2..5 && word.all { it.isUpperCase() || !it.isLetter() }

    /** "cats" is "cat" plus an ending; the dictionary does not need to list every form. */
    private fun inflected(word: String): List<String>? {
        if (word.endsWith("'s") && word.length > 3) {
            base(word.dropLast(2))?.let { return it + voicedEnding(it, voiced = "Z", unvoiced = "S") }
        }
        if (word.endsWith("es") && word.length > 3) {
            base(word.dropLast(2))?.let { return it + sibilantPlural(it) }
        }
        if (word.endsWith("s") && word.length > 2) {
            base(word.dropLast(1))?.let { return it + voicedEnding(it, voiced = "Z", unvoiced = "S") }
        }
        if (word.endsWith("ed") && word.length > 3) {
            base(word.dropLast(2))?.let { return it + pastEnding(it) }
        }
        if (word.endsWith("ing") && word.length > 4) {
            base(word.dropLast(3))?.let { return it + listOf("IH0", "NG") }
        }
        if (word.endsWith("ly") && word.length > 3) {
            base(word.dropLast(2))?.let { return it + listOf("L", "IY0") }
        }
        return null
    }

    /**
     * Tries the stem as written, then the two spellings English hides behind a suffix: a dropped
     * silent "e" ("gliding" from "glide") and a doubled final consonant ("stopped" from "stop").
     */
    private fun base(stem: String): List<String>? {
        dictionary.variants(stem).firstOrNull()?.let { return it }
        dictionary.variants(stem + "e").firstOrNull()?.let { return it }
        if (stem.length > 2 && stem.last() == stem[stem.length - 2] && stem.last() !in "aeiou") {
            dictionary.variants(stem.dropLast(1)).firstOrNull()?.let { return it }
        }
        if (stem.endsWith("i")) dictionary.variants(stem.dropLast(1) + "y").firstOrNull()?.let { return it }
        return null
    }

    private fun pastEnding(base: List<String>): List<String> {
        val last = base.last().trimEnd('0', '1', '2')
        return if (last in setOf("T", "D")) listOf("IH0", "D") else listOf(if (last in UNVOICED) "T" else "D")
    }

    private fun sibilantPlural(base: List<String>): List<String> =
        if (base.last().trimEnd('0', '1', '2') in SIBILANTS) listOf("IH0", "Z") else voicedEnding(base, "Z", "S")

    private fun voicedEnding(base: List<String>, voiced: String, unvoiced: String): List<String> {
        val last = base.last().trimEnd('0', '1', '2')
        if (last in SIBILANTS) return listOf("IH0", voiced)
        return listOf(if (last in UNVOICED) unvoiced else voiced)
    }

    private companion object {
        val SIBILANTS = setOf("S", "Z", "SH", "ZH", "CH", "JH")
        val UNVOICED = setOf("P", "T", "K", "F", "TH", "S", "SH", "CH", "HH")
    }
}

/**
 * Deterministic English letter-to-sound rules for words no dictionary entry and no inflection can
 * explain — mostly names. Wrong stress on an invented word is a smaller defect than spelling the
 * word out, which is what this replaces. Not a substitute for the licensed frontend that Phase A2
 * of the neural plan still has to choose.
 */
internal object LetterToSound {
    fun phones(word: String): List<String> {
        val letters = word.filter { it.isLetter() }
        if (letters.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var index = 0
        while (index < letters.length) {
            val consumed = emit(letters, index, out)
            index += consumed
        }
        return stressed(out)
    }

    /** Appends the phones for the grapheme at [index] and returns how many letters it covered. */
    private fun emit(word: String, index: Int, out: MutableList<String>): Int {
        val rest = word.length - index
        val char = word[index]
        if (rest > 1 && char == word[index + 1] && char !in VOWELS_SPELLED) {
            consonant(word, index)?.let { out += it }
            return 2
        }
        if (rest >= 3) {
            when (word.substring(index, index + 3)) {
                "tch" -> { out += "CH"; return 3 }
                "igh" -> { out += "AY"; return 3 }
                "sch" -> { out += listOf("S", "K"); return 3 }
            }
        }
        if (rest >= 2) {
            val pair = word.substring(index, index + 2)
            val atStart = index == 0
            val atEnd = rest == 2
            when {
                pair == "sh" -> { out += "SH"; return 2 }
                pair == "ch" -> { out += "CH"; return 2 }
                pair == "th" -> { out += "TH"; return 2 }
                pair == "ph" -> { out += "F"; return 2 }
                pair == "wh" -> { out += "W"; return 2 }
                pair == "ck" -> { out += "K"; return 2 }
                pair == "ng" -> { out += "NG"; return 2 }
                pair == "qu" -> { out += listOf("K", "W"); return 2 }
                pair == "dg" -> { out += "JH"; return 2 }
                pair == "gh" -> return 2
                atStart && pair in setOf("kn", "gn") -> { out += "N"; return 2 }
                atStart && pair == "wr" -> { out += "R"; return 2 }
                atStart && pair == "ps" -> { out += "S"; return 2 }
                atEnd && pair == "mb" -> { out += "M"; return 2 }
                atEnd && pair == "le" && word[index - 1] !in VOWELS_SPELLED -> {
                    out += listOf("AH0", "L")
                    return 2
                }
                pair in R_CONTROLLED -> { out += R_CONTROLLED.getValue(pair); return 2 }
                pair in VOWEL_PAIRS -> { out += VOWEL_PAIRS.getValue(pair); return 2 }
            }
        }
        if (char in VOWELS_SPELLED) {
            out += vowel(word, index)
            return 1
        }
        consonant(word, index)?.let { out += it }
        return 1
    }

    private fun vowel(word: String, index: Int): List<String> {
        val char = word[index]
        if (char == 'e' && index == word.length - 1 && word.take(index).any { it in VOWELS_SPELLED }) {
            return emptyList()
        }
        if (char == 'y') {
            return when {
                index == 0 -> listOf("Y")
                index == word.length - 1 -> listOf("IY0")
                else -> listOf("IH")
            }
        }
        return listOf(if (isMagicE(word, index)) LONG.getValue(char) else SHORT.getValue(char))
    }

    /** A single vowel, one consonant, then a final "e" — the "stane" pattern. */
    private fun isMagicE(word: String, index: Int): Boolean =
        word.length - index == 3 &&
            word.last() == 'e' &&
            word[index + 1] !in VOWELS_SPELLED

    private fun consonant(word: String, index: Int): List<String>? {
        val char = word[index]
        val next = word.getOrNull(index + 1)
        return when (char) {
            'c' -> listOf(if (next in SOFTENERS) "S" else "K")
            'g' -> listOf(if (next in SOFTENERS) "JH" else "G")
            'x' -> listOf("K", "S")
            's' -> listOf("S")
            else -> CONSONANTS[char]?.let(::listOf)
        }
    }

    /** Primary stress on the first vowel; every other vowel unstressed. */
    private fun stressed(phones: List<String>): List<String> {
        var marked = false
        return phones.map { phone ->
            if (phone.last().isDigit() || phone !in VOWEL_PHONES) {
                phone
            } else if (!marked) {
                marked = true
                phone + "1"
            } else {
                phone + "0"
            }
        }
    }

    private val VOWELS_SPELLED = "aeiouy"
    private val SOFTENERS = setOf('e', 'i', 'y')
    private val LONG = mapOf('a' to "EY", 'e' to "IY", 'i' to "AY", 'o' to "OW", 'u' to "UW")
    private val SHORT = mapOf('a' to "AE", 'e' to "EH", 'i' to "IH", 'o' to "AA", 'u' to "AH")
    private val R_CONTROLLED = mapOf(
        "ar" to listOf("AA", "R"), "er" to listOf("ER"), "ir" to listOf("ER"),
        "or" to listOf("AO", "R"), "ur" to listOf("ER"),
    )
    private val VOWEL_PAIRS = mapOf(
        "ee" to listOf("IY"), "ea" to listOf("IY"), "oo" to listOf("UW"), "ai" to listOf("EY"),
        "ay" to listOf("EY"), "oa" to listOf("OW"), "oe" to listOf("OW"), "ow" to listOf("OW"),
        "ou" to listOf("AW"), "oi" to listOf("OY"), "oy" to listOf("OY"), "au" to listOf("AO"),
        "aw" to listOf("AO"), "ie" to listOf("IY"), "ei" to listOf("EY"), "ew" to listOf("UW"),
        "ue" to listOf("UW"), "eu" to listOf("UW"),
    )
    private val CONSONANTS = mapOf(
        'b' to "B", 'd' to "D", 'f' to "F", 'h' to "HH", 'j' to "JH", 'k' to "K", 'l' to "L",
        'm' to "M", 'n' to "N", 'p' to "P", 'q' to "K", 'r' to "R", 't' to "T", 'v' to "V",
        'w' to "W", 'z' to "Z",
    )
    private val VOWEL_PHONES = setOf(
        "AA", "AE", "AH", "AO", "AW", "AY", "EH", "ER", "EY", "IH", "IY", "OW", "OY", "UH", "UW",
    )
}

/**
 * One spelling, two readings. CMUdict lists both pronunciations but does not say which sense each
 * belongs to, so the sense is identified structurally — by where the primary stress falls, or by
 * which vowel a variant carries — and selected from the neighbouring words. When no rule applies
 * the dictionary's own first variant is used, which is what the reader did before.
 */
internal object Heteronyms {
    fun choose(word: String, variants: List<List<String>>, context: WordContext): List<String> {
        val first = variants.first()
        if (variants.size < 2) return first
        val wanted = RULES[word]?.invoke(context) ?: return first
        return variants.firstOrNull(wanted) ?: first
    }

    /**
     * Function words lose their vowel in running speech — "to" is /tə/ before another word and
     * /tuː/ before a pause. CMUdict already lists both; this picks the one the phrase calls for.
     */
    fun reduced(word: String, variants: List<List<String>>, context: WordContext): List<String>? {
        if (word !in REDUCIBLE || context.followedByPunctuation || variants.size < 2) return null
        return variants.firstOrNull { variant -> variant.none { it.endsWith("1") } }
    }

    private val REDUCIBLE = setOf(
        "a", "an", "and", "as", "at", "but", "can", "do", "does", "for", "from", "had", "has",
        "have", "of", "or", "than", "the", "to", "was", "were",
    )

    private fun carries(phone: String): (List<String>) -> Boolean =
        { variant -> variant.any { it.trimEnd('0', '1', '2') == phone } }

    private fun endsWith(phone: String): (List<String>) -> Boolean =
        { variant -> variant.last().trimEnd('0', '1', '2') == phone }

    private val NOUN_MARKERS = setOf(
        "the", "a", "an", "this", "that", "these", "those", "my", "your", "his", "her", "its",
        "our", "their", "no", "any", "some", "each", "every", "one", "another", "of", "in", "on",
        "for", "with", "by", "at", "from", "into", "about", "without", "his", "whose",
    )
    private val VERB_MARKERS = setOf(
        "to", "will", "would", "can", "could", "should", "must", "may", "might", "shall", "do",
        "does", "did", "don't", "doesn't", "didn't", "i", "we", "you", "they", "he", "she", "who",
        "please", "let", "cannot", "won't",
    )

    /**
     * Noun-verb stress pairs: "a RECord" against "to reCORD". Both readings exist in the
     * dictionary; the neighbouring word decides which one is meant.
     */
    private val STRESS_PAIR_WORDS = setOf(
        "record", "present", "object", "subject", "contract", "conduct", "desert", "produce",
        "project", "rebel", "permit", "conflict", "contest", "protest", "insult", "suspect",
        "survey", "transfer", "increase", "decrease", "progress", "export", "import", "convert",
        "digest", "refuse", "console", "combine", "compound", "upset", "address", "discount",
    )

    private val PERFECT_MARKERS = setOf(
        "have", "has", "had", "having", "been", "hasn't", "haven't", "hadn't", "already", "just",
        "never", "then", "i'd", "he'd", "she'd", "we'd", "they'd", "you'd", "who'd",
    )
    private val DETERMINERS = setOf("a", "an", "the", "this", "that")

    private val STRESS_PAIRS: Map<String, (WordContext) -> ((List<String>) -> Boolean)?> =
        STRESS_PAIR_WORDS.associateWith { _ ->
            { context: WordContext ->
                when (preceding(context)) {
                    in NOUN_MARKERS -> { candidate: List<String> -> firstVowelStressed(candidate) }
                    in VERB_MARKERS -> { candidate: List<String> -> !firstVowelStressed(candidate) }
                    else -> null
                }
            }
        }

    /** Readings that differ by vowel or by final voicing rather than by stress. */
    private val VOWEL_PAIRS: Map<String, (WordContext) -> ((List<String>) -> Boolean)?> = mapOf(
        "read" to { context ->
            if (preceding(context) in PERFECT_MARKERS) carries("EH") else carries("IY")
        },
        "live" to { context ->
            if (preceding(context) in DETERMINERS) carries("AY") else carries("IH")
        },
        "wind" to { context ->
            if (preceding(context) in VERB_MARKERS) carries("AY") else carries("IH")
        },
        "lead" to { context ->
            if (following(context) in LEAD_METAL) carries("EH") else carries("IY")
        },
        "wound" to { context ->
            if (following(context) in WOUND_UP) carries("AW") else carries("UW")
        },
        "tear" to { context -> if (following(context) in TORN_APART) carries("EH") else null },
        "tears" to { context -> if (following(context) in TORN_APART) carries("EH") else null },
        "bass" to { context -> if (following(context) in BASS_MUSIC) carries("EY") else null },
        "use" to ::nounVerbVoicing,
        "uses" to ::nounVerbVoicing,
        "excuse" to ::nounVerbVoicing,
        "close" to { context ->
            when {
                following(context) in CLOSE_BY || preceding(context) in CLOSE_ADVERBS -> endsWith("S")
                preceding(context) in VERB_MARKERS -> endsWith("Z")
                else -> null
            }
        },
    )

    private val RULES: Map<String, (WordContext) -> ((List<String>) -> Boolean)?> =
        STRESS_PAIRS + VOWEL_PAIRS

    /** "the use of" is unvoiced; "to use it" is voiced. */
    private fun nounVerbVoicing(context: WordContext): ((List<String>) -> Boolean)? = when (preceding(context)) {
        in NOUN_MARKERS -> endsWith("S")
        in VERB_MARKERS -> endsWith("Z")
        else -> null
    }

    private fun preceding(context: WordContext): String? = context.previous?.lowercase()?.trim('.', ',', '"', '\u201c', '\u201d')

    private fun following(context: WordContext): String? = context.next?.lowercase()?.trim('.', ',', '"', '\u201c', '\u201d')

    private val LEAD_METAL = setOf("pipe", "pipes", "paint", "poisoning", "ore", "shot", "weights", "solder")
    private val WOUND_UP = setOf("up", "around", "down", "back", "tight", "tighter")
    private val TORN_APART = setOf("up", "down", "apart", "open", "through", "off", "into", "away")
    private val BASS_MUSIC = setOf("guitar", "guitars", "line", "player", "clef", "drum", "voice", "note", "notes")
    private val CLOSE_BY = setOf("to", "by", "enough", "behind", "beside", "together", "range")
    private val CLOSE_ADVERBS = setOf("so", "very", "too", "quite", "get", "getting", "got", "stay", "stayed", "come", "came")

    /** True when the primary stress is on the word's first vowel. */
    private fun firstVowelStressed(phones: List<String>): Boolean {
        val firstVowel = phones.indexOfFirst { phone -> phone.last().isDigit() }
        return firstVowel >= 0 && phones[firstVowel].endsWith("1")
    }
}
