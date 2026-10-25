package com.vaani.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vaani.app.data.ConfigurableAgentRepository
import com.vaani.app.data.KeystoreSettingsStore
import com.vaani.app.data.httpRepositoryFor
import com.vaani.app.ui.HomeScreen
import com.vaani.app.ui.SettingsScreen
import com.vaani.app.ui.theme.VaaniTheme
import com.vaani.app.viewmodel.HomeViewModel
import com.vaani.app.viewmodel.SettingsViewModel

class MainActivity : ComponentActivity() {

    private val settingsStore by lazy { KeystoreSettingsStore(applicationContext) }
    private val repository by lazy { ConfigurableAgentRepository(settingsStore) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VaaniTheme {
                val home: HomeViewModel = viewModel(factory = HomeViewModel.factory(repository))
                var showSettings by rememberSaveable { mutableStateOf(settingsStore.load() == null) }

                if (showSettings) {
                    val settings: SettingsViewModel =
                        viewModel(factory = SettingsViewModel.factory(settingsStore, ::httpRepositoryFor))
                    val state by settings.state.collectAsStateWithLifecycle()
                    val close = {
                        showSettings = false
                        home.refreshConnection()
                    }
                    BackHandler(onBack = close)
                    LaunchedEffect(state.saved) {
                        if (state.saved) {
                            settings.onSavedHandled()
                            close()
                        }
                    }
                    SettingsScreen(
                        state = state,
                        onBack = close,
                        onAddressChange = settings::onAddressChange,
                        onTokenChange = settings::onTokenChange,
                        onTest = settings::testConnection,
                        onSave = settings::save,
                        onForget = settings::forget,
                    )
                } else {
                    val state by home.state.collectAsStateWithLifecycle()
                    HomeScreen(
                        state = state,
                        onInputChange = home::onInputChange,
                        onSend = home::onSend,
                        onCancel = home::onCancel,
                        onQuickCommand = home::onQuickCommand,
                        onMicClick = home::onMicClick,
                        onRefreshConnection = home::refreshConnection,
                        onNoticeShown = home::onNoticeShown,
                        onOpenSettings = { showSettings = true },
                    )
                }
            }
        }
    }
}
