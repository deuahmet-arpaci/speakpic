package com.speakpic.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : MediaActivity() {

    // --- Kamera arayüzü ---
    private lateinit var previewView: PreviewView
    private lateinit var controls: View
    private lateinit var gridOverlay: GridOverlayView
    private lateinit var focusRing: View
    private lateinit var flashOverlay: View
    private lateinit var flashButton: ImageButton
    private lateinit var hdrButton: TextView
    private lateinit var zoomSlider: SeekBar
    private lateinit var thumb: ImageView
    private lateinit var micButton: ImageButton

    // --- Bilgi paneli ---
    private lateinit var infoPanel: View
    private lateinit var tabMeta: TextView
    private lateinit var tabNote: TextView
    private lateinit var metaText: TextView
    private lateinit var noteGroup: View
    private lateinit var recRow: View
    private lateinit var recDot: View
    private lateinit var noteText: TextView

    // --- Durum ---
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var extensionsManager: ExtensionsManager? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var hdrOn = false
    private var sliding = false

    private var lastPhotoUri: Uri? = null
    private var listenTarget: Uri? = null
    private var currentNote = ""
    private lateinit var speech: SpeechHelper

    private val handler = Handler(Looper.getMainLooper())
    private val hideMicRunnable = Runnable {
        if (!speech.listening && infoPanel.visibility != View.VISIBLE) micButton.visibility = View.GONE
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else {
                toast(R.string.camera_permission_needed)
                finish()
            }
        }

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else toast(R.string.mic_permission_needed)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdgeDark()
        setContentView(R.layout.activity_main)
        bindViews()
        controls.applySystemBarPadding()

        speech = SpeechHelper(
            this,
            onState = { on -> onListeningState(on) },
            onPartial = { p -> noteText.text = joinText(currentNote, p) },
            onFinal = { text -> onSpeechFinal(text) },
            onFailure = { msg ->
                noteText.text = currentNote
                msg?.let { toast(it) }
            }
        )

        setupControls()
        setupGestures()

        // Dil değişince/ekran yeniden oluşturulunca son fotoğrafı koru
        savedInstanceState?.getString("photo")?.let { saved ->
            val uri = Uri.parse(saved)
            lastPhotoUri = uri
            currentNote = savedInstanceState?.getString("note").orEmpty()
            noteText.text = currentNote
            loadThumb(uri)
            loadMeta(uri)
            showMicButton()
        } ?: io.execute {
            val latest = PhotoStore.latest(this)
            runOnUiThread { latest?.let { loadThumb(it) } }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        lastPhotoUri?.let {
            outState.putString("photo", it.toString())
            outState.putString("note", currentNote)
        }
    }

    private fun bindViews() {
        previewView = findViewById(R.id.previewView)
        controls = findViewById(R.id.controls)
        gridOverlay = findViewById(R.id.gridOverlay)
        focusRing = findViewById(R.id.focusRing)
        flashOverlay = findViewById(R.id.flashOverlay)
        flashButton = findViewById(R.id.flashButton)
        hdrButton = findViewById(R.id.hdrButton)
        zoomSlider = findViewById(R.id.zoomSlider)
        thumb = findViewById(R.id.thumb)
        micButton = findViewById(R.id.micButton)
        infoPanel = findViewById(R.id.infoPanel)
        tabMeta = findViewById(R.id.tabMeta)
        tabNote = findViewById(R.id.tabNote)
        metaText = findViewById(R.id.metaText)
        noteGroup = findViewById(R.id.noteGroup)
        recRow = findViewById(R.id.recRow)
        recDot = findViewById(R.id.recDot)
        noteText = findViewById(R.id.noteText)
    }

    private fun setupControls() {
        findViewById<View>(R.id.shutterButton).setOnClickListener { takePhoto() }
        findViewById<View>(R.id.switchButton).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }
        val openGallery = View.OnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        findViewById<View>(R.id.galleryButton).setOnClickListener(openGallery)
        thumb.setOnClickListener(openGallery)
        findViewById<View>(R.id.languageButton).setOnClickListener { LanguageManager.show(this) }

        findViewById<View>(R.id.gridButton).setOnClickListener {
            gridOverlay.visibility =
                if (gridOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        flashButton.setOnClickListener { cycleFlash() }
        hdrButton.setOnClickListener {
            hdrOn = !hdrOn
            startCamera()
        }

        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) camera?.cameraControl?.setLinearZoom(progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sliding = true }
            override fun onStopTrackingTouch(sb: SeekBar?) { sliding = false }
        })

        micButton.setOnClickListener { onMicClicked() }
        findViewById<View>(R.id.panelClose).setOnClickListener { hidePanel() }
        tabMeta.setOnClickListener { selectTab(0) }
        tabNote.setOnClickListener { selectTab(1) }
        selectTab(0)
    }

    // ------------------------------------------------------------------ Kamera

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val extFuture = ExtensionsManager.getInstanceAsync(this, provider)
            extFuture.addListener({
                extensionsManager = try { extFuture.get() } catch (e: Exception) { null }
                bindCamera(provider)
            }, ContextCompat.getMainExecutor(this))
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode)
            .build()

        var selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val em = extensionsManager
        val hdrAvailable = try {
            em != null && em.isExtensionAvailable(selector, ExtensionMode.HDR)
        } catch (e: Exception) { false }
        hdrButton.visibility = if (hdrAvailable) View.VISIBLE else View.GONE
        if (!hdrAvailable) hdrOn = false
        hdrButton.setTextColor(if (hdrOn) Color.parseColor("#FFD54F") else Color.WHITE)
        if (hdrOn && em != null) selector = em.getExtensionEnabledCameraSelector(selector, ExtensionMode.HDR)

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, preview, imageCapture)
            camera?.cameraInfo?.zoomState?.observe(this) { state ->
                if (!sliding) zoomSlider.progress = (state.linearZoom * 100).toInt()
            }
        } catch (e: Exception) {
            toast(getString(R.string.error_generic, e.message ?: ""))
        }
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
            else -> ImageCapture.FLASH_MODE_OFF
        }
        imageCapture?.flashMode = flashMode
        when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> {
                flashButton.setImageResource(R.drawable.ic_flash_on)
                flashButton.setColorFilter(Color.parseColor("#FFD54F"))
                toast(R.string.flash_on)
            }
            ImageCapture.FLASH_MODE_AUTO -> {
                flashButton.setImageResource(R.drawable.ic_flash_on)
                flashButton.setColorFilter(Color.parseColor("#80D8FF"))
                toast(R.string.flash_auto)
            }
            else -> {
                flashButton.setImageResource(R.drawable.ic_flash_off)
                flashButton.setColorFilter(Color.WHITE)
                toast(R.string.flash_off)
            }
        }
    }

    private fun setupGestures() {
        val scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return true
                    val z = cam.cameraInfo.zoomState.value ?: return true
                    val ratio = (z.zoomRatio * d.scaleFactor).coerceIn(z.minZoomRatio, z.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(ratio)
                    return true
                }
            })
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }
        })
        previewView.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun focusAt(x: Float, y: Float) {
        val point = previewView.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
        focusRing.x = x - focusRing.width / 2f
        focusRing.y = y - focusRing.height / 2f
        focusRing.animate().cancel()
        focusRing.alpha = 1f
        focusRing.scaleX = 1.4f
        focusRing.scaleY = 1.4f
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
            focusRing.animate().alpha(0f).setStartDelay(700).setDuration(300).start()
        }.start()
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "SpeakPic_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SpeakPic")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()

        flashOverlay.alpha = 0.8f
        flashOverlay.animate().alpha(0f).setDuration(200).start()

        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    output.savedUri?.let { onPhotoSaved(it) }
                }
                override fun onError(exc: ImageCaptureException) {
                    toast(getString(R.string.error_generic, exc.message ?: ""))
                }
            })
    }

    private fun onPhotoSaved(uri: Uri) {
        speech.cancel()
        lastPhotoUri = uri
        currentNote = ""
        noteText.text = ""
        loadThumb(uri)
        hidePanel()
        showMicButton()
        loadMeta(uri)
    }

    private fun loadThumb(uri: Uri) {
        Glide.with(this).load(uri).centerCrop().into(thumb)
    }

    private fun loadMeta(uri: Uri) {
        io.execute {
            val meta = ExifNotes.describe(this, uri)
            runOnUiThread { metaText.text = meta }
        }
    }

    // ------------------------------------------------------------------ Mikrofon

    private fun showMicButton() {
        micButton.setImageResource(R.drawable.ic_mic)
        micButton.visibility = View.VISIBLE
        micButton.scaleX = 0f
        micButton.scaleY = 0f
        micButton.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
        handler.removeCallbacks(hideMicRunnable)
        handler.postDelayed(hideMicRunnable, 20_000)
    }

    private fun onMicClicked() {
        handler.removeCallbacks(hideMicRunnable)
        if (speech.listening) {
            speech.finish()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startListening()
    }

    private fun startListening() {
        val target = lastPhotoUri ?: return
        if (!speech.isAvailable()) {
            toast(R.string.speech_unavailable)
            return
        }
        listenTarget = target
        showPanel(1)
        speech.start(LanguageManager.speechTag(this))
    }

    private fun onListeningState(on: Boolean) {
        recRow.visibility = if (on) View.VISIBLE else View.GONE
        micButton.setImageResource(if (on) R.drawable.ic_stop else R.drawable.ic_mic)
        if (on) {
            recDot.startAnimation(AlphaAnimation(1f, 0.15f).apply {
                duration = 600
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            })
        } else {
            recDot.clearAnimation()
        }
    }

    private fun onSpeechFinal(text: String) {
        val target = listenTarget ?: return
        if (text.isBlank()) {
            noteText.text = currentNote
            toast(R.string.speech_no_match)
            return
        }
        val newNote = joinText(currentNote, text)
        noteText.text = newNote
        saveNote(target, newNote) { ok ->
            if (ok) {
                currentNote = newNote
                PhotoStore.setNote(target, newNote)
                toast(R.string.note_saved)
                loadMeta(target)
            } else {
                noteText.text = currentNote
            }
        }
    }

    // ------------------------------------------------------------------ Bilgi paneli

    private fun dp(v: Int) = v * resources.displayMetrics.density

    private fun showPanel(tab: Int) {
        selectTab(tab)
        if (infoPanel.visibility != View.VISIBLE) {
            infoPanel.translationX = dp(260)
            infoPanel.visibility = View.VISIBLE
            infoPanel.animate().translationX(0f).setDuration(200).start()
        }
    }

    private fun hidePanel() {
        if (infoPanel.visibility != View.VISIBLE) return
        infoPanel.animate().translationX(dp(260)).setDuration(200).withEndAction {
            infoPanel.visibility = View.GONE
        }.start()
    }

    private fun selectTab(index: Int) {
        val on = Color.parseColor("#E3F2FD")
        val off = Color.parseColor("#F1F1F1")
        tabMeta.setBackgroundColor(if (index == 0) on else off)
        tabNote.setBackgroundColor(if (index == 1) on else off)
        tabMeta.setTextColor(if (index == 0) Color.parseColor("#1565C0") else Color.GRAY)
        tabNote.setTextColor(if (index == 1) Color.parseColor("#1565C0") else Color.GRAY)
        metaText.visibility = if (index == 0) View.VISIBLE else View.GONE
        noteGroup.visibility = if (index == 1) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ Yaşam döngüsü

    override fun onStop() {
        super.onStop()
        speech.cancel()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        speech.cancel()
        super.onDestroy()
    }
}
