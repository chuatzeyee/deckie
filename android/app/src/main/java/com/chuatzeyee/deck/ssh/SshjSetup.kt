package com.chuatzeyee.deck.ssh

import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * One-time, idempotent setup that makes sshj (and our own Ed25519 code) use a real,
 * Ed25519/X25519-capable BouncyCastle provider. Android ships its own stub "BC" provider that
 * lacks Ed25519/X25519 support; sshj resolves crypto providers purely by name ("BC"), so as long
 * as Android's stub answers to that name every Ed25519 operation sshj performs - host-key
 * verification and client auth, both load-bearing for this app - silently fails. The Task 2
 * device spike confirmed that removing the stub, installing a real [BouncyCastleProvider] under
 * the same name, and pointing sshj's own [SecurityUtils] at it is what actually works on-device.
 *
 * This is the one deliberate global [Security] mutation in this app's main code - scoped to this
 * single function, run at most once per process via [ensure].
 */
object SshjSetup {

    @Volatile private var done = false

    @Synchronized
    fun ensure() {
        if (done) return
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        SecurityUtils.setSecurityProvider(BouncyCastleProvider.PROVIDER_NAME)
        done = true
    }
}
