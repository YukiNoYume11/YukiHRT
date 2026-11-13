package cn.hrtcurve.app

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
 * API Key 的本机加密存储。
 *
 * 加密密钥由 Android Keystore 生成并持有，不可导出；应用只保存 IV + 密文，
 * 明文 Key 不写入 SharedPreferences、不进入日志、不参与备份（allowBackup=false），
 * 也不会进入发送给模型的记录 JSON。卸载应用时 Keystore 条目与密文一并删除。
 */
class SecretStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("hrt_curve_secret", Context.MODE_PRIVATE)

    fun hasApiKey(): Boolean = prefs.contains(KEY_PAYLOAD)

    fun loadApiKey(): String? {
        val payload = prefs.getString(KEY_PAYLOAD, null) ?: return null
        return try {
            val raw = Base64.decode(payload, Base64.NO_WRAP)
            if (raw.size <= IV_LENGTH) return null
            val key = existingKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw, 0, IV_LENGTH))
            val plain = cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH)
            String(plain, Charsets.UTF_8).takeIf { it.isNotBlank() }
        } catch (error: Exception) {
            // Keystore 条目失效或密文损坏：清掉无法解密的残留，要求重新输入。
            prefs.edit().remove(KEY_PAYLOAD).apply()
            null
        }
    }

    /** 返回是否成功写入本机加密存储；空 Key 会清除已有记录并返回 false。 */
    fun saveApiKey(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            clearApiKey()
            return false
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(trimmed.toByteArray(Charsets.UTF_8))
            val payload = cipher.iv + encrypted
            prefs.edit()
                .putString(KEY_PAYLOAD, Base64.encodeToString(payload, Base64.NO_WRAP))
                .apply()
            true
        } catch (error: Exception) {
            prefs.edit().remove(KEY_PAYLOAD).apply()
            false
        }
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_PAYLOAD).apply()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? {
        val entry = keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry ?: return null
        return entry.secretKey
    }

    private fun secretKey(): SecretKey = existingKey() ?: generateKey()

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "yuki_hrt_ai_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_PAYLOAD = "api_key_payload"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
