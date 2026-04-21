package com.github.reonaore.fuzzyfinderintellijplugin.services

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.settings.FuzzyFinderSettingsService
import com.github.reonaore.fuzzyfinderintellijplugin.settings.SupportedCommand
import com.github.reonaore.fuzzyfinderintellijplugin.util.FuzzyFinderParsers
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.*

@Service(Service.Level.PROJECT)
class FuzzyFinderService(
    private val project: Project,
    private val cs: CoroutineScope,
) : Disposable {

    private val settingsService: FuzzyFinderSettingsService
        get() = ApplicationManager.getApplication().getService(FuzzyFinderSettingsService::class.java)

    init {
        project.messageBus.connect(this)
    }

    override fun dispose() = Unit

    suspend fun search(query: String, options: FdSearchOptions, limit: Int = MAX_RESULTS): SearchResult {
        val candidates = discoverCandidates(options)
        val stdout = runProcess(
            fzfCommandLine(query),
            candidates,
        )

        val totalCount = FuzzyFinderParsers.parseNulSeparatedPaths(candidates).size
        val results = FuzzyFinderParsers.parseNulSeparatedPaths(stdout).take(limit)

        return SearchResult(totalCandidates = totalCount, results = results)
    }

    fun notifyError(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Fuzzy Finder Notifications")
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }

    private suspend fun discoverCandidates(options: FdSearchOptions): ByteArray {
        if (project.basePath == null) {
            return byteArrayOf()
        }
        val root = Path.of(project.basePath!!)
        return runProcess(fdCommandLine(options, root))
    }


    private suspend fun runProcess(commandLine: GeneralCommandLine, stdin: ByteArray? = null): ByteArray =
        withContext(Dispatchers.IO) {
            val process = createProcess(commandLine)
            try {
                val stdout = async { process.inputStream.use { it.readAllBytes() } }
                val stderr = async { process.errorStream.bufferedReader().use { it.readText() } }
                process.outputStream.use { output ->
                    stdin?.run { output.write(stdin) }
                }
                val processIsSuccess = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)

                if (!processIsSuccess) {
                    throw FuzzyFinderException(
                        MyBundle.message(
                            "error.commandTimedOut",
                            commandLine.commandLineString
                        )
                    )
                }

                val exitCode = process.exitValue()

                if (commandLine.exePath == settingsService.executablePath(SupportedCommand.FZF) && exitCode == 1) {
                    return@withContext ByteArray(0)
                }
                checkExitCode(commandLine, exitCode, stderr.await())
                return@withContext stdout.await()

            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }
        }

    private fun fdCommandLine(options: FdSearchOptions, root: Path): GeneralCommandLine {
        return GeneralCommandLine(settingsService.executablePath(SupportedCommand.FD))
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withParameters(buildFdParameters(options, root))
    }

    private fun fzfCommandLine(query: String): GeneralCommandLine {
        return GeneralCommandLine(settingsService.executablePath(SupportedCommand.FZF))
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withParameters("--filter", query, "--scheme=path", "--read0", "--print0")
    }

    private fun createProcess(commandLine: GeneralCommandLine): Process {
        return try {
            commandLine.createProcess()
        } catch (error: IOException) {
            throw FuzzyFinderException(
                MyBundle.message("error.commandLaunchFailed", commandLine.exePath, error.message.orEmpty()),
                error,
            )
        }
    }

    private fun checkExitCode(commandLine: GeneralCommandLine, exitCode: Int, stderr: String) {
        if (exitCode == 0) return

        val stderrText = stderr.ifBlank { MyBundle.message("error.noCommandOutput") }
        throw FuzzyFinderException(
            MyBundle.message("error.commandFailed", commandLine.commandLineString, exitCode, stderrText),
        )
    }

    private companion object {
        const val MAX_RESULTS = 200
        const val PROCESS_TIMEOUT_SECONDS = 15L
    }
}

class FuzzyFinderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class SearchResult(
    val totalCandidates: Int,
    val results: List<Path>,
)

data class FdSearchOptions(
    val entryType: FdEntryType = FdEntryType.FILES,
    val includeHidden: Boolean = false,
    val followSymlinks: Boolean = true,
    val respectGitIgnore: Boolean = true,
    val excludePatterns: List<String> = listOf(".git"),
)

enum class FdEntryType(val presentableName: String, val fdValue: String?) {
    ANY("Any", null),
    FILES("Files", "f"),
    DIRECTORIES("Directories", "d"),
    SYMLINKS("Symlinks", "l"),
    EXECUTABLES("Executables", "x"),
    EMPTY("Empty", "e");

    override fun toString(): String = presentableName
}

internal fun buildFdParameters(options: FdSearchOptions, root: Path): List<String> {
    val parameters = mutableListOf<String>()

    options.entryType.fdValue?.let { entryType ->
        parameters += listOf("--type", entryType)
    }
    parameters += "--absolute-path"
    if (options.includeHidden) {
        parameters += "--hidden"
    }
    if (options.followSymlinks) {
        parameters += "--follow"
    }
    if (!options.respectGitIgnore) {
        parameters += "--no-ignore"
    }
    options.excludePatterns
        .map(String::trim)
        .filter(String::isNotEmpty)
        .forEach { pattern ->
            parameters += listOf("--exclude", pattern)
        }

    parameters += listOf("--print0", ".", root.toString())
    return parameters
}
