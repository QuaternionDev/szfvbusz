package hu.szfvbus.ui.map

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RectF
import android.view.animation.AccelerateDecelerateInterpolator
import hu.szfvbus.data.LineColors
import hu.szfvbus.data.model.GeoPoint
import hu.szfvbus.data.model.Stop
import hu.szfvbus.data.model.Vehicle
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.expressions.Expression.toBool
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.expressions.Expression.toNumber
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconPitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.symbolZOrder
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textMaxWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.abs

/** A térképre rajzolt vonallánc (egy járat, egy vonal vagy egy útvonalterv-szakasz). */
data class MapShape(val points: List<GeoPoint>, val color: Int, val dashed: Boolean = false)

data class MapLabeledPoint(val point: GeoPoint, val name: String, val color: Int, val stopId: String? = null)

/** Minden, ami a jelenlegi kijelöléshez kirajzolódik. */
data class MapOverlay(
    val shapes: List<MapShape> = emptyList(),
    /** A kijelölt járat vagy vonal megállói, színes karikákként. */
    val routeStops: List<MapLabeledPoint> = emptyList(),
    /** A kijelölt megálló peronjai, nagy jelölőkként. */
    val selectedStops: List<MapLabeledPoint> = emptyList(),
    /** Az útvonalterv kiinduló- és célpontja. */
    val endpoints: List<MapLabeledPoint> = emptyList(),
)

/**
 * Az alaptérkép stílusára helyezett források és rétegek gazdája; az élő járműveket két helyzetfrissítés
 * között simán mozgatva rajzolja ki. A stílus minden (újra)betöltésekor újra létrejön.
 */
