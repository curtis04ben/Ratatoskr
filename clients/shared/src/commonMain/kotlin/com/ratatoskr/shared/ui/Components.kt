package com.ratatoskr.shared.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A dark, hairline-bordered text field matching .lock-form / .modal-card
 * inputs in style.css: panel-alt background, subtle border, no heavy
 * Material outline styling. */
@Composable
fun RatatoskrTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier,
        singleLine = singleLine,
        enabled = enabled,
        visualTransformation = if (isPassword) {
            androidx.compose.ui.text.input.PasswordVisualTransformation()
        } else {
            androidx.compose.ui.text.input.VisualTransformation.None
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = RatatoskrColors.PanelAlt,
            unfocusedContainerColor = RatatoskrColors.PanelAlt,
            disabledContainerColor = RatatoskrColors.PanelAlt,
            focusedBorderColor = RatatoskrColors.Verdigris,
            unfocusedBorderColor = RatatoskrColors.Hairline,
            focusedLabelColor = RatatoskrColors.TextMuted,
            unfocusedLabelColor = RatatoskrColors.TextMuted,
            focusedTextColor = RatatoskrColors.Text,
            unfocusedTextColor = RatatoskrColors.Text,
        ),
    )
}

/** The brass "primary action" button, .btn-primary in style.css. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = RatatoskrColors.Brass,
            contentColor = RatatoskrColors.Bg,
            disabledContainerColor = RatatoskrColors.Brass.copy(alpha = 0.4f),
        ),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(text, fontWeight = FontWeight.SemiBold)
    }
}

/** A quiet, low-emphasis outline button, .btn-ghost. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        border = BorderStroke(1.dp, RatatoskrColors.Hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = RatatoskrColors.Text),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(text)
    }
}

/** A ghost button with danger-colored text, .btn-caution, for actions
 * that aren't destructive but deserve a second look (e.g. Export). */
@Composable
fun CautionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        border = BorderStroke(1.dp, RatatoskrColors.Hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = RatatoskrColors.DangerBright),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(text)
    }
}

/** The red outline "destructive action" button, .btn-danger. */
@Composable
fun DangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        border = BorderStroke(1.dp, RatatoskrColors.Danger),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = RatatoskrColors.DangerBright),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(text)
    }
}

/** An underlined text-only link button, .link-btn. */
@Composable
fun LinkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text, color = RatatoskrColors.TextMuted, style = MaterialTheme.typography.bodySmall)
    }
}

/** Inline error text, .error in style.css: reserves space so the layout
 * doesn't jump when an error appears/disappears. */
@Composable
fun ErrorText(message: String?, modifier: Modifier = Modifier) {
    Text(
        text = message ?: "",
        color = RatatoskrColors.DangerBright,
        style = MaterialTheme.typography.bodySmall,
        modifier = modifier.padding(top = 6.dp),
        textAlign = TextAlign.Start,
    )
}
