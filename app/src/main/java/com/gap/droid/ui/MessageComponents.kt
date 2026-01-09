package com.gap.droid.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
 

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import android.content.Intent
import android.net.Uri
import com.gap.droid.model.BitchatMessage
import com.gap.droid.model.DeliveryStatus
import com.gap.droid.mesh.BluetoothMeshService
import java.text.SimpleDateFormat
import java.util.*
import com.gap.droid.ui.media.VoiceNotePlayer
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.gap.droid.ui.media.FileMessageItem
import com.gap.droid.model.BitchatMessageType
import com.gap.droid.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration


// VoiceNotePlayer moved to com.gap.droid.ui.media.VoiceNotePlayer

/**
 * Message display components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

@Composable
fun MessagesList(
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    modifier: Modifier = Modifier,
    forceScrollToBottom: Boolean = false,
    onScrolledUpChanged: ((Boolean) -> Unit)? = null,
    onNicknameClick: ((String) -> Unit)? = null,
    onMessageLongPress: ((BitchatMessage) -> Unit)? = null,
    onCancelTransfer: ((BitchatMessage) -> Unit)? = null,
    onImageClick: ((String, List<String>, Int) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    
    // Track if this is the first time messages are being loaded
    var hasScrolledToInitialPosition by remember { mutableStateOf(false) }
    
    // Smart scroll: auto-scroll to bottom for initial load, then only when user is at or near the bottom
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val layoutInfo = listState.layoutInfo
            val firstVisibleIndex = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1
            
            // With reverseLayout=true and reversed data, index 0 is the latest message at the bottom
            val isFirstLoad = !hasScrolledToInitialPosition
            val isNearLatest = firstVisibleIndex <= 2
            
            if (isFirstLoad || isNearLatest) {
                listState.animateScrollToItem(0)
                if (isFirstLoad) {
                    hasScrolledToInitialPosition = true
                }
            }
        }
    }
    
    // Track whether user has scrolled away from the latest messages
    val isAtLatest by remember {
        derivedStateOf {
            val firstVisibleIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1
            firstVisibleIndex <= 2
        }
    }
    LaunchedEffect(isAtLatest) {
        onScrolledUpChanged?.invoke(!isAtLatest)
    }
    
    // Force scroll to bottom when requested (e.g., when user sends a message)
    LaunchedEffect(forceScrollToBottom) {
        if (messages.isNotEmpty()) {
            // With reverseLayout=true and reversed data, latest is at index 0
            listState.animateScrollToItem(0)
        }
    }
    
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
        reverseLayout = true
    ) {
        items(
            items = messages.asReversed(),
            key = { it.id }
        ) { message ->
                MessageItem(
                    message = message,
                    messages = messages,
                    currentUserNickname = currentUserNickname,
                    meshService = meshService,
                    onNicknameClick = onNicknameClick,
                    onMessageLongPress = onMessageLongPress,
                    onCancelTransfer = onCancelTransfer,
                    onImageClick = onImageClick
                )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    messages: List<BitchatMessage> = emptyList(),
    onNicknameClick: ((String) -> Unit)? = null,
    onMessageLongPress: ((BitchatMessage) -> Unit)? = null,
    onCancelTransfer: ((BitchatMessage) -> Unit)? = null,
    onImageClick: ((String, List<String>, Int) -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    
    // Determine if this message was sent by self
    val isSelf = message.senderPeerID == meshService.myPeerID || 
                 message.sender == currentUserNickname ||
                 message.sender.startsWith("$currentUserNickname#")
    
    // System messages get special treatment
    if (message.sender == "system") {
        // Center-aligned system message
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = "• ${message.content} •",
                fontSize = 12.sp,
                color = Color.Gray,
                fontStyle = FontStyle.Italic
            )
        }
        return
    }
    
    // Chat bubble layout
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start
    ) {
        // Sender name (only for others' messages)
        if (!isSelf) {
            val (baseName, suffix) = splitSuffix(message.sender)
            val haptic = LocalHapticFeedback.current
            Row(
                modifier = Modifier
                    .padding(start = 12.dp, bottom = 2.dp)
                    .clickable { 
                        if (onNicknameClick != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNicknameClick(message.originalSender ?: message.sender)
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
                // Use theme-aware colors that work in both light and dark mode
                val senderColor = if (isDark) {
                    getPeerColor(message, isDark)
                } else {
                    // Darker colors for light mode readability
                    Color(0xFF1565C0) // Material Blue 800 - readable on white
                }
                Text(
                    text = truncateNickname(baseName),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = senderColor
                )
                if (suffix.isNotEmpty()) {
                    Text(
                        text = suffix,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Normal,
                        color = senderColor.copy(alpha = 0.6f)
                    )
                }
            }
        }
        
        // Message bubble
        val bubbleShape = RoundedCornerShape(
            topStart = if (isSelf) 16.dp else 4.dp,
            topEnd = if (isSelf) 4.dp else 16.dp,
            bottomStart = 16.dp,
            bottomEnd = 16.dp
        )
        
        val isDarkTheme = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
        
        val bubbleColor = if (isSelf) {
            Color(0xFF2E7D32) // Material Green 700 - works in both themes
        } else {
            if (isDarkTheme) Color(0xFF3D3D3D) else Color(0xFFE8E8E8) // Gray adapts to theme
        }
        
        val maxBubbleWidth = 0.8f // 80% of screen width
        
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .fillMaxWidth(maxBubbleWidth)
                .background(bubbleColor, bubbleShape)
                .pointerInput(message) {
                    detectTapGestures(
                        onLongPress = {
                            onMessageLongPress?.invoke(message)
                        }
                    )
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Delegate to special renderers for media types
            when (message.type) {
                BitchatMessageType.Image -> {
                    com.gap.droid.ui.media.ImageMessageItem(
                        message = message,
                        messages = messages,
                        currentUserNickname = currentUserNickname,
                        meshService = meshService,
                        colorScheme = colorScheme,
                        timeFormatter = timeFormatter,
                        onNicknameClick = onNicknameClick,
                        onMessageLongPress = onMessageLongPress,
                        onCancelTransfer = onCancelTransfer,
                        onImageClick = onImageClick,
                        modifier = Modifier
                    )
                }
                BitchatMessageType.Audio -> {
                    com.gap.droid.ui.media.AudioMessageItem(
                        message = message,
                        currentUserNickname = currentUserNickname,
                        meshService = meshService,
                        colorScheme = colorScheme,
                        timeFormatter = timeFormatter,
                        onNicknameClick = null, // Already showing sender above
                        onMessageLongPress = onMessageLongPress,
                        onCancelTransfer = onCancelTransfer,
                        modifier = Modifier
                    )
                }
                BitchatMessageType.File -> {
                    // File content rendering
                    val path = message.content.trim()
                    val packet = try {
                        val file = java.io.File(path)
                        if (file.exists()) {
                            com.gap.droid.model.BitchatFilePacket(
                                fileName = file.name,
                                fileSize = file.length(),
                                mimeType = com.gap.droid.features.file.FileUtils.getMimeTypeFromExtension(file.name),
                                content = file.readBytes()
                            )
                        } else null
                    } catch (e: Exception) { null }
                    
                    if (packet != null) {
                        FileMessageItem(
                            packet = packet,
                            onFileClick = {}
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.file_unavailable),
                            color = Color.Gray
                        )
                    }
                }
                else -> {
                    // Text message content
                    Column {
                        val isDarkTheme = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
                        val textColor = if (isSelf) Color.White else if (isDarkTheme) colorScheme.onSurface else Color.Black
                        
                        // Format message content with clickable elements
                        val annotatedContent = formatBubbleMessageContent(
                            content = message.content,
                            mentions = message.mentions,
                            currentUserNickname = currentUserNickname,
                            textColor = textColor,
                            isSelf = isSelf
                        )
                        
                        val context = LocalContext.current
                        val haptic = LocalHapticFeedback.current
                        var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
                        
                        Text(
                            text = annotatedContent,
                            fontSize = 15.sp,
                            color = textColor,
                            modifier = Modifier.pointerInput(message.id) {
                                detectTapGestures(
                                    onTap = { position ->
                                        val layout = textLayoutResult ?: return@detectTapGestures
                                        val offset = layout.getOffsetForPosition(position)
                                        
                                        // Check for URL clicks
                                        val urlAnnotations = annotatedContent.getStringAnnotations(
                                            tag = "url_click",
                                            start = offset,
                                            end = offset
                                        )
                                        if (urlAnnotations.isNotEmpty()) {
                                            val raw = urlAnnotations.first().item
                                            val resolved = if (raw.startsWith("http://", ignoreCase = true) || 
                                                raw.startsWith("https://", ignoreCase = true)) raw else "https://$raw"
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(resolved))
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                            } catch (_: Exception) {}
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        }
                                    }
                                )
                            },
                            onTextLayout = { textLayoutResult = it }
                        )
                        
                        // Timestamp row
                        Row(
                            modifier = Modifier
                                .align(Alignment.End)
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = timeFormatter.format(message.timestamp),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                // Use colors with good contrast against bubble backgrounds
                                color = if (isSelf) {
                                    Color.White.copy(alpha = 0.85f) // White on green bubble
                                } else {
                                    // Dark gray text on both light and dark gray bubbles
                                    if (isDarkTheme) Color(0xFFAAAAAA) else Color(0xFF555555)
                                }
                            )
                            
                            // Delivery status for own messages
                            if (isSelf && message.isPrivate) {
                                message.deliveryStatus?.let { status ->
                                    DeliveryStatusIcon(status = status)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
    private fun MessageTextWithClickableNicknames(
        message: BitchatMessage,
        messages: List<BitchatMessage>,
        currentUserNickname: String,
        meshService: BluetoothMeshService,
        colorScheme: ColorScheme,
        timeFormatter: SimpleDateFormat,
        onNicknameClick: ((String) -> Unit)?,
        onMessageLongPress: ((BitchatMessage) -> Unit)?,
        onCancelTransfer: ((BitchatMessage) -> Unit)?,
        onImageClick: ((String, List<String>, Int) -> Unit)?,
        modifier: Modifier = Modifier
    ) {
    // Image special rendering
    if (message.type == BitchatMessageType.Image) {
        com.gap.droid.ui.media.ImageMessageItem(
            message = message,
            messages = messages,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            onCancelTransfer = onCancelTransfer,
            onImageClick = onImageClick,
            modifier = modifier
        )
        return
    }

    // Voice note special rendering
    if (message.type == BitchatMessageType.Audio) {
        com.gap.droid.ui.media.AudioMessageItem(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            onCancelTransfer = onCancelTransfer,
            modifier = modifier
        )
        return
    }

    // File special rendering
    if (message.type == BitchatMessageType.File) {
        val path = message.content.trim()
        // Derive sending progress if applicable
        val (overrideProgress, _) = when (val st = message.deliveryStatus) {
            is com.gap.droid.model.DeliveryStatus.PartiallyDelivered -> {
                if (st.total > 0 && st.reached < st.total) {
                    (st.reached.toFloat() / st.total.toFloat()) to Color(0xFF1E88E5) // blue while sending
                } else null to null
            }
            else -> null to null
        }
        Column(modifier = modifier.fillMaxWidth()) {
            // Header: nickname + timestamp line above the file, identical styling to text messages
            val headerText = formatMessageHeaderAnnotatedString(
                message = message,
                currentUserNickname = currentUserNickname,
                meshService = meshService,
                colorScheme = colorScheme,
                timeFormatter = timeFormatter
            )
            val haptic = LocalHapticFeedback.current
            var headerLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                text = headerText,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onSurface,
                modifier = Modifier.pointerInput(message.id) {
                    detectTapGestures(onTap = { pos ->
                        val layout = headerLayout ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(pos)
                        val ann = headerText.getStringAnnotations("nickname_click", offset, offset)
                        if (ann.isNotEmpty() && onNicknameClick != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNicknameClick.invoke(ann.first().item)
                        }
                    }, onLongPress = { onMessageLongPress?.invoke(message) })
                },
                onTextLayout = { headerLayout = it }
            )

            // Try to load the file packet from the path
            val packet = try {
                val file = java.io.File(path)
                if (file.exists()) {
                    // Create a temporary BitchatFilePacket for display
                    // In a real implementation, this would be stored with the packet metadata
                    com.gap.droid.model.BitchatFilePacket(
                        fileName = file.name,
                        fileSize = file.length(),
                        mimeType = com.gap.droid.features.file.FileUtils.getMimeTypeFromExtension(file.name),
                        content = file.readBytes()
                    )
                } else null
            } catch (e: Exception) {
                null
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Box {
                    if (packet != null) {
                        if (overrideProgress != null) {
                            // Show sending animation while in-flight
                            com.gap.droid.ui.media.FileSendingAnimation(
                                fileName = packet.fileName,
                                progress = overrideProgress,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            // Static file display with open/save dialog
                            FileMessageItem(
                                packet = packet,
                                onFileClick = {
                                    // handled inside FileMessageItem via dialog
                                }
                            )
                        }

                        // Cancel button overlay during sending
                        val showCancel = message.sender == currentUserNickname && (message.deliveryStatus is DeliveryStatus.PartiallyDelivered)
                        if (showCancel) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(22.dp)
                                    .background(Color.Gray.copy(alpha = 0.6f), CircleShape)
                                    .clickable { onCancelTransfer?.invoke(message) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.cd_cancel), tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    } else {
                        Text(text = stringResource(R.string.file_unavailable), fontFamily = FontFamily.Monospace, color = Color.Gray)
                    }
                }
            }
        }
        return
    }

    // Check if this message should be animated during PoW mining
    val shouldAnimate = shouldAnimateMessage(message.id)
    
    // If animation is needed, use the matrix animation component for content only
    if (shouldAnimate) {
        // Display message with matrix animation for content
        MessageWithMatrixAnimation(
            message = message,
            messages = messages,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            onImageClick = onImageClick,
            modifier = modifier
        )
    } else {
        // Normal message display
        val annotatedText = formatMessageAsAnnotatedString(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter
        )
        
        // Check if this message was sent by self to avoid click interactions on own nickname
        val isSelf = message.senderPeerID == meshService.myPeerID || 
                     message.sender == currentUserNickname ||
                     message.sender.startsWith("$currentUserNickname#")
        
        val haptic = LocalHapticFeedback.current
        val context = LocalContext.current
        var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = annotatedText,
            modifier = modifier.pointerInput(message) {
                detectTapGestures(
                    onTap = { position ->
                        val layout = textLayoutResult ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(position)
                        // Nickname click only when not self
                        if (!isSelf && onNicknameClick != null) {
                            val nicknameAnnotations = annotatedText.getStringAnnotations(
                                tag = "nickname_click",
                                start = offset,
                                end = offset
                            )
                            if (nicknameAnnotations.isNotEmpty()) {
                                val nickname = nicknameAnnotations.first().item
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNicknameClick.invoke(nickname)
                                return@detectTapGestures
                            }
                        }
                        // Geohash teleport (all messages)
                        val geohashAnnotations = annotatedText.getStringAnnotations(
                            tag = "geohash_click",
                            start = offset,
                            end = offset
                        )
                        if (geohashAnnotations.isNotEmpty()) {
                            val geohash = geohashAnnotations.first().item
                            try {
                                val locationManager = com.gap.droid.geohash.LocationChannelManager.getInstance(
                                    context
                                )
                                val level = when (geohash.length) {
                                    in 0..2 -> com.gap.droid.geohash.GeohashChannelLevel.REGION
                                    in 3..4 -> com.gap.droid.geohash.GeohashChannelLevel.PROVINCE
                                    5 -> com.gap.droid.geohash.GeohashChannelLevel.CITY
                                    6 -> com.gap.droid.geohash.GeohashChannelLevel.NEIGHBORHOOD
                                    else -> com.gap.droid.geohash.GeohashChannelLevel.BLOCK
                                }
                                val channel = com.gap.droid.geohash.GeohashChannel(level, geohash.lowercase())
                                locationManager.setTeleported(true)
                                locationManager.select(com.gap.droid.geohash.ChannelID.Location(channel))
                            } catch (_: Exception) { }
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            return@detectTapGestures
                        }
                        // URL open (all messages)
                        val urlAnnotations = annotatedText.getStringAnnotations(
                            tag = "url_click",
                            start = offset,
                            end = offset
                        )
                        if (urlAnnotations.isNotEmpty()) {
                            val raw = urlAnnotations.first().item
                            val resolved = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) raw else "https://$raw"
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(resolved))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (_: Exception) { }
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            return@detectTapGestures
                        }
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMessageLongPress?.invoke(message)
                    }
                )
            },
            fontFamily = FontFamily.Monospace,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = androidx.compose.ui.text.TextStyle(
                color = colorScheme.onSurface
            ),
            onTextLayout = { result -> textLayoutResult = result }
        )
    }
}

@Composable
fun DeliveryStatusIcon(status: DeliveryStatus) {
    val colorScheme = MaterialTheme.colorScheme
    
    when (status) {
        is DeliveryStatus.Sending -> {
            Text(
                text = stringResource(R.string.status_sending),
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Sent -> {
            // Use a subtle hollow marker for Sent; single check is reserved for Delivered (iOS parity)
            Text(
                text = stringResource(R.string.status_pending),
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Delivered -> {
            // Single check for Delivered (matches iOS expectations)
            Text(
                text = stringResource(R.string.status_sent),
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.Read -> {
            Text(
                text = stringResource(R.string.status_delivered),
                fontSize = 10.sp,
                color = Color(0xFF007AFF), // Blue
                fontWeight = FontWeight.Bold
            )
        }
        is DeliveryStatus.Failed -> {
            Text(
                text = stringResource(R.string.status_failed),
                fontSize = 10.sp,
                color = Color.Red.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.PartiallyDelivered -> {
            // Show a single subdued check without numeric label
            Text(
                text = stringResource(R.string.status_sent),
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * Format message content for chat bubble display with clickable URLs and mentions
 */
