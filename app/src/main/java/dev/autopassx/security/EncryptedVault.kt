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

class EncryptedVault(context: Context) {
    private val prefs = context.getSharedPreferences("vault_records", Context.MODE_PRIVATE)
    private val alias = "autopassx_aes_v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    @Synchronized fun put(id: String, record: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(record.toString().toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(4 + cipher.iv.size + encrypted.size)
            .putInt(cipher.iv.size).put(cipher.iv).put(encrypted).array()
        prefs.edit().putString(id, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()
    }

    @Synchronized fun get(id: String): JSONObject? = decrypt(prefs.getString(id, null))

    @Synchronized fun all(): List<JSONObject> = prefs.all.mapNotNull { (_, value) ->
        (value as? String)?.let(::decrypt)
    }

    @Synchronized fun delete(id: String) { prefs.edit().remove(id).commit() }

    private fun decrypt(raw: String?): JSONObject? = try {
        if (raw == null) return null
        val data = ByteBuffer.wrap(Base64.decode(raw, Base64.NO_WRAP))
        val ivSize = data.int
        require(ivSize in 12..16 && data.remaining() > ivSize)
        val iv = ByteArray(ivSize).also(data::get)
        val encrypted = ByteArray(data.remaining()).also(data::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
    } catch (_: Exception) { null }
}