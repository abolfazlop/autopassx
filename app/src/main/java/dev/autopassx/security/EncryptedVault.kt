package dev.autopassx.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small local encrypted record store.
 *
 * Each record is authenticated against its storage key (AAD), preventing an
 * encrypted value from being moved to another record ID without detection.
 * The reader remains compatible with the original unversioned v1 payloads.
 */
class EncryptedVault(context: Context) {
    private val prefs = context.getSharedPreferences("vault_records", Context.MODE_PRIVATE)
    private val alias = "autopassx_aes_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    @Synchronized
    fun put(id: String, record: JSONObject) {
        require(id.isNotBlank() && id.length <= 512) { "Invalid record ID" }
        val plainText = record.toString().toByteArray(Charsets.UTF_8)
        require(plainText.size <= MAX_RECORD_BYTES) { "Record is too large" }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plainText)
        val payload = ByteBuffer.allocate(8 + cipher.iv.size + encrypted.size)
            .putInt(MAGIC)
            .putInt(cipher.iv.size)
            .put(cipher.iv)
            .put(encrypted)
            .array()

        check(
            prefs.edit()
                .putString(id, Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit()
        ) { "Unable to persist encrypted record" }
    }

    @Synchronized
    fun get(id: String): JSONObject? = decrypt(id, prefs.getString(id, null))

    @Synchronized
    fun all(): List<JSONObject> = prefs.all.mapNotNull { (id, value) ->
        val recordId = id
        (value as? String)?.let { decrypt(recordId, it) }
    }

    @Synchronized
    fun delete(id: String) {
        check(prefs.edit().remove(id).commit()) { "Unable to delete record" }
    }

    private fun decrypt(id: String, raw: String?): JSONObject? {
        return try {
            if (raw == null) return null
            require(id.isNotBlank() && id.length <= 512)

            val data = ByteBuffer.wrap(Base64.decode(raw, Base64.NO_WRAP))
            require(data.remaining() >= 4)
            val markerOrIvSize = data.int
            val versioned = markerOrIvSize == MAGIC
            val ivSize = if (versioned) {
                require(data.remaining() >= 4)
                data.int
            } else {
                markerOrIvSize
            }

            require(ivSize in 12..16 && data.remaining() > ivSize + GCM_TAG_BYTES)
            val iv = ByteArray(ivSize).also(data::get)
            val encrypted = ByteArray(data.remaining()).also(data::get)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            if (versioned) cipher.updateAAD(id.toByteArray(Charsets.UTF_8))

            JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        } catch (_: Exception) {
            // Corrupt, tampered, or unreadable records are never returned as plaintext.
            null
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MAGIC = 0x41505831 // "APX1"
        const val GCM_TAG_BYTES = 16
        const val MAX_RECORD_BYTES = 64 * 1024
    }
}
