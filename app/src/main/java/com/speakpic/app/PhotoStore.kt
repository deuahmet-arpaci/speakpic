package com.speakpic.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class Photo(
    val uri: Uri,
    val name: String,
    val mime: String?,
    val dateAdded: Long,
    val isVideo: Boolean
)

/** Cihazdaki tüm fotoğraf ve videoları (diğer uygulamalarınkiler dahil) listeler; açıklama bilgisini önbellekler. */
object PhotoStore {

    /** Küçük resimdeki açıklama rozetini arka planda okumak için. */
    val bg: ExecutorService = Executors.newFixedThreadPool(2)

    // değer: açıklama metni; "" = açıklama yok
    private val notes = ConcurrentHashMap<Uri, String>()

    private val imageCollection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val videoCollection: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    private fun query(ctx: Context, collection: Uri, isVideo: Boolean, out: MutableList<Photo>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_ADDED
        )
        ctx.contentResolver.query(
            collection, projection, null, null,
            "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            while (c.moveToNext()) {
                out += Photo(
                    ContentUris.withAppendedId(collection, c.getLong(idCol)),
                    c.getString(nameCol).orEmpty(),
                    c.getString(mimeCol),
                    c.getLong(dateCol),
                    isVideo
                )
            }
        }
    }

    /** Fotoğraflar + videolar, yeniden eskiye. */
    fun all(ctx: Context): List<Photo> {
        val out = ArrayList<Photo>()
        query(ctx, imageCollection, false, out)
        try {
            query(ctx, videoCollection, true, out)
        } catch (e: Exception) {
            // video erişimi yoksa yalnızca fotoğraflar listelenir
        }
        out.sortByDescending { it.dateAdded }
        return out
    }

    fun latest(ctx: Context): Uri? = all(ctx).firstOrNull()?.uri

    fun name(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }.orEmpty()
    } catch (e: Exception) { "" }

    /** null = henüz okunmadı, "" = açıklama yok, diğer = açıklama metni */
    fun cachedNote(uri: Uri): String? = notes[uri]

    /** Açıklamayı döndürür (yoksa null); sonucu önbelleğe alır. */
    fun noteOf(ctx: Context, uri: Uri): String? {
        notes[uri]?.let { return it.ifEmpty { null } }
        val n = ExifNotes.read(ctx, uri)
        notes[uri] = n.orEmpty()
        return n
    }

    fun setNote(uri: Uri, text: String?) { notes[uri] = text.orEmpty() }

    fun forget(uris: Collection<Uri>) { uris.forEach { notes.remove(it) } }
}
