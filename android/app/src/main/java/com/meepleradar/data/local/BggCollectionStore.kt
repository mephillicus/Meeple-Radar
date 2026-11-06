package com.meepleradar.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.meepleradar.data.model.BggExpansion
import com.meepleradar.data.model.BggCollectionGame
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class CachedBggCollection(
    val username: String,
    val games: List<BggCollectionGame>,
    val updatedAt: Long,
    val apiToken: String,
    val expansions: List<BggExpansion> = emptyList(),
    val expansionsLoaded: Boolean = false,
)

interface BggCollectionStore {
    fun load(): CachedBggCollection?
    fun save(collection: CachedBggCollection)
    fun clear()
}

class SharedPreferencesBggCollectionStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : BggCollectionStore {
    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    override fun load(): CachedBggCollection? {
        val username = preferences.getString(KEY_USERNAME, null)?.takeIf(String::isNotBlank) ?: return null
        val gamesJson = preferences.getString(KEY_GAMES, null) ?: return null
        val expansionsJson = preferences.getString(KEY_EXPANSIONS, "[]") ?: "[]"
        return try {
            CachedBggCollection(
                username = username,
                games = json.decodeFromString<List<BggCollectionGame>>(gamesJson),
                updatedAt = preferences.getLong(KEY_UPDATED_AT, 0L),
                apiToken = decrypt(preferences.getString(KEY_TOKEN, null) ?: return null),
                expansions = json.decodeFromString<List<BggExpansion>>(expansionsJson),
                expansionsLoaded = preferences.getBoolean(KEY_EXPANSIONS_LOADED, false),
            )
        } catch (_: Exception) {
            null
        }
    }

    override fun save(collection: CachedBggCollection) {
        preferences.edit()
            .putString(KEY_USERNAME, collection.username)
            .putString(KEY_GAMES, json.encodeToString(collection.games))
            .putString(KEY_EXPANSIONS, json.encodeToString(collection.expansions))
            .putBoolean(KEY_EXPANSIONS_LOADED, collection.expansionsLoaded)
            .putLong(KEY_UPDATED_AT, collection.updatedAt)
            .putString(KEY_TOKEN, encrypt(collection.apiToken))
            .apply()
    }

    override fun clear() {
        preferences.edit().clear().apply()
        KeyStore.getInstance(ANDROID_KEY_STORE).apply {
            load(null)
            if (containsAlias(KEY_ALIAS)) deleteEntry(KEY_ALIAS)
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(
            ByteBuffer.allocate(iv.size + encrypted.size).put(iv).put(encrypted).array(),
            android.util.Base64.NO_WRAP,
        )
    }

    private fun decrypt(value: String): String {
        val payload = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
        val buffer = ByteBuffer.wrap(payload)
        val iv = ByteArray(IV_LENGTH).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREF_NAME = "meeple_radar_bgg_collection"
        const val KEY_USERNAME = "username"
        const val KEY_GAMES = "games"
        const val KEY_EXPANSIONS = "expansions"
        const val KEY_EXPANSIONS_LOADED = "expansions_loaded"
        const val KEY_UPDATED_AT = "updated_at"
        const val KEY_TOKEN = "encrypted_api_token"
        const val KEY_ALIAS = "meeple_radar_bgg_api_token"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val GCM_TAG_LENGTH = 128
    }
}
