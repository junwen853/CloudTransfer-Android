package io.github.junwen853.cloudtransfer.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.junwen853.cloudtransfer.icloud.Cookies
import io.github.junwen853.cloudtransfer.icloud.ICloudSession
import io.github.junwen853.cloudtransfer.icloud.SessionAccess
import io.github.junwen853.cloudtransfer.icloud.SessionExpiredException
import org.json.JSONObject
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the iCloud web session encrypted with a key from the Android Keystore,
 * which never leaves the device. Backups are disabled for the app, so the
 * session cannot be restored onto another phone.
 */
class SessionStore private constructor(context: Context) : SessionAccess {
    private val prefs = context.applicationContext.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val lock = Any()

    @Volatile
    private var cached: ICloudSession? = null

    @Volatile
    private var loaded = false

    companion object {
        private const val KEY_ALIAS = "cloudtransfer_session"
        private const val PREF_BLOB = "blob"

        @Volatile
        private var instance: SessionStore? = null

        fun get(context: Context): SessionStore =
            instance ?: synchronized(this) { instance ?: SessionStore(context).also { instance = it } }
    }

    fun load(): ICloudSession? = synchronized(lock) {
        if (loaded) return cached
        cached = runCatching { decode(decrypt(prefs.getString(PREF_BLOB, null) ?: return@runCatching null)) }.getOrNull()
        loaded = true
        cached
    }

    fun save(session: ICloudSession) = synchronized(lock) {
        val blob = encrypt(encode(session))
        check(prefs.edit().putString(PREF_BLOB, blob).commit()) { "登录信息保存失败。" }
        cached = session
        loaded = true
    }

    fun clear() = synchronized(lock) {
        prefs.edit().remove(PREF_BLOB).commit()
        cached = null
        loaded = true
    }

    override fun current(): ICloudSession = load() ?: throw SessionExpiredException("还没有登录 iCloud。")

    override fun applySetCookies(setCookieHeaders: List<String>) {
        synchronized(lock) {
            val session = load() ?: return
            val merged = Cookies.applySetCookies(session.cookieHeader, setCookieHeaders)
            if (merged != session.cookieHeader) save(session.copy(cookieHeader = merged))
        }
    }

    private fun encode(session: ICloudSession): String = JSONObject()
        .put("cookie", session.cookieHeader)
        .put("databaseUrl", session.databaseUrl)
        .put("dsid", session.dsid)
        .put("clientId", session.clientId)
        .put("build", session.clientBuildNumber)
        .put("mastering", session.clientMasteringNumber)
        .put("photosUpload", session.photosUploadUrl)
        .put("savedAt", session.savedAt)
        .toString()

    private fun decode(text: String): ICloudSession {
        val json = JSONObject(text)
        return ICloudSession(
            cookieHeader = json.getString("cookie"),
            databaseUrl = json.getString("databaseUrl"),
            dsid = json.getString("dsid"),
            clientId = json.optString("clientId"),
            clientBuildNumber = json.optString("build"),
            clientMasteringNumber = json.optString("mastering"),
            photosUploadUrl = json.optString("photosUpload"),
            savedAt = json.optLong("savedAt"),
        )
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return encoder.encodeToString(cipher.iv) + ":" + encoder.encodeToString(sealed)
    }

    private fun decrypt(blob: String): String {
        val (iv, sealed) = blob.split(':', limit = 2).map { Base64.getDecoder().decode(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(sealed), Charsets.UTF_8)
    }
}
