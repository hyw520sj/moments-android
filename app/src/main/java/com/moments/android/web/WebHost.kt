package com.moments.android.web

import android.net.Uri
import android.os.Message
import android.view.View
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient

/**
 * WebView 需要宿主页（Activity）帮忙完成的动作。
 */
interface WebHost {

    /** 网页触发 <input type="file"> */
    fun openFileChooser(
        params: WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<Uri>>
    )

    /** 网页进入全屏（如视频全屏） */
    fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)

    /** 网页退出全屏 */
    fun exitFullscreen()

    fun showJsAlert(message: String, result: JsResult)

    fun showJsConfirm(message: String, result: JsResult)

    /** 自签名证书：返回 true 表示宿主页已经弹窗处理 */
    fun confirmSslError(handler: SslErrorHandler, description: String): Boolean

    /** window.open / target="_blank" 打开新窗口 */
    fun createPopupWindow(resultMsg: Message?): Boolean
}
