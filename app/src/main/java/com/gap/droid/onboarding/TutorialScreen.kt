package com.gapmesh.droid.onboarding

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.gapmesh.droid.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gapmesh.droid.service.MeshServicePreferences
import com.gapmesh.droid.ui.ChatViewModel

@Composable
fun TutorialScreen(
    viewModel: ChatViewModel,
    onComplete: () -> Unit
) {
    var step by remember { mutableStateOf(0) }
    val context = LocalContext.current
    
    // Persist background preference
    fun saveBackgroundPref(enabled: Boolean) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("background_mode_enabled", enabled)
            .apply()
    }
    
    // Mark tutorial as seen when done
    fun finishTutorial() {
        context.getSharedPreferences("onboarding_prefs", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("tutorial_seen", true)
            .apply()
        onComplete()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center
            ) {
                when (step) {
                    0 -> IdentityStep(viewModel)
                    1 -> ConnectivityStep(
                        onBackgroundModeChange = { saveBackgroundPref(it) }
                    )
                    2 -> EducationStep()
                }
            }
            
            Spacer(Modifier.height(24.dp))
            
            Button(
                onClick = {
                    if (step < 2) step++ else finishTutorial()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(if (step < 2) stringResource(R.string.onboarding_next) else stringResource(R.string.onboarding_get_started))
                Spacer(Modifier.width(8.dp))
                Icon(if (step < 2) Icons.AutoMirrored.Filled.ArrowForward else Icons.Filled.Check, null)
            }
        }
    }
}

@Composable
private fun IdentityStep(viewModel: ChatViewModel) {
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    var isEditing by remember { mutableStateOf(false) }
    var editName by remember { mutableStateOf("") }
    
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.onboarding_welcome),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_identity_desc),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        
        Spacer(Modifier.height(32.dp))
        
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(stringResource(R.string.onboarding_username_label), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                
                if (isEditing) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { 
                                if (editName.isNotBlank()) viewModel.setNickname(editName)
                                isEditing = false 
                            }) {
                                Icon(Icons.Filled.Check, "Save")
                            }
                        }
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = nickname,
                            style = MaterialTheme.typography.titleLarge,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        IconButton(onClick = { 
                            editName = nickname
                            isEditing = true 
                        }) {
                            Icon(Icons.Filled.Edit, "Edit")
                        }
                    }
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
        ) {
            Row(Modifier.padding(12.dp)) {
                Text(
                    stringResource(R.string.onboarding_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun ConnectivityStep(onBackgroundModeChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    // Default to true or existing value
    var backgroundEnabled by remember {
        mutableStateOf(MeshServicePreferences.isBackgroundEnabled(true))
    }
    
    // Save to shared prefs when changed
    LaunchedEffect(backgroundEnabled) {
        MeshServicePreferences.setBackgroundEnabled(backgroundEnabled)
    }

    Column {
        Text(
            stringResource(R.string.onboarding_bg_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(24.dp))
        
        Text(stringResource(R.string.onboarding_bg_option), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (backgroundEnabled) stringResource(R.string.onboarding_enabled_rec) else stringResource(R.string.onboarding_disabled), fontWeight = FontWeight.Bold)
            }
            Switch(
                checked = backgroundEnabled,
                onCheckedChange = { 
                    backgroundEnabled = it
                    onBackgroundModeChange(it)
                }
            )
        }
        
        Spacer(Modifier.height(8.dp))
        Text(
            if (backgroundEnabled) 
                stringResource(R.string.onboarding_bg_enabled_desc)
            else 
                stringResource(R.string.onboarding_bg_disabled_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )
        
        HorizontalDivider(Modifier.padding(vertical = 24.dp))
        
        Text(stringResource(R.string.onboarding_mesh_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_mesh_desc),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun EducationStep() {
    Column {
        Text(
            stringResource(R.string.onboarding_features_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(24.dp))
        
        FeatureItem(
            title = stringResource(R.string.onboarding_feature_geo_title),
            desc = stringResource(R.string.onboarding_feature_geo_desc)
        )
        
        FeatureItem(
            title = stringResource(R.string.onboarding_feature_notes_title),
            desc = stringResource(R.string.onboarding_feature_notes_desc)
        )

        FeatureItem(
            title = stringResource(R.string.onboarding_emergency_title),
            desc = stringResource(R.string.onboarding_emergency_desc)
        )
        
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        
        Text(stringResource(R.string.onboarding_status_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        
        StatusItem(Color(0xFF00C851), stringResource(R.string.onboarding_status_green), stringResource(R.string.onboarding_status_green_desc))
        StatusItem(Color(0xFFFF9500), stringResource(R.string.onboarding_status_orange), stringResource(R.string.onboarding_status_orange_desc))
        StatusItem(Color.Red, stringResource(R.string.onboarding_status_red), stringResource(R.string.onboarding_status_red_desc))
        
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.onboarding_status_desc),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun FeatureItem(title: String, desc: String) {
    Column(Modifier.padding(bottom = 16.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(desc, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatusItem(color: Color, name: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(12.dp)) {
            drawCircle(color = color)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
    }
}
