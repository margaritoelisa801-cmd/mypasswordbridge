package it.keybridge.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts vault fields using an Android Keystore AES-256 key.
 *
 * This starter version does not yet bind every decrypt operation to BiometricPrompt.
 * Do not treat it as a production-ready password manager until that policy is added.
 */
class CryptoManager(
    private val alias: String = "keybridge_vault_aes_v1"
) {
    data class EncryptedValue(val nonce: ByteArray, val ciphertext: ByteArray)

    private val keyStore: KeyStore
        get() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plaintext: ByteArray, associatedData: ByteArray? = null): EncryptedValue {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        associatedData?.let(cipher::updateAAD)
        return EncryptedValue(cipher.iv, cipher.doFinal(plaintext))
    }

    fun decrypt(
        nonce: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray? = null
    ): ByteArray {
        require(nonce.size == 12) { "Nonce GCM non valido" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, nonce)
        )
        associatedData?.let(cipher::updateAAD)
        return cipher.doFinal(ciphertext)
    }
}
