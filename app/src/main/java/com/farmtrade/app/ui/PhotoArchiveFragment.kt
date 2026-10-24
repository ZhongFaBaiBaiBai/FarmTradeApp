package com.farmtrade.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.farmtrade.app.R
import com.farmtrade.app.data.DatabaseHelper
import com.farmtrade.app.data.PhotoNote
import com.farmtrade.app.databinding.FragmentPhotoArchiveBinding
import com.farmtrade.app.databinding.ItemNoteBinding
import com.farmtrade.app.databinding.ItemPhotoBinding
import com.farmtrade.app.view.ZoomImageView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「图片」页：保存单据照片 + 给照片写注释 + 单独写文字笔记。
 *
 * - 右下角「+」可选：从相册存图 / 写一条文字笔记
 * - 图片卡片：点照片全屏查看（双指缩放、双击放大、单指拖动），点注释行编辑注释
 * - 文字笔记卡片：点击即可编辑
 * - 注释与笔记都存在 SQLite 的 photo_notes 表里，图片文件存在 files/receipts/
 */
class PhotoArchiveFragment : Fragment() {

    private var _binding: FragmentPhotoArchiveBinding? = null
    private val binding get() = _binding!!

    private lateinit var dir: File
    private lateinit var db: DatabaseHelper
    private lateinit var adapter: ArchiveAdapter

