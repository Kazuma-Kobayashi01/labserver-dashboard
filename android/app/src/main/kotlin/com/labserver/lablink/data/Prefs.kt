package com.labserver.lablink.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * App settings. Secrets (the device's WireGuard private key, the preshared key and the Lab bot key) are sealed with an
 * AES-GCM key that lives in the Android Keystore, so they never sit in plain text in the app's storage.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("lablink", Context.MODE_PRIVATE)
    private val vault = Vault()

    var address: String
        get() = sp.getString("address", "10.77.0.4") ?: ""
        set(v) = sp.edit().putString("address", v.trim()).apply()
    var endpoint: String
        get() = sp.getString("endpoint", "") ?: ""
        set(v) = sp.edit().putString("endpoint", v.trim()).apply()
    var hubPublicKey: String
        get() = sp.getString("hubPublicKey", "") ?: ""
        set(v) = sp.edit().putString("hubPublicKey", v.trim()).apply()
    var allowedIPs: String
        get() = sp.getString("allowedIPs", "10.77.0.0/24,10.0.0.0/24") ?: ""
        set(v) = sp.edit().putString("allowedIPs", v.trim()).apply()

    /** Where every home hostname lives inside the tunnel (the server's Incus bridge address). */
    var tunnelHost: String
        get() = sp.getString("tunnelHost", "10.0.0.1") ?: ""
        set(v) = sp.edit().putString("tunnelHost", v.trim()).apply()

    /** Base domain of the home sites: dashboard = domain, incus.domain, git.domain, bot.domain ... */
    var domain: String
        get() = sp.getString("domain", "labserver.internal") ?: ""
        set(v) = sp.edit().putString("domain", v.trim()).apply()

    /** Empty = the default `http://bot.<domain>/p/labbot/v1`. */
    var botBaseUrl: String
        get() = sp.getString("botBaseUrl", "") ?: ""
        set(v) = sp.edit().putString("botBaseUrl", v.trim().trimEnd('/')).apply()

    var privateKey: String
        get() = secret("privateKey")
        set(v) = putSecret("privateKey", v)
    var presharedKey: String
        get() = secret("presharedKey")
        set(v) = putSecret("presharedKey", v)
    var botKey: String
        get() = secret("botKey")
        set(v) = putSecret("botKey", v)

    val effectiveBotUrl: String get() = botBaseUrl.ifBlank { "http://bot.$domain/p/labbot/v1" }

    private fun secret(name: String): String = sp.getString(name, null)?.let { vault.open(it) } ?: ""

    private fun putSecret(name: String, value: String) {
        val v = value.trim()
        sp.edit().apply { if (v.isEmpty()) remove(name) else putString(name, vault.seal(v)) }.apply()
    }
}

private class Vault {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun seal(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    fun open(sealed: String): String = try {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, IV_LEN))
        }
        String(c.doFinal(raw, IV_LEN, raw.size - IV_LEN))
    } catch (_: Exception) {
        "" // the Keystore key was lost (e.g. app data restored on another phone): treat as unset
    }

    private companion object {
        const val ALIAS = "lablink-vault"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
