package com.moments.android.ui

import android.content.Context
import android.net.Uri
import android.util.Size
import android.os.Build
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.moments.android.R
import com.moments.android.databinding.ItemAttachmentBinding

/** 一篇 memo 里选中的本地附件 */
data class Attachment(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val isVideo: Boolean
)

class AttachmentAdapter(
    private val items: MutableList<Attachment>,
    private val onRemove: (Attachment) -> Unit
) : RecyclerView.Adapter<AttachmentAdapter.Holder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemAttachmentBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemAttachmentBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: Attachment) {
            loadThumb(binding.root.context, binding.thumb, item)
            binding.remove.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                onRemove(items[pos])
            }
        }
    }

    private fun loadThumb(context: Context, view: ImageView, item: Attachment) {
        view.setImageDrawable(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val size = Size(240, 240)
                val bmp = if (item.isVideo) {
                    context.contentResolver.loadThumbnail(item.uri, size, null)
                } else {
                    context.contentResolver.loadThumbnail(item.uri, size, null)
                }
                view.setImageBitmap(bmp)
                return
            }
        }
        view.setImageResource(
            if (item.isVideo) R.drawable.ic_video else R.drawable.ic_image
        )
    }

    companion object {
        /** 从 content:// 取一个可读的文件名 */
        fun resolveName(context: Context, uri: Uri, fallback: String): String {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) {
                    val name = cursor.getString(index)
                    if (!name.isNullOrBlank()) return name
                }
            }
            return fallback
        }
    }
}
