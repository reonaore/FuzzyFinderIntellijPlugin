package com.github.reonaore.fuzzyfinderintellijplugin.shared.ui

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.util.FuzzyFinderParsers
import com.intellij.openapi.application.readAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path

class FuzzyFinderPreviewLoader {

    suspend fun load(path: Path): PreviewContent {
        val virtualFile = readAction {
            LocalFileSystem.getInstance().findFileByNioFile(path)
        } ?: return PreviewContent(MyBundle.message("dialog.preview.missing"), null)
        if (virtualFile.isDirectory) {
            return PreviewContent(MyBundle.message("dialog.preview.directory"), null)
        }

        if (virtualFile.fileType.isBinary) {
            return PreviewContent(MyBundle.message("dialog.preview.binary"), null)
        }

        return try {
            PreviewContent(readPreview(virtualFile), virtualFile)
        } catch (_: IOException) {
            PreviewContent(MyBundle.message("dialog.preview.missing"), null)
        }
    }

    private suspend fun readPreview(file: VirtualFile): String {
        return readAction {
            file.inputStream.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                    val builder = StringBuilder()
                    var totalChars = 0
                    val buffer = CharArray(READ_BUFFER_SIZE)

                    while (totalChars < PREVIEW_CHAR_LIMIT) {
                        val remaining = minOf(buffer.size, PREVIEW_CHAR_LIMIT - totalChars)
                        val read = reader.read(buffer, 0, remaining)
                        if (read <= 0) {
                            break
                        }
                        builder.appendRange(buffer, 0, read)
                        totalChars += read
                    }

                    FuzzyFinderParsers.appendPreviewSuffix(builder.toString(), totalChars >= PREVIEW_CHAR_LIMIT)
                }
            }
        }
    }

    companion object {
        private const val PREVIEW_CHAR_LIMIT = 12000
        private const val READ_BUFFER_SIZE = 2048
    }
}

data class PreviewContent(
    val text: String,
    val virtualFile: VirtualFile?,
) {
    companion object {
        val empty = PreviewContent(
            text = MyBundle.message("dialog.preview.empty"),
            virtualFile = null,
        )
    }
}
