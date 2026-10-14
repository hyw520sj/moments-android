package com.moments.android.util

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

data class ProbeResult(
    /** HTTP 状态码，连接失败时为 -1 */
    val code: Int,
    val bodySnippet: String = "",
    val error: Throwable? = null
) {
    /** 服务器有响应（即使是 401/404，也说明地址与端口是通的） */
    val reachable: Boolean get() = code > 0
}

/**
 * 配置页用来探测服务器是否可达。
 */
object HttpProbe {

    fun probe(url: String, trustInvalidCert: Boolean): ProbeResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 6000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "MomentsAndroid/1.0")

            if (trustInvalidCert && connection is HttpsURLConnection) {
                connection.sslSocketFactory = TrustAllCerts.socketFactory
                connection.hostnameVerifier = { _, _ -> true }
            }

            val code = connection.responseCode
            val body = try {
                (if (code in 200..399) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
                    ?.take(2000)
                    .orEmpty()
            } catch (e: IOException) {
                ""
            }
            ProbeResult(code, body)
        } catch (e: Exception) {
            ProbeResult(-1, "", e)
        } finally {
            connection?.disconnect()
        }
    }
}

internal object TrustAllCerts {

    private val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }.socketFactory
    }
}
