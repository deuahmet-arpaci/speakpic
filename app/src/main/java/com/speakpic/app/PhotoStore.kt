package com.speakpic.app

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class Photo(val uri: Uri, val name: String, val mime: String?)

/** Cihazdaki tüm fotoğrafları (diğer uygulamalarınkiler dahil) listeler; açıklama bilgisini önbellekler. */
object PhotoStore {

    /** Küçük resimdeki açıklama rozetini arka planda okumak için. */
    val bg: ExecutorService = Executors.newFixedThreadPool(2)

    // değer: açıklama metni; "" = açıklama yok
    private val notes = ConcurrentHashMap<Uri, String>()

    private val collection: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    fun all(ctx: Context): List<Photo> {
        val out = ArrayList<Photo>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE
        )
        ctx.contentResolver.query(
            collection, projection, null, null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            while (c.moveToNext()) {
                out += Photo(
                    ContentUris.withAppendedId(collection, c.getLong(idCol)),
                    c.getString(nameCol).orEmpty(),
                    c.getString(mimeCol)
                )
            }
        }
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
