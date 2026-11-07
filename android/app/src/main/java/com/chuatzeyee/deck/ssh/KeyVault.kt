package com.chuatzeyee.deck.ssh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.schmizz.sshj.userauth.keyprovider.KeyProvider as SshKeyProvider

private const val KEY_COMMENT = "deck@titan2"
private val KEY_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
private const val KEYSTORE_ALIAS = "deck-vault"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"
private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val GCM_IV_BYTES = 12

/** [keyId] unchanged if it is a safe key file name (letters, digits, `_`, `-`; 1-64 chars - never a
 * path), else [IllegalArgumentException]. */
internal fun requireValidKeyId(keyId: String): String {
    require(KEY_ID_REGEX.matches(keyId)) { "Invalid keyId: $keyId" }
    return keyId
}

/** `ssh-ed25519 <base64> deck@titan2` for [key] - a pure formatting step, independent of storage. */
fun openSshPublicKeyLine(key: PublicKey): String =
    "${KeyType.fromKey(key)} ${Base64.getEncoder().encodeToString(publicKeyBlob(key))} $KEY_COMMENT"

/**
 * Stores one Ed25519 client identity per [generate]/[import]ed `keyId`, as an AES-GCM-encrypted
 * file under `filesDir/keys/<keyId>`, wrapped with an Android Keystore key (alias [KEYSTORE_ALIAS])
 * that never leaves hardware-backed storage. Uses an explicit [BouncyCastleProvider] instance for
 * Ed25519 key generation/decoding rather than registering one process-wide for our own crypto -
 * see [SshjSetup] for the one deliberate global provider change this app does make, for sshj.
 */
class KeyVault(context: Context) {

    private val keysDir = File(context.filesDir, "keys").also { it.mkdirs() }
    private val bc = BouncyCastleProvider()

    fun generate(keyId: String): String {
        SshjSetup.ensure()
        val keyPair = KeyPairGenerator.getInstance("Ed25519", bc).generateKeyPair()
        writeKeyPair(validated(keyId), keyPair)
        return openSshPublicKeyLine(keyPair.public)
    }

    fun import(keyId: String, privateKeyPem: String, passphrase: CharArray?) {
        SshjSetup.ensure()
        val keyPair = parsePrivateKey(privateKeyPem, passphrase)
        require(KeyType.fromKey(keyPair.public) == KeyType.ED25519) { "Only Ed25519 keys are supported" }
        writeKeyPair(validated(keyId), keyPair)
    }

    fun provider(keyId: String): SshKeyProvider {
        SshjSetup.ensure()
        return KeyPairWrapper(readKeyPair(validated(keyId)))
    }

    fun publicKey(keyId: String): String {
        SshjSetup.ensure()
        return openSshPublicKeyLine(readKeyPair(validated(keyId)).public)
    }

    /** Deletes [keyId]'s stored identity (deleting a host). True if a file was removed; false
     * if there was none. */
    fun delete(keyId: String): Boolean {
        val file = File(keysDir, validated(keyId))
        return file.exists() && file.delete()
    }

    private fun validated(keyId: String): String = requireValidKeyId(keyId)

    private fun parsePrivateKey(pem: String, passphrase: CharArray?): KeyPair {
        val finder = passphrase?.let(PasswordUtils::createOneOff)
        val provider: FileKeyProvider = when (KeyProviderUtil.detectKeyFileFormat(pem, false)) {
            KeyFormat.OpenSSHv1 -> OpenSSHKeyV1KeyFile()
            KeyFormat.PKCS8 -> PKCS8KeyFile()
            KeyFormat.PuTTY -> PuTTYKeyFile()
            else -> throw IllegalArgumentException("Unsupported or unrecognised private key format")
        }
        provider.init(StringReader(pem), finder)
        return KeyPair(provider.public, provider.private)
    }

    /** Refuses to overwrite an existing `keyId` - losing an already-authorized identity is data
     * loss, not something a later `generate`/`import` call should ever do silently. Writes to a
     * temp file first and renames into place, so a crash mid-write can't corrupt the real file. */
    private fun writeKeyPair(keyId: String, keyPair: KeyPair) {
        val target = File(keysDir, keyId)
        require(!target.exists()) { "keyId '$keyId' already exists" }
        // "key-" is a fixed prefix (createTempFile requires >= 3 chars; keyId alone could be shorter).
        val temp = File.createTempFile("key-", ".tmp", keysDir)
        try {
            temp.writeBytes(encrypt(encodeKeyPair(keyPair)))
            check(temp.renameTo(target)) { "Failed to save key for '$keyId'" }
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    private fun readKeyPair(keyId: String): KeyPair = decodeKeyPair(decrypt(File(keysDir, keyId).readBytes()))

    private fun encodeKeyPair(keyPair: KeyPair): ByteArray {
        val priv = keyPair.private.encoded
        val pub = keyPair.public.encoded
        return ByteBuffer.allocate(8 + priv.size + pub.size)
            .putInt(priv.size).put(priv).putInt(pub.size).put(pub)
            .array()
    }

    private fun decodeKeyPair(data: ByteArray): KeyPair {
        val buffer = ByteBuffer.wrap(data)
        val priv = ByteArray(buffer.int).also { buffer.get(it) }
        val pub = ByteArray(buffer.int).also { buffer.get(it) }
        val factory = KeyFactory.getInstance("Ed25519", bc)
        return KeyPair(
            factory.generatePublic(X509EncodedKeySpec(pub)),
            factory.generatePrivate(PKCS8EncodedKeySpec(priv)),
        )
    }

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        return cipher.iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val iv = data.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = data.copyOfRange(GCM_IV_BYTES, data.size)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return cipher.doFinal(ciphertext)
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }
}
