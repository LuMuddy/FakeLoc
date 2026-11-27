package io.github.cobylinweiqi.fakeloc.core

/**
 * Which base map the picker draws with.
 *
 * Three renderers, one per mainstream mainland platform: Baidu, Tencent and Amap.
 * Each speaks a different datum, and that is carried here rather than guessed at
 * the call site — Baidu reports BD-09, Tencent and Amap report GCJ-02, and the
 * config itself always stores WGS-84. [datum] is the single place the picker
 * turns to when it has to convert the stored anchor into whatever the selected
 * SDK expects.
 *
 * The id (not the enum ordinal) is what is persisted, so an unknown or removed
 * id falls back to [DEFAULT] instead of throwing, and a config written by a
 * build that only knew Tencent still loads.
 *
 * **No key is baked into this build.** Every platform's key is entered on the
 * settings page and handed to its SDK at runtime — Baidu through
 * `SDKInitializer.setApiKey`, Amap through `MapsInitializer.setApiKey` and
 * `ServiceSettings.setApiKey`, Tencent per map view through
 * `TencentMapOptions.setMapKey`.
 */
enum class MapProvider(val id: String, val datum: Datum) {
    BAIDU("baidu", Datum.BD09),
    TENCENT("tencent", Datum.GCJ02),
    AMAP("amap", Datum.GCJ02),
    ;

    companion object {
        /** Baidu is the default: the anchor is most often taken from a Baidu
         *  search result, and BD-09 keeps the pin exactly on the tapped point. */
        val DEFAULT = BAIDU

        /** Unknown or missing ids fall back to [DEFAULT] rather than throwing. */
        fun from(id: String?): MapProvider =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
