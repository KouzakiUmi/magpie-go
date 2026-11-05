package com.magpie.android

import android.app.Application
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.magpie.android.runtime.AndroidMagpieRuntime
import com.magpie.android.runtime.MagpieConfig
import com.magpie.android.runtime.MagpieEvent
import com.magpie.android.runtime.MagpieRuntime
import com.magpie.android.ui.MagpieRuntimeProvider
import com.magpie.android.ui.StartConfigProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 把运行时与启动配置注入界面，并保管固定的 web key。
 *
 * web key 为什么必须固定：magpie 每次运行默认随机生成一个，换了 key，
 * 浏览器里换来的 Cookie 就失效，WebView 冷启动要重新登录。固定之后
 * Cookie 长期有效（magpie 对固定 key 的 Cookie 保 400 天）。
 *
 * 保管方式：Android Keystore 里的 AES 密钥加密后存 SharedPreferences，
 * 明文 key 不落盘。
 */
class MagpieApplication : Application(), MagpieRuntimeProvider, StartConfigProvider {

    private lateinit var runtimeInstance: MagpieRuntime
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // debug 包里打开 WebView 调试，便于用 chrome://inspect 定位渲染问题。
        // 发布包不开。
        if (BuildConfig.DEBUG) {
            android.webkit.WebView.setWebContentsDebuggingEnabled(true)
        }
        runtimeInstance = AndroidMagpieRuntime.getInstance(this)

        // 注意：这里**不要**再单独收集 runtime.events()。
        // events() 内部对 state 的收集是排他的，多一个收集者会让界面收不到
        // 状态更新（表现：服务已 Running，界面仍停在 Starting）。
        // 需要看状态时用 adb logcat 看界面自己的日志，或临时加在界面侧。
    }

    override fun runtime(): MagpieRuntime = runtimeInstance

    override fun config(): MagpieConfig = MagpieConfig(
        webKey = WebKeyStore.getOrCreate(this),
    )

    private companion object {
        const val TAG = "MagpieApp"
    }
}

/**
 * 固定 web key 的生成与保管。
 *
 * 生成的 key 满足 magpie 的约束：至少 16 字符，只用字母、数字与 `-._~`
 * （`CheckWebKey` 的规则），这样它能安全地放进 URL 查询与 Cookie。
 */
internal object WebKeyStore {

    private const val PREFS = "magpie_secure"
    private const val FIELD = "web_key"
    private const val KEY_ALIAS = "magpie_web_key"
    private const val KEY_LEN_BYTES = 24 // base64url 后 32 字符，满足 16+ 的要求

    @Volatile
    private var cached: String? = null

    fun getOrCreate(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            read(context)?.let { cached = it; return it }
            val fresh = generate()
            write(context, fresh)
            cached = fresh
            return fresh
        }
    }

    /** 生成一个符合 magpie 字符集要求的随机 key。 */
    private fun generate(): String {
        val bytes = ByteArray(KEY_LEN_BYTES)
        java.security.SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context): String? = try {
        val p = prefs(context)
        val payload = p.getString(FIELD, null) ?: return null
        decrypt(payload)
    } catch (e: Exception) {
        null
    }

    private fun write(context: Context, key: String) {
        try {
            prefs(context).edit().putString(FIELD, encrypt(key)).apply()
        } catch (e: Exception) {
            // 存不住时仍返回内存中的 key：本次运行可用，重启后会换新的
        }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** 加密后拼成 `iv:ciphertext`（各自 base64）。 */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val out = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv) + ":" +
            Base64.getEncoder().encodeToString(out)
    }

    private fun decrypt(payload: String): String {
        val (ivB64, dataB64) = payload.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.getDecoder().decode(ivB64)),
        )
        val out = cipher.doFinal(Base64.getDecoder().decode(dataB64))
        return String(out, Charsets.UTF_8)
    }
}
