package dev.reclaimed.player.jellyfin

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class JellyfinConfig(
    val serverUrl: String = "",
    val apiKey: String = "",
    val userId: String? = null,
    val libraryId: String? = null,
    val libraryName: String? = null,
) {
    val isConfigured: Boolean = serverUrl.isNotBlank() && apiKey.isNotBlank()
}

class JellyfinSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): JellyfinConfig = JellyfinConfig(
        serverUrl = preferences.getString(KEY_SERVER_URL, "").orEmpty(),
        apiKey = decrypt(preferences.getString(KEY_API_KEY, null)).orEmpty(),
        userId = preferences.getString(KEY_USER_ID, null),
        libraryId = preferences.getString(KEY_LIBRARY_ID, null),
        libraryName = preferences.getString(KEY_LIBRARY_NAME, null),
    )

    fun saveConnection(serverUrl: String, apiKey: String): JellyfinConfig {
        val current = load()
        val normalizedUrl = serverUrl.trim().trimEnd('/')
        val resolvedApiKey = apiKey.trim().ifEmpty { current.apiKey }
        val serverChanged = normalizedUrl != current.serverUrl
        preferences.edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .putString(KEY_API_KEY, encrypt(resolvedApiKey))
            .apply {
                if (serverChanged) {
                    remove(KEY_LIBRARY_ID)
                    remove(KEY_LIBRARY_NAME)
                }
            }
            .apply()
        return load()
    }

    fun selectLibrary(library: JellyfinLibrary): JellyfinConfig {
        preferences.edit()
            .putString(KEY_LIBRARY_ID, library.id)
            .putString(KEY_LIBRARY_NAME, library.name)
            .apply()
        return load()
    }

    fun saveServerUrl(serverUrl: String): JellyfinConfig {
        val current = load()
        val normalizedUrl = serverUrl.trim().trimEnd('/')
        preferences.edit()
            .putString(KEY_SERVER_URL, normalizedUrl)
            .apply {
                if (normalizedUrl != current.serverUrl) {
                    remove(KEY_API_KEY)
                    remove(KEY_USER_ID)
                    remove(KEY_LIBRARY_ID)
                    remove(KEY_LIBRARY_NAME)
                }
            }
            .apply()
        return load()
    }

    fun saveQuickConnectAuthentication(accessToken: String, userId: String?): JellyfinConfig {
        preferences.edit()
            .putString(KEY_API_KEY, encrypt(accessToken))
            .putString(KEY_USER_ID, userId)
            .remove(KEY_LIBRARY_ID)
            .remove(KEY_LIBRARY_NAME)
            .apply()
        return load()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val payload = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val payload = Base64.decode(value, Base64.NO_WRAP)
            val iv = payload.copyOfRange(0, GCM_IV_BYTES)
            val encrypted = payload.copyOfRange(GCM_IV_BYTES, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "jellyfin_settings"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_API_KEY = "api_key_encrypted"
        const val KEY_USER_ID = "user_id"
        const val KEY_LIBRARY_ID = "library_id"
        const val KEY_LIBRARY_NAME = "library_name"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "reclaimed_player_jellyfin_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
    }
}
