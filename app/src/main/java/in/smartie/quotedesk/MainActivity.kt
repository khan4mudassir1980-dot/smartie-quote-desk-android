package `in`.smartie.quotedesk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.smartie.quotedesk.ui.SessionViewModel
import `in`.smartie.quotedesk.ui.SmartieApp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import `in`.smartie.quotedesk.ui.theme.SmartieTheme
import `in`.smartie.quotedesk.ui.theme.TextSizePreference

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
        )
        val container = (application as SmartieApplication).container
        setContent {
            val textSize by container.devicePreferences.textSize
                .collectAsStateWithLifecycle(initialValue = TextSizePreference.NORMAL)
            SmartieTheme(textSize = textSize) {
                val sessionViewModel: SessionViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        SessionViewModel(container.authRepository) as T
                })
                SmartieApp(container = container, sessionViewModel = sessionViewModel)
            }
        }
    }
}
