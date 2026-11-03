package com.jagdeep.ui.components

import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Initiator side of pairing from the Nearby list. Comparing the code is the security step, so
 * "They don't match" is exactly as prominent as "Codes match", and dismissing never confirms.
 */
@Composable
fun NearbyPairingDialog(
    deviceName: String,
    /** Null while connecting. */
    code: String?,
    /** True once this user confirmed and the other device's user still has to. */
    waitingForOther: Boolean,
    onCodeMatches: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    val comparing = code != null && !waitingForOther
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Default.Link, contentDescription = null) },
        title = { Text("Pair with $deviceName?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when {
                    code == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Waiting for $deviceName to respond…")
                    }
                    comparing -> {
                        Text("Check that $deviceName shows the same code. Only confirm if they match.")
                        PairingCode(code)
                    }
                    else -> {
                        PairingCode(code)
                        Text("Now confirm on $deviceName.")
                    }
                }
            }
        },
        confirmButton = {
            if (comparing) TextButton(onClick = { onCodeMatches(true) }) { Text("Codes match") }
        },
        dismissButton = {
            if (comparing) {
                TextButton(onClick = { onCodeMatches(false) }) { Text("They don't match") }
            } else {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun PairingCode(code: String) {
    val grouped = code.chunked(3).joinToString(" ")
    Text(
        grouped,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Pairing code ${code.toList().joinToString(" ")}" },
        style = MaterialTheme.typography.displaySmall,
        fontFamily = FontFamily.Monospace,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.primary,
    )
}
