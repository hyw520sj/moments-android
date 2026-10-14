package com.moments.android.data

import android.net.Uri

/**
 * 服务器地址拼装 / 判断工具。
 */
object ServerUrls {

    /**
     * 拼装成 base URL，结果一定以 "/" 结尾，例如 http://192.168.1.10:3000/moments/
     */
    fun build(scheme: String, host: String, port: Int?, path: String): String {
        var cleanHost = host.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .trim()
            .trimEnd('/')

        val defaultPort = if (scheme.equals("https", true)) 443 else 80
        val portPart = port
            ?.takeIf { it > 0 && it <= 65535 && it != defaultPort }
            ?.let { ":$it" }
            ?: ""

        val cleanPath = path.trim()
        val pathPart = when {
            cleanPath.isEmpty() || cleanPath == "/" -> "/"
            cleanPath.startsWith("/") -> cleanPath.trimEnd('/')
            else -> "/${cleanPath.trimEnd('/')}"
        }

        val url = "$scheme://$cleanHost$portPart$pathPart"
        return if (url.endsWith("/")) url else "$url/"
    }

    /**
     * 是否属于同一个 Moments 站点（忽略协议与端口差异，只看主机名）。
     * 判定为同站则留在 APP 内加载，否则交给系统浏览器打开。
     */
    fun isSameSite(baseUrl: String, url: String): Boolean {
        val base = runCatching { Uri.parse(baseUrl) }.getOrNull()?.host ?: return false
        val other = runCatching { Uri.parse(url) }.getOrNull()?.host ?: return false
        return base.equals(other, ignoreCase = true)
    }
}