class MapController(
    context: Context,
    private val map: MapLibreMap,
    private val style: Style,
    private val darkTheme: Boolean,
) {
    var onVehicleClick: ((vehicleId: String) -> Unit)? = null
    var onStopClick: ((stopId: String) -> Unit)? = null
    var onEmptyClick: (() -> Unit)? = null
    var onLongClick: ((GeoPoint) -> Unit)? = null
    var onUserGesture: (() -> Unit)? = null

    private val icons = MapIcons(context)
    private val density = context.resources.displayMetrics.density

    private val shapesSource = GeoJsonSource(SRC_SHAPES)
    private val stopsSource = GeoJsonSource(SRC_STOPS)
    private val routeStopsSource = GeoJsonSource(SRC_ROUTE_STOPS)
    private val selectedStopSource = GeoJsonSource(SRC_SELECTED_STOP)
    private val endpointsSource = GeoJsonSource(SRC_ENDPOINTS)
    private val locationSource = GeoJsonSource(SRC_LOCATION)
    private val vehiclesSource = GeoJsonSource(SRC_VEHICLES)

    private data class Frame(val lat: Double, val lon: Double, val heading: Float?)

    private val displayed = HashMap<String, Frame>()
    private var vehicles: List<Vehicle> = emptyList()
    private var vehicleColors: Map<String, Int> = emptyMap()
    private var selectedVehicleId: String? = null
    private var followVehicleId: String? = null
    private var followPaddingBottom = 0.0
    /** Amíg a kamera egy újonnan követett járműhöz repül, a képkocka-frissítések nem szakíthatják meg. */
    private var followAnimating = false
    /** Követés, amelyet még a jármű megjelenése előtt kértek; amint a jármű feltűnik, végrehajtódik. */
    private var pendingFollowNavigation: Boolean? = null
    private var animator: ValueAnimator? = null

    private val clickListener = MapLibreMap.OnMapClickListener { latLng -> handleClick(latLng) }
    private val longClickListener = MapLibreMap.OnMapLongClickListener { latLng ->
        onLongClick?.invoke(GeoPoint(latLng.latitude, latLng.longitude))
        true
    }
    private val moveStartedListener = MapLibreMap.OnCameraMoveStartedListener { reason ->
        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) onUserGesture?.invoke()
    }

    init {
        addSourcesAndLayers()
        map.addOnMapClickListener(clickListener)
        map.addOnMapLongClickListener(longClickListener)
        map.addOnCameraMoveStartedListener(moveStartedListener)
    }

    private fun addSourcesAndLayers() {
        listOf(
            shapesSource, stopsSource, routeStopsSource, selectedStopSource,
            endpointsSource, locationSource, vehiclesSource,
        ).forEach(style::addSource)

        val stopStroke = if (darkTheme) "#9CCBFF" else "#0D5C91"
        val surface = if (darkTheme) "#1D2024" else "#FFFFFF"
        val labelColor = if (darkTheme) "#E2E2E6" else "#1A1C1E"
        val halo = if (darkTheme) "#111418" else "#FFFFFF"

        style.addLayer(
            LineLayer(LAYER_SHAPES_CASING, SRC_SHAPES).withProperties(
                lineColor(if (darkTheme) "#000000" else "#FFFFFF"),
                lineWidth(interpolate(linear(), zoom(), stop(11, 5f), stop(16, 10f))),
                lineOpacity(0.7f),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(Expression.not(toBool(get("dashed")))),
        )
        style.addLayer(
            LineLayer(LAYER_SHAPES, SRC_SHAPES).withProperties(
                lineColor(toColor(get("color"))),
                lineWidth(interpolate(linear(), zoom(), stop(11, 3f), stop(16, 6.5f))),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
            ).withFilter(Expression.not(toBool(get("dashed")))),
        )
        style.addLayer(
            LineLayer(LAYER_SHAPES_DASHED, SRC_SHAPES).withProperties(
                lineColor(toColor(get("color"))),
                lineWidth(4f),
                lineDasharray(arrayOf(0.1f, 1.8f)),
                lineCap(Property.LINE_CAP_ROUND),
            ).withFilter(toBool(get("dashed"))),
        )
        style.addLayer(
            CircleLayer(LAYER_STOPS, SRC_STOPS).withProperties(
                circleRadius(interpolate(linear(), zoom(), stop(13.5, 2.5f), stop(17, 6f))),
                circleColor(surface),
                circleStrokeColor(stopStroke),
                circleStrokeWidth(interpolate(linear(), zoom(), stop(13.5, 1f), stop(17, 2f))),
            ).apply { minZoom = 13.5f },
        )
        style.addLayer(
            CircleLayer(LAYER_ROUTE_STOPS, SRC_ROUTE_STOPS).withProperties(
                circleRadius(interpolate(linear(), zoom(), stop(11, 3f), stop(17, 7f))),
                circleColor(surface),
                circleStrokeColor(toColor(get("color"))),
                circleStrokeWidth(interpolate(linear(), zoom(), stop(11, 1.5f), stop(17, 3f))),
            ),
        )
        style.addLayer(
            SymbolLayer(LAYER_STOP_LABELS, SRC_STOPS).withProperties(
                textField(get("name")),
                textFont(FONT),
                textSize(11.5f),
                textColor(labelColor),
                textHaloColor(halo),
                textHaloWidth(1.5f),
                textAnchor(Property.TEXT_ANCHOR_TOP),
                textOffset(arrayOf(0f, 0.8f)),
                textOptional(true),
            ).apply { minZoom = 16.3f },
        )
        style.addLayer(
            SymbolLayer(LAYER_ROUTE_STOP_LABELS, SRC_ROUTE_STOPS).withProperties(
                textField(get("name")),
                textFont(FONT),
                textSize(12f),
                textColor(labelColor),
                textHaloColor(halo),
                textHaloWidth(1.5f),
                textAnchor(Property.TEXT_ANCHOR_TOP),
                textOffset(arrayOf(0f, 0.9f)),
                textOptional(true),
            ).apply { minZoom = 14.5f },
        )
        style.addLayer(
            CircleLayer(LAYER_SELECTED_STOP, SRC_SELECTED_STOP).withProperties(
                circleRadius(9f),
                circleColor(stopStroke),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(3f),
            ),
        )
        style.addLayer(
            SymbolLayer(LAYER_SELECTED_STOP_LABELS, SRC_SELECTED_STOP).withProperties(
                textField(get("name")),
                textFont(FONT_BOLD),
                textSize(12.5f),
                textColor(stopStroke),
                textHaloColor(halo),
                textHaloWidth(2f),
                textAnchor(Property.TEXT_ANCHOR_TOP),
                textOffset(arrayOf(0f, 1.1f)),
                textMaxWidth(9f),
                textOptional(true),
            ).apply { minZoom = 14.5f },
        )
        style.addLayer(
            CircleLayer(LAYER_ENDPOINTS, SRC_ENDPOINTS).withProperties(
                circleRadius(8f),
                circleColor(toColor(get("color"))),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(3f),
            ),
        )
        style.addLayer(
            CircleLayer(LAYER_LOCATION_HALO, SRC_LOCATION).withProperties(
                circleRadius(22f),
                circleColor("#1E88E5"),
                circleOpacity(0.18f),
            ),
        )
        style.addLayer(
            CircleLayer(LAYER_LOCATION, SRC_LOCATION).withProperties(
                circleRadius(8f),
                circleColor("#1E88E5"),
                circleStrokeColor("#FFFFFF"),
                circleStrokeWidth(3f),
            ),
        )
        style.addLayer(
            SymbolLayer(LAYER_VEHICLE_POINTERS, SRC_VEHICLES).withProperties(
                iconImage(get("pointer")),
                iconRotate(toNumber(get("heading"))),
                iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_MAP),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconSize(switchCase(toBool(get("sel")), literal(1.25f), literal(1f))),
                symbolZOrder(Property.SYMBOL_Z_ORDER_SOURCE),
            ).withFilter(has("heading")),
        )
        style.addLayer(
            SymbolLayer(LAYER_VEHICLES, SRC_VEHICLES).withProperties(
                iconImage(get("badge")),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                iconSize(switchCase(toBool(get("sel")), literal(1.25f), literal(1f))),
                symbolZOrder(Property.SYMBOL_Z_ORDER_SOURCE),
            ),
        )
    }

    private fun handleClick(latLng: LatLng): Boolean {
        val p = map.projection.toScreenLocation(latLng)
        val r = 18 * density
        val rect = RectF(p.x - r, p.y - r, p.x + r, p.y + r)
        map.queryRenderedFeatures(rect, LAYER_VEHICLES, LAYER_VEHICLE_POINTERS).firstOrNull()?.let { f ->
            f.getStringProperty("id")?.let { onVehicleClick?.invoke(it); return true }
        }
        map.queryRenderedFeatures(rect, LAYER_SELECTED_STOP, LAYER_ROUTE_STOPS, LAYER_STOPS).firstOrNull()?.let { f ->
            f.getStringProperty("id")?.let { onStopClick?.invoke(it); return true }
        }
        onEmptyClick?.invoke()
        return false
    }

    fun setStops(stops: Collection<Stop>) {
        stopsSource.setGeoJson(
            FeatureCollection.fromFeatures(
                stops.map { s ->
                    Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).apply {
                        addStringProperty("id", s.id)
                        // Az állomásokon több azonos nevű peron van; a felirat különbözteti meg őket.
                        addStringProperty("name", s.platformLabel?.let { "${s.name}\n$it" } ?: s.name)
                    }
                },
            ),
        )
    }

    fun setOverlay(overlay: MapOverlay) {
        shapesSource.setGeoJson(
            FeatureCollection.fromFeatures(
                overlay.shapes.filter { it.points.size >= 2 }.map { shape ->
                    Feature.fromGeometry(LineString.fromLngLats(shape.points.map { Point.fromLngLat(it.lon, it.lat) }))
                        .apply {
                            addStringProperty("color", LineColors.hex(shape.color))
                            addBooleanProperty("dashed", shape.dashed)
                        }
                },
            ),
        )
        routeStopsSource.setGeoJson(
            FeatureCollection.fromFeatures(
                overlay.routeStops.map { s ->
                    Feature.fromGeometry(Point.fromLngLat(s.point.lon, s.point.lat)).apply {
                        addStringProperty("name", s.name)
                        addStringProperty("color", LineColors.hex(s.color))
                        s.stopId?.let { addStringProperty("id", it) }
                    }
                },
            ),
        )
        selectedStopSource.setGeoJson(
            FeatureCollection.fromFeatures(
                overlay.selectedStops.map { s ->
                    Feature.fromGeometry(Point.fromLngLat(s.point.lon, s.point.lat)).apply {
                        addStringProperty("name", s.name)
                        s.stopId?.let { addStringProperty("id", it) }
                    }
                },
            ),
        )
        endpointsSource.setGeoJson(
            FeatureCollection.fromFeatures(
                overlay.endpoints.map { e ->
                    Feature.fromGeometry(Point.fromLngLat(e.point.lon, e.point.lat)).apply {
                        addStringProperty("name", e.name)
                        addStringProperty("color", LineColors.hex(e.color))
                    }
                },
            ),
        )
    }

    fun setMyLocation(point: GeoPoint?) {
        locationSource.setGeoJson(
            FeatureCollection.fromFeatures(
                listOfNotNull(point?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }),
            ),
        )
    }

    /**
     * A [list] járműveit mutatja élő flottaként. A járművek a kirajzolt helyükről csúsznak az újra;
     * a messzire ugrott (vagy most megjelent) járművek közvetlenül a helyükre kerülnek.
     */
    fun setVehicles(list: List<Vehicle>, colors: Map<String, Int>, selectedId: String?) {
        val selectionChanged = selectedId != selectedVehicleId
        selectedVehicleId = selectedId
        vehicleColors = colors
        list.forEach { ensureImages(it.line, colors[it.id] ?: LineColors.INTERCITY) }

        val starts = list.associate { v -> v.id to (displayed[v.id] ?: Frame(v.lat, v.lon, v.heading)) }
        val moved = list.any { v ->
            val s = starts.getValue(v.id)
            s.lat != v.lat || s.lon != v.lon
        }
        vehicles = list
        animator?.cancel()
        displayed.keys.retainAll(list.map { it.id }.toSet())
        pendingFollowNavigation?.let { navigation ->
            val id = followVehicleId?.takeIf { it in starts } ?: return@let
            displayed[id] = starts.getValue(id)
            follow(id, followPaddingBottom.toInt(), navigation)
        }
        if (!moved) {
            renderFrame(starts, 1f)
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (selectionChanged) 0 else ANIMATION_MS
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { renderFrame(starts, it.animatedValue as Float) }
            start()
        }
    }

    private fun renderFrame(starts: Map<String, Frame>, t: Float) {
        val features = ArrayList<Feature>(vehicles.size)
        var followed: Frame? = null
        // A kijelölt jármű utoljára, hogy felül rajzolódjon.
        val ordered = vehicles.sortedBy { it.id == selectedVehicleId }
        for (v in ordered) {
            val s = starts[v.id] ?: Frame(v.lat, v.lon, v.heading)
            val far = abs(s.lat - v.lat) > 0.02 || abs(s.lon - v.lon) > 0.03
            val k = if (far) 1.0 else t.toDouble()
            val frame = Frame(
                lat = s.lat + (v.lat - s.lat) * k,
                lon = s.lon + (v.lon - s.lon) * k,
                heading = v.heading ?: s.heading,
            )
            displayed[v.id] = frame
            if (v.id == followVehicleId) followed = frame
            val color = vehicleColors[v.id] ?: LineColors.INTERCITY
            features += Feature.fromGeometry(Point.fromLngLat(frame.lon, frame.lat)).apply {
                addStringProperty("id", v.id)
                addStringProperty("badge", icons.badgeName(v.line, color))
                addStringProperty("pointer", icons.pointerName(color))
                addBooleanProperty("sel", v.id == selectedVehicleId)
                frame.heading?.let { addNumberProperty("heading", it) }
            }
        }
        vehiclesSource.setGeoJson(FeatureCollection.fromFeatures(features))
        followed?.takeIf { !followAnimating }?.let { f ->
            map.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder(map.cameraPosition)
                        .target(LatLng(f.lat, f.lon))
                        .padding(0.0, 0.0, 0.0, followPaddingBottom)
                        .build(),
                ),
            )
        }
    }

    private fun ensureImages(line: String, color: Int) {
        val badge = icons.badgeName(line, color)
        if (style.getImage(badge) == null) style.addImage(badge, icons.badge(line, color))
        val pointer = icons.pointerName(color)
        if (style.getImage(pointer) == null) style.addImage(pointer, icons.pointer(color))
    }

    /**
     * A kamerát a mozgó járművön tartja; null esetén leáll a követés.
     * [navigation] módban a térkép megdől és ráközelít, mint egy navigációs alkalmazásban.
     */
    fun follow(vehicleId: String?, bottomPaddingPx: Int, navigation: Boolean = false) {
        followVehicleId = vehicleId
        followPaddingBottom = bottomPaddingPx.toDouble()
        val frame = vehicleId?.let { displayed[it] }
        pendingFollowNavigation = if (frame == null && vehicleId != null) navigation else null
        if (frame == null) return
        followAnimating = true
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(frame.lat, frame.lon))
                    .zoom(if (navigation) NAVIGATION_ZOOM else maxOf(map.cameraPosition.zoom, 15.0))
                    .tilt(if (navigation) NAVIGATION_TILT else map.cameraPosition.tilt)
                    .padding(0.0, 0.0, 0.0, followPaddingBottom)
                    .build(),
            ),
            600,
            object : MapLibreMap.CancelableCallback {
                override fun onCancel() {
                    followAnimating = false
                }

                override fun onFinish() {
                    followAnimating = false
                }
            },
        )
    }

    /** Vissza a lapos, északra tájolt térképre egy utazás után. */
    fun resetTilt() {
        val position = map.cameraPosition
        if (position.tilt == 0.0 && position.bearing == 0.0) return
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(CameraPosition.Builder(position).tilt(0.0).bearing(0.0).build()),
            500,
        )
    }

    fun moveTo(point: GeoPoint, zoom: Double, bottomPaddingPx: Int) {
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(point.lat, point.lon))
                    .zoom(zoom)
                    .padding(0.0, 0.0, 0.0, bottomPaddingPx.toDouble())
                    .build(),
            ),
            600,
        )
    }

    fun fit(points: List<GeoPoint>, bottomPaddingPx: Int, topPaddingPx: Int) {
        if (points.isEmpty()) return
        if (points.size == 1 || points.all { it == points.first() }) {
            moveTo(points.first(), 16.0, bottomPaddingPx)
            return
        }
        val bounds = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.lat, it.lon)) } }.build()
        val side = (56 * density).toInt()
        map.animateCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, side, topPaddingPx + side, side, bottomPaddingPx + side),
            700,
        )
    }

    fun visibleCenter(): GeoPoint {
        val t = map.cameraPosition.target ?: return GeoPoint(CITY_CENTER.latitude, CITY_CENTER.longitude)
        return GeoPoint(t.latitude, t.longitude)
    }

    fun dispose() {
        animator?.cancel()
        animator = null
        map.removeOnMapClickListener(clickListener)
        map.removeOnMapLongClickListener(longClickListener)
        map.removeOnCameraMoveStartedListener(moveStartedListener)
    }

    companion object {
        val CITY_CENTER = LatLng(47.1910, 18.4105)
        const val DEFAULT_ZOOM = 13.2
        const val ANIMATION_MS = 1500L
        val FONT = arrayOf("Noto Sans Regular")
        val FONT_BOLD = arrayOf("Noto Sans Bold")

        const val STYLE_LIGHT = "https://tiles.openfreemap.org/styles/positron"
        const val STYLE_DARK = "https://tiles.openfreemap.org/styles/dark"

        private const val SRC_SHAPES = "szfv-shapes"
        private const val SRC_STOPS = "szfv-stops"
        private const val SRC_ROUTE_STOPS = "szfv-route-stops"
        private const val SRC_SELECTED_STOP = "szfv-selected-stop"
        private const val SRC_ENDPOINTS = "szfv-endpoints"
        private const val SRC_LOCATION = "szfv-location"
        private const val SRC_VEHICLES = "szfv-vehicles"

        private const val LAYER_SHAPES_CASING = "szfv-shapes-casing"
        private const val LAYER_SHAPES = "szfv-shapes"
        private const val LAYER_SHAPES_DASHED = "szfv-shapes-dashed"
        private const val LAYER_STOPS = "szfv-stops"
        private const val LAYER_ROUTE_STOPS = "szfv-route-stops"
        private const val LAYER_STOP_LABELS = "szfv-stop-labels"
        private const val LAYER_ROUTE_STOP_LABELS = "szfv-route-stop-labels"
        private const val LAYER_SELECTED_STOP = "szfv-selected-stop"
        private const val LAYER_SELECTED_STOP_LABELS = "szfv-selected-stop-labels"
        private const val NAVIGATION_ZOOM = 16.3
        private const val NAVIGATION_TILT = 50.0
        private const val LAYER_ENDPOINTS = "szfv-endpoints"
        private const val LAYER_LOCATION_HALO = "szfv-location-halo"
        private const val LAYER_LOCATION = "szfv-location"
        private const val LAYER_VEHICLE_POINTERS = "szfv-vehicle-pointers"
        private const val LAYER_VEHICLES = "szfv-vehicles"
    }
}
