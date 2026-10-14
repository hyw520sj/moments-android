package com.moments.android.api

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** 登录成功后的信息 */
data class AuthResult(val token: String, val username: String, val userId: Int)

/** 一个待上传的本地文件 */
data class UploadFile(val fileName: String, val mimeType: String, val bytes: ByteArray)

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    data class Err(val message: String) : ApiResult<Nothing>()
}

/**
 * Moments 后端接口（与网页版 /api 完全一致）：
 * - POST /api/user/login
 * - POST /api/user/profile
 * - POST /api/file/upload   (multipart 字段名 "files")
 * - POST /api/memo/save
 */
class MomentsApi(private val baseUrl: String, private val trustInvalidCert: Boolean) {

    private val client: OkHttpClient by lazy {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
        if (trustInvalidCert) {
            val ctx = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
            }
            builder.sslSocketFactory(ctx.socketFactory, trustAllManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        builder.build()
    }

    private fun url(path: String): String {
        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return base + path.removePrefix("/")
    }

    /** 登录：{ code:0, data:{ token, username, id } } */
    fun login(username: String, password: String): ApiResult<AuthResult> {
        val body = JSONObject()
            .put("username", username)
            .put("password", password)
            .toString()
            .toRequestBody(JSON)
        val request = Request.Builder()
            .url(url("api/user/login"))
            .post(body)
            .build()
        return client.newCall(request).execute().use { response ->
            parse(response.body?.string().orEmpty()) { data ->
                val obj = data as? JSONObject ?: JSONObject()
                ApiResult.Ok(
                    AuthResult(
                        token = obj.optString("token"),
                        username = obj.optString("username"),
                        userId = obj.optInt("id")
                    )
                )
            }
        }
    }

    /** 当前登录用户信息 */
    fun profile(token: String): ApiResult<JSONObject> {
        val request = Request.Builder()
            .url(url("api/user/profile"))
            .header("x-api-token", token)
            .post("{}".toRequestBody(JSON))
            .build()
        return client.newCall(request).execute().use { response ->
            parse(response.body?.string().orEmpty()) { data ->
                ApiResult.Ok(data as? JSONObject ?: JSONObject())
            }
        }
    }

    /** 上传文件，返回服务器端可访问路径列表 */
    fun upload(token: String, files: List<UploadFile>): ApiResult<List<String>> {
        if (files.isEmpty()) return ApiResult.Ok(emptyList())
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
        files.forEach { file ->
            val media = runCatching { file.mimeType.toMediaType() }.getOrDefault(DEFAULT_MEDIA)
            multipart.addFormDataPart("files", file.fileName, file.bytes.toRequestBody(media))
        }
        val request = Request.Builder()
            .url(url("api/file/upload"))
            .header("x-api-token", token)
            .post(multipart.build())
            .build()
        return client.newCall(request).execute().use { response ->
            parse(response.body?.string().orEmpty()) { data ->
                val list = mutableListOf<String>()
                when (data) {
                    is JSONArray -> for (i in 0 until data.length()) list.add(data.optString(i))
                    is JSONObject -> {
                        val arr = data.optJSONArray("urls") ?: data.optJSONArray("files")
                        if (arr != null) for (i in 0 until arr.length()) list.add(arr.optString(i))
                    }
                }
                ApiResult.Ok(list)
            }
        }
    }

    /**
     * 保存 Memo。payload 参考网页端 components/MemoEdit.vue。
     */
    fun saveMemo(token: String, payload: JSONObject): ApiResult<Unit> {
        val request = Request.Builder()
            .url(url("api/memo/save"))
            .header("x-api-token", token)
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return client.newCall(request).execute().use { response ->
            parse(response.body?.string().orEmpty()) { ApiResult.Ok(Unit) }
        }
    }

    private inline fun <T> parse(raw: String, block: (Any) -> ApiResult<T>): ApiResult<T> {
        if (raw.isBlank()) return ApiResult.Err("服务器无响应")
        return try {
            val json = JSONObject(raw)
            val code = json.optInt("code", -1)
            if (code == 0) {
                val data: Any = json.opt("data") ?: JSONObject()
                block(data)
            } else {
                ApiResult.Err(json.optString("message").ifBlank { "请求失败($code)" })
            }
        } catch (e: Exception) {
            ApiResult.Err(e.message ?: "解析响应失败")
        }
    }

    private companion object {
        val JSON: MediaType = "application/json; charset=utf-8".toMediaType()
        val DEFAULT_MEDIA: MediaType = "application/octet-stream".toMediaType()
        val trustAllManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}
