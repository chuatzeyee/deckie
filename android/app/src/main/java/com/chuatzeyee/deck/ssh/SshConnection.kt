package com.chuatzeyee.deck.ssh

import com.chuatzeyee.deck.core.Host
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val CONNECT_TIMEOUT_MILLIS = 10_000
private const val KEEPALIVE_SECONDS = 15
private const val EXEC_TIMEOUT_SECONDS = 30L
private const val EXEC_TIMEOUT_MILLIS = EXEC_TIMEOUT_SECONDS * 1000
private const val PTY_TERM_TYPE = "xterm-256color"

/**
 * Thrown by [SshConnection.connect] when [Host.pinnedHostKey] is `null`: the server has never
 * been seen before. The caller is expected to show [fingerprint] (and [keyType], e.g. `ED25519`)
 * to the user, pin it via `HostRepository.pin` only if they accept it, then reconnect with the
 * updated [Host].
 */
class HostKeyFirstUse(val fingerprint: String, val keyType: String) :
    IOException("Host key not yet trusted; server presented $keyType fingerprint $fingerprint")

/**
 * Thrown by [SshConnection.connect] when the server's presented host key contradicts
 * [Host.pinnedHostKey] - [SshConnection] never auto-repins. [keyType] is the presented key's type.
 */
class HostKeyMismatch(val expected: String, val got: String, val keyType: String) :
    IOException("Host key mismatch: pinned $expected, server presented $keyType $got")

/** Thrown by [SshConnection.run] when a command doesn't complete within the per-run timeout. */
class SshExecTimeoutException(message: String) : IOException(message)

/** `SHA256:<base64, no padding>` of [key]'s wire-format blob - same format `ssh-keygen -lf` prints. */
internal fun sshFingerprint(key: PublicKey): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBlob(key))
    return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
}

private const val COMMAND_LABEL_MAX_CHARS = 40

/** First word of [command], length-capped - enough to identify it in a log/exception without
 * leaking a command's full argument text (which may contain secrets). */
private fun commandLabel(command: String): String =
    command.trim().substringBefore(' ').take(COMMAND_LABEL_MAX_CHARS)

/**
 * One SSH connection to [host], authenticating with [keys] and pinning its host key via
 * [checkPin]: [connect] rejects and throws [HostKeyFirstUse] the first time a host presents a key
 * (nothing is pinned without the caller's say-so), and throws [HostKeyMismatch] if the presented
 * key ever contradicts an already-pinned one. One sshj client per instance; [run] opens one exec
 * channel per call, always closed; [openPty] opens one long-lived channel.
 */
