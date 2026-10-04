package org.starfall.multigateway.ui.chat

sealed interface MarkdownBlock {
    data class CodeBlock(val language: String, val code: String, val isClosed: Boolean = true) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class BlockQuote(val text: String) : MarkdownBlock
    data class UnorderedList(val items: List<String>) : MarkdownBlock
    data class OrderedList(val items: List<Pair<String, String>>) : MarkdownBlock
    data class TaskList(val items: List<Pair<Boolean, String>>) : MarkdownBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock
    data object HorizontalRule : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
}

fun parseMarkdown(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = markdown.lines()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // 1. Fenced Code Block
        if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
            val fence = if (line.trimStart().startsWith("```")) "```" else "~~~"
            val lang = line.trimStart().removePrefix(fence).trim()
            val codeLines = mutableListOf<String>()
            var closed = false
            i++
            while (i < lines.size) {
                val codeLine = lines[i]
                if (codeLine.trimStart().startsWith(fence)) {
                    closed = true
                    i++
                    break
                } else {
                    codeLines.add(codeLine)
                    i++
                }
            }
            blocks.add(MarkdownBlock.CodeBlock(lang, codeLines.joinToString("\n"), closed))
            continue
        }

        // 2. Horizontal Rule (---, ***, ___)
        val trimmed = line.trim()
        if (trimmed.length >= 3 && (trimmed.all { it == '-' } || trimmed.all { it == '*' } || trimmed.all { it == '_' })) {
            blocks.add(MarkdownBlock.HorizontalRule)
            i++
            continue
        }

        // 3. Heading (# .. ######)
        val headingMatch = Regex("^(#{1,6})\\s+(.*)$").find(trimmed)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            val text = headingMatch.groupValues[2].trim()
            blocks.add(MarkdownBlock.Heading(level, text))
            i++
            continue
        }

        // 4. Blockquote (> ...)
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoteLines.add(lines[i].trim().removePrefix(">").trim())
                i++
            }
            blocks.add(MarkdownBlock.BlockQuote(quoteLines.joinToString("\n")))
            continue
        }

        // 5. Task List (- [ ] or - [x])
        if (trimmed.startsWith("- [ ] ") || trimmed.startsWith("- [x] ") || trimmed.startsWith("- [X] ") ||
            trimmed.startsWith("* [ ] ") || trimmed.startsWith("* [x] ") || trimmed.startsWith("* [X] ")
        ) {
            val taskItems = mutableListOf<Pair<Boolean, String>>()
            while (i < lines.size) {
                val current = lines[i].trim()
                val isCheck = when {
                    current.startsWith("- [x] ", ignoreCase = true) || current.startsWith("* [x] ", ignoreCase = true) -> true
                    current.startsWith("- [ ] ") || current.startsWith("* [ ] ") -> false
                    else -> null
                }
                if (isCheck != null) {
                    val itemText = current.substring(6).trim()
                    taskItems.add(Pair(isCheck, itemText))
                    i++
                } else {
                    break
                }
            }
            blocks.add(MarkdownBlock.TaskList(taskItems))
            continue
        }

        // 6. Unordered List (- , * , + )
        if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
            val listItems = mutableListOf<String>()
            while (i < lines.size) {
                val current = lines[i].trim()
                if (current.startsWith("- ") || current.startsWith("* ") || current.startsWith("+ ")) {
                    listItems.add(current.substring(2).trim())
                    i++
                } else if (current.isNotBlank() && (lines[i].startsWith("  ") || lines[i].startsWith("\t")) && listItems.isNotEmpty()) {
                    val last = listItems.removeAt(listItems.lastIndex)
                    listItems.add("$last\n${current.trim()}")
                    i++
                } else {
                    break
                }
            }
            blocks.add(MarkdownBlock.UnorderedList(listItems))
            continue
        }

        // 7. Ordered List (1. , 2. )
        val orderedMatch = Regex("^(\\d+)\\.\\s+(.*)$").find(trimmed)
        if (orderedMatch != null) {
            val orderedItems = mutableListOf<Pair<String, String>>()
            while (i < lines.size) {
                val current = lines[i].trim()
                val match = Regex("^(\\d+)\\.\\s+(.*)$").find(current)
                if (match != null) {
                    orderedItems.add(Pair(match.groupValues[1], match.groupValues[2].trim()))
                    i++
                } else if (current.isNotBlank() && (lines[i].startsWith("  ") || lines[i].startsWith("\t")) && orderedItems.isNotEmpty()) {
                    val last = orderedItems.removeAt(orderedItems.lastIndex)
                    orderedItems.add(Pair(last.first, "${last.second}\n${current.trim()}"))
                    i++
                } else {
                    break
                }
            }
            blocks.add(MarkdownBlock.OrderedList(orderedItems))
            continue
        }

        // 8. Table (| Col 1 | Col 2 |)
        if (trimmed.startsWith("|") && trimmed.endsWith("|") && i + 1 < lines.size && lines[i + 1].trim().contains("---")) {
            val headers = trimmed.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size) {
                val rowLine = lines[i].trim()
                if (rowLine.startsWith("|") && rowLine.endsWith("|")) {
                    val rowCells = rowLine.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    rows.add(rowCells)
                    i++
                } else {
                    break
                }
            }
            blocks.add(MarkdownBlock.Table(headers, rows))
            continue
        }

        if (trimmed.isEmpty()) { i++; continue }

        val paragraphLines = mutableListOf<String>()
        while (i < lines.size) {
            val current = lines[i]
            val currentTrim = current.trim()
            if (currentTrim.isEmpty()) { i++; break }
            if (currentTrim.startsWith("```") || currentTrim.startsWith("~~~") ||
                Regex("^(#{1,6})\\s+(.*)$").matches(currentTrim) || currentTrim.startsWith(">") ||
                currentTrim.startsWith("- ") || currentTrim.startsWith("* ") || currentTrim.startsWith("+ ") ||
                Regex("^(\\d+)\\.\\s+").containsMatchIn(currentTrim) ||
                (currentTrim.startsWith("|") && currentTrim.endsWith("|") && i + 1 < lines.size && lines[i + 1].trim().contains("---")) ||
                (currentTrim.length >= 3 && (currentTrim.all { it == '-' } || currentTrim.all { it == '*' } || currentTrim.all { it == '_' }))
            ) break
            paragraphLines.add(currentTrim)
            i++
        }
        if (paragraphLines.isNotEmpty()) blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString("\n")))
    }
    return blocks
}
