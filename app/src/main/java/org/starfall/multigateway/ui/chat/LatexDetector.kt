package org.starfall.multigateway.ui.chat

object LatexDetector {

    /**
     * Determines whether to render LaTeX based on the user's preference mode
     * ("ON", "OFF", or "AUTO").
     */
    fun shouldRenderLatex(text: String, mode: String): Boolean {
        return when (mode.uppercase()) {
            "OFF" -> false
            "ON" -> text.contains("$") || text.contains("\\(") || text.contains("\\[")
            else -> containsLatex(text) // "AUTO" default
        }
    }

    /**
     * Intelligent auto-detection algorithm that distinguishes between LaTeX math equations
     * and USD price ranges (e.g., "$10 to $20", "$10 - $20", "$10.50 - $19.99").
     */
    fun containsLatex(text: String): Boolean {
        if (text.isBlank()) return false

        // 1. Double dollar $$ ... $$ is always LaTeX block math.
        if (Regex("""\$\$[\s\S]+?\$\$""").containsMatchIn(text)) {
            return true
        }

        // 2. Escaped LaTeX delimiters like \( ... \) or \[ ... \]
        if (text.contains("""\(""") || text.contains("""\[""")) {
            return true
        }

        // 3. Find single-dollar blocks: $ ... $
        val singleDollarRegex = Regex("""(?<!\$)\$([^\$]+)\$(?!\$)""")
        val matches = singleDollarRegex.findAll(text).toList()

        if (matches.isEmpty()) return false

        // Check each match to see if it resembles a LaTeX expression
        for (match in matches) {
            val inner = match.groupValues[1].trim()

            // Check if this match is part of a USD price range pattern:
            if (isUsdPriceRange(text, match)) {
                continue // Skip price ranges like "$10 to $20", "$10 - $20"
            }

            // A. Contains LaTeX commands (e.g., \frac, \alpha, \sqrt, \int, \sum)
            if (inner.contains("\\")) {
                return true
            }

            // B. Contains superscript '^' or subscript '_'
            if (inner.contains("^") || inner.contains("_")) {
                return true
            }

            // C. Contains math functions (e.g. sin, cos, log, lim, sqrt, sum, int)
            if (inner.contains(Regex("""\b(sin|cos|tan|log|ln|lim|sqrt|sum|int|f\s*\([a-zA-Z]\))\b"""))) {
                return true
            }

            // D. Mathematical operations with algebraic variables: e.g. "x + y", "a = b", "2x + 3"
            if (inner.contains(Regex("""[a-zA-Z]\s*[+\-*/=<>±≠≤≥]\s*[a-zA-Z0-9]""")) ||
                inner.contains(Regex("""[0-9]\s*[+\-*/=<>±≠≤≥]\s*[a-zA-Z]"""))) {
                return true
            }

            // E. Single letter math variables like "$x$", "$y$", "$N$"
            if (inner.length == 1 && inner[0].isLetter()) {
                return true
            }
        }

        return false
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
