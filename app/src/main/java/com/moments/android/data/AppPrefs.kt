package com.moments.android.data

import android.content.Context
import androidx.core.content.edit

/**
 * 服务器配置持久化。所有字段均直接读取 SharedPreferences，
 * 写入统一走 [save]，配置页回填表单也直接读这些字段。
 */
class AppPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    val scheme: String
        get() = prefs.getString(KEY_SCHEME, DEFAULT_SCHEME) ?: DEFAULT_SCHEME

    val host: String
        get() = prefs.getString(KEY_HOST, "") ?: ""

    /** 端口，未手动指定时为 null */
    val port: Int?
        get() = if (prefs.contains(KEY_PORT)) prefs.getInt(KEY_PORT, 0) else null

    /** 子路径，形如 "/moments"，未使用反向代理子路径时为空字符串 */
    val path: String
        get() = prefs.getString(KEY_PATH, "") ?: ""

    /** 是否忽略 HTTPS 证书错误（自签名证书场景） */
    val trustInvalidCert: Boolean
        get() = prefs.getBoolean(KEY_TRUST_SSL, false)

    // ---------------- 登录态（原生发布/上传用） ----------------

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        private set(value) {
            prefs.edit { putString(KEY_TOKEN, value) }
        }

    val username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""

    val userId: Int
        get() = prefs.getInt(KEY_USER_ID, 0)

    val isLoggedIn: Boolean
        get() = token.isNotBlank()

    fun saveAuth(token: String, username: String, userId: Int) {
        prefs.edit {
            putString(KEY_TOKEN, token)
            putString(KEY_USERNAME, username)
            putInt(KEY_USER_ID, userId)
        }
    }

    fun clearAuth() {
        prefs.edit {
            remove(KEY_TOKEN)
            remove(KEY_USERNAME)
            remove(KEY_USER_ID)
        }
    }

    val isConfigured: Boolean
        get() = host.isNotBlank()

    /** 最终打开的首地址，始终以 "/" 结尾 */
    val baseUrl: String
        get() = ServerUrls.build(scheme, host, port, path)

    fun save(scheme: String, host: String, port: Int?, path: String, trustInvalidCert: Boolean) {
        prefs.edit {
            putString(KEY_SCHEME, scheme)
            putString(KEY_HOST, host)
            putString(KEY_PATH, path)
            putBoolean(KEY_TRUST_SSL, trustInvalidCert)
            if (port == null) remove(KEY_PORT) else putInt(KEY_PORT, port)
        }
    }

    fun clearAll() {
        prefs.edit(commit = true) { clear() }
    }

    companion object {
        private const val PREF_NAME = "moments_app"
        private const val KEY_SCHEME = "scheme"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_PATH = "path"
        private const val KEY_TRUST_SSL = "trust_invalid_cert"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_USERNAME = "auth_username"
        private const val KEY_USER_ID = "auth_user_id"
        const val DEFAULT_SCHEME = "http"
        const val DEFAULT_PORT = 3000
    }
}
