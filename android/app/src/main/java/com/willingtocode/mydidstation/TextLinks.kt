package com.willingtocode.mydidstation

/**
 * Finds the links in a message body, so the thread can make them tappable.
 *
 * Kept apart from the UI and free of Compose types so the matching rules can be
 * tested on the JVM, which is where the edge cases are: trailing punctuation,
 * brackets around a URL, and a bare host with no scheme.
 */
object TextLinks {

    /**
     * Deliberately conservative. Only http, https and a bare `www.` host match, so
     * a phone number or an address never turns into a link by accident. Stopping at
     * whitespace and quotes keeps the match inside one word.
     */
    private val URL = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")

    /** Punctuation that usually belongs to the sentence rather than the URL. */
    private const val TRAILING = ".,;:!?]}…"

    /**
     * Character ranges of [body] that are links, in order, with trailing
     * punctuation trimmed off, so "see http://a.example." does not open "a.example.".
     */
    fun find(body: String): List<IntRange> =
        URL.findAll(body).mapNotNull { match ->
            var end = match.range.last
            while (end >= match.range.first) {
                val c = body[end]
                if (c in TRAILING) {
                    end--
                    continue
                }
                // A closing bracket is punctuation only when the match does not open
                // one, since plenty of real URLs end in a balanced pair.
                if (c == ')') {
                    val slice = body.substring(match.range.first, end + 1)
                    if (slice.count { it == ')' } > slice.count { it == '(' }) {
                        end--
                        continue
                    }
                }
                break
            }
            if (end < match.range.first) null else match.range.first..end
        }.toList()

    /** The string to open for a matched range. A bare host is assumed to be https. */
    fun toUrl(raw: String): String =
        if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
}
