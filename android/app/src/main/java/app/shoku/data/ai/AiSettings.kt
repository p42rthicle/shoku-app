package app.shoku.data.ai

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The user's own Claude API key and direct mode's spending ledger.
 *
 * The key lives in private SharedPreferences, encrypted with an AES key held in the Android Keystore
 * (not androidx.security-crypto, which Google has deprecated). The Keystore key never leaves the phone,
 * so a cloud backup of the app's files carries only ciphertext; restored on a new phone it can't be
 * read and the key simply has to be entered again.
 */
object AiSettings {
    private const val KEY_PREFS = "ai_key"
    private const val USAGE_PREFS = "ai_usage"
    private const val KEYSTORE_ALIAS = "shoku_claude_api_key"

    private var keyPrefs: SharedPreferences? = null
    private var usagePrefs: SharedPreferences? = null
    @Volatile private var cached: String? = null
    @Volatile private var loaded = false

    private val _hasKey = MutableStateFlow(false)
    /** True while a key is saved; Settings listens to it. */
    val hasKey: StateFlow<Boolean> = _hasKey

    fun init(context: Context) {
        val app = context.applicationContext
        keyPrefs = app.getSharedPreferences(KEY_PREFS, Context.MODE_PRIVATE)
        usagePrefs = app.getSharedPreferences(USAGE_PREFS, Context.MODE_PRIVATE)
        _hasKey.value = keyPrefs?.contains("key") == true
    }

    /** The saved key, or null. Decrypted once and kept in memory. */
    @Synchronized
    fun apiKey(): String? {
        if (loaded) return cached
        val prefs = keyPrefs ?: return null
        cached = prefs.getString("key", null)?.let { stored ->
            // Restored from another phone, or the Keystore was reset: the key has to be entered again
            // (saving a new one overwrites the unreadable copy).
            runCatching { decrypt(stored) }.getOrNull()
        }
        loaded = true
        _hasKey.value = cached != null
        return cached
    }

    @Synchronized
    fun saveKey(key: String) {
        val k = key.trim()
        require(k.isNotEmpty()) { "Enter a key first" }
        val prefs = keyPrefs ?: error("AiSettings.init was not called")
        prefs.edit().putString("key", encrypt(k)).apply()
        cached = k
        loaded = true
        _hasKey.value = true
    }

    @Synchronized
    fun removeKey() {
        keyPrefs?.edit()?.remove("key")?.apply()
        cached = null
        loaded = true
        _hasKey.value = false
    }

    /** "sk-ant-…a1b2", for showing which key is saved. */
    fun maskedKey(): String? = apiKey()?.let { k -> if (k.length > 12) k.take(7) + "…" + k.takeLast(4) else "…" }

    /** Direct mode's monthly spend and cap. In memory when not initialised (unit tests). */
    val ledger: UsageLedger by lazy {
        val prefs = usagePrefs
        UsageLedger(
            if (prefs == null) MemoryStore() else object : KeyValueStore {
                override fun get(key: String) = prefs.getString(key, null)
                override fun put(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
            },
        )
    }

    /**
     * Builds without a backend never reach the app-start hook that fills household units, so this adds
     * one: [fill] runs each time the app comes to the foreground. [UnitsFiller.run] itself does nothing
     * without a key and at most one new job a day.
     */
    fun fillUnitsOnStart(scope: CoroutineScope, fill: suspend () -> Unit) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch { runCatching { fill() }.onFailure { android.util.Log.w("Shoku", "Units fill", it) } }
            }
        })
    }

    // ---------- Keystore ----------

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val enc = Base64.getEncoder()
        return enc.encodeToString(cipher.iv) + ":" + enc.encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    private fun decrypt(stored: String): String {
        val (iv, data) = stored.split(':', limit = 2).map { Base64.getDecoder().decode(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv)) }
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }
}
