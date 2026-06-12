package com.github.reonaore.fuzzyfinderintellijplugin.shared.ui

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.services.PreviewHighlightRange
import com.github.reonaore.fuzzyfinderintellijplugin.services.PreviewLineHighlight
import com.github.reonaore.fuzzyfinderintellijplugin.services.PreviewLineHighlightKind
import com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRenderer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle

class FuzzyFinderPreview(
    private val project: Project,
) {
    private val document = EditorFactory.getInstance().createDocument(MyBundle.message("dialog.preview.empty"))
    private val previewHighlighters = mutableListOf<RangeHighlighter>()
    val editor = (EditorFactory.getInstance().createViewer(document, project) as EditorEx).apply {
        settings.apply {
            isLineNumbersShown = true
            isFoldingOutlineShown = false
            isRightMarginShown = false
            isWhitespacesShown = false
            isCaretRowShown = false
            additionalColumnsCount = 1
            additionalLinesCount = 1
        }
        setCaretEnabled(false)
    }

    suspend fun show(
        content: PreviewContent,
        scrollToLine: Int? = null,
        highlightRanges: List<PreviewHighlightRange> = emptyList(),
        lineHighlights: List<PreviewLineHighlight> = emptyList(),
    ) {
        val (text, virtualFile) = content
        val normalizedText = normalizePreviewTextForEditor(text)
        val highlighter = readAction {
            if (virtualFile != null && !virtualFile.isDirectory && !virtualFile.fileType.isBinary) {
                EditorHighlighterFactory.getInstance().createEditorHighlighter(project, virtualFile)
            } else {
                EditorHighlighterFactory.getInstance().createEditorHighlighter(project, PlainTextFileType.INSTANCE)
            }
        }
        writeAction {
            clearPreviewHighlighters()
            editor.highlighter = highlighter
            editor.caretModel.moveToOffset(0)
            editor.scrollingModel.scrollVertically(0)
            document.setText(normalizedText)
            addPreviewLineHighlighters(lineHighlights)
            addPreviewHighlighters(normalizedText, highlightRanges)
            scrollToLine?.let { line ->
                val lineIndex = (line - 1).coerceIn(0, document.lineCount - 1)
                editor.caretModel.moveToLogicalPosition(com.intellij.openapi.editor.LogicalPosition(lineIndex, 0))
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            }
        }
    }

    fun dispose() {
        clearPreviewHighlighters()
        EditorFactory.getInstance().releaseEditor(editor)
    }

    private fun addPreviewLineHighlighters(lineHighlights: List<PreviewLineHighlight>) {
        previewLineHighlightIndexes(document.lineCount, lineHighlights).forEach { highlight ->
            val highlighter = editor.markupModel.addLineHighlighter(
                highlight.lineIndex,
                lineHighlightLayer(highlight.kind),
                lineHighlightTextAttributes(highlight.kind),
            )
            if (highlight.kind == PreviewLineHighlightKind.SELECTED) {
                highlighter.setLineMarkerRenderer(SelectedLineAccentRenderer)
            }
            previewHighlighters += highlighter
        }
    }

    private fun addPreviewHighlighters(text: String, highlightRanges: List<PreviewHighlightRange>) {
        val textAttributes = TextAttributes(
            SEARCH_MATCH_FOREGROUND,
            SEARCH_MATCH_BACKGROUND,
            null,
            null,
            Font.PLAIN,
        )
        previewHighlightTextOffsets(text, highlightRanges).forEach { range ->
            previewHighlighters += editor.markupModel.addRangeHighlighter(
                range.startOffset,
                range.endOffset,
                WORD_HIGHLIGHT_LAYER,
                textAttributes,
                HighlighterTargetArea.EXACT_RANGE,
            )
        }
    }

    private fun lineHighlightLayer(kind: PreviewLineHighlightKind): Int {
        return when (kind) {
            PreviewLineHighlightKind.MATCH -> MATCH_LINE_HIGHLIGHT_LAYER
            PreviewLineHighlightKind.SELECTED -> SELECTED_LINE_HIGHLIGHT_LAYER
        }
    }

    private fun lineHighlightTextAttributes(kind: PreviewLineHighlightKind): TextAttributes {
        val background = when (kind) {
            PreviewLineHighlightKind.MATCH -> RELATED_LINE_BACKGROUND
            PreviewLineHighlightKind.SELECTED -> SELECTED_LINE_BACKGROUND
        }
        return TextAttributes(null, background, null, null, Font.PLAIN)
    }

    private fun clearPreviewHighlighters() {
        previewHighlighters.forEach(editor.markupModel::removeHighlighter)
        previewHighlighters.clear()
    }

    private companion object {
        const val WORD_HIGHLIGHT_LAYER = HighlighterLayer.SELECTION - 1
        const val SELECTED_LINE_HIGHLIGHT_LAYER = HighlighterLayer.SELECTION - 3
        const val MATCH_LINE_HIGHLIGHT_LAYER = HighlighterLayer.SELECTION - 4
        val RELATED_LINE_BACKGROUND = JBColor(
            Color(0xF6, 0xC8, 0x5F, 46),
            Color(0xF6, 0xC8, 0x5F, 36),
        )
        val SELECTED_LINE_BACKGROUND = JBColor(
            Color(0x3B, 0x82, 0xF6, 64),
            Color(0x3B, 0x82, 0xF6, 89),
        )
        val SELECTED_LINE_ACCENT = JBColor(
            Color(0x25, 0x63, 0xEB),
            Color(0x60, 0xA5, 0xFA),
        )
        val SEARCH_MATCH_BACKGROUND = JBColor(
            Color(0xFF, 0xB1, 0x94),
            Color(0xFF, 0x8A, 0x65),
        )
        val SEARCH_MATCH_FOREGROUND = JBColor(
            Color(0x4A, 0x1D, 0x12),
            Color(0x2A, 0x12, 0x0B),
        )
    }

    private object SelectedLineAccentRenderer : LineMarkerRenderer {
        override fun paint(editor: Editor, graphics: Graphics, rectangle: Rectangle) {
            graphics.color = SELECTED_LINE_ACCENT
            graphics.fillRect(rectangle.x, rectangle.y, SELECTED_LINE_ACCENT_WIDTH, rectangle.height)
        }
    }
}

