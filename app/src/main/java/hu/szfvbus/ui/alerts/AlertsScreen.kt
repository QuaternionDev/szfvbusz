package hu.szfvbus.ui.alerts

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.szfvbus.AppContainer
import hu.szfvbus.data.model.Alert
import hu.szfvbus.ui.common.EmptyBox
import hu.szfvbus.ui.common.ErrorBox
import hu.szfvbus.ui.common.Load
import hu.szfvbus.ui.common.LoadingBox
import hu.szfvbus.ui.common.appViewModelFactory
import hu.szfvbus.ui.common.period
import hu.szfvbus.ui.common.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AlertsViewModel(container: AppContainer) : ViewModel() {
    private val repo = container.transit
    private val _state = MutableStateFlow<Load<List<Alert>>>(Load.Loading)
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load(force: Boolean = false) {
        viewModelScope.launch {
            _state.value = Load.Loading
            _state.value = try {
                Load.Success(repo.alerts(forceRefresh = force))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Error(e.userMessage())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertsScreen(onBack: () -> Unit) {
    val vm: AlertsViewModel = viewModel(factory = appViewModelFactory { AlertsViewModel(it) })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Forgalmi hírek") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vissza") }
                },
                actions = {
                    IconButton(onClick = { vm.load(force = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Frissítés")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                Load.Loading -> item { LoadingBox() }
                is Load.Error -> item { ErrorBox(s.message, { vm.load(force = true) }) }
                is Load.Success -> {
                    if (s.data.isEmpty()) item { EmptyBox("Jelenleg nincs a helyi járatokat érintő forgalmi változás.") }
                    item {
                        Text(
                            "Forrás: MÁVINFORM (mavcsoport.hu) és a MÁV Plusz menetrendi adatai. " +
                                "Új változásnál értesítést küldünk.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(s.data, key = { it.id }) { alert ->
                        Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (alert.lines.isNotEmpty()) {
                                    Text(
                                        "Érintett vonalak: ${alert.lines.joinToString(", ")}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(alert.header, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                alert.period()?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (alert.description.isNotEmpty()) Text(AnnotatedString.fromHtml(alert.description))
                                alert.url?.takeIf { it.startsWith("http") }?.let { url ->
                                    TextButton(onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                        }
                                    }) { Text("Részletek") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
