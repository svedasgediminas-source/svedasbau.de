package app.novaiptv.core.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.novaiptv.core.data.logic.MiniJson
import app.novaiptv.core.domain.CredentialStore
import app.novaiptv.core.model.XtreamCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores Xtream credentials encrypted with an AES-256-GCM key that never leaves the Android
 * KeyStore. Each entry is "v1:<base64 iv>:<base64 ciphertext>" in a private SharedPreferences
 * file. Values are never logged; failures surface as null/no-op.
 */
class CredentialStoreImpl(context: Context) : CredentialStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun put(credentials: XtreamCredentials): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = UUID.randomUUID().toString()
            val plain = MiniJson.encode(
                mapOf(
                    FIELD_SERVER to credentials.serverUrl,
                    FIELD_USER to credentials.username,
                    FIELD_PASS to credentials.password,
                ),
            ).toByteArray(Charsets.UTF_8)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain)
            plain.fill(0)
            val value = VERSION + ":" + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            if (!prefs.edit().putString(key, value).commit()) {
                throw IllegalStateException("credential store write failed")
            }
            key
        }
    }

    override suspend fun get(key: String): XtreamCredentials? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val stored = prefs.getString(key, null) ?: return@withLock null
            val parts = stored.split(':')
            if (parts.size != 3 || parts[0] != VERSION) return@withLock null
            try {
                val iv = Base64.decode(parts[1], Base64.NO_WRAP)
                val encrypted = Base64.decode(parts[2], Base64.NO_WRAP)
                val secret = existingKey() ?: return@withLock null
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, iv))
                val plain = cipher.doFinal(encrypted)
                val map = MiniJson.decode(String(plain, Charsets.UTF_8))
                plain.fill(0)
                val server = map[FIELD_SERVER]
                val user = map[FIELD_USER]
                val pass = map[FIELD_PASS]
                if (server == null || user == null || pass == null) null else XtreamCredentials(server, user, pass)
            } catch (e: GeneralSecurityException) {
                null // Key invalidated or data tampered with.
            } catch (e: IllegalArgumentException) {
                null // Bad base64.
            }
        }
    }

    override suspend fun delete(key: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock { prefs.edit().remove(key).commit() }
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "nova_credentials"
        const val KEY_ALIAS = "nova_credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val VERSION = "v1"
        const val FIELD_SERVER = "s"
        const val FIELD_USER = "u"
        const val FIELD_PASS = "p"
    }
}
