package app.novaiptv

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.lifecycleScope
import app.novaiptv.core.designsystem.NovaTheme
import app.novaiptv.di.LocalAppContainer
import app.novaiptv.navigation.NovaNavHost
import app.novaiptv.ui.AppLocale
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as NovaApp
        setContent {
            NovaTheme {
                CompositionLocalProvider(LocalAppContainer provides app.container) {
                    NovaNavHost(onExit = { finish() })
                }
            }
        }
        app.refreshOnStartIfEnabled()
        importDemoPlaylistIfRequested(app)
        // Keep the UI language in sync with the stored preference ("applied immediately").
        lifecycleScope.launch {
            app.container.preferences.preferences
                .map { it.language.tag }
                .distinctUntilChanged()
                .collect { tag -> if (AppLocale.apply(this@MainActivity, tag)) recreate() }
        }
    }

    /**
     * Debug builds only: `adb shell am start ... --es nova_demo_m3u <url> [--es nova_demo_epg <url>]`
     * adds a playlist without typing on the remote. Used by the CI emulator run for screenshots.
     */
    private fun importDemoPlaylistIfRequested(app: NovaApp) {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val url = intent?.getStringExtra(EXTRA_DEMO_M3U)
        if (!debuggable || url.isNullOrBlank()) return
        val epg = intent?.getStringExtra(EXTRA_DEMO_EPG)
        val playlists = app.container.playlists
        app.container.imports.start { playlists.addM3uUrl(name = "Demo", url = url, epgUrl = epg) }
    }

    private companion object {
        const val EXTRA_DEMO_M3U = "nova_demo_m3u"
        const val EXTRA_DEMO_EPG = "nova_demo_epg"
    }
}
