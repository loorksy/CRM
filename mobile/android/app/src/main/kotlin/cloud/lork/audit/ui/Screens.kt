@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cloud.lork.audit

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.lork.audit.data.Sources
import cloud.lork.audit.data.decodeColumns
import cloud.lork.audit.data.decodeSources
import cloud.lork.audit.db.CycleEntity
import cloud.lork.audit.db.SendItemEntity
import cloud.lork.audit.engine.AuditColumns
import cloud.lork.audit.engine.DriveFile
import cloud.lork.audit.engine.LinkEvent
import cloud.lork.audit.engine.LinkPhase
import cloud.lork.audit.engine.ParseMode
import cloud.lork.audit.engine.Readiness
import cloud.lork.audit.engine.auditReadiness
import cloud.lork.audit.engine.parseMessages
import cloud.lork.audit.engine.parseSpreadsheetId
import cloud.lork.audit.engine.suggestColumns
import cloud.lork.audit.send.AuditAccessibilityService
import cloud.lork.audit.send.FloatingBubbleService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { Audit, Send, Settings }

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as LorkApp
    val link = graph.session.state
    var screen by mutableStateOf(Screen.Audit)
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    var cycle by mutableStateOf<CycleEntity?>(null)
    var rows by mutableStateOf<List<String>>(emptyList())
    var planId by mutableStateOf<String?>(null)
    var planBlocked by mutableStateOf(false)
    var force by mutableStateOf(false)
    var query by mutableStateOf("")
    var searchText by mutableStateOf("")
    var showRows by mutableStateOf(false)
    var sources by mutableStateOf(Sources())
    var discount by mutableStateOf("0")
    var agentColor by mutableStateOf("#8b5cf6")
    var managementColor by mutableStateOf("#facc15")
    var colUser by mutableStateOf("C")
    var colTitle by mutableStateOf("D")
    var colSalary by mutableStateOf("L")
    var colStatus by mutableStateOf("")
    var colMgmt by mutableStateOf("A")
    var colAgent by mutableStateOf("A")
    var colAgentSalary by mutableStateOf("D")
    var files by mutableStateOf<List<DriveFile>>(emptyList())
    var fileMessage by mutableStateOf("")
    var fileQuery by mutableStateOf("")
    var sheetTitles by mutableStateOf<List<String>>(emptyList())
    var selectedFile by mutableStateOf("")
    var selectedFileId by mutableStateOf("")
    var role by mutableStateOf("إدارة")
    var pasted by mutableStateOf("")
    var columnHint by mutableStateOf("")
    var rawMessages by mutableStateOf("")
    var mpm by mutableStateOf(10)
    var confirmedChat by mutableStateOf(false)
    var sendItems by mutableStateOf<List<SendItemEntity>>(emptyList())
    var sendState by mutableStateOf("IDLE")

    fun reload() {
        val email = link.value.email ?: return
        val current = graph.store.ensureCycle(email)
        cycle = current
        sources = decodeSources(current.sourcesJson)
        val columns = decodeColumns(current.columnsJson)
        discount = trimNumber(current.discountRate)
        agentColor = current.agentColor
        managementColor = current.managementColor
        colUser = columns.userInfoUserIdCol
        colTitle = columns.userInfoTitleCol
        colSalary = columns.userInfoSalaryCol
        colStatus = columns.userInfoStatusCol
        colMgmt = columns.cycleMgmtUserIdCol
        colAgent = columns.cycleAgentUserIdCol
        colAgentSalary = columns.cycleAgentSalaryCol
        rows = graph.store.rowsPreview(current)
        refreshSend()
    }

    fun snapshotLine(): String {
        val current = cycle ?: return "لا توجد دورة محفوظة."
        val snap = graph.db.dao().latestSnapshot(current.id, current.accountEmail)
            ?: return "لا توجد نسخة محلية لهذه الدورة."
        val whenText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale("ar")).format(Date(snap.createdAt))
        return "النسخة المحلية: $whenText · ${snap.rowCount} صفاً في معلومات المستخدمين."
    }

    fun runAudit(context: Context) {
        val current = cycle
        val advice = auditReadiness(link.value, current?.let { hasSnapshot(it) } == true, context.isOnline())
        if (current == null || advice.readiness != Readiness.READY) {
            message = advice.message
            when (advice.readiness) {
                Readiness.NEED_DOWNLOAD -> if (context.isOnline() && current != null) download(thenAudit = true, context = context)
                Readiness.NEED_SOURCES, Readiness.NEED_ACCOUNT, Readiness.NEED_AUTH -> screen = Screen.Settings
                else -> Unit
            }
            return
        }
        if (busy) return
        busy = true
        viewModelScope.launch(Dispatchers.IO) {
            val (plan, text) = graph.store.preview(current, context.isOnline(), force)
            withContext(Dispatchers.Main) {
                busy = false
                message = text
                planBlocked = plan?.blocked == true
                planId = if (plan?.blocked == false) plan.planId else null
                rows = graph.store.rowsPreview(current)
            }
        }
    }

    fun approve() {
        val current = cycle ?: return
        val id = planId ?: return
        if (busy) return
        busy = true
        viewModelScope.launch(Dispatchers.IO) {
            val text = graph.store.approve(current, id)
            withContext(Dispatchers.Main) {
                busy = false
                message = text
                cycle = graph.db.dao().cycle(current.id)
                if (text.startsWith("تم اعتماد")) planId = null
            }
        }
    }

    fun download(thenAudit: Boolean, context: Context) {
        val current = cycle ?: return
        if (!sources.complete) {
            message = "حدد ملفات وأوراق الإدارة والوكيل ومعلومات المستخدمين أولاً."
            screen = Screen.Settings
            return
        }
        if (!context.isOnline()) {
            message = "يلزم اتصال مرة واحدة لتنزيل بيانات الدورة. بعدها يعمل التدقيق دون إنترنت."
            return
        }
        if (busy) return
        busy = true
        viewModelScope.launch(Dispatchers.IO) {
            val text = graph.store.download(current)
            withContext(Dispatchers.Main) {
                busy = false
                message = text
                if (text.startsWith("تم تنزيل")) {
                    graph.session.note(LinkEvent.DataReady)
                    rows = graph.store.rowsPreview(current)
                    if (thenAudit) runAudit(context)
                }
            }
        }
    }

    fun search() {
        val current = cycle ?: return
        searchText = graph.store.search(current, query)
    }

    fun manual() {
        val current = cycle ?: return
        message = graph.store.manual(current, query)
        search()
    }

    fun savePreferences() {
        val current = cycle ?: return
        val rate = discount.replace(',', '.').toDoubleOrNull() ?: 0.0
        val columns = AuditColumns(colUser, colTitle, colSalary, colStatus, colMgmt, colAgent, colAgentSalary)
        graph.store.saveSources(current, sources, columns, rate, agentColor, managementColor)
        cycle = graph.db.dao().cycle(current.id)
        graph.session.note(if (sources.complete) LinkEvent.SourcesReady else LinkEvent.SourcesPartial)
        message = if (sources.complete) "حُفظ الربط. الخطوة التالية تنزيل بيانات الدورة." else "حُفظ ما اكتمل، وما زال أحد المصادر ناقصاً."
    }

    fun loadFiles(context: Context) {
        if (link.value.phase == LinkPhase.NONE || link.value.phase == LinkPhase.CANCELED || link.value.phase == LinkPhase.AUTH_FAILED || link.value.phase == LinkPhase.WAITING_CONSENT) {
            fileMessage = "الربط لم يكتمل بعد. أعد الربط قبل عرض الملفات."
            return
        }
        if (!context.isOnline()) {
            fileMessage = "عرض ملفات Drive يحتاج اتصالاً."
            return
        }
        if (busy) return
        busy = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { graph.sheets.listAllSpreadsheets() }.getOrElse {
                withContext(Dispatchers.Main) {
                    busy = false
                    fileMessage = "تعذر قراءة قائمة الملفات."
                }
                return@launch
            }
            withContext(Dispatchers.Main) {
                busy = false
                if (result.errorCode != null) {
                    files = emptyList()
                    fileMessage = result.message
                } else {
                    files = result.files
                    fileMessage = result.message
                }
            }
        }
    }

    fun openFile(id: String, name: String) {
        if (busy) return
        busy = true
        selectedFileId = id
        selectedFile = "$name · $id"
        viewModelScope.launch(Dispatchers.IO) {
            val titles = runCatching { graph.sheets.listSheets(id) }.getOrElse { emptyList() }
            val header = titles.firstOrNull()?.let { title ->
                runCatching { graph.sheets.read(id, title).firstOrNull() }.getOrNull()
            }
            withContext(Dispatchers.Main) {
                busy = false
                sheetTitles = titles
                if (titles.isEmpty()) {
                    fileMessage = "تعذر قراءة أوراق هذا الملف أو أنه بلا أوراق."
                } else if (header != null) {
                    val guess = suggestColumns(header)
                    columnHint = if (guess.ambiguous) {
                        "عناوين الأعمدة غير حاسمة. راجع أحرف الأعمدة يدوياً قبل الحفظ."
                    } else {
                        "اقتراح من العناوين: المستخدم ${guess.userId ?: "—"}، العنوان ${guess.title ?: "—"}، الراتب ${guess.salary ?: "—"}."
                    }
                    guess.userId?.let { colUser = it }
                    guess.title?.let { colTitle = it }
                    guess.salary?.let { colSalary = it }
                    guess.status?.let { colStatus = it }
                }
            }
        }
    }

    fun chooseSheet(title: String) {
        val id = selectedFileId
        if (id.isBlank()) return
        sources = when (role) {
            "وكيل" -> sources.copy(agentSs = id, agentSheet = title)
            "مستخدمون" -> sources.copy(userSs = id, userSheet = title)
            else -> sources.copy(mgmtSs = id, mgmtSheet = title)
        }
        message = "اختيرت ورقة «$title» لدور $role. احفظ الربط بعد اختيار الأدوار الثلاثة."
    }

    fun applyPastedId() {
        val id = parseSpreadsheetId(pasted)
        if (id == null) {
            message = "الرابط أو المعرّف غير مفهوم."
            return
        }
        selectedFile = "ملف ملصوق · $id"
        openFile(id, "ملف ملصوق")
    }

    fun startSend(context: Context) {
        if (busy) return
        if (!confirmedChat) {
            message = "أكّد أن محادثة واتساب المفتوحة هي الوجهة. التطبيق يكتب في المحادثة الظاهرة فقط."
            return
        }
        val enabled = accessibilityEnabled(context)
        val bound = AuditAccessibilityService.instance != null
        if (!enabled || !bound) {
            message = "خدمة إمكانية الوصول غير متصلة الآن. ظهورها في الإعدادات لا يكفي."
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            message = "إذن الظهور فوق التطبيقات غير ممنوح."
            return
        }
        val messages = parseMessages(rawMessages, ParseMode.PARAGRAPH_THEN_LINES)
        if (messages.isEmpty()) {
            message = "لا توجد رسائل. افصل بين الرسائل بسطر فارغ."
            return
        }
        val whatsApp = context.packageManager.getLaunchIntentForPackage("com.whatsapp")
            ?: context.packageManager.getLaunchIntentForPackage("com.whatsapp.w4b")
        if (whatsApp == null) {
            message = "واتساب غير مثبت على هذا الجهاز."
            return
        }
        val dao = graph.db.dao()
        dao.clearItems()
        dao.insertItems(messages.mapIndexed { index, body -> SendItemEntity("m$index", index, body, "READY", "جاهزة") })
        dao.saveSession(
            cloud.lork.audit.db.SendSessionEntity(
                state = "SENDING",
                idx = 0,
                mpm = mpm.coerceIn(1, 1000),
                chatKey = null,
                rawText = rawMessages,
            ),
        )
        ContextCompat.startForegroundService(context, Intent(context, FloatingBubbleService::class.java))
        AuditAccessibilityService.instance?.startLoop()
        context.startActivity(whatsApp)
        refreshSend()
        message = "بدأ الإرسال داخل المحادثة المفتوحة. الضغط على زر الإرسال لا يثبت وصول الرسالة."
    }

    fun pauseSend() {
        val dao = graph.db.dao()
        val session = dao.session() ?: return
        dao.saveSession(session.copy(state = "PAUSED"))
        AuditAccessibilityService.instance?.stopLoop()
        refreshSend()
        message = "توقف الإرسال مؤقتاً وبقي الطابور."
    }

    fun resumeSend() {
        if (AuditAccessibilityService.instance == null) {
            message = "لا يمكن الاستئناف وخدمة إمكانية الوصول غير متصلة."
            return
        }
        val dao = graph.db.dao()
        val session = dao.session() ?: return
        if (dao.items().any { it.status == "UNCERTAIN" || it.status == "EXECUTION_STARTED" }) {
            message = "توجد رسالة غير مؤكدة. راجعها قبل الاستئناف حتى لا تُعاد تلقائياً."
            return
        }
        dao.saveSession(session.copy(state = "SENDING"))
        AuditAccessibilityService.instance?.startLoop()
        refreshSend()
    }

    fun stopSend(context: Context) {
        val dao = graph.db.dao()
        dao.items().filter { it.status == "READY" || it.status == "TEXT_PREPARED" }.forEach {
            dao.updateItem(it.itemId, "SKIPPED", "أُوقف الإرسال")
        }
        dao.session()?.let { dao.saveSession(it.copy(state = "STOPPED")) }
        AuditAccessibilityService.instance?.stopLoop()
        context.stopService(Intent(context, FloatingBubbleService::class.java))
        refreshSend()
        message = "أُوقف الإرسال. الرسائل غير المؤكدة بقيت للمراجعة."
    }

    fun reviewUncertain() {
        val dao = graph.db.dao()
        val uncertain = dao.items().filter { it.status == "UNCERTAIN" || it.status == "EXECUTION_STARTED" }
        if (uncertain.isEmpty()) {
            message = "لا توجد رسائل غير مؤكدة."
            return
        }
        uncertain.forEach { dao.updateItem(it.itemId, "READY", "أُعيد تجهيزها بعد مراجعة. لن تُرسل حتى تضغط استئناف.") }
        dao.session()?.let { dao.saveSession(it.copy(state = "PAUSED")) }
        refreshSend()
        message = "أُعيد تجهيز الرسائل غير المؤكدة وبقي الإرسال متوقفاً."
    }

    fun export(context: Context) {
        val email = link.value.email
        if (email.isNullOrBlank()) {
            message = "لا يوجد حساب لربط التصدير به."
            return
        }
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val file = File(dir, "lork-audit.json")
        file.writeText(graph.store.exportJson(email))
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "تصدير النسخة المحلية"))
    }

    fun copyDiagnostic(context: Context) {
        val text = diagnostic(context)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("lork", text))
        message = "نُسخ التشخيص. لا يحتوي رمزاً سرياً."
    }

    fun diagnostic(context: Context): String {
        val current = cycle
        val snap = current?.let { graph.db.dao().latestSnapshot(it.id, it.accountEmail) }
        return listOf(
            "الإصدار ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "النوع ${if (BuildConfig.DEBUG) "debug" else "release"}",
            "الحزمة ${BuildConfig.APPLICATION_ID}",
            "الحساب ${link.value.email ?: "—"}",
            "المرحلة ${link.value.phase.name}",
            "الرمز ${link.value.diagnostic}",
            "إمكانية الوصول مفعّلة=${accessibilityEnabled(context)} متصلة=${AuditAccessibilityService.instance != null}",
            "الظهور فوق التطبيقات=${Settings.canDrawOverlays(context)}",
            "النسخة المحلية صفوف=${snap?.rowCount ?: 0}",
            "الإرسال ${sendState}",
        ).joinToString("\n")
    }

    fun refreshSend() {
        val dao = graph.db.dao()
        sendItems = dao.items()
        sendState = dao.session()?.state ?: "IDLE"
        if (rawMessages.isBlank()) rawMessages = dao.session()?.rawText.orEmpty()
        if (dao.session() != null) mpm = dao.session()!!.mpm
    }

    private fun hasSnapshot(current: CycleEntity): Boolean =
        graph.db.dao().latestSnapshot(current.id, current.accountEmail) != null

    private fun trimNumber(value: Double): String =
        if (value == kotlin.math.floor(value)) value.toLong().toString() else value.toString()
}

