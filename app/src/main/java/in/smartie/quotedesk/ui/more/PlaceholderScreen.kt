package `in`.smartie.quotedesk.ui.more

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.smartie.quotedesk.ui.components.Tag
import `in`.smartie.quotedesk.ui.components.TagTone
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/** A destination whose screen arrives in a later parity phase. */
@Composable
fun PlaceholderScreen(label: String, phase: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Tag("In development · phase $phase", TagTone.WARN)
        Text(
            label,
            style = MaterialTheme.typography.titleLarge,
            color = SmartieColors.Ink,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "This screen is rebuilt in phase $phase of the parity plan. Until then, keep using " +
                "the PWA for it.",
            style = MaterialTheme.typography.bodyMedium,
            color = SmartieColors.Steel,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}
