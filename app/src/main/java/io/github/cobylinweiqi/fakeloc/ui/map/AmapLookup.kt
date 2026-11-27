package io.github.cobylinweiqi.fakeloc.ui.map

import android.content.Context
import android.util.Log
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeAddress
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.geocoder.RegeocodeResult
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import io.github.cobylinweiqi.fakeloc.core.Coords
import io.github.cobylinweiqi.fakeloc.core.MapProvider
import io.github.cobylinweiqi.fakeloc.core.PickedAddress
import io.github.cobylinweiqi.fakeloc.mapsdk.MapSdkBootstrap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Address lookup and keyword suggestions through the **Amap (高德)** SDK.
 *
 * Amap's `RegeocodeAddress` carries province / city / district / township and
 * an `adCode` in one object, so the mapping is direct. Everything here is
 * WGS-84 in and out; Amap speaks **GCJ-02**, so points are converted at the
 * boundary.
 */

private const val AMAP_TAG = "FakeLoc/AmapLookup"
private const val LOOKUP_TIMEOUT_MS = 6_000L
private const val SUGGESTION_LIMIT = 8

/** Amap's "success" code for search callbacks. */
private const val AMAP_SUCCESS = 1000

internal suspend fun amapReverseGeocode(
    context: Context,
    apiKey: String?,
    latitude: Double,
    longitude: Double,
): PickedAddress? {
    if (apiKey.isNullOrBlank()) return null
    MapSdkBootstrap.ensure(context, MapProvider.AMAP, apiKey)
    val (gcjLat, gcjLng) = Coords.wgs84ToGcj02(latitude, longitude)
    return withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
        suspendCancellableCoroutine<PickedAddress?> { cont ->
            val search = runCatching { GeocodeSearch(context.applicationContext ?: context) }.getOrNull()
            if (search == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            search.setOnGeocodeSearchListener(
                object : GeocodeSearch.OnGeocodeSearchListener {
                    override fun onRegeocodeSearched(result: RegeocodeResult?, code: Int) {
                        if (!cont.isActive) return
                        if (code != AMAP_SUCCESS || result == null) {
                            Log.w(AMAP_TAG, "reverse geocode rejected: code=$code")
                            cont.resume(null)
                            return
                        }
                        cont.resume(readAmapAddress(result.regeocodeAddress))
                    }

                    override fun onGeocodeSearched(result: GeocodeResult?, code: Int) = Unit
                },
            )
            val query = RegeocodeQuery(LatLonPoint(gcjLat, gcjLng), 1000f, GeocodeSearch.AMAP)
            try {
                search.getFromLocationAsyn(query)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }
    } ?: null
}

internal suspend fun amapSuggestPlaces(
    context: Context,
    apiKey: String?,
    query: String,
    nearLatitude: Double,
    nearLongitude: Double,
): List<MapPlaceHit>? {
    if (apiKey.isNullOrBlank()) return null
    MapSdkBootstrap.ensure(context, MapProvider.AMAP, apiKey)
    val (gcjLat, gcjLng) = Coords.wgs84ToGcj02(nearLatitude, nearLongitude)
    return withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
        suspendCancellableCoroutine<List<MapPlaceHit>?> { cont ->
            val poiQuery = PoiSearch.Query(query, "", "").apply {
                pageSize = SUGGESTION_LIMIT
                location = LatLonPoint(gcjLat, gcjLng)
            }
            val search = runCatching {
                PoiSearch(context.applicationContext ?: context, poiQuery)
            }.getOrNull()
            if (search == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            search.setOnPoiSearchListener(
                object : PoiSearch.OnPoiSearchListener {
                    override fun onPoiSearched(result: PoiResult?, code: Int) {
                        if (!cont.isActive) return
                        if (code != AMAP_SUCCESS || result == null) {
                            Log.w(AMAP_TAG, "place suggestion rejected: code=$code")
                            cont.resume(null)
                            return
                        }
                        val rows = result.pois.orEmpty().mapNotNull { item: PoiItem ->
                            val point = item.latLonPoint ?: return@mapNotNull null
                            val (lat, lng) = Coords.gcj02ToWgs84(
                                point.latitude,
                                point.longitude,
                            )
                            MapPlaceHit(
                                title = item.title.orEmpty().ifBlank { item.snippet.orEmpty() },
                                region = listOfNotNull(
                                    item.provinceName,
                                    item.cityName,
                                    item.adName,
                                ).filter { it.isNotBlank() }.joinToString(" · "),
                                latitude = lat,
                                longitude = lng,
                            )
                        }
                        cont.resume(rows.ifEmpty { null })
                    }

                    override fun onPoiItemSearched(item: PoiItem?, code: Int) = Unit
                },
            )
            try {
                search.searchPOIAsyn()
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(null)
            }
        }
    } ?: null
}

internal suspend fun amapProbe(
    context: Context,
    apiKey: String?,
    latitude: Double,
    longitude: Double,
): MapProbe {
    if (apiKey.isNullOrBlank()) return MapProbe.NoKey
    MapSdkBootstrap.ensure(context, MapProvider.AMAP, apiKey)
    val (gcjLat, gcjLng) = Coords.wgs84ToGcj02(latitude, longitude)
    return withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
        suspendCancellableCoroutine<MapProbe> { cont ->
            val search = runCatching { GeocodeSearch(context.applicationContext ?: context) }.getOrNull()
            if (search == null) {
                cont.resume(MapProbe.Unreachable)
                return@suspendCancellableCoroutine
            }
            search.setOnGeocodeSearchListener(
                object : GeocodeSearch.OnGeocodeSearchListener {
                    override fun onRegeocodeSearched(result: RegeocodeResult?, code: Int) {
                        if (!cont.isActive) return
                        cont.resume(
                            if (code == AMAP_SUCCESS && result != null) {
                                MapProbe.Ok
                            } else {
                                MapProbe.Rejected(code)
                            },
                        )
                    }

                    override fun onGeocodeSearched(result: GeocodeResult?, code: Int) = Unit
                },
            )
            val query = RegeocodeQuery(LatLonPoint(gcjLat, gcjLng), 1000f, GeocodeSearch.AMAP)
            try {
                search.getFromLocationAsyn(query)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(MapProbe.Unreachable)
            }
        }
    } ?: MapProbe.Unreachable
}

private fun readAmapAddress(address: RegeocodeAddress?): PickedAddress {
    if (address == null) return PickedAddress.NONE
    val province = address.province.orEmpty()
    val city = address.city.orEmpty()
    if (province.isBlank() && city.isBlank()) return PickedAddress.NONE
    return PickedAddress(
        country = "",
        province = province,
        city = city,
        district = address.district.orEmpty(),
        town = address.township.orEmpty(),
        street = address.streetNumber?.street.orEmpty(),
        adCode = address.adCode.orEmpty(),
        cityCode = "",
        placeName = listOfNotNull(address.neighborhood, address.building)
            .firstOrNull { it.isNotBlank() }
            .orEmpty(),
        line = address.formatAddress.orEmpty(),
    )
}
