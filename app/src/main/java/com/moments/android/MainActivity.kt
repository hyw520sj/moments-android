package com.moments.android

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.view.ContextMenu
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsResult
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.moments.android.data.AppPrefs
import com.moments.android.data.ServerUrls
import com.moments.android.databinding.ActivityMainBinding
import com.moments.android.util.openExternalSafe
import com.moments.android.web.MomentsWebChromeClient
import com.moments.android.web.MomentsWebViewClient
import com.moments.android.web.WebHost
import com.moments.android.web.applyMomentsSettings
import com.moments.android.web.stripWebViewUserAgent
import org.json.JSONObject
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity(), WebHost {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs

    private var chromeClient: MomentsWebChromeClient? = null
    private val popupWebViews = mutableListOf<WebView>()

    // <input type="file">
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraUri: Uri? = null

    // 视频全屏
    private var fullscreenView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var lastBackPressedAt = 0L
    private var pendingDownload: PendingDownload? = null
    private var syncedWebToken: String? = null

    private val configLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (prefs.isConfigured) loadSite() else showError(getString(R.string.main_no_host))
    }

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && prefs.isLoggedIn) syncWebLoginState()
    }

    private val composeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            if (binding.webView.url?.let { ServerUrls.isSameSite(prefs.baseUrl, it) } != true) {
                navigate("/")
            } else {
                binding.webView.reload()
            }
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        deliverFileResult(result.resultCode, result.data)
    }

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingDownload
        pendingDownload = null
        if (granted && pending != null) {
            startDownload(pending)
        } else if (!granted) {
            Toast.makeText(this, R.string.need_storage_permission, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)
        setSupportActionBar(binding.toolbar)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        // 沉浸式：系统栏留白由我们自己按 insets 精确补齐，根治顶部文字压状态栏
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setupInsets()

        setupWebView()
        setupSwipeRefresh()
        setupNavigation()
        setupBackPressed()

        binding.btnRetry.setOnClickListener { loadSite() }
        binding.btnReconfigure.setOnClickListener { openConfig() }

        if (prefs.isConfigured) loadSite() else openConfig(firstLaunch = true)
    }

    // ------------------------------------------------------------------ 初始化

    private fun setupWebView() {
        val webView = binding.webView
        webView.applyMomentsSettings()
        webView.stripWebViewUserAgent()

        chromeClient = MomentsWebChromeClient(
            host = this,
            onProgressChanged = { updateProgress(it) },
            onTitleReceived = { binding.toolbar.title = it }
        )

        webView.webChromeClient = chromeClient
        webView.webViewClient = MomentsWebViewClient(
            prefs = prefs,
            onPageStarted = {
                hideError()
                binding.swipeRefresh.isRefreshing = true
            },
            onPageFinished = {
                binding.swipeRefresh.isRefreshing = false
                CookieManager.getInstance().flush()
                syncWebLoginState()
            },
            onMainFrameError = { code, description ->
                showError(
                    if (description.isBlank()) {
                        getString(R.string.main_error_msg, "ERROR_$code")
                    } else {
                        getString(R.string.main_error_msg, description)
                    }
                )
            },
            onOpenExternal = { url -> openExternalSafe(this, url) },
            confirmSslError = { handler, description -> confirmSsl(handler, description) }
        )

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            requestDownload(PendingDownload(url, userAgent, contentDisposition, mimeType))
        }
        registerForContextMenu(webView)
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            hideError()
            binding.webView.reload()
        }
    }

    /** 统一转发到 confirmSslError（弹自签名证书确认框） */
    private fun confirmSsl(handler: SslErrorHandler, description: String): Boolean {
        return confirmSslError(handler, description)
    }

    /**
     * 系统栏留白：
     * - 顶栏按状态栏高度加 top padding（修复标题与状态栏重叠）
     * - 底栏按导航栏手势区加 bottom padding
     * - 内容区避开底栏 + 手势区
     * - 悬浮发布按钮整体上移同样高度
     */
    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            binding.appBar.setPadding(0, statusTop, 0, 0)
            binding.bottomNav.setPadding(0, 0, 0, navBottom)
            binding.contentHost.setPadding(0, 0, 0, navBottom + dp(BOTTOM_BAR_HEIGHT_DP))

            val params = binding.fabCompose.layoutParams as? CoordinatorLayout.LayoutParams
            if (params != null) {
                params.bottomMargin = dp(FAB_MARGIN_DP) + navBottom
                binding.fabCompose.layoutParams = params
            }
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun setupNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> navigate("/")
                R.id.nav_calendar -> navigate("/user/calendar")
                R.id.nav_me ->
                    if (prefs.isLoggedIn) navigate("/user/${prefs.userId}") else openLogin()

                R.id.nav_compose -> return@setOnItemSelectedListener false
                else -> return@setOnItemSelectedListener false
            }
            true
        }
        binding.bottomNav.setOnItemReselectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> binding.webView.reload()
                else -> Unit
            }
        }
        binding.fabCompose.setOnClickListener { openCompose() }
    }

    /** 在当前站点内跳转（站内路由交给 SPA 自己渲染） */
    private fun navigate(path: String) {
        if (!prefs.isConfigured) {
            openConfig(firstLaunch = true)
            return
        }
        hideError()
        val url = prefs.baseUrl.trimEnd('/') + if (path.startsWith("/")) path else "/$path"
        if (binding.webView.url == url) {
            binding.webView.reload()
        } else {
            binding.webView.loadUrl(url)
        }
    }

    private fun openLogin() {
        if (!prefs.isConfigured) {
            Toast.makeText(this, R.string.need_server, Toast.LENGTH_SHORT).show()
            openConfig(firstLaunch = true)
            return
        }
        loginLauncher.launch(Intent(this, LoginActivity::class.java))
    }

    private fun openCompose() {
        if (!prefs.isLoggedIn) {
            openLogin()
            return
        }
        composeLauncher.launch(Intent(this, ComposeActivity::class.java))
    }

    /**
     * 原生登录后把 token 写进 WebView 的 localStorage（网页端 useStorage('global')），
     * 这样页面一刷新就是已登录状态，APP 与网页两种入口行为一致。
     */
    private fun syncWebLoginState() {
        if (!prefs.isLoggedIn) return
        if (syncedWebToken == prefs.token) return
        // 只在站点页面内注入，避免 about:blank 上写 localStorage 失败却标记成已同步
        val current = binding.webView.url ?: return
        if (!ServerUrls.isSameSite(prefs.baseUrl, current)) return
        val payload = JSONObject()
            .put(
                "userinfo", JSONObject()
                    .put("token", prefs.token)
                    .put("username", prefs.username)
                    .put("id", prefs.userId)
            )
            .toString()
        val js = "try{localStorage.setItem('global', ${JSONObject.quote(payload)});}catch(e){}"
        syncedWebToken = prefs.token
        binding.webView.evaluateJavascript(js) { binding.webView.reload() }
    }

    private fun clearWebLoginState() {
        prefs.clearAuth()
        syncedWebToken = null
        binding.webView.evaluateJavascript(
            "try{localStorage.removeItem('global');}catch(e){}"
        ) { binding.webView.reload() }
    }

    private fun setupBackPressed() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    fullscreenView != null -> chromeClient?.onHideCustomView()
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    else -> {
                        val now = System.currentTimeMillis()
                        if (now - lastBackPressedAt < 2000) {
                            finish()
                        } else {
                            lastBackPressedAt = now
                            Toast.makeText(
                                this@MainActivity,
                                R.string.main_exit_hint,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        })
    }

    // ------------------------------------------------------------------ 页面控制

    private fun loadSite() {
        if (!prefs.isConfigured) {
            showError(getString(R.string.main_no_host))
            return
        }
        hideError()
        binding.toolbar.subtitle = runCatching { Uri.parse(prefs.baseUrl).host }.getOrNull()
        binding.webView.loadUrl(prefs.baseUrl)
    }

    private fun updateProgress(progress: Int) {
        val isLoading = progress in 1..99
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.progressBar.progress = progress
    }

    private fun showError(message: String) {
        binding.swipeRefresh.isRefreshing = false
        binding.progressBar.visibility = View.GONE
        binding.errorMessage.text = message
        binding.errorView.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.errorView.visibility = View.GONE
    }

    private fun openConfig(firstLaunch: Boolean = false) {
        configLauncher.launch(
            Intent(this, ConfigActivity::class.java)
                .putExtra(ConfigActivity.EXTRA_FIRST_LAUNCH, firstLaunch)
        )
    }

    // ------------------------------------------------------------------ 菜单

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_login)?.isVisible = !prefs.isLoggedIn
        menu.findItem(R.id.action_logout)?.isVisible = prefs.isLoggedIn
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                hideError()
                binding.webView.reload()
                true
            }

            R.id.action_login -> {
                openLogin()
                true
            }

            R.id.action_logout -> {
                clearWebLoginState()
                Toast.makeText(this, R.string.logged_out, Toast.LENGTH_SHORT).show()
                true
            }

            R.id.action_tags -> {
                if (prefs.username.isBlank()) openLogin() else navigate("/tags/${prefs.username}")
                true
            }

            R.id.action_friend -> {
                navigate("/friend")
                true
            }

            R.id.action_settings -> {
                navigate("/sys/settings")
                true
            }

            R.id.action_reconfigure -> {
                openConfig()
                true
            }

            R.id.action_open_in_browser -> {
                openExternalSafe(this, binding.webView.url ?: prefs.baseUrl)
                true
            }

            R.id.action_exit -> {
                finishAffinity()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    // ------------------------------------------------------------------ 长按菜单

    override fun onCreateContextMenu(
        menu: ContextMenu,
        v: View,
        menuInfo: ContextMenu.ContextMenuInfo?
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val result = binding.webView.hitTestResult
        val target = result.extra ?: return
        when (result.type) {
            WebView.HitTestResult.IMAGE_TYPE,
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                menu.setHeaderTitle(R.string.ctx_image_title)
                menu.add(Menu.NONE, CTX_SAVE_IMAGE, 0, R.string.ctx_save_image)
                menu.add(Menu.NONE, CTX_OPEN_IMAGE, 0, R.string.ctx_open_image)
                menu.add(Menu.NONE, CTX_COPY_LINK, 0, R.string.ctx_copy_link)
            }

            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                menu.setHeaderTitle(target)
                menu.add(Menu.NONE, CTX_OPEN_LINK, 0, R.string.ctx_open_link)
                menu.add(Menu.NONE, CTX_COPY_LINK, 0, R.string.ctx_copy_link)
            }

            else -> Unit
        }
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        val target = binding.webView.hitTestResult.extra ?: return super.onContextItemSelected(item)
        when (item.itemId) {
            CTX_SAVE_IMAGE -> {
                requestDownload(PendingDownload(target, null, null, "image/*"))
                return true
            }

            CTX_OPEN_IMAGE, CTX_OPEN_LINK -> {
                openExternalSafe(this, target)
                return true
            }

            CTX_COPY_LINK -> {
                (getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText("url", target))
                Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
                return true
            }
        }
        return super.onContextItemSelected(item)
    }

    // ------------------------------------------------------------------ 文件上传

    override fun openFileChooser(
        params: WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<Uri>>
    ) {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = callback

        val acceptTypes = params.acceptTypes
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE
        val anyType = acceptTypes.isEmpty() || acceptTypes.any { it == "*/*" }
        val wantsImage = anyType || acceptTypes.any { it.startsWith("image", ignoreCase = true) }
        val wantsVideo = anyType || acceptTypes.any { it.startsWith("video", ignoreCase = true) }

        // 单选时可以顺带给个「拍照/录像」入口
        val initialIntents = mutableListOf<Intent>()
        if (!multiple) {
            if (wantsImage) {
                createCaptureIntent(MediaStore.ACTION_IMAGE_CAPTURE, ".jpg")
                    ?.let { initialIntents.add(it) }
            }
            if (wantsVideo) {
                createCaptureIntent(MediaStore.ACTION_VIDEO_CAPTURE, ".mp4")
                    ?.let { initialIntents.add(it) }
            }
        }

        val mimeType = when {
            acceptTypes.any { it.startsWith("image", ignoreCase = true) } -> "image/*"
            acceptTypes.any { it.startsWith("video", ignoreCase = true) } -> "video/*"
            else -> "*/*"
        }

        val picker = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
            if (acceptTypes.isNotEmpty()) {
                putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes.toTypedArray())
            }
        }

        val chooser = Intent.createChooser(picker, getString(R.string.chooser_title))
        if (initialIntents.isNotEmpty()) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toTypedArray())
        }

        try {
            fileChooserLauncher.launch(chooser)
        } catch (e: ActivityNotFoundException) {
            filePathCallback = null
            callback.onReceiveValue(null)
            Toast.makeText(this, R.string.chooser_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createCaptureIntent(action: String, extension: String): Intent? {
        val resolvable = packageManager.queryIntentActivities(Intent(action), 0)
        if (resolvable.isEmpty()) return null

        val file = try {
            File.createTempFile(if (extension == ".jpg") "IMG_" else "VID_", extension, cacheDir)
        } catch (e: IOException) {
            return null
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        pendingCameraUri = uri

        return Intent(action).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun deliverFileResult(resultCode: Int, data: Intent?) {
        val callback = filePathCallback ?: return
        filePathCallback = null

        val uris = mutableListOf<Uri>()
        if (resultCode == RESULT_OK) {
            val clipData = data?.clipData
            if (clipData != null && clipData.itemCount > 0) {
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i)?.uri?.let { uris.add(it) }
                }
            } else {
                data?.data?.let { uris.add(it) }
                if (uris.isEmpty()) pendingCameraUri?.let { uris.add(it) }
            }
        }
        pendingCameraUri = null
        callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    // ------------------------------------------------------------------ 视频全屏

    override fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (fullscreenView != null) {
            callback.onCustomViewHidden()
            return
        }
        fullscreenView = view
        customViewCallback = callback

        binding.fullscreenContainer.apply {
            removeAllViews()
            addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            )
            visibility = View.VISIBLE
        }

        WindowInsetsControllerCompat(window, binding.root)
            .hide(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun exitFullscreen() {
        if (fullscreenView == null) return
        binding.fullscreenContainer.apply {
            removeAllViews()
            visibility = View.GONE
        }
        fullscreenView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null

        WindowInsetsControllerCompat(window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------------------------------ JS 弹窗 / 证书

    override fun showJsAlert(message: String, result: JsResult) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .setOnCancelListener { result.cancel() }
            .show()
    }

    override fun showJsConfirm(message: String, result: JsResult) {
        MaterialAlertDialogBuilder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
    }

    override fun confirmSslError(handler: SslErrorHandler, description: String): Boolean {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ssl_title)
            .setMessage(getString(R.string.ssl_message, description))
            .setPositiveButton(R.string.ssl_always) { _, _ ->
                prefs.save(prefs.scheme, prefs.host, prefs.port, prefs.path, true)
                handler.proceed()
            }
            .setNeutralButton(R.string.ssl_once) { _, _ -> handler.proceed() }
            .setNegativeButton(R.string.ssl_cancel) { _, _ -> handler.cancel() }
            .setOnCancelListener { handler.cancel() }
            .show()
        return true
    }

    // ------------------------------------------------------------------ 新窗口

    /**
     * window.open / target="_blank"：同站内容回到主 WebView，站外内容交给系统浏览器。
     */
    override fun createPopupWindow(resultMsg: Message?): Boolean {
        val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
        val popup = WebView(this).apply {
            applyMomentsSettings()
            stripWebViewUserAgent()
            webViewClient = object : MomentsWebViewClient(
                prefs = prefs,
                onPageStarted = {},
                onPageFinished = {},
                onMainFrameError = { _, _ -> },
                onOpenExternal = { url ->
                    openExternalSafe(this@MainActivity, url)
                    destroyPopup(this)
                },
                confirmSslError = { handler, description -> confirmSsl(handler, description) }
            ) {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: android.webkit.WebResourceRequest
                ): Boolean {
                    val url = request.url?.toString().orEmpty()
                    if (ServerUrls.isSameSite(prefs.baseUrl, url)) {
                        binding.webView.loadUrl(url)
                    } else {
                        openExternalSafe(this@MainActivity, url)
                    }
                    destroyPopup(view)
                    return true
                }
            }
        }
        popupWebViews.add(popup)
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    private fun destroyPopup(view: WebView) {
        popupWebViews.remove(view)
        runCatching { view.stopLoading() }
        runCatching { view.destroy() }
    }

    // ------------------------------------------------------------------ 下载

    data class PendingDownload(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?
    )

    private fun requestDownload(download: PendingDownload) {
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = download
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        startDownload(download)
    }

    private fun startDownload(download: PendingDownload) {
        try {
            val request = DownloadManager.Request(Uri.parse(download.url)).apply {
                runCatching {
                    addRequestHeader(
                        "User-Agent",
                        download.userAgent
                            ?: android.webkit.WebSettings.getDefaultUserAgent(this@MainActivity)
                    )
                }
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setMimeType(download.mimeType)
                val name = URLUtil.guessFileName(
                    download.url,
                    download.contentDisposition,
                    download.mimeType
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Moments/$name")
                allowScanningByMediaScanner()
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            openExternalSafe(this, download.url)
        }
    }

    // ------------------------------------------------------------------ 生命周期

    override fun onResume() {
        super.onResume()
        binding.webView.resumeTimers()
    }

    override fun onPause() {
        binding.webView.pauseTimers()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        popupWebViews.toList().forEach { destroyPopup(it) }
        (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
        runCatching { binding.webView.destroy() }
        super.onDestroy()
    }

    private companion object {
        const val CTX_SAVE_IMAGE = 1
        const val CTX_OPEN_IMAGE = 2
        const val CTX_COPY_LINK = 3
        const val CTX_OPEN_LINK = 4
        const val BOTTOM_BAR_HEIGHT_DP = 56
        const val FAB_MARGIN_DP = 24
    }
}
