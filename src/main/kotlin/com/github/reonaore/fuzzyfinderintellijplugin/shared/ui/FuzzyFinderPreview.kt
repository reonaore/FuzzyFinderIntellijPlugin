package com.github.reonaore.fuzzyfinderintellijplugin.shared.ui

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.services.PreviewHighlightRange
import com.github.reonaore.fuzzyfinderintellijplugin.services.PreviewLineHighlight
import com.github.reonaore.fuzzyfinderintellijplugin.services.PreviewLineHighlightKind
import com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
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
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Rectangle

class FuzzyFinderPreview(
    private val project: Project,
) {
    private val document = EditorFactory.getInstance().createDocument(MyBundle.message("dialog.preview.empty"))
    private val previewHighlighters = mutableListOf<RangeHighlighter>()
    private var currentPreviewText = document.text
    private var currentHighlightRanges = emptyList<PreviewHighlightRange>()
    private var currentLineHighlights = emptyList<PreviewLineHighlight>()
    private var isDisposed = false
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
    private val colorSchemeConnection = ApplicationManager.getApplication().messageBus.connect().apply {
        subscribe(EditorColorsManager.TOPIC, EditorColorsListener {
            ApplicationManager.getApplication().invokeLater(
                {
                    if (!isDisposed) {
                        refreshPreviewHighlighters()
                    }
                },
                ModalityState.any(),
            )
        })
    }
    private val selectedLineAccentRenderer = object : LineMarkerRenderer {
        override fun paint(editor: Editor, graphics: Graphics, rectangle: Rectangle) {
            graphics.color = selectedLineAccentColor()
            graphics.fillRect(rectangle.x, rectangle.y, SELECTED_LINE_ACCENT_WIDTH, rectangle.height)
        }
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
            editor.highlighter = highlighter
            editor.caretModel.moveToOffset(0)
            editor.scrollingModel.scrollVertically(0)
            document.setText(normalizedText)
            currentPreviewText = normalizedText
            currentHighlightRanges = highlightRanges
            currentLineHighlights = lineHighlights
            refreshPreviewHighlighters()
            scrollToLine?.let { line ->
                val lineIndex = (line - 1).coerceIn(0, document.lineCount - 1)
                editor.caretModel.moveToLogicalPosition(com.intellij.openapi.editor.LogicalPosition(lineIndex, 0))
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            }
        }
    }

    fun dispose() {
        isDisposed = true
        colorSchemeConnection.disconnect()
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
                highlighter.setLineMarkerRenderer(selectedLineAccentRenderer)
            }
            previewHighlighters += highlighter
        }
    }

    private fun addPreviewHighlighters(text: String, highlightRanges: List<PreviewHighlightRange>) {
        val scheme = editor.colorsScheme
        val textAttributes = previewSearchTextAttributes(
            scheme.getAttributes(EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES),
            scheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES),
            scheme.getColor(EditorColors.SELECTION_FOREGROUND_COLOR),
            scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR),
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
        val scheme = editor.colorsScheme
        val background = when (kind) {
            PreviewLineHighlightKind.MATCH -> relatedLineBackground(scheme)
            PreviewLineHighlightKind.SELECTED -> scheme.getColor(EditorColors.CARET_ROW_COLOR)
                ?: scheme.defaultBackground
        }
        return TextAttributes(null, background, null, null, Font.PLAIN)
    }

    private fun relatedLineBackground(scheme: EditorColorsScheme): Color {
        val searchBackground = scheme.getAttributes(EditorColors.TEXT_SEARCH_RESULT_ATTRIBUTES)?.backgroundColor
            ?: scheme.getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)?.backgroundColor
            ?: scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR)
            ?: scheme.defaultBackground
        return blendColors(scheme.defaultBackground, searchBackground, RELATED_LINE_HIGHLIGHT_OPACITY)
    }

    private fun selectedLineAccentColor(): Color {
        val scheme = editor.colorsScheme
        return scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR)
            ?: scheme.getColor(EditorColors.CARET_COLOR)
            ?: scheme.defaultForeground
    }

    private fun refreshPreviewHighlighters() {
        clearPreviewHighlighters()
        addPreviewLineHighlighters(currentLineHighlights)
        addPreviewHighlighters(currentPreviewText, currentHighlightRanges)
    }

    private fun clearPreviewHighlighters() {
        previewHighlighters.forEach(editor.markupModel::removeHighlighter)
        previewHighlighters.clear()
    }

    private companion object {
        const val WORD_HIGHLIGHT_LAYER = HighlighterLayer.SELECTION - 1
        const val SELECTED_LINE_HIGHLIGHT_LAYER = HighlighterLayer.SELECTION - 3
        const val MATCH_LINE_HIGHLIGHT_LAYER = HighlighterLayer.SELECTION - 4
        const val RELATED_LINE_HIGHLIGHT_OPACITY = 0.18f
    }
}

private const val SELECTED_LINE_ACCENT_WIDTH = 3

internal fun previewSearchTextAttributes(
    preferred: TextAttributes?,
    fallback: TextAttributes?,
    selectionForeground: Color?,
    selectionBackground: Color?,
): TextAttributes {
    return preferred?.clone()
        ?: fallback?.clone()
        ?: TextAttributes(selectionForeground, selectionBackground, null, null, Font.PLAIN)
}

internal fun blendColors(background: Color, foreground: Color, foregroundOpacity: Float): Color {
    val opacity = foregroundOpacity.coerceIn(0f, 1f)
    val backgroundOpacity = 1f - opacity
    return Color(
        (background.red * backgroundOpacity + foreground.red * opacity).toInt(),
        (background.green * backgroundOpacity + foreground.green * opacity).toInt(),
        (background.blue * backgroundOpacity + foreground.blue * opacity).toInt(),
    )
}

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
