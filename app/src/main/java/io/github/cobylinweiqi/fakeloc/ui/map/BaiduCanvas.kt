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
import com.baidu.mapapi.map.BaiduMap
import com.baidu.mapapi.map.MapStatus
import com.baidu.mapapi.map.MapStatusUpdateFactory
import com.baidu.mapapi.map.MapView
import com.baidu.mapapi.model.LatLng

/**
 * Baidu's base map.
 *
 * Baidu speaks **BD-09**, so the WGS-84 anchor the screen hands over is converted
 * here and nowhere else; every callback converts back before reporting. Getting
 * this wrong puts the pin a few hundred metres off while the map itself looks
 * perfectly healthy — the same trap the Tencent canvas documents from the other
 * direction.
 *
 * The key is applied globally by `MapSdkBootstrap` through
 * `SDKInitializer.setApiKey` before the first view is built, so unlike Tencent
 * there is nothing per-view to pass. The [key] wrapper still rebuilds the view
 * when the key string changes so a fresh key is picked up on the next process
 * (Baidu reads its key once at `initialize`).
 */
@Composable
internal fun BaiduCanvas(
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

        val targetBd09 = remember(targetLatitude, targetLongitude) {
            val (lat, lng) = toBd09(targetLatitude, targetLongitude)
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

        LaunchedEffect(targetBd09, targetZoom, moveNonce) {
            holder.view?.map?.animateMapStatus(
                MapStatusUpdateFactory.newLatLngZoom(targetBd09, targetZoom),
            )
        }

        AndroidView(
            modifier = modifier,
            factory = { context ->
                buildMapView(context, fail) {
                    MapView(context).also { view ->
                        holder.view = view
                        view.map?.apply {
                            setOnMapStatusChangeListener(
                                object : BaiduMap.OnMapStatusChangeListener {
                                    override fun onMapStatusChangeStart(status: MapStatus?) = Unit

                                    override fun onMapStatusChangeStart(
                                        status: MapStatus?,
                                        reason: Int,
                                    ) = Unit

                                    override fun onMapStatusChange(status: MapStatus?) {
                                        reportBaiduCenter(status, center)
                                    }

                                    override fun onMapStatusChangeFinish(status: MapStatus?) {
                                        reportBaiduCenter(status, center)
                                    }
                                },
                            )
                            animateMapStatus(
                                MapStatusUpdateFactory.newLatLngZoom(targetBd09, targetZoom),
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

private fun reportBaiduCenter(status: MapStatus?, center: (Double, Double) -> Unit) {
    status?.target?.let { target ->
        val (lat, lng) = bd09ToWgs84(target.latitude, target.longitude)
        center(lat, lng)
    }
}
