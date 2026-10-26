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
    @Volatile var current: String = "en"
        private set

    fun wrap(context: Context): Context {
        val saved = if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales
                ?.takeUnless { it.isEmpty }?.get(0)?.language
        } else context.getSharedPreferences("interface_language", Context.MODE_PRIVATE)
            .getString("language", null)
        val language = saved?.takeIf { it in setOf("ar", "en", "es") }
            ?: context.resources.configuration.locales[0].language.takeIf { it in setOf("ar", "en", "es") } ?: "en"
        current = language
        val locale = Locale.forLanguageTag(language)
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    fun select(activity: Activity, language: String) {
        if (language !in setOf("ar", "en", "es") || language == current) return
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                LocaleList.forLanguageTags(language)
            return
        }
        activity.getSharedPreferences("interface_language", Context.MODE_PRIVATE)
            .edit { putString("language", language) }
        // Recreate only the Activity. Its ViewModel keeps open documents and unsaved work.
        activity.recreate()
    }
}
