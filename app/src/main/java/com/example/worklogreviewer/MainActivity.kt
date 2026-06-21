package com.example.worklogreviewer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.worklogreviewer.ui.theme.WorkLogReviewerTheme
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.*
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

data class ChatMessage(val text: String, val isUser: Boolean)
data class ConceptCandidate(
    val name: String,
    val description: String,
    val similarConcepts: List<Pair<String, String>> = emptyList()
)
data class CyberEntry(val direction: String, val content: String)

object CyberdeckLog {
    val entries = mutableStateListOf<CyberEntry>()
    fun tx(content: String) { entries.add(CyberEntry("TX", content)); trim() }
    fun rx(content: String) { entries.add(CyberEntry("RX", content)); trim() }
    fun clear() { entries.clear() }
    private fun trim() { while (entries.size > 40) entries.removeAt(0) }
}

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
    private val PREF_WIKI_FOLDER = "wiki_folder"
    private val PREF_PROMPT_FILE = "prompt_file"
    private val PREF_WEEKLY_PROMPT_FILE = "weekly_prompt_file"
    private val PREF_MODEL_STAGE = "model_stage"
    private val PREF_MODEL_PREFERENCE = "model_preference"
    private val PREF_SCHEDULE_ENABLED = "schedule_enabled"
    private val PREF_SCHEDULE_HOUR = "schedule_hour"
    private val PREF_SCHEDULE_MINUTE = "schedule_minute"
    private val WIKI_INSTRUCTION_FILE = "WIKI.md"

    // モデル設定（設定変更時にリセット）
    private var currentModelStage: String = "PREVIEW"
    private var currentModelPreference: String = "FULL"
    private var cachedModelAvailable: Boolean? = null

    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private val notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Environment.isExternalStorageManager()) {
            storagePermissionLauncher.launch(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val autoReview = intent.getBooleanExtra("AUTO_REVIEW", false)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // モデル設定の初期読み込み
        currentModelStage = prefs.getString(PREF_MODEL_STAGE, "PREVIEW") ?: "PREVIEW"
        currentModelPreference = prefs.getString(PREF_MODEL_PREFERENCE, "FULL") ?: "FULL"

        setContent {
            WorkLogReviewerTheme {
                var selectedTab by remember { mutableIntStateOf(0) }
                var memoryEnabled by remember { mutableStateOf(prefs.getBoolean(PREF_MEMORY_ENABLED, true)) }
                var logFolder by remember { mutableStateOf(prefs.getString(PREF_LOG_FOLDER, DEFAULT_VAULT) ?: DEFAULT_VAULT) }
                var reviewFilePath by remember { mutableStateOf(prefs.getString(PREF_REVIEW_FILE, "$DEFAULT_VAULT/REVIEW.md") ?: "$DEFAULT_VAULT/REVIEW.md") }
                var memoryFilePath by remember { mutableStateOf(prefs.getString(PREF_MEMORY_FILE, "$DEFAULT_VAULT/memory.md") ?: "$DEFAULT_VAULT/memory.md") }
                var wikiFolder by remember { mutableStateOf(prefs.getString(PREF_WIKI_FOLDER, "$DEFAULT_VAULT/wiki") ?: "$DEFAULT_VAULT/wiki") }
                var promptFilePath by remember { mutableStateOf(prefs.getString(PREF_PROMPT_FILE, "$DEFAULT_VAULT/PROMPT.md") ?: "$DEFAULT_VAULT/PROMPT.md") }
                var weeklyPromptFilePath by remember { mutableStateOf(prefs.getString(PREF_WEEKLY_PROMPT_FILE, "$DEFAULT_VAULT/WEEKLY_PROMPT.md") ?: "$DEFAULT_VAULT/WEEKLY_PROMPT.md") }
                var modelStage by remember { mutableStateOf(currentModelStage) }
                var modelPreference by remember { mutableStateOf(currentModelPreference) }
                var scheduleEnabled by remember { mutableStateOf(prefs.getBoolean(PREF_SCHEDULE_ENABLED, false)) }
                var scheduleHour by remember { mutableStateOf(prefs.getInt(PREF_SCHEDULE_HOUR, 8)) }
                var scheduleMinute by remember { mutableStateOf(prefs.getInt(PREF_SCHEDULE_MINUTE, 0)) }

                Scaffold(
                    bottomBar = {
                        Column {
                            CyberdeckWindow()
                            NavigationBar {
                                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.RateReview, null) }, label = { Text("レビュー") })
                                NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.CalendarMonth, null) }, label = { Text("週次") })
                                NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.Description, null) }, label = { Text("メモ") })
                                NavigationBarItem(selected = selectedTab == 3, onClick = { selectedTab = 3 }, icon = { Icon(Icons.Default.AutoStories, null) }, label = { Text("Wiki") })
                                NavigationBarItem(selected = selectedTab == 4, onClick = { selectedTab = 4 }, icon = { Icon(Icons.Default.Chat, null) }, label = { Text("チャット") })
                                NavigationBarItem(selected = selectedTab == 5, onClick = { selectedTab = 5 }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("設定") })
                            }
                        }
                    }
                ) { paddingValues ->
                    Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
                        when (selectedTab) {
                            0 -> ReviewScreen(autoReview, memoryEnabled, logFolder, reviewFilePath, memoryFilePath, wikiFolder, promptFilePath)
                            1 -> WeeklyReviewScreen(memoryEnabled, logFolder, reviewFilePath, memoryFilePath, wikiFolder, weeklyPromptFilePath)
                            2 -> MemoReviewScreen(memoryEnabled, logFolder, reviewFilePath, memoryFilePath, wikiFolder, promptFilePath)
                            3 -> WikiScreen(wikiFolder)
                            4 -> ChatScreen(memoryEnabled, reviewFilePath, memoryFilePath)
                            5 -> SettingsScreen(
                                memoryEnabled, logFolder, reviewFilePath, memoryFilePath, wikiFolder,
                                promptFilePath, weeklyPromptFilePath, modelStage, modelPreference,
                                scheduleEnabled, scheduleHour, scheduleMinute,
                                onMemoryToggle = { memoryEnabled = it; prefs.edit().putBoolean(PREF_MEMORY_ENABLED, it).apply() },
                                onSavePaths = { lf, rf, mf, wf, pf, wpf ->
                                    logFolder = lf; reviewFilePath = rf; memoryFilePath = mf
                                    wikiFolder = wf; promptFilePath = pf; weeklyPromptFilePath = wpf
                                    prefs.edit().putString(PREF_LOG_FOLDER, lf).putString(PREF_REVIEW_FILE, rf)
                                        .putString(PREF_MEMORY_FILE, mf).putString(PREF_WIKI_FOLDER, wf)
                                        .putString(PREF_PROMPT_FILE, pf).putString(PREF_WEEKLY_PROMPT_FILE, wpf).apply()
                                },
                                onModelChange = { stage, preference ->
                                    modelStage = stage; modelPreference = preference
                                    currentModelStage = stage; currentModelPreference = preference
                                    cachedModelAvailable = null // キャッシュリセット
                                    prefs.edit().putString(PREF_MODEL_STAGE, stage).putString(PREF_MODEL_PREFERENCE, preference).apply()
                                },
                                onScheduleChange = { enabled, hour, minute ->
                                    scheduleEnabled = enabled; scheduleHour = hour; scheduleMinute = minute
                                    prefs.edit().putBoolean(PREF_SCHEDULE_ENABLED, enabled)
                                        .putInt(PREF_SCHEDULE_HOUR, hour).putInt(PREF_SCHEDULE_MINUTE, minute).apply()
                                    if (enabled) ScheduleManager.schedule(this@MainActivity, hour, minute)
                                    else ScheduleManager.cancel(this@MainActivity)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────
    // モデルビルダー
    // ─────────────────────────────────────────
    private fun buildModel(stage: String, preference: String): com.google.mlkit.genai.prompt.GenerativeModel {
        val releaseStage = if (stage == "PREVIEW") ModelReleaseStage.PREVIEW else ModelReleaseStage.STABLE
        val modelPref = if (preference == "FULL") ModelPreference.FULL else ModelPreference.FAST
        val config = generationConfig {
            modelConfig = modelConfig {
                this.releaseStage = releaseStage
                this.preference = modelPref
            }
        }
        return Generation.getClient(config)
    }

    // ─────────────────────────────────────────
    // 通信ログ窓（常時表示）
    // ─────────────────────────────────────────
    @Composable
    fun CyberdeckWindow() {
        val listState = rememberLazyListState()
        LaunchedEffect(CyberdeckLog.entries.size) {
            if (CyberdeckLog.entries.isNotEmpty()) listState.animateScrollToItem(CyberdeckLog.entries.size - 1)
        }
        Column(
            modifier = Modifier.fillMaxWidth().height(150.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("AICORE LINK [$currentModelStage/$currentModelPreference]", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text("🗑 クリア", color = MaterialTheme.colorScheme.outline, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.clickable { CyberdeckLog.clear() })
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            if (CyberdeckLog.entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("待機中...", color = MaterialTheme.colorScheme.outline, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(CyberdeckLog.entries) { entry ->
                        Text("${if (entry.direction == "TX") "TX>" else "RX<"} ${entry.content}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────
    // プロンプトファイル読み込み（共通）
    // ─────────────────────────────────────────
    private fun loadPromptSection(filePath: String, section: String): String? {
        val file = File(filePath)
        if (!file.exists()) return null
        val lines = file.readText().lines()
        val startIdx = lines.indexOfFirst { it.trim() == "## $section" }
        if (startIdx < 0) return null
        val afterStart = lines.drop(startIdx + 1)
        val endIdx = afterStart.indexOfFirst { it.startsWith("## ") }
        return if (endIdx < 0) afterStart.joinToString("\n").trim()
        else afterStart.take(endIdx).joinToString("\n").trim()
    }

    // ─────────────────────────────────────────
    // アクションテキストのフィルタ（共通）
    // ─────────────────────────────────────────
    private fun filterActions(raw: String): String {
        val filtered = raw.lines()
            .filter { it.trim().startsWith("- [ ]") || it.trim().startsWith("- [x]") || it.trim().startsWith("- [X]") }
            .joinToString("\n")
        return filtered.ifEmpty { raw }
    }

    // ─────────────────────────────────────────
    // Wiki関連
    // ─────────────────────────────────────────
    private fun loadConceptsList(wikiFolder: String): List<Pair<String, String>> {
        val file = File(wikiFolder, "concepts.md")
        if (!file.exists()) return emptyList()
        return file.readText().lines().filter { it.startsWith("- ") }.mapNotNull { line ->
            val content = line.removePrefix("- ")
            val idx = content.indexOf("：")
            if (idx >= 0) Pair(content.substring(0, idx).trim(), content.substring(idx + 1).trim()) else null
        }
    }
    private fun appendToConceptsFile(wikiFolder: String, name: String, description: String) {
        File(wikiFolder).mkdirs()
        val file = File(wikiFolder, "concepts.md")
        val entry = "- $name：$description"
        if (!file.exists()) file.writeText("# 概念レジストリ\n$entry\n") else file.appendText("$entry\n")
    }
    private suspend fun extractConceptCandidates(reviewContent: String, wikiFolder: String): List<ConceptCandidate> {
        return withContext(Dispatchers.IO) {
            val existing = loadConceptsList(wikiFolder)
            val existingText = if (existing.isNotEmpty()) "既存概念:\n" + existing.joinToString("\n") { "- ${it.first}：${it.second}" } + "\n\n" else ""
            val wikiInstructionFile = File(wikiFolder, WIKI_INSTRUCTION_FILE)
            val wikiInstruction = if (wikiInstructionFile.exists()) wikiInstructionFile.readText() + "\n\n"
            else "抽出する概念の基準:\n- 作業ログに具体的に登場した手法・ツール・プロセスを優先する\n- 「効率化」「改善」などの抽象的な表現は避ける\n- 固有の作業名・技術名・判断基準など再利用できる粒度にする\n- 抽象度が高すぎる概念（例：生産性向上、自己管理）は抽出しない\n\n"
            val prompt = "$wikiInstruction\n${existingText}以下のレビュー内容から、まだ登録されていない新しい概念を最大3つ抽出してください。\n出力形式：「概念の名前：一行で説明」を1行ずつのみ出力してください。\n「概念名」という言葉は出力しないでください。\n\n出力例：\nタスク分解：大きな作業を小さな単位に分けること\n属人化リスク：特定の担当者だけが業務を把握している状態\n\n$reviewContent"
            val response = ask(prompt)
            val candidates = response.lines().map { it.trim().removePrefix("- ") }.filter { it.isNotEmpty() && it.contains("：") }
                .map { line ->
                    val cleaned = line.removePrefix("概念名：").removePrefix("概念：").trim()
                    val idx = cleaned.indexOf("："); if (idx < 0) return@map null
                    val name = cleaned.substring(0, idx).trim(); val desc = cleaned.substring(idx + 1).trim()
                    if (name.isEmpty() || desc.isEmpty()) null else ConceptCandidate(name, desc)
                }.filterNotNull().take(3)
            if (existing.isEmpty()) candidates
            else candidates.map { candidate ->
                val similarResponse = ask("以下の概念リストの中から「${candidate.name}」と類似するものを最大2つ選んでください。\n概念名のみを1行ずつ出力してください。類似するものがなければ何も出力しないでください。\n\n${existing.joinToString("\n") { "- ${it.first}：${it.second}" }}")
                val similarNames = similarResponse.lines().map { it.trim().removePrefix("- ") }.filter { name -> existing.any { it.first == name } }
                candidate.copy(similarConcepts = existing.filter { it.first in similarNames })
            }
        }
    }
    private suspend fun generateAndSaveWikiPage(candidate: ConceptCandidate, sourceName: String, wikiFolder: String) {
        withContext(Dispatchers.IO) {
            File(wikiFolder).mkdirs()
            val overview = ask("「${candidate.name}」（${candidate.description}）について、日本語で2〜3文の概要を書いてください。専門的すぎず、実務に即した説明にしてください。")
            val relatedSection = if (candidate.similarConcepts.isNotEmpty()) "## 関連概念\n" + candidate.similarConcepts.joinToString("\n") { (n, d) -> "- [[$n]] - $d" } else ""
            val date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            File(wikiFolder, "${candidate.name}.md").writeText("# ${candidate.name}\n\n*最終更新: $date*\n\n## 概要\n$overview\n\n$relatedSection\n\n## ソース\n- $date $sourceName".trimIndent())
        }
    }
    private fun appendSourceToWikiPage(conceptName: String, sourceName: String, relatedConceptName: String, wikiFolder: String) {
        val file = File(wikiFolder, "$conceptName.md"); if (!file.exists()) return
        val date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        var content = file.readText()
        content = if (content.contains("## ソース")) content.replace("## ソース", "## ソース\n- $date $sourceName") else "$content\n\n## ソース\n- $date $sourceName"
        val relatedEntry = "- [[$relatedConceptName]]"
        content = if (content.contains("## 関連概念")) { if (!content.contains(relatedEntry)) content.replace("## 関連概念", "## 関連概念\n$relatedEntry") else content } else "$content\n\n## 関連概念\n$relatedEntry"
        file.writeText(content)
    }

    // ─────────────────────────────────────────
    // Wiki画面
    // ─────────────────────────────────────────
    @Composable
    fun WikiScreen(wikiFolder: String) {
        val scrollState = rememberScrollState()
        val concepts = remember(wikiFolder) { loadConceptsList(wikiFolder) }
        val wikiPages = remember(wikiFolder) {
            File(wikiFolder).listFiles()?.filter { it.extension == "md" && it.name != "concepts.md" && it.name != WIKI_INSTRUCTION_FILE }?.sortedBy { it.nameWithoutExtension } ?: emptyList()
        }
        val wikiInstructionFile = File(wikiFolder, WIKI_INSTRUCTION_FILE)
        Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Wiki", style = MaterialTheme.typography.headlineMedium)
            Text("📖 概念レジストリ", style = MaterialTheme.typography.titleMedium)
            if (concepts.isEmpty()) Text("概念がまだありません。\nレビュー後に「📖 Wiki概念」から追加できます。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            else concepts.forEach { (name, description) ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("[[$name]]", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            HorizontalDivider()
            Text("📄 Wikiページ", style = MaterialTheme.typography.titleMedium)
            if (wikiPages.isEmpty()) Text("Wikiページがまだありません。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            else wikiPages.forEach { file ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(file.nameWithoutExtension, style = MaterialTheme.typography.bodyLarge)
                        Text(java.text.SimpleDateFormat("MM/dd").format(file.lastModified()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            HorizontalDivider()
            Text("⚙️ 概念抽出の指示", style = MaterialTheme.typography.titleMedium)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (wikiInstructionFile.exists()) { Text("WIKI.md 読み込み済み ✅", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary); Text("Obsidianで編集することで概念抽出の基準をカスタマイズできます", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                    else { Text("WIKI.md 未作成（デフォルト設定を使用中）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline); Text("$wikiFolder/$WIKI_INSTRUCTION_FILE を作成すると抽出基準をカスタマイズできます", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                }
            }
            Text("Wikiフォルダ: $wikiFolder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }

    // ─────────────────────────────────────────
    // 概念確認ダイアログ
    // ─────────────────────────────────────────
    @Composable
    fun ConceptDialog(candidate: ConceptCandidate, currentIndex: Int, total: Int, onSave: (addAsNew: Boolean, checkedConcepts: Set<String>) -> Unit, onSkip: () -> Unit) {
        var addAsNew by remember { mutableStateOf(true) }
        var checkedConcepts by remember { mutableStateOf(setOf<String>()) }
        AlertDialog(onDismissRequest = onSkip, title = { Text("📖 概念候補 ($currentIndex/$total)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(modifier = Modifier.padding(12.dp)) { Text(candidate.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary); Text(candidate.description, style = MaterialTheme.typography.bodySmall) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { Checkbox(checked = addAsNew, onCheckedChange = { addAsNew = it }); Text("新概念として追加する", style = MaterialTheme.typography.bodyMedium) }
                    if (candidate.similarConcepts.isNotEmpty()) {
                        HorizontalDivider(); Text("類似する既存概念（チェックで追記）:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        candidate.similarConcepts.forEach { (name, description) ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Checkbox(checked = checkedConcepts.contains(name), onCheckedChange = { checked -> checkedConcepts = if (checked) checkedConcepts + name else checkedConcepts - name })
                                Column { Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary); Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onSave(addAsNew, checkedConcepts) }, enabled = addAsNew || checkedConcepts.isNotEmpty()) { Text("保存する") } },
            dismissButton = { TextButton(onClick = onSkip) { Text("スキップ") } })
    }

    // ─────────────────────────────────────────
    // 設定画面
    // ─────────────────────────────────────────
    @Composable
    fun SettingsScreen(
        memoryEnabled: Boolean, logFolder: String, reviewFilePath: String, memoryFilePath: String,
        wikiFolder: String, promptFilePath: String, weeklyPromptFilePath: String,
        modelStage: String, modelPreference: String,
        scheduleEnabled: Boolean, scheduleHour: Int, scheduleMinute: Int,
        onMemoryToggle: (Boolean) -> Unit,
        onSavePaths: (String, String, String, String, String, String) -> Unit,
        onModelChange: (String, String) -> Unit,
        onScheduleChange: (Boolean, Int, Int) -> Unit
    ) {
        var logFolderInput by remember(logFolder) { mutableStateOf(logFolder) }
        var reviewFileInput by remember(reviewFilePath) { mutableStateOf(reviewFilePath) }
        var memoryFileInput by remember(memoryFilePath) { mutableStateOf(memoryFilePath) }
        var wikiFolderInput by remember(wikiFolder) { mutableStateOf(wikiFolder) }
        var promptFileInput by remember(promptFilePath) { mutableStateOf(promptFilePath) }
        var weeklyPromptFileInput by remember(weeklyPromptFilePath) { mutableStateOf(weeklyPromptFilePath) }
        var saveStatus by remember { mutableStateOf("") }
        var localScheduleEnabled by remember(scheduleEnabled) { mutableStateOf(scheduleEnabled) }
        var localHour by remember(scheduleHour) { mutableStateOf(scheduleHour) }
        var localMinute by remember(scheduleMinute) { mutableStateOf(scheduleMinute) }
        val memoryFile = File(memoryFilePath)
        val memoryExists = memoryFile.exists()
        val memorySize = if (memoryExists) runCatching { memoryFile.readText().lines().size }.getOrDefault(0) else 0
        val scrollState = rememberScrollState()

        Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("設定", style = MaterialTheme.typography.headlineMedium)
            HorizontalDivider()

            // ── モデル設定 ──
            Text("🤖 モデル設定", style = MaterialTheme.typography.titleMedium)
            Text("リリースステージ", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("PREVIEW", "STABLE").forEach { stage ->
                    FilterChip(
                        selected = modelStage == stage,
                        onClick = { onModelChange(stage, modelPreference) },
                        label = { Text(stage) }
                    )
                }
            }
            Text("モデル設定", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("FULL" to "精度重視", "FAST" to "速度重視").forEach { (pref, label) ->
                    FilterChip(
                        selected = modelPreference == pref,
                        onClick = { onModelChange(modelStage, pref) },
                        label = { Text("$pref ($label)") }
                    )
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text("現在: $modelStage / $modelPreference\n利用不可の場合はSTABLE/FULLに自動切替えしてCyberdeckに通知します", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }

            HorizontalDivider()

            // ── パス設定 ──
            Text("📁 パス設定", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = logFolderInput, onValueChange = { logFolderInput = it }, label = { Text("作業ログフォルダ") }, modifier = Modifier.fillMaxWidth(), singleLine = true, supportingText = { Text("YYYYMMDD作業log.md が置かれているフォルダ") })
            OutlinedTextField(value = reviewFileInput, onValueChange = { reviewFileInput = it }, label = { Text("REVIEW.md のパス") }, modifier = Modifier.fillMaxWidth(), singleLine = true, supportingText = { Text("評価基準ファイルのフルパス") })
            OutlinedTextField(value = memoryFileInput, onValueChange = { memoryFileInput = it }, label = { Text("memory.md のパス") }, modifier = Modifier.fillMaxWidth(), singleLine = true, supportingText = { Text("長期メモリファイルのフルパス") })
            OutlinedTextField(value = wikiFolderInput, onValueChange = { wikiFolderInput = it }, label = { Text("Wikiフォルダ") }, modifier = Modifier.fillMaxWidth(), singleLine = true, supportingText = { Text("concepts.md・WIKI.md・Wikiページの保存先") })

            HorizontalDivider()
            Text("📝 プロンプト指示ファイル", style = MaterialTheme.typography.titleMedium)
            Text("ファイルが存在する場合はアプリ内デフォルトの指示を上書きします", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            OutlinedTextField(value = promptFileInput, onValueChange = { promptFileInput = it }, label = { Text("PROMPT.md のパス") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                supportingText = { val e = File(promptFileInput).exists(); Text(if (e) "✅ 読み込み済み（日次・メモ用）" else "未作成（デフォルト使用）", color = if (e) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) })
            OutlinedTextField(value = weeklyPromptFileInput, onValueChange = { weeklyPromptFileInput = it }, label = { Text("WEEKLY_PROMPT.md のパス") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                supportingText = { val e = File(weeklyPromptFileInput).exists(); Text(if (e) "✅ 読み込み済み（週次用）" else "未作成（デフォルト使用）", color = if (e) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline) })

            if (saveStatus.isNotEmpty()) Text(saveStatus, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onSavePaths(logFolderInput.trim(), reviewFileInput.trim(), memoryFileInput.trim(), wikiFolderInput.trim(), promptFileInput.trim(), weeklyPromptFileInput.trim()); saveStatus = "✅ パスを保存しました" }, modifier = Modifier.fillMaxWidth()) { Text("パスを保存") }

            HorizontalDivider()
            Text("⏰ 自動レビュー通知", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("スケジュール実行", style = MaterialTheme.typography.bodyLarge)
                    Text(if (localScheduleEnabled) "毎日 ${"%02d".format(localHour)}:${"%02d".format(localMinute)} に通知" else "オフ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Switch(checked = localScheduleEnabled, onCheckedChange = { localScheduleEnabled = it; onScheduleChange(it, localHour, localMinute) })
            }
            if (localScheduleEnabled) {
                Text("実行時刻: ${"%02d".format(localHour)}:${"%02d".format(localMinute)}", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("時", style = MaterialTheme.typography.labelMedium); Button(onClick = { localHour = (localHour + 1) % 24 }) { Text("▲") }; Text("%02d".format(localHour), style = MaterialTheme.typography.titleMedium); Button(onClick = { localHour = (localHour - 1 + 24) % 24 }) { Text("▼") } }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("分", style = MaterialTheme.typography.labelMedium); Button(onClick = { localMinute = (localMinute + 15) % 60 }) { Text("▲") }; Text("%02d".format(localMinute), style = MaterialTheme.typography.titleMedium); Button(onClick = { localMinute = ((localMinute - 15) + 60) % 60 }) { Text("▼") } }
                }
                Button(onClick = { onScheduleChange(true, localHour, localMinute) }, modifier = Modifier.fillMaxWidth()) { Text("時刻を保存") }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Text("⚠️ 通知をタップしてアプリを開くと前日のレビューが自動で始まります", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
            }

            HorizontalDivider()
            Text("🧠 長期メモリ", style = MaterialTheme.typography.titleMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) { Text("メモリ参照を使用する", style = MaterialTheme.typography.bodyLarge); Text(if (memoryEnabled) "レビュー・チャットのコンテキストにmemory.mdを含めます" else "memory.mdをコンテキストに含めません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                Switch(checked = memoryEnabled, onCheckedChange = onMemoryToggle)
            }
            if (memoryExists) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("memory.md の状態", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline); Text("${memorySize}行 記録済み"); Text(memoryFilePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                }
            } else Text("memory.md はまだ作成されていません。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }

    // ─────────────────────────────────────────
    // メモリ関連
    // ─────────────────────────────────────────
    private fun loadMemory(memoryFilePath: String): String { val file = File(memoryFilePath); return if (file.exists()) file.readText() else "" }
    private fun appendToMemory(content: String, source: String, memoryFilePath: String) {
        val file = File(memoryFilePath); val date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val entry = "\n## $date（$source）\n$content\n"
        if (!file.exists()) { file.parentFile?.mkdirs(); file.writeText("# 長期メモリ\n$entry") } else file.appendText(entry)
    }
    private suspend fun extractMemoryCandidate(content: String): String = withContext(Dispatchers.IO) {
        ask("以下の内容から、長期的に記憶すべき重要な洞察・パターン・教訓を箇条書き3点以内で抽出してください。箇条書きのみ出力してください。\n\n$content")
    }
    private fun buildMemoryContext(enabled: Boolean, memoryFilePath: String): String { if (!enabled) return ""; val memory = loadMemory(memoryFilePath); return if (memory.isNotEmpty()) memory else "" }

    // ─────────────────────────────────────────
    // メモリ確認ダイアログ
    // ─────────────────────────────────────────
    @Composable
    fun MemoryDialog(candidate: String, onSave: () -> Unit, onDismiss: () -> Unit) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text("🧠 メモリ候補") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("以下の内容をmemory.mdに保存しますか？", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline); Text(candidate) } },
            confirmButton = { TextButton(onClick = onSave) { Text("保存する") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("スキップ") } })
    }

    // ─────────────────────────────────────────
    // ログ抽出（共通）
    // ─────────────────────────────────────────
    private fun extractLogContent(raw: String): String {
        return if (raw.startsWith(REVIEWED_MARKER)) {
            val idx = raw.indexOf(REVIEW_END_MARKER)
            if (idx >= 0) raw.substring(idx + REVIEW_END_MARKER.length).trimStart()
            else raw.substringAfter(REVIEWED_MARKER).trimStart().substringAfter("---").trimStart()
                .let { val i = it.indexOf("---"); if (i >= 0) it.substring(i + 3).trimStart() else it }
        } else raw
    }

    // ─────────────────────────────────────────
    // 共通LLM呼び出し（モデル管理・フォールバック付き）
    // ─────────────────────────────────────────
    private suspend fun ask(prompt: String): String {
        return withContext(Dispatchers.IO) {
            try {
                if (cachedModelAvailable == null) {
                    val testModel = buildModel(currentModelStage, currentModelPreference)
                    val status = testModel.checkStatus()
                    cachedModelAvailable = (status == 1) // 1 = AVAILABLE
                    if (!cachedModelAvailable!!) {
                        CyberdeckLog.rx("⚠️ ${currentModelStage}/${currentModelPreference} 利用不可 → STABLE/FULLにフォールバック")
                    }
                }

                val (model, tag) = if (cachedModelAvailable == true) {
                    Pair(buildModel(currentModelStage, currentModelPreference), "${currentModelStage}/${currentModelPreference}")
                } else {
                    Pair(buildModel("STABLE", "FULL"), "STABLE/FULL(FB)")
                }

                CyberdeckLog.tx("[$tag] $prompt")
                val response = model.generateContent(generateContentRequest(TextPart(prompt)) { maxOutputTokens = 256 })
                val text = response.candidates.firstOrNull()?.text ?: ""
                CyberdeckLog.rx(text)
                text
            } catch (e: Exception) {
                Log.e("Ask", "Error", e)
                CyberdeckLog.rx("❌ エラー: ${e.message}")
                ""
            }
        }
    }

    // ─────────────────────────────────────────
    // 日次レビュー画面
    // ─────────────────────────────────────────
    @Composable
    fun ReviewScreen(autoReview: Boolean, memoryEnabled: Boolean, logFolder: String, reviewFilePath: String, memoryFilePath: String, wikiFolder: String, promptFilePath: String) {
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
        var conceptQueue by remember { mutableStateOf(listOf<ConceptCandidate>()) }
        var conceptIndex by remember { mutableIntStateOf(0) }
        var showConceptDialog by remember { mutableStateOf(false) }
        var isExtractingConcepts by remember { mutableStateOf(false) }
        var wikiSourceName by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        val scrollState = rememberScrollState()

        fun clearResults() { summary = ""; improvements = ""; actions = ""; memoryCandidate = ""; status = "日付を選んでレビューを開始してください" }
        suspend fun startReview() {
            isProcessing = true; summary = ""; improvements = ""; actions = ""; memoryCandidate = ""; status = "処理中... (1/3) 総評を生成中"
            val result = runReview(selectedDate, memoryEnabled, logFolder, reviewFilePath, memoryFilePath, promptFilePath, { status = it }, { summary = it }, { improvements = it }, { actions = it })
            status = result; isProcessing = false
        }

        LaunchedEffect(autoReview) {
            if (autoReview) {
                selectedDate = LocalDate.now().minusDays(1)
                val logFile = File("$logFolder/${selectedDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"))}作業log.md")
                if (logFile.exists() && !logFile.readText().startsWith(REVIEWED_MARKER)) startReview()
                else status = "昨日のログは既にレビュー済みです"
            }
        }

        if (showOverwriteDialog) { AlertDialog(onDismissRequest = { showOverwriteDialog = false }, title = { Text("レビュー済みです") }, text = { Text("このログは既にレビューされています。上書きしますか？") }, confirmButton = { TextButton(onClick = { showOverwriteDialog = false; scope.launch { startReview() } }) { Text("上書きする") } }, dismissButton = { TextButton(onClick = { showOverwriteDialog = false }) { Text("キャンセル") } }) }
        if (showMemoryDialog && memoryCandidate.isNotEmpty()) { MemoryDialog(candidate = memoryCandidate, onSave = { appendToMemory(memoryCandidate, "日次レビュー", memoryFilePath); showMemoryDialog = false; status = "✅ memory.md に保存しました" }, onDismiss = { showMemoryDialog = false }) }
        if (showConceptDialog && conceptQueue.isNotEmpty() && conceptIndex < conceptQueue.size) {
            ConceptDialog(candidate = conceptQueue[conceptIndex], currentIndex = conceptIndex + 1, total = conceptQueue.size,
                onSave = { addAsNew, checkedConcepts -> scope.launch { val candidate = conceptQueue[conceptIndex]; withContext(Dispatchers.IO) { if (addAsNew) { appendToConceptsFile(wikiFolder, candidate.name, candidate.description); generateAndSaveWikiPage(candidate, wikiSourceName, wikiFolder) }; checkedConcepts.forEach { appendSourceToWikiPage(it, wikiSourceName, candidate.name, wikiFolder) } }; if (conceptIndex < conceptQueue.size - 1) conceptIndex++ else { showConceptDialog = false; status = "✅ Wiki概念を更新しました" } } },
                onSkip = { if (conceptIndex < conceptQueue.size - 1) conceptIndex++ else showConceptDialog = false })
        }

        Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Work Log Reviewer", style = MaterialTheme.typography.headlineMedium)
            if (memoryEnabled) Text("🧠 メモリ参照: ON", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text("対象日: ${selectedDate.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"))}", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { selectedDate = selectedDate.minusDays(1); clearResults() }) { Text("◀ 前日") }
                Button(onClick = { selectedDate = LocalDate.now(); clearResults() }) { Text("今日") }
                Button(onClick = { selectedDate = selectedDate.plusDays(1); clearResults() }) { Text("翌日 ▶") }
            }
            Button(onClick = { val logFile = File("$logFolder/${selectedDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"))}作業log.md"); if (logFile.exists() && logFile.readText().startsWith(REVIEWED_MARKER)) showOverwriteDialog = true else scope.launch { startReview() } }, enabled = !isProcessing, modifier = Modifier.fillMaxWidth()) { Text("レビュー開始") }
            if (isProcessing) CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            Text(text = status)
            if (summary.isNotEmpty()) { HorizontalDivider(); Text("### 総評", style = MaterialTheme.typography.titleMedium); Text(summary) }
            if (improvements.isNotEmpty()) { HorizontalDivider(); Text("### 改善提案", style = MaterialTheme.typography.titleMedium); Text(improvements) }
            if (actions.isNotEmpty()) { HorizontalDivider(); Text("### 次のアクション", style = MaterialTheme.typography.titleMedium); Text(actions) }
            if (summary.isNotEmpty()) {
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { isExtractingMemory = true; scope.launch { memoryCandidate = extractMemoryCandidate("総評: $summary\n改善提案: $improvements\n次のアクション: $actions"); isExtractingMemory = false; showMemoryDialog = true } }, enabled = !isExtractingMemory && !isExtractingConcepts, modifier = Modifier.weight(1f)) { Text(if (isExtractingMemory) "抽出中..." else "🧠 メモリ") }
                    Button(onClick = { isExtractingConcepts = true; wikiSourceName = "${selectedDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"))}作業log"; scope.launch { val candidates = extractConceptCandidates("$summary\n$improvements\n$actions", wikiFolder); conceptQueue = candidates; conceptIndex = 0; isExtractingConcepts = false; if (candidates.isNotEmpty()) showConceptDialog = true else status = "新しい概念は見つかりませんでした" } }, enabled = !isExtractingConcepts && !isExtractingMemory, modifier = Modifier.weight(1f)) { Text(if (isExtractingConcepts) "抽出中..." else "📖 Wiki概念") }
                }
            }
        }
    }

    // ─────────────────────────────────────────
    // 週次レビュー画面
    // ─────────────────────────────────────────
    @Composable
    fun WeeklyReviewScreen(memoryEnabled: Boolean, logFolder: String, reviewFilePath: String, memoryFilePath: String, wikiFolder: String, weeklyPromptFilePath: String) {
        var weekStart by remember { mutableStateOf(LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) }
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
        var conceptQueue by remember { mutableStateOf(listOf<ConceptCandidate>()) }
        var conceptIndex by remember { mutableIntStateOf(0) }
        var showConceptDialog by remember { mutableStateOf(false) }
        var isExtractingConcepts by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val weekEnd = weekStart.plusDays(6)
        val fmtDisplay = DateTimeFormatter.ofPattern("MM/dd")

        fun reset() { phase = WeeklyPhase.IDLE; status = ""; keyPoints = ""; questions = emptyList(); answers = emptyList(); currentInput = ""; currentQuestionIndex = 0; finalReport = ""; memoryCandidate = "" }

        if (showMemoryDialog && memoryCandidate.isNotEmpty()) { MemoryDialog(candidate = memoryCandidate, onSave = { appendToMemory(memoryCandidate, "週次レビュー", memoryFilePath); showMemoryDialog = false; status = "✅ memory.md に保存しました" }, onDismiss = { showMemoryDialog = false }) }
        if (showConceptDialog && conceptQueue.isNotEmpty() && conceptIndex < conceptQueue.size) {
            val fmtFile = DateTimeFormatter.ofPattern("yyyy.MM.dd"); val fmtEnd = DateTimeFormatter.ofPattern("MM.dd")
            val wikiSourceName = "WeeklyReview(${weekStart.format(fmtFile)}-${weekEnd.format(fmtEnd)})"
            ConceptDialog(candidate = conceptQueue[conceptIndex], currentIndex = conceptIndex + 1, total = conceptQueue.size,
                onSave = { addAsNew, checkedConcepts -> scope.launch { val candidate = conceptQueue[conceptIndex]; withContext(Dispatchers.IO) { if (addAsNew) { appendToConceptsFile(wikiFolder, candidate.name, candidate.description); generateAndSaveWikiPage(candidate, wikiSourceName, wikiFolder) }; checkedConcepts.forEach { appendSourceToWikiPage(it, wikiSourceName, candidate.name, wikiFolder) } }; if (conceptIndex < conceptQueue.size - 1) conceptIndex++ else { showConceptDialog = false; status = "✅ Wiki概念を更新しました" } } },
                onSkip = { if (conceptIndex < conceptQueue.size - 1) conceptIndex++ else showConceptDialog = false })
        }

        if (phase == WeeklyPhase.QUESTIONING) {
            Column(modifier = Modifier.fillMaxSize().imePadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("週次レビュー", style = MaterialTheme.typography.headlineMedium)
                Text("対象週: ${weekStart.format(fmtDisplay)} 〜 ${weekEnd.format(fmtDisplay)}", style = MaterialTheme.typography.bodyLarge)
                HorizontalDivider()
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { Text("📋 一週間の重要ポイント", style = MaterialTheme.typography.titleMedium); Text(keyPoints) }
                    item { HorizontalDivider() }
                    item { answers.take(currentQuestionIndex).forEachIndexed { i, answer -> if (answer.isNotEmpty()) { Text("Q${i + 1}: ${questions[i]}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline); Text("→ $answer", style = MaterialTheme.typography.bodySmall); Spacer(modifier = Modifier.height(4.dp)) } } }
                    if (questions.isNotEmpty() && currentQuestionIndex < questions.size) {
                        item {
                            Text("Q${currentQuestionIndex + 1}/${questions.size}", style = MaterialTheme.typography.labelMedium)
                            Text(questions[currentQuestionIndex], style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(value = currentInput, onValueChange = { currentInput = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("あなたの回答を入力...") }, minLines = 3)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = {
                                val newAnswers = answers.toMutableList(); newAnswers[currentQuestionIndex] = currentInput; answers = newAnswers; currentInput = ""
                                if (currentQuestionIndex < questions.size - 1) { currentQuestionIndex++ } else {
                                    phase = WeeklyPhase.GENERATING
                                    scope.launch {
                                        status = "レポートを生成中..."
                                        val report = generateWeeklyReport(weekStart, keyPoints, questions, newAnswers, weeklyPromptFilePath)
                                        finalReport = report
                                        val fmtFile = DateTimeFormatter.ofPattern("yyyy.MM.dd"); val fmtEnd = DateTimeFormatter.ofPattern("MM.dd")
                                        val fileName = "WeeklyReview(${weekStart.format(fmtFile)}-${weekEnd.format(fmtEnd)}).md"
                                        File("$logFolder/$fileName").writeText(report)
                                        phase = WeeklyPhase.DONE; status = "✅ $fileName に保存しました"
                                    }
                                }
                            }, enabled = currentInput.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(if (currentQuestionIndex < questions.size - 1) "次の質問へ →" else "レポートを生成する") }
                        }
                    }
                }
            }
        } else {
            val scrollState = rememberScrollState()
            Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("週次レビュー", style = MaterialTheme.typography.headlineMedium)
                if (memoryEnabled) Text("🧠 メモリ参照: ON", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text("対象週: ${weekStart.format(fmtDisplay)} 〜 ${weekEnd.format(fmtDisplay)}", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { weekStart = weekStart.minusWeeks(1); reset() }) { Text("◀ 前週") }
                    Button(onClick = { weekStart = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); reset() }) { Text("今週") }
                    Button(onClick = { weekStart = weekStart.plusWeeks(1); reset() }) { Text("翌週 ▶") }
                }
                when (phase) {
                    WeeklyPhase.IDLE -> Button(onClick = { phase = WeeklyPhase.ANALYZING; scope.launch { val result = analyzeWeek(weekStart, memoryEnabled, logFolder, reviewFilePath, memoryFilePath, weeklyPromptFilePath) { s -> status = s }; if (result == null) { status = "❌ ログが見つかりませんでした"; phase = WeeklyPhase.IDLE } else { keyPoints = result; status = "✅ 分析完了。AIが質問を生成中..."; val qs = generateQuestions(result, weeklyPromptFilePath); questions = qs; answers = MutableList(qs.size) { "" }; currentQuestionIndex = 0; currentInput = ""; phase = WeeklyPhase.QUESTIONING; status = "" } } }, modifier = Modifier.fillMaxWidth()) { Text("週次レビュー開始") }
                    WeeklyPhase.ANALYZING, WeeklyPhase.GENERATING -> { CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally)); Text(status) }
                    WeeklyPhase.DONE -> {
                        Text(status, color = MaterialTheme.colorScheme.primary)
                        HorizontalDivider(); Text("📄 週次レビュー", style = MaterialTheme.typography.titleMedium); Text(finalReport)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scope.launch { memoryCandidate = extractMemoryCandidate(finalReport); showMemoryDialog = true } }, enabled = !isExtractingMemory && !isExtractingConcepts, modifier = Modifier.weight(1f)) { Text("🧠 メモリ") }
                            Button(onClick = { isExtractingConcepts = true; scope.launch { val candidates = extractConceptCandidates(finalReport, wikiFolder); conceptQueue = candidates; conceptIndex = 0; isExtractingConcepts = false; if (candidates.isNotEmpty()) showConceptDialog = true else status = "新しい概念は見つかりませんでした" } }, enabled = !isExtractingConcepts && !isExtractingMemory, modifier = Modifier.weight(1f)) { Text(if (isExtractingConcepts) "抽出中..." else "📖 Wiki概念") }
                        }
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
    fun MemoReviewScreen(memoryEnabled: Boolean, logFolder: String, reviewFilePath: String, memoryFilePath: String, wikiFolder: String, promptFilePath: String) {
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
        var conceptQueue by remember { mutableStateOf(listOf<ConceptCandidate>()) }
        var conceptIndex by remember { mutableIntStateOf(0) }
        var showConceptDialog by remember { mutableStateOf(false) }
        var isExtractingConcepts by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val scrollState = rememberScrollState()
        val systemFiles = setOf(File(reviewFilePath).name, File(memoryFilePath).name)
        val vaultFiles = remember(logFolder) { File(logFolder).listFiles()?.filter { it.extension == "md" && it.name !in systemFiles }?.sortedByDescending { it.lastModified() } ?: emptyList() }

        fun reset() { phase = MemoReviewPhase.FILE_LIST; selectedFile = null; status = ""; summary = ""; improvements = ""; actions = ""; memoryCandidate = "" }
        suspend fun startMemoReview(file: File) {
            selectedFile = file; phase = MemoReviewPhase.REVIEWING; summary = ""; improvements = ""; actions = ""
            val result = reviewMemo(file, memoryEnabled, reviewFilePath, memoryFilePath, promptFilePath, { status = it }, { summary = it }, { improvements = it }, { actions = it })
            status = result; phase = MemoReviewPhase.DONE
        }

        if (showMemoryDialog && memoryCandidate.isNotEmpty()) { MemoryDialog(candidate = memoryCandidate, onSave = { appendToMemory(memoryCandidate, "メモレビュー", memoryFilePath); showMemoryDialog = false; status = "✅ memory.md に保存しました" }, onDismiss = { showMemoryDialog = false }) }
        if (showConceptDialog && conceptQueue.isNotEmpty() && conceptIndex < conceptQueue.size) {
            val wikiSourceName = selectedFile?.nameWithoutExtension ?: "メモ"
            ConceptDialog(candidate = conceptQueue[conceptIndex], currentIndex = conceptIndex + 1, total = conceptQueue.size,
                onSave = { addAsNew, checkedConcepts -> scope.launch { val candidate = conceptQueue[conceptIndex]; withContext(Dispatchers.IO) { if (addAsNew) { appendToConceptsFile(wikiFolder, candidate.name, candidate.description); generateAndSaveWikiPage(candidate, wikiSourceName, wikiFolder) }; checkedConcepts.forEach { appendSourceToWikiPage(it, wikiSourceName, candidate.name, wikiFolder) } }; if (conceptIndex < conceptQueue.size - 1) conceptIndex++ else { showConceptDialog = false; status = "✅ Wiki概念を更新しました" } } },
                onSkip = { if (conceptIndex < conceptQueue.size - 1) conceptIndex++ else showConceptDialog = false })
        }
        if (showOverwriteDialog) { AlertDialog(onDismissRequest = { showOverwriteDialog = false }, title = { Text("レビュー済みです") }, text = { Text("このメモは既にレビューされています。上書きしますか？") }, confirmButton = { TextButton(onClick = { showOverwriteDialog = false; pendingFile?.let { scope.launch { startMemoReview(it) } } }) { Text("上書きする") } }, dismissButton = { TextButton(onClick = { showOverwriteDialog = false }) { Text("キャンセル") } }) }

        when (phase) {
            MemoReviewPhase.FILE_LIST -> Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("メモレビュー", style = MaterialTheme.typography.headlineMedium)
                if (memoryEnabled) Text("🧠 メモリ参照: ON", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text("レビューするファイルを選んでください", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
                HorizontalDivider()
                if (vaultFiles.isEmpty()) Text("ファイルが見つかりません\n設定でフォルダパスを確認してください", color = MaterialTheme.colorScheme.outline)
                else LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(vaultFiles) { file ->
                        val isReviewed = runCatching { file.readText().startsWith(REVIEWED_MARKER) }.getOrDefault(false)
                        Card(modifier = Modifier.fillMaxWidth().clickable { if (isReviewed) { pendingFile = file; showOverwriteDialog = true } else scope.launch { startMemoReview(file) } }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) { Text(file.nameWithoutExtension, style = MaterialTheme.typography.bodyLarge); Text("${(file.length() / 1024).coerceAtLeast(1)} KB · ${java.text.SimpleDateFormat("yyyy/MM/dd").format(file.lastModified())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                                if (isReviewed) Text("✅", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            MemoReviewPhase.REVIEWING -> Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("メモレビュー", style = MaterialTheme.typography.headlineMedium); Text(selectedFile?.nameWithoutExtension ?: "", style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(24.dp)); CircularProgressIndicator(); Text(status)
            }
            MemoReviewPhase.DONE -> Column(modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(scrollState), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("メモレビュー", style = MaterialTheme.typography.headlineMedium); Text(selectedFile?.nameWithoutExtension ?: "", style = MaterialTheme.typography.titleMedium)
                Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                if (summary.isNotEmpty()) { HorizontalDivider(); Text("### 総評", style = MaterialTheme.typography.titleMedium); Text(summary) }
                if (improvements.isNotEmpty()) { HorizontalDivider(); Text("### 改善提案", style = MaterialTheme.typography.titleMedium); Text(improvements) }
                if (actions.isNotEmpty()) { HorizontalDivider(); Text("### 次のアクション", style = MaterialTheme.typography.titleMedium); Text(actions) }
                if (summary.isNotEmpty()) {
                    HorizontalDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { scope.launch { memoryCandidate = extractMemoryCandidate("総評: $summary\n改善提案: $improvements\n次のアクション: $actions"); showMemoryDialog = true } }, enabled = !isExtractingMemory && !isExtractingConcepts, modifier = Modifier.weight(1f)) { Text("🧠 メモリ") }
                        Button(onClick = { isExtractingConcepts = true; scope.launch { val candidates = extractConceptCandidates("総評: $summary\n改善提案: $improvements\n次のアクション: $actions", wikiFolder); conceptQueue = candidates; conceptIndex = 0; isExtractingConcepts = false; if (candidates.isNotEmpty()) showConceptDialog = true else status = "新しい概念は見つかりませんでした" } }, enabled = !isExtractingConcepts && !isExtractingMemory, modifier = Modifier.weight(1f)) { Text(if (isExtractingConcepts) "抽出中..." else "📖 Wiki概念") }
                    }
                }
                Button(onClick = { reset() }, modifier = Modifier.fillMaxWidth()) { Text("別のファイルをレビューする") }
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
        LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
        if (showMemoryDialog && memoryCandidate.isNotEmpty()) { MemoryDialog(candidate = memoryCandidate, onSave = { appendToMemory(memoryCandidate, "チャット", memoryFilePath); showMemoryDialog = false }, onDismiss = { showMemoryDialog = false }) }
        Column(modifier = Modifier.fillMaxSize().imePadding().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (messages.isEmpty()) return@TextButton; isExtractingMemory = true; scope.launch { val content = messages.joinToString("\n") { if (it.isUser) "ユーザー: ${it.text}" else "AI: ${it.text}" }; memoryCandidate = extractMemoryCandidate(content); isExtractingMemory = false; showMemoryDialog = true } }, enabled = messages.isNotEmpty() && !isThinking && !isExtractingMemory) { Text(if (isExtractingMemory) "🧠 抽出中..." else "🧠 メモリ候補を確認") }
                    if (memoryEnabled) Text("ON", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = { messages.clear() }, enabled = messages.isNotEmpty() && !isThinking) { Text("会話をクリア") }
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) { message ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start) {
                        Card(modifier = Modifier.widthIn(max = 280.dp), colors = CardDefaults.cardColors(containerColor = if (message.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) { Text(message.text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                if (isThinking) { item { Row(horizontalArrangement = Arrangement.Start) { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Text("考え中...", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) } } } }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("メッセージを入力...") }, enabled = !isThinking)
                Button(onClick = { if (input.isBlank()) return@Button; val userMessage = input.trim(); input = ""; messages.add(ChatMessage(userMessage, isUser = true)); isThinking = true; val history = messages.toList(); scope.launch { val reply = chatLlm(userMessage, history.dropLast(1), memoryEnabled, reviewFilePath, memoryFilePath); messages.add(ChatMessage(reply, isUser = false)); isThinking = false } }, enabled = !isThinking && input.isNotBlank()) { Text("送信") }
            }
        }
    }

    // ─────────────────────────────────────────
    // チャット用LLM呼び出し
    // ─────────────────────────────────────────
    private suspend fun chatLlm(userMessage: String, history: List<ChatMessage>, memoryEnabled: Boolean, reviewFilePath: String, memoryFilePath: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val criteria = runCatching { File(reviewFilePath).readText() }.getOrDefault("")
                val memory = buildMemoryContext(memoryEnabled, memoryFilePath)
                val context = buildString {
                    if (memory.isNotEmpty()) append("【参考情報・コンテキスト（回答の対象ではありません）】\n$memory\n\n")
                    if (criteria.isNotEmpty()) append("【評価基準（参考）】\n$criteria\n\n")
                }
                val historyText = history.takeLast(6).let { recent -> if (recent.isEmpty()) "" else "【これまでの会話】\n" + recent.joinToString("\n") { if (it.isUser) "ユーザー: ${it.text}" else "アシスタント: ${it.text}" } + "\n\n" }
                val instruction = if (memory.isNotEmpty()) "※参考情報はコンテキストとして活用してください。回答対象はユーザーの質問のみです。\n\n" else ""
                val result = ask("${context}${historyText}${instruction}【ユーザーの質問】\n$userMessage\n\n日本語で簡潔に3文以内で答えてください。")
                result.ifEmpty { "応答が得られませんでした" }
            } catch (e: Exception) { Log.e("Chat", "Error", e); "エラー: ${e.message}" }
        }
    }

    // ─────────────────────────────────────────
    // レビュー処理
    // ─────────────────────────────────────────
    private suspend fun runReview(date: LocalDate, memoryEnabled: Boolean, logFolder: String, reviewFilePath: String, memoryFilePath: String, promptFilePath: String, onProgress: suspend (String) -> Unit, onSummary: suspend (String) -> Unit, onImprovements: suspend (String) -> Unit, onActions: suspend (String) -> Unit): String {
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
                val context = buildString { if (memory.isNotEmpty()) append("【参考情報・コンテキスト（レビュー対象ではありません）】\n$memory\n\n"); append("【評価基準（参考）】\n$criteria\n\n") }
                val target = "【本日の作業ログ（レビュー対象）】\n$logContent"
                val instruction = "\n\n※必ず【本日の作業ログ】のみを対象にしてください。"
                val summaryInstruction = loadPromptSection(promptFilePath, "総評") ?: "本日の作業ログの総評を日本語2文以内で書いてください。"
                val improvementsInstruction = loadPromptSection(promptFilePath, "改善提案") ?: "本日の作業ログへの改善提案を日本語で箇条書き3点のみ書いてください。"
                val actionsInstruction = loadPromptSection(promptFilePath, "次のアクション") ?: "本日の作業ログをふまえた明日やるべきことを以下の形式で3点のみ出力してください。説明文・見出し・その他のテキストは一切不要です。\n- [ ] タスク内容\n- [ ] タスク内容\n- [ ] タスク内容"

                onProgress("処理中... (1/3) 総評を生成中")
                val summaryText = ask("$context$target$instruction\n\n$summaryInstruction")
                onSummary(summaryText)
                onProgress("処理中... (2/3) 改善提案を生成中")
                val improvementsText = ask("$context$target$instruction\n\n$improvementsInstruction")
                onImprovements(improvementsText)
                onProgress("処理中... (3/3) 次のアクションを生成中")
                val actionsText = filterActions(ask("$context$target$instruction\n\n$actionsInstruction"))
                onActions(actionsText)

                val reviewText = "$REVIEWED_MARKER\n---\n## レビュー ${date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))}\n\n### 総評\n$summaryText\n\n### 改善提案\n$improvementsText\n\n### 次のアクション\n$actionsText\n\n---\n$REVIEW_END_MARKER"
                logFile.writeText("$reviewText\n\n$logContent")
                "✅ レビュー完了！"
            } catch (e: Exception) { Log.e("Reviewer", "Error", e); "❌ エラー: ${e.message}" }
        }
    }

    private suspend fun reviewMemo(file: File, memoryEnabled: Boolean, reviewFilePath: String, memoryFilePath: String, promptFilePath: String, onProgress: suspend (String) -> Unit, onSummary: suspend (String) -> Unit, onImprovements: suspend (String) -> Unit, onActions: suspend (String) -> Unit): String {
        return withContext(Dispatchers.IO) {
            try {
                val reviewFile = File(reviewFilePath)
                if (!reviewFile.exists()) return@withContext "❌ REVIEW.md が見つかりません: $reviewFilePath"
                val memoContent = extractLogContent(file.readText())
                val criteria = reviewFile.readText()
                val memory = buildMemoryContext(memoryEnabled, memoryFilePath)
                val context = buildString { if (memory.isNotEmpty()) append("【参考情報・コンテキスト（レビュー対象ではありません）】\n$memory\n\n"); append("【評価基準（参考）】\n$criteria\n\n") }
                val target = "【メモ（レビュー対象）】\n$memoContent"
                val instruction = "\n\n※必ず【メモ】のみを対象にしてください。"
                val summaryInstruction = loadPromptSection(promptFilePath, "総評") ?: "評価基準に基づきこのメモの総評を日本語2文以内で書いてください。"
                val improvementsInstruction = loadPromptSection(promptFilePath, "改善提案") ?: "評価基準に基づきこのメモへの改善提案を日本語で箇条書き3点のみ書いてください。"
                val actionsInstruction = loadPromptSection(promptFilePath, "次のアクション") ?: "このメモをふまえた次のアクションを以下の形式で3点のみ出力してください。説明文・見出し・その他のテキストは一切不要です。\n- [ ] タスク内容\n- [ ] タスク内容\n- [ ] タスク内容"

                onProgress("処理中... (1/3) 総評を生成中")
                val summaryText = ask("$context$target$instruction\n\n$summaryInstruction")
                onSummary(summaryText)
                onProgress("処理中... (2/3) 改善提案を生成中")
                val improvementsText = ask("$context$target$instruction\n\n$improvementsInstruction")
                onImprovements(improvementsText)
                onProgress("処理中... (3/3) 次のアクションを生成中")
                val actionsText = filterActions(ask("$context$target$instruction\n\n$actionsInstruction"))
                onActions(actionsText)

                val date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                val reviewText = "$REVIEWED_MARKER\n---\n## レビュー $date（${file.nameWithoutExtension}）\n\n### 総評\n$summaryText\n\n### 改善提案\n$improvementsText\n\n### 次のアクション\n$actionsText\n\n---\n$REVIEW_END_MARKER"
                file.writeText("$reviewText\n\n$memoContent")
                "✅ レビュー完了！"
            } catch (e: Exception) { Log.e("MemoReview", "Error", e); "❌ エラー: ${e.message}" }
        }
    }

    private suspend fun analyzeWeek(weekStart: LocalDate, memoryEnabled: Boolean, logFolder: String, reviewFilePath: String, memoryFilePath: String, weeklyPromptFilePath: String, onProgress: (String) -> Unit): String? {
        return withContext(Dispatchers.IO) {
            val criteria = runCatching { File(reviewFilePath).readText() }.getOrDefault("")
            val memory = buildMemoryContext(memoryEnabled, memoryFilePath)
            val logTexts = mutableListOf<String>()
            for (i in 0..6) { val date = weekStart.plusDays(i.toLong()); val logFile = File("$logFolder/${date.format(DateTimeFormatter.ofPattern("yyyyMMdd"))}作業log.md"); if (logFile.exists()) logTexts.add("【${date.format(DateTimeFormatter.ofPattern("MM/dd(E)"))}】\n${extractLogContent(logFile.readText())}") }
            if (logTexts.isEmpty()) return@withContext null
            val allLogs = logTexts.joinToString("\n\n---\n\n")
            val context = buildString { if (memory.isNotEmpty()) append("【参考情報・コンテキスト（分析対象ではありません）】\n$memory\n\n"); if (criteria.isNotEmpty()) append("【評価基準（参考）】\n$criteria\n\n") }
            val target = "【一週間の作業ログ（分析対象）】\n$allLogs"
            val instruction = "\n\n※必ず【一週間の作業ログ】のみを対象にしてください。"
            val achievementsInstruction = loadPromptSection(weeklyPromptFilePath, "成果") ?: "この一週間の作業ログから特に重要な成果・出来事を日本語で箇条書き3点にまとめてください。"
            val issuesInstruction = loadPromptSection(weeklyPromptFilePath, "課題") ?: "この一週間の作業ログから見られた課題・改善すべき点を日本語で箇条書き3点にまとめてください。"
            onProgress("(1/2) 成果・出来事を分析中...")
            val part1 = ask("$context$target$instruction\n\n$achievementsInstruction")
            onProgress("(2/2) 課題・改善点を分析中...")
            val part2 = ask("$context$target$instruction\n\n$issuesInstruction")
            "【成果・出来事】\n$part1\n\n【課題・改善点】\n$part2"
        }
    }

    private suspend fun generateQuestions(keyPoints: String, weeklyPromptFilePath: String): List<String> {
        return withContext(Dispatchers.IO) {
            val instruction = loadPromptSection(weeklyPromptFilePath, "質問") ?: "担当者の主観的な印象や感情を引き出すための質問を3つ作成してください。番号なし・質問文のみを1行ずつ出力してください。"
            ask("以下は一週間の作業の重要ポイントです。\n$keyPoints\n\nこの内容をふまえて、$instruction")
                .lines().map { it.trim() }.filter { it.isNotEmpty() }.take(3)
        }
    }

    private suspend fun generateWeeklyReport(weekStart: LocalDate, keyPoints: String, questions: List<String>, answers: List<String>, weeklyPromptFilePath: String): String {
        return withContext(Dispatchers.IO) {
            val weekEnd = weekStart.plusDays(6)
            val fmtFile = DateTimeFormatter.ofPattern("yyyy.MM.dd"); val fmtEnd = DateTimeFormatter.ofPattern("MM.dd")
            val qaText = questions.zip(answers).joinToString("\n\n") { (q, a) -> "Q: $q\nA: $a" }
            val conclusionInstruction = loadPromptSection(weeklyPromptFilePath, "総合所見") ?: "一週間の総合所見を日本語2文以内で書いてください。"
            val conclusion = ask("以下の情報をもとに、$conclusionInstruction\n重要ポイント:\n$keyPoints\nQ&A:\n$qaText")
            val qaSection = questions.zip(answers).mapIndexed { i, (q, a) -> "### Q${i + 1}: $q\n$a" }.joinToString("\n\n")
            "# WeeklyReview(${weekStart.format(fmtFile)}-${weekEnd.format(fmtEnd)})\n\n## 重要ポイント\n$keyPoints\n\n## 振り返りQ&A\n$qaSection\n\n## 総合所見\n$conclusion"
        }
    }
}