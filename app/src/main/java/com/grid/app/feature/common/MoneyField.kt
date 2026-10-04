package com.grid.app.feature.common

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.grid.app.core.designsystem.theme.GridText
import com.grid.app.core.designsystem.theme.GridTheme
import com.grid.app.core.money.Currencies

/** Parses user-typed money ("1 234,5", "12.50") into minor units; null when empty or invalid. */
fun parseMoney(text: String, currency: String): Long? {
    val cleaned = text.trim().replace(" ", "").replace(" ", "").replace(" ", "")
    if (cleaned.isEmpty()) return null
    // Treat the last '.' or ',' as the decimal separator; drop other separators (grouping).
    val lastSep = cleaned.indexOfLast { it == '.' || it == ',' }
    val normalized = if (lastSep < 0) cleaned else {
        val digitsAfter = cleaned.length - lastSep - 1
        val intPart = cleaned.substring(0, lastSep).replace(".", "").replace(",", "")
        // "1,234" / "1.234" with exactly three trailing digits and a 0-2 digit currency reads as grouping.
        if (digitsAfter == 3 && Currencies.fractionDigits(currency) < 3) intPart + cleaned.substring(lastSep + 1)
        else intPart + "." + cleaned.substring(lastSep + 1)
    }
    return runCatching { Currencies.toMinor(normalized, currency) }.getOrNull()?.takeIf { it >= 0 }
}

/** Plain decimal text for prefilling a [MoneyField]. */
fun moneyFieldText(minor: Long?, currency: String): String =
    if (minor == null || minor == 0L) "" else Currencies.toDecimal(minor, currency).stripTrailingZeros().toPlainString()

@Composable
fun MoneyField(
    value: String,
    onValueChange: (String) -> Unit,
    currency: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    isError: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
) {
    val colors = GridTheme.colors
    OutlinedTextField(
        value = value,
        onValueChange = { new -> onValueChange(new.filter { it.isDigit() || it == '.' || it == ',' || it == ' ' }) },
        modifier = modifier,
        label = label?.let { { Text(it) } },
        prefix = { Text(Currencies.symbol(currency) + " ", style = GridText.moneySmall, color = colors.muted) },
        textStyle = GridText.moneySmall.copy(color = colors.text),
        singleLine = true,
        isError = isError,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedBorderColor = colors.hairline,
            focusedBorderColor = colors.text.copy(alpha = 0.6f),
            unfocusedContainerColor = colors.tile,
            focusedContainerColor = colors.tile,
        ),
    )
}
