package com.speakpic.app

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Temel filtreler, ayarlar, döndürme ve (isteğe bağlı) Claude ile yönergeye dayalı düzenleme. */
class EditorActivity : MediaActivity() {

    private lateinit var uri: Uri
    private var orientation = ExifInterface.ORIENTATION_NORMAL
    private var previewSrc: Bitmap? = null
    private var previewBase: Bitmap? = null
    private var rotation = 0
    private var params = EditEngine.Params()
    private var updatingUi = false

    private lateinit var imageView: ImageView
    private lateinit var saveButton: TextView
    private lateinit var aiInput: EditText
    private lateinit var aiApply: TextView
    private lateinit var aiStatus: TextView
    private lateinit var aiMic: ImageButton
    private lateinit var speech: SpeechHelper

    private val chips = LinkedHashMap<String, TextView>()
    private val sliders = ArrayList<SeekBar>()

    private val filterNames = mapOf(
        "none" to R.string.filter_original,
        "bw" to R.string.filter_bw,
        "sepia" to R.string.filter_sepia,
        "vivid" to R.string.filter_vivid,
        "warm" to R.string.filter_warm,
        "cool" to R.string.filter_cool,
        "vintage" to R.string.filter_vintage
    )

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startDictation() else toast(R.string.mic_permission_needed)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdgeDark()
        setContentView(R.layout.activity_editor)
        findViewById<View>(R.id.editorRoot).applySystemBarPadding()

        val data = intent.data
        if (data == null) {
            finish()
            return
        }
        uri = data

        imageView = findViewById(R.id.editImage)
        saveButton = findViewById(R.id.saveButton)
        aiInput = findViewById(R.id.aiInput)
        aiApply = findViewById(R.id.aiApply)
        aiStatus = findViewById(R.id.aiStatus)
        aiMic = findViewById(R.id.aiMic)

        speech = SpeechHelper(
            this,
            onState = { on -> aiMic.setImageResource(if (on) R.drawable.ic_stop else R.drawable.ic_mic) },
            onPartial = { p -> aiInput.setText(p); aiInput.setSelection(aiInput.text.length) },
            onFinal = { text -> if (text.isNotBlank()) aiInput.setText(text); aiInput.setSelection(aiInput.text.length) },
            onFailure = { msg -> msg?.let { toast(it) } }
        )

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        saveButton.setOnClickListener { saveCopy() }
        findViewById<View>(R.id.rotateButton).setOnClickListener {
            rotation = (rotation + 90) % 360
            renderPreview()
        }
        findViewById<View>(R.id.resetButton).setOnClickListener {
            params = EditEngine.Params()
            rotation = 0
            syncUi()
            renderPreview()
        }
        aiApply.setOnClickListener { onAiApply() }
        findViewById<View>(R.id.aiKeyButton).setOnClickListener { showKeyDialog(null) }
        aiMic.setOnClickListener { onMicClicked() }

        buildFilterChips()
        bindSliders()
        syncUi()

