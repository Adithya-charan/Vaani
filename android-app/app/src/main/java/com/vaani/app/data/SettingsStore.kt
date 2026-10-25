package com.vaani.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AgentConfig(val address: String, val token: String)

interface SettingsStore {
    fun load(): AgentConfig?
    fun save(config: AgentConfig)
    fun clear()
}

/**
 * Stores the laptop address in plain prefs and the token encrypted with an
 * AES-GCM key that never leaves the Android Keystore.
 */
class KeystoreSettingsStore(context: Context) : SettingsStore {

    private val prefs = context.getSharedPreferences("vaani_settings", Context.MODE_PRIVATE)

    override fun load(): AgentConfig? {
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        val encrypted = prefs.getString(KEY_TOKEN, null) ?: return null
        val token = try {
            decrypt(encrypted)
        } catch (e: GeneralSecurityException) {
            return null // key lost (e.g. restored backup); user must re-enter the token
        } catch (e: IllegalArgumentException) {
            return null
        }
        return AgentConfig(address, token)
    }

    override fun save(config: AgentConfig) {
        prefs.edit {
            putString(KEY_ADDRESS, config.address)
            putString(KEY_TOKEN, encrypt(config.token))
        }
    }

    override fun clear() {
        prefs.edit { clear() }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
        return String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "vaani_token_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val KEY_ADDRESS = "agent_address"
        const val KEY_TOKEN = "agent_token_enc"
    }
}