private const val SELECTED_LINE_ACCENT_WIDTH = 3

internal fun normalizePreviewTextForEditor(text: String): String {
    return StringUtil.convertLineSeparators(text)
}

internal fun previewHighlightTextOffsets(text: String, highlightRanges: List<PreviewHighlightRange>): List<TextRange> {
    if (text.isEmpty() || highlightRanges.isEmpty()) {
        return emptyList()
    }

    val lineStartOffsets = lineStartOffsets(text)
    return highlightRanges.mapNotNull { highlight ->
        val lineIndex = highlight.line - 1
        val lineStart = lineStartOffsets.getOrNull(lineIndex) ?: return@mapNotNull null
        val lineEnd = text.indexOf('\n', lineStart).takeIf { it >= 0 } ?: text.length
        val startOffset = (lineStart + highlight.range.startOffset).coerceIn(lineStart, lineEnd)
        val endOffset = (lineStart + highlight.range.endOffset).coerceIn(startOffset, lineEnd)
        TextRange(startOffset, endOffset).takeIf { it.startOffset < it.endOffset }
    }
}

internal fun previewLineHighlightIndexes(
    lineCount: Int,
    lineHighlights: List<PreviewLineHighlight>,
): List<PreviewLineHighlightIndex> {
    return lineHighlights.mapNotNull { highlight ->
        val lineIndex = highlight.line - 1
        if (lineIndex !in 0 until lineCount) {
            return@mapNotNull null
        }
        PreviewLineHighlightIndex(lineIndex = lineIndex, kind = highlight.kind)
    }
}

internal data class PreviewLineHighlightIndex(
    val lineIndex: Int,
    val kind: PreviewLineHighlightKind,
)

private fun lineStartOffsets(text: String): List<Int> {
    val offsets = mutableListOf(0)
    var newlineIndex = text.indexOf('\n')
    while (newlineIndex >= 0 && newlineIndex + 1 < text.length) {
        offsets += newlineIndex + 1
        newlineIndex = text.indexOf('\n', newlineIndex + 1)
    }
    return offsets
}