        io.execute {
            val o = EditEngine.orientation(this, uri)
            val bmp = EditEngine.decode(this, uri, 1600)
            runOnUiThread {
                if (bmp == null) {
                    toast(R.string.editor_failed)
                    finish()
                } else {
                    orientation = o
                    previewSrc = bmp
                    renderPreview()
                }
            }
        }
    }

    // ------------------------------------------------------------------ Önizleme

    private fun renderPreview() {
        val src = previewSrc ?: return
        val old = previewBase
        val fresh = EditEngine.transform(src, orientation, rotation, null)
        imageView.setImageBitmap(fresh)
        previewBase = fresh
        old?.recycle()
        applyFilterPreview()
    }

    private fun applyFilterPreview() {
        imageView.colorFilter = ColorMatrixColorFilter(EditEngine.matrix(params))
    }

    // ------------------------------------------------------------------ Filtreler ve ayarlar

    private fun buildFilterChips() {
        val row = findViewById<LinearLayout>(R.id.filterRow)
        for (f in EditEngine.FILTERS) {
            val tv = TextView(this).apply {
                text = getString(filterNames.getValue(f))
                setTextColor(Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dpToPx(8) }
                setOnClickListener {
                    params.filter = f
                    updateChips()
                    applyFilterPreview()
                }
            }
            row.addView(tv)
            chips[f] = tv
        }
    }

    private fun updateChips() {
        for ((f, tv) in chips) {
            tv.setBackgroundResource(if (params.filter == f) R.drawable.chip_on else R.drawable.chip_off)
        }
    }

    private fun bindSliders() {
        fun bind(id: Int, set: (Int) -> Unit) {
            val sb = findViewById<SeekBar>(id)
            sb.max = 200
            sb.progress = 100
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !updatingUi) {
                        set(progress - 100)
                        applyFilterPreview()
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            sliders += sb
        }
        bind(R.id.sliderBrightness) { params.brightness = it }
        bind(R.id.sliderContrast) { params.contrast = it }
        bind(R.id.sliderSaturation) { params.saturation = it }
        bind(R.id.sliderWarmth) { params.warmth = it }
    }

    private fun syncUi() {
        updatingUi = true
        sliders[0].progress = params.brightness + 100
        sliders[1].progress = params.contrast + 100
        sliders[2].progress = params.saturation + 100
        sliders[3].progress = params.warmth + 100
        updatingUi = false
        updateChips()
    }

    // ------------------------------------------------------------------ Kaydet

    private fun saveCopy() {
        saveButton.isEnabled = false
        toast(R.string.preparing)
        val snapshot = params.copy()
        val rot = rotation
        val orient = orientation
        io.execute {
            val newUri = EditEngine.saveCopy(this, uri, orient, rot, snapshot)
            runOnUiThread {
                if (newUri != null) {
                    toast(R.string.editor_saved)
                    finish()
                } else {
                    saveButton.isEnabled = true
                    toast(R.string.editor_failed)
                }
            }
        }
    }

    // ------------------------------------------------------------------ Yapay zekâ ile düzenleme

    private fun onMicClicked() {
        if (speech.listening) {
            speech.finish()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startDictation()
    }

    private fun startDictation() {
        if (!speech.isAvailable()) {
            toast(R.string.speech_unavailable)
            return
        }
        speech.start(LanguageManager.speechTag(this))
    }

    private fun onAiApply() {
        val instruction = aiInput.text.toString().trim()
        if (instruction.isEmpty()) return
        val key = AiEditor.getKey(this)
        if (key == null) {
            showKeyDialog { onAiApply() }
            return
        }
        val base = previewBase ?: return

        // Gönderilecek kopya: en uzun kenar 1024 px (hem hız hem gizlilik/maliyet için)
        val scale = 1024f / maxOf(base.width, base.height)
        val small = if (scale < 1f)
            Bitmap.createScaledBitmap(base, (base.width * scale).toInt(), (base.height * scale).toInt(), true)
        else base.copy(Bitmap.Config.ARGB_8888, false)

        aiStatus.visibility = View.VISIBLE
        aiStatus.text = getString(R.string.ai_thinking)
        aiApply.isEnabled = false
        val current = params.copy()
        val language = Locale.forLanguageTag(LanguageManager.speechTag(this)).getDisplayLanguage(Locale.ENGLISH)

        io.execute {
            try {
                val bos = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                small.recycle()
                val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                val suggestion = AiEditor.suggest(key, b64, instruction, current, language)
                runOnUiThread { applySuggestion(suggestion) }
            } catch (e: Exception) {
                runOnUiThread {
                    aiStatus.text = getString(R.string.ai_error, e.message ?: e.javaClass.simpleName)
                    aiApply.isEnabled = true
                }
            }
        }
    }

    private fun applySuggestion(s: AiEditor.Suggestion) {
        params = s.params
        if (s.rotateDegrees != 0) rotation = (rotation + s.rotateDegrees) % 360
        syncUi()
        if (s.rotateDegrees != 0) renderPreview() else applyFilterPreview()
        aiStatus.text = s.explanation
        aiApply.isEnabled = true
    }

    private fun showKeyDialog(then: (() -> Unit)?) {
        val input = EditText(this).apply {
            setText(AiEditor.getKey(this@EditorActivity).orEmpty())
            hint = "sk-ant-…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val pad = dpToPx(20)
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.ai_key_title)
            .setMessage(R.string.ai_key_msg)
            .setView(box)
            .setPositiveButton(R.string.action_save) { _, _ ->
                AiEditor.setKey(this, input.text.toString())
                if (!AiEditor.getKey(this).isNullOrBlank()) then?.invoke()
            }
            .setNegativeButton(R.string.action_cancel, null)
        if (AiEditor.getKey(this) != null) {
            builder.setNeutralButton(R.string.ai_key_clear) { _, _ -> AiEditor.setKey(this, null) }
        }
        builder.show()
    }

    override fun onPause() {
        super.onPause()
        if (::speech.isInitialized) speech.cancel()
    }

    override fun onDestroy() {
        if (::speech.isInitialized) speech.cancel()
        previewBase = null
        super.onDestroy()
    }
}
