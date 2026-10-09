package org.starfall.multigateway.ui.chat

object LatexDetector {

    private val blockDollarExpression = Regex("""(?s)^\s*\$\$(.+?)\$\$\s*$""")
    private val bracketExpression = Regex("""(?s)^\s*\\\[(.+?)\\\]\s*$""")
    private val parenthesizedExpression = Regex("""(?s)^\s*\\\((.+?)\\\)\s*$""")
    private val singleDollarExpression = Regex("""(?s)(?<!\$)\$([^\$\n]+?)\$(?!\$)""")

    /**
     * Determines whether to render LaTeX based on the user's preference mode
     * ("ON", "OFF", or "AUTO").
     */
    fun shouldRenderLatex(text: String, mode: String): Boolean {
        return when (mode.uppercase()) {
            "OFF" -> false
            "ON" -> containsLatex(text)
            else -> containsLatex(text) // "AUTO" default
        }
    }

    /**
     * Intelligent auto-detection algorithm that distinguishes between LaTeX math equations
     * and USD price ranges (e.g., "$10 to $20", "$10 - $20", "$10.50 - $19.99").
     */
    fun containsLatex(text: String): Boolean {
        if (text.isBlank()) return false

        // 1. Double dollar $$ ... $$ is LaTeX block math.
        if (Regex("""(?s)\$\$(.+?)\$\$""").containsMatchIn(text)) {
            return true
        }

        // 2. Escaped LaTeX delimiters like \( ... \) or \[ ... \]. An opening
        // delimiter by itself is common in streaming messages and is not math yet.
        if (containsPairedExpression(text, "\\(", "\\)") ||
            containsPairedExpression(text, "\\[", "\\]")) {
            return true
        }

        // 3. Find single-dollar blocks: $ ... $
        val matches = singleDollarExpression.findAll(text).toList()

        if (matches.isEmpty()) return false

        // Check each match to see if it resembles a LaTeX expression
        for (match in matches) {
            val inner = match.groupValues[1].trim()

            // Check if this match is part of a USD price range pattern:
            if (isUsdPriceRange(text, match)) {
                continue // Skip price ranges like "$10 to $20", "$10 - $20"
            }

            if (looksLikeMath(inner)) {
                return true
            }
        }

        return false
    }

    /**
     * Returns a formula only when the paragraph is a standalone math expression.
     * This deliberately leaves prose containing inline math to the Markdown
     * renderer; full inline layout is outside the first rendering version.
     */
    internal fun standaloneFormula(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        blockDollarExpression.matchEntire(trimmed)?.let { return it.groupValues[1].trim() }
        bracketExpression.matchEntire(trimmed)?.let { return it.groupValues[1].trim() }
        parenthesizedExpression.matchEntire(trimmed)?.let { return it.groupValues[1].trim() }

        singleDollarExpression.matchEntire(trimmed)?.let { match ->
            val inner = match.groupValues[1].trim()
            if (looksLikeMath(inner) && !isUsdPriceRange(trimmed, match)) return inner
        }
        return null
    }

    private fun containsPairedExpression(text: String, opening: String, closing: String): Boolean {
        var start = text.indexOf(opening)
        while (start >= 0) {
            val end = text.indexOf(closing, start + opening.length)
            if (end > start + opening.length && looksLikeMath(text.substring(start + opening.length, end).trim())) {
                return true
            }
            start = text.indexOf(opening, start + opening.length)
        }
        return false
    }

    private fun looksLikeMath(expression: String): Boolean {
        val inner = expression.trim()
        if (inner.isEmpty()) return false

        // LaTeX commands (e.g. \frac, \alpha, \sqrt, \int, \sum).
        if (inner.contains("\\")) return true

        // Superscript and subscript notation is unambiguous in a delimited block.
        if (inner.contains("^") || inner.contains("_")) return true

        // Common math functions (e.g. sin, cos, log, lim, sqrt, sum, int).
        if (inner.contains(Regex("""\b(sin|cos|tan|log|ln|lim|sqrt|sum|int|f\s*\([a-zA-Z]\))\b"""))) {
            return true
        }

        // Mathematical operations with algebraic variables: x + y, a = b, 2x + 3.
        if (inner.contains(Regex("""[a-zA-Z]\s*[+\-*/=<>±≠≤≥]\s*[a-zA-Z0-9]""")) ||
            inner.contains(Regex("""[0-9]\s*[+\-*/=<>±≠≤≥]\s*[a-zA-Z]"""))) {
            return true
        }

        // Single letter math variables such as $x$, $y$, or $N$.
        return inner.length == 1 && inner[0].isLetter()
    }

    private fun isUsdPriceRange(fullText: String, match: MatchResult): Boolean {
        val inner = match.groupValues[1].trim()

        // 1. If inner starts with a price number (e.g. "10", "10.50", "1,000")
        val startsWithNumber = inner.matches(Regex("""^\d+([.,]\d+)?.*"""))
        if (!startsWithNumber) return false

        // Check if inner ends with common price range connectors like "to", "-", "and", "~", ","
        if (inner.endsWith("to", ignoreCase = true) ||
            inner.endsWith("-") ||
            inner.endsWith("and", ignoreCase = true) ||
            inner.endsWith("~") ||
            inner.endsWith(",")) {
            return true
        }

        // Check surrounding context: what comes immediately after the second '$'?
        val matchEnd = match.range.last + 1
        val remainingText = fullText.substring(matchEnd).trimStart()

        // If the remaining text starts with a number (e.g., "20", "20.00"), then "$10 ... $20" was matched
        if (remainingText.matches(Regex("""^\d+([.,]\d+)?.*"""))) {
            return true
        }

        return false
    }
}
