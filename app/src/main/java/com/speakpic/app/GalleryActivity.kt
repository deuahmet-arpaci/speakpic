package com.speakpic.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.util.concurrent.Executors

class GalleryActivity : MediaActivity() {

    private lateinit var adapter: PhotoAdapter
    private lateinit var emptyText: TextView
    private lateinit var progress: View
    private lateinit var permBanner: View
    private lateinit var normalBar: View
    private lateinit var selectionBar: View
    private lateinit var selectionCount: TextView
    private lateinit var chipAll: TextView
    private lateinit var chipNoted: TextView

    private var photos: List<Photo> = emptyList()
    private var onlyNoted = false
    private var scanGen = 0
    private val scanner = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("speakpic", Context.MODE_PRIVATE) }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            adapter.clearSelection()
            updateSelectionUi()
        }
    }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            updatePermBanner()
            loadPhotos()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdgeDark()
        setContentView(R.layout.activity_gallery)
        findViewById<View>(R.id.galleryRoot).applySystemBarPadding()

        emptyText = findViewById(R.id.emptyText)
        progress = findViewById(R.id.progress)
        permBanner = findViewById(R.id.permBanner)
        normalBar = findViewById(R.id.normalBar)
        selectionBar = findViewById(R.id.selectionBar)
        selectionCount = findViewById(R.id.selectionCount)
        chipAll = findViewById(R.id.chipAll)
        chipNoted = findViewById(R.id.chipNoted)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.languageButton).setOnClickListener { LanguageManager.show(this) }
        findViewById<View>(R.id.permGrant).setOnClickListener { onGrantClicked() }
        findViewById<View>(R.id.selCancel).setOnClickListener { backCallback.handleOnBackPressed() }
        findViewById<View>(R.id.selShare).setOnClickListener { shareUris(adapter.selection.toList()) }
        findViewById<View>(R.id.selDelete).setOnClickListener { deleteSelected() }
        chipAll.setOnClickListener { setFilter(false) }
        chipNoted.setOnClickListener { setFilter(true) }
        onBackPressedDispatcher.addCallback(this, backCallback)

        adapter = PhotoAdapter(
            applicationContext,
            onClick = { photo -> onPhotoClicked(photo) },
            onLongClick = { photo -> toggleSelect(photo) }
        )
        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = GridLayoutManager(this@GalleryActivity, 3)
            adapter = this@GalleryActivity.adapter
        }

        if (!hasFullAccess() && !prefs.getBoolean("asked_media", false)) onGrantClicked()
    }

    override fun onResume() {
        super.onResume()
        updatePermBanner()
        loadPhotos()
    }

    // ------------------------------------------------------------------ İzin

    private fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun mainPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasFullAccess() =
        ContextCompat.checkSelfPermission(this, mainPermission()) == PackageManager.PERMISSION_GRANTED

    private fun updatePermBanner() {
        permBanner.visibility = if (hasFullAccess()) View.GONE else View.VISIBLE
    }

    private fun onGrantClicked() {
        val asked = prefs.getBoolean("asked_media", false)
        if (asked && !hasFullAccess() && !shouldShowRequestPermissionRationale(mainPermission())) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        } else {
            prefs.edit().putBoolean("asked_media", true).apply()
            permLauncher.launch(mediaPermissions())
        }
    }

    // ------------------------------------------------------------------ Liste

    private fun loadPhotos() {
        io.execute {
            val all = PhotoStore.all(this)
            runOnUiThread {
                photos = all
                applyFilter()
            }
        }
    }

    private fun setFilter(noted: Boolean) {
        if (onlyNoted == noted) return
        onlyNoted = noted
        adapter.clearSelection()
        updateSelectionUi()
        applyFilter()
    }

    private fun applyFilter() {
        chipAll.setBackgroundResource(if (!onlyNoted) R.drawable.chip_on else R.drawable.chip_off)
        chipNoted.setBackgroundResource(if (onlyNoted) R.drawable.chip_on else R.drawable.chip_off)
        val gen = ++scanGen
        if (!onlyNoted) {
            progress.visibility = View.GONE
            adapter.submit(photos)
            showEmpty(photos.isEmpty())
        } else {
            progress.visibility = View.VISIBLE
            val snapshot = photos
            scanner.execute {
                val noted = snapshot.filter { PhotoStore.noteOf(applicationContext, it.uri) != null }
                runOnUiThread {
                    if (gen == scanGen) {
                        progress.visibility = View.GONE
                        adapter.submit(noted)
                        showEmpty(noted.isEmpty())
                    }
                }
            }
        }
    }

    private fun showEmpty(empty: Boolean) {
        emptyText.setText(if (onlyNoted) R.string.gallery_empty_noted else R.string.gallery_empty_all)
        emptyText.visibility = if (empty) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ Seçim ve işlemler

    private fun onPhotoClicked(photo: Photo) {
        if (adapter.selection.isNotEmpty()) {
            toggleSelect(photo)
        } else {
            startActivity(Intent(this, ViewerActivity::class.java).setData(photo.uri))
        }
    }

    private fun toggleSelect(photo: Photo) {
        adapter.toggle(photo.uri)
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val n = adapter.selection.size
        val selecting = n > 0
        normalBar.visibility = if (selecting) View.GONE else View.VISIBLE
        selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        selectionCount.text = getString(R.string.selected_count, n)
        backCallback.isEnabled = selecting
    }

    private fun deleteSelected() {
        val uris = adapter.selection.toList()
        if (uris.isEmpty()) return
        requestDelete(uris) {
            PhotoStore.forget(uris)
            toast(R.string.deleted)
            adapter.clearSelection()
            updateSelectionUi()
            loadPhotos()
        }
    }

    override fun onDestroy() {
        scanner.shutdown()
        super.onDestroy()
    }
}

class PhotoAdapter(
    private val ctx: Context,
    private val onClick: (Photo) -> Unit,
    private val onLongClick: (Photo) -> Unit
) : RecyclerView.Adapter<PhotoAdapter.VH>() {

    private var items: List<Photo> = emptyList()
    val selection = LinkedHashSet<Uri>()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val img: ImageView = v.findViewById(R.id.photoImage)
        val badge: View = v.findViewById(R.id.noteBadge)
        val overlay: View = v.findViewById(R.id.selOverlay)
    }

    fun submit(list: List<Photo>) {
        items = list
        selection.retainAll(list.map { it.uri }.toSet())
        notifyDataSetChanged()
    }

    fun toggle(uri: Uri) {
        if (!selection.add(uri)) selection.remove(uri)
        val idx = items.indexOfFirst { it.uri == uri }
        if (idx >= 0) notifyItemChanged(idx)
    }

    fun clearSelection() {
        if (selection.isEmpty()) return
        selection.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.itemView.tag = item.uri
        Glide.with(holder.img).load(item.uri).centerCrop().into(holder.img)
        holder.overlay.visibility = if (selection.contains(item.uri)) View.VISIBLE else View.GONE
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onLongClick(item); true }

        val cached = PhotoStore.cachedNote(item.uri)
        if (cached != null) {
            holder.badge.visibility = if (cached.isNotEmpty()) View.VISIBLE else View.GONE
        } else {
            holder.badge.visibility = View.GONE
            PhotoStore.bg.execute {
                val note = PhotoStore.noteOf(ctx, item.uri)
                holder.itemView.post {
                    if (holder.itemView.tag == item.uri) {
                        holder.badge.visibility = if (note != null) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }
}
