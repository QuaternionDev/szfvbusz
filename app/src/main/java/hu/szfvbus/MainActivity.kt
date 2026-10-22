package hu.szfvbus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import hu.szfvbus.notify.AlertCheckWorker
import hu.szfvbus.notify.AlertNotifier
import hu.szfvbus.ui.SzfvBusAppUi
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    /** A forgalmi változásokról szóló értesítésekre koppintások száma; mindegyik megnyitja a forgalmi híreket. */
    private val openAlertsRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIntent(intent)
            // Friss hírek azonnal, a következő időszakos ellenőrzés kivárása nélkül.
            AlertCheckWorker.runNow(this)
        }
        setContent { SzfvBusAppUi(openAlertsRequests) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(AlertNotifier.EXTRA_OPEN_ALERTS, false) == true) {
            intent.removeExtra(AlertNotifier.EXTRA_OPEN_ALERTS)
            openAlertsRequests.value++
        }
    }
}
