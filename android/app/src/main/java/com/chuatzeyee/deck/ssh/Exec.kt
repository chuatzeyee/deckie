package com.chuatzeyee.deck.ssh

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** The result of one [Exec.run]: process exit code plus captured stdout/stderr, decoded as UTF-8. */
data class ExecResult(val exit: Int, val stdout: String, val stderr: String)

/**
 * Runs one shell command to completion over SSH. Each call opens its own channel: [stdin] (if
 * any) is written then the stream is closed (sending EOF) before stdout/stderr are read in full.
 */
fun interface Exec {
    suspend fun run(command: String, stdin: ByteArray?): ExecResult
}

/**
 * A long-lived interactive channel with a PTY attached: [output] is the command's combined
 * terminal output, [input] sends keystrokes, and [resize] tells the remote PTY the view changed.
 */
interface Pty : Closeable {
    val output: InputStream
    val input: OutputStream
    fun resize(cols: Int, rows: Int)
}
