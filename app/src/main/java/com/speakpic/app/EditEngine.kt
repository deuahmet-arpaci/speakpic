package com.speakpic.app

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max
import kotlin.math.roundToInt

/** Fotoğraf düzenleme: temel filtreler + ayarlar, hepsi cihazda ColorMatrix ile uygulanır. */
object EditEngine {

    val FILTERS = listOf("none", "bw", "sepia", "vivid", "warm", "cool", "vintage")

    data class Params(
        var filter: String = "none",
        var brightness: Int = 0,   // -100..100
        var contrast: Int = 0,     // -100..100
        var saturation: Int = 0,   // -100..100
        var warmth: Int = 0        // -100..100
    )

    private fun sat(s: Float) = ColorMatrix().apply { setSaturation(s) }

    private fun contrast(c: Float): ColorMatrix {
        val t = 128f * (1f - c)
        return ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f
        ))
    }

    private fun shift(b: Float) = ColorMatrix(floatArrayOf(
        1f, 0f, 0f, 0f, b,
        0f, 1f, 0f, 0f, b,
        0f, 0f, 1f, 0f, b,
        0f, 0f, 0f, 1f, 0f
    ))

    private fun gains(r: Float, g: Float, b: Float) = ColorMatrix(floatArrayOf(
        r, 0f, 0f, 0f, 0f,
        0f, g, 0f, 0f, 0f,
        0f, 0f, b, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    ))

    fun matrix(p: Params): ColorMatrix {
        val cm = ColorMatrix()
        when (p.filter) {
            "bw" -> cm.postConcat(sat(0f))
            "sepia" -> cm.postConcat(ColorMatrix(floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )))
            "vivid" -> { cm.postConcat(sat(1.4f)); cm.postConcat(contrast(1.1f)) }
            "warm" -> cm.postConcat(gains(1.08f, 1.0f, 0.9f))
            "cool" -> cm.postConcat(gains(0.9f, 1.0f, 1.08f))
            "vintage" -> {
                cm.postConcat(sat(0.65f))
                cm.postConcat(gains(1.1f, 1.0f, 0.85f))
                cm.postConcat(contrast(0.9f))
                cm.postConcat(shift(12f))
            }
        }
        if (p.saturation != 0) cm.postConcat(sat((1f + p.saturation / 100f).coerceAtLeast(0f)))
        if (p.contrast != 0) cm.postConcat(contrast((1f + p.contrast / 100f).coerceAtLeast(0f)))
        if (p.brightness != 0) cm.postConcat(shift(p.brightness * 1.6f))
        if (p.warmth != 0) cm.postConcat(gains(1f + p.warmth / 300f, 1f, 1f - p.warmth / 300f))
        return cm
    }

    // ------------------------------------------------------------------ Görüntü yükleme / dönüştürme

    fun orientation(ctx: Context, uri: Uri): Int = try {
        ctx.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** En uzun kenarı en fazla [maxSide] olacak şekilde açar; bellek yetmezse otomatik küçültür. */
    fun decode(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        repeat(3) {
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            } catch (e: OutOfMemoryError) {
                sample *= 2
            }
        }
        return null
    }

    private fun exifMatrix(orientation: Int): Matrix {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { m.setRotate(180f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
        }
        return m
    }

    /** EXIF yönü + kullanıcı döndürmesi + (isteğe bağlı) renk matrisi uygulanmış yeni bir bitmap üretir. */
    fun transform(src: Bitmap, orientation: Int, userDegrees: Int, filter: ColorMatrix?): Bitmap {
        val m = exifMatrix(orientation)
        val deg = ((userDegrees % 360) + 360) % 360
        if (deg != 0) m.postRotate(deg.toFloat())
        val rect = RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        m.mapRect(rect)
        m.postTranslate(-rect.left, -rect.top)
        val out = Bitmap.createBitmap(
            rect.width().roundToInt().coerceAtLeast(1),
            rect.height().roundToInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (filter != null) paint.colorFilter = ColorMatrixColorFilter(filter)
        Canvas(out).drawBitmap(src, m, paint)
        return out
    }

    /** Düzenlenmiş KOPYAYI Pictures/SpeakPic altına kaydeder; orijinal açıklama (EXIF) yeni dosyaya aktarılır. */
    fun saveCopy(ctx: Context, srcUri: Uri, orientation: Int, userDegrees: Int, params: Params): Uri? {
        val src = decode(ctx, srcUri, 4096) ?: return null
        var result: Bitmap? = null
        return try {
            val out = transform(src, orientation, userDegrees, matrix(params))
            result = out
            src.recycle()

            val resolver = ctx.contentResolver
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "SpeakPic_edit_${System.currentTimeMillis()}.jpg")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SpeakPic")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val newUri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(newUri)?.use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(newUri, values, null, null)

            ExifNotes.read(ctx, srcUri)?.let { ExifNotes.write(ctx, newUri, it) }
            newUri
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            result?.recycle()
        }
    }
}
