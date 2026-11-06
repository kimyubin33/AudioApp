package com.kimyubin33.audioapp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import uniffi.audio_core.TokenStore
import uniffi.audio_core.TokenStoreException

/**
 * 로그인 토큰을 Android Keystore의 AES-GCM 키로 암호화해서 저장한다.
 * - 키는 Keystore 안에만 있고 앱이 꺼내볼 수 없다.
 * - 저장되는 건 (IV + 암호문)뿐이다. 평문 토큰은 디스크에 쓰지 않는다.
 * - 토큰·키·경로 등은 로그에 남기지 않는다.
 */
class KeystoreTokenStore(context: Context) : TokenStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadToken(): String? {
        val stored = prefs.getString(KEY_TOKEN, null) ?: return null
        return try {
            decrypt(Base64.decode(stored, Base64.NO_WRAP))
        } catch (e: Exception) {
            // 키가 사라졌거나(기기 이전·잠금 해제 방식 변경 등) 데이터가 손상됨 → 로그인 안 된 상태로 취급
            prefs.edit().remove(KEY_TOKEN).apply()
            null
        }
    }

    override fun saveToken(token: String) {
        try {
            val encrypted = Base64.encodeToString(encrypt(token), Base64.NO_WRAP)
            // commit(): 저장 성공 여부를 확인해야 하므로 동기 저장
            if (!prefs.edit().putString(KEY_TOKEN, encrypted).commit()) {
                throw TokenStoreException.Unavailable()
            }
        } catch (e: TokenStoreException) {
            throw e
        } catch (e: Exception) {
            throw TokenStoreException.Unavailable()
        }
    }

    override fun clearToken() {
        if (!prefs.edit().remove(KEY_TOKEN).commit()) {
            throw TokenStoreException.Unavailable()
        }
    }

    private fun encrypt(plain: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv // 매번 새로 만들어지는 12바이트
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return iv + body
    }

    private fun decrypt(data: ByteArray): String {
        require(data.size > IV_SIZE_BYTES)
        val iv = data.copyOfRange(0, IV_SIZE_BYTES)
        val body = data.copyOfRange(IV_SIZE_BYTES, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_SIZE_BITS, iv))
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
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
        const val KEY_ALIAS = "audio_app_token_key"
        const val PREFS_NAME = "secure_token" // 백업 제외 규칙의 파일명(secure_token.xml)과 같아야 한다
        const val KEY_TOKEN = "token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE_BYTES = 12
        const val TAG_SIZE_BITS = 128
    }
}