class SshConnection(
    private val host: Host,
    private val keys: KeyProvider,
) : Exec, Closeable {

    private val client = SSHClient(DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE })

    @Volatile private var connectAttempted = false

    val isConnected: Boolean
        get() = client.isConnected && client.isAuthenticated

    suspend fun connect(): Unit = withContext(Dispatchers.IO) {
        SshjSetup.ensure()
        check(!connectAttempted) { "connect() already called for ${host.name}" }
        connectAttempted = true

        client.connectTimeout = CONNECT_TIMEOUT_MILLIS
        var firstUseFingerprint: String? = null
        var mismatch: PinResult.Mismatch? = null
        var presentedKeyType: String? = null
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val presented = sshFingerprint(key)
                presentedKeyType = KeyType.fromKey(key).toString()
                return when (val result = checkPin(host.pinnedHostKey, presented)) {
                    PinResult.Match -> true
                    is PinResult.FirstUse -> {
                        firstUseFingerprint = result.fingerprint
                        false
                    }
                    is PinResult.Mismatch -> {
                        mismatch = result
                        false
                    }
                }
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })

        try {
            client.connect(host.address, host.port)
            client.connection.keepAlive.keepAliveInterval = KEEPALIVE_SECONDS
            client.authPublickey(host.user, keys)
        } catch (e: TransportException) {
            runCatching { client.disconnect() }
            if (e.disconnectReason != DisconnectReason.HOST_KEY_NOT_VERIFIABLE) throw e
            val type = presentedKeyType ?: "unknown"
            firstUseFingerprint?.let { throw HostKeyFirstUse(it, type) }
            mismatch?.let { throw HostKeyMismatch(it.expected, it.got, type) }
            throw e
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw e
        }
    }

    override suspend fun run(command: String, stdin: ByteArray?): ExecResult = withContext(Dispatchers.IO) {
        SshjSetup.ensure()
        val session = client.startSession()
        val cmd = try {
            session.exec(command)
        } catch (t: Throwable) {
            runCatching { session.close() }
            throw t
        }
        // Both the timeout/cancellation path (onCancel, firing on its own background thread - see
        // runBlockingCancellable) and this function's own finally block close the same cmd/session;
        // on every timed-out exec they race. closeOnce makes that race harmless by guaranteeing
        // the actual close logic runs exactly once, from whichever side gets there first.
        val closeChannel = closeOnce { runCatching { cmd.close() }; runCatching { session.close() } }
        try {
            // withTimeoutOrNull (not withTimeout/catch) so only *our own* timeout here turns into
            // SshExecTimeoutException - an outer caller's cancellation still propagates as plain
            // CancellationException instead of being misreported as a command timeout.
            withTimeoutOrNull(EXEC_TIMEOUT_MILLIS) { awaitExec(cmd, stdin, closeChannel) }
                ?: throw SshExecTimeoutException(
                    "Command timed out after ${EXEC_TIMEOUT_SECONDS}s: ${commandLabel(command)}",
                )
        } finally {
            closeChannel()
        }
    }

    private suspend fun awaitExec(cmd: Session.Command, stdin: ByteArray?, onCancel: () -> Unit): ExecResult =
        runBlockingCancellable(onCancel = onCancel) { blockingExec(cmd, stdin) }

    /**
     * Starts draining stdout/stderr before writing [stdin], so a large payload can't deadlock
     * against the channel's flow-control window while nothing is reading the other side yet.
     */
    private fun blockingExec(cmd: Session.Command, stdin: ByteArray?): ExecResult {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val stdoutReader = Thread({ runCatching { cmd.inputStream.copyTo(stdout) } }, "deck-ssh-stdout")
            .apply { isDaemon = true; start() }
        val stderrReader = Thread({ runCatching { cmd.errorStream.copyTo(stderr) } }, "deck-ssh-stderr")
            .apply { isDaemon = true; start() }
        if (stdin != null) cmd.outputStream.write(stdin)
        cmd.outputStream.close()
        stdoutReader.join()
        stderrReader.join()
        cmd.join(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return ExecResult(cmd.exitStatus ?: -1, stdout.toString(Charsets.UTF_8), stderr.toString(Charsets.UTF_8))
    }

    /** Opens a PTY-backed channel running [command] (via `exec`, not `startShell`). */
    suspend fun openPty(command: String, cols: Int, rows: Int): Pty = withContext(Dispatchers.IO) {
        SshjSetup.ensure()
        val session = client.startSession()
        try {
            session.allocatePTY(PTY_TERM_TYPE, cols, rows, 0, 0, emptyMap())
            SessionPty(session, session.exec(command))
        } catch (t: Throwable) {
            runCatching { session.close() }
            throw t
        }
    }

    override fun close() {
        runCatching { client.disconnect() }
    }
}

private class SessionPty(
    private val session: Session,
    private val command: Session.Command,
) : Pty {
    override val output: InputStream get() = command.inputStream
    override val input: OutputStream get() = command.outputStream

    // session.exec() and session.startShell() both return views of the same underlying
    // SessionChannel object (it implements Session.Command, Session.Shell and Session.Subsystem
    // simultaneously), and changeWindowDimensions is only declared on the Shell interface - this
    // cast is always valid for a channel that had allocatePTY called on it, regardless of which
    // view was returned.
    override fun resize(cols: Int, rows: Int) {
        (command as Session.Shell).changeWindowDimensions(cols, rows, 0, 0)
    }

    override fun close() {
        runCatching { command.close() }
        runCatching { session.close() }
    }
}
