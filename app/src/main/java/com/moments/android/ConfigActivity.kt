package com.moments.android

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputLayout
import com.moments.android.data.AppPrefs
import com.moments.android.data.ServerUrls
import com.moments.android.databinding.ActivityConfigBinding
import com.moments.android.util.HttpProbe
import com.moments.android.util.openExternalSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfigBinding
    private lateinit var prefs: AppPrefs

    private val isFirstLaunch: Boolean
        get() = intent?.getBooleanExtra(EXTRA_FIRST_LAUNCH, false) ?: false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)
        setSupportActionBar(binding.toolbar)

        if (!isFirstLaunch) {
            binding.toolbar.setNavigationIcon(
                androidx.appcompat.R.drawable.abc_ic_ab_back_material
            )
            binding.toolbar.setNavigationOnClickListener { finish() }
        }

        prefill()
        binding.saveButton.setOnClickListener { onSaveClick() }

        binding.openInBrowserButton.visibility =
            if (prefs.isConfigured) View.VISIBLE else View.GONE
        binding.openInBrowserButton.setOnClickListener { openExternalSafe(this, prefs.baseUrl) }
    }

    private fun prefill() {
        binding.portInput.setText(prefs.port?.toString() ?: AppPrefs.DEFAULT_PORT.toString())
        binding.pathInput.setText(prefs.path)
        if (prefs.isConfigured) {
            binding.hostInput.setText(prefs.host)
            if (prefs.scheme.equals("https", ignoreCase = true)) {
                binding.schemeGroup.check(R.id.btnHttps)
            } else {
                binding.schemeGroup.check(R.id.btnHttp)
            }
            binding.trustSslCheck.isChecked = prefs.trustInvalidCert
        }
    }

    private fun onSaveClick() {
        clearErrors()

        val host = binding.hostInput.text?.toString()?.trim().orEmpty()
        if (host.isBlank()) {
            setFieldError(binding.hostLayout, getString(R.string.config_err_host))
            return
        }

        val portText = binding.portInput.text?.toString()?.trim().orEmpty()
        val port = portText.toIntOrNull()
        if (portText.isNotEmpty() && (port == null || port <= 0 || port > 65535)) {
            setFieldError(binding.portLayout, getString(R.string.config_err_port))
            return
        }

        val scheme = if (binding.schemeGroup.checkedButtonId == R.id.btnHttps) "https" else "http"
        val path = binding.pathInput.text?.toString()?.trim().orEmpty()
        val trustSsl = binding.trustSslCheck.isChecked

        val baseUrl = ServerUrls.build(scheme, host, port, path)

        setTesting(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { HttpProbe.probe(baseUrl, trustSsl) }
            setTesting(false)

            if (result.code in 200..499) {
                prefs.save(scheme, host, port, path, trustSsl)
                Toast.makeText(this@ConfigActivity, R.string.config_result_started, Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            } else {
                val reason = result.error?.message
                    ?: result.error?.javaClass?.simpleName
                    ?: "HTTP ${result.code}"
                showResult(getString(R.string.config_result_fail, reason))
            }
        }
    }

    private fun clearErrors() {
        binding.hostLayout.error = null
        binding.portLayout.error = null
        showResult(null)
    }

    private fun setFieldError(layout: TextInputLayout, message: String) {
        layout.error = message
    }

    private fun showResult(text: String?) {
        binding.resultText.text = text
        binding.resultText.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun setTesting(testing: Boolean) {
        binding.saveButton.isEnabled = !testing
        binding.testingBar.visibility = if (testing) View.VISIBLE else View.GONE
        showResult(if (testing) getString(R.string.config_testing) else null)
    }

    companion object {
        const val EXTRA_FIRST_LAUNCH = "extra_first_launch"
    }
}
