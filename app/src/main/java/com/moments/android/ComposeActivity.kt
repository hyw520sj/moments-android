package com.moments.android

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.moments.android.api.ApiResult
import com.moments.android.api.MomentsApi
import com.moments.android.api.UploadFile
import com.moments.android.data.AppPrefs
import com.moments.android.databinding.ActivityComposeBinding
import com.moments.android.ui.Attachment
import com.moments.android.ui.AttachmentAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 原生「写朋友圈」：选图/选视频 -> /api/file/upload -> /api/memo/save。
 * 载荷与网页端 components/MemoEdit.vue 保持一致。
 */
class ComposeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityComposeBinding
    private lateinit var prefs: AppPrefs
    private lateinit var api: MomentsApi

    private val attachments = mutableListOf<Attachment>()
    private lateinit var adapter: AttachmentAdapter
    private var publishing = false

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> uris.forEach { addAttachment(it, false) }; refreshAttachments() }

    private val pickVideo = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { addAttachment(it, true) }; refreshAttachments() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityComposeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPrefs(this)
        api = MomentsApi(prefs.baseUrl, prefs.trustInvalidCert)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        applyTopInset(binding.toolbar)
        applyBottomInset(binding.composeRoot)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.publishButton.setOnClickListener { publish() }
        binding.addImageButton.setOnClickListener { pickImages.launch("image/*") }
        binding.addVideoButton.setOnClickListener { pickVideo.launch("video/*") }

        setupMarkdownShortcuts()
        setupAttachments()

        if (!prefs.isLoggedIn) {
            Toast.makeText(this, R.string.compose_need_login, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // ------------------------------------------------------------------ UI

    private fun setupAttachments() {
        adapter = AttachmentAdapter(attachments) {
            attachments.remove(it)
            refreshAttachments()
        }
        binding.attachments.layoutManager = GridLayoutManager(this, 3)
        binding.attachments.adapter = adapter
        refreshAttachments()
    }

    private fun refreshAttachments() {
        adapter.notifyDataSetChanged()
    }

    private fun addAttachment(uri: Uri, isVideo: Boolean) {
        if (isVideo) {
            attachments.removeAll { it.isVideo }
        } else {
            if (attachments.count { !it.isVideo } >= MAX_IMAGES) {
                Toast.makeText(this, R.string.compose_too_many_images, Toast.LENGTH_SHORT).show()
                return
            }
        }
        val mime = contentResolver.getType(uri) ?: if (isVideo) "video/mp4" else "image/jpeg"
        val fallback = if (isVideo) "video.mp4" else "image.jpg"
        val name = AttachmentAdapter.resolveName(this, uri, fallback)
        attachments.add(Attachment(uri, name, mime, isVideo))
    }

    private fun setupMarkdownShortcuts() {
        binding.mdBold.setOnClickListener { wrapSelection("**", "**") }
        binding.mdQuote.setOnClickListener { prefixLine("> ") }
        binding.mdCode.setOnClickListener { wrapSelection("`", "`") }
        binding.mdLink.setOnClickListener {
            val start = binding.contentInput.selectionStart
            binding.contentInput.text?.insert(start, "[]()")
            binding.contentInput.setSelection(start + 1)
        }
        binding.mdList.setOnClickListener { prefixLine("- ") }
    }

    private fun wrapSelection(left: String, right: String) {
        val edit = binding.contentInput
        val start = edit.selectionStart.coerceAtLeast(0)
        val end = edit.selectionEnd.coerceAtLeast(start)
        val selected = edit.text?.subSequence(start, end)?.toString().orEmpty()
        edit.text?.replace(start, end, "$left$selected$right")
        edit.setSelection(start + left.length + selected.length)
    }

    private fun prefixLine(prefix: String) {
        val edit = binding.contentInput
        val start = edit.selectionStart.coerceAtLeast(0)
        edit.text?.insert(start, prefix)
        edit.setSelection(start + prefix.length)
    }

    private fun setStatus(text: CharSequence?, visible: Boolean) {
        binding.statusText.text = text
        binding.statusText.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ 发布

    private fun publish() {
        if (publishing) return
        val content = binding.contentInput.text?.toString()?.trim().orEmpty()
        if (content.isBlank()) {
            Toast.makeText(this, R.string.compose_empty, Toast.LENGTH_SHORT).show()
            return
        }
        if (!prefs.isLoggedIn) {
            Toast.makeText(this, R.string.compose_need_login, Toast.LENGTH_SHORT).show()
            return
        }

        publishing = true
        binding.publishButton.isEnabled = false
        setStatus(getString(R.string.compose_saving), true)

        val token = prefs.token
        val picked = attachments.toList()
        val tags = binding.tagsInput.text?.toString().orEmpty()
            .split(",", "，", " ")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val showType = if (binding.publicSwitch.isChecked) 1 else 0
        val pinned = binding.pinSwitch.isChecked

        lifecycleScope.launch {
            val outcome: ApiResult<Unit> = withContext(Dispatchers.IO) {
                uploadAndSave(token, content, tags, showType, pinned, picked)
            }
            publishing = false
            binding.publishButton.isEnabled = true
            when (outcome) {
                is ApiResult.Ok -> {
                    Toast.makeText(this@ComposeActivity, R.string.compose_success, Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK)
                    finish()
                }

                is ApiResult.Err -> {
                    setStatus(getString(R.string.compose_failed, outcome.message), true)
                }
            }
        }
    }

    private fun uploadAndSave(
        token: String,
        content: String,
        tags: List<String>,
        showType: Int,
        pinned: Boolean,
        picked: List<Attachment>
    ): ApiResult<Unit> {
        val images = picked.filter { !it.isVideo }
        val video = picked.firstOrNull { it.isVideo }

        // 上传顺序固定为「先图片后视频」，便于按返回顺序回填 URL
        val ordered = images + listOfNotNull(video)
        val files = ordered.mapNotNull { attachment ->
            val bytes = runCatching {
                contentResolver.openInputStream(attachment.uri)?.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null || bytes.isEmpty()) null
            else UploadFile(attachment.fileName, attachment.mimeType, bytes)
        }

        if (files.isNotEmpty()) {
            runOnUiThread {
                setStatus(getString(R.string.compose_uploading, 1, files.size), true)
            }
            when (val uploaded = api.upload(token, files)) {
                is ApiResult.Err -> return ApiResult.Err(uploaded.message)
                is ApiResult.Ok -> {
                    val urls = uploaded.value
                    var cursor = 0
                    val imgUrls = images.mapNotNull {
                        urls.getOrNull(cursor++)?.let { u -> if (u.isBlank()) null else u }
                    }
                    val videoUrl = if (video != null) urls.getOrNull(cursor)?.takeIf { it.isNotBlank() } else null
                    return save(token, content, tags, showType, pinned, imgUrls, videoUrl)
                }
            }
        }
        return save(token, content, tags, showType, pinned, emptyList(), null)
    }

    private fun save(
        token: String,
        content: String,
        tags: List<String>,
        showType: Int,
        pinned: Boolean,
        imgUrls: List<String>,
        videoUrl: String?
    ): ApiResult<Unit> {
        val ext = JSONObject()
        if (!videoUrl.isNullOrBlank()) {
            ext.put(
                "video", JSONObject()
                    .put("type", "online")
                    .put("value", videoUrl)
            )
        }
        val payload = JSONObject()
            .put("content", content)
            .put("ext", ext)
            .put("pinned", pinned)
            .put("showType", showType)
            .put("externalFavicon", "")
            .put("externalTitle", "")
            .put("externalUrl", "")
            .put("imgs", JSONArray().apply { imgUrls.forEach { put(it) } })
            .put("location", "")
            .put("tags", JSONArray().apply { tags.forEach { put(it) } })
            .put("createdAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        return api.saveMemo(token, payload)
    }

    // ------------------------------------------------------------------ 内边距

    private fun applyTopInset(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
            insets
        }
    }

    private fun applyBottomInset(view: View) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottom)
            insets
        }
    }

    private companion object {
        const val MAX_IMAGES = 9
    }
}
