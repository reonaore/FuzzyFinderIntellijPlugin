package com.github.reonaore.fuzzyfinderintellijplugin.shared.ui

import com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange
import com.intellij.ui.SimpleTextAttributes

internal fun contiguousHighlightRanges(highlightIndexes: Set<Int>): List<TextRange> {
    if (highlightIndexes.isEmpty()) return emptyList()

    val sortedIndexes = highlightIndexes.sorted()
    val ranges = mutableListOf<TextRange>()
    var rangeStart = sortedIndexes.first()
    var previousIndex = rangeStart

    for (index in sortedIndexes.drop(1)) {
        if (index == previousIndex + 1) {
            previousIndex = index
            continue
        }

        ranges += TextRange(rangeStart, previousIndex + 1)
        rangeStart = index
        previousIndex = index
    }

    ranges += TextRange(rangeStart, previousIndex + 1)
    return ranges
}

internal class HighlightedTextComponent : com.intellij.ui.SimpleColoredComponent() {
    fun applyHighlight(text: String, highlightRanges: List<TextRange>, foregroundColor: java.awt.Color) {
        clear()
        val plain = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, foregroundColor)
        val highlighted = SimpleTextAttributes(SimpleTextAttributes.STYLE_SEARCH_MATCH, foregroundColor)

        if (highlightRanges.isEmpty()) {
            append(text, plain)
            return
        }

        var start = 0
        highlightRanges
            .sortedBy(TextRange::startOffset)
            .forEach { range ->
                val rangeStart = range.startOffset.coerceIn(start, text.length)
                val rangeEnd = range.endOffset.coerceIn(rangeStart, text.length)
                if (rangeStart > start) {
                    append(text.substring(start, rangeStart), plain)
                }
                if (rangeEnd > rangeStart) {
                    append(text.substring(rangeStart, rangeEnd), highlighted)
                }
                start = rangeEnd
            }
        if (start < text.length) {
            append(text.substring(start), plain)
        }
    }
}
