package com.vaani.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.height
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.graphicsLayer
import com.vaani.app.viewmodel.VoiceState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.vaani.app.R
import com.vaani.app.data.AgentError
import com.vaani.app.data.CommandResult
import com.vaani.app.data.CommandStatus
import com.vaani.app.data.FoundFile
import com.vaani.app.ui.theme.StatusColors
import com.vaani.app.ui.theme.VaaniTheme
import com.vaani.app.viewmodel.ConnectionState
import com.vaani.app.viewmodel.Exchange
import com.vaani.app.viewmodel.HomeUiState
import com.vaani.app.viewmodel.MAX_COMMAND_LENGTH
import com.vaani.app.viewmodel.Reply

private val QUICK_COMMANDS = listOf(
    "Open Calculator",
    "Find latest PDF in Downloads",
    "Open Notepad",
    "Open File Explorer",
    "Is my laptop connected?",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onQuickCommand: (String) -> Unit,
    onMicClick: () -> Unit,
    onRefreshConnection: () -> Unit,
    onNoticeShown: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleMute: () -> Unit = {},
    micLevel: Float = 0f,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbar.showSnackbar(it)
            onNoticeShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VaaniLogo(size = 32.dp)
                        Spacer(Modifier.size(10.dp))
                        Text("Vaani", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    ConnectionChip(state.connection, onRefreshConnection)
                    IconButton(onClick = onToggleMute) {
                        Icon(
                            painterResource(if (state.muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up),
                            contentDescription = if (state.muted) "Spoken replies off. Tap to turn on." else "Spoken replies on. Tap to mute.",
                            tint = if (state.speaking) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(painterResource(R.drawable.ic_settings), contentDescription = "Laptop connection settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            if (state.isSending || state.voice == VoiceState.Transcribing) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.size(4.dp))
            }
            Conversation(state.exchanges, Modifier.weight(1f))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(QUICK_COMMANDS) { cmd ->
                    AssistChip(onClick = { onQuickCommand(cmd) }, label = { Text(cmd) }, enabled = !state.isBusy)
                }
            }
            CommandBar(state, micLevel, onInputChange, onSend, onCancel, onMicClick)
        }
    }
}

@Composable
private fun ConnectionChip(connection: ConnectionState, onRefresh: () -> Unit) {
    val (label, target) = when (connection) {
        ConnectionState.Checking -> "Checking…" to StatusColors.checking
        is ConnectionState.Connected -> "Connected" to StatusColors.connected
        is ConnectionState.Disconnected -> connection.reason.shortLabel() to StatusColors.disconnected
    }
    val color by animateColorAsState(target, label = "connection")
    Surface(
        onClick = onRefresh,
        enabled = connection != ConnectionState.Checking,
        shape = CircleShape,
        color = color.copy(alpha = 0.12f),
        modifier = Modifier
            .padding(end = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "Laptop $label. Tap to check again."
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.size(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = color)
        }
    }
}

@Composable
private fun Conversation(exchanges: List<Exchange>, modifier: Modifier = Modifier) {
    if (exchanges.isEmpty()) {
        EmptyState(modifier)
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(exchanges.size, exchanges.lastOrNull()?.reply) {
        listState.animateScrollToItem(exchanges.lastIndex)
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(exchanges, key = { it.id }) { ExchangeItem(it) }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VaaniLogo(size = 72.dp)
        Spacer(Modifier.size(16.dp))
        Text("What should your laptop do?", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.size(8.dp))
        Text(
            "Try “Open Calculator” or “Find my project report”.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ExchangeItem(exchange: Exchange) {
    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            modifier = Modifier.align(Alignment.End).widthIn(max = 320.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (exchange.viaVoice) {
                    Icon(
                        painterResource(R.drawable.ic_mic),
                        contentDescription = "Spoken",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                }
                Text(exchange.command, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.size(6.dp))
        ReplyBubble(exchange.reply, Modifier.align(Alignment.Start))
    }
}

private data class Tone(val label: String, val color: Color)

@Composable
private fun ReplyBubble(reply: Reply, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
        modifier = modifier.widthIn(max = 340.dp).semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            when (reply) {
                Reply.Pending -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                    Text("Working on it…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Reply.Cancelled -> Text(
                    "Cancelled. If the laptop already started the action, it may still finish.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is Reply.Failed -> {
                    StatusLabel(Tone("Couldn't send", MaterialTheme.colorScheme.error))
                    Text(reply.error.userMessage())
                }
                is Reply.Answered -> ResultContent(reply.result)
            }
        }
    }
}

@Composable
private fun ResultContent(result: CommandResult) {
    val tone = when (result.status) {
        CommandStatus.Success -> Tone("Done", StatusColors.connected)
        CommandStatus.NotFound -> Tone("Not found", MaterialTheme.colorScheme.onSurfaceVariant)
        CommandStatus.Rejected -> Tone("Not allowed", MaterialTheme.colorScheme.error)
        CommandStatus.Error -> Tone("Failed", MaterialTheme.colorScheme.error)
        CommandStatus.NeedsClarification -> Tone("Question", MaterialTheme.colorScheme.secondary)
    }
    StatusLabel(tone)
    if (result.files.isNotEmpty()) {
        SearchResults(result.files, result.search, key = result.requestId)
    } else {
        Text(result.message)
        result.search?.let { s ->
            if (s.searchedFolders.isNotEmpty()) {
                Text(
                    "Searched ${s.searchedFolders.joinToString(", ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusLabel(tone: Tone) {
    Text(
        tone.label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = tone.color,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun CommandBar(
    state: HomeUiState,
    micLevel: Float,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    onMicClick: () -> Unit,
) {
    val voice = state.voice
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MicButton(voice, micLevel, enabled = !state.isSending, onClick = onMicClick)
        Spacer(Modifier.size(8.dp))
        if (voice != VoiceState.Idle) {
            VoiceStatus(voice, Modifier.weight(1f))
        } else OutlinedTextField(
            value = state.input,
            onValueChange = { text ->
                // The field wraps long commands, so Enter would insert a newline; treat it as Send.
                if ('\n' in text) {
                    onInputChange(text.replace("\n", ""))
                    onSend()
                } else {
                    onInputChange(text)
                }
            },
            placeholder = { Text("Type a command") },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            maxLines = 3,
            supportingText = if (state.input.length > MAX_COMMAND_LENGTH - 50) {
                { Text("${state.input.length}/$MAX_COMMAND_LENGTH") }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        Spacer(Modifier.size(8.dp))
        if (state.isBusy) {
            FilledIconButton(onClick = onCancel, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "Cancel")
            }
        } else {
            FilledIconButton(onClick = onSend, enabled = state.canSend, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_send), contentDescription = "Send command")
            }
        }
    }
}

@Composable
private fun MicButton(voice: VoiceState, level: Float, enabled: Boolean, onClick: () -> Unit) {
    val recording = voice is VoiceState.Recording
    val ring by animateFloatAsState(if (recording) 1f + level * 0.6f else 1f, label = "micRing")
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
        if (recording) {
            Box(
                Modifier
                    .size(48.dp)
                    .graphicsLayer { scaleX = ring; scaleY = ring }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.18f))
            )
        }
        when (voice) {
            is VoiceState.Recording -> FilledIconButton(
                onClick = onClick,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Icon(painterResource(R.drawable.ic_stop), contentDescription = "Stop and send voice command") }
            VoiceState.Transcribing -> FilledTonalIconButton(onClick = {}, enabled = false, modifier = Modifier.size(48.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            VoiceState.Idle -> FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_mic), contentDescription = "Speak a command")
            }
        }
    }
}

@Composable
private fun VoiceStatus(voice: VoiceState, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .height(56.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.Center) {
            when (voice) {
                is VoiceState.Recording -> {
                    val secs = voice.elapsedMs / 1000
                    Text("Listening…  0:%02d".format(secs), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Tap ■ to send · up to 0:30",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VoiceState.Transcribing -> Text("Understanding…", style = MaterialTheme.typography.bodyLarge)
                VoiceState.Idle -> Unit
            }
        }
    }
}

// --- previews ---------------------------------------------------------------

private val previewState = HomeUiState(
    connection = ConnectionState.Connected("LAPTOP"),
    exchanges = listOf(
        Exchange(0, "Open Calculator", Reply.Answered(
            CommandResult("a", CommandStatus.Success, "open_calculator", "Calculator opened."))),
        Exchange(1, "Find my Java project", Reply.Answered(
            CommandResult("b", CommandStatus.Success, "find_file", "Found Documents/Projects/JavaProject.",
                listOf(FoundFile("JavaProject", "Documents/Projects/JavaProject", true))))),
        Exchange(2, "Delete everything", Reply.Answered(
            CommandResult("c", CommandStatus.Rejected, null, "This action is not supported."))),
        Exchange(3, "Open Notepad", Reply.Failed(AgentError.Unreachable)),
        Exchange(4, "Open File Explorer", Reply.Pending),
    ),
)

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun HomePreview() {
    VaaniTheme(dynamicColor = false) {
        HomeScreen(previewState, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, heightDp = 640)
@Composable
private fun HomeEmptyPreview() {
    VaaniTheme(dynamicColor = false) {
        HomeScreen(HomeUiState(connection = ConnectionState.Disconnected(AgentError.NotConfigured)),
            {}, {}, {}, {}, {}, {}, {}, {})
    }
}
