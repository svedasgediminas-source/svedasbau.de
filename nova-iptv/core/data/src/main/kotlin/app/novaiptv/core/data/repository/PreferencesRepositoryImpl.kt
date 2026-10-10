package app.novaiptv.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.novaiptv.core.domain.PreferencesRepository
import app.novaiptv.core.model.AppLanguage
import app.novaiptv.core.model.AspectRatioMode
import app.novaiptv.core.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Single DataStore instance per process (the delegate guarantees it). */
private val Context.novaDataStore: DataStore<Preferences> by preferencesDataStore(name = "nova_prefs")

class PreferencesRepositoryImpl(context: Context) : PreferencesRepository {

    private val dataStore: DataStore<Preferences> = context.applicationContext.novaDataStore

    override val preferences: Flow<UserPreferences> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { read(it) }
        .distinctUntilChanged()

    override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        dataStore.edit { prefs ->
            val updated = transform(read(prefs))
            write(prefs, updated)
        }
    }

    private object Keys {
        val LANGUAGE = stringPreferencesKey("language")
        val ACTIVE_PLAYLIST = longPreferencesKey("active_playlist_id")
        val ASPECT = stringPreferencesKey("aspect_ratio")
        val AUDIO_LANG = stringPreferencesKey("preferred_audio_language")
        val SUB_LANG = stringPreferencesKey("preferred_subtitle_language")
        val SUBS_ENABLED = booleanPreferencesKey("subtitles_enabled")
        val SUB_SCALE = floatPreferencesKey("subtitle_scale")
        val SHOW_NUMBERS = booleanPreferencesKey("show_channel_numbers")
        val HIDE_ADULT = booleanPreferencesKey("hide_adult_content")
        val AUTO_REFRESH = booleanPreferencesKey("auto_refresh_on_start")
        val EPG_SHIFT = intPreferencesKey("epg_time_shift_minutes")
        val BUFFER = intPreferencesKey("buffer_millis")
    }

    private fun read(p: Preferences): UserPreferences {
        val d = UserPreferences()
        return UserPreferences(
            language = p[Keys.LANGUAGE]?.let { name -> AppLanguage.entries.firstOrNull { it.name == name } } ?: d.language,
            activePlaylistId = p[Keys.ACTIVE_PLAYLIST],
            aspectRatio = p[Keys.ASPECT]?.let { name -> AspectRatioMode.entries.firstOrNull { it.name == name } } ?: d.aspectRatio,
            preferredAudioLanguage = p[Keys.AUDIO_LANG],
            preferredSubtitleLanguage = p[Keys.SUB_LANG],
            subtitlesEnabled = p[Keys.SUBS_ENABLED] ?: d.subtitlesEnabled,
            subtitleScale = p[Keys.SUB_SCALE] ?: d.subtitleScale,
            showChannelNumbers = p[Keys.SHOW_NUMBERS] ?: d.showChannelNumbers,
            hideAdultContent = p[Keys.HIDE_ADULT] ?: d.hideAdultContent,
            autoRefreshOnStart = p[Keys.AUTO_REFRESH] ?: d.autoRefreshOnStart,
            epgTimeShiftMinutes = p[Keys.EPG_SHIFT] ?: d.epgTimeShiftMinutes,
            bufferMillis = p[Keys.BUFFER] ?: d.bufferMillis,
        )
    }

    private fun write(p: MutablePreferences, u: UserPreferences) {
        p[Keys.LANGUAGE] = u.language.name
        val active = u.activePlaylistId
        if (active != null) p[Keys.ACTIVE_PLAYLIST] = active else p.remove(Keys.ACTIVE_PLAYLIST)
        p[Keys.ASPECT] = u.aspectRatio.name
        val audio = u.preferredAudioLanguage
        if (audio != null) p[Keys.AUDIO_LANG] = audio else p.remove(Keys.AUDIO_LANG)
        val sub = u.preferredSubtitleLanguage
        if (sub != null) p[Keys.SUB_LANG] = sub else p.remove(Keys.SUB_LANG)
        p[Keys.SUBS_ENABLED] = u.subtitlesEnabled
        p[Keys.SUB_SCALE] = u.subtitleScale
        p[Keys.SHOW_NUMBERS] = u.showChannelNumbers
        p[Keys.HIDE_ADULT] = u.hideAdultContent
        p[Keys.AUTO_REFRESH] = u.autoRefreshOnStart
        p[Keys.EPG_SHIFT] = u.epgTimeShiftMinutes
        p[Keys.BUFFER] = u.bufferMillis
    }
}
