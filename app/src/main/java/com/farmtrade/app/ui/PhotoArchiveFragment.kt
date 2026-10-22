package com.farmtrade.app.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.farmtrade.app.databinding.FragmentPhotoArchiveBinding
import com.farmtrade.app.databinding.ItemPhotoBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 图片页：把过磅单、记录本等单据照片存到本机，随时翻看。
 *
 * - 右下角「+」从相册选图 → 复制到 App 私有目录 files/receipts/
 * - 三列网格展示缩略图，角标显示保存时间
 * - 点击看大图，长按（或大图里的删除）删除
 * - 全程本地存储，不联网、不上传
 */
class PhotoArchiveFragment : Fragment() {

    private var _binding: FragmentPhotoArchiveBinding? = null
    private val binding get() = _binding!!

    /** 图片存放目录：/data/data/com.farmtrade.app/files/receipts */
    private lateinit var dir: File
    private lateinit var adapter: PhotoAdapter
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) saveImage(uri)
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
        dir = File(requireContext().filesDir, "receipts").apply { if (!exists()) mkdirs() }
        adapter = PhotoAdapter()
        binding.rvPhotos.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.rvPhotos.adapter = adapter
        binding.fabAddPhoto.setOnClickListener { pickImage.launch("image/*") }
        reload()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================== 数据 ====================

    private fun reload() {
        if (_binding == null) return
        val files = dir.listFiles { f ->
            f.isFile && f.extension.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp")
        }?.sortedByDescending { it.name } ?: emptyList()

        adapter.submit(files)
        binding.tvPhotoCount.text = "已保存 ${files.size} 张单据照片"
        val empty = files.isEmpty()
        binding.llEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvPhotos.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun saveImage(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val dest = withContext(Dispatchers.IO) {
                    val f = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
                    requireContext().contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { out -> input.copyTo(out) }
                    }
                    f
                }
                reload()
                toast("已保存")
            } catch (e: Exception) {
                toast("保存失败：${e.message ?: "未知错误"}")
            }
        }
    }

    private fun openPhoto(file: File) {
        val iv = ImageView(requireContext()).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(0, 8, 0, 8)
        }
        Glide.with(this).load(file).into(iv)
        MaterialAlertDialogBuilder(requireContext())
            .setView(iv)
            .setPositiveButton("关闭", null)
            .setNegativeButton("删除") { _, _ -> confirmDelete(file) }
            .show()
    }

    private fun confirmDelete(file: File) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除图片")
            .setMessage("确定删除这张单据照片吗？")
            .setPositiveButton("删除") { _, _ ->
                val ok = file.delete()
                reload()
                toast(if (ok) "已删除" else "删除失败")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun timeOf(file: File): String {
        val ms = file.name.removePrefix("IMG_").substringBefore('.').toLongOrNull()
            ?: file.lastModified()
        return timeFmt.format(Date(ms))
    }

    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    // ==================== 适配器 ====================

    private inner class PhotoAdapter : RecyclerView.Adapter<PhotoAdapter.VH>() {
        private val items = mutableListOf<File>()

        fun submit(list: List<File>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemPhotoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        override fun getItemCount(): Int = items.size

        inner class VH(private val b: ItemPhotoBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(file: File) {
                if (_binding == null) return
                Glide.with(this@PhotoArchiveFragment)
                    .load(file)
                    .centerCrop()
                    .into(b.ivPhoto)
                b.tvPhotoTime.text = timeOf(file)
                b.root.setOnClickListener { openPhoto(file) }
                b.root.setOnLongClickListener { confirmDelete(file); true }
            }
        }
    }
}
