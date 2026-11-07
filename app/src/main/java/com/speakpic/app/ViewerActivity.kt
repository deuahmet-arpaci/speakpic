package com.speakpic.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import java.util.Locale

class ViewerActivity : MediaActivity() {

    private lateinit var uri: Uri
    private var mime: String? = null
    private var note = ""

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private lateinit var speech: SpeechHelper

    private lateinit var noteView: TextView
    private lateinit var titleView: TextView
    private lateinit var speakButton: ImageButton
    private lateinit var micButton: ImageButton
    private lateinit var copyButton: ImageButton

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startVoice() else toast(R.string.mic_permission_needed)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdgeDark()
        setContentView(R.layout.activity_viewer)
        findViewById<View>(R.id.viewerRoot).applySystemBarPadding()

        val data = intent.data
        if (data == null) {
            finish()
            return
        }
        uri = data

        noteView = findViewById(R.id.noteView)
        titleView = findViewById(R.id.titleView)
        speakButton = findViewById(R.id.speakButton)
        micButton = findViewById(R.id.micButton)
        copyButton = findViewById(R.id.copyButton)

        speech = SpeechHelper(
            this,
            onState = { on -> micButton.setImageResource(if (on) R.drawable.ic_stop else R.drawable.ic_mic) },
            onPartial = { p ->
                noteView.text = joinText(note, p)
                noteView.alpha = 1f
            },
            onFinal = { text -> onVoiceFinal(text) },
            onFailure = { msg ->
                showNote(note)
                msg?.let { toast(it) }
            }
        )

        Glide.with(this).load(uri).into(findViewById<ImageView>(R.id.photoView))

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.shareButton).setOnClickListener {
            shareUris(listOf(uri), note.ifEmpty { null }, mime)
        }
        findViewById<View>(R.id.deleteButton).setOnClickListener { deletePhoto() }
        findViewById<View>(R.id.moreButton).setOnClickListener { showMore(it) }
        speakButton.setOnClickListener { if (speaking) stopSpeaking() else speak() }
        micButton.setOnClickListener { onMicClicked() }
        findViewById<View>(R.id.editNoteButton).setOnClickListener { showEditDialog() }
        copyButton.setOnClickListener { copyNote() }

        // Başlık ve açıklama doğrudan dosyadan (EXIF) okunur.
        io.execute {
            val name = PhotoStore.name(this, uri)
            val type = contentResolver.getType(uri)
            val n = ExifNotes.read(this, uri).orEmpty()
            runOnUiThread {
                mime = type
                titleView.text = name
                showNote(n)
                if (n.isNotEmpty()) speak() // açılınca otomatik oku
            }
        }
    }

    private fun showNote(text: String) {
        note = text
        if (text.isEmpty()) {
            noteView.setText(R.string.no_description)
            noteView.alpha = 0.6f
        } else {
            noteView.text = text
            noteView.alpha = 1f
        }
        val a = if (text.isEmpty()) 0.4f else 1f
        speakButton.alpha = a
        copyButton.alpha = a
    }

    // ------------------------------------------------------------------ Sesli okuma

    private fun speak() {
        if (note.isEmpty()) {
            toast(R.string.no_description)
            return
        }
        ensureTts {
            tts?.speak(note, TextToSpeech.QUEUE_FLUSH, null, "note")
            setSpeaking(true)
        }
    }

    private fun ensureTts(then: () -> Unit) {
        if (ttsReady) {
            then()
            return
        }
        if (tts != null) return // hazırlanıyor
        tts = TextToSpeech(this) { status ->
            val engine = tts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                toastLong(R.string.tts_missing)
                return@TextToSpeech
            }
            val r = engine.setLanguage(Locale.forLanguageTag(LanguageManager.speechTag(this)))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                toastLong(R.string.tts_missing)
                return@TextToSpeech
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { runOnUiThread { setSpeaking(false) } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { runOnUiThread { setSpeaking(false) } }
            })
            ttsReady = true
            then()
        }
    }

    private fun stopSpeaking() {
        tts?.stop()
        setSpeaking(false)
    }

    private fun setSpeaking(value: Boolean) {
        speaking = value
        speakButton.setImageResource(if (value) R.drawable.ic_stop else R.drawable.ic_volume)
        speakButton.contentDescription = getString(if (value) R.string.action_stop else R.string.action_read_aloud)
    }

    // ------------------------------------------------------------------ Sesle / elle açıklama

    private fun onMicClicked() {
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
        startVoice()
    }

    private fun startVoice() {
        stopSpeaking()
        if (!speech.isAvailable()) {
            toast(R.string.speech_unavailable)
            return
        }
        speech.start(LanguageManager.speechTag(this))
    }

    private fun onVoiceFinal(text: String) {
        if (text.isBlank()) {
            showNote(note)
            toast(R.string.speech_no_match)
            return
        }
        val newNote = joinText(note, text)
        noteView.text = newNote
        applyNote(newNote)
    }

    private fun applyNote(newNote: String) {
        saveNote(uri, newNote) { ok ->
            if (ok) {
                PhotoStore.setNote(uri, newNote)
                showNote(newNote)
                toast(R.string.note_saved)
            } else {
                showNote(note)
            }
        }
    }

    private fun inputContainer(input: EditText): FrameLayout {
        val pad = (20 * resources.displayMetrics.density).toInt()
        return FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
    }

    private fun showEditDialog() {
        stopSpeaking()
        val input = EditText(this).apply {
            setText(note)
            setSelection(note.length)
            hint = getString(R.string.edit_desc_hint)
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.action_edit_desc)
            .setView(inputContainer(input))
            .setPositiveButton(R.string.action_save) { _, _ -> applyNote(input.text.toString().trim()) }
            .setNegativeButton(R.string.action_cancel, null)
        if (note.isNotEmpty()) builder.setNeutralButton(R.string.action_clear) { _, _ -> applyNote("") }
        builder.show()
    }

    private fun copyNote() {
        if (note.isEmpty()) {
            toast(R.string.no_description)
            return
        }
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("SpeakPic", note))
        toast(R.string.desc_copied)
    }

    // ------------------------------------------------------------------ Fotoğraf işlemleri

    private fun showMore(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.action_edit_photo)
        popup.menu.add(0, 2, 1, R.string.action_rename)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> editPhoto()
                2 -> showRenameDialog()
            }
            true
        }
        popup.show()
    }

    private fun editPhoto() {
        val intent = Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(uri, mime ?: "image/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.action_edit_photo)))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_app_found)
        }
    }

    private fun showRenameDialog() {
        val current = titleView.text.toString()
        val base = current.substringBeforeLast('.', current)
        val input = EditText(this).apply {
            setText(base)
            setSelection(base.length)
            hint = getString(R.string.rename_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_title)
            .setView(inputContainer(input))
            .setPositiveButton(R.string.action_save) { _, _ ->
                renameFile(uri, input.text.toString()) { newName ->
                    if (newName != null) titleView.text = newName else toast(R.string.rename_failed)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun deletePhoto() {
        stopSpeaking()
        requestDelete(listOf(uri)) {
            PhotoStore.forget(listOf(uri))
            toast(R.string.deleted)
            finish()
        }
    }

    // ------------------------------------------------------------------ Yaşam döngüsü

    override fun onPause() {
        super.onPause()
        if (::speech.isInitialized) {
            speech.cancel()
            stopSpeaking()
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        if (::speech.isInitialized) speech.cancel()
        super.onDestroy()
    }
}
