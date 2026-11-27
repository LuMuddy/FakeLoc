package io.github.cobylinweiqi.fakeloc.ui.map

import android.content.Context
import android.util.Log
import com.baidu.mapapi.model.LatLng
import com.baidu.mapapi.search.core.SearchResult
import com.baidu.mapapi.search.geocode.GeoCodeResult
import com.baidu.mapapi.search.geocode.GeoCoder
import com.baidu.mapapi.search.geocode.OnGetGeoCoderResultListener
import com.baidu.mapapi.search.geocode.ReverseGeoCodeOption
import com.baidu.mapapi.search.geocode.ReverseGeoCodeResult
import com.baidu.mapapi.search.sug.OnGetSuggestionResultListener
import com.baidu.mapapi.search.sug.SuggestionResult
import com.baidu.mapapi.search.sug.SuggestionSearch
import com.baidu.mapapi.search.sug.SuggestionSearchOption
import io.github.cobylinweiqi.fakeloc.core.Coords
import io.github.cobylinweiqi.fakeloc.core.MapProvider
import io.github.cobylinweiqi.fakeloc.core.PickedAddress
import io.github.cobylinweiqi.fakeloc.mapsdk.MapSdkBootstrap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Address lookup and keyword suggestions through the **Baidu** map SDK.
 *
 * Same job as `TencentLookup`, different vendor: Baidu's search clients answer
 * with the administrative parts by way of `ReverseGeoCodeResult.AddressComponent`
 * (province / city / district / street / town plus `adcode`), which is exactly
 * what the hook side reads off `BDLocation`.
 *
 * Datum: everything here is WGS-84 in and out. Baidu speaks **BD-09**, so the
 * point is converted on the way in and back on the way out — the picker never
 * sees a second coordinate system.
 */

private const val BAIDU_TAG = "FakeLoc/BaiduLookup"
private const val LOOKUP_TIMEOUT_MS = 6_000L
private const val SUGGESTION_LIMIT = 8

