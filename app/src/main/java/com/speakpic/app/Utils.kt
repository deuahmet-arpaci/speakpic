package com.speakpic.app

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Koyu arayüz için şeffaf durum/gezinme çubuklu tam ekran (Android 15+ zorunlu kıldığı için tüm sürümlerde aynı). */
fun ComponentActivity.edgeToEdgeDark() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
    )
}

/** Sistem çubukları ve kamera çentiği kadar iç boşluk bırakır. */
fun View.applySystemBarPadding() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
fun Context.toast(@StringRes id: Int) = toast(getString(id))
fun Context.toastLong(@StringRes id: Int) = Toast.makeText(this, id, Toast.LENGTH_LONG).show()

fun joinText(a: String, b: String): String =
    listOf(a, b).filter { it.isNotBlank() }.joinToString(" ")

fun Context.dpToPx(v: Int): Int = (v * resources.displayMetrics.density).toInt()