fun accessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(context, AuditAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
}

private fun Context.isOnline(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    return manager.activeNetwork != null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(model: HomeViewModel = viewModel()) {
    val link by model.link.collectAsState()
    val context = LocalContext.current
    val scheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    androidx.compose.runtime.LaunchedEffect(link.email) { model.reload() }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = scheme) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("تدقيق لورك") },
                        actions = {
                            IconButton(onClick = { model.screen = Screen.Settings }) {
                                Icon(Icons.Filled.Settings, contentDescription = "الإعدادات")
                            }
                        },
                    )
                },
            ) { padding ->
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Choice("التدقيق", model.screen == Screen.Audit) { model.screen = Screen.Audit }
                        Choice("الإرسال", model.screen == Screen.Send) { model.screen = Screen.Send }
                    }
                    if (model.message.isNotBlank()) Text(model.message)
                    when (model.screen) {
                        Screen.Audit -> AuditPage(model, link.email, link.message, context)
                        Screen.Send -> SendPage(model, context)
                        Screen.Settings -> SettingsPage(model, context)
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) Button(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
    else OutlinedButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
}

@Composable
private fun AuditPage(model: HomeViewModel, email: String?, linkMessage: String, context: Context) {
    val cycle = model.cycle
    val advice = auditReadiness(
        model.link.value,
        cycle?.let { model.snapshotLine().startsWith("النسخة") } == true,
        context.isOnline(),
    )
    Text(email?.let { "الحساب: $it" } ?: "لا يوجد حساب مرتبط", fontWeight = FontWeight.Bold)
    Text(linkMessage)
    Text(if (cycle == null) "لا توجد دورة بعد الربط." else "الدورة: ${cycle.name}")
    Text(model.snapshotLine())
    Text(advice.message)
    WideButton("مزامنة / تنزيل بيانات الدورة", !model.busy) { model.download(false, context) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = model.force, onCheckedChange = { model.force = it })
        Text("فرض الإعادة إذا لم يتغيّر جدول المستخدمين")
    }
    WideButton(advice.action, !model.busy) { model.runAudit(context) }
    if (model.planId != null) {
        WideButton("اعتماد الكتابة في Google", !model.busy) { model.approve() }
        Text("الاعتماد هو الذي يكتب. تنفيذ التدقيق يعرض الخطة فقط.")
    }
    OutlinedTextField(
        value = model.query,
        onValueChange = { model.query = it },
        label = { Text("رقم المستخدم") },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { model.search() }, enabled = !model.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("بحث") }
        OutlinedButton(onClick = { model.manual() }, enabled = !model.busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("تدقيق يدوي") }
    }
    if (model.searchText.isNotBlank()) Text(model.searchText)
    TextButton(onClick = { model.showRows = !model.showRows }) {
        Text(if (model.showRows) "إخفاء النتائج" else "عرض ملخص النتائج")
    }
    if (model.showRows) {
        if (model.rows.isEmpty()) Text("لا توجد نتائج محلية بعد.")
        model.rows.forEach { Text(it) }
    }
}

