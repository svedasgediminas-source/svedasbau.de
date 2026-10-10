package app.novaiptv.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.novaiptv.core.common.Clock
import app.novaiptv.core.domain.Reminder
import app.novaiptv.core.domain.ReminderRepository
import app.novaiptv.core.domain.ReminderRules
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Dedicated DataStore file so reminders never touch the user preferences file. */
private val Context.reminderDataStore: DataStore<Preferences> by preferencesDataStore(name = "nova_reminders")

/**
 * Programme reminders stored as one string value (see [ReminderRules.encode]). The list is small
 * (capped at [ReminderRules.MAX_REMINDERS]); expired entries are filtered on read and pruned on
 * every write.
 */
class ReminderRepositoryImpl(
    context: Context,
    private val clock: Clock,
) : ReminderRepository {

    private val dataStore: DataStore<Preferences> = context.applicationContext.reminderDataStore

    override fun observeReminders(): Flow<List<Reminder>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> ReminderRules.prune(ReminderRules.decode(prefs[KEY]), clock.nowMillis()) }
        .distinctUntilChanged()

    override suspend fun add(reminder: Reminder) {
        edit { list, now -> ReminderRules.add(list, reminder, now) }
    }

    override suspend fun remove(channelId: String, startMillis: Long) {
        edit { list, now -> ReminderRules.remove(list, channelId, startMillis, now) }
    }

    private suspend fun edit(transform: (List<Reminder>, Long) -> List<Reminder>) {
        try {
            dataStore.edit { prefs ->
                val updated = transform(ReminderRules.decode(prefs[KEY]), clock.nowMillis())
                if (updated.isEmpty()) prefs.remove(KEY) else prefs[KEY] = ReminderRules.encode(updated)
            }
        } catch (e: IOException) {
            // Disk full / corrupted file: reminders are best effort, never crash the UI.
        }
    }

    private companion object {
        val KEY = stringPreferencesKey("reminders")
    }
}
