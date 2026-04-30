package com.example.airecorder03

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.airecorder03.database.RecordingItem
import com.example.airecorder03.ui.theme.AIrecorder03Theme
import com.example.airecorder03.ui.theme.AccentRecord
import com.example.airecorder03.ui.theme.AccentSummary
import com.example.airecorder03.ui.theme.ChatBubbleMaxWidthFraction
import com.example.airecorder03.ui.theme.CornerRadii
import com.example.airecorder03.ui.theme.Elevations
import com.example.airecorder03.ui.theme.chatContentMaxWidth
import com.example.airecorder03.ui.theme.listContentMaxWidth
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private val recordingViewModel: RecordingViewModel by viewModels()
    private val recordingsListViewModel: RecordingsListViewModel by viewModels()
    private val playerViewModel: PlayerViewModel by viewModels { PlayerViewModel.Factory() }
    private val chatViewModel: ChatViewModel by viewModels()

    private var pendingTranscriptionRequest: Pair<RecordingItem, String>? = null

    // File picker for importing audio files
    private val importAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // Take persistable permission so we can read the file
            try {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { /* Not all providers support persistable permissions */ }
            recordingsListViewModel.importAudioFile(it)
            Toast.makeText(this, "Importing audio file...", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestRecordingPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val isGranted = permissions.entries.all { it.value }
        if (isGranted) {
            recordingViewModel.startRecording()
        } else {
            Toast.makeText(this, "Recording permission is required to start recording.", Toast.LENGTH_LONG).show()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pendingTranscriptionRequest?.let { (recording, lang) ->
                recordingsListViewModel.transcribeFile(recording, lang)
            }
            pendingTranscriptionRequest = null
        } else {
            Toast.makeText(this, "Notification permission is required for background transcription.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AIrecorder03Theme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AIrecorderAppLayout(
                        modifier = Modifier.padding(innerPadding),
                        recordingViewModel = recordingViewModel,
                        recordingsListViewModel = recordingsListViewModel,
                        playerViewModel = playerViewModel,
                        chatViewModel = chatViewModel,
                        onStartRecording = { checkAndStartRecording() },
                        onTranscribe = { recording, lang ->
                            checkAndTranscribe(recording, lang)
                        },
                        onImportAudio = {
                            importAudioLauncher.launch(arrayOf(
                                "audio/mpeg",      // mp3
                                "audio/mp4",       // m4a
                                "audio/x-m4a",     // m4a alternative
                                "audio/wav",       // wav
                                "audio/x-wav",     // wav alternative
                                "audio/flac",      // flac
                                "audio/aac",       // aac
                                "audio/ogg",       // ogg
                                "audio/*"          // fallback for other audio types
                            ))
                        },
                        onShareRecording = { recording ->
                            val intent = recordingsListViewModel.getShareIntent(recording)
                            if (intent != null) {
                                startActivity(Intent.createChooser(intent, "Share Recording"))
                            } else {
                                Toast.makeText(this, "Recording file not found.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }

    private fun checkAndStartRecording() {
        val requiredPermissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            requiredPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }

        val areAllPermissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (areAllPermissionsGranted) {
            recordingViewModel.startRecording()
        } else {
            requestRecordingPermissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    private fun checkAndTranscribe(recording: RecordingItem, lang: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                recordingsListViewModel.transcribeFile(recording, lang)
            } else {
                pendingTranscriptionRequest = recording to lang
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            recordingsListViewModel.transcribeFile(recording, lang)
        }
    }
}

@Composable
fun AIrecorderAppLayout(
    modifier: Modifier = Modifier,
    recordingViewModel: RecordingViewModel,
    recordingsListViewModel: RecordingsListViewModel,
    playerViewModel: PlayerViewModel,
    chatViewModel: ChatViewModel,
    onStartRecording: () -> Unit,
    onTranscribe: (RecordingItem, String) -> Unit,
    onImportAudio: () -> Unit,
    onShareRecording: (RecordingItem) -> Unit
) {
    val recordings by recordingsListViewModel.recordings.collectAsState()
    var viewingRecording by rememberSaveable(stateSaver = RecordingItem.saver) { mutableStateOf<RecordingItem?>(null) }
    var editingRecording by remember { mutableStateOf<RecordingItem?>(null) }
    var deletingRecording by remember { mutableStateOf<RecordingItem?>(null) }
    var viewingSummaryRecording by remember { mutableStateOf<RecordingItem?>(null) }
    val transcribingFileId by rememberUpdatedState(recordingsListViewModel.transcribingFileId)
    val transcriptionCompleted by rememberUpdatedState(recordingsListViewModel.transcriptionCompleted)
    val summarizingFileId by rememberUpdatedState(recordingsListViewModel.summarizingFileId)
    val summaryCompleted by rememberUpdatedState(recordingsListViewModel.summaryCompleted)
    val summaryProgress by rememberUpdatedState(recordingsListViewModel.summaryProgress)
    val summaryError by rememberUpdatedState(recordingsListViewModel.summaryError)

    if (editingRecording != null) {
        RenameDialog(
            recording = editingRecording!!,
            onDismiss = { editingRecording = null },
            onConfirm = { newName ->
                recordingsListViewModel.renameRecording(editingRecording!!, newName)
                editingRecording = null
            }
        )
    }

    if (deletingRecording != null) {
        DeleteConfirmationDialog(
            recording = deletingRecording!!,
            onDismiss = { deletingRecording = null },
            onConfirm = {
                recordingsListViewModel.deleteRecording(deletingRecording!!)
                deletingRecording = null
            }
        )
    }

    if (transcribingFileId != null) {
        val transcribingItem = recordings.find { it.id == transcribingFileId }
        if (transcribingItem != null) {
            TranscriptionStatusDialog(
                item = transcribingItem,
                isCompleted = transcriptionCompleted,
                onDismiss = { recordingsListViewModel.dismissTranscriptionDialog() }
            )
        }
    }

    // AI Summary in-progress dialog
    if (summarizingFileId != null) {
        val summarizingItem = recordings.find { it.id == summarizingFileId }
        if (summarizingItem != null) {
            SummaryStatusDialog(
                item = summarizingItem,
                isCompleted = summaryCompleted,
                progress = summaryProgress,
                error = summaryError,
                onDismiss = { recordingsListViewModel.dismissSummaryDialog() }
            )
        }
    }

    if (viewingSummaryRecording != null) {
        // Find the latest version from recordings list (in case summary was just generated)
        val latestRecording = recordings.find { it.id == viewingSummaryRecording!!.id } ?: viewingSummaryRecording!!
        SummaryViewerScreen(
            recording = latestRecording,
            onBack = { viewingSummaryRecording = null },
            onSave = { newText ->
                recordingsListViewModel.updateSummary(latestRecording, newText)
            },
            onRegenerate = {
                viewingSummaryRecording = null
                recordingsListViewModel.summarizeRecording(latestRecording)
            }
        )
    } else if (viewingRecording == null) {
        val pagerState = rememberPagerState(pageCount = { 3 })
        val coroutineScope = rememberCoroutineScope()

        // 生命周期感知：监听聊天界面切入切出（Chat 现在是 page 2）
        LaunchedEffect(pagerState.currentPage) {
            if (pagerState.currentPage == 2) {
                chatViewModel.onChatResumed()
            } else {
                chatViewModel.onChatPaused()
            }
        }

        val transcriptionState by recordingViewModel.transcriptionState.collectAsState()

        Box(modifier = modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                when (page) {
                    0 -> RecordingScreen(
                        modifier = Modifier.fillMaxSize(),
                        isRecording = recordingViewModel.isRecording,
                        isPaused = recordingViewModel.isPaused,
                        duration = recordingViewModel.recordingDuration,
                        transcriptionState = transcriptionState,
                        transcriptionLanguage = recordingViewModel.transcriptionLanguage,
                        recentReplays = recordingViewModel.recentReplays,
                        isSavingReplay = recordingViewModel.isSavingReplay,
                        onStartClick = onStartRecording,
                        onStopClick = { recordingViewModel.stopRecording() },
                        onPauseClick = { recordingViewModel.pauseRecording() },
                        onResumeClick = { recordingViewModel.resumeRecording() },
                        onTriggerReplay = { recordingViewModel.triggerInstantReplay() },
                        onStartTranscription = { lang -> recordingViewModel.startLiveTranscription(lang) },
                        onStopTranscription = { recordingViewModel.stopLiveTranscription() },
                        onToggleLanguage = {
                            recordingViewModel.changeTranscriptionLanguage(
                                if (recordingViewModel.transcriptionLanguage == "CN") "EN" else "CN"
                            )
                        },
                        playerViewModel = playerViewModel,
                        onNavigateToNext = {
                            coroutineScope.launch { pagerState.animateScrollToPage(1) }
                        }
                    )
                    1 -> RecordingsListScreen(
                        modifier = Modifier.fillMaxSize(),
                        recordings = recordings,
                        playerViewModel = playerViewModel,
                        onTranscribe = onTranscribe,
                        onView = { recording -> viewingRecording = recording },
                        onEdit = { recording -> editingRecording = recording },
                        onDelete = { recording -> deletingRecording = recording },
                        onImportAudio = onImportAudio,
                        onShareRecording = onShareRecording,
                        transcribingFileId = transcribingFileId,
                        summarizingFileId = summarizingFileId,
                        onSummarize = { recording -> recordingsListViewModel.summarizeRecording(recording) },
                        onViewSummary = { recording -> viewingSummaryRecording = recording },
                        onNavigateToPrevious = {
                            coroutineScope.launch { pagerState.animateScrollToPage(0) }
                        },
                        onNavigateToNext = {
                            coroutineScope.launch { pagerState.animateScrollToPage(2) }
                        }
                    )
                    2 -> ChatScreen(
                        modifier = Modifier.fillMaxSize(),
                        viewModel = chatViewModel,
                        recordingsListViewModel = recordingsListViewModel,
                        onNavigateToPrevious = {
                            coroutineScope.launch { pagerState.animateScrollToPage(1) }
                        }
                    )
                }
            }

            PagerDotIndicator(
                pageCount = 3,
                currentPage = pagerState.currentPage,
                onDotClick = { page ->
                    coroutineScope.launch { pagerState.animateScrollToPage(page) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
            )
        }
    } else {
        viewingRecording?.let {
            TranscriptionViewerScreen(
                recording = it,
                recordingsListViewModel = recordingsListViewModel,
                playerViewModel = playerViewModel,
                chatViewModel = chatViewModel,
                onBack = { viewingRecording = null })
        }
    }
}

@Composable
fun TranscriptionStatusDialog(item: RecordingItem, isCompleted: Boolean?, onDismiss: () -> Unit) {
    val title: String
    val text: @Composable () -> Unit

    when (isCompleted) {
        null -> {
            title = "Transcription in Progress"
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("Please wait while \"${item.fileName}\" is being transcribed.")
                    Spacer(modifier = Modifier.height(16.dp))
                    CircularProgressIndicator()
                }
            }
        }
        true -> {
            title = "Transcription Complete"
            text = { Text("\"${item.fileName}\" has been successfully transcribed.") }
        }
        false -> {
            title = "Transcription Failed"
            text = { Text("Failed to transcribe \"${item.fileName}\".") }
        }
    }

    AlertDialog(
        onDismissRequest = { if (isCompleted != null) onDismiss() },
        title = { Text(title) },
        text = text,
        confirmButton = {
            if (isCompleted != null) {
                TextButton(onClick = onDismiss) {
                    Text("OK")
                }
            }
        }
    )
}

// ============================================================
// Settings Dialog - Provider selection & API key management
// ============================================================

@Composable
fun SettingsDialog(
    apiKeyStore: ApiKeyStore,
    currentLlmProvider: LlmProvider,
    onLlmProviderChanged: (LlmProvider) -> Unit,
    onRequestRebuildSync: (alsoClearFirstSummaries: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var speechProvider by remember { mutableStateOf(apiKeyStore.getSpeechProvider()) }
    var llmProvider by remember { mutableStateOf(currentLlmProvider) }

    var iflytekAppId by remember { mutableStateOf(apiKeyStore.getIFlytekAppId()) }
    var iflytekSecretKey by remember { mutableStateOf(apiKeyStore.getIFlytekSecretKey()) }
    var qwenCloudKey by remember { mutableStateOf(apiKeyStore.getQwenCloudApiKey()) }
    var tavilyKey by remember { mutableStateOf(apiKeyStore.getTavilyApiKey()) }
    var reduceFileMemoryTokens by remember { mutableStateOf(apiKeyStore.getReduceFileMemoryTokens()) }

    // Rebuild-sync confirmation cascade
    var rebuildConfirmStep by remember { mutableStateOf(0) }  // 0=none, 1=first confirm, 2=second confirm, 3=ask-delete-first-summaries

    when (rebuildConfirmStep) {
        1 -> AlertDialog(
            onDismissRequest = { rebuildConfirmStep = 0 },
            title = { Text("确定要重建本地内容同步数据库吗？") },
            text = {
                Text(
                    "此操作会删除所有二次总结结果，然后重新对所有缺失一次/二次总结的文件进行云端总结。" +
                        "将大量消耗 Qwen Omni Plus 和 Qwen3-Max 额度，耗时且可能产生较多费用。"
                )
            },
            confirmButton = {
                TextButton(onClick = { rebuildConfirmStep = 2 }) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = { rebuildConfirmStep = 0 }) { Text("取消") }
            }
        )
        2 -> AlertDialog(
            onDismissRequest = { rebuildConfirmStep = 0 },
            title = { Text("请再次确认") },
            text = {
                Text(
                    "再次确认：此操作不可撤销，且会产生真实的 API 调用费用与流量消耗。" +
                        "如果你不完全清楚后果，请点击取消。"
                )
            },
            confirmButton = {
                TextButton(onClick = { rebuildConfirmStep = 3 }) { Text("我已理解，继续") }
            },
            dismissButton = {
                TextButton(onClick = { rebuildConfirmStep = 0 }) { Text("取消") }
            }
        )
        3 -> AlertDialog(
            onDismissRequest = { rebuildConfirmStep = 0 },
            title = { Text("是否同时删除第一次总结结果？") },
            text = {
                Text(
                    "选择 \"是\"：会一并删除所有一次总结，再重新进行完整同步（耗费更多）。\n" +
                        "选择 \"否\"：保留现有一次总结，仅清理二次总结后再同步。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    rebuildConfirmStep = 0
                    onRequestRebuildSync(true)
                    onDismiss()
                }) { Text("是，一并删除") }
            },
            dismissButton = {
                TextButton(onClick = {
                    rebuildConfirmStep = 0
                    onRequestRebuildSync(false)
                    onDismiss()
                }) { Text("否，仅重建二次总结") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务设置") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ---- Speech Provider Section ----
                Text(
                    "语音识别服务",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "本地服务通过 Vosk 在设备上进行转录（需解码重采样）；\n云端服务通过讯飞直接上传音频文件，无需本地解码。",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                
                SpeechProvider.entries.forEach { provider ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { speechProvider = provider }
                    ) {
                        RadioButton(
                            selected = speechProvider == provider,
                            onClick = { speechProvider = provider }
                        )
                        Text(provider.displayName, fontSize = 14.sp)
                    }
                }

                // iFlytek credentials (only shown when cloud is selected)
                AnimatedVisibility(visible = speechProvider == SpeechProvider.CLOUD_IFLYTEK) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = iflytekAppId,
                            onValueChange = { iflytekAppId = it },
                            label = { Text("讯飞 APPID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = iflytekSecretKey,
                            onValueChange = { iflytekSecretKey = it },
                            label = { Text("讯飞 SecretKey") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "在讯飞开放平台 (xfyun.cn) 创建应用后获取",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // ---- LLM Provider Section ----
                Text(
                    "模型服务 (对话 & 翻译)",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "本地使用 LiteRT-LM (Qwen2.5-1.5B)，离线可用但能力有限；\n" +
                    "云端使用 Qwen3-Max (深度思考模型)，支持联网搜索，能力更强。",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                
                LlmProvider.entries.forEach { provider ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { llmProvider = provider }
                    ) {
                        RadioButton(
                            selected = llmProvider == provider,
                            onClick = { llmProvider = provider }
                        )
                        Text(provider.displayName, fontSize = 14.sp)
                    }
                }

                // Qwen Cloud API Key (only shown when cloud is selected)
                AnimatedVisibility(visible = llmProvider == LlmProvider.CLOUD_QWEN3_MAX) {
                    Column {
                        OutlinedTextField(
                            value = qwenCloudKey,
                            onValueChange = { qwenCloudKey = it },
                            label = { Text("Qwen3-Max API Key") },
                            placeholder = { Text("sk-...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "DashScope API Key，可在阿里云百炼平台获取",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                }

                // Tavily API Key (only shown for local LLM mode)
                AnimatedVisibility(visible = llmProvider == LlmProvider.LOCAL_LITERTLM) {
                    Column {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text(
                            "联网搜索 (Tavily)",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "本地模型需要 Tavily API 提供联网搜索。可在 tavily.com 免费获取。",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = tavilyKey,
                            onValueChange = { tavilyKey = it },
                            label = { Text("Tavily API Key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // ---- Local File Memory Section ----
                Text(
                    "本地文件记忆",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "减少本地文件记忆 token 消耗（测试版）",
                            fontSize = 14.sp
                        )
                        Text(
                            "开启后，每轮对话中仅首次发送时注入本地文件索引 JSON，后续消息不再重复携带。",
                            fontSize = 11.sp,
                            color = Color.Gray
                        )
                    }
                    Switch(
                        checked = reduceFileMemoryTokens,
                        onCheckedChange = { reduceFileMemoryTokens = it }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { rebuildConfirmStep = 1 },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("强制重建本地内容同步数据库（谨慎！）")
                }
                Text(
                    "会触发大量云端 API 调用。仅在同步结果明显异常时使用。",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                // Save all settings
                apiKeyStore.setSpeechProvider(speechProvider)
                apiKeyStore.setIFlytekAppId(iflytekAppId)
                apiKeyStore.setIFlytekSecretKey(iflytekSecretKey)
                apiKeyStore.setQwenCloudApiKey(qwenCloudKey)
                apiKeyStore.setTavilyApiKey(tavilyKey)
                apiKeyStore.setReduceFileMemoryTokens(reduceFileMemoryTokens)

                // Notify LLM provider change if changed
                if (llmProvider != currentLlmProvider) {
                    onLlmProviderChanged(llmProvider)
                }
                apiKeyStore.setLlmProvider(llmProvider)

                onDismiss()
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// ============================================================
// Chat Screen (updated for dual provider support)
// ============================================================

@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel,
    recordingsListViewModel: RecordingsListViewModel,
    onNavigateToPrevious: () -> Unit
) {
    var textState by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Auto-scroll to latest message
    LaunchedEffect(viewModel.messages.size) {
        if (viewModel.messages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.messages.size - 1)
        }
    }

    // Settings dialog
    if (showSettingsDialog) {
        SettingsDialog(
            apiKeyStore = viewModel.apiKeyStore,
            currentLlmProvider = viewModel.currentLlmProvider,
            onLlmProviderChanged = { provider ->
                viewModel.switchLlmProvider(provider)
            },
            onRequestRebuildSync = { alsoClearFirst ->
                recordingsListViewModel.rebuildContentSync(alsoClearFirstSummaries = alsoClearFirst)
            },
            onDismiss = {
                showSettingsDialog = false
                // Refresh state after settings change
                viewModel.onChatResumed()
            }
        )
    }

    val isCloudMode = viewModel.currentLlmProvider == LlmProvider.CLOUD_QWEN3_MAX
    val isSyncing = recordingsListViewModel.isSyncing
    val syncStatus = recordingsListViewModel.syncStatusMessage
    val hasQwenKey = viewModel.apiKeyStore.hasQwenCloudApiKey()
    val syncButtonEnabled = isCloudMode && hasQwenKey && !isSyncing

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top toolbar (raised surface card)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(CornerRadii.Large),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = Elevations.Card,
                shadowElevation = Elevations.Card
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Provider indicator chip
                        Surface(
                            shape = RoundedCornerShape(CornerRadii.Chip),
                            color = if (isCloudMode) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                text = if (isCloudMode) "☁ Qwen3-Max" else "📱 本地模型",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (isCloudMode) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Local content sync button: enabled only on Qwen3-Max with API key
                        FilledTonalButton(
                            onClick = { recordingsListViewModel.performLocalContentSync() },
                            enabled = syncButtonEnabled,
                            shape = RoundedCornerShape(CornerRadii.Medium),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            if (isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = if (isSyncing) "同步中" else "本地内容同步",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Settings button
                        FilledTonalIconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // Search toggle row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Web Search",
                            modifier = Modifier.size(18.dp),
                            tint = if (viewModel.isWebSearchEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "联网搜索",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (viewModel.isWebSearchEnabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Switch(
                            checked = viewModel.isWebSearchEnabled,
                            onCheckedChange = { enabled ->
                                if (!isCloudMode && enabled && !viewModel.apiKeyStore.hasTavilyApiKey()) {
                                    showSettingsDialog = true
                                } else {
                                    viewModel.isWebSearchEnabled = enabled
                                }
                            }
                        )
                    }

                    // File memory toggle (cloud mode only)
                    if (isCloudMode) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "File Memory",
                                modifier = Modifier.size(18.dp),
                                tint = if (viewModel.isFileMemoryEnabled) AccentSummary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "本地文件记忆",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (viewModel.isFileMemoryEnabled) AccentSummary
                                        else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Switch(
                                checked = viewModel.isFileMemoryEnabled,
                                onCheckedChange = { viewModel.isFileMemoryEnabled = it }
                            )
                        }
                    }
                }
            }

            // Sync status line (tiny, only when present)
            if (syncStatus != null) {
                Text(
                    text = syncStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
            }
            recordingsListViewModel.syncError?.let { err ->
                Text(
                    text = "同步错误: $err",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
            }

            // Status display (initializing or error)
            if (viewModel.isInitializing) {
                Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (isCloudMode) "正在连接云端服务..." else "Loading Qwen AI...")
                    }
                }
            } else if (viewModel.initError != null) {
                Text(
                    text = "Error: ${viewModel.initError}",
                    color = Color.Red,
                    modifier = Modifier.padding(16.dp)
                )
            } else if (!viewModel.isEngineActive) {
                 Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (isCloudMode) "请在设置中填入 API Key 以启用云端服务"
                        else "AI Engine is currently idle."
                    )
                }
            }

            // Message list — centered column capped on wide screens
            val chatMaxWidth = chatContentMaxWidth()
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (chatMaxWidth == androidx.compose.ui.unit.Dp.Unspecified) Modifier else Modifier.widthIn(max = chatMaxWidth))
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    items(viewModel.messages) { message ->
                        ChatBubble(message)
                    }
                }
            }

            // Searching indicator
            if (viewModel.isSearching) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "正在搜索...",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Input area — rounded card with text field + circular send button
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (chatMaxWidth == androidx.compose.ui.unit.Dp.Unspecified) Modifier else Modifier.widthIn(max = chatMaxWidth))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(CornerRadii.XLarge),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = Elevations.Card,
                    shadowElevation = Elevations.Card
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = textState,
                            onValueChange = { textState = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                Text(
                                    if (isCloudMode) "Ask Qwen3-Max..." else "Ask Qwen...",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            },
                            enabled = viewModel.isEngineActive && !viewModel.isSending,
                            shape = RoundedCornerShape(CornerRadii.Large),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                disabledContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            maxLines = 5
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        FilledIconButton(
                            onClick = {
                                viewModel.sendMessage(textState)
                                textState = ""
                            },
                            enabled = viewModel.isEngineActive && !viewModel.isSending && textState.isNotBlank(),
                            shape = CircleShape,
                            modifier = Modifier.size(46.dp)
                        ) {
                            if (viewModel.isSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        IconButton(
            onClick = onNavigateToPrevious,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .size(48.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Go to List",
                tint = Color.Gray
            )
        }
    }
}

// ============================================================
// Chat Bubble (updated with thinking content support)
// ============================================================

@Composable
fun ChatBubble(message: ChatMessage) {
    if (message.isSearchResult) {
        // Search result hint: centered, small, special color
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(CornerRadii.Chip),
                color = MaterialTheme.colorScheme.secondaryContainer,
                tonalElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    } else {
        val bubbleShape = if (message.isUser) {
            RoundedCornerShape(
                topStart = CornerRadii.Large,
                topEnd = CornerRadii.Large,
                bottomStart = CornerRadii.Large,
                bottomEnd = CornerRadii.Small
            )
        } else {
            RoundedCornerShape(
                topStart = CornerRadii.Large,
                topEnd = CornerRadii.Large,
                bottomStart = CornerRadii.Small,
                bottomEnd = CornerRadii.Large
            )
        }
        val bubbleColor = if (message.isUser) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
        val bubbleTextColor = if (message.isUser) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = if (message.isUser) Alignment.End else Alignment.Start
        ) {
            // Thinking content (collapsible, for Qwen3-Max deep thinking)
            if (message.thinkingText != null && !message.isUser) {
                var isThinkingExpanded by remember { mutableStateOf(false) }
                Surface(
                    modifier = Modifier.fillMaxWidth(ChatBubbleMaxWidthFraction),
                    shape = RoundedCornerShape(CornerRadii.Medium),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    tonalElevation = 0.dp
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { isThinkingExpanded = !isThinkingExpanded }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "思考过程",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontStyle = FontStyle.Italic
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = if (isThinkingExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle thinking",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        AnimatedVisibility(visible = isThinkingExpanded) {
                            Text(
                                text = message.thinkingText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Main message content
            Surface(
                modifier = Modifier.fillMaxWidth(ChatBubbleMaxWidthFraction),
                shape = bubbleShape,
                color = bubbleColor,
                tonalElevation = if (message.isUser) 0.dp else Elevations.Card,
                shadowElevation = if (message.isUser) Elevations.Card else 0.dp
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = bubbleTextColor
                )
            }
        }
    }
}

// ============================================================
// Recordings List Screen (unchanged)
// ============================================================

@Composable
fun RecordingsListScreen(
    modifier: Modifier = Modifier,
    recordings: List<RecordingItem>,
    playerViewModel: PlayerViewModel,
    transcribingFileId: Long?,
    summarizingFileId: Long?,
    onTranscribe: (RecordingItem, String) -> Unit,
    onView: (RecordingItem) -> Unit,
    onEdit: (RecordingItem) -> Unit,
    onDelete: (RecordingItem) -> Unit,
    onImportAudio: () -> Unit,
    onShareRecording: (RecordingItem) -> Unit,
    onSummarize: (RecordingItem) -> Unit,
    onViewSummary: (RecordingItem) -> Unit,
    onNavigateToPrevious: () -> Unit,
    onNavigateToNext: () -> Unit
) {
    val currentlyPlaying by playerViewModel.currentlyPlayingItem.collectAsState()
    val isPlaying by playerViewModel.isPlaying.collectAsState()

    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        val listMaxWidth = listContentMaxWidth()
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar (title + import button)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(CornerRadii.Large),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = Elevations.Card,
                shadowElevation = Elevations.Card
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "我的录音",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${recordings.size} 条记录",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onImportAudio,
                        shape = RoundedCornerShape(CornerRadii.Medium),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = "Import Audio",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("导入音频", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.TopCenter
            ) {
                if (recordings.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "还没有录音",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "前往录音界面开始你的第一条录音",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (listMaxWidth == androidx.compose.ui.unit.Dp.Unspecified) Modifier else Modifier.widthIn(max = listMaxWidth))
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 12.dp, horizontal = 0.dp)
                    ) {
                        items(items = recordings) { recording ->
                            RecordingListItem(
                                recording = recording,
                                isPlaying = currentlyPlaying?.id == recording.id && isPlaying,
                                isTranscribing = transcribingFileId == recording.id,
                                isSummarizing = summarizingFileId == recording.id,
                                onTranscribe = { lang -> onTranscribe(recording, lang) },
                                onView = { onView(recording) },
                                onEdit = { onEdit(recording) },
                                onDelete = { onDelete(recording) },
                                onPlay = { playerViewModel.playRecording(recording) },
                                onShare = { onShareRecording(recording) },
                                onSummarize = { onSummarize(recording) },
                                onViewSummary = { onViewSummary(recording) }
                            )
                        }
                    }
                }
            }

            if (currentlyPlaying != null) {
                PlayerControls(viewModel = playerViewModel, modifier = Modifier.padding(8.dp))
            }
        }

        IconButton(
            onClick = onNavigateToPrevious,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .size(48.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Go to Chat",
                tint = Color.Gray
            )
        }

        IconButton(
            onClick = onNavigateToNext,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .size(48.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Go to Recorder",
                tint = Color.Gray
            )
        }
    }
}

@Composable
fun RecordingListItem(
    recording: RecordingItem,
    isPlaying: Boolean,
    isTranscribing: Boolean,
    isSummarizing: Boolean,
    onTranscribe: (String) -> Unit,
    onView: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onSummarize: () -> Unit,
    onViewSummary: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CornerRadii.Large),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevations.Card),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Title row (leaves room for top-end action row)
                Text(
                    text = recording.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(end = 96.dp)
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Meta chips (duration + date)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetaChip(label = recording.duration)
                    MetaChip(
                        label = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                            .format(Date(recording.timestamp))
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Action row
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedButton(
                        onClick = onEdit,
                        enabled = !isTranscribing,
                        shape = RoundedCornerShape(CornerRadii.Medium),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Edit", style = MaterialTheme.typography.labelLarge)
                    }

                    if (recording.transcriptionPath == null) {
                        FilledTonalButton(
                            onClick = { onTranscribe("CN") },
                            enabled = !isTranscribing,
                            shape = RoundedCornerShape(CornerRadii.Medium),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) { Text("CN", style = MaterialTheme.typography.labelLarge) }
                        FilledTonalButton(
                            onClick = { onTranscribe("EN") },
                            enabled = !isTranscribing,
                            shape = RoundedCornerShape(CornerRadii.Medium),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) { Text("EN", style = MaterialTheme.typography.labelLarge) }
                    } else {
                        Button(
                            onClick = onView,
                            enabled = !isTranscribing,
                            shape = RoundedCornerShape(CornerRadii.Medium),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("View", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    IconButton(onClick = onShare, enabled = !isTranscribing) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    IconButton(onClick = onDelete, enabled = !isTranscribing) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }

                    if (isTranscribing) {
                        Spacer(Modifier.width(4.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            // Top-right corner: Play button + AI Summary button
            Row(
                modifier = Modifier.align(Alignment.TopEnd),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val hasSummary = recording.summaryText != null
                FilledTonalIconButton(
                    onClick = {
                        if (hasSummary) onViewSummary() else onSummarize()
                    },
                    enabled = !isTranscribing && !isSummarizing,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (hasSummary) AccentSummary.copy(alpha = 0.15f)
                                         else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (hasSummary) AccentSummary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.size(40.dp)
                ) {
                    if (isSummarizing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = "AI Summary",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                FilledTonalIconButton(
                    onClick = onPlay,
                    enabled = !isTranscribing,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer
                                         else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (isPlaying) MaterialTheme.colorScheme.onPrimaryContainer
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaChip(label: String) {
    Surface(
        shape = RoundedCornerShape(CornerRadii.Chip),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 0.dp
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

data class SubtitleLine(
    val text: String, 
    val start: Double, 
    val end: Double,
    val translation: String? = null
)

fun groupWordsIntoSentences(words: List<Word>, maxWordsPerLine: Int = 10): List<SubtitleLine> {
    if (words.isEmpty()) return emptyList()

    return words.chunked(maxWordsPerLine).map { chunk ->
        SubtitleLine(
            text = chunk.joinToString(" ") { it.text },
            start = chunk.first().start,
            end = chunk.last().end
        )
    }
}

@Composable
fun RenameDialog(
    recording: RecordingItem,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember { mutableStateOf(recording.fileName.substringBeforeLast('.')) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Recording") },
        text = {
            TextField(
                value = newName,
                onValueChange = { newName = it },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(newName) }) {
                Text("Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun DeleteConfirmationDialog(
    recording: RecordingItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Recording") },
        text = { Text("Are you sure you want to delete \"${recording.fileName}\"? This will also delete its transcription if it exists. This action cannot be undone.") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = Color.Red)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ============================================================
// AI Summary Status Dialog (shown during summarization)
// ============================================================

@Composable
fun SummaryStatusDialog(
    item: RecordingItem,
    isCompleted: Boolean?,
    progress: String,
    error: String?,
    onDismiss: () -> Unit
) {
    val title: String
    val text: @Composable () -> Unit

    when {
        error != null -> {
            title = "AI 总结失败"
            text = { Text("总结 \"${item.fileName}\" 时出错：\n$error") }
        }
        isCompleted == true -> {
            title = "AI 总结完成"
            text = { Text("\"${item.fileName}\" 的 AI 总结已生成。") }
        }
        else -> {
            title = "AI 总结中..."
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("正在使用 Qwen 3.5 Omni Plus 分析 \"${item.fileName}\"...")
                    Spacer(modifier = Modifier.height(12.dp))
                    CircularProgressIndicator()
                    if (progress.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (progress.length > 100) progress.takeLast(100) + "..." else progress,
                            fontSize = 12.sp,
                            color = Color.Gray,
                            maxLines = 3
                        )
                    }
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (isCompleted != null || error != null) onDismiss() },
        title = { Text(title) },
        text = text,
        confirmButton = {
            if (isCompleted != null || error != null) {
                TextButton(onClick = onDismiss) {
                    Text("OK")
                }
            }
        }
    )
}

// ============================================================
// AI Summary Viewer/Editor Screen
// ============================================================

@Composable
fun SummaryViewerScreen(
    recording: RecordingItem,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    onRegenerate: () -> Unit
) {
    var editedText by remember(recording.id, recording.summaryText) {
        mutableStateOf(recording.summaryText ?: "")
    }
    var isEditing by remember { mutableStateOf(false) }
    var hasChanges by remember { mutableStateOf(false) }
    var showRegenerateConfirm by remember { mutableStateOf(false) }

    if (showRegenerateConfirm) {
        AlertDialog(
            onDismissRequest = { showRegenerateConfirm = false },
            title = { Text("重新生成总结") },
            text = { Text("确定要重新生成 AI 总结吗？当前总结内容将被覆盖。") },
            confirmButton = {
                TextButton(onClick = {
                    showRegenerateConfirm = false
                    onRegenerate()
                }) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRegenerateConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top bar card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(CornerRadii.Large),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = Elevations.Card,
            shadowElevation = Elevations.Card
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    onClick = {
                        if (hasChanges) onSave(editedText)
                        onBack()
                    },
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = recording.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            tint = AccentSummary,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "AI 总结 · Qwen 3.5 Omni Plus",
                            style = MaterialTheme.typography.labelMedium,
                            color = AccentSummary,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }

                // Edit/Save toggle
                OutlinedButton(
                    onClick = {
                        if (isEditing && hasChanges) {
                            onSave(editedText)
                            hasChanges = false
                        }
                        isEditing = !isEditing
                    },
                    shape = RoundedCornerShape(CornerRadii.Medium),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isEditing) "保存" else "编辑",
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                // Regenerate button
                FilledTonalIconButton(
                    onClick = { showRegenerateConfirm = true },
                    shape = CircleShape,
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = AccentSummary.copy(alpha = 0.15f),
                        contentColor = AccentSummary
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = "Regenerate",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Content area
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .padding(bottom = 12.dp),
            shape = RoundedCornerShape(CornerRadii.Large),
            elevation = CardDefaults.cardElevation(defaultElevation = Elevations.Card),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            if (isEditing) {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = {
                        editedText = it
                        hasChanges = true
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = RoundedCornerShape(CornerRadii.Large),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp)
                ) {
                    Text(
                        text = editedText.ifBlank { "暂无总结内容" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (editedText.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun TranscriptionViewerScreen(
    recording: RecordingItem,
    recordingsListViewModel: RecordingsListViewModel,
    playerViewModel: PlayerViewModel,
    chatViewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val currentPosition by playerViewModel.currentPosition.collectAsState()
    var subtitleLines by remember { mutableStateOf<List<SubtitleLine>>(emptyList()) }
    val lazyListState = rememberLazyListState()
    var autoScrollEnabled by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val application = context.applicationContext as Application
    val viewerViewModel: TranscriptionViewerViewModel = viewModel(
        key = "transcription_viewer_${recording.id}",
        factory = TranscriptionViewerViewModelFactory(
            application,
            recording.filePath,
            recording.transcriptionPath
        )
    )

    // Force reload transcription data every time screen is shown
    // (handles re-transcription where file content changed but path stayed the same,
    //  and handles ViewModel being cached in ViewModelStore across navigations)
    LaunchedEffect(recording.id, recording.transcriptionPath) {
        viewerViewModel.loadRecording(recording.filePath, recording.transcriptionPath)
    }

    // 启动播放
    LaunchedEffect(recording.id) {
        playerViewModel.playRecording(recording)
    }

    val sentences by viewerViewModel.sentences.collectAsState()
    val isTranslating by viewerViewModel.isTranslating.collectAsState()
    var showTranslationConfirmDialog by remember { mutableStateOf(false) }

    // 监听 ViewModel 中的句子变化（包括翻译同步）
    LaunchedEffect(sentences) {
        subtitleLines = sentences.map { 
            SubtitleLine(it.text, it.start, it.end, it.translation) 
        }
    }

    val currentLineIndex = subtitleLines.indexOfFirst { (currentPosition / 1000.0) in it.start..it.end }

    LaunchedEffect(currentLineIndex) {
        if (autoScrollEnabled && currentLineIndex != -1) {
            lazyListState.animateScrollToItem(max(0, currentLineIndex - 1))
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Transcription") },
            text = { Text("Are you sure you want to delete this transcription? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        recordingsListViewModel.deleteTranscription(recording)
                        showDeleteDialog = false
                        onBack()
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Translation confirmation dialog — only needed for local LLM mode (engine conflict)
    if (showTranslationConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showTranslationConfirmDialog = false },
            title = { Text("AI 调度提示") },
            text = { Text("启动翻译需要暂时关闭聊天功能以调度 AI 引擎，是否确认暂时关闭聊天界面以进行翻译？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTranslationConfirmDialog = false
                        chatViewModel.forceCloseForExternalUse()
                        viewerViewModel.translateAll()
                    }
                ) {
                    Text("确认")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTranslationConfirmDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Top bar card
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(CornerRadii.Large),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = Elevations.Card,
            shadowElevation = Elevations.Card
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalIconButton(
                        onClick = onBack,
                        shape = CircleShape,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Text(
                        text = recording.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )

                    // Translate button
                    FilledTonalIconButton(
                        onClick = {
                            if (viewerViewModel.isCloudLlmMode()) {
                                viewerViewModel.translateAll()
                            } else if (chatViewModel.isEngineActive) {
                                showTranslationConfirmDialog = true
                            } else {
                                viewerViewModel.translateAll()
                            }
                        },
                        enabled = !isTranslating,
                        shape = CircleShape,
                        modifier = Modifier.size(40.dp)
                    ) {
                        if (isTranslating) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Translate, contentDescription = "Translate", modifier = Modifier.size(20.dp))
                        }
                    }

                    // Delete button
                    FilledTonalIconButton(
                        onClick = { showDeleteDialog = true },
                        shape = CircleShape,
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp))
                    }
                }

                // Auto-scroll toggle row
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "自动跟随",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Switch(checked = autoScrollEnabled, onCheckedChange = { autoScrollEnabled = it })
                }
            }
        }

        // Player controls
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            shape = RoundedCornerShape(CornerRadii.Large),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = Elevations.Card,
            shadowElevation = Elevations.Card
        ) {
            PlayerControls(
                viewModel = playerViewModel,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        // Transcript card
        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .padding(bottom = 12.dp),
            shape = RoundedCornerShape(CornerRadii.Large),
            elevation = CardDefaults.cardElevation(defaultElevation = Elevations.Card),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            if (subtitleLines.isNotEmpty()) {
                LazyColumn(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), state = lazyListState) {
                    itemsIndexed(subtitleLines) { index, line ->
                        val isCurrentLine = index == currentLineIndex
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(CornerRadii.Small))
                                .background(
                                    if (isCurrentLine) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                    else Color.Transparent
                                )
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = line.text,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isCurrentLine) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (isCurrentLine) FontWeight.Medium else FontWeight.Normal
                            )
                            if (line.translation != null) {
                                Text(
                                    text = line.translation,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }
                        if (index < subtitleLines.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 2.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "暂无转写内容",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerControls(viewModel: PlayerViewModel, modifier: Modifier = Modifier) {
    val isPlaying by viewModel.isPlaying.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val duration = viewModel.duration

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = currentPosition.toFloat(),
            onValueChange = { viewModel.seekTo(it.toInt()) },
            valueRange = 0f..max(1f, duration.toFloat()),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatMillis(currentPosition.toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatMillis(duration.toLong()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { viewModel.rewind() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Rewind",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            FilledIconButton(
                onClick = { viewModel.playPause() },
                shape = CircleShape,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(onClick = { viewModel.forward() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Forward",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

fun formatMillis(millis: Long): String {
    return String.format(Locale.US, "%02d:%02d",
        TimeUnit.MILLISECONDS.toMinutes(millis) % TimeUnit.HOURS.toMinutes(1),
        TimeUnit.MILLISECONDS.toSeconds(millis) % TimeUnit.MINUTES.toSeconds(1))
}

@Composable
fun RecordingScreen(
    modifier: Modifier = Modifier,
    isRecording: Boolean,
    isPaused: Boolean,
    duration: String,
    transcriptionState: LiveTranscriptionState,
    transcriptionLanguage: String,
    recentReplays: List<RecordingItem>,
    isSavingReplay: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onTriggerReplay: () -> Unit,
    onStartTranscription: (String) -> Unit,
    onStopTranscription: () -> Unit,
    onToggleLanguage: () -> Unit,
    playerViewModel: PlayerViewModel,
    onNavigateToNext: () -> Unit
) {
    val currentlyPlayingItem by playerViewModel.currentlyPlayingItem.collectAsState()
    val isPlayerPlaying by playerViewModel.isPlaying.collectAsState()
    val transcriptionActive = transcriptionState.isActive || transcriptionState.isModelLoading

    val statusLabel = when {
        !isRecording -> "准备录音"
        isPaused -> "已暂停"
        else -> "正在录音"
    }
    val statusColor = when {
        !isRecording -> MaterialTheme.colorScheme.onSurfaceVariant
        isPaused -> MaterialTheme.colorScheme.tertiary
        else -> AccentRecord
    }

    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {

        if (!transcriptionActive) {
            // ══ 正常模式：按钮居中显示 ═══════════════════════════════════════
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // 估算核心内容高度，动态计算顶部间距使按钮保持居中
                val coreEst = if (isRecording) 430.dp else 310.dp
                val topSpace = ((maxHeight - coreEst) / 2).coerceAtLeast(24.dp)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(topSpace))

                    // Status row
                    RecordingStatusRow(isRecording, isPaused, statusLabel, statusColor)

                    if (isRecording) {
                        Spacer(Modifier.height(12.dp))
                        Text(duration,
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.Light,
                            color = MaterialTheme.colorScheme.onBackground)
                    }

                    Spacer(Modifier.height(if (isRecording) 40.dp else 48.dp))

                    // Main button (full size)
                    RecordMainButton(
                        isRecording = isRecording,
                        isPaused = isPaused,
                        compact = false,
                        onStartClick = onStartClick,
                        onStopClick = onStopClick
                    )

                    Spacer(Modifier.height(28.dp))

                    // Secondary controls
                    if (isRecording) {
                        RecordingControlsRow(
                            isPaused = isPaused,
                            isSavingReplay = isSavingReplay,
                            transcriptionActive = false,
                            transcriptionLanguage = transcriptionLanguage,
                            onPauseClick = onPauseClick,
                            onResumeClick = onResumeClick,
                            onTriggerReplay = onTriggerReplay,
                            onStartTranscription = { onStartTranscription(transcriptionLanguage) },
                            onStopTranscription = onStopTranscription,
                            onToggleLanguage = onToggleLanguage
                        )
                    } else {
                        Text("轻触开始录音",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    // Instant replay cards
                    if (recentReplays.isNotEmpty()) {
                        Spacer(Modifier.height(24.dp))
                        ReplaySection(
                            replays = recentReplays,
                            currentlyPlayingItem = currentlyPlayingItem,
                            isPlayerPlaying = isPlayerPlaying,
                            playerViewModel = playerViewModel
                        )
                    }

                    Spacer(Modifier.height(56.dp))
                }
            }
        } else {
            // ══ 转录模式：顶部字幕区 + 紧凑按钮 ═════════════════════════════
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(20.dp))

                // Compact status + timer
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RecordingStatusRow(isRecording, isPaused, statusLabel, statusColor)
                    if (isRecording) {
                        Spacer(Modifier.width(4.dp))
                        Text(duration,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Light,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Subtitle area
                LiveSubtitleCard(
                    state = transcriptionState,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 260.dp)
                )

                Spacer(Modifier.height(16.dp))

                // Compact button
                RecordMainButton(
                    isRecording = isRecording,
                    isPaused = isPaused,
                    compact = true,
                    onStartClick = onStartClick,
                    onStopClick = onStopClick
                )

                Spacer(Modifier.height(16.dp))

                // Controls
                if (isRecording) {
                    RecordingControlsRow(
                        isPaused = isPaused,
                        isSavingReplay = isSavingReplay,
                        transcriptionActive = true,
                        transcriptionLanguage = transcriptionLanguage,
                        onPauseClick = onPauseClick,
                        onResumeClick = onResumeClick,
                        onTriggerReplay = onTriggerReplay,
                        onStartTranscription = { onStartTranscription(transcriptionLanguage) },
                        onStopTranscription = onStopTranscription,
                        onToggleLanguage = onToggleLanguage
                    )
                }

                // Instant replay cards
                if (recentReplays.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    ReplaySection(
                        replays = recentReplays,
                        currentlyPlayingItem = currentlyPlayingItem,
                        isPlayerPlaying = isPlayerPlaying,
                        playerViewModel = playerViewModel
                    )
                }

                Spacer(Modifier.height(56.dp))
            }
        }

        // Navigation arrow → RecordingsList
        IconButton(
            onClick = onNavigateToNext,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 8.dp)
                .size(48.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Go to List",
                tint = Color.Gray)
        }
    }
}

@Composable
private fun RecordingStatusRow(
    isRecording: Boolean,
    isPaused: Boolean,
    statusLabel: String,
    statusColor: androidx.compose.ui.graphics.Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (isRecording && !isPaused) {
            val inf = rememberInfiniteTransition(label = "recDot")
            val dotAlpha by inf.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
                label = "dotAlpha"
            )
            Box(Modifier.size(10.dp).clip(CircleShape).background(AccentRecord.copy(alpha = dotAlpha)))
        }
        Text(statusLabel, style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium, color = statusColor)
    }
}

@Composable
private fun RecordMainButton(
    isRecording: Boolean,
    isPaused: Boolean,
    compact: Boolean,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    val outerSize = if (compact) 140.dp else 200.dp
    val btnSize  = if (compact) 100.dp else 140.dp
    val iconSize = if (compact) 38.dp  else 48.dp
    val ringBase = if (compact) 88f    else 112f

    Box(Modifier.size(outerSize), contentAlignment = Alignment.Center) {
        if (isRecording && !isPaused) {
            val inf = rememberInfiniteTransition(label = "ring")
            val ringScale by inf.animateFloat(
                initialValue = 0.95f, targetValue = 1.15f,
                animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
                label = "ringScale"
            )
            val ringAlpha by inf.animateFloat(
                initialValue = 0.30f, targetValue = 0.05f,
                animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
                label = "ringAlpha"
            )
            Box(Modifier.size((ringBase * ringScale).dp).clip(CircleShape)
                .background(AccentRecord.copy(alpha = ringAlpha)))
        }
        val mainColor   = if (!isRecording) AccentRecord else MaterialTheme.colorScheme.surface
        val mainContent = if (!isRecording) MaterialTheme.colorScheme.onPrimary else AccentRecord
        Surface(
            onClick = if (isRecording) onStopClick else onStartClick,
            modifier = Modifier.size(btnSize).shadow(Elevations.RaisedCard, CircleShape),
            shape = CircleShape,
            color = mainColor,
            border = if (isRecording) androidx.compose.foundation.BorderStroke(3.dp, AccentRecord) else null
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                    contentDescription = if (isRecording) "Stop" else "Record",
                    tint = mainContent,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

@Composable
private fun RecordingControlsRow(
    isPaused: Boolean,
    isSavingReplay: Boolean,
    transcriptionActive: Boolean,
    transcriptionLanguage: String,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onTriggerReplay: () -> Unit,
    onStartTranscription: () -> Unit,
    onStopTranscription: () -> Unit,
    onToggleLanguage: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Pause / Resume
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledTonalIconButton(
                onClick = if (isPaused) onResumeClick else onPauseClick,
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Icon(
                    imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(if (isPaused) "继续" else "暂停",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Instant Replay
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledTonalIconButton(
                onClick = onTriggerReplay,
                enabled = !isSavingReplay && !isPaused,
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            ) {
                if (isSavingReplay) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.FiberManualRecord,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("15s回放",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Live Transcription toggle
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FilledTonalIconButton(
                onClick = if (transcriptionActive) onStopTranscription else onStartTranscription,
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (transcriptionActive)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (transcriptionActive)
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(Icons.Filled.Translate,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(4.dp))
            Text(if (transcriptionActive) "关闭转录" else "实时转录",
                style = MaterialTheme.typography.labelSmall,
                color = if (transcriptionActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // Language toggle – only when transcription is completely inactive
    if (!transcriptionActive) {
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("转录语言:", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(
                onClick = onToggleLanguage,
                shape = RoundedCornerShape(CornerRadii.Chip),
                color = if (transcriptionLanguage == "CN")
                    MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                tonalElevation = 2.dp
            ) {
                Text(
                    text = if (transcriptionLanguage == "CN") "中文" else "English",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (transcriptionLanguage == "CN")
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReplaySection(
    replays: List<RecordingItem>,
    currentlyPlayingItem: RecordingItem?,
    isPlayerPlaying: Boolean,
    playerViewModel: PlayerViewModel
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(3.dp, 14.dp).background(
            MaterialTheme.colorScheme.tertiary, RoundedCornerShape(2.dp)))
        Text("即时回放", style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface)
    }
    Spacer(Modifier.height(8.dp))
    replays.forEach { item ->
        InstantReplayCard(
            item = item,
            isPlaying = currentlyPlayingItem?.id == item.id && isPlayerPlaying,
            isCurrent = currentlyPlayingItem?.id == item.id,
            onPlayPause = { playerViewModel.playRecording(item) },
            onStop = { playerViewModel.stopPlayback() }
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LiveSubtitleCard(
    state: LiveTranscriptionState,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val displayText = buildString {
        if (state.finalText.isNotEmpty()) append(state.finalText)
        if (state.partialText.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append(state.partialText)
        }
    }
    LaunchedEffect(displayText) {
        if (scrollState.maxValue > 0) scrollState.animateScrollTo(scrollState.maxValue)
    }
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(CornerRadii.Large),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        if (state.isModelLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Text("正在加载语音模型…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = if (displayText.isNotEmpty()) displayText else "等待语音输入…",
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 24.sp,
                    color = if (displayText.isNotEmpty())
                        MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
private fun InstantReplayCard(
    item: RecordingItem,
    isPlaying: Boolean,
    isCurrent: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit
) {
    val accentColor = if (isCurrent) MaterialTheme.colorScheme.tertiary
    else MaterialTheme.colorScheme.outline
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CornerRadii.Medium),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent)
                MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevations.Card)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(3.dp, 32.dp).background(accentColor, RoundedCornerShape(2.dp)))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = item.duration,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalIconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(36.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (isPlaying)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(18.dp)
                )
            }
            if (isCurrent) {
                FilledTonalIconButton(
                    onClick = onStop,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = "结束",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun PagerDotIndicator(
    pageCount: Int,
    currentPage: Int,
    onDotClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = Elevations.Pill,
        shadowElevation = Elevations.Pill
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            repeat(pageCount) { index ->
                val selected = index == currentPage
                val width by animateFloatAsState(
                    targetValue = if (selected) 22f else 8f,
                    animationSpec = tween(durationMillis = 220),
                    label = "dotWidth"
                )
                Box(
                    modifier = Modifier
                        .height(8.dp)
                        .width(width.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                        .clickable { onDotClick(index) }
                )
            }
        }
    }
}
