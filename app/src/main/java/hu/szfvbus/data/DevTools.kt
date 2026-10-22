package hu.szfvbus.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A rejtett fejlesztői mód tesztkapcsolói.
 * Csak a memóriában élnek, így újraindítás után mindig valós adatok jönnek.
 */
data class DevState(
    /** Menetrend szerint mozgó buszok, hogy az élő követés akkor is kipróbálható legyen, amikor nem jár busz. */
    val simulateVehicles: Boolean = false,
    /** „Felszállok” minden indulásnál, nem csak a felszállási időablakon belül. */
    val boardAnyDeparture: Boolean = false,
    /** Minden várható időhöz hozzáadva, mintha a buszok késnének. */
    val fakeDelayMin: Int = 0,
    /** Kitalált forgalmi hír ezen a vonalon. */
    val testAlertLine: String? = null,
) {
    val isActive get() = this != DevState()
}

object DevTools {
    private val _state = MutableStateFlow(DevState())
    val state = _state.asStateFlow()

    val current get() = _state.value

    fun update(block: (DevState) -> DevState) = _state.update(block)

    fun reset() {
        _state.value = DevState()
    }

    const val TEST_ALERT_ID = "dev-test-alert"
}
