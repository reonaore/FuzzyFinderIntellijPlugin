package com.github.reonaore.fuzzyfinderintellijplugin.services

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.intellij.execution.configurations.GeneralCommandLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong

interface CommandRunner {
    suspend fun run(
        command: CommandSpec,
        stdin: ByteArray? = null,
        noMatchExitCodes: Set<Int> = emptySet(),
    ): ByteArray

    fun streamLines(
        command: CommandSpec,
        stdin: ByteArray? = null,
        noMatchExitCodes: Set<Int> = emptySet(),
    ): Flow<String> = flow {
        run(command, stdin, noMatchExitCodes)
            .toString(StandardCharsets.UTF_8)
            .lineSequence()
            .forEach { line ->
                emit(line)
            }
    }
}

data class CommandSpec(
    val executable: String,
    val parameters: List<String>,
)

class IntellijCommandRunner(
    private val timeoutSeconds: Long = PROCESS_TIMEOUT_SECONDS,
    private val processFactory: (GeneralCommandLine) -> Process = { it.createProcess() },
) : CommandRunner {

    override suspend fun run(
        command: CommandSpec,
        stdin: ByteArray?,
        noMatchExitCodes: Set<Int>,
    ): ByteArray = withContext(Dispatchers.IO) {
        val commandLine = GeneralCommandLine(command.executable)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withParameters(command.parameters)

        val process = createProcess(commandLine)
        try {
            val stdout = async { process.inputStream.use { it.readAllBytes() } }
            val stderr = async { process.errorStream.bufferedReader().use { it.readText() } }
            process.outputStream.use { output ->
                stdin?.let(output::write)
            }

            val processFinished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!processFinished) {
                throw FuzzyFinderException(
                    MyBundle.message("error.commandTimedOut", commandLine.commandLineString),
                )
            }

            val exitCode = process.exitValue()
            if (exitCode in noMatchExitCodes) {
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

    override fun streamLines(
        command: CommandSpec,
        stdin: ByteArray?,
        noMatchExitCodes: Set<Int>,
    ): Flow<String> = channelFlow {
        val commandLine = GeneralCommandLine(command.executable)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withParameters(command.parameters)

        val process = createProcess(commandLine)
        val lastOutputAt = AtomicLong(System.nanoTime())
        val readerJob = async(Dispatchers.IO) {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    lastOutputAt.set(System.nanoTime())
                    trySend(line).getOrThrow()
                }
            }
        }
        val stderrJob = async(Dispatchers.IO) {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }
        val waitJob = async(Dispatchers.IO) {
            process.outputStream.use { output ->
                stdin?.let(output::write)
            }

            while (!process.waitFor(PROCESS_WAIT_POLL_MS, TimeUnit.MILLISECONDS)) {
                val idleNanos = System.nanoTime() - lastOutputAt.get()
                if (idleNanos > TimeUnit.SECONDS.toNanos(timeoutSeconds)) {
                    throw FuzzyFinderException(
                        MyBundle.message("error.commandTimedOut", commandLine.commandLineString),
                    )
                }
            }

            val exitCode = process.exitValue()
            if (exitCode !in noMatchExitCodes) {
                checkExitCode(commandLine, exitCode, stderrJob.await())
            }
            readerJob.await()
        }

        waitJob.invokeOnCompletion { error ->
            if (process.isAlive) {
                process.destroyForcibly()
            }
            error?.let(::close) ?: close()
        }
        awaitClose {
            readerJob.cancel()
            stderrJob.cancel()
            waitJob.cancel()
            if (process.isAlive) {
                process.destroyForcibly()
            }
        }
    }.buffer(Channel.UNLIMITED)

    private fun createProcess(commandLine: GeneralCommandLine): Process {
        return try {
            processFactory(commandLine)
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
        const val PROCESS_TIMEOUT_SECONDS = 15L
        const val PROCESS_WAIT_POLL_MS = 200L
    }
}