@Composable
private fun SendPage(model: HomeViewModel, context: Context) {
    val notices = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    Text("الوجهة هي محادثة واتساب المفتوحة الآن، كما في الإرسال الأصلي. لا تُرسل القائمة إلى أرقام.")
    Text(permissionLine(context))
    OutlinedTextField(
        value = model.rawMessages,
        onValueChange = { model.rawMessages = it },
        label = { Text("الرسائل") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 4,
    )
    val preview = parseMessages(model.rawMessages, ParseMode.PARAGRAPH_THEN_LINES)
    Text("المعاينة: ${preview.size} رسالة")
    preview.take(8).forEachIndexed { index, body -> Text("${index + 1}. ${body.take(180)}") }
    Text("السرعة: ${model.mpm} رسالة في الدقيقة")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(10, 60, 300, 1000).forEach { speed ->
            FilterChip(selected = model.mpm == speed, onClick = { model.mpm = speed }, label = { Text("$speed") })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = model.confirmedChat, onCheckedChange = { model.confirmedChat = it })
        Text("المحادثة المفتوحة هي الوجهة")
    }
    WideButton("تفعيل إمكانية الوصول") {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    WideButton("السماح بالظهور فوق التطبيقات") {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        context.startActivity(intent)
    }
    WideButton("بدء الإرسال", !model.busy) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notices.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        model.startSend(context)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { model.pauseSend() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("إيقاف مؤقت") }
        OutlinedButton(onClick = { model.resumeSend() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("استئناف") }
        OutlinedButton(onClick = { model.stopSend(context) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("إيقاف") }
    }
    Text("حالة الطابور: ${model.sendState}. تم الضغط لا يعني أن الرسالة وصلت.")
    WideButton("مراجعة غير المؤكدة وإعادة تجهيزها") { model.reviewUncertain() }
    model.sendItems.forEach { item ->
        Text("${item.position + 1}. ${statusArabic(item.status)} — ${item.body.take(80)}")
        if (item.detail.isNotBlank()) Text(item.detail)
    }
    Text(
        "الإرسال يكتب داخل حقل واتساب المعروف ثم يضغط زر الإرسال. إذا غادرت التطبيق أو تغيّرت المحادثة أو اختفى الحقل يتوقف. الحالة غير المؤكدة لا تُعاد وحدها.",
    )
}

@Composable
private fun SettingsPage(model: HomeViewModel, context: Context) {
    val link by model.link.collectAsState()
    Text("ربط Google", fontWeight = FontWeight.Bold)
    Text(link.email?.let { "الحساب: $it" } ?: "لم يُختر حساب بعد")
    Text("التفويض: ${phaseArabic(link.phase)}")
    Text(link.message)
    if (link.diagnostic.isNotBlank()) Text("رمز التشخيص: ${link.diagnostic}")
    WideButton(if (link.phase == LinkPhase.NONE) "ربط Google" else "إعادة محاولة الربط") {
        (context.applicationContext as LorkApp).session.begin()
    }
    Text("اختر الدور ثم ملفاً ثم ورقة. يمكن لصق رابط أو معرّف.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("إدارة", "وكيل", "مستخدمون").forEach { name ->
            FilterChip(selected = model.role == name, onClick = { model.role = name }, label = { Text(name) })
        }
    }
    Text(sourceLine(model.sources))
    OutlinedTextField(
        value = model.pasted,
        onValueChange = { model.pasted = it },
        label = { Text("رابط أو معرّف ملف") },
        modifier = Modifier.fillMaxWidth(),
    )
    WideButton("استخدام المعرّف") { model.applyPastedId() }
    WideButton("عرض الجداول", !model.busy) { model.loadFiles(context) }
    if (model.fileMessage.isNotBlank()) Text(model.fileMessage)
    OutlinedTextField(
        value = model.fileQuery,
        onValueChange = { model.fileQuery = it },
        label = { Text("بحث في أسماء الملفات") },
        modifier = Modifier.fillMaxWidth(),
    )
    model.files.filter { it.name.contains(model.fileQuery, ignoreCase = true) }.take(30).forEach { file ->
        TextButton(onClick = { model.openFile(file.id, file.name) }, modifier = Modifier.fillMaxWidth()) {
            Text(file.name.ifBlank { file.id }, modifier = Modifier.fillMaxWidth())
        }
    }
    if (model.selectedFile.isNotBlank()) Text(model.selectedFile)
    model.sheetTitles.forEach { title ->
        TextButton(onClick = { model.chooseSheet(title) }) { Text(title) }
    }
    if (model.columnHint.isNotBlank()) Text(model.columnHint)
    Field("خصم النسبة", model.discount) { model.discount = it }
    Field("لون سحب الوكالة", model.agentColor) { model.agentColor = it }
    Field("لون سحب الإدارة", model.managementColor) { model.managementColor = it }
    Field("عمود رقم المستخدم", model.colUser) { model.colUser = it }
    Field("عمود العنوان", model.colTitle) { model.colTitle = it }
    Field("عمود الراتب", model.colSalary) { model.colSalary = it }
    Field("عمود الحالة، فارغ يعني التالي", model.colStatus) { model.colStatus = it }
    Field("عمود رقم الإدارة", model.colMgmt) { model.colMgmt = it }
    Field("عمود رقم الوكيل", model.colAgent) { model.colAgent = it }
    Field("عمود راتب الوكيل", model.colAgentSalary) { model.colAgentSalary = it }
    WideButton("حفظ الربط والتفضيلات") { model.savePreferences() }
    WideButton("تنزيل بيانات الدورة", !model.busy) { model.download(false, context) }
    WideButton("تصدير النسخة المحلية") { model.export(context) }
    Text(model.diagnostic(context))
    WideButton("نسخ التشخيص") { model.copyDiagnostic(context) }
    Text("تحديث التطبيق من بناء debug لا يستبدل تثبيتاً موقّعاً بمفتاح إصدار مختلف. صدّر النسخة المحلية قبل إزالة التطبيق.")
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun WideButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        contentPadding = ButtonDefaults.ContentPadding,
    ) { Text(label) }
}

