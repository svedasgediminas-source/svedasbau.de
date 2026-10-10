package app.novaiptv

import android.content.Context
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
        // Keep the UI language in sync with the stored preference ("applied immediately").
        lifecycleScope.launch {
            app.container.preferences.preferences
                .map { it.language.tag }
                .distinctUntilChanged()
                .collect { tag -> if (AppLocale.apply(this@MainActivity, tag)) recreate() }
        }
    }
}
