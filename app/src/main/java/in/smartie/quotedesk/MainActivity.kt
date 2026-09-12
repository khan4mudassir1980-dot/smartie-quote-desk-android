package `in`.smartie.quotedesk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import `in`.smartie.quotedesk.ui.SessionViewModel
import `in`.smartie.quotedesk.ui.SmartieApp
import `in`.smartie.quotedesk.ui.theme.SmartieTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SmartieApplication).container
        setContent {
            SmartieTheme {
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
