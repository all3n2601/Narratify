package app.narratify.shared.text

internal object TextNormalizer {
    private val abbreviations = mapOf(
        "dr." to "doctor", "prof." to "professor", "mr." to "mister", "mrs." to "missus",
        "ms." to "miz", "jr." to "junior", "sr." to "senior", "etc." to "et cetera",
        "i.e." to "that is", "e.g." to "for example", "a.m." to "A M", "p.m." to "P M",
        "jan." to "January", "feb." to "February", "mar." to "March", "apr." to "April",
        "jun." to "June", "jul." to "July", "aug." to "August", "sep." to "September",
        "sept." to "September", "oct." to "October", "nov." to "November", "dec." to "December",
    )
    private val punctuation = mapOf('…' to "...", '—' to "—", '–' to "-", '“' to "\"", '”' to "\"", '‘' to "'", '’' to "'")
    private val fractions = mapOf('¼' to "one quarter", '½' to "one half", '¾' to "three quarters", '⅓' to "one third", '⅔' to "two thirds")

    fun normalize(value: String, language: String, previousWord: String? = null): String {
        if (!language.startsWith("en", ignoreCase = true)) return value.map { punctuation[it] ?: it.toString() }.joinToString("")
        abbreviations[value.lowercase()]?.let { return it }
        if (value.length == 1) fractions[value[0]]?.let { return it }
        normalizeNumber(value)?.let { return it }
        romanNumeral(value, previousWord)?.let { return it }
        return value.map { punctuation[it] ?: it.toString() }.joinToString("")
    }

    fun isKnownAbbreviation(value: String): Boolean = value.lowercase() in abbreviations

    private fun normalizeNumber(raw: String): String? {
        ordinal(raw)?.let { return it }
        year(raw)?.let { return it }
        clockTime(raw)?.let { return it }
        var value = raw.replace("−", "-")
        val currency = value.firstOrNull()?.takeIf { it in "$£€" }
        if (currency != null) value = value.drop(1)
        val percent = value.endsWith('%')
        if (percent) value = value.dropLast(1)
        val negative = value.startsWith('-')
        if (negative) value = value.drop(1)
        value = value.replace(",", "")
        if (value.isEmpty() || value.any { !it.isDigit() && it != '.' } || value.count { it == '.' } > 1) return null
        val parts = value.split('.')
        val whole = parts[0].toLongOrNull() ?: return null
        val spoken = buildString {
            if (negative) append("minus ")
            append(cardinal(whole))
            if (parts.size == 2 && currency == null) {
                append(" point ")
                append(parts[1].map { digitName(it) }.joinToString(" "))
            }
            if (currency != null) append(' ').append(currencyName(currency, whole, parts.getOrNull(1)))
            if (percent) append(" percent")
        }
        return spoken
    }

    private fun currencyName(symbol: Char, whole: Long, decimals: String?): String {
        val unit = when (symbol) { '$' -> "dollar"; '£' -> "pound"; else -> "euro" }
        val cents = decimals?.padEnd(2, '0')?.take(2)?.toIntOrNull() ?: 0
        return buildString {
            append(if (whole == 1L) unit else "${unit}s")
            if (cents > 0) append(" and ${cardinal(cents.toLong())} cents")
        }
    }

    /**
     * "Chapter XIV" is a number; "the XL shirt", "DC", and "CD" are not. Roman numerals are only
     * expanded directly after a structural word, because too many English words and initialisms
     * are also well-formed numerals.
     */
    private fun romanNumeral(value: String, previousWord: String?): String? {
        val preceding = previousWord?.lowercase()?.trimEnd('.') ?: return null
        if (preceding !in STRUCTURAL_WORDS) return null
        if (value.length < 2 || !ROMAN.matches(value)) return null
        var total = 0
        var previous = 0
        value.reversed().forEach { symbol ->
            val digit = ROMAN_VALUES.getValue(symbol)
            total += if (digit < previous) -digit else digit
            previous = maxOf(previous, digit)
        }
        return cardinal(total.toLong())
    }

