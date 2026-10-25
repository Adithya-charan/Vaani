package com.vaani.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.vaani.app.R
import com.vaani.app.ui.theme.StatusColors
import com.vaani.app.ui.theme.VaaniTheme
import com.vaani.app.viewmodel.SettingsUiState
import com.vaani.app.viewmodel.TestOutcome

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onAddressChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onForget: () -> Unit,
) {
    var showToken by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Laptop connection") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Run the Vaani agent on your laptop, then enter the address it is listening on and the " +
                    "VAANI_AUTH_TOKEN from its .env file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.address,
                onValueChange = onAddressChange,
                label = { Text("Laptop address") },
                placeholder = { Text("192.168.0.7:8765") },
                singleLine = true,
                isError = state.addressError != null,
                supportingText = { Text(state.addressError ?: "IP address and port. Port 8765 is used if you leave it out.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.token,
                onValueChange = onTokenChange,
                label = { Text("Access token") },
                singleLine = true,
                isError = state.tokenError != null,
                supportingText = { Text(state.tokenError ?: "Stored encrypted on this phone.") },
                visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onTest, enabled = !state.testing) { Text("Test connection") }
                Button(onClick = onSave, enabled = !state.testing) { Text("Save") }
                if (state.testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            state.testOutcome?.let { TestResultCard(it) }
            if (state.hasSavedConfig) {
                Spacer(Modifier.size(8.dp))
                TextButton(onClick = onForget) {
                    Text("Forget this laptop", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun TestResultCard(outcome: TestOutcome) {
    val color = if (outcome.ok) StatusColors.connected else MaterialTheme.colorScheme.error
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(outcome.message, color = color, modifier = Modifier.padding(14.dp))
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsPreview() {
    VaaniTheme(dynamicColor = false) {
        SettingsScreen(
            SettingsUiState(address = "192.168.0.7:8765", token = "x".repeat(43),
                testOutcome = TestOutcome(true, "Connected to LAPTOP."), hasSavedConfig = true),
            {}, {}, {}, {}, {}, {},
        )
    }
}
