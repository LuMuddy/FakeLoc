package io.github.cobylinweiqi.fakeloc.mapsdk

import android.content.Context
import android.util.Log
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer
import com.tencent.tencentmap.mapsdk.maps.TencentMapInitializer
import io.github.cobylinweiqi.fakeloc.core.MapProvider

/**
 * Brings up the map SDK the picker is about to draw with.
 *
 * Deliberately *not* done in `Application.onCreate`: each SDK reads its key once
 * at initialisation, and the key does not exist until the config has been loaded
 * from LSPosed's remote preferences — a service bind that can land after the
 * first frame. Initialising on launch would bake in the wrong key on every run
 * where the user supplied their own.
 *
 * Each provider is initialised lazily and independently: [ensure] can bring up
 * Baidu and later Amap in the same process, and one failing does not stop the
 * others. The only per-provider state that matters is the key it was initialised
 * with, because Baidu's and Amap's `initialize` calls read the key once. A key
 * change for an already-started provider is therefore reported as
 * [Status.RestartNeeded] rather than silently ignored.
 */
object MapSdkBootstrap {

    private const val TAG = "FakeLoc/MapSdk"

    /** The key each provider was first brought up with, or absent if not yet.
     *  A plain `HashMap` (guarded by [ensure]) rather than a concurrent map: a
     *  provider can be initialised with *no* key (Tencent takes its key per map
     *  view), and `ConcurrentHashMap` would throw on the null value. */
    private val startedKeys = HashMap<MapProvider, String?>()

    /** Providers whose privacy consent has been recorded this process. */
    private val consented = HashSet<MapProvider>()

    /**
     * Starts [provider] with [key] if it is not already running, and reports what
     * the picker should do next. Idempotent and cheap on repeat calls: the SDKs
     * are only touched the first time a provider is seen (or when the caller is
     * the connectivity probe, which needs consent before it can send anything).
     *
     * [context] is normalised to the application context because Baidu rejects a
     * non-application context in `setAgreePrivacy` with a message that later
     * surfaces as a dead `MapView` inside Compose's change application.
     */
    @Synchronized
    fun ensure(context: Context, provider: MapProvider, key: String?): Status {
        val appContext = context.applicationContext ?: context

        val started = startedKeys.containsKey(provider)
        if (started) {
            // Tencent passes its key per map view, so a changed key is handled by
            // rebuilding the view, not the process; Baidu and Amap read the key
            // once at `initialize`, so a change needs a restart.
            return if (provider == MapProvider.TENCENT || startedKeys[provider] == key) {
                Status.Ready
            } else {
                Status.RestartNeeded
            }
        }

        val failure = runCatching {
            when (provider) {
                MapProvider.TENCENT -> startTencent()
                MapProvider.BAIDU -> startBaidu(appContext, key)
                MapProvider.AMAP -> startAmap(appContext, key)
            }
        }.exceptionOrNull()

        if (failure == null) {
            startedKeys[provider] = key
            Log.i(TAG, "${provider.id} map SDK ready (key=${if (key.isNullOrBlank()) "none" else "user"})")
            return Status.Ready
        }

        Log.w(TAG, "${provider.id} map SDK failed to start", failure)
        return Status.Failed(failure.message ?: failure.javaClass.simpleName)
    }

    /**
     * Tencent has no global entry point: 5.9.0 exposes `setAgreePrivacy(boolean)`
     * and nothing else. Everything else comes up lazily when the first `MapView`
     * is constructed, which is why the key is passed there rather than here.
     */
    private fun startTencent() {
        if (consented.add(MapProvider.TENCENT)) {
            TencentMapInitializer.setAgreePrivacy(true)
        }
    }

    /**
     * Baidu: privacy consent and the key must both be in place before
     * `initialize`. The coordinate type is pinned to BD-09 (its own datum) so the
     * canvas and the search SDK agree with what [io.github.cobylinweiqi.fakeloc.core.Coords]
     * produces.
     */
    private fun startBaidu(context: Context, key: String?) {
        if (consented.add(MapProvider.BAIDU)) {
            SDKInitializer.setAgreePrivacy(context, true)
            SDKInitializer.setCoordType(CoordType.BD09LL)
        }
        if (!key.isNullOrBlank()) SDKInitializer.setApiKey(key)
        if (!SDKInitializer.isInitialized()) SDKInitializer.initialize(context)
    }

    /**
     * Amap: the map engine and the search client each keep their own privacy
     * flags and their own key slot, so both are set here in one place. The map
     * key and the service (web) key are the same AK in Amap's console.
     */
    private fun startAmap(context: Context, key: String?) {
        if (consented.add(MapProvider.AMAP)) {
            MapsInitializer.updatePrivacyShow(context, true, true)
            MapsInitializer.updatePrivacyAgree(context, true)
            ServiceSettings.updatePrivacyShow(context, true, true)
            ServiceSettings.updatePrivacyAgree(context, true)
        }
        if (!key.isNullOrBlank()) {
            MapsInitializer.setApiKey(key)
            ServiceSettings.getInstance().setApiKey(key)
        }
        MapsInitializer.initialize(context)
    }

    /** What the picker should tell the user, if anything. */
    sealed interface Status {
        /** The selected provider is up. */
        data object Ready : Status

        /**
         * Up, but with a different key than the config now holds. The map will
         * keep using the old one until the process restarts.
         */
        data object RestartNeeded : Status

        /** Could not be started; [detail] is the SDK's own complaint. */
        data class Failed(val detail: String) : Status
    }
}
