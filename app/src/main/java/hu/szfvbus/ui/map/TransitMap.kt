package hu.szfvbus.ui.map

import android.view.Gravity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

/**
 * Compose-ba ágyazott MapLibre-térkép.
 * Az [onController] minden stílusbetöltés után (a világos és a sötét téma közti váltáskor is)
 * friss [MapController]-t kap, előtte pedig nullt.
 */
@Composable
fun TransitMap(
    darkTheme: Boolean,
    topInset: Dp,
    onController: (MapController?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnController by rememberUpdatedState(onController)

    val mapView = remember {
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .camera(
                CameraPosition.Builder()
                    .target(MapController.CITY_CENTER)
                    .zoom(MapController.DEFAULT_ZOOM)
                    .build(),
            )
            .logoEnabled(true)
            .attributionEnabled(true)
        MapView(context, options).apply { onCreate(null) }
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var controller by remember { mutableStateOf<MapController?>(null) }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller?.dispose()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.apply {
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = false
                setCompassGravity(Gravity.TOP or Gravity.END)
            }
            m.setMinZoomPreference(10.0)
            m.setMaxZoomPreference(19.0)
            m.setLatLngBoundsForCameraTarget(
                LatLngBounds.Builder()
                    .include(LatLng(46.95, 18.05))
                    .include(LatLng(47.45, 18.80))
                    .build(),
            )
            map = m
        }
    }

    // Az iránytű, a logó és a forrásmegjelölés ne kerüljön a lebegő keresősáv és a panel alá.
    LaunchedEffect(map, topInset) {
        val m = map ?: return@LaunchedEffect
        val top = with(density) { topInset.roundToPx() }
        val side = with(density) { Dp(12f).roundToPx() }
        m.uiSettings.setCompassMargins(0, top + side, side, 0)
        m.uiSettings.setLogoGravity(Gravity.TOP or Gravity.START)
        m.uiSettings.setLogoMargins(side, top + side, 0, 0)
        m.uiSettings.setAttributionGravity(Gravity.TOP or Gravity.START)
        m.uiSettings.setAttributionMargins(side + with(density) { Dp(90f).roundToPx() }, top + side, 0, 0)
    }

    LaunchedEffect(map, darkTheme) {
        val m = map ?: return@LaunchedEffect
        controller?.dispose()
        controller = null
        currentOnController(null)
        val url = if (darkTheme) MapController.STYLE_DARK else MapController.STYLE_LIGHT
        m.setStyle(url) { style ->
            val c = MapController(context, m, style, darkTheme)
            controller = c
            currentOnController(c)
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
