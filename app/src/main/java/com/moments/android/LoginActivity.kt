package com.moments.android

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import com.moments.android.api.ApiResult
import com.moments.android.api.MomentsApi
import com.moments.android.data.AppPrefs
import com.moments.android.databinding.ActivityLoginBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 原生登录页：直接调用 /api/user/login，拿到 token 后写入 AppPrefs。
 * 登录成功后会把 token 同步给 WebView（见 MainActivity.syncWebLoginState）。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var prefs: AppPrefs
    private lateinit var api: MomentsApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)
        api = MomentsApi(prefs.baseUrl, prefs.trustInvalidCert)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyTopInset(binding.toolbar)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.serverText.text = prefs.baseUrl
        binding.loginButton.setOnClickListener { doLogin() }
        binding.skipButton.setOnClickListener { finish() }

        binding.usernameInput.doAfterTextChanged { clearError(binding.usernameLayout) }
        binding.passwordInput.doAfterTextChanged { clearError(binding.passwordLayout) }

        if (!prefs.isConfigured) {
            Toast.makeText(this, R.string.need_server, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (prefs.username.isNotBlank()) binding.usernameInput.setText(prefs.username)
    }

    private fun clearError(layout: TextInputLayout) {
        layout.error = null
        binding.resultText.visibility = View.GONE
    }

    private fun doLogin() {
        val username = binding.usernameInput.text?.toString()?.trim().orEmpty()
        val password = binding.passwordInput.text?.toString().orEmpty()

        if (username.isBlank() || password.isBlank()) {
            binding.resultText.apply {
                text = getString(R.string.login_err_empty)
                visibility = View.VISIBLE
            }
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { api.login(username, password) }
                    .getOrElse { ApiResult.Err(it.message ?: "网络异常") }
            }
            setLoading(false)
            when (result) {
                is ApiResult.Ok -> {
                    prefs.saveAuth(result.value.token, result.value.username, result.value.userId)
                    Toast.makeText(this@LoginActivity, R.string.login_ok, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }

                is ApiResult.Err -> {
                    binding.resultText.apply {
                        text = getString(R.string.login_fail, result.message)
                        visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.loginButton.isEnabled = !loading
        binding.loginProgress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    /** 顶部栏按状态栏高度补内边距，避免标题压在状态栏上 */
    private fun applyTopInset(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }
    }
}
