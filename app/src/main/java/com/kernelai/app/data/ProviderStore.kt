package com.kernelai.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Provider credentials stay in private app storage, encrypted with an Android Keystore key. */
class ProviderStore(file: File) {
    private val data = AtomicJson(file)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("qingwei_providers_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("qingwei_providers_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    suspend fun load(): ProviderCatalog? = withContext(Dispatchers.IO) {
        data.read()?.let { encoded ->
            val bytes = Base64.getDecoder().decode(encoded)
            require(bytes.size > 28) { "供应商存储格式无效" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            json.decodeFromString<ProviderCatalog>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)).also { it.validate() }
        }
    }
    suspend fun save(catalog: ProviderCatalog) = withContext(Dispatchers.IO) {
        catalog.validate()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        data.write(Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(json.encodeToString(catalog).toByteArray(Charsets.UTF_8))))
    }
}
