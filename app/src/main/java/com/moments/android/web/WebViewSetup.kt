package com.moments.android.web

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * 让 WebView 的浏览器能力尽量对齐 Chrome：JS / localStorage / 视频自动播放 /
 * 混合内容 / 多窗口 / 缩放 / 缓存等。
 */
@SuppressLint("SetJavaScriptEnabled")
fun WebView.applyMomentsSettings() {
    settings.apply {
        // 基础能力
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        loadsImagesAutomatically = true

        // 网页中 target="_blank" / window.open 需要
        javaScriptCanOpenWindowsAutomatically = true
        setSupportMultipleWindows(true)

        // 视口与缩放
        useWideViewPort = true
        loadWithOverviewMode = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        textZoom = 100

        // 音视频：Moments 支持音乐 / 视频
        mediaPlaybackRequiresUserGesture = false

        // 有些内容通过 http 引用（如自建音乐源），允许混合内容
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        // 跟随服务端缓存头
        cacheMode = WebSettings.LOAD_DEFAULT

        setNeedInitialFocus(false)
        allowContentAccess = true
        allowFileAccess = true
    }

    // Cookie：登录态依赖 localStorage，第三方资源（音乐、豆瓣卡片）可能写 cookie
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
}

/**
 * 去掉 UA 里的 "; wv" 标记，让服务端把它当普通 Chrome 处理。
 * 部分验证码 / CDN 会针对 WebView UA 做限制。
 */
fun WebView.stripWebViewUserAgent() {
    val defaultUa = WebSettings.getDefaultUserAgent(context)
    settings.userAgentString = defaultUa.replace("; wv", "")
}