    private val shortFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val fullFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) saveImage(uri)
    }

    /** 正在等待相机回传的文件 */
    private var pendingPhotoFile: java.io.File? = null

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) takePhoto() else toast("需要相机权限才能拍照")
    }

    private val takePicture = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val f = pendingPhotoFile
        if (success && f != null && f.exists() && f.length() > 0) {
            reload()
            toast("已保存")
        } else {
            f?.delete()
            if (success) toast("拍照失败")
        }
        pendingPhotoFile = null
    }

    /** 点「拍照」：先要权限，再拉起系统相机 */
    private fun requestCamera() {
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) takePhoto() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun takePhoto() {
        try {
            val f = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
            f.createNewFile()
            pendingPhotoFile = f
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                f
            )
            takePicture.launch(uri)
        } catch (e: Exception) {
            toast("无法启动相机：${e.message ?: "未知错误"}")
            pendingPhotoFile?.delete()
            pendingPhotoFile = null
        }
    }

    /** 列表行：图片 或 文字笔记，按时间倒序混排 */
    private sealed class Row {
        abstract val timeMs: Long
        data class Photo(val file: File, val noteItem: PhotoNote?, override val timeMs: Long) : Row()
        data class Note(val item: PhotoNote, override val timeMs: Long) : Row()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhotoArchiveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = DatabaseHelper(requireContext())
        dir = File(requireContext().filesDir, "receipts").apply { if (!exists()) mkdirs() }

        adapter = ArchiveAdapter()
        binding.rvPhotos.layoutManager = LinearLayoutManager(requireContext())
        binding.rvPhotos.adapter = adapter
        binding.fabAddPhoto.setOnClickListener { showAddMenu() }
        reload()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================== 数据 ====================

    private fun reload() {
        if (_binding == null) return
        val photos = dir.listFiles { f ->
            f.isFile && f.extension.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp")
        }?.sortedByDescending { it.name } ?: emptyList()

        val notes = db.getAllPhotoNotes()
        val noteByFile = notes.filter { !it.fileName.isNullOrBlank() }.associateBy { it.fileName!! }
        val textNotes = notes.filter { it.fileName.isNullOrBlank() }

        val rows = mutableListOf<Row>()
        photos.forEach { f ->
            rows.add(Row.Photo(f, noteByFile[f.name], fileTimeMs(f)))
        }
        textNotes.forEach { n ->
            rows.add(Row.Note(n, parseMs(n.createdAt)))
        }
        rows.sortByDescending { it.timeMs }
        adapter.submit(rows)

        val empty = rows.isEmpty()
        binding.llEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvPhotos.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvPhotoCount.text = "已保存 ${photos.size} 张照片 · ${textNotes.size} 条笔记"
    }

    private fun saveImage(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val f = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { out -> input.copyTo(out) }
                    }
                }
                reload()
                toast("已保存")
            } catch (e: Exception) {
                toast("保存失败：${e.message ?: "未知错误"}")
            }
        }
    }

    // ==================== 添加菜单 ====================

    private fun showAddMenu() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("添加")
            .setItems(arrayOf("拍照", "从相册选图片", "写一条文字笔记")) { _, which ->
                when (which) {
                    0 -> requestCamera()
                    1 -> pickImage.launch("image/*")
                    2 -> editNote(null, null)
                }
            }
            .show()
    }

    // ==================== 注释编辑 ====================

    /**
     * 编辑注释。fileName 为 null 时是独立的文字笔记。
     * @param existing 已有注释（存在则可删除）
     */
    private fun editNote(fileName: String?, existing: PhotoNote?) {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            setText(existing?.note ?: "")
            hint = if (fileName != null) "例如：麦种 济麦22" else "例如：今年用的是济麦22麦种"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            textSize = 15f
        }
        val container = FrameLayout(ctx).apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(input)
        }

        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle(if (fileName != null) "图片注释" else "文字笔记")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val text = input.text?.toString().orEmpty().trim()
                if (text.isEmpty()) {
                    toast("内容不能为空")
                    return@setPositiveButton
                }
                db.savePhotoNote(fileName, text, fullFmt.format(Date()))
                reload()
                toast("已保存")
            }
            .setNegativeButton("取消", null)

        if (existing != null) {
            builder.setNeutralButton("删除") { _, _ ->
                db.deletePhotoNote(existing.id)
                reload()
                toast("已删除")
            }
        }
        builder.show()
    }

    // ==================== 图片查看 / 删除 ====================

    private fun viewPhoto(row: Row.Photo) {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), 0)
        }
        val zoom = ZoomImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(400)
            )
        }
        Glide.with(this).load(row.file).into(zoom)
        container.addView(zoom)

        val noteText = row.noteItem?.note
        container.addView(TextView(ctx).apply {
            text = if (noteText.isNullOrBlank()) "（还没有注释，点「编辑注释」写一条）" else noteText
            textSize = 14f
            setTextColor(
                ContextCompat.getColor(ctx, if (noteText.isNullOrBlank()) R.color.text_hint else R.color.text_primary)
            )
            setPadding(0, dp(12), 0, dp(4))
            gravity = Gravity.START
        })

        MaterialAlertDialogBuilder(ctx)
            .setTitle("单据照片")
            .setView(container)
            .setPositiveButton("关闭", null)
            .setNeutralButton("编辑注释") { _, _ -> editNote(row.file.name, row.noteItem) }
            .setNegativeButton("删除图片") { _, _ -> confirmDeletePhoto(row.file) }
            .show()
    }

    private fun confirmDeletePhoto(file: File) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除图片")
            .setMessage("确定删除这张照片吗？对应的注释也会一起删掉。")
            .setPositiveButton("删除") { _, _ ->
                file.delete()
                db.deleteNoteByFile(file.name)
                reload()
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 工具 ====================

    private fun fileTimeMs(f: File): Long =
        f.name.removePrefix("IMG_").substringBefore('.').toLongOrNull() ?: f.lastModified()

    private fun parseMs(text: String): Long = try {
        fullFmt.parse(text)?.time ?: 0L
    } catch (_: Exception) {
        0L
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    // ==================== 适配器 ====================

    private inner class ArchiveAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val rows = mutableListOf<Row>()

        fun submit(list: List<Row>) {
            rows.clear()
            rows.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int =
            if (rows[position] is Row.Photo) TYPE_PHOTO else TYPE_NOTE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_PHOTO) {
                PhotoVH(ItemPhotoBinding.inflate(inflater, parent, false))
            } else {
                NoteVH(ItemNoteBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Photo -> (holder as PhotoVH).bind(row)
                is Row.Note -> (holder as NoteVH).bind(row)
            }
        }

        override fun getItemCount(): Int = rows.size

        inner class PhotoVH(private val b: ItemPhotoBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(row: Row.Photo) {
                if (_binding == null) return
                Glide.with(this@PhotoArchiveFragment)
                    .load(row.file)
                    .centerCrop()
                    .into(b.ivPhoto)
                b.tvPhotoTime.text = shortFmt.format(Date(row.timeMs))

                val note = row.noteItem?.note
                if (note.isNullOrBlank()) {
                    b.tvPhotoNote.text = "点这里写注释，例如「麦种 济麦22」"
                    b.tvPhotoNote.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
                } else {
                    b.tvPhotoNote.text = note
                    b.tvPhotoNote.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                }

                b.ivPhoto.setOnClickListener { viewPhoto(row) }
                b.llNoteArea.setOnClickListener { editNote(row.file.name, row.noteItem) }
                b.root.setOnLongClickListener { confirmDeletePhoto(row.file); true }
            }
        }

        inner class NoteVH(private val b: ItemNoteBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(row: Row.Note) {
                if (_binding == null) return
                b.tvNoteText.text = row.item.note
                b.tvNoteTime.text = row.item.createdAt
                b.root.setOnClickListener { editNote(null, row.item) }
                b.root.setOnLongClickListener {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("删除笔记")
                        .setMessage("确定删除这条笔记吗？")
                        .setPositiveButton("删除") { _, _ ->
                            db.deletePhotoNote(row.item.id)
                            reload()
                            toast("已删除")
                        }
                        .setNegativeButton("取消", null)
                        .show()
                    true
                }
            }
        }
    }

    private companion object {
        const val TYPE_PHOTO = 0
        const val TYPE_NOTE = 1
    }
}
