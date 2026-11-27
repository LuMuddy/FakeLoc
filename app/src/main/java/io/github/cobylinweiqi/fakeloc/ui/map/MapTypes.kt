package io.github.cobylinweiqi.fakeloc.ui.map

/**
 * One search-suggestion row, already converted to **WGS-84**.
 *
 * Shared by all three providers so the picker does not care which SDK produced
 * it. Flattened for the same reason the platform `Address` is flattened: the
 * vendor rows carry ids, categories and distances that nothing renders, and
 * holding them would keep the response object alive through Compose state.
 */
internal data class MapPlaceHit(
    val title: String,
    val region: String,
    val latitude: Double,
    val longitude: Double,
)

/**
 * How a connectivity probe ended.
 *
 * [Rejected] carries the provider's own numeric status so the settings page can
 * name the gate that refused — the four Tencent codes in particular each map to a
 * different fix, and collapsing them into "failed" is what made a valid-but-
 * unsigned key read exactly like a missing one.
 */
internal sealed interface MapProbe {
    /** The key answered and an address came back: everything works. */
    data object Ok : MapProbe

    /** No key configured, so nothing was ever sent. Not a failure — a blank. */
    data object NoKey : MapProbe

    /** No answer at all: unreachable host, no network, or the timeout fired. */
    data object Unreachable : MapProbe

    /** The provider's server answered and refused; [status] is its reason. */
    data class Rejected(val status: Int) : MapProbe
}
