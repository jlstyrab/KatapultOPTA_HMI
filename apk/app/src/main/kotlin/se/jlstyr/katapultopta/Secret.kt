package se.jlstyr.katapultopta

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hemligheter i SharedPreferences, krypterade med en AES-nyckel i Android
 * Keystore (samma sak som EncryptedSharedPreferences gör, men utan AndroidX —
 * D3). Nyckeln lämnar aldrig Keystore, så en kopia av prefs-filen avslöjar
 * inte lösenordet. Går Keystore inte att använda (trasig på vissa billiga
 * plattor) sparas värdet okrypterat och det loggas.
 */
object Secret {
    private const val ALIAS = "katapultopta-secret"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    fun get(prefs: SharedPreferences, key: String): String {
        val enc = prefs.getString("${key}_enc", null)
        if (enc == null) return prefs.getString(key, "") ?: ""
        return try {
            val parts = enc.split(':')
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ct = Base64.decode(parts[2], Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(128, iv))
            String(c.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            Diag.log("hemlighet $key gick inte att läsa (${e.javaClass.simpleName}) — ange den igen")
            ""
        }
    }

    fun has(prefs: SharedPreferences, key: String): Boolean =
        prefs.contains("${key}_enc") || !prefs.getString(key, "").isNullOrEmpty()

    /** Tomt värde tar bort hemligheten. */
    fun put(prefs: SharedPreferences, key: String, value: String) {
        val e = prefs.edit().remove(key).remove("${key}_enc")
        if (value.isNotEmpty()) {
            try {
                val c = Cipher.getInstance(TRANSFORM)
                c.init(Cipher.ENCRYPT_MODE, aesKey())
                val ct = c.doFinal(value.toByteArray(Charsets.UTF_8))
                e.putString("${key}_enc", "v1:" + Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" +
                    Base64.encodeToString(ct, Base64.NO_WRAP))
            } catch (ex: Exception) {
                Diag.log("Keystore otillgänglig (${ex.javaClass.simpleName}) — $key sparas okrypterat")
                e.putString(key, value)
            }
        }
        e.apply()
    }

    private fun aesKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return g.generateKey()
    }
}
