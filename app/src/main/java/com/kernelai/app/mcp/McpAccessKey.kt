package com.kernelai.app.mcp

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.kernelai.app.data.writeAtomic
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stable across a system service restart; explicit disabling invalidates the old credential. */
internal class McpAccessKey(private val file: File) {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey("qingwei_mcp_access_v1", null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("qingwei_mcp_access_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun loadOrCreate(): String {
        if (file.exists()) {
            require(file.length() <= 4096)
            val bytes = Base64.getDecoder().decode(file.readText())
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8).also { require(it.length >= 32) }
        }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        writeAtomic(file, Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(token.toByteArray())))
        return token
    }
    @Synchronized fun clear() { if (file.exists()) require(file.delete()) { "旧访问令牌无法移除" } }
}
