package hu.szfvbus.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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
    /** Kedvenc megállóirányok, irányonként egy peron azonosítójával (lásd [hu.szfvbus.data.model.StopGroup.sameDirection]). */
    val favoriteDirections: Set<String> = emptySet(),
    val showIntercity: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Értesítések az új forgalmi változásokról. */
    val notifyAlerts: Boolean = true,
    /** A rejtett fejlesztői mód, a Névjegyből kapcsolható be. */
    val devMode: Boolean = false,
    /** Az indítási figyelmeztetések, amelyeknél a „Ne mutasd újra” gombot választották. */
    val hiddenNotices: Set<String> = emptySet(),
    /** Induláskor (naponta legfeljebb egyszer) megnézi, van-e új verzió a GitHubon. */
    val checkUpdates: Boolean = true,
    /** A verzió, amelynél a „Kihagyom” gombot választották; erről nem szól többet. */
    val skippedVersion: String? = null,
    /** A Közelben fül keresési sugara méterben, a [NEARBY_RADII] egyike. */
    val nearbyRadiusM: Int = DEFAULT_NEARBY_RADIUS,
)

/** A Közelben fülön választható keresési sugarak méterben. */
val NEARBY_RADII = listOf(300, 500, 700, 1000, 1500)
const val DEFAULT_NEARBY_RADIUS = 700

enum class ThemeMode(val label: String) {
    SYSTEM("Rendszer szerint"),
    LIGHT("Világos"),
    DARK("Sötét"),
}

class UserPrefs(private val context: Context) {
    private val favStopsKey = stringSetPreferencesKey("favorite_stops")
    private val favLinesKey = stringSetPreferencesKey("favorite_lines")
    private val favDirectionsKey = stringSetPreferencesKey("favorite_directions")
    private val intercityKey = booleanPreferencesKey("show_intercity")
    private val themeKey = stringPreferencesKey("theme_mode")
    private val notifyKey = booleanPreferencesKey("notify_alerts")
    private val devModeKey = booleanPreferencesKey("dev_mode")
    private val seenAlertsKey = stringSetPreferencesKey("seen_alerts")
    private val hiddenNoticesKey = stringSetPreferencesKey("hidden_notices")
    private val checkUpdatesKey = booleanPreferencesKey("check_updates")
    private val skippedVersionKey = stringPreferencesKey("skipped_version")
    private val lastUpdateCheckKey = longPreferencesKey("last_update_check")
    private val nearbyRadiusKey = intPreferencesKey("nearby_radius")

    val prefs: Flow<Prefs> = context.dataStore.data.map { p ->
        Prefs(
            favoriteStops = p[favStopsKey].orEmpty(),
            favoriteLines = p[favLinesKey].orEmpty(),
            favoriteDirections = p[favDirectionsKey].orEmpty(),
            showIntercity = p[intercityKey] ?: false,
            themeMode = ThemeMode.entries.firstOrNull { it.name == p[themeKey] } ?: ThemeMode.SYSTEM,
            notifyAlerts = p[notifyKey] ?: true,
            devMode = p[devModeKey] ?: false,
            hiddenNotices = p[hiddenNoticesKey].orEmpty(),
            checkUpdates = p[checkUpdatesKey] ?: true,
            skippedVersion = p[skippedVersionKey],
            nearbyRadiusM = p[nearbyRadiusKey]?.takeIf { it in NEARBY_RADII } ?: DEFAULT_NEARBY_RADIUS,
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

    suspend fun setCheckUpdates(value: Boolean) {
        context.dataStore.edit { it[checkUpdatesKey] = value }
    }

    suspend fun setSkippedVersion(version: String?) {
        context.dataStore.edit { if (version == null) it.remove(skippedVersionKey) else it[skippedVersionKey] = version }
    }

    /** A legutóbbi sikeres frissítéskeresés ideje (epoch ms), vagy 0. */
    suspend fun lastUpdateCheck(): Long = context.dataStore.data.first()[lastUpdateCheckKey] ?: 0

    suspend fun setLastUpdateCheck(epochMs: Long) {
        context.dataStore.edit { it[lastUpdateCheckKey] = epochMs }
    }

    suspend fun setNearbyRadius(meters: Int) {
        context.dataStore.edit { it[nearbyRadiusKey] = meters }
    }

    suspend fun setDevMode(value: Boolean) {
        context.dataStore.edit { it[devModeKey] = value }
    }

    suspend fun toggleFavoriteStop(groupName: String) = toggle(favStopsKey, groupName)

    suspend fun toggleFavoriteLine(routeId: String) = toggle(favLinesKey, routeId)

    /**
     * Egy megállóirány, amelynek a peronjai [stopIds]: ha bármelyikük kedvenc, mind kikerül,
     * különben az első bekerül.
     */
    suspend fun toggleFavoriteDirection(stopIds: List<String>) {
        if (stopIds.isEmpty()) return
        context.dataStore.edit { p ->
            val current = p[favDirectionsKey].orEmpty()
            p[favDirectionsKey] = if (stopIds.any { it in current }) current - stopIds.toSet() else current + stopIds.first()
        }
    }

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
