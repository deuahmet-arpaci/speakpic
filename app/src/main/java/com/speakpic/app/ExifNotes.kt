package com.speakpic.app

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.text.Normalizer
import kotlin.math.roundToInt

enum class WriteResult { OK, NO_PERMISSION, UNSUPPORTED, ERROR }

/**
 * Açıklamayı görüntünün EXIF alanlarına yazar / okur.
 *
 * ExifInterface metni yalnızca ASCII yazabildiği için:
 *  - UserComment      : Türkçe/Unicode karakterleri \uXXXX ile kodlanmış, kayıpsız sürüm
 *  - ImageDescription : diğer uygulamalarda okunabilsin diye ASCII'ye çevrilmiş sürüm
 */
object ExifNotes {

    private val WRITABLE = setOf("image/jpeg", "image/png", "image/webp")

    fun encode(text: String): String = buildString {
        for (c in text) {
            if (c.code in 32..126 && c != '\\') append(c)
            else append("\\u%04x".format(c.code))
        }
    }

    fun decode(s: String): String =
        Regex("\\\\u([0-9a-fA-F]{4})").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }

    fun toAscii(s: String): String {
        val pre = s.replace('ı', 'i').replace('İ', 'I')
        return Normalizer.normalize(pre, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\x20-\\x7E]"), "?")
    }

    /** text boş/null ise açıklama silinir. */
    fun write(ctx: Context, uri: Uri, text: String?): WriteResult {
        if (ctx.contentResolver.getType(uri) !in WRITABLE) return WriteResult.UNSUPPORTED
        return try {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: return WriteResult.ERROR
            pfd.use {
                val exif = ExifInterface(it.fileDescriptor)
                if (text.isNullOrBlank()) {
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, null)
                    exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, null)
                } else {
                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, encode(text))
                    exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, toAscii(text))
                }
                exif.saveAttributes()
            }
            WriteResult.OK
        } catch (e: SecurityException) {
            WriteResult.NO_PERMISSION
        } catch (e: Exception) {
            WriteResult.ERROR
        }
    }

    fun read(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            val raw = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)?.takeIf { it.isNotBlank() }
                ?: exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
            raw?.let { decode(it).trim() }?.takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        null
    }

    /** Bilgi panelindeki "META VERİ" sekmesi için özet metin. */
    fun describe(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.openInputStream(uri)?.use { stream ->
            val e = ExifInterface(stream)
            val lines = mutableListOf<String>()
            (e.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: e.getAttribute(ExifInterface.TAG_DATETIME))
                ?.let { lines += ctx.getString(R.string.meta_date, it.replaceFirst(":", ".").replaceFirst(":", ".")) }
            val dev = listOfNotNull(
                e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL)
            ).joinToString(" ")
            if (dev.isNotBlank()) lines += ctx.getString(R.string.meta_device, dev)
            val w = e.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0)
            val h = e.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0)
            if (w > 0 && h > 0) lines += ctx.getString(R.string.meta_resolution, "$w × $h")
            e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                ?.let { lines += ctx.getString(R.string.meta_iso, it) }
            val exp = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            if (exp > 0) {
                val txt = if (exp < 1) "1/${(1 / exp).roundToInt()} s" else "$exp s"
                lines += ctx.getString(R.string.meta_exposure, txt)
            }
            val f = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
            if (f > 0) lines += ctx.getString(R.string.meta_aperture, "f/$f")
            val fl = e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
            if (fl > 0) lines += ctx.getString(R.string.meta_focal, "%.1f mm".format(fl))
            if (!e.getAttribute(ExifInterface.TAG_USER_COMMENT).isNullOrBlank())
                lines += ctx.getString(R.string.meta_has_note)
            if (lines.isEmpty()) ctx.getString(R.string.meta_none) else lines.joinToString("\n")
        } ?: ctx.getString(R.string.meta_none)
    } catch (ex: Exception) {
        ctx.getString(R.string.meta_none)
    }
}
