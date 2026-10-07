package io.github.micro123.mediaplayer.data.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Entire connection profiles are encrypted. Preferences contain IDs and ciphertext only. */
@android.annotation.SuppressLint("UseKtx")
class NetworkCredentialStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("network_credentials", Context.MODE_PRIVATE)
    private val alias = "player-network-profiles-v1"
    @Synchronized private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(profile: NetworkProfile) {
        val c = profile.credentials
        val plain = JSONObject().apply {
            put("address", RemoteAddress.parse(profile.address).address)
            put("guest", c.guest); put("username", c.username); put("password", c.password)
            put("domain", c.domain); put("uid", c.uid); put("gid", c.gid)
        }.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(profile.id.toByteArray()) }
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP)
        check(preferences.edit().putString(profile.id, value).commit()) { "无法保存网络认证信息" }
    }
    @Synchronized fun read(id: String): NetworkProfile? {
        val value = preferences.getString(id, null) ?: return null
        try {
            val (iv, encrypted) = value.split(':', limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))); updateAAD(id.toByteArray())
            }
            val data = JSONObject(String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8))
            return NetworkProfile(id, data.getString("address"), NetworkCredentials(data.getBoolean("guest"),
                data.getString("username"), data.getString("password"), data.getString("domain"), data.getLong("uid"), data.getLong("gid")))
        } catch (_: Exception) { throw RemoteAccessException("网络认证信息无法解密，请编辑该位置并重新输入账号密码") }
    }
    @Synchronized fun remove(id: String) { check(preferences.edit().remove(id).commit()) { "无法删除网络认证信息" } }
}
