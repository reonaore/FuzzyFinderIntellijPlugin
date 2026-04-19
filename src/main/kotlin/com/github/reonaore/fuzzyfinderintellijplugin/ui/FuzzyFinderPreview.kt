package com.github.reonaore.fuzzyfinderintellijplugin.ui

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project

class FuzzyFinderPreview(
    private val project: Project,
) {
    val document = EditorFactory.getInstance().createDocument(MyBundle.message("dialog.preview.empty"))
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

    suspend fun show(content: PreviewContent) {
        val (text, virtualFile) = content
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
            document.setText(text)
        }
    }

    fun dispose() {
        EditorFactory.getInstance().releaseEditor(editor)
    }
}
