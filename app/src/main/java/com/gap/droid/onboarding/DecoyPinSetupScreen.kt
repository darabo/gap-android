package com.gapmesh.droid.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gapmesh.droid.R
import com.gapmesh.droid.service.DecoyModeManager

/**
 * Onboarding step for setting up the Decoy PIN.
 * Users can accept a random PIN or create their own 4–8 digit PIN.
 * They must check the "I've memorized my PIN" checkbox to proceed.
 */
@Composable
fun DecoyPinSetupScreen(
    onPinSet: (String) -> Unit
) {
    var currentPin by remember { mutableStateOf(DecoyModeManager.generateRandomPIN()) }
    var isCustomMode by remember { mutableStateOf(false) }
    var customPin by remember { mutableStateOf("") }
    var hasMemorized by remember { mutableStateOf(false) }
    var showPin by remember { mutableStateOf(true) }
    var pinError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title
        Icon(
            imageVector = Icons.Filled.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.decoy_onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.decoy_onboarding_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        // How it works
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.decoy_onboarding_how_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                HowItWorksItem("👆", stringResource(R.string.decoy_onboarding_how_wipe))
                HowItWorksItem("🔢", stringResource(R.string.decoy_onboarding_how_calc))
                HowItWorksItem("🔑", stringResource(R.string.decoy_onboarding_how_pin))
                HowItWorksItem("💾", stringResource(R.string.decoy_onboarding_how_persist))
            }
        }

        Spacer(Modifier.height(24.dp))

        // PIN Section
        Text(
            stringResource(R.string.decoy_onboarding_pin_label),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))

        if (!isCustomMode) {
            // Show generated PIN
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (showPin) currentPin else "••••",
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 8.sp
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { showPin = !showPin }) {
                        Icon(
                            if (showPin) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (showPin) "Hide" else "Show")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        currentPin = DecoyModeManager.generateRandomPIN()
                        hasMemorized = false
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.decoy_onboarding_generate_new))
                }
                OutlinedButton(
                    onClick = {
                        isCustomMode = true
                        customPin = ""
                        hasMemorized = false
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.decoy_onboarding_choose_own))
                }
            }
        } else {
            // Custom PIN entry
            OutlinedTextField(
                value = customPin,
                onValueChange = { value ->
                    // Only digits, max 8
                    val filtered = value.filter { it.isDigit() }.take(8)
                    customPin = filtered
                    pinError = when {
                        filtered.length < 4 -> "PIN must be at least 4 digits"
                        else -> null
                    }
                    hasMemorized = false
                },
                label = { Text(stringResource(R.string.decoy_onboarding_pin_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                isError = pinError != null,
                supportingText = pinError?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                isCustomMode = false
                hasMemorized = false
            }) {
                Text(stringResource(R.string.decoy_onboarding_generate_new))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Warning
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(R.string.decoy_onboarding_pin_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(12.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        // Memorized checkbox
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(
                checked = hasMemorized,
                onCheckedChange = { hasMemorized = it }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.decoy_onboarding_memorized),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(Modifier.height(16.dp))

        // Save button (only enabled when PIN is valid and memorized)
        val canProceed = hasMemorized && if (isCustomMode) {
            customPin.length in 4..8
        } else {
            true
        }

        Button(
            onClick = {
                val pin = if (isCustomMode) customPin else currentPin
                onPinSet(pin)
            },
            enabled = canProceed,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(stringResource(R.string.onboarding_next))
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Filled.Calculate,
                contentDescription = null
            )
        }
    }
}

@Composable
private fun HowItWorksItem(emoji: String, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(emoji, fontSize = 16.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
