package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.services.PreviewHighlightRange
import com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import java.awt.Font

class FuzzyFinderPreview(
    private val project: Project,
) {
    val document = EditorFactory.getInstance().createDocument(MyBundle.message("dialog.preview.empty"))
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
    ) {
        val (text, virtualFile) = content
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
            document.setText(text)
            addPreviewHighlighters(text, highlightRanges)
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

    private fun addPreviewHighlighters(text: String, highlightRanges: List<PreviewHighlightRange>) {
        val textAttributes = TextAttributes(
            null,
            JBColor(0xFFF59D, 0x5C4A16),
            null,
            null,
            Font.PLAIN,
        )
        previewHighlightTextOffsets(text, highlightRanges).forEach { range ->
            previewHighlighters += editor.markupModel.addRangeHighlighter(
                range.startOffset,
                range.endOffset,
                HighlighterLayer.SELECTION - 1,
                textAttributes,
                HighlighterTargetArea.EXACT_RANGE,
            )
        }
    }

    private fun clearPreviewHighlighters() {
        previewHighlighters.forEach(editor.markupModel::removeHighlighter)
        previewHighlighters.clear()
    }
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

private fun lineStartOffsets(text: String): List<Int> {
    val offsets = mutableListOf(0)
    text.forEachIndexed { index, char ->
        if (char == '\n' && index + 1 < text.length) {
            offsets += index + 1
        }
    }
    return offsets
}
