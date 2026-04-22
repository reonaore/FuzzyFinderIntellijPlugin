package com.github.reonaore.fuzzyfinderintellijplugin.services

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class IntellijCommandRunnerTest {

    @Test
    fun returnsStdoutWhenProcessSucceeds() {
        val process = FakeProcess(
            stdout = "result".toByteArray(),
            stderr = ByteArray(0),
            exitCode = 0,
            waitResult = true,
        )
        val runner = IntellijCommandRunner(processFactory = { process })

        val stdout = kotlinx.coroutines.runBlocking {
            runner.run(
                command = CommandSpec("fd", listOf("--print0")),
                stdin = "input".toByteArray(),
            )
        }

        assertArrayEquals("result".toByteArray(), stdout)
        assertArrayEquals("input".toByteArray(), process.stdin.toByteArray())
    }

    @Test
    fun returnsEmptyBytesForConfiguredNoMatchExitCode() {
        val process = FakeProcess(
            stdout = "ignored".toByteArray(),
            stderr = ByteArray(0),
            exitCode = 1,
            waitResult = true,
        )
        val runner = IntellijCommandRunner(processFactory = { process })

        val stdout = kotlinx.coroutines.runBlocking {
            runner.run(
                command = CommandSpec("fzf", listOf("--filter", "x")),
                noMatchExitCodes = setOf(1),
            )
        }

        assertArrayEquals(ByteArray(0), stdout)
    }

    @Test
    fun throwsWhenProcessTimesOut() {
        val process = FakeProcess(
            stdout = ByteArray(0),
            stderr = ByteArray(0),
            exitCode = 0,
            waitResult = false,
        )
        val runner = IntellijCommandRunner(timeoutSeconds = 1, processFactory = { process })

        val error = captureFailure {
            kotlinx.coroutines.runBlocking {
                runner.run(CommandSpec("fd", listOf("--print0")))
            }
        }

        assertTrue(error.message.orEmpty().contains("timed out"))
        assertTrue(process.destroyed.get())
    }

    @Test
    fun throwsWhenProcessFailsAndIncludesStderr() {
        val process = FakeProcess(
            stdout = ByteArray(0),
            stderr = "boom".toByteArray(),
            exitCode = 2,
            waitResult = true,
        )
        val runner = IntellijCommandRunner(processFactory = { process })

        val error = captureFailure {
            kotlinx.coroutines.runBlocking {
                runner.run(CommandSpec("fd", listOf("--print0")))
            }
        }

        assertTrue(error.message.orEmpty().contains("boom"))
    }

    private fun captureFailure(block: () -> Unit): FuzzyFinderException {
        try {
            block()
        } catch (error: FuzzyFinderException) {
            return error
        }
        throw AssertionError("Expected FuzzyFinderException")
    }

    private class FakeProcess(
        private val stdout: ByteArray,
        private val stderr: ByteArray,
        private val exitCode: Int,
        private val waitResult: Boolean,
    ) : Process() {
        val stdin = ByteArrayOutputStream()
        val destroyed = AtomicBoolean(false)

        override fun getOutputStream(): OutputStream = stdin

        override fun getInputStream() = ByteArrayInputStream(stdout)

        override fun getErrorStream() = ByteArrayInputStream(stderr)

        override fun waitFor(): Int = exitCode

        override fun waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean = waitResult

        override fun exitValue(): Int = exitCode

        override fun destroy() {
            destroyed.set(true)
        }

        override fun destroyForcibly(): Process {
            destroyed.set(true)
            return this
        }

        override fun isAlive(): Boolean = !destroyed.get() && !waitResult
    }
}
