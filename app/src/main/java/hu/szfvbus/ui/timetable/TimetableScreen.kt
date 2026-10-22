package hu.szfvbus.ui.timetable

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.szfvbus.AppContainer
import hu.szfvbus.data.model.TimetablePattern
import hu.szfvbus.ui.common.EmptyBox
import hu.szfvbus.ui.common.ErrorBox
import hu.szfvbus.ui.common.LineBadge
import hu.szfvbus.ui.common.Load
import hu.szfvbus.ui.common.LoadingBox
import hu.szfvbus.ui.common.appViewModelFactory
import hu.szfvbus.ui.common.lineColorOf
import hu.szfvbus.ui.common.userMessage
import hu.szfvbus.util.TimeUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class TimetableViewModel(container: AppContainer, private val groupName: String) : ViewModel() {
    private val repo = container.transit

    private val _date = MutableStateFlow(TimeUtil.today())
    val date = _date.asStateFlow()

    private val _state = MutableStateFlow<Load<List<TimetablePattern>>>(Load.Loading)
    val state = _state.asStateFlow()

    private var job: Job? = null

    init {
        load()
    }

    fun setDate(date: LocalDate) {
        _date.value = date
        load()
    }

    fun load() {
        job?.cancel()
        job = viewModelScope.launch {
            _state.value = Load.Loading
            _state.value = try {
                val group = repo.network().group(groupName) ?: throw IllegalStateException("Ismeretlen megálló")
                Load.Success(repo.timetable(group.ids, _date.value))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Load.Error(e.userMessage())
            }
        }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("yyyy. MMMM d., EEEE", Locale.forLanguageTag("hu"))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TimetableScreen(
    groupName: String,
    routeId: String?,
    onBack: () -> Unit,
) {
    val vm: TimetableViewModel = viewModel(
        key = "timetable-$groupName",
        factory = appViewModelFactory { TimetableViewModel(it, groupName) },
    )
    val date by vm.date.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedKey by rememberSaveable(groupName, routeId) { mutableStateOf<String?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(groupName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vissza") }
                },
            )
        },
    ) { padding ->
        val patterns = (state as? Load.Success)?.data.orEmpty()
        // Alapértelmezett szűrő: a kért vonal első iránya, egyébként minden.
        LaunchedEffect(patterns, routeId) {
            if (selectedKey == null && routeId != null) {
                selectedKey = patterns.firstOrNull { it.routeId == routeId }?.let { keyOf(it) }
            }
        }
        val visible = patterns.filter { selectedKey == null || keyOf(it) == selectedKey }
        val listState = rememberLazyListState()

        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { vm.setDate(date.minusDays(1)) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Előző nap")
                    }
                    TextButton(onClick = { showPicker = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(dateFormat.format(date), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { vm.setDate(date.plusDays(1)) }) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Következő nap")
                    }
                }
            }
            if (patterns.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = selectedKey == null,
                            onClick = { selectedKey = null },
                            label = { Text("Összes") },
                        )
                        patterns.forEach { p ->
                            FilterChip(
                                selected = selectedKey == keyOf(p),
                                onClick = { selectedKey = keyOf(p) },
                                leadingIcon = { LineBadge(p.line, lineColorOf(p.line, p.isLocal), small = true) },
                                label = { Text(p.headsign, maxLines = 1) },
                            )
                        }
                    }
                }
            }
            when (val s = state) {
                Load.Loading -> item { LoadingBox() }
                is Load.Error -> item { ErrorBox(s.message, vm::load) }
                is Load.Success -> {
                    if (s.data.isEmpty()) item { EmptyBox("Ezen a napon nincs indulás erről a megállóról.") }
                    val isToday = date == TimeUtil.today()
                    items(visible, key = { keyOf(it) }) { p ->
                        PatternTimetable(p, highlightNext = isToday)
                    }
                }
            }
        }
    }

    if (showPicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let {
                        vm.setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text("Kiválasztás") }
            },
            dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Mégse") } },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

private fun keyOf(p: TimetablePattern) = p.routeId + "|" + p.headsign

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PatternTimetable(p: TimetablePattern, highlightNext: Boolean) {
    val now = TimeUtil.nowSec()
    val nextIndex = if (highlightNext) p.departures.indexOfFirst { p.serviceDay + it >= now } else -1
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineBadge(p.line, lineColorOf(p.line, p.isLocal))
        Spacer(Modifier.width(12.dp))
        Text("→ ${p.headsign}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    val byHour = p.departures.withIndex().groupBy { it.value / 3600 }
    byHour.forEach { (hour, entries) ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(
                (hour % 24).toString().padStart(2, '0'),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(40.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                entries.forEach { (index, seconds) ->
                    val minute = (seconds % 3600) / 60
                    val next = index == nextIndex
                    Text(
                        minute.toString().padStart(2, '0'),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (next) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            next -> MaterialTheme.colorScheme.onPrimary
                            nextIndex >= 0 && index < nextIndex -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = if (next) {
                            Modifier.clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 4.dp)
                        } else {
                            Modifier.padding(horizontal = 4.dp)
                        },
                    )
                }
            }
        }
    }
}