private fun sourceLine(sources: Sources): String = listOf(
    "الإدارة: ${sources.mgmtSheet.ifBlank { "غير محددة" }}",
    "الوكيل: ${sources.agentSheet.ifBlank { "غير محدد" }}",
    "المستخدمون: ${sources.userSheet.ifBlank { "غير محددة" }}",
).joinToString(" · ")

private fun phaseArabic(phase: LinkPhase): String = when (phase) {
    LinkPhase.NONE -> "غير مرتبط"
    LinkPhase.CANCELED -> "أُلغي"
    LinkPhase.WAITING_CONSENT -> "بانتظار الموافقة"
    LinkPhase.AUTH_FAILED -> "فشل التفويض"
    LinkPhase.AUTHORIZED -> "مفوّض، والمصادر لم تكتمل"
    LinkPhase.SOURCES_PARTIAL -> "مصادر ناقصة"
    LinkPhase.SOURCES_READY -> "الأوراق محددة، بانتظار التنزيل"
    LinkPhase.DATA_READY -> "البيانات المحلية جاهزة"
}

private fun permissionLine(context: Context): String {
    val enabled = accessibilityEnabled(context)
    val bound = AuditAccessibilityService.instance != null
    val overlay = Settings.canDrawOverlays(context)
    return "إمكانية الوصول: ${if (enabled) "مفعّلة" else "غير مفعّلة"}، الاتصال بالخدمة: ${if (bound) "متصل" else "غير متصل"}، الظهور فوق التطبيقات: ${if (overlay) "مسموح" else "غير مسموح"}."
}

private fun statusArabic(status: String): String = when (status) {
    "READY" -> "جاهزة"
    "TEXT_PREPARED" -> "تم تجهيز النص"
    "EXECUTION_STARTED" -> "بدأ التنفيذ"
    "SEND_PRESSED" -> "تم الضغط على الإرسال"
    "UNCERTAIN" -> "غير مؤكدة"
    "FAILED" -> "فشل"
    "SKIPPED" -> "تم تجاوزها"
    else -> status
}
