package com.speakpic.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.IOException

/** Kayıt sırasında hız değiştirilen an: [timeMs] kayıt başlangıcından itibaren, [speed] çarpan (0.25 .. 4). */
data class SpeedEvent(val timeMs: Long, val speed: Float)

/**
 * Kaydedilmiş videoyu, kayıt sırasında seçilen hız değişimleriyle yeniden dışa aktarır (Media3 Transformer).
 * Ana (Looper'lı) iş parçacığından kullanılmalıdır. Başarılı olursa işlenmiş video MediaStore'a eklenir ve
 * ham kayıt silinir; başarısızlıkta ham kayıt korunur.
 */
class SpeedExporter(private val ctx: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var transformer: Transformer? = null
    private var tmp: File? = null
    private var polling = false
    private var cancelled = false

    private class SegmentProvider(
        private val timesUs: LongArray,
        private val speeds: FloatArray
    ) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float {
            var s = speeds[0]
            for (i in timesUs.indices) {
                if (timeUs >= timesUs[i]) s = speeds[i] else break
            }
            return s
        }

        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
            for (t in timesUs) if (t > timeUs) return t
            return C.TIME_UNSET
        }
    }

    private fun buildProvider(events: List<SpeedEvent>): SpeedProvider {
        // zamana göre sırala, aynı anda olanlardan sonuncusunu al, ardışık aynı hızları birleştir
        val sorted = events.sortedBy { it.timeMs }
        val times = ArrayList<Long>()
        val speeds = ArrayList<Float>()
        for (e in sorted) {
            val t = if (times.isEmpty()) 0L else e.timeMs * 1000L
            if (times.isNotEmpty() && times.last() == t) {
                speeds[speeds.lastIndex] = e.speed
            } else if (speeds.isEmpty() || speeds.last() != e.speed) {
                times += t
                speeds += e.speed
            }
        }
        if (times.isEmpty()) { times += 0L; speeds += 1f }
        return SegmentProvider(times.toLongArray(), speeds.toFloatArray())
    }

    fun export(
        rawUri: Uri,
        events: List<SpeedEvent>,
        onProgress: (Int) -> Unit,
        onDone: (Uri?, String?) -> Unit
    ) {
        cancelled = false
        val out = File(ctx.cacheDir, "fx_${System.currentTimeMillis()}.mp4")
        tmp = out
        try {
            val item = EditedMediaItem.Builder(MediaItem.fromUri(rawUri))
                .setSpeed(buildProvider(events))
                .setFrameRate(30)
                .build()
            val t = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        polling = false
                        if (cancelled) return
                        saveToMediaStore(out, rawUri, onDone)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        polling = false
                        out.delete()
                        if (!cancelled) onDone(null, exportException.message ?: "export failed")
                    }
                })
                .build()
            transformer = t
            t.start(item, out.absolutePath)
            poll(t, onProgress)
        } catch (e: Exception) {
            out.delete()
            onDone(null, e.message ?: "export failed")
        }
    }

    private fun poll(t: Transformer, onProgress: (Int) -> Unit) {
        polling = true
        val holder = ProgressHolder()
        val r = object : Runnable {
            override fun run() {
                if (!polling) return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                main.postDelayed(this, 300)
            }
        }
        main.post(r)
    }

    fun cancel() {
        cancelled = true
        polling = false
        transformer?.cancel()
        tmp?.delete()
    }

    private fun saveToMediaStore(file: File, rawUri: Uri, onDone: (Uri?, String?) -> Unit) {
        Thread {
            var result: Uri? = null
            var error: String? = null
            try {
                val resolver = ctx.contentResolver
                val base = PhotoStore.name(ctx, rawUri).substringBeforeLast('.', "SpeakPic_video")
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "${base}_speed.mp4")
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/SpeakPic")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("insert failed")
                resolver.openOutputStream(uri)?.use { o -> file.inputStream().use { it.copyTo(o) } }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                result = uri
                try { resolver.delete(rawUri, null, null) } catch (e: Exception) { /* ham kayıt kalabilir */ }
            } catch (e: Exception) {
                error = e.message ?: "save failed"
            }
            file.delete()
            main.post { if (!cancelled) onDone(result, error) }
        }.start()
    }
}
