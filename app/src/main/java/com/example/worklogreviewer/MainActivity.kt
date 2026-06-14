package com.example.worklogreviewer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.worklogreviewer.ui.theme.WorkLogReviewerTheme
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.*
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

data class ChatMessage(val text: String, val isUser: Boolean)

enum class WeeklyPhase { IDLE, ANALYZING, QUESTIONING, GENERATING, DONE }
enum class MemoReviewPhase { FILE_LIST, REVIEWING, DONE }

class MainActivity : ComponentActivity() {

    private val DEFAULT_VAULT = "${Environment.getExternalStorageDirectory()}/Documents/Obsidian/obsidian"
    private val REVIEWED_MARKER = "%%REVIEWED%%"
    private val REVIEW_END_MARKER = "%%REVIEW_END%%"

    private val PREFS_NAME = "WorkLogReviewerPrefs"
    private val PREF_MEMORY_ENABLED = "memory_enabled"
    private val PREF_LOG_FOLDER = "log_folder"
    private val PREF_REVIEW_FILE = "review_file"
    private val PREF_MEMORY_FILE = "memory_file"

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Environment.isExternalStorageManager()) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
            )
            storagePermissionLauncher.launch(intent)
        }

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val autoReview = intent.getBooleanExtra("AUTO_REVIEW", false)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        setContent {
            WorkLogReviewerTheme {
                var selectedTab by remember { mutableIntStateOf(0) }
                var memoryEnabled by remember {
                    mutableStateOf(prefs.getBoolean(PREF_MEMORY_ENABLED, true))
                }
                var logFolder by remember {
                    mutableStateOf(prefs.getString(PREF_LOG_FOLDER, DEFAULT_VAULT) ?: DEFAULT_VAULT)
                }
                var reviewFilePath by remember {
                    mutableStateOf(prefs.getString(PREF_REVIEW_FILE, "$DEFAULT_VAULT/REVIEW.md") ?: "$DEFAULT_VAULT/REVIEW.md")
                }
                var memoryFilePath by remember {
                    mutableStateOf(prefs.getString(PREF_MEMORY_FILE, "$DEFAULT_VAULT/memory.md") ?: "$DEFAULT_VAULT/memory.md")
                }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                icon = { Icon(Icons.Default.RateReview, contentDescription = "レビュー") },
                                label = { Text("レビュー") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                icon = { Icon(Icons.Default.CalendarMonth, contentDescription = "週次") },
                                label = { Text("週次") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 2,
                                onClick = { selectedTab = 2 },
                                icon = { Icon(Icons.Default.Description, contentDescription = "メモ") },
                                label = { Text("メモ") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 3,
                                onClick = { selectedTab = 3 },
                                icon = { Icon(Icons.Default.Chat, contentDescription = "チャット") },
                                label = { Text("チャット") }
                            )
                            NavigationBarItem(
                                selected = selectedTab == 4,
                                onClick = { selectedTab = 4 },
                                icon = { Icon(Icons.Default.Settings, contentDescription = "設定") },
                                label = { Text("設定") }
                            )
                        }
                    }
                ) { paddingValues ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        when (selectedTab) {
                            0 -> ReviewScreen(autoReview, memoryEnabled, logFolder, reviewFilePath, memoryFilePath)
                            1 -> WeeklyReviewScreen(memoryEnabled, logFolder, reviewFilePath, memoryFilePath)
                            2 -> MemoReviewScreen(memoryEnabled, logFolder, reviewFilePath, memoryFilePath)
                            3 -> ChatScreen(memoryEnabled, reviewFilePath, memoryFilePath)
                            4 -> SettingsScreen(
                                memoryEnabled = memoryEnabled,
                                logFolder = logFolder,
                                reviewFilePath = reviewFilePath,
                                memoryFilePath = memoryFilePath,
                                onMemoryToggle = { enabled ->
                                    memoryEnabled = enabled
                                    prefs.edit().putBoolean(PREF_MEMORY_ENABLED, enabled).apply()
                                },
                                onSavePaths = { lf, rf, mf ->
                                    logFolder = lf
                                    reviewFilePath = rf
                                    memoryFilePath = mf
                                    prefs.edit()
                                        .putString(PREF_LOG_FOLDER, lf)
                                        .putString(PREF_REVIEW_FILE, rf)
                                        .putString(PREF_MEMORY_FILE, mf)
                                        .apply()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────
    // 設定画面
    // ─────────────────────────────────────────
    @Composable
    fun SettingsScreen(
        memoryEnabled: Boolean,
        logFolder: String,
        reviewFilePath: String,
        memoryFilePath: String,
        onMemoryToggle: (Boolean) -> Unit,
        onSavePaths: (String, String, String) -> Unit
    ) {
        var logFolderInput by remember(logFolder) { mutableStateOf(logFolder) }
        var reviewFileInput by remember(reviewFilePath) { mutableStateOf(reviewFilePath) }
        var memoryFileInput by remember(memoryFilePath) { mutableStateOf(memoryFilePath) }
        var saveStatus by remember { mutableStateOf("") }

        val memoryFile = File(memoryFilePath)
        val memoryExists = memoryFile.exists()
        val memorySize = if (memoryExists) runCatching { memoryFile.readText().lines().size }.getOrDefault(0) else 0

        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("設定", style = MaterialTheme.typography.headlineMedium)
            HorizontalDivider()

            // ── パス設定 ──
            Text("📁 パス設定", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = logFolderInput,
                onValueChange = { logFolderInput = it },
                label = { Text("作業ログフォルダ") },
                placeholder = { Text("/storage/emulated/0/Documents/...") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("YYYYMMDD作業log.md が置かれているフォルダ") }
            )

            OutlinedTextField(
                value = reviewFileInput,
                onValueChange = { reviewFileInput = it },
                label = { Text("REVIEW.md のパス") },
                placeholder = { Text("/storage/emulated/0/Documents/.../REVIEW.md") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("評価基準ファイルのフルパス") }
            )

            OutlinedTextField(
                value = memoryFileInput,
                onValueChange = { memoryFileInput = it },
                label = { Text("memory.md のパス") },
                placeholder = { Text("/storage/emulated/0/Documents/.../memory.md") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("長期メモリファイルのフルパス") }
            )

            if (saveStatus.isNotEmpty()) {
                Text(
                    text = saveStatus,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(
                onClick = {
                    onSavePaths(
                        logFolderInput.trim(),
                        reviewFileInput.trim(),
                        memoryFileInput.trim()
                    )
                    saveStatus = "✅ 保存しました"
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("パスを保存") }

            HorizontalDivider()

            // ── メモリ設定 ──
            Text("🧠 長期メモリ", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("メモリ参照を使用する", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (memoryEnabled) "レビュー・チャットのコンテキストにmemory.mdを含めます"
                        else "memory.mdをコンテキストに含めません",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(checked = memoryEnabled, onCheckedChange = onMemoryToggle)
            }

            if (memoryExists) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "memory.md の状態",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text("${memorySize}行 記録済み")
                        Text(
                            memoryFilePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                Text(
                    "memory.md はまだ作成されていません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }

    // ─────────────────────────────────────────
    // メモリ関連
    // ─────────────────────────────────────────
    private fun loadMemory(memoryFilePath: String): String {
        val file = File(memoryFilePath)
        return if (file.exists()) file.readText() else ""
    }

    private fun appendToMemory(content: String, source: String, memoryFilePath: String) {
        val file = File(memoryFilePath)
        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val entry = "\n## $date（$source）\n$content\n"
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.writeText("# 長期メモリ\n$entry")
        } else {
            file.appendText(entry)
        }
    }

    private suspend fun extractMemoryCandidate(content: String): String {
        return withContext(Dispatchers.IO) {
            val model = Generation.getClient()
            val prompt = """
以下の内容から、長期的に記憶すべき重要な洞察・パターン・教訓を箇条書き3点以内で抽出してください。
箇条書きのみ出力してください。

$content
            """.trimIndent()
            ask(model, prompt)
        }
    }

    private fun buildMemoryContext(enabled: Boolean, memoryFilePath: String): String {
        if (!enabled) return ""
        val memory = loadMemory(memoryFilePath)
        return if (memory.isNotEmpty()) memory else ""
    }

    // ─────────────────────────────────────────
    // メモリ確認ダイアログ
    // ─────────────────────────────────────────
    @Composable
    fun MemoryDialog(
        candidate: String,
        onSave: () -> Unit,
        onDismiss: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("🧠 メモリ候補") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "以下の内容をmemory.mdに保存しますか？",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(candidate)
                }
            },
            confirmButton = { TextButton(onClick = onSave) { Text("保存する") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("スキップ") } }
        )
    }

    // ─────────────────────────────────────────
    // ログ抽出（共通）
    // ─────────────────────────────────────────
    private fun extractLogContent(raw: String): String {
        return if (raw.startsWith(REVIEWED_MARKER)) {
            val idx = raw.indexOf(REVIEW_END_MARKER)
            if (idx >= 0) {
                raw.substring(idx + REVIEW_END_MARKER.length).trimStart()
            } else {
                raw.substringAfter(REVIEWED_MARKER).trimStart()
                    .substringAfter("---").trimStart()
                    .let {
                        val i = it.indexOf("---")
                        if (i >= 0) it.substring(i + 3).trimStart() else it
                    }
            }
        } else raw
    }

    // ─────────────────────────────────────────
    // 日次レビュー画面
    // ─────────────────────────────────────────
    @Composable
    fun ReviewScreen(
        autoReview: Boolean,
        memoryEnabled: Boolean,
        logFolder: String,
        reviewFilePath: String,
        memoryFilePath: String
    ) {
        var selectedDate by remember { mutableStateOf(LocalDate.now()) }
        var status by remember { mutableStateOf("日付を選んでレビューを開始してください") }
        var isProcessing by remember { mutableStateOf(false) }
        var summary by remember { mutableStateOf("") }
        var improvements by remember { mutableStateOf("") }
        var actions by remember { mutableStateOf("") }
        var showOverwriteDialog by remember { mutableStateOf(false) }
        var memoryCandidate by remember { mutableStateOf("") }
        var showMemoryDialog by remember { mutableStateOf(false) }
        var isExtractingMemory by remember { mutableStateOf(false) }

        val scope = rememberCoroutineScope()
        val scrollState = rememberScrollState()

        fun clearResults() {
            summary = ""; improvements = ""; actions = ""; memoryCandidate = ""
            status = "日付を選んでレビューを開始してください"
        }

        suspend fun startReview() {
            isProcessing = true
            summary = ""; improvements = ""; actions = ""; memoryCandidate = ""
            status = "処理中... (1/3) 総評を生成中"
            val result = runReview(
                date = selectedDate,
                memoryEnabled = memoryEnabled,
                logFolder = logFolder,
                reviewFilePath = reviewFilePath,
                memoryFilePath = memoryFilePath,
                onProgress = { status = it },
                onSummary = { summary = it },
                onImprovements = { improvements = it },
                onActions = { actions = it }
            )
            status = result
            isProcessing = false
        }

        LaunchedEffect(autoReview) {
            if (autoReview) {
                selectedDate = LocalDate.now().minusDays(1)
                val dateStr = selectedDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                val logFile = File("$logFolder/${dateStr}作業log.md")
                if (logFile.exists() && !logFile.readText().startsWith(REVIEWED_MARKER)) {
                    startReview()
                } else {
                    status = "昨日のログは既にレビュー済みです"
                }
            }
        }

        if (showOverwriteDialog) {
            AlertDialog(
                onDismissRequest = { showOverwriteDialog = false },
                title = { Text("レビュー済みです") },
                text = { Text("このログは既にレビューされています。上書きしますか？") },
                confirmButton = {
                    TextButton(onClick = {
                        showOverwriteDialog = false
                        scope.launch { startReview() }
                    }) { Text("上書きする") }
                },
                dismissButton = {
                    TextButton(onClick = { showOverwriteDialog = false }) { Text("キャンセル") }
                }
            )
        }

        if (showMemoryDialog && memoryCandidate.isNotEmpty()) {
            MemoryDialog(
                candidate = memoryCandidate,
                onSave = {
                    appendToMemory(memoryCandidate, "日次レビュー", memoryFilePath)
                    showMemoryDialog = false
                    status = "✅ memory.md に保存しました"
                },
                onDismiss = { showMemoryDialog = false }
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Work Log Reviewer", style = MaterialTheme.typography.headlineMedium)
            if (memoryEnabled) {
                Text("🧠 メモリ参照: ON", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = "対象日: ${selectedDate.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))}",
                style = MaterialTheme.typography.bodyLarge
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { selectedDate = selectedDate.minusDays(1); clearResults() }) { Text("◀ 前日") }
                Button(onClick = { selectedDate = LocalDate.now(); clearResults() }) { Text("今日") }
                Button(onClick = { selectedDate = selectedDate.plusDays(1); clearResults() }) { Text("翌日 ▶") }
            }
            Button(
                onClick = {
                    val dateStr = selectedDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                    val logFile = File("$logFolder/${dateStr}作業log.md")
                    if (logFile.exists() && logFile.readText().startsWith(REVIEWED_MARKER)) {
                        showOverwriteDialog = true
                    } else {
                        scope.launch { startReview() }
                    }
                },
                enabled = !isProcessing,
                modifier = Modifier.fillMaxWidth()
            ) { Text("レビュー開始") }
            if (isProcessing) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(text = status)
            if (summary.isNotEmpty()) {
                HorizontalDivider()
                Text("### 総評", style = MaterialTheme.typography.titleMedium)
                Text(text = summary)
            }
            if (improvements.isNotEmpty()) {
                HorizontalDivider()
                Text("### 改善提案", style = MaterialTheme.typography.titleMedium)
                Text(text = improvements)
            }
            if (actions.isNotEmpty()) {
                HorizontalDivider()
                Text("### 次のアクション", style = MaterialTheme.typography.titleMedium)
                Text(text = actions)
            }
            if (summary.isNotEmpty()) {
                HorizontalDivider()
                Button(
                    onClick = {
                        isExtractingMemory = true
                        scope.launch {
                            memoryCandidate = extractMemoryCandidate("総評: $summary\n改善提案: $improvements\n次のアクション: $actions")
                            isExtractingMemory = false
                            showMemoryDialog = true
                        }
                    },
                    enabled = !isExtractingMemory,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (isExtractingMemory) "🧠 抽出中..." else "🧠 メモリ候補を確認") }
            }
        }
    }

    // ─────────────────────────────────────────
    // 週次レビュー画面
    // ─────────────────────────────────────────
    @Composable
    fun WeeklyReviewScreen(
        memoryEnabled: Boolean,
        logFolder: String,
        reviewFilePath: String,
        memoryFilePath: String
    ) {
        var weekStart by remember {
            mutableStateOf(LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
        }
        var phase by remember { mutableStateOf(WeeklyPhase.IDLE) }
        var status by remember { mutableStateOf("") }
        var keyPoints by remember { mutableStateOf("") }
        var questions by remember { mutableStateOf<List<String>>(emptyList()) }
        var answers by remember { mutableStateOf<List<String>>(emptyList()) }
        var currentInput by remember { mutableStateOf("") }
        var currentQuestionIndex by remember { mutableIntStateOf(0) }
        var finalReport by remember { mutableStateOf("") }
        var memoryCandidate by remember { mutableStateOf("") }
        var showMemoryDialog by remember { mutableStateOf(false) }
        var isExtractingMemory by remember { mutableStateOf(false) }

        val scope = rememberCoroutineScope()
        val weekEnd = weekStart.plusDays(6)
        val fmtDisplay = DateTimeFormatter.ofPattern("MM/dd")

        fun reset() {
            phase = WeeklyPhase.IDLE; status = ""; keyPoints = ""
            questions = emptyList(); answers = emptyList()
            currentInput = ""; currentQuestionIndex = 0; finalReport = ""; memoryCandidate = ""
        }

        if (showMemoryDialog && memoryCandidate.isNotEmpty()) {
            MemoryDialog(
                candidate = memoryCandidate,
                onSave = {
                    appendToMemory(memoryCandidate, "週次レビュー", memoryFilePath)
                    showMemoryDialog = false
                    status = "✅ memory.md に保存しました"
                },
                onDismiss = { showMemoryDialog = false }
            )
        }

        if (phase == WeeklyPhase.QUESTIONING) {
            Column(
                modifier = Modifier.fillMaxSize().imePadding().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("週次レビュー", style = MaterialTheme.typography.headlineMedium)
                Text("対象週: ${weekStart.format(fmtDisplay)} 〜 ${weekEnd.format(fmtDisplay)}", style = MaterialTheme.typography.bodyLarge)
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Text("📋 一週間の重要ポイント", style = MaterialTheme.typography.titleMedium)
                        Text(text = keyPoints)
                    }
                    item { HorizontalDivider() }
                    item {
                        answers.take(currentQuestionIndex).forEachIndexed { i, answer ->
                            if (answer.isNotEmpty()) {
                                Text("Q${i + 1}: ${questions[i]}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                Text("→ $answer", style = MaterialTheme.typography.bodySmall)
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                        }
                    }
                    if (questions.isNotEmpty() && currentQuestionIndex < questions.size) {
                        item {
                            Text("Q${currentQuestionIndex + 1}/${questions.size}", style = MaterialTheme.typography.labelMedium)
                            Text(questions[currentQuestionIndex], style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = currentInput,
                                onValueChange = { currentInput = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("あなたの回答を入力...") },
                                minLines = 3
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = {
                                    val newAnswers = answers.toMutableList()
                                    newAnswers[currentQuestionIndex] = currentInput
                                    answers = newAnswers
                                    currentInput = ""
                                    if (currentQuestionIndex < questions.size - 1) {
                                        currentQuestionIndex++
                                    } else {
                                        phase = WeeklyPhase.GENERATING
                                        scope.launch {
                                            status = "レポートを生成中..."
                                            val report = generateWeeklyReport(weekStart, keyPoints, questions, newAnswers)
                                            finalReport = report
                                            val fmtFile = DateTimeFormatter.ofPattern("yyyy.MM.dd")
                                            val fmtEnd = DateTimeFormatter.ofPattern("MM.dd")
                                            val fileName = "WeeklyReview(${weekStart.format(fmtFile)}-${weekEnd.format(fmtEnd)}).md"
                                            File("$logFolder/$fileName").writeText(report)
                                            phase = WeeklyPhase.DONE
                                            status = "✅ $fileName に保存しました"
                                        }
                                    }
                                },
                                enabled = currentInput.isNotBlank(),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (currentQuestionIndex < questions.size - 1) "次の質問へ →" else "レポートを生成する") }
                        }
                    }
                }
            }
        } else {
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("週次レビュー", style = MaterialTheme.typography.headlineMedium)
                if (memoryEnabled) Text("🧠 メモリ参照: ON", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text("対象週: ${weekStart.format(fmtDisplay)} 〜 ${weekEnd.format(fmtDisplay)}", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { weekStart = weekStart.minusWeeks(1); reset() }) { Text("◀ 前週") }
                    Button(onClick = { weekStart = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); reset() }) { Text("今週") }
                    Button(onClick = { weekStart = weekStart.plusWeeks(1); reset() }) { Text("翌週 ▶") }
                }
                when (phase) {
                    WeeklyPhase.IDLE -> Button(
                        onClick = {
                            phase = WeeklyPhase.ANALYZING
                            scope.launch {
                                val result = analyzeWeek(weekStart, memoryEnabled, logFolder, reviewFilePath, memoryFilePath) { s -> status = s }
                                if (result == null) { status = "❌ ログが見つかりませんでした"; phase = WeeklyPhase.IDLE }
                                else {
                                    keyPoints = result
                                    status = "✅ 分析完了。AIが質問を生成中..."
                                    val qs = generateQuestions(result)
                                    questions = qs; answers = MutableList(qs.size) { "" }
                                    currentQuestionIndex = 0; currentInput = ""
                                    phase = WeeklyPhase.QUESTIONING; status = ""
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("週次レビュー開始") }
                    WeeklyPhase.ANALYZING, WeeklyPhase.GENERATING -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                        Text(text = status)
                    }
                    WeeklyPhase.DONE -> {
                        Text(text = status, color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider()
                        Text("📄 週次レビュー", style = MaterialTheme.typography.titleMedium)
                        Text(text = finalReport)
                        Button(
                            onClick = {
                                isExtractingMemory = true
                                scope.launch {
                                    memoryCandidate = extractMemoryCandidate(finalReport)
                                    isExtractingMemory = false; showMemoryDialog = true
                                }
                            },
                            enabled = !isExtractingMemory, modifier = Modifier.fillMaxWidth()
                        ) { Text(if (isExtractingMemory) "🧠 抽出中..." else "🧠 メモリ候補を確認") }
                        Button(onClick = { reset() }, modifier = Modifier.fillMaxWidth()) { Text("別の週をレビューする") }
                    }
                    else -> {}
                }
            }
        }
    }

    // ─────────────────────────────────────────
    // メモレビュー画面
    // ─────────────────────────────────────────
    @Composable
    fun MemoReviewScreen(
        memoryEnabled: Boolean,
        logFolder: String,
        reviewFilePath: String,
        memoryFilePath: String
    ) {
        var phase by remember { mutableStateOf(MemoReviewPhase.FILE_LIST) }
        var selectedFile by remember { mutableStateOf<File?>(null) }
        var status by remember { mutableStateOf("") }
        var summary by remember { mutableStateOf("") }
        var improvements by remember { mutableStateOf("") }
        var actions by remember { mutableStateOf("") }
        var memoryCandidate by remember { mutableStateOf("") }
        var showMemoryDialog by remember { mutableStateOf(false) }
        var isExtractingMemory by remember { mutableStateOf(false) }
        var showOverwriteDialog by remember { mutableStateOf(false) }
        var pendingFile by remember { mutableStateOf<File?>(null) }

        val scope = rememberCoroutineScope()
        val scrollState = rememberScrollState()

        val systemFiles = setOf(File(reviewFilePath).name, File(memoryFilePath).name)
        val vaultFiles = remember(logFolder) {
            File(logFolder).listFiles()
                ?.filter { it.extension == "md" && it.name !in systemFiles }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }

        fun reset() {
            phase = MemoReviewPhase.FILE_LIST; selectedFile = null; status = ""
            summary = ""; improvements = ""; actions = ""; memoryCandidate = ""
        }

        suspend fun startMemoReview(file: File) {
            selectedFile = file; phase = MemoReviewPhase.REVIEWING
            summary = ""; improvements = ""; actions = ""
            val result = reviewMemo(
                file = file, memoryEnabled = memoryEnabled,
                reviewFilePath = reviewFilePath, memoryFilePath = memoryFilePath,
                onProgress = { status = it },
                onSummary = { summary = it },
                onImprovements = { improvements = it },
                onActions = { actions = it }
            )
            status = result; phase = MemoReviewPhase.DONE
        }

        if (showMemoryDialog && memoryCandidate.isNotEmpty()) {
            MemoryDialog(
                candidate = memoryCandidate,
                onSave = { appendToMemory(memoryCandidate, "メモレビュー", memoryFilePath); showMemoryDialog = false; status = "✅ memory.md に保存しました" },
                onDismiss = { showMemoryDialog = false }
            )
        }

        if (showOverwriteDialog) {
            AlertDialog(
                onDismissRequest = { showOverwriteDialog = false },
                title = { Text("レビュー済みです") },
                text = { Text("このメモは既にレビューされています。上書きしますか？") },
                confirmButton = {
                    TextButton(onClick = {
                        showOverwriteDialog = false
                        pendingFile?.let { scope.launch { startMemoReview(it) } }
                    }) { Text("上書きする") }
                },
                dismissButton = { TextButton(onClick = { showOverwriteDialog = false }) { Text("キャンセル") } }
            )
        }

        when (phase) {
            MemoReviewPhase.FILE_LIST -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("メモレビュー", style = MaterialTheme.typography.headlineMedium)
                    if (memoryEnabled) Text("🧠 メモリ参照: ON", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text("レビューするファイルを選んでください", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                    HorizontalDivider()
                    if (vaultFiles.isEmpty()) {
                        Text("ファイルが見つかりません\n設定でフォルダパスを確認してください", color = MaterialTheme.colorScheme.outline)
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(vaultFiles) { file ->
                                val isReviewed = runCatching { file.readText().startsWith(REVIEWED_MARKER) }.getOrDefault(false)
                                Card(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        if (isReviewed) { pendingFile = file; showOverwriteDialog = true }
                                        else scope.launch { startMemoReview(file) }
                                    },
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(file.nameWithoutExtension, style = MaterialTheme.typography.bodyLarge)
                                            Text(
                                                "${(file.length() / 1024).coerceAtLeast(1)} KB · ${java.text.SimpleDateFormat("yyyy/MM/dd").format(file.lastModified())}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                        if (isReviewed) Text("✅", style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            MemoReviewPhase.REVIEWING -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("メモレビュー", style = MaterialTheme.typography.headlineMedium)
                    Text(selectedFile?.nameWithoutExtension ?: "", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(24.dp))
                    CircularProgressIndicator()
                    Text(text = status)
                }
            }
            MemoReviewPhase.DONE -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text("メモレビュー", style = MaterialTheme.typography.headlineMedium)
                    Text(selectedFile?.nameWithoutExtension ?: "", style = MaterialTheme.typography.titleMedium)
                    Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    if (summary.isNotEmpty()) { HorizontalDivider(); Text("### 総評", style = MaterialTheme.typography.titleMedium); Text(summary) }
                    if (improvements.isNotEmpty()) { HorizontalDivider(); Text("### 改善提案", style = MaterialTheme.typography.titleMedium); Text(improvements) }
                    if (actions.isNotEmpty()) { HorizontalDivider(); Text("### 次のアクション", style = MaterialTheme.typography.titleMedium); Text(actions) }
                    HorizontalDivider()
                    Button(
                        onClick = {
                            isExtractingMemory = true
                            scope.launch {
                                memoryCandidate = extractMemoryCandidate("総評: $summary\n改善提案: $improvements\n次のアクション: $actions")
                                isExtractingMemory = false; showMemoryDialog = true
                            }
                        },
                        enabled = !isExtractingMemory && summary.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (isExtractingMemory) "🧠 抽出中..." else "🧠 メモリ候補を確認") }
                    Button(onClick = { reset() }, modifier = Modifier.fillMaxWidth()) { Text("別のファイルをレビューする") }
                }
            }
        }
    }

    // ─────────────────────────────────────────
    // チャット画面
    // ─────────────────────────────────────────
    @Composable
    fun ChatScreen(memoryEnabled: Boolean, reviewFilePath: String, memoryFilePath: String) {
        val messages = remember { mutableStateListOf<ChatMessage>() }
        var input by remember { mutableStateOf("") }
        var isThinking by remember { mutableStateOf(false) }
        var memoryCandidate by remember { mutableStateOf("") }
        var showMemoryDialog by remember { mutableStateOf(false) }
        var isExtractingMemory by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
        }

        if (showMemoryDialog && memoryCandidate.isNotEmpty()) {
            MemoryDialog(
                candidate = memoryCandidate,
                onSave = { appendToMemory(memoryCandidate, "チャット", memoryFilePath); showMemoryDialog = false },
                onDismiss = { showMemoryDialog = false }
            )
        }

        Column(modifier = Modifier.fillMaxSize().imePadding().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            if (messages.isEmpty()) return@TextButton
                            isExtractingMemory = true
                            scope.launch {
                                val content = messages.joinToString("\n") { if (it.isUser) "ユーザー: ${it.text}" else "AI: ${it.text}" }
                                memoryCandidate = extractMemoryCandidate(content)
                                isExtractingMemory = false; showMemoryDialog = true
                            }
                        },
                        enabled = messages.isNotEmpty() && !isThinking && !isExtractingMemory
                    ) { Text(if (isExtractingMemory) "🧠 抽出中..." else "🧠 メモリ候補を確認") }
                    if (memoryEnabled) Text("ON", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { messages.clear() }, enabled = messages.isNotEmpty() && !isThinking) { Text("会話をクリア") }
            }

            LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { message ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start) {
                        Card(
                            modifier = Modifier.widthIn(max = 280.dp),
                            colors = CardDefaults.cardColors(containerColor = if (message.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        ) { Text(text = message.text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                if (isThinking) {
                    item {
                        Row(horizontalArrangement = Arrangement.Start) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Text("考え中...", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f), placeholder = { Text("メッセージを入力...") }, enabled = !isThinking
                )
                Button(
                    onClick = {
                        if (input.isBlank()) return@Button
                        val userMessage = input.trim(); input = ""
                        messages.add(ChatMessage(userMessage, isUser = true))
                        isThinking = true
                        val history = messages.toList()
                        scope.launch {
                            val reply = chat(userMessage, history.dropLast(1), memoryEnabled, reviewFilePath, memoryFilePath)
                            messages.add(ChatMessage(reply, isUser = false)); isThinking = false
                        }
                    },
                    enabled = !isThinking && input.isNotBlank()
                ) { Text("送信") }
            }
        }
    }

    // ─────────────────────────────────────────
    // 共通ヘルパー
    // ─────────────────────────────────────────
    private suspend fun ask(model: com.google.mlkit.genai.prompt.GenerativeModel, prompt: String): String {
        val response = model.generateContent(generateContentRequest(TextPart(prompt)) { maxOutputTokens = 256 })
        return response.candidates.firstOrNull()?.text ?: ""
    }

    private suspend fun chat(
        userMessage: String,
        history: List<ChatMessage>,
        memoryEnabled: Boolean,
        reviewFilePath: String,
        memoryFilePath: String
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val criteria = runCatching { File(reviewFilePath).readText() }.getOrDefault("")
                val memory = buildMemoryContext(memoryEnabled, memoryFilePath)
                val context = buildString {
                    if (memory.isNotEmpty()) append("【参考情報・コンテキスト（回答の対象ではありません）】\n$memory\n\n")
                    if (criteria.isNotEmpty()) append("【評価基準（参考）】\n$criteria\n\n")
                }
                val historyText = history.takeLast(6).let { recent ->
                    if (recent.isEmpty()) "" else "【これまでの会話】\n" + recent.joinToString("\n") { if (it.isUser) "ユーザー: ${it.text}" else "アシスタント: ${it.text}" } + "\n\n"
                }
                val instruction = if (memory.isNotEmpty()) "※参考情報はコンテキストとして活用してください。回答対象はユーザーの質問のみです。\n\n" else ""
                val prompt = "${context}${historyText}${instruction}【ユーザーの質問】\n$userMessage\n\n日本語で簡潔に3文以内で答えてください。"
                val model = Generation.getClient()
                val response = model.generateContent(generateContentRequest(TextPart(prompt)) { maxOutputTokens = 256 })
                response.candidates.firstOrNull()?.text ?: "応答が得られませんでした"
            } catch (e: Exception) {
                Log.e("Chat", "Error", e)
                "エラー: ${e.message}"
            }
        }
    }

    private suspend fun runReview(
        date: LocalDate,
        memoryEnabled: Boolean,
        logFolder: String,
        reviewFilePath: String,
        memoryFilePath: String,
        onProgress: suspend (String) -> Unit,
        onSummary: suspend (String) -> Unit,
        onImprovements: suspend (String) -> Unit,
        onActions: suspend (String) -> Unit
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                val logFile = File("$logFolder/${dateStr}作業log.md")
                val reviewFile = File(reviewFilePath)
                if (!logFile.exists()) return@withContext "❌ ログファイルが見つかりません: ${logFile.name}"
                if (!reviewFile.exists()) return@withContext "❌ REVIEW.md が見つかりません: $reviewFilePath"

                val logContent = extractLogContent(logFile.readText())
                val criteria = reviewFile.readText()
                val memory = buildMemoryContext(memoryEnabled, memoryFilePath)
                val model = Generation.getClient()

                val context = buildString {
                    if (memory.isNotEmpty()) append("【参考情報・コンテキスト（レビュー対象ではありません）】\n$memory\n\n")
                    append("【評価基準（参考）】\n$criteria\n\n")
                }
                val target = "【本日の作業ログ（レビュー対象）】\n$logContent"
                val instruction = "\n\n※必ず【本日の作業ログ】のみを対象にしてください。"

                onProgress("処理中... (1/3) 総評を生成中")
                val summaryText = ask(model, "$context$target${instruction}\n\n本日の作業ログの総評を日本語2文以内で書いてください。")
                onSummary(summaryText)
                onProgress("処理中... (2/3) 改善提案を生成中")
                val improvementsText = ask(model, "$context$target${instruction}\n\n本日の作業ログへの改善提案を日本語で箇条書き3点のみ書いてください。")
                onImprovements(improvementsText)
                onProgress("処理中... (3/3) 次のアクションを生成中")
                val actionsText = ask(model, "$context$target${instruction}\n\n本日の作業ログをふまえた明日やるべきことを「- [ ] 」形式で3点のみ書いてください。")
                onActions(actionsText)

                val reviewText = """
$REVIEWED_MARKER
---
## レビュー ${date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))}

### 総評
$summaryText

### 改善提案
$improvementsText

### 次のアクション
$actionsText

---
$REVIEW_END_MARKER
                """.trimIndent()
                logFile.writeText("$reviewText\n\n$logContent")
                "✅ レビュー完了！"
            } catch (e: Exception) {
                Log.e("Reviewer", "Error", e)
                "❌ エラー: ${e.message}"
            }
        }
    }

    private suspend fun reviewMemo(
        file: File,
        memoryEnabled: Boolean,
        reviewFilePath: String,
        memoryFilePath: String,
        onProgress: suspend (String) -> Unit,
        onSummary: suspend (String) -> Unit,
        onImprovements: suspend (String) -> Unit,
        onActions: suspend (String) -> Unit
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val reviewFile = File(reviewFilePath)
                if (!reviewFile.exists()) return@withContext "❌ REVIEW.md が見つかりません: $reviewFilePath"
                val memoContent = extractLogContent(file.readText())
                val criteria = reviewFile.readText()
                val memory = buildMemoryContext(memoryEnabled, memoryFilePath)
                val model = Generation.getClient()

                val context = buildString {
                    if (memory.isNotEmpty()) append("【参考情報・コンテキスト（レビュー対象ではありません）】\n$memory\n\n")
                    append("【評価基準（参考）】\n$criteria\n\n")
                }
                val target = "【メモ（レビュー対象）】\n$memoContent"
                val instruction = "\n\n※必ず【メモ】のみを対象にしてください。"

                onProgress("処理中... (1/3) 総評を生成中")
                val summaryText = ask(model, "$context$target${instruction}\n\n評価基準に基づきこのメモの総評を日本語2文以内で書いてください。")
                onSummary(summaryText)
                onProgress("処理中... (2/3) 改善提案を生成中")
                val improvementsText = ask(model, "$context$target${instruction}\n\n評価基準に基づきこのメモへの改善提案を日本語で箇条書き3点のみ書いてください。")
                onImprovements(improvementsText)
                onProgress("処理中... (3/3) 次のアクションを生成中")
                val actionsText = ask(model, "$context$target${instruction}\n\nこのメモをふまえた次のアクションを「- [ ] 」形式で3点のみ書いてください。")
                onActions(actionsText)

                val date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                val reviewText = """
$REVIEWED_MARKER
---
## レビュー $date（${file.nameWithoutExtension}）

### 総評
$summaryText

### 改善提案
$improvementsText

### 次のアクション
$actionsText

---
$REVIEW_END_MARKER
                """.trimIndent()
                file.writeText("$reviewText\n\n$memoContent")
                "✅ レビュー完了！"
            } catch (e: Exception) {
                Log.e("MemoReview", "Error", e)
                "❌ エラー: ${e.message}"
            }
        }
    }

    private suspend fun analyzeWeek(
        weekStart: LocalDate,
        memoryEnabled: Boolean,
        logFolder: String,
        reviewFilePath: String,
        memoryFilePath: String,
        onProgress: (String) -> Unit
    ): String? {
        return withContext(Dispatchers.IO) {
            val criteria = runCatching { File(reviewFilePath).readText() }.getOrDefault("")
            val memory = buildMemoryContext(memoryEnabled, memoryFilePath)
            val logTexts = mutableListOf<String>()
            for (i in 0..6) {
                val date = weekStart.plusDays(i.toLong())
                val dateStr = date.format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                val logFile = File("$logFolder/${dateStr}作業log.md")
                if (logFile.exists()) {
                    val content = extractLogContent(logFile.readText())
                    logTexts.add("【${date.format(DateTimeFormatter.ofPattern("MM/dd(E)"))}】\n$content")
                }
            }
            if (logTexts.isEmpty()) return@withContext null
            val allLogs = logTexts.joinToString("\n\n---\n\n")
            val model = Generation.getClient()
            val context = buildString {
                if (memory.isNotEmpty()) append("【参考情報・コンテキスト（分析対象ではありません）】\n$memory\n\n")
                if (criteria.isNotEmpty()) append("【評価基準（参考）】\n$criteria\n\n")
            }
            val target = "【一週間の作業ログ（分析対象）】\n$allLogs"
            val instruction = "\n\n※必ず【一週間の作業ログ】のみを対象にしてください。"
            onProgress("(1/2) 成果・出来事を分析中...")
            val part1 = ask(model, "$context$target${instruction}\n\nこの一週間の作業ログから特に重要な成果・出来事を日本語で箇条書き3点にまとめてください。")
            onProgress("(2/2) 課題・改善点を分析中...")
            val part2 = ask(model, "$context$target${instruction}\n\nこの一週間の作業ログから見られた課題・改善すべき点を日本語で箇条書き3点にまとめてください。")
            "【成果・出来事】\n$part1\n\n【課題・改善点】\n$part2"
        }
    }

    private suspend fun generateQuestions(keyPoints: String): List<String> {
        return withContext(Dispatchers.IO) {
            val model = Generation.getClient()
            val prompt = """
以下は一週間の作業の重要ポイントです。
$keyPoints

この内容をふまえて、担当者の主観的な印象や感情を引き出すための質問を3つ作成してください。
番号なし・質問文のみを1行ずつ出力してください。
            """.trimIndent()
            ask(model, prompt).lines().map { it.trim() }.filter { it.isNotEmpty() }.take(3)
        }
    }

    private suspend fun generateWeeklyReport(
        weekStart: LocalDate,
        keyPoints: String,
        questions: List<String>,
        answers: List<String>
    ): String {
        return withContext(Dispatchers.IO) {
            val weekEnd = weekStart.plusDays(6)
            val fmtFile = DateTimeFormatter.ofPattern("yyyy.MM.dd")
            val fmtEnd = DateTimeFormatter.ofPattern("MM.dd")
            val model = Generation.getClient()
            val qaText = questions.zip(answers).joinToString("\n\n") { (q, a) -> "Q: $q\nA: $a" }
            val conclusion = ask(model, "以下の情報をもとに、一週間の総合所見を日本語2文以内で書いてください。\n重要ポイント:\n$keyPoints\nQ&A:\n$qaText")
            val qaSection = questions.zip(answers).mapIndexed { i, (q, a) -> "### Q${i + 1}: $q\n$a" }.joinToString("\n\n")
            """
# WeeklyReview(${weekStart.format(fmtFile)}-${weekEnd.format(fmtEnd)})

## 重要ポイント
$keyPoints

## 振り返りQ&A
$qaSection

## 総合所見
$conclusion
            """.trimIndent()
        }
    }
}