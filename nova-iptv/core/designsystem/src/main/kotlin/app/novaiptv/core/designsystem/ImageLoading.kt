package app.novaiptv.core.designsystem

import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

/**
 * Installs the process-wide Coil image loader. Call once from Application.onCreate.
 * [callFactory] lets the app share its OkHttpClient (connection pool, user agent, timeouts)
 * with image loading; it is invoked lazily on first network fetch.
 */
fun installNovaImageLoader(callFactory: () -> okhttp3.Call.Factory) {
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = callFactory)) }
            .crossfade(true)
            .build()
    }
}