    /** "3:30" is read as a time, not as two numbers separated by a pause. */
    private fun clockTime(raw: String): String? {
        val parts = raw.split(':')
        if (parts.size != 2) return null
        if (parts[0].length !in 1..2 || parts[1].length != 2) return null
        if (parts.any { part -> part.any { !it.isDigit() } }) return null
        val hour = parts[0].toInt()
        val minute = parts[1].toInt()
        if (hour > 23 || minute > 59) return null
        return when {
            minute == 0 -> "${cardinal(hour.toLong())} o'clock"
            minute < 10 -> "${cardinal(hour.toLong())} oh ${cardinal(minute.toLong())}"
            else -> "${cardinal(hour.toLong())} ${cardinal(minute.toLong())}"
        }
    }

    /**
     * Bare four-digit numbers in prose are almost always years, and a reader says them in pairs:
     * "nineteen eighty four", not "one thousand nine hundred eighty four". Anything carrying a
     * separator, currency, sign, or fraction is a quantity and keeps its cardinal form.
     */
    private fun year(raw: String): String? {
        if (raw.length != 4 || raw.any { !it.isDigit() }) return null
        val number = raw.toInt()
        if (number < 1100 || number > 2099) return null
        if (number % 1000 == 0 || number in 2000..2009) return null
        val high = number / 100
        val low = number % 100
        return when {
            low == 0 -> "${underThousand(high.toLong())} hundred"
            low < 10 -> "${underThousand(high.toLong())} oh ${underThousand(low.toLong())}"
            else -> "${underThousand(high.toLong())} ${underThousand(low.toLong())}"
        }
    }

    /** "22nd" reads as an ordinal; the digits alone would read as "twenty two". */
    private fun ordinal(raw: String): String? {
        val suffix = ORDINAL_SUFFIXES.firstOrNull { raw.length > it.length && raw.endsWith(it, ignoreCase = true) } ?: return null
        val digits = raw.dropLast(suffix.length)
        if (digits.any { !it.isDigit() }) return null
        val number = digits.toLongOrNull() ?: return null
        val words = cardinal(number)
        val last = words.substringAfterLast(' ')
        val spoken = ORDINAL_WORDS[last] ?: if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        return words.dropLast(last.length) + spoken
    }

    private fun cardinal(number: Long): String {
        if (number == 0L) return "zero"
        if (number < 0 || number > 999_999_999_999L) return number.toString()
        val scales = listOf(1_000_000_000L to "billion", 1_000_000L to "million", 1_000L to "thousand")
        var remaining = number
        val parts = mutableListOf<String>()
        for ((scale, name) in scales) {
            if (remaining >= scale) {
                parts += "${underThousand(remaining / scale)} $name"
                remaining %= scale
            }
        }
        if (remaining > 0) parts += underThousand(remaining)
        return parts.joinToString(" ")
    }

    private fun underThousand(value: Long): String {
        val ones = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
        val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        val parts = mutableListOf<String>()
        var remaining = value.toInt()
        if (remaining >= 100) { parts += "${ones[remaining / 100]} hundred"; remaining %= 100 }
        if (remaining >= 20) { parts += tens[remaining / 10]; remaining %= 10 }
        if (remaining > 0) parts += ones[remaining]
        return parts.joinToString(" ")
    }

    private val STRUCTURAL_WORDS = setOf(
        "chapter", "part", "book", "volume", "vol", "act", "scene", "section", "canto",
        "appendix", "article", "phase", "stage", "figure", "fig", "plate", "no",
    )
    private val ROMAN = Regex("^M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})$")
    private val ROMAN_VALUES = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)

    private val ORDINAL_SUFFIXES = listOf("st", "nd", "rd", "th")
    private val ORDINAL_WORDS = mapOf(
        "zero" to "zeroth", "one" to "first", "two" to "second", "three" to "third",
        "five" to "fifth", "eight" to "eighth", "nine" to "ninth", "twelve" to "twelfth",
        "hundred" to "hundredth", "thousand" to "thousandth", "million" to "millionth",
        "billion" to "billionth",
    )

    private fun digitName(char: Char): String = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")[char - '0']
}
