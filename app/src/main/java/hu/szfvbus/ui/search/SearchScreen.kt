package hu.szfvbus.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import hu.szfvbus.data.TransitRepository
import hu.szfvbus.data.model.Line
import hu.szfvbus.data.model.Network
import hu.szfvbus.data.model.Place
import hu.szfvbus.data.model.StopGroup
import hu.szfvbus.ui.common.LineBadge
import hu.szfvbus.ui.common.Load
import hu.szfvbus.ui.common.LoadingBox
import hu.szfvbus.ui.common.SectionHeader
import hu.szfvbus.ui.common.appViewModelFactory
import hu.szfvbus.util.searchKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

enum class SearchPurpose { BROWSE, PLAN_FROM, PLAN_TO }

@OptIn(FlowPreview::class)
class SearchViewModel(private val repo: TransitRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    private val _places = MutableStateFlow<Load<List<Place>>?>(null)
    val places = _places.asStateFlow()

    private var geocodingEnabled = false

    init {
        viewModelScope.launch {
            _query.debounce(450).collectLatest { q ->
                if (!geocodingEnabled || q.trim().length < 3) {
                    _places.value = null
                    return@collectLatest
                }
                _places.value = Load.Loading
                _places.value = try {
                    Load.Success(repo.geocode(q.trim()))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Load.Error("A címkeresés most nem elérhető")
                }
            }
        }
    }

    fun enableGeocoding(enabled: Boolean) {
        geocodingEnabled = enabled
    }

    fun setQuery(q: String) {
        _query.value = q
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    purpose: SearchPurpose,
    network: Load<Network>,
    hasLocation: Boolean,
    onBack: () -> Unit,
    onStopGroup: (StopGroup) -> Unit,
    onLine: (Line) -> Unit,
    onPlace: (Place) -> Unit,
    onMyLocation: () -> Unit,
) {
    val vm: SearchViewModel = viewModel(
        key = "search-$purpose",
        factory = appViewModelFactory { SearchViewModel(it.transit) },
    )
    vm.enableGeocoding(purpose != SearchPurpose.BROWSE)
    val query by vm.query.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        vm.setQuery("")
        focus.requestFocus()
    }

    val net = (network as? Load.Success)?.data
    val key = searchKey(query)
    val lines = remember(net, key, purpose) {
        if (net == null || purpose != SearchPurpose.BROWSE || key.isEmpty()) emptyList()
        else net.lines.filter { searchKey(it.shortName).startsWith(key) || searchKey(it.longName).contains(key) }.take(8)
    }
    val groups = remember(net, key) {
        if (net == null || key.isEmpty()) emptyList()
        else net.groups.filter { searchKey(it.name).contains(key) }
            .sortedBy { if (searchKey(it.name).startsWith(key)) 0 else 1 }
            .take(30)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Vissza") }
                },
                title = {
                    TextField(
                        value = query,
                        onValueChange = vm::setQuery,
                        placeholder = {
                            Text(
                                when (purpose) {
                                    SearchPurpose.BROWSE -> "Megálló vagy vonal"
                                    SearchPurpose.PLAN_FROM -> "Honnan? (megálló vagy cím)"
                                    SearchPurpose.PLAN_TO -> "Hová? (megálló vagy cím)"
                                },
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { vm.setQuery("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Törlés")
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (purpose != SearchPurpose.BROWSE && hasLocation) {
                item {
                    ResultRow(
                        icon = { Icon(Icons.Default.MyLocation, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        title = "Saját helyzet",
                        subtitle = null,
                        onClick = onMyLocation,
                    )
                }
            }
            if (net == null) item { LoadingBox() }
            if (lines.isNotEmpty()) {
                item { SectionHeader("Vonalak") }
                items(lines, key = { "l-" + it.id }) { line ->
                    ResultRow(
                        icon = { LineBadge(line.shortName, Color(line.color)) },
                        title = line.longName,
                        subtitle = null,
                        onClick = { onLine(line) },
                    )
                }
            }
            if (groups.isNotEmpty()) {
                item { SectionHeader("Megállók") }
                items(groups, key = { "s-" + it.name }) { group ->
                    ResultRow(
                        icon = { Icon(Icons.Default.DirectionsBus, contentDescription = null) },
                        title = group.name,
                        subtitle = group.lines.joinToString(", ").ifEmpty { null },
                        onClick = { onStopGroup(group) },
                    )
                }
            }
            when (val p = places) {
                null -> Unit
                Load.Loading -> item { LoadingBox() }
                is Load.Error -> item {
                    Text(p.message, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                }
                is Load.Success -> if (p.data.isNotEmpty()) {
                    item { SectionHeader("Címek, helyek") }
                    items(p.data) { place ->
                        ResultRow(
                            icon = { Icon(Icons.Default.Place, contentDescription = null) },
                            title = place.name,
                            subtitle = place.detail,
                            onClick = { onPlace(place) },
                        )
                    }
                }
            }
            if (key.isNotEmpty() && net != null && lines.isEmpty() && groups.isEmpty() && places !is Load.Loading &&
                (places as? Load.Success)?.data.isNullOrEmpty()
            ) {
                item {
                    Text(
                        "Nincs találat.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Column(Modifier.width(48.dp), horizontalAlignment = Alignment.CenterHorizontally) { icon() }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
