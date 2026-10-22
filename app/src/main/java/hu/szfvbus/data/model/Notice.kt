package hu.szfvbus.data.model

/** Az alkalmazás indításakor teljes képernyőn, egymás után megjelenő figyelmeztetés. */
sealed interface LaunchNotice {
    val id: String

    /** Csak egyszer jelenik meg: a „Rendben” is végleg elrejti. */
    val showOnce: Boolean get() = false

    /** Székesfehérváron még nem minden busz küldi a helyzetét, így nem mind követhető élőben. */
    data object LiveCoverage : LaunchNotice {
        override val id = "live-coverage"
    }

    /** Az első indításkor: az alkalmazás nem hivatalos, nem a MÁV-csoport terméke. */
    data object Unofficial : LaunchNotice {
        override val id = "unofficial"
        override val showOnce get() = true
    }

    data class TrafficChange(val alert: Alert) : LaunchNotice {
        override val id get() = alert.id
    }
}

/**
 * A még megjelenítendő figyelmeztetések sorban: elsőként mindig az élő követésről szóló, utána (az első
 * indításkor) a nem hivatalos alkalmazásról szóló, végül a forgalmi változások. Kimarad, amit a felhasználó
 * elrejtett ([hidden]), vagy ebben a menetben már bezárt ([closed]).
 */
fun noticeQueue(alerts: List<Alert>, hidden: Set<String>, closed: Set<String>): List<LaunchNotice> =
    (listOf(LaunchNotice.LiveCoverage, LaunchNotice.Unofficial) + alerts.map(LaunchNotice::TrafficChange))
        .distinctBy { it.id }
        .filter { it.id !in hidden && it.id !in closed }
