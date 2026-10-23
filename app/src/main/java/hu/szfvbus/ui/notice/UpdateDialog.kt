package hu.szfvbus.ui.notice

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import hu.szfvbus.BuildConfig
import hu.szfvbus.data.api.AppRelease

/**
 * Értesítés az új verzióról. A „Letöltés” a kiadás GitHub-oldalát nyitja meg, ahol a változások és
 * az APK-k vannak; a telepítés a felhasználóra marad.
 */
@Composable
fun UpdateDialog(release: AppRelease, onLater: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onLater,
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text("Új verzió: ${release.tag}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Elérhető az SzfvBusz új verziója. Most a v${BuildConfig.VERSION_NAME} van a telefonodon.")
                Text(
                    "A letöltési oldalon a változások is olvashatók. Az új APK-t a régi fölé telepítheted, " +
                        "a kedvenceid és a beállításaid megmaradnak.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.url))) }
                onLater()
            }) { Text("Letöltés") }
        },
        dismissButton = {
            TextButton(onClick = onSkip) { Text("Kihagyom") }
            TextButton(onClick = onLater) { Text("Később") }
        },
    )
}
