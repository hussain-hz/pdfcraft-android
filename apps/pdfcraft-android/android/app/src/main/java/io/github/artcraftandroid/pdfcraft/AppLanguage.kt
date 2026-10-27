package io.github.artcraftandroid.pdfcraft

import androidx.core.content.edit

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.os.Build
import java.util.Locale

/** Interface language only: PDF contents, engine commands and speech voices stay independent. */
object AppLanguage {
    /** Empty means follow the device language, independently of the resolved UI language. */
    @Volatile var selected: String = ""
        private set
    @Volatile var current: String = "en"
        private set

    fun wrap(context: Context): Context {
        val saved = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales
                ?.takeUnless { it.isEmpty }?.get(0)?.language
        } else context.getSharedPreferences("interface_language", Context.MODE_PRIVATE)
            .getString("language", null)
        selected = saved?.takeIf { it in setOf("ar", "en", "es") }.orEmpty()
        val language = selected.takeIf { it.isNotEmpty() }
            ?: context.resources.configuration.locales[0].language.takeIf { it in setOf("ar", "en", "es") } ?: "en"
        current = language
        val locale = Locale.forLanguageTag(language)
        // Override only language and direction so system appearance, font scale and density stay live.
        val config = Configuration()
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    fun select(activity: Activity, language: String) {
        if (language !in setOf("", "ar", "en", "es") || language == selected) return
        selected = language
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(language)
            return
        }
        activity.getSharedPreferences("interface_language", Context.MODE_PRIVATE)
            .edit { if (language.isEmpty()) remove("language") else putString("language", language) }
        // Recreate only the Activity. Its ViewModel keeps open documents and unsaved work.
        activity.recreate()
    }
}
