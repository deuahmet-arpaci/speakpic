package com.speakpic.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
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
    private lateinit var gridColumn: View
    private lateinit var focusRing: View
    private lateinit var flashOverlay: View
    private lateinit var flashButton: ImageButton
    private lateinit var settingsButton: ImageButton
    private lateinit var hdrButton: TextView
    private lateinit var zoomSlider: SeekBar
    private lateinit var thumb: ImageView
    private lateinit var micButton: ImageButton
    private lateinit var shutterButton: View
    private lateinit var modePhoto: TextView
    private lateinit var modeVideo: TextView
    private lateinit var recBar: View
    private lateinit var recBarDot: View
    private lateinit var recTimer: TextView

    // --- Video hız kontrolü ---
    private lateinit var speedColumn: View
    private lateinit var speedSlider: SeekBar
    private lateinit var speedReadout: TextView

    // --- Kamera ayarları paneli ---
    private lateinit var settingsPanel: View
    private lateinit var chipRatio43: TextView
    private lateinit var chipRatio169: TextView
    private lateinit var chipSizeS: TextView
    private lateinit var chipSizeM: TextView
    private lateinit var chipSizeL: TextView
    private lateinit var nightSwitch: SwitchCompat
    private lateinit var evSlider: SeekBar
    private lateinit var evValue: TextView

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
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var extensionsManager: ExtensionsManager? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var torchOn = false
    private var videoMode = false
    private var hdrOn = false
    private var nightOn = false
    private var nightFallback = false
    private var nightFallbackApplied = false
    private var ratio169 = false
    private var sizeIndex = 2        // 0 küçük, 1 orta, 2 büyük
    private var evIndex = 0
    private var evStep = 0f
    private var sliding = false
    private var updatingUi = false
    private var speedHintShown = false

    // Video hız olayları
    private val speeds = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f)
    private val speedLabels = arrayOf("¼×", "½×", "1×", "2×", "4×")
    private val speedEvents = ArrayList<SpeedEvent>()
    private var recStartElapsed = 0L
    private var exporter: SpeedExporter? = null

    private var lastPhotoUri: Uri? = null
    private var listenTarget: Uri? = null
    private var currentNote = ""
    private lateinit var speech: SpeechHelper

    private val prefs by lazy { getSharedPreferences("speakpic", MODE_PRIVATE) }
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

    // Video kaydı için mikrofon izni: reddedilirse sessiz video kaydedilir
    private val videoAudioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startRecording() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdgeDark()
        setContentView(R.layout.activity_main)
        bindViews()
        controls.applySystemBarPadding()

        ratio169 = prefs.getBoolean("ratio169", false)
        sizeIndex = prefs.getInt("sizeIndex", 2)

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
        updateSettingsUi()
        updateModeUi()
        onSpeedChanged(speedSlider.progress)

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
        gridColumn = findViewById(R.id.gridColumn)
        focusRing = findViewById(R.id.focusRing)
        flashOverlay = findViewById(R.id.flashOverlay)
        flashButton = findViewById(R.id.flashButton)
        settingsButton = findViewById(R.id.settingsButton)
        hdrButton = findViewById(R.id.hdrButton)
        zoomSlider = findViewById(R.id.zoomSlider)
        thumb = findViewById(R.id.thumb)
        micButton = findViewById(R.id.micButton)
        shutterButton = findViewById(R.id.shutterButton)
        modePhoto = findViewById(R.id.modePhoto)
        modeVideo = findViewById(R.id.modeVideo)
        recBar = findViewById(R.id.recBar)
        recBarDot = findViewById(R.id.recBarDot)
        recTimer = findViewById(R.id.recTimer)
        speedColumn = findViewById(R.id.speedColumn)
        speedSlider = findViewById(R.id.speedSlider)
        speedReadout = findViewById(R.id.speedReadout)
        settingsPanel = findViewById(R.id.settingsPanel)
        chipRatio43 = findViewById(R.id.chipRatio43)
        chipRatio169 = findViewById(R.id.chipRatio169)
        chipSizeS = findViewById(R.id.chipSizeS)
        chipSizeM = findViewById(R.id.chipSizeM)
        chipSizeL = findViewById(R.id.chipSizeL)
        nightSwitch = findViewById(R.id.nightSwitch)
        evSlider = findViewById(R.id.evSlider)
        evValue = findViewById(R.id.evValue)
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
        shutterButton.setOnClickListener { if (videoMode) toggleRecording() else takePhoto() }
        findViewById<View>(R.id.switchButton).setOnClickListener {
            if (recording != null) return@setOnClickListener
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            startCamera()
        }
        val openGallery = View.OnClickListener {
            if (recording != null) return@OnClickListener
            startActivity(Intent(this, GalleryActivity::class.java))
        }
        findViewById<View>(R.id.galleryButton).setOnClickListener(openGallery)
        thumb.setOnClickListener(openGallery)
        findViewById<View>(R.id.languageButton).setOnClickListener {
            if (recording == null) LanguageManager.show(this)
        }

        findViewById<View>(R.id.gridButton).setOnClickListener {
            gridOverlay.visibility =
                if (gridOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        flashButton.setOnClickListener { cycleFlash() }
        hdrButton.setOnClickListener {
            hdrOn = !hdrOn
            if (hdrOn && nightOn) {
                nightOn = false
                updateSettingsUi()
            }
            startCamera()
        }
        modePhoto.setOnClickListener { setMode(false) }
        modeVideo.setOnClickListener { setMode(true) }

        zoomSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) camera?.cameraControl?.setLinearZoom(progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { sliding = true }
            override fun onStopTrackingTouch(sb: SeekBar?) { sliding = false }
        })

        // Video hız çubuğu: kayıt sırasında oynatılan her değişiklik zaman damgasıyla saklanır
        speedSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                onSpeedChanged(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        speedReadout.setOnClickListener { speedSlider.progress = 2 }

        // Kamera ayarları paneli
        settingsButton.setOnClickListener {
            settingsPanel.visibility = if (settingsPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.settingsClose).setOnClickListener { settingsPanel.visibility = View.GONE }
        chipRatio43.setOnClickListener { setRatio(false) }
        chipRatio169.setOnClickListener { setRatio(true) }
        chipSizeS.setOnClickListener { setSize(0) }
        chipSizeM.setOnClickListener { setSize(1) }
        chipSizeL.setOnClickListener { setSize(2) }
        nightSwitch.setOnCheckedChangeListener { _, checked ->
            if (updatingUi) return@setOnCheckedChangeListener
            nightOn = checked
            nightFallbackApplied = false
            if (checked) hdrOn = false
            startCamera()
        }
        evSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || updatingUi) return
                val range = camera?.cameraInfo?.exposureState?.exposureCompensationRange ?: return
                evIndex = range.lower + progress
                evValue.text = formatEv(evIndex)
                camera?.cameraControl?.setExposureCompensationIndex(evIndex)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        micButton.setOnClickListener { onMicClicked() }
        findViewById<View>(R.id.panelClose).setOnClickListener { hidePanel() }
        tabMeta.setOnClickListener { selectTab(0) }
        tabNote.setOnClickListener { selectTab(1) }
        selectTab(0)
    }

    // ------------------------------------------------------------------ Kamera ayarları

    private fun setRatio(wide: Boolean) {
        if (ratio169 == wide) return
        ratio169 = wide
        prefs.edit().putBoolean("ratio169", wide).apply()
        updateSettingsUi()
        startCamera()
    }

    private fun setSize(index: Int) {
        if (sizeIndex == index) return
        sizeIndex = index
        prefs.edit().putInt("sizeIndex", index).apply()
        updateSettingsUi()
        startCamera()
    }

    private fun updateSettingsUi() {
        fun chip(tv: TextView, on: Boolean) =
            tv.setBackgroundResource(if (on) R.drawable.chip_on else R.drawable.chip_off)
        chip(chipRatio43, !ratio169)
        chip(chipRatio169, ratio169)
        chip(chipSizeS, sizeIndex == 0)
        chip(chipSizeM, sizeIndex == 1)
        chip(chipSizeL, sizeIndex == 2)
        updatingUi = true
        nightSwitch.isChecked = nightOn
        updatingUi = false
    }

    private fun formatEv(index: Int) = String.format(Locale.US, "%+.1f EV", index * evStep)

    /** Kamera bağlandıktan sonra pozlama çubuğunu cihazın gerçek aralığına göre ayarlar. */
    private fun applyExposureState() {
        val cam = camera ?: return
        val st = cam.cameraInfo.exposureState
        evSlider.isEnabled = st.isExposureCompensationSupported
        if (!st.isExposureCompensationSupported) {
            evValue.text = "—"
            return
        }
        val range = st.exposureCompensationRange
        evStep = st.exposureCompensationStep.toFloat()
        if (nightFallback && !nightFallbackApplied) {
            evIndex = maxOf(evIndex, range.upper * 2 / 3)
            nightFallbackApplied = true
            toast(R.string.night_fallback)
        }
        evIndex = evIndex.coerceIn(range.lower, range.upper)
        updatingUi = true
        evSlider.max = range.upper - range.lower
        evSlider.progress = evIndex - range.lower
        updatingUi = false
        evValue.text = formatEv(evIndex)
        cam.cameraControl.setExposureCompensationIndex(evIndex)
    }

    // ------------------------------------------------------------------ Mod (fotoğraf / video)

    private fun setMode(video: Boolean) {
        if (recording != null || videoMode == video) return
        videoMode = video
        updateModeUi()
        startCamera()
    }

    private fun updateModeUi() {
        val gold = Color.parseColor("#FFD54F")
        modePhoto.setTextColor(if (!videoMode) gold else Color.WHITE)
        modeVideo.setTextColor(if (videoMode) gold else Color.WHITE)
        shutterButton.setBackgroundResource(if (videoMode) R.drawable.shutter_video_bg else R.drawable.shutter_bg)
        flashMode = ImageCapture.FLASH_MODE_OFF
        torchOn = false
        updateFlashIcon(false)

        // Fotoğraf: kırpmadan gerçek çerçeve (FIT); video: tam ekran
        previewView.scaleType =
            if (videoMode) PreviewView.ScaleType.FILL_CENTER else PreviewView.ScaleType.FIT_CENTER
        gridColumn.visibility = if (videoMode) View.GONE else View.VISIBLE
        speedColumn.visibility = if (videoMode) View.VISIBLE else View.GONE
        settingsButton.visibility = if (videoMode) View.GONE else View.VISIBLE
        if (videoMode) {
            settingsPanel.visibility = View.GONE
            speech.cancel()
            handler.removeCallbacks(hideMicRunnable)
            micButton.visibility = View.GONE
            hidePanel()
            hdrButton.visibility = View.GONE
            if (!speedHintShown) {
                speedHintShown = true
                toast(R.string.speed_hint)
            }
        }
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
        val cases = mutableListOf<UseCase>()
        if (videoMode) {
            cases += Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        Quality.FHD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            val vc = VideoCapture.withOutput(recorder)
            videoCapture = vc
            imageCapture = null
            cases += vc
        } else {
            val aspect = if (ratio169) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val sizeStrategy = when (sizeIndex) {
                0 -> ResolutionStrategy(
                    if (ratio169) Size(1920, 1080) else Size(1600, 1200),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
                1 -> ResolutionStrategy(
                    if (ratio169) Size(3840, 2160) else Size(3264, 2448),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                )
                else -> ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
            }
            val photoSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspect)
                .setResolutionStrategy(sizeStrategy)
                .build()
            val previewSelector = ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build()

            cases += Preview.Builder().setResolutionSelector(previewSelector).build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val ic = ImageCapture.Builder()
                .setResolutionSelector(photoSelector)
                .setCaptureMode(
                    if (sizeIndex == 2 || nightOn) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                )
                .setFlashMode(flashMode)
                .build()
            imageCapture = ic
            videoCapture = null
            cases += ic
        }

        var selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
        val em = extensionsManager
        fun available(mode: Int) = !videoMode && try {
            em != null && em.isExtensionAvailable(selector, mode)
        } catch (e: Exception) { false }

        val hdrAvailable = available(ExtensionMode.HDR)
        val nightAvailable = available(ExtensionMode.NIGHT)
        hdrButton.visibility = if (hdrAvailable) View.VISIBLE else View.GONE
        if (!hdrAvailable) hdrOn = false
        hdrButton.setTextColor(if (hdrOn) Color.parseColor("#FFD54F") else Color.WHITE)

        val wanted = when {
            nightOn && nightAvailable -> ExtensionMode.NIGHT
            hdrOn && hdrAvailable -> ExtensionMode.HDR
            else -> ExtensionMode.NONE
        }
        if (wanted != ExtensionMode.NONE && em != null) {
            selector = em.getExtensionEnabledCameraSelector(selector, wanted)
        }
        // Cihaz gece modu eklentisini desteklemiyorsa: pozlamayı artırarak aydınlat
        nightFallback = nightOn && !nightAvailable && !videoMode

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, *cases.toTypedArray())
            camera?.cameraInfo?.zoomState?.observe(this) { state ->
                if (!sliding) zoomSlider.progress = (state.linearZoom * 100).toInt()
            }
            applyExposureState()
        } catch (e: Exception) {
            toast(getString(R.string.error_generic, e.message ?: ""))
        }
    }

    private fun cycleFlash() {
        if (videoMode) {
            torchOn = !torchOn
            camera?.cameraControl?.enableTorch(torchOn)
        } else {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }
            imageCapture?.flashMode = flashMode
        }
        updateFlashIcon(true)
    }

    /** Fotoğraf modunda flaş (kapalı/açık/otomatik), video modunda fener (kapalı/açık). */
    private fun updateFlashIcon(showToast: Boolean) {
        val state = when {
            videoMode -> if (torchOn) 1 else 0
            flashMode == ImageCapture.FLASH_MODE_ON -> 1
            flashMode == ImageCapture.FLASH_MODE_AUTO -> 2
            else -> 0
        }
        when (state) {
            1 -> {
                flashButton.setImageResource(R.drawable.ic_flash_on)
                flashButton.setColorFilter(Color.parseColor("#FFD54F"))
                if (showToast) toast(R.string.flash_on)
            }
            2 -> {
                flashButton.setImageResource(R.drawable.ic_flash_on)
                flashButton.setColorFilter(Color.parseColor("#80D8FF"))
                if (showToast) toast(R.string.flash_auto)
            }
            else -> {
                flashButton.setImageResource(R.drawable.ic_flash_off)
                flashButton.setColorFilter(Color.WHITE)
                if (showToast) toast(R.string.flash_off)
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

    // ------------------------------------------------------------------ Fotoğraf

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

    // ------------------------------------------------------------------ Video

    private fun currentSpeed() = speeds[speedSlider.progress.coerceIn(0, speeds.lastIndex)]

    private fun onSpeedChanged(progress: Int) {
        val i = progress.coerceIn(0, speeds.lastIndex)
        speedReadout.text = speedLabels[i]
        speedReadout.setTextColor(
            when {
                speeds[i] > 1f -> Color.parseColor("#FFB74D")
                speeds[i] < 1f -> Color.parseColor("#4FC3F7")
                else -> Color.WHITE
            }
        )
        if (recording != null) {
            speedEvents.add(SpeedEvent(SystemClock.elapsedRealtime() - recStartElapsed, speeds[i]))
        }
    }

    private fun toggleRecording() {
        val active = recording
        if (active != null) {
            active.stop()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            videoAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecording()
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        if (recording != null) return
        speech.cancel()

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "SpeakPic_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/SpeakPic")
        }
        val output = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        var pending = vc.output.prepareRecording(this, output)
        if (hasAudio) pending = pending.withAudioEnabled()

        // Hız zaman çizelgesi: ilk olay kayıt başındaki hız
        speedEvents.clear()
        recStartElapsed = SystemClock.elapsedRealtime()
        speedEvents.add(SpeedEvent(0L, currentSpeed()))

        recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    // Gerçek kayıt, düğmeye basıştan biraz sonra başlar: zaman çizelgesini buna göre kaydır
                    val now = SystemClock.elapsedRealtime()
                    val delta = now - recStartElapsed
                    val shifted = speedEvents.mapIndexed { i, e ->
                        if (i == 0) e else SpeedEvent((e.timeMs - delta).coerceAtLeast(0L), e.speed)
                    }
                    speedEvents.clear()
                    speedEvents.addAll(shifted)
                    recStartElapsed = now
                    setRecordingUi(true)
                }
                is VideoRecordEvent.Status -> {
                    val sec = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                    recTimer.text = String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
                }
                is VideoRecordEvent.Finalize -> {
                    recording = null
                    setRecordingUi(false)
                    if (!event.hasError()) {
                        val durationMs = event.recordingStats.recordedDurationNanos / 1_000_000L
                        val events = speedEvents.filter { it.timeMs < durationMs || it.timeMs == 0L }
                        val rawUri = event.outputResults.outputUri
                        if (events.any { it.speed != 1f }) processSpeed(rawUri, events) else onVideoSaved(rawUri)
                    } else {
                        toast(getString(R.string.error_generic, event.cause?.message ?: event.error.toString()))
                    }
                }
            }
        }
    }

    /** Kayıt sırasında hız değiştirildiyse videoyu bu hızlarla yeniden işler. */
    private fun processSpeed(rawUri: Uri, events: List<SpeedEvent>) {
        val pad = dpToPx(20)
        val label = TextView(this).apply {
            setText(R.string.processing_speed)
            setTextColor(Color.WHITE)
            textSize = 15f
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2 }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(label)
            addView(bar)
        }
        val exp = SpeedExporter(this)
        exporter = exp
        val dialog = AlertDialog.Builder(this)
            .setView(box)
            .setCancelable(false)
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                exp.cancel()
                exporter = null
                onVideoSaved(rawUri) // iptal: hızlandırılmamış ham kayıt kalır
            }
            .create()
        dialog.window?.setBackgroundDrawableResource(R.drawable.card_bg)
        dialog.show()

        exp.export(
            rawUri, events,
            onProgress = { bar.progress = it },
            onDone = { newUri, err ->
                if (dialog.isShowing) dialog.dismiss()
                exporter = null
                if (newUri != null) {
                    onVideoSaved(newUri)
                } else {
                    err?.let { toast(getString(R.string.error_generic, it)) }
                    onVideoSaved(rawUri)
                }
            }
        )
    }

    private fun setRecordingUi(on: Boolean) {
        recBar.visibility = if (on) View.VISIBLE else View.GONE
        shutterButton.setBackgroundResource(
            if (on) R.drawable.shutter_rec_bg else R.drawable.shutter_video_bg
        )
        if (on) {
            recTimer.setText(R.string.timer_zero)
            recBarDot.startAnimation(AlphaAnimation(1f, 0.15f).apply {
                duration = 600
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            })
        } else {
            recBarDot.clearAnimation()
        }
    }

    private fun onVideoSaved(uri: Uri) {
        lastPhotoUri = null
        listenTarget = null
        currentNote = ""
        loadThumb(uri)
        toast(R.string.video_saved)
    }

    // ------------------------------------------------------------------ Mikrofon (fotoğraf açıklaması)

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

    private fun showPanel(tab: Int) {
        selectTab(tab)
        if (infoPanel.visibility != View.VISIBLE) {
            infoPanel.translationX = dpToPx(260).toFloat()
            infoPanel.visibility = View.VISIBLE
            infoPanel.animate().translationX(0f).setDuration(200).start()
        }
    }

    private fun hidePanel() {
        if (infoPanel.visibility != View.VISIBLE) return
        infoPanel.animate().translationX(dpToPx(260).toFloat()).setDuration(200).withEndAction {
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
        recording?.stop() // arka plana geçerken kaydı düzgünce bitir
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        speech.cancel()
        exporter?.cancel()
        super.onDestroy()
    }
}
