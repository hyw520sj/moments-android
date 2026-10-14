package com.moments.android.web

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.moments.android.data.AppPrefs
import com.moments.android.data.ServerUrls

/**
 * 负责：
 * 1) 站点内链接留在 APP 内，站外链接交给系统浏览器；
 * 2) tel:/mailto:/intent: 等协议交给其它 APP；
 * 3) 主框架加载失败时通知宿主页显示错误页；
 * 4) 自签名证书提示。
 */
open class MomentsWebViewClient(
    private val prefs: AppPrefs,
    private val onPageStarted: () -> Unit,
    private val onPageFinished: () -> Unit,
    private val onMainFrameError: (code: Int, description: String) -> Unit,
    private val onOpenExternal: (String) -> Unit,
    private val confirmSslError: (handler: SslErrorHandler, description: String) -> Boolean
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url?.toString() ?: return false
        if (!request.isForMainFrame) return false
        return route(view.context, url)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
        return route(view.context, url)
    }

    private fun route(context: Context, url: String): Boolean {
        val isHttp = url.startsWith("http://", true) || url.startsWith("https://", true)
        if (isHttp) {
            // 同一台 Moments 服务器 -> 留在 APP 里
            if (ServerUrls.isSameSite(prefs.baseUrl, url)) return false
            onOpenExternal(url)
            return true
        }
        if (url.startsWith("intent://", true)) return openIntentUri(context, url)
        return openOtherApp(context, url)
    }

    private fun openIntentUri(context: Context, url: String): Boolean {
        return try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                component = null
                selector = null
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else {
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (!fallback.isNullOrEmpty()) {
                    onOpenExternal(fallback)
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun openOtherApp(context: Context, url: String): Boolean {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            )
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        onPageStarted()
    }

    override fun onPageFinished(view: WebView, url: String) {
        onPageFinished()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        if (request.isForMainFrame) {
            onMainFrameError(error.errorCode, error.description?.toString().orEmpty())
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        if (request.isForMainFrame && errorResponse.statusCode >= 500) {
            onMainFrameError(errorResponse.statusCode, "HTTP ${errorResponse.statusCode}")
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        if (prefs.trustInvalidCert) {
            handler.proceed()
            return
        }
        val handled = confirmSslError(handler, error.toString())
        if (!handled) handler.cancel()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        if (detail.didCrash()) {
            view.reload()
        }
        return true
    }
}
