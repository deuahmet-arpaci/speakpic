package com.speakpic.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * Açıklamayı fotoğrafın PİKSELLERİNE yazar (görselin altına siyah bir şerit eklenir, fotoğraf kapanmaz).
 * Piksel olarak gömülen metin, WhatsApp gibi uygulamalar EXIF'i silse de yeniden sıkıştırmadan sağ çıkar.
 * Orijinal dosyaya dokunmaz; önbellekte geçici bir kopya üretir.
 */
object CaptionRenderer {

    private const val MAX_SIDE = 2048

    fun render(ctx: Context, uri: Uri, text: String): File? = try {
        // 1) Boyutları oku, belleği korumak için örnekleyerek aç
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        // 2) EXIF yönünü uygula (telefonla dik çekilen fotoğraflar yan çıkmasın)
        val orientation = ctx.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        if (!m.isIdentity) {
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated !== bmp) bmp.recycle()
            bmp = rotated
        }

        // 3) Metni genişliğe göre sar, şerit yüksekliğini hesapla
        val w = bmp.width
        val textSize = (w * 0.04f).coerceAtLeast(24f)
        val pad = textSize * 0.8f
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, (w - 2 * pad).toInt())
            .setLineSpacing(0f, 1.1f)
            .setMaxLines(10)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val barH = (layout.height + 2 * pad).toInt()

        // 4) Çiz
        val out = Bitmap.createBitmap(w, bmp.height + barH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawBitmap(bmp, 0f, 0f, null)
        canvas.drawRect(
            0f, bmp.height.toFloat(), w.toFloat(), out.height.toFloat(),
            Paint().apply { color = Color.BLACK }
        )
        canvas.save()
        canvas.translate(pad, bmp.height + pad)
        layout.draw(canvas)
        canvas.restore()
        bmp.recycle()

        // 5) Önbelleğe kaydet (eski geçici dosyaları temizle)
        val dir = File(ctx.cacheDir, "shared").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        val file = File(dir, "speakpic_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        out.recycle()
        file
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }
}
