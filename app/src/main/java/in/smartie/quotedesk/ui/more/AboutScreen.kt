package `in`.smartie.quotedesk.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.smartie.quotedesk.BuildConfig
import `in`.smartie.quotedesk.ui.components.SmartieCard
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

@Composable
fun AboutScreen() {
    val dimens = LocalSmartieDimens.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = dimens.screenPadding,
            end = dimens.screenPadding,
            top = dimens.gapM,
            bottom = dimens.listBottomInset,
        ),
        verticalArrangement = Arrangement.spacedBy(dimens.gapS),
    ) {
        item {
            Section("SMARTIE Quote Desk") {
                Line("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Line("Package", BuildConfig.APPLICATION_ID)
                Line("Environment", BuildConfig.FIREBASE_ENV)
                if (BuildConfig.IS_STAGING) {
                    Line(
                        "Note",
                        "This is a staging build. It must only ever be pointed at staging data.",
                    )
                }
            }
        }
        item {
            Section("Licences") {
                Line("Inter", "Interface typeface. SIL Open Font License 1.1")
                Line("Jetpack Compose, Firebase", "Apache License 2.0")
            }
        }
        item {
            Section("Internal use") {
                Line(
                    "Smart India Enterprises",
                    "Internal quoting and stock tool. Prices and customer records are " +
                        "confidential business data.",
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    SmartieCard {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = SmartieColors.Ink,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Column(Modifier.padding(bottom = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = SmartieColors.Steel)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = SmartieColors.Ink2)
    }
}
