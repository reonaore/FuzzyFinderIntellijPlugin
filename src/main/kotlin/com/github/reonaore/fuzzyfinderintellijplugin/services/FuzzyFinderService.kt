package com.github.reonaore.fuzzyfinderintellijplugin.services

import com.github.reonaore.fuzzyfinderintellijplugin.util.FuzzyFinderParsers
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class FuzzyFinderService(private val project: Project) {

    @Volatile
    private var cachedCandidates: List<Path>? = null

    fun ensureCandidates(): List<Path> {
        cachedCandidates?.let { return it }
        synchronized(this) {
            cachedCandidates?.let { return it }
            return discoverCandidates().also { cachedCandidates = it }
        }
    }

    fun filterCandidates(query: String, limit: Int = MAX_RESULTS): List<Path> {
        val candidates = ensureCandidates()
        if (query.isBlank()) {
            return candidates.take(limit)
        }

        val stdout = runProcess(
            GeneralCommandLine("fzf")
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .withParameters("--filter", query, "--scheme=path", "--read0", "--print0"),
            stdin = FuzzyFinderParsers.toNulSeparatedBytes(candidates),
        )

        return FuzzyFinderParsers.parseNulSeparatedPaths(stdout).take(limit)
    }

    fun resolveSearchRoot(): Path? = project.basePath?.let(Paths::get)

    fun notifyError(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Fuzzy Finder Notifications")
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    }

    private fun discoverCandidates(): List<Path> {
        val root = resolveSearchRoot()
            ?: throw FuzzyFinderException("Project root is unavailable.")

        val stdout = runProcess(
            GeneralCommandLine("fd")
                .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                .withParameters(
                    "--type", "f",
                    "--absolute-path",
                    "--hidden",
                    "--follow",
                    "--exclude", ".git",
                    "--print0",
                    ".",
                    root.toString(),
                ),
        )

        return FuzzyFinderParsers.parseNulSeparatedPaths(stdout)
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
    }
}

class FuzzyFinderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
