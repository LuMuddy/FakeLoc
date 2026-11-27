package io.github.cobylinweiqi.fakeloc.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng

/**
 * Amap (高德)'s base map.
 *
 * Amap speaks **GCJ-02**, like Tencent. The key is applied globally by
 * `MapSdkBootstrap` (`MapsInitializer.setApiKey` + `initialize`) before the view
 * is built; `MapsInitializer.initialize` must have run first or the `MapView`
 * draws nothing, which is why `MapPickerScreen` calls `ensure` during
 * composition rather than in an effect that fires after the frame.
 *
 * Lifecycle: Amap's own `MapView` wants `onCreate`, `onResume`, `onPause` and
 * `onDestroy` forwarded; the observer below covers resume/pause and `onRelease`
 * covers the rest.
 */
@Composable
internal fun AmapCanvas(
    targetLatitude: Double,
    targetLongitude: Double,
    targetZoom: Float,
    moveNonce: Int,
    onCenterChanged: (Double, Double) -> Unit,
    onFailed: (Throwable) -> Unit,
    mapKey: String?,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    key(mapKey) {
        val holder = remember { MapViewHolder<MapView>() }
        val center by rememberUpdatedState(onCenterChanged)
        val fail by rememberUpdatedState(onFailed)

        val targetGcj02 = remember(targetLatitude, targetLongitude) {
            val (lat, lng) = toGcj02(targetLatitude, targetLongitude)
            LatLng(lat, lng)
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> holder.view?.onResume()
                    Lifecycle.Event.ON_PAUSE -> holder.view?.onPause()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        LaunchedEffect(targetGcj02, targetZoom, moveNonce) {
            holder.view?.map?.moveCamera(
                CameraUpdateFactory.newLatLngZoom(targetGcj02, targetZoom),
            )
        }

        AndroidView(
            modifier = modifier,
            factory = { context ->
                buildMapView(context, fail) {
                    MapView(context).also { view ->
                        holder.view = view
                        view.onCreate(null)
                        view.map?.apply {
                            setOnCameraChangeListener(
                                object : AMap.OnCameraChangeListener {
                                    override fun onCameraChange(position: CameraPosition?) {
                                        reportAmapCenter(position, center)
                                    }

                                    override fun onCameraChangeFinish(position: CameraPosition?) {
                                        reportAmapCenter(position, center)
                                    }
                                },
                            )
                            moveCamera(
                                CameraUpdateFactory.newLatLngZoom(targetGcj02, targetZoom),
                            )
                        }
                        view.onResume()
                    }
                }
            },
            onRelease = { view ->
                (view as? MapView)?.let { map ->
                    runCatching {
                        map.onPause()
                        map.onDestroy()
                    }
                }
                if (holder.view === view) holder.view = null
            },
        )
    }
}

private fun reportAmapCenter(position: CameraPosition?, center: (Double, Double) -> Unit) {
    position?.target?.let { target ->
        val (lat, lng) = gcj02ToWgs84(target.latitude, target.longitude)
        center(lat, lng)
    }
}
