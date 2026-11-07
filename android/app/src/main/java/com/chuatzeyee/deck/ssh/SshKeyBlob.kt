package com.chuatzeyee.deck.ssh

import net.schmizz.sshj.common.Buffer
import java.security.PublicKey

/**
 * SSH wire-format blob (type string + key fields) for [key] - the same bytes OpenSSH hashes for
 * `ssh-keygen -lf` and that go into an `authorized_keys`/public-key-line entry. Shared by
 * [KeyVault]'s public-key-line formatting and [SshConnection]'s host-key fingerprinting so the
 * two never drift out of sync.
 */
fun publicKeyBlob(key: PublicKey): ByteArray = Buffer.PlainBuffer().putPublicKey(key).compactData
