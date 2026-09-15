package `in`.smartie.quotedesk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/**
 * Marks a screen that is read-only until its parity phase rebuilds it. The
 * beta editing paths are removed rather than left in place, because they
 * wrote data the PWA could not read (audit D1-D3).
 */
@Composable
fun InDevelopmentBanner(phase: String, detail: String, modifier: Modifier = Modifier) {
    val dimens = LocalSmartieDimens.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimens.radius))
            .background(SmartieColors.PurpleLight)
            .border(dimens.hairline, SmartieColors.PurpleLine, RoundedCornerShape(dimens.radius))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            "View only · rebuilt in phase $phase",
            style = MaterialTheme.typography.titleSmall,
            color = SmartieColors.PurpleDark
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = SmartieColors.Ink2,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
