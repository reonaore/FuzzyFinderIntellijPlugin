package com.github.reonaore.fuzzyfinderintellijplugin.services

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.github.reonaore.fuzzyfinderintellijplugin.settings.FuzzyFinderSettingsService
import com.github.reonaore.fuzzyfinderintellijplugin.settings.SupportedCommand
import com.github.reonaore.fuzzyfinderintellijplugin.util.FuzzyFinderParsers
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class FuzzyFinderService(
    private val project: Project,
    private val cs: CoroutineScope,
) : Disposable {

    private val cachedCandidates = ConcurrentHashMap<FdSearchOptions, List<Path>>()
    private val inFlightCandidates = ConcurrentHashMap<FdSearchOptions, CompletableDeferred<List<Path>>>()
    private val settingsService: FuzzyFinderSettingsService
        get() = ApplicationManager.getApplication().getService(FuzzyFinderSettingsService::class.java)

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any(::affectsSearchRoots)) {
                        invalidateCaches()
                    }
                }
            },
        )
    }

    override fun dispose() = Unit

    fun getCachedCandidates(options: FdSearchOptions): List<Path>? = cachedCandidates[options]

    fun invalidateCaches() {
        cachedCandidates.clear()
        inFlightCandidates.clear()
    }

    suspend fun ensureCandidates(options: FdSearchOptions): List<Path> {
        cachedCandidates[options]?.let { return it }
        val deferred = inFlightCandidates.computeIfAbsent(options) {
            CompletableDeferred<List<Path>>().also { future ->
                cs.async {
                    runCatching { discoverCandidates(options) }
                        .onSuccess { future.complete(it) }
                        .onFailure { future.completeExceptionally(it) }
                }
            }
        }

        return try {
            deferred.await().also { cachedCandidates[options] = it }
        } catch (error: Throwable) {
            throw unwrapCandidateError(error)
        } finally {
            if (deferred.isCompleted) {
                inFlightCandidates.remove(options, deferred)
            }
        }
    }

    suspend fun search(query: String, options: FdSearchOptions, limit: Int = MAX_RESULTS): SearchResult {
        val candidates = ensureCandidates(options)
        val results = if (query.isBlank()) {
            candidates.take(limit)
        } else {
            val stdout = runProcess(
                fzfCommandLine(query),
                stdin = FuzzyFinderParsers.toNulSeparatedBytes(candidates),
            )

            FuzzyFinderParsers.parseNulSeparatedPaths(stdout).take(limit)
        }

        return SearchResult(totalCandidates = candidates.size, results = results)
    }

    fun resolveSearchRoots(): List<Path> {
        val roots = ProjectRootManager.getInstance(project)
            .contentRoots
            .asSequence()
            .mapNotNull { virtualFile ->
                runCatching { Paths.get(virtualFile.path).normalize() }.getOrNull()
            }
            .toMutableList()

        project.basePath
            ?.let { runCatching { Paths.get(it).normalize() }.getOrNull() }
            ?.let(roots::add)

        return roots.distinct()
    }

    suspend fun streamCandidates(
        options: FdSearchOptions,
        onBatch: suspend (List<Path>, Int) -> Unit,
    ): List<Path> {
        cachedCandidates[options]?.let {
            onBatch(it, it.size)
            return it
        }

        val deferred = CompletableDeferred<List<Path>>()
        val existing = inFlightCandidates.putIfAbsent(options, deferred)
        if (existing != null) {
            return try {
                existing.await().also { onBatch(it, it.size) }
            } catch (error: Throwable) {
                throw unwrapCandidateError(error)
            }
        }

        return try {
            val result = discoverCandidatesStreaming(options, onBatch)
            cachedCandidates[options] = result
            deferred.complete(result)
            result
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
            throw error
        } finally {
            inFlightCandidates.remove(options, deferred)
        }
    }

    fun notifyError(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Fuzzy Finder Notifications")
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }

    private suspend fun discoverCandidates(options: FdSearchOptions): List<Path> {
        val seen = LinkedHashSet<Path>()
        for (root in resolveSearchRootsOrThrow()) {
            val stdout = runProcess(fdCommandLine(options, root))
            FuzzyFinderParsers.parseNulSeparatedPaths(stdout)
                .mapTo(seen) { normalizeCandidatePath(it, root) }
        }
        return seen.toList()
    }

    private suspend fun discoverCandidatesStreaming(
        options: FdSearchOptions,
        onBatch: suspend (List<Path>, Int) -> Unit,
    ): List<Path> = withContext(Dispatchers.IO) {
        val paths = mutableListOf<Path>()
        val batch = mutableListOf<Path>()
        val seen = LinkedHashSet<Path>()

        for (root in resolveSearchRootsOrThrow()) {
            currentCoroutineContext().ensureActive()
            val commandLine = fdCommandLine(options, root)
            val process = createProcess(commandLine)
            val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }
            val stderrReader = cs.async(Dispatchers.IO) {
                process.errorStream.bufferedReader().use { it.readText() }
            }

            try {
                process.inputStream.use { input ->
                    readNulSeparatedPaths(input) { path ->
                        currentCoroutineContext().ensureActive()
                        val normalized = normalizeCandidatePath(path, root)
                        if (!seen.add(normalized)) {
                            return@readNulSeparatedPaths
                        }

                        paths.add(normalized)
                        batch.add(normalized)
                        if (batch.size >= STREAM_BATCH_SIZE) {
                            onBatch(batch.toList(), paths.size)
                            batch.clear()
                        }
                    }
                }

                if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    throw FuzzyFinderException(MyBundle.message("error.commandTimedOut", commandLine.commandLineString))
                }

                checkExitCode(commandLine, process.exitValue(), stderrReader.await())
            } finally {
                cancellationHandle?.dispose()
            }
        }

        if (batch.isNotEmpty()) {
            onBatch(batch.toList(), paths.size)
        }

        paths
    }

    private suspend fun readNulSeparatedPaths(input: InputStream, onPath: suspend (Path) -> Unit) {
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

    private suspend fun runProcess(commandLine: GeneralCommandLine, stdin: ByteArray? = null): ByteArray =
        withContext(Dispatchers.IO) {
        val process = createProcess(commandLine)
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }

        try {
            coroutineScope {
                val stdoutReader = async(Dispatchers.IO) {
                    process.inputStream.use { it.readAllBytes() }
                }
                val stderrReader = async(Dispatchers.IO) {
                    process.errorStream.bufferedReader().use { it.readText() }
                }

                process.outputStream.use { output ->
                    if (stdin != null) {
                        output.write(stdin)
                    }
                }

                if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    throw FuzzyFinderException(MyBundle.message("error.commandTimedOut", commandLine.commandLineString))
                }

                val exitCode = process.exitValue()
                val stdout = stdoutReader.await()
                val stderr = stderrReader.await()

                if (commandLine.exePath == settingsService.executablePath(SupportedCommand.FZF) && exitCode == 1) {
                    return@coroutineScope ByteArray(0)
                }
                checkExitCode(commandLine, exitCode, stderr)

                stdout
            }
        } finally {
            cancellationHandle?.dispose()
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

    private fun resolveSearchRootsOrThrow(): List<Path> {
        val roots = resolveSearchRoots()
        if (roots.isNotEmpty()) {
            return roots
        }

        throw FuzzyFinderException(MyBundle.message("error.projectRootUnavailable"))
    }

    private fun normalizeCandidatePath(path: Path, root: Path): Path {
        return if (path.isAbsolute) {
            path.normalize()
        } else {
            root.resolve(path).normalize()
        }
    }

    private fun affectsSearchRoots(event: VFileEvent): Boolean {
        val eventPath = runCatching { Paths.get(event.path).normalize() }.getOrNull() ?: return false
        return resolveSearchRoots().any { root ->
            eventPath == root || eventPath.startsWith(root) || root.startsWith(eventPath)
        }
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

private fun unwrapCandidateError(error: Throwable): RuntimeException {
    val cause = error.cause
    return when (cause) {
        is FuzzyFinderException -> cause
        is CancellationException -> FuzzyFinderException("Candidate loading was cancelled.", cause)
        is RuntimeException -> cause
        else -> FuzzyFinderException(cause?.message ?: error.message ?: "Candidate loading failed.", cause ?: error)
    }
}
