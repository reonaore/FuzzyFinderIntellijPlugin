package com.github.reonaore.fuzzyfinderintellijplugin.tabfinder

import com.github.reonaore.fuzzyfinderintellijplugin.services.TextRange
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

data class OpenTabCandidate(
    val file: VirtualFile,
    val fileName: String,
    val secondaryPath: String?,
    val displayText: String,
)

data class TabListItem(
    val candidate: OpenTabCandidate,
    val highlightRanges: List<TextRange>,
)

internal fun VirtualFile.toOpenTabCandidate(basePath: String?): OpenTabCandidate {
    val relativeDisplayPath = relativeDisplayPath(basePath)
    val fileName = name.ifBlank { relativeDisplayPath }
    val secondaryPath = relativeDisplayPath
        .substringBeforeLast('/', missingDelimiterValue = "")
        .ifBlank { null }

    return OpenTabCandidate(
        file = this,
        fileName = fileName,
        secondaryPath = secondaryPath,
        displayText = listOf(fileName, relativeDisplayPath)
            .filter(String::isNotBlank)
            .distinct()
            .joinToString(" "),
    )
}

private fun VirtualFile.relativeDisplayPath(basePath: String?): String {
    val presentable = presentableUrl.ifBlank { path }
    if (basePath == null) {
        return presentable
    }

    return runCatching {
        Path.of(basePath)
            .relativize(Path.of(path))
            .toString()
            .replace('\\', '/')
    }.getOrDefault(presentable)
}
