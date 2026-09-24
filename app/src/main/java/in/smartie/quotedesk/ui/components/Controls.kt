package `in`.smartie.quotedesk.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import `in`.smartie.quotedesk.ui.theme.LocalSmartieDimens
import `in`.smartie.quotedesk.ui.theme.SmartieColors

/** Primary button at the PWA's 42-44dp height, not Material's 40dp default. */
@Composable
fun SmartiePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false
) {
    val dimens = LocalSmartieDimens.current
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(dimens.radius),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 17.dp),
        modifier = modifier.heightIn(min = dimens.buttonHeightCompact)
    ) {
        if (busy) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp)
            )
        } else {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Ghost button (`.btn.gh`): white panel, rule border, same height. */
@Composable
fun SmartieGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    /**
     * Narrower padding and a smaller face, for a row of several.
     *
     * The height is untouched — `buttonHeightCompact` either way — so what a
     * thumb has to hit does not change. Only the space around the words does,
     * which is what lets four card controls share one row at 360dp instead of
     * three.
     */
    compact: Boolean = false
) {
    val dimens = LocalSmartieDimens.current
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(dimens.radius),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = if (danger) SmartieColors.Danger else SmartieColors.Ink2
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = if (compact) 9.dp else 14.dp
        ),
        modifier = modifier.heightIn(min = dimens.buttonHeightCompact)
    ) {
        Text(
            text,
            style = if (compact) {
                MaterialTheme.typography.labelMedium
            } else {
                MaterialTheme.typography.labelLarge
            },
            maxLines = 1
        )
    }
}

/**
 * Label above the field, as the PWA's `label.f > span` — Material's floating
 * label makes every input 56dp tall and pushes the compact layouts apart.
 */
@Composable
fun SmartieField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    /**
     * What the keyboard's action key does. Defaulted, so the many call sites
     * that want the platform default say nothing.
     */
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    val dimens = LocalSmartieDimens.current
    Column(modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = SmartieColors.Steel,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            isError = isError,
            placeholder = placeholder?.let {
                {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyLarge,
                        color = SmartieColors.Steel2
                    )
                }
            },
            trailingIcon = trailingIcon,
            textStyle = MaterialTheme.typography.bodyLarge,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            shape = RoundedCornerShape(dimens.radius),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = SmartieColors.Panel,
                unfocusedContainerColor = SmartieColors.Panel,
                disabledContainerColor = SmartieColors.Panel2,
                errorContainerColor = SmartieColors.Panel,
                focusedIndicatorColor = SmartieColors.Purple,
                unfocusedIndicatorColor = SmartieColors.Rule,
                disabledIndicatorColor = SmartieColors.Rule2
            ),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = dimens.inputHeight)
        )
        if (supportingText != null) {
            Text(
                supportingText,
                style = MaterialTheme.typography.labelMedium,
                color = if (isError) SmartieColors.Danger else SmartieColors.Steel,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Quick `+` / `-` stepper. It never opens a dialog or a number keyboard: the
 * buttons change a pending value by one and the caller saves with Done.
 */
@Composable
fun CompactStepper(
    value: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    modifier: Modifier = Modifier,
    decrementEnabled: Boolean = true,
    incrementEnabled: Boolean = true,
    highlighted: Boolean = false,
    /**
     * What a screen reader — and a test — calls these two buttons.
     *
     * Null by default, which leaves the bare symbols. On a card carrying one
     * stepper that is enough, and every existing caller finds it by its "+".
     * A **list with a stepper on every row** is the case that needs these:
     * there "+" names four controls at once, and "the third +" is not an
     * identity anybody should be asserting against.
     */
    incrementLabel: String? = null,
    decrementLabel: String? = null
) {
    val dimens = LocalSmartieDimens.current
    Row(
        modifier
            .height(dimens.stepperHeight)
            .clip(RoundedCornerShape(dimens.radius))
            .background(SmartieColors.Panel)
            .border(
                dimens.hairline,
                if (highlighted) SmartieColors.PurpleLine else SmartieColors.Rule,
                RoundedCornerShape(dimens.radius)
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepperButton("−", dimens.stepperButtonWidth, decrementEnabled, onDecrement, decrementLabel)
        Box(
            Modifier
                .width(54.dp)
                .height(dimens.stepperHeight)
                .background(if (highlighted) SmartieColors.PurpleTint else SmartieColors.Panel),
            contentAlignment = Alignment.Center
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                color = SmartieColors.Ink,
                maxLines = 1
            )
        }
        StepperButton("+", dimens.stepperButtonWidth, incrementEnabled, onIncrement, incrementLabel)
    }
}

@Composable
private fun StepperButton(
    symbol: String,
    width: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    label: String? = null
) {
    val dimens = LocalSmartieDimens.current
    Box(
        Modifier
            .width(width)
            .height(dimens.stepperHeight)
            .background(SmartieColors.Panel2)
            .then(
                if (enabled) Modifier.clickableNoRipple(onClick) else Modifier
            )
            .then(
                if (label != null) Modifier.semantics { contentDescription = label } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            symbol,
            style = MaterialTheme.typography.titleLarge,
            color = if (enabled) SmartieColors.Ink else SmartieColors.Steel2
        )
    }
}

/**
 * Single-line segmented selector. Labels are weighted and clipped to one line
 * so "Normal" can never wrap vertically at 360dp (audit U8).
 */
@Composable
fun <T> SegmentedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    accent: (T) -> Color = { SmartieColors.Purple }
) {
    val dimens = LocalSmartieDimens.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(dimens.radius))
            .border(dimens.hairline, SmartieColors.Rule, RoundedCornerShape(dimens.radius)),
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val tint = accent(option)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = dimens.buttonHeightCompact)
                    .background(if (isSelected) tint.copy(alpha = 0.12f) else SmartieColors.Panel)
                    .clickableNoRipple { onSelect(option) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) tint else SmartieColors.Ink2,
                    maxLines = 1
                )
            }
        }
    }
}
