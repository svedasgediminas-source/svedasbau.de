package app.novaiptv

import android.app.Application
import app.novaiptv.core.data.DataGraph
import app.novaiptv.core.designsystem.installNovaImageLoader
import app.novaiptv.di.AppContainer
import app.novaiptv.di.AppContainerImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class NovaApp : Application() {
    lateinit var graph: DataGraph
        private set
    lateinit var container: AppContainer
        private set

    /** Process-lifetime scope for work that must survive activity recreation (e.g. language change). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val startupRefreshDone = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        graph = DataGraph(this)
        container = AppContainerImpl(graph)
        // Posters and logos share the app's OkHttpClient (connection pool, timeouts, user agent).
        installNovaImageLoader { graph.okHttpClient }
    }

    /** Refreshes stale playlists once per process if the user enabled auto refresh. */
    fun refreshOnStartIfEnabled() {
        if (!startupRefreshDone.compareAndSet(false, true)) return
        appScope.launch {
            try {
                if (container.preferences.preferences.first().autoRefreshOnStart) {
                    graph.refreshStalePlaylists()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Errors are reported per playlist through ImportProgress / Playlist.lastError.
            }
        }
    }
}