internal suspend fun baiduReverseGeocode(
    context: Context,
    apiKey: String?,
    latitude: Double,
    longitude: Double,
): PickedAddress? = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
    suspendCancellableCoroutine<PickedAddress?> { cont ->
        MapSdkBootstrap.ensure(context, MapProvider.BAIDU, apiKey)
        val coder = runCatching { GeoCoder.newInstance() }.getOrNull()
        if (coder == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        coder.setOnGetGeoCodeResultListener(
            object : OnGetGeoCoderResultListener {
                override fun onGetGeoCodeResult(result: GeoCodeResult?) = Unit

                override fun onGetReverseGeoCodeResult(result: ReverseGeoCodeResult?) {
                    if (!cont.isActive) return
                    if (result == null || result.error != SearchResult.ERRORNO.NO_ERROR) {
                        Log.w(BAIDU_TAG, "reverse geocode rejected: ${result?.error}")
                        cont.resume(null)
                        return
                    }
                    cont.resume(readBaiduAddress(result))
                }
            },
        )
        val (bdLat, bdLng) = Coords.wgs84ToBd09(latitude, longitude)
        try {
            coder.reverseGeoCode(ReverseGeoCodeOption().location(LatLng(bdLat, bdLng)))
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
        cont.invokeOnCancellation { runCatching { coder.destroy() } }
    }
} ?: null

internal suspend fun baiduSuggestPlaces(
    context: Context,
    apiKey: String?,
    query: String,
    nearLatitude: Double,
    nearLongitude: Double,
): List<MapPlaceHit>? = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
    suspendCancellableCoroutine<List<MapPlaceHit>?> { cont ->
        MapSdkBootstrap.ensure(context, MapProvider.BAIDU, apiKey)
        val search = runCatching { SuggestionSearch.newInstance() }.getOrNull()
        if (search == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        search.setOnGetSuggestionResultListener(
            object : OnGetSuggestionResultListener {
                override fun onGetSuggestionResult(result: SuggestionResult?) {
                    if (!cont.isActive) return
                    if (result == null || result.error != SearchResult.ERRORNO.NO_ERROR) {
                        Log.w(BAIDU_TAG, "place suggestion rejected: ${result?.error}")
                        cont.resume(null)
                        return
                    }
                    val rows = result.allSuggestions.orEmpty().mapNotNull { info ->
                        val point = info.pt ?: return@mapNotNull null
                        val (lat, lng) = Coords.bd09ToWgs84(point.latitude, point.longitude)
                        MapPlaceHit(
                            title = info.key.orEmpty().ifBlank { info.address.orEmpty() },
                            region = listOfNotNull(info.city, info.district)
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            latitude = lat,
                            longitude = lng,
                        )
                    }
                    runCatching { search.destroy() }
                    cont.resume(rows.ifEmpty { null })
                }
            },
        )
        val (bdLat, bdLng) = Coords.wgs84ToBd09(nearLatitude, nearLongitude)
        val option = SuggestionSearchOption()
            .keyword(query)
            .location(LatLng(bdLat, bdLng))
        try {
            search.requestSuggestion(option)
        } catch (t: Throwable) {
            if (cont.isActive) cont.resume(null)
        }
        cont.invokeOnCancellation { runCatching { search.destroy() } }
    }
} ?: null

internal suspend fun baiduProbe(
    context: Context,
    apiKey: String?,
    latitude: Double,
    longitude: Double,
): MapProbe {
    if (apiKey.isNullOrBlank()) return MapProbe.NoKey
    return withTimeoutOrNull(LOOKUP_TIMEOUT_MS) {
        suspendCancellableCoroutine<MapProbe> { cont ->
            MapSdkBootstrap.ensure(context, MapProvider.BAIDU, apiKey)
            val coder = runCatching { GeoCoder.newInstance() }.getOrNull()
            if (coder == null) {
                cont.resume(MapProbe.Unreachable)
                return@suspendCancellableCoroutine
            }
            coder.setOnGetGeoCodeResultListener(
                object : OnGetGeoCoderResultListener {
                    override fun onGetGeoCodeResult(result: GeoCodeResult?) = Unit

                    override fun onGetReverseGeoCodeResult(result: ReverseGeoCodeResult?) {
                        if (!cont.isActive) return
                        runCatching { coder.destroy() }
                        val error = result?.error
                        cont.resume(
                            if (error == SearchResult.ERRORNO.NO_ERROR) {
                                MapProbe.Ok
                            } else {
                                MapProbe.Rejected(error?.ordinal ?: -1)
                            },
                        )
                    }
                },
            )
            val (bdLat, bdLng) = Coords.wgs84ToBd09(latitude, longitude)
            try {
                coder.reverseGeoCode(ReverseGeoCodeOption().location(LatLng(bdLat, bdLng)))
            } catch (t: Throwable) {
                if (cont.isActive) cont.resume(MapProbe.Unreachable)
            }
        }
    } ?: MapProbe.Unreachable
}

private fun readBaiduAddress(result: ReverseGeoCodeResult): PickedAddress {
    val detail = result.addressDetail
    val province = detail?.province.orEmpty()
    val city = detail?.city.orEmpty()
    if (province.isBlank() && city.isBlank()) return PickedAddress.NONE

    val adcode = detail?.adcode ?: 0
    return PickedAddress(
        country = detail?.countryName.orEmpty(),
        province = province,
        city = city,
        district = detail?.district.orEmpty(),
        town = detail?.town.orEmpty(),
        street = detail?.street.orEmpty(),
        adCode = if (adcode > 0) adcode.toString() else "",
        // Amap's `cityCode` is a phone area code, not the app-internal table CCB
        // expects; left blank so `SpoofConfig.addressFor` answers from its own
        // pinned default exactly as it does on the Tencent path.
        cityCode = "",
        placeName = result.poiList?.firstOrNull()?.name
            ?: result.formattedPoiAddress.orEmpty(),
        line = result.address.orEmpty(),
    )
}
