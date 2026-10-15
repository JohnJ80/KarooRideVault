package com.ridevault.karoo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridevault.karoo.strava.StravaApi
import com.ridevault.karoo.strava.StravaTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Strava auth screen using the relay flow:
 *
 * 1. Shows a QR code that links to the auth relay
 * 2. User scans with phone → logs into Strava → gets a 6-digit PIN
 * 3. User types the PIN here → app fetches the token from the relay
 *
 * Auto-submits when the 6th digit is entered, or on keyboard Done.
 */
@Composable
fun StravaAuthScreen(
    relayUrl: String,
    api: StravaApi,
    tokenStore: StravaTokenStore,
    onConnected: () -> Unit,
    onCancel: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Extract submit logic so it can be called from button, keyboard, or auto-submit
    fun submitPin() {
        if (pin.length != 6 || isLoading) return
        isLoading = true
        status = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                api.fetchTokenFromRelay(relayUrl, pin)
            }
            result.onSuccess { tokenResponse ->
                tokenStore.saveToken(
                    StravaTokenStore.TokenData(
                        accessToken = tokenResponse.accessToken,
                        refreshToken = tokenResponse.refreshToken,
                        expiresAt = tokenResponse.expiresAt,
                        athleteId = tokenResponse.athlete?.id ?: 0,
                        athleteFirstName = tokenResponse.athlete?.firstName ?: "",
                    ),
                )
                Timber.i("Strava auth via relay complete")
                onConnected()
            }
            result.onFailure { e ->
                status = when {
                    e.message?.contains("404") == true -> "Invalid or expired PIN. Try again."
                    else -> "Error: ${e.message}"
                }
                Timber.e(e, "Relay token fetch failed")
            }
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Connect to Strava",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // QR Code linking to the relay auth endpoint
        QrCodeImage(
            content = "$relayUrl/auth",
            size = 160.dp,
        )

        Text(
            text = "Scan with your phone to log in",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Then enter the PIN shown on your phone:",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )

        // PIN input — auto-submits on 6th digit or keyboard Done
        OutlinedTextField(
            value = pin,
            onValueChange = { newValue ->
                if (newValue.length <= 6 && newValue.all { it.isDigit() }) {
                    pin = newValue
                    // Auto-submit when 6th digit entered
                    if (newValue.length == 6) {
                        submitPin()
                    }
                }
            },
            label = { Text("6-digit PIN") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = { submitPin() },
            ),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(0.6f),
            textStyle = MaterialTheme.typography.titleLarge.copy(
                textAlign = TextAlign.Center,
                letterSpacing = androidx.compose.ui.unit.TextUnit(4f, androidx.compose.ui.unit.TextUnitType.Sp),
            ),
        )

        if (status != null) {
            Text(
                text = status!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onCancel) {
                Text("Cancel")
            }
            Button(
                onClick = { submitPin() },
                enabled = pin.length == 6 && !isLoading,
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Connect")
                }
            }
        }
    }
}
