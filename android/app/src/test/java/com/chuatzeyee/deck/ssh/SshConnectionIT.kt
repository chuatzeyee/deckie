package com.chuatzeyee.deck.ssh

import com.chuatzeyee.deck.core.Host
import com.chuatzeyee.deck.core.Tmux
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.SSHClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test

/**
 * Exercises [SshConnection] against a real server. Skipped unless `DECK_IT_HOST`, `DECK_IT_USER`,
 * and `DECK_IT_KEY` (a path to a private key file already authorized on that server) are all set -
 * this suite is opt-in only and is never run against the shared dev VM (see Task 7 report).
 *
 * Uses [runBlocking], not `runTest`: these tests do real blocking network I/O and rely on real
 * wall-clock timeouts (connect timeout, exec timeout), which a `TestDispatcher`'s virtual time
 * would fast-forward past, firing spuriously before the real I/O had a chance to complete.
 */
class SshConnectionIT {

    private val itHost = System.getenv("DECK_IT_HOST")
    private val itUser = System.getenv("DECK_IT_USER")
    private val itKeyPath = System.getenv("DECK_IT_KEY")

    private val connections = mutableListOf<SshConnection>()

    @Before
    fun checkEnv() {
        assumeNotNull(itHost)
        assumeNotNull(itUser)
        assumeNotNull(itKeyPath)
    }

    @After
    fun tearDown() {
        connections.forEach { it.close() }
    }

    private fun buildHost(pinnedHostKey: String? = null) = Host(
        id = "it",
        name = "it-host",
        address = requireNotNull(itHost),
        user = requireNotNull(itUser),
        keyId = "it",
        pinnedHostKey = pinnedHostKey,
    )

    private fun newConnection(pinnedHostKey: String? = null): SshConnection {
        val keys = SSHClient().loadKeys(requireNotNull(itKeyPath))
        return SshConnection(buildHost(pinnedHostKey), keys).also { connections.add(it) }
    }

    /** First contact with this throwaway host always trusts-on-first-use (never auto-pins); this
     * replays the two-step flow a real caller does: reject, learn the fingerprint, reconnect pinned. */
    private fun connectPinned(): SshConnection {
        val fingerprint = try {
            runBlocking { newConnection().connect() }
            throw AssertionError("expected HostKeyFirstUse on an unpinned host")
        } catch (firstUse: HostKeyFirstUse) {
            assertTrue("expected a non-blank key type, got '${firstUse.keyType}'", firstUse.keyType.isNotBlank())
            firstUse.fingerprint
        }
        return newConnection(pinnedHostKey = fingerprint).also { runBlocking { it.connect() } }
    }

    @Test fun connects_and_lists_tmux() = runBlocking {
        val ssh = connectPinned()
        val result = ssh.run(Tmux.LIST, null)
        assertTrue(result.exit == 0 || Tmux.isNoServer(result.exit, result.stderr))
    }

    @Test fun stdin_reaches_command() = runBlocking {
        val ssh = connectPinned()
        val result = ssh.run("cat", "héllo".toByteArray(Charsets.UTF_8))
        assertEquals("héllo", result.stdout)
    }

    @Test fun wrong_pin_refuses() = runBlocking {
        val ssh = newConnection(pinnedHostKey = "SHA256:wrong")
        try {
            ssh.connect()
            throw AssertionError("expected connect() to throw")
        } catch (expected: HostKeyMismatch) {
            assertTrue("expected a non-blank key type, got '${expected.keyType}'", expected.keyType.isNotBlank())
        }
    }
}
