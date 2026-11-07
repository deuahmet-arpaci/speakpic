package com.speakpic.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * Galeri, görüntüleyici ve kamera ekranının ortak tabanı.
 * Başka uygulamalara ait fotoğrafları değiştirmek/silmek için Android 11+ sistem onay
 * isteklerini (createWriteRequest / createDeleteRequest) yönetir.
 */
abstract class MediaActivity : AppCompatActivity() {

    protected val io = Executors.newSingleThreadExecutor()

    private var afterWriteGrant: (() -> Unit)? = null
    private var afterDelete: (() -> Unit)? = null

    private val writeLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val action = afterWriteGrant
            afterWriteGrant = null
            if (result.resultCode == RESULT_OK) action?.invoke() else toast(R.string.access_denied)
        }

    private val deleteLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            val action = afterDelete
            afterDelete = null
            if (result.resultCode == RESULT_OK) action?.invoke()
        }

    protected fun requestWrite(uris: List<Uri>, then: () -> Unit) {
        try {
            val pi = MediaStore.createWriteRequest(contentResolver, uris)
            afterWriteGrant = then
            writeLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (e: Exception) {
            toast(R.string.access_denied)
        }
    }

    /** Sistem onay penceresi gösterir; kullanıcı onaylarsa [then] çalışır. */
    protected fun requestDelete(uris: List<Uri>, then: () -> Unit) {
        try {
            val pi = MediaStore.createDeleteRequest(contentResolver, uris)
            afterDelete = then
            deleteLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } catch (e: Exception) {
            toast(getString(R.string.error_generic, e.message ?: ""))
        }
    }

    /** Açıklamayı EXIF'e yazar; gerekirse yazma izni ister. [onDone] ana iş parçacığında çağrılır. */
    protected fun saveNote(uri: Uri, text: String?, retried: Boolean = false, onDone: (Boolean) -> Unit) {
        io.execute {
            when (ExifNotes.write(this, uri, text)) {
                WriteResult.OK -> runOnUiThread { onDone(true) }
                WriteResult.NO_PERMISSION -> runOnUiThread {
                    if (retried) {
                        toast(R.string.note_save_failed); onDone(false)
                    } else {
                        requestWrite(listOf(uri)) { saveNote(uri, text, true, onDone) }
                    }
                }
                WriteResult.UNSUPPORTED -> runOnUiThread { toast(R.string.exif_unsupported); onDone(false) }
                WriteResult.ERROR -> runOnUiThread { toast(R.string.note_save_failed); onDone(false) }
            }
        }
    }

    /** Dosya adını (uzantıyı koruyarak) değiştirir. [onDone] yeni adı veya hata durumunda null verir. */
    protected fun renameFile(uri: Uri, newBase: String, retried: Boolean = false, onDone: (String?) -> Unit) {
        io.execute {
            val oldName = PhotoStore.name(this, uri)
            val ext = oldName.substringAfterLast('.', "")
            val clean = newBase.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
            if (clean.isEmpty()) {
                runOnUiThread { onDone(null) }
                return@execute
            }
            val full = if (ext.isEmpty()) clean else "$clean.$ext"
            try {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, full) }
                val rows = contentResolver.update(uri, values, null, null)
                runOnUiThread { onDone(if (rows > 0) full else null) }
            } catch (e: SecurityException) {
                runOnUiThread {
                    if (retried) onDone(null)
                    else requestWrite(listOf(uri)) { renameFile(uri, newBase, true, onDone) }
                }
            } catch (e: Exception) {
                runOnUiThread { onDone(null) }
            }
        }
    }

    protected fun shareUris(uris: List<Uri>, text: String? = null, mime: String? = null) {
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = mime ?: "image/*"
                putExtra(Intent.EXTRA_STREAM, uris[0])
                if (!text.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, text)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        val clip = ClipData.newRawUri("", uris[0])
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        intent.clipData = clip
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_chooser)))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_app_found)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }
}
