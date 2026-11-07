package com.chuatzeyee.deck.ssh

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

private val bc = BouncyCastleProvider()

// X.509 SubjectPublicKeyInfo prefix for an Ed25519 key (RFC 8410): the raw 32-byte key follows it.
private val ED25519_SPKI_PREFIX = byteArrayOf(
    0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
)

// A fixed PUBLIC key vector (a public key is not a secret). Expected values come from
// `cat key.pub` and `ssh-keygen -lf key.pub` on a throwaway key whose private half was discarded.
private const val VECTOR_BLOB_B64 = "AAAAC3NzaC1lZDI1NTE5AAAAIHmijg5D7zKiRJV0i+fs42dmfMok745Tg+Bv99d6P/Pn"
private const val VECTOR_FINGERPRINT = "SHA256:3im3S5Su4dCEnX27b/QlE+LUulNaPQH2BOc7uPCOS3Y"

class SshKeyFormattingTest {

    /** The OpenSSH wire blob built by hand: string "ssh-ed25519" + string(32-byte raw key). */
    private fun handBuiltBlob(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            val type = "ssh-ed25519".toByteArray(Charsets.US_ASCII)
            data.writeInt(type.size)
            data.write(type)
            data.writeInt(raw.size)
            data.write(raw)
        }
        return out.toByteArray()
    }

    private fun rawKey(key: PublicKey): ByteArray = key.encoded.takeLast(32).toByteArray()

    private fun keyFromRaw(raw: ByteArray): PublicKey =
        KeyFactory.getInstance("Ed25519", bc).generatePublic(X509EncodedKeySpec(ED25519_SPKI_PREFIX + raw))

    private fun fingerprintOf(blob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    @Test fun generated_key_line_matches_hand_built_blob() {
        val key = KeyPairGenerator.getInstance("Ed25519", bc).generateKeyPair().public
        val blob = handBuiltBlob(rawKey(key))
        assertEquals(
            "ssh-ed25519 ${Base64.getEncoder().encodeToString(blob)} deck@titan2",
            openSshPublicKeyLine(key),
        )
    }

    @Test fun generated_key_fingerprint_matches_hash_of_hand_built_blob() {
        val key = KeyPairGenerator.getInstance("Ed25519", bc).generateKeyPair().public
        assertEquals(fingerprintOf(handBuiltBlob(rawKey(key))), sshFingerprint(key))
    }

    @Test fun fixed_public_vector_matches_ssh_keygen() {
        val blob = Base64.getDecoder().decode(VECTOR_BLOB_B64)
        val key = keyFromRaw(blob.takeLast(32).toByteArray())
        assertEquals("ssh-ed25519 $VECTOR_BLOB_B64 deck@titan2", openSshPublicKeyLine(key))
        assertEquals(VECTOR_FINGERPRINT, sshFingerprint(key))
    }
}
