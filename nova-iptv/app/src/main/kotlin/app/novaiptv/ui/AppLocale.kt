package app.novaiptv.ui

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Per-app language without AppCompat.
 *
 * - API 33+: the platform [LocaleManager] stores and applies the app locale; setting it
 *   recreates the activity automatically.
 * - API 23–32: the chosen tag is mirrored into a tiny synchronous SharedPreferences file
 *   (DataStore is async and cannot be read in attachBaseContext). The activity wraps its base
 *   context with that locale and is recreated after a change.
 *
 * The DataStore preference (UserPreferences.language) stays the source of truth; this object
 * only applies it.
 */
object AppLocale {
    private const val PREFS = "nova_locale_mirror"
    private const val KEY_TAG = "tag"

    private fun storedTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, "") ?: ""

    /** Wraps the activity base context on API < 33. Empty tag = follow the system. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = storedTag(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }

    /**
     * Applies [tag] ("" = system). Returns true when the caller must recreate the activity
     * (API < 33 only; on 33+ the platform recreates it).
     */
    fun apply(context: Context, tag: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return false
            val current = manager.applicationLocales.toLanguageTags()
            if (current != tag) {
                manager.applicationLocales =
                    if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
            }
            return false
        }
        if (storedTag(context) == tag) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TAG, tag).apply()
        return true
    }
}
