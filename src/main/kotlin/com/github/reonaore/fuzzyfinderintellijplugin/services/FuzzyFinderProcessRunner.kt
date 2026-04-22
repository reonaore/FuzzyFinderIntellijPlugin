package com.github.reonaore.fuzzyfinderintellijplugin.services

import com.github.reonaore.fuzzyfinderintellijplugin.MyBundle
import com.intellij.execution.configurations.GeneralCommandLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.*

interface CommandRunner {
    suspend fun run(
        command: CommandSpec,
        stdin: ByteArray? = null,
        noMatchExitCodes: Set<Int> = emptySet(),
    ): ByteArray
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
    }
}
