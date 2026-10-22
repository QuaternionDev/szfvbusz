package hu.szfvbus.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "prefs")

data class Prefs(
    /** Megállócsoportok nevei (lásd [hu.szfvbus.data.model.StopGroup]). */
    val favoriteStops: Set<String> = emptySet(),
    /** A vonalak gtfsId-i. */
    val favoriteLines: Set<String> = emptySet(),
    val showIntercity: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Értesítések az új forgalmi változásokról. */
    val notifyAlerts: Boolean = true,
    /** A rejtett fejlesztői mód, a Névjegyből kapcsolható be. */
    val devMode: Boolean = false,
    /** Az indítási figyelmeztetések, amelyeknél a „Ne mutasd újra” gombot választották. */
    val hiddenNotices: Set<String> = emptySet(),
)

enum class ThemeMode(val label: String) {
    SYSTEM("Rendszer szerint"),
    LIGHT("Világos"),
    DARK("Sötét"),
}

class UserPrefs(private val context: Context) {
    private val favStopsKey = stringSetPreferencesKey("favorite_stops")
    private val favLinesKey = stringSetPreferencesKey("favorite_lines")
    private val intercityKey = booleanPreferencesKey("show_intercity")
    private val themeKey = stringPreferencesKey("theme_mode")
    private val notifyKey = booleanPreferencesKey("notify_alerts")
    private val devModeKey = booleanPreferencesKey("dev_mode")
    private val seenAlertsKey = stringSetPreferencesKey("seen_alerts")
    private val hiddenNoticesKey = stringSetPreferencesKey("hidden_notices")

    val prefs: Flow<Prefs> = context.dataStore.data.map { p ->
        Prefs(
            favoriteStops = p[favStopsKey].orEmpty(),
            favoriteLines = p[favLinesKey].orEmpty(),
            showIntercity = p[intercityKey] ?: false,
            themeMode = ThemeMode.entries.firstOrNull { it.name == p[themeKey] } ?: ThemeMode.SYSTEM,
            notifyAlerts = p[notifyKey] ?: true,
            devMode = p[devModeKey] ?: false,
            hiddenNotices = p[hiddenNoticesKey].orEmpty(),
        )
    }

    /**
     * Elrejti az [id] figyelmeztetést. Ha túl sok gyűlt össze, csak a még érvényesek ([current])
     * maradnak meg, így a lejárt forgalmi változások nem halmozódnak fel.
     */
    suspend fun hideNotice(id: String, current: Set<String>) {
        context.dataStore.edit { p ->
            val hidden = p[hiddenNoticesKey].orEmpty() + id
            p[hiddenNoticesKey] = if (hidden.size <= MAX_HIDDEN_NOTICES) hidden else hidden.filter { it in current || it == id }.toSet()
        }
    }

    suspend fun clearHiddenNotices() {
        context.dataStore.edit { it.remove(hiddenNoticesKey) }
    }

    /** A forgalmi változások azonosítói, amelyekről már ment értesítés; az első ellenőrzés előtt null. */
    suspend fun seenAlerts(): Set<String>? = context.dataStore.data.first()[seenAlertsKey]

    suspend fun setSeenAlerts(ids: Set<String>?) {
        context.dataStore.edit { if (ids == null) it.remove(seenAlertsKey) else it[seenAlertsKey] = ids }
    }

    suspend fun setNotifyAlerts(value: Boolean) {
        context.dataStore.edit { it[notifyKey] = value }
    }

    suspend fun setDevMode(value: Boolean) {
        context.dataStore.edit { it[devModeKey] = value }
    }

    suspend fun toggleFavoriteStop(groupName: String) = toggle(favStopsKey, groupName)

    suspend fun toggleFavoriteLine(routeId: String) = toggle(favLinesKey, routeId)

    suspend fun setShowIntercity(value: Boolean) {
        context.dataStore.edit { it[intercityKey] = value }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
    }

    private suspend fun toggle(key: Preferences.Key<Set<String>>, value: String) {
        context.dataStore.edit { p ->
            val current = p[key].orEmpty()
            p[key] = if (value in current) current - value else current + value
        }
    }

    private companion object {
        const val MAX_HIDDEN_NOTICES = 100
    }
}
