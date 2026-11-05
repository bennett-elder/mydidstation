package com.willingtocode.mydidstation

/**
 * SMS segmentation, so the composer can tell the truth about what will happen.
 *
 * Two alphabets, two budgets:
 *  - GSM 03.38 (7-bit): 160 units single, 153 per part once concatenated, and a
 *    few characters cost two units because they need an escape.
 *  - UCS-2 (anything outside GSM, e.g. curly quotes or emoji): 70 single, 67 per
 *    part, counted in UTF-16 code units (so most emoji cost 2).
 *
 * The budget is a property of the whole message, not of a position: typing past
 * 160 does not "fill" the first part, it reflows everything to 153.
 */
enum class SmsEncoding { GSM7, UCS2 }

data class SmsAnalysis(
    val encoding: SmsEncoding,
    val units: Int,
    val budget: Int,
    val segments: Int,
    /** Character index in the text where each following segment begins. */
    val boundaries: List<Int>,
    /** True when a single word was longer than a part and had to be cut. */
    val hardBreak: Boolean,
) {
    val remaining: Int get() = budget - units
}

object Sms {

    private const val GSM_SINGLE = 160
    private const val GSM_PART = 153
    private const val UCS2_SINGLE = 70
    private const val UCS2_PART = 67

    private val GSM_BASIC = (
        "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?" +
            "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
        ).toSet()

    private val GSM_EXTENDED = setOf('\u000C', '^', '{', '}', '\\', '[', '~', ']', '|', '€')

    fun analyze(text: String): SmsAnalysis {
        val gsm = text.all { it in GSM_BASIC || it in GSM_EXTENDED }
        if (gsm) {
            val units = text.sumOf { if (it in GSM_EXTENDED) 2 else 1 }
            val segments = if (units <= GSM_SINGLE) 1 else ceilDiv(units, GSM_PART)
            val budget = if (segments == 1) GSM_SINGLE else segments * GSM_PART
            val cuts = if (segments == 1) emptyList() else splitPoints(text, GSM_PART, true)
            return SmsAnalysis(
                encoding = SmsEncoding.GSM7,
                units = units,
                budget = budget,
                segments = segments,
                boundaries = cuts.map { kotlin.math.abs(it) },
                hardBreak = cuts.any { it < 0 },
            )
        }

        val units = text.length // UTF-16 code units, which is what UCS-2 counts
        val segments = if (units <= UCS2_SINGLE) 1 else ceilDiv(units, UCS2_PART)
        val budget = if (segments == 1) UCS2_SINGLE else segments * UCS2_PART
        val cuts = if (segments == 1) emptyList() else splitPoints(text, UCS2_PART, false)
        return SmsAnalysis(
            encoding = SmsEncoding.UCS2,
            units = units,
            budget = budget,
            segments = segments,
            boundaries = cuts.map { kotlin.math.abs(it) },
            hardBreak = cuts.any { it < 0 },
        )
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    /**
     * Greedy fill, then back off to the last whitespace so words stay intact.
     * Negative values are markers for a forced cut inside an over-long word;
     * [analyze] uses that only to report `hardBreak`.
     */
    private fun splitPoints(text: String, partUnits: Int, gsm: Boolean): List<Int> {
        val cuts = mutableListOf<Int>()
        var start = 0
        while (start < text.length) {
            var units = 0
            var i = start
            while (i < text.length) {
                val cost = if (gsm) (if (text[i] in GSM_EXTENDED) 2 else 1) else 1
                if (units + cost > partUnits) break
                units += cost
                i++
            }
            if (i >= text.length) break
            val cut = if (i > start && text[i].isWhitespace()) {
                i + 1 // break after the space, so the next part starts clean
            } else {
                var back = i
                while (back > start && !text[back - 1].isWhitespace()) back--
                if (back > start) back else -i
            }
            cuts.add(cut)
            start = kotlin.math.abs(cut)
        }
        return cuts
    }

    /** Text of each part, for the "where will it break" preview. */
    fun parts(text: String, analysis: SmsAnalysis): List<String> {
        if (analysis.segments <= 1) return listOf(text)
        val out = mutableListOf<String>()
        var start = 0
        for (cut in analysis.boundaries) {
            val at = kotlin.math.abs(cut)
            out.add(text.substring(start, at))
            start = at
        }
        out.add(text.substring(start))
        return out
    }
}
