package com.github.reonaore.fuzzyfinderintellijplugin.services

import com.github.reonaore.fuzzyfinderintellijplugin.settings.FuzzyFinderSettingsService
import com.github.reonaore.fuzzyfinderintellijplugin.settings.SupportedCommand
import com.github.reonaore.fuzzyfinderintellijplugin.util.FuzzyFinderParsers
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class FuzzyFinderService(private val project: Project) {

    private val cachedCandidates = ConcurrentHashMap<FdSearchOptions, List<Path>>()
    private val inFlightCandidates = ConcurrentHashMap<FdSearchOptions, CompletableFuture<List<Path>>>()
    private val settingsService: FuzzyFinderSettingsService
        get() = ApplicationManager.getApplication().getService(FuzzyFinderSettingsService::class.java)

    fun getCachedCandidates(options: FdSearchOptions): List<Path>? = cachedCandidates[options]

    fun ensureCandidates(options: FdSearchOptions): List<Path> {
        cachedCandidates[options]?.let { return it }
        val future = inFlightCandidates.computeIfAbsent(options) {
            CompletableFuture.supplyAsync {
                discoverCandidates(options)
            }
        }

        return try {
            future.get().also { cachedCandidates[options] = it }
        } catch (error: Exception) {
            throw unwrapCandidateError(error)
        } finally {
            if (future.isDone) {
                inFlightCandidates.remove(options, future)
            }
        }
    }

    fun search(query: String, options: FdSearchOptions, limit: Int = MAX_RESULTS): SearchResult {
        val candidates = ensureCandidates(options)
        val results = if (query.isBlank()) {
            candidates.take(limit)
        } else {
            val stdout = runProcess(
                GeneralCommandLine(settingsService.executablePath(SupportedCommand.FZF))
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                    .withParameters("--filter", query, "--scheme=path", "--read0", "--print0"),
                stdin = FuzzyFinderParsers.toNulSeparatedBytes(candidates),
            )

            FuzzyFinderParsers.parseNulSeparatedPaths(stdout).take(limit)
        }

        return SearchResult(totalCandidates = candidates.size, results = results)
    }

    fun resolveSearchRoot(): Path? = project.basePath?.let(Paths::get)

    fun streamCandidates(options: FdSearchOptions, onBatch: (List<Path>, Int) -> Unit): List<Path> {
        cachedCandidates[options]?.let {
            onBatch(it, it.size)
            return it
        }

        val future = CompletableFuture<List<Path>>()
        val existing = inFlightCandidates.putIfAbsent(options, future)
        if (existing != null) {
            return try {
                existing.get().also { onBatch(it, it.size) }
            } catch (error: Exception) {
                throw unwrapCandidateError(error)
            }
        }

        return try {
            val result = discoverCandidatesStreaming(options, onBatch)
            cachedCandidates[options] = result
            future.complete(result)
            result
        } catch (error: Exception) {
            future.completeExceptionally(error)
            throw error
        } finally {
            inFlightCandidates.remove(options, future)
        }
    }

    fun notifyError(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Fuzzy Finder Notifications")
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }

    private fun discoverCandidates(options: FdSearchOptions): List<Path> {
        val root = resolveSearchRoot()
            ?: throw FuzzyFinderException("Project root is unavailable.")

        val stdout = runProcess(
            GeneralCommandLine(settingsService.executablePath(SupportedCommand.FD))
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .withParameters(buildFdParameters(options, root.toString())),
        )

        return FuzzyFinderParsers.parseNulSeparatedPaths(stdout)
    }

    private fun discoverCandidatesStreaming(
        options: FdSearchOptions,
        onBatch: (List<Path>, Int) -> Unit,
    ): List<Path> {
        val root = resolveSearchRoot()
            ?: throw FuzzyFinderException("Project root is unavailable.")

        val commandLine = GeneralCommandLine(settingsService.executablePath(SupportedCommand.FD))
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withParameters(buildFdParameters(options, root.toString()))

        val process = try {
            commandLine.createProcess()
        } catch (error: IOException) {
            throw FuzzyFinderException(
                "Failed to launch '${commandLine.exePath}': ${error.message.orEmpty()}",
                error,
            )
        }

        val stderrReader = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().use { it.readText() }
        }

        val paths = mutableListOf<Path>()
        val batch = mutableListOf<Path>()

        process.inputStream.use { input ->
            readNulSeparatedPaths(input) { path ->
                paths.add(path)
                batch.add(path)
                if (batch.size >= STREAM_BATCH_SIZE) {
                    onBatch(batch.toList(), paths.size)
                    batch.clear()
                }
            }
        }

        if (batch.isNotEmpty()) {
            onBatch(batch.toList(), paths.size)
        }

        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw FuzzyFinderException("Command timed out: ${commandLine.commandLineString}")
        }

        val exitCode = process.exitValue()
        val stderr = stderrReader.get()
        if (exitCode != 0) {
            val stderrText = stderr.ifBlank { "No error output was produced." }
            throw FuzzyFinderException(
                "Command failed: ${commandLine.commandLineString} (exit code $exitCode). $stderrText",
            )
        }

        return paths
    }

    private fun readNulSeparatedPaths(input: InputStream, onPath: (Path) -> Unit) {
        val chunk = ByteArray(STREAM_READ_BUFFER_SIZE)
        val current = java.io.ByteArrayOutputStream()

        while (true) {
            val read = input.read(chunk)
            if (read <= 0) break

            for (index in 0 until read) {
                val byte = chunk[index]
                if (byte.toInt() == 0) {
                    if (current.size() > 0) {
                        onPath(Paths.get(current.toString(StandardCharsets.UTF_8)))
                        current.reset()
                    }
                } else {
                    current.write(byte.toInt())
                }
            }
        }

        if (current.size() > 0) {
            onPath(Paths.get(current.toString(StandardCharsets.UTF_8)))
        }
    }

    private fun runProcess(commandLine: GeneralCommandLine, stdin: ByteArray? = null): ByteArray {
        val process = try {
            commandLine.createProcess()
        } catch (error: IOException) {
            throw FuzzyFinderException(
                "Failed to launch '${commandLine.exePath}': ${error.message.orEmpty()}",
                error,
            )
        }

        val stdoutReader = CompletableFuture.supplyAsync {
            process.inputStream.use { it.readAllBytes() }
        }
        val stderrReader = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().use { it.readText() }
        }

        process.outputStream.use { output ->
            if (stdin != null) {
                output.write(stdin)
            }
        }

        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw FuzzyFinderException("Command timed out: ${commandLine.commandLineString}")
        }

        val exitCode = process.exitValue()
        val stdout = stdoutReader.get()
        val stderr = stderrReader.get()

        if (exitCode != 0) {
            if (commandLine.exePath == "fzf" && exitCode == 1) {
                return ByteArray(0)
            }

            val stderrText = stderr.ifBlank { "No error output was produced." }
            throw FuzzyFinderException(
                "Command failed: ${commandLine.commandLineString} (exit code $exitCode). $stderrText",
            )
        }

        return stdout
    }

    private companion object {
        const val MAX_RESULTS = 200
        const val PROCESS_TIMEOUT_SECONDS = 15L
        const val STREAM_BATCH_SIZE = 100
        const val STREAM_READ_BUFFER_SIZE = 8192
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

internal fun buildFdParameters(options: FdSearchOptions, root: String): List<String> {
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

    parameters += listOf("--print0", ".", root)
    return parameters
}

private fun unwrapCandidateError(error: Exception): RuntimeException {
    val cause = error.cause
    return when (cause) {
        is FuzzyFinderException -> cause
        is CancellationException -> FuzzyFinderException("Candidate loading was cancelled.", cause)
        is RuntimeException -> cause
        else -> FuzzyFinderException(cause?.message ?: error.message ?: "Candidate loading failed.", cause ?: error)
    }
}