fun formatBubbleMessageContent(
    content: String,
    mentions: List<String>?,
    currentUserNickname: String,
    textColor: Color,
    isSelf: Boolean
): AnnotatedString {
    val builder = AnnotatedString.Builder()
    
    // URL pattern matching
    val urlPattern = """(https?://[^\s]+|www\.[^\s]+|[a-zA-Z0-9][-a-zA-Z0-9]*\.[a-zA-Z]{2,}[^\s]*)""".toRegex()
    val mentionPattern = """@([\p{L}0-9_]+(?:#[a-fA-F0-9]{4})?)""".toRegex()
    
    // Find all matches
    val urlMatches = urlPattern.findAll(content).toList()
    val mentionMatches = mentionPattern.findAll(content).toList()
    
    // Combine and sort
    data class Match(val range: IntRange, val type: String, val value: String)
    val allMatches = mutableListOf<Match>()
    
    urlMatches.forEach { allMatches.add(Match(it.range, "url", it.value)) }
    mentionMatches.forEach { allMatches.add(Match(it.range, "mention", it.value)) }
    allMatches.sortBy { it.range.first }
    
    // Remove overlapping matches (keep first one)
    val cleanedMatches = mutableListOf<Match>()
    for (match in allMatches) {
        val overlaps = cleanedMatches.any { existing ->
            match.range.first < existing.range.last && match.range.last > existing.range.first
        }
        if (!overlaps) cleanedMatches.add(match)
    }
    
    var lastEnd = 0
    val isMentioned = mentions?.contains(currentUserNickname) == true
    
    for (match in cleanedMatches) {
        // Text before match
        if (lastEnd < match.range.first) {
            builder.pushStyle(SpanStyle(
                color = textColor,
                fontWeight = if (isMentioned) FontWeight.Bold else FontWeight.Normal
            ))
            builder.append(content.substring(lastEnd, match.range.first))
            builder.pop()
        }
        
        // Match content
        when (match.type) {
            "url" -> {
                builder.pushStyle(SpanStyle(
                    color = Color(0xFF64B5F6), // Light blue for links
                    textDecoration = TextDecoration.Underline
                ))
                val start = builder.length
                builder.append(match.value)
                val end = builder.length
                builder.addStringAnnotation("url_click", match.value, start, end)
                builder.pop()
            }
            "mention" -> {
                val mentionName = match.value.removePrefix("@")
                val isMe = mentionName == currentUserNickname || mentionName.startsWith("$currentUserNickname#")
                builder.pushStyle(SpanStyle(
                    color = if (isMe) Color(0xFFFF9500) else Color(0xFF64B5F6),
                    fontWeight = FontWeight.SemiBold
                ))
                builder.append(match.value)
                builder.pop()
            }
        }
        
        lastEnd = match.range.last + 1
    }
    
    // Remaining text
    if (lastEnd < content.length) {
        builder.pushStyle(SpanStyle(
            color = textColor,
            fontWeight = if (isMentioned) FontWeight.Bold else FontWeight.Normal
        ))
        builder.append(content.substring(lastEnd))
        builder.pop()
    }
    
    return builder.toAnnotatedString()
}
