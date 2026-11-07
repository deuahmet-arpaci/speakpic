package com.speakpic.app

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/** Uygulama içi dil seçici. Seçim, AppCompat tarafından kalıcı saklanır ve menüleri anında değiştirir. */
object LanguageManager {

    private val languages = listOf(
        "tr" to "Türkçe",
        "en" to "English",
        "de" to "Deutsch",
        "fr" to "Français",
        "es" to "Español",
        "it" to "Italiano",
        "pt" to "Português",
        "ru" to "Русский",
        "ar" to "العربية",
        "ja" to "日本語"
    )

    fun show(activity: AppCompatActivity) {
        val current = AppCompatDelegate.getApplicationLocales()
        val currentTag = if (current.isEmpty()) "" else current.get(0)?.language.orEmpty()
        val names = listOf(activity.getString(R.string.language_system)) + languages.map { it.second }
        val checked = if (currentTag.isEmpty()) 0
        else (languages.indexOfFirst { it.first == currentTag } + 1).coerceAtLeast(0)

        AlertDialog.Builder(activity)
            .setTitle(R.string.language_title)
            .setSingleChoiceItems(names.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                val locales = if (which == 0) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(languages[which - 1].first)
                AppCompatDelegate.setApplicationLocales(locales)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** Konuşma tanıma ve sesli okuma için o anki uygulama dilinin etiketi (örn. "tr", "de"). */
    fun speechTag(ctx: Context): String = ctx.resources.configuration.locales[0].toLanguageTag()
}
