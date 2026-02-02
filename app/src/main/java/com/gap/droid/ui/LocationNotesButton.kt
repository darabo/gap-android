package com.gapmesh.droid.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.gapmesh.droid.R
import com.gapmesh.droid.geohash.ChannelID
import com.gapmesh.droid.geohash.LocationChannelManager
import com.gapmesh.droid.nostr.LocationNotesManager

/**
 * Location Notes button component for MainHeader
 * Shows in mesh mode when location permission granted AND services enabled
 * Icon turns primary color when notes exist, gray otherwise
 * Made more prominent with larger icon and badge indicator
 */
@Composable
fun LocationNotesButton(
    viewModel: ChatViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    
    // Get channel and permission state
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val locationManager = remember { LocationChannelManager.getInstance(context) }
    val permissionState by locationManager.permissionState.collectAsStateWithLifecycle()
    val locationServicesEnabled by locationManager.locationServicesEnabled.collectAsStateWithLifecycle(false)

    // Check both permission AND location services enabled
    val locationPermissionGranted = permissionState == LocationChannelManager.PermissionState.AUTHORIZED
    val locationEnabled = locationPermissionGranted && locationServicesEnabled
    
    // Get notes count from LocationNotesManager
    val notesManager = remember { LocationNotesManager.getInstance() }
    val notes by notesManager.notes.collectAsStateWithLifecycle()
    val notesCount = notes.size

    // Only show in mesh mode when location is authorized (iOS pattern)
    if (selectedLocationChannel is ChannelID.Mesh && locationEnabled) {
        val hasNotes = notesCount > 0
        
        Box(modifier = modifier) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.size(36.dp) // Larger touch target
            ) {
                Icon(
                    imageVector = Icons.Filled.EditNote, // More recognizable "notes" icon
                    contentDescription = stringResource(R.string.cd_location_notes),
                    modifier = Modifier.size(22.dp), // Larger icon
                    tint = if (hasNotes) Color(0xFF2E7D32) else colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            
            // Badge indicator when notes exist
            if (hasNotes) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .align(Alignment.TopEnd)
                        .offset(x = (-4).dp, y = 4.dp)
                        .background(Color(0xFF2E7D32), CircleShape)
                )
            }
        }
    }
}
