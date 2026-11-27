package io.github.cobylinweiqi.fakeloc.ui.map

import android.content.Context
import io.github.cobylinweiqi.fakeloc.core.MapProvider
import io.github.cobylinweiqi.fakeloc.core.PickedAddress

/**
 * The picker's provider-agnostic lookup surface.
 *
 * The screen works in WGS-84 throughout; each provider implementation converts
 * to its own datum internally. Keeping the dispatch here means the UI never has
 * to know which SDK a given provider uses — it just asks for an address, a
 * suggestion, or a probe, and names the selected [MapProvider].
 */

internal suspend fun mapReverseGeocode(
    context: Context,
    provider: MapProvider,
    apiKey: String?,
    secretKey: String?,
    latitude: Double,
    longitude: Double,
): PickedAddress? = when (provider) {
    MapProvider.BAIDU -> baiduReverseGeocode(context, apiKey, latitude, longitude)
    MapProvider.TENCENT -> tencentReverseGeocode(context, apiKey, secretKey, latitude, longitude)
    MapProvider.AMAP -> amapReverseGeocode(context, apiKey, latitude, longitude)
}

internal suspend fun mapSuggestPlaces(
    context: Context,
    provider: MapProvider,
    apiKey: String?,
    secretKey: String?,
    query: String,
    nearLatitude: Double,
    nearLongitude: Double,
): List<MapPlaceHit>? = when (provider) {
    MapProvider.BAIDU -> baiduSuggestPlaces(context, apiKey, query, nearLatitude, nearLongitude)
    MapProvider.TENCENT ->
        tencentSuggestPlaces(context, apiKey, secretKey, query, nearLatitude, nearLongitude)
    MapProvider.AMAP -> amapSuggestPlaces(context, apiKey, query, nearLatitude, nearLongitude)
}

internal suspend fun mapProbe(
    context: Context,
    provider: MapProvider,
    apiKey: String?,
    secretKey: String?,
    latitude: Double,
    longitude: Double,
): MapProbe = when (provider) {
    MapProvider.BAIDU -> baiduProbe(context, apiKey, latitude, longitude)
    MapProvider.TENCENT -> tencentProbe(context, apiKey, secretKey, latitude, longitude)
    MapProvider.AMAP -> amapProbe(context, apiKey, latitude, longitude)
}
