package com.moments.android.web

import android.net.Uri
import android.os.Message
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * 负责进度、标题、文件选择（头像/图片/视频上传）、视频全屏、JS 弹窗。
 */
class MomentsWebChromeClient(
    private val host: WebHost,
    private val onProgressChanged: (Int) -> Unit,
    private val onTitleReceived: (String) -> Unit
) : WebChromeClient() {

    private var customViewCallback: CustomViewCallback? = null

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        onProgressChanged(newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String) {
        if (title.isNotBlank()) onTitleReceived(title)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean {
        return try {
            host.openFileChooser(fileChooserParams, filePathCallback)
            true
        } catch (e: Exception) {
            Log.w(TAG, "open file chooser failed", e)
            filePathCallback.onReceiveValue(null)
            true
        }
    }

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message
    ): Boolean {
        return host.createPopupWindow(resultMsg)
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        customViewCallback = callback
        host.enterFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        host.exitFullscreen()
    }

    override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        host.showJsAlert(message, result)
        return true
    }

    override fun onJsConfirm(
        view: WebView,
        url: String,
        message: String,
        result: JsResult
    ): Boolean {
        host.showJsConfirm(message, result)
        return true
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        Log.d(TAG, "[console] ${consoleMessage.message()} (line ${consoleMessage.lineNumber()})")
        return false
    }

    private companion object {
        const val TAG = "MomentsWeb"
    }
}
