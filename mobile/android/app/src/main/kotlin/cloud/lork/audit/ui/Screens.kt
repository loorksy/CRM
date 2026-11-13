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
    var showReview by mutableStateOf(false)
    var resultFilter by mutableStateOf("الكل")
    var columnsOpen by mutableStateOf(false)
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
        refreshSend()
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
    }

    fun hasCurrentSnapshot(): Boolean = cycle?.let { hasSnapshot(it) } == true

    fun changeSendRate(value: Int) {
        mpm = value.coerceIn(1, 1000)
        val dao = graph.db.dao()
        dao.session()?.let { dao.saveSession(it.copy(mpm = mpm)) }
    }

    fun snapshotLine(): String {
        val current = cycle ?: return "لا توجد دورة محفوظة."
        val snap = graph.db.dao().latestSnapshot(current.id, current.accountEmail)
            ?: return "لا توجد نسخة محلية لهذه الدورة."
        if (!hasSnapshot(current)) return "تغيّرت المصادر. نزّل بيانات الدورة مجدداً."
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
                showReview = planId != null
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
        planId = null
        showReview = false
        rows = cycle?.let { graph.store.rowsPreview(it) }.orEmpty()
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
                state = "WAITING_CHAT",
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
        dao.saveSession(session.copy(state = "SENDING", mpm = mpm.coerceIn(1, 1000)))
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
        graph.db.dao().latestSnapshot(current.id, current.accountEmail)?.let {
            it.complete && it.fingerprint == cloud.lork.audit.data.fingerprint(decodeSources(current.sourcesJson))
        } == true

    private fun trimNumber(value: Double): String =
        if (value == kotlin.math.floor(value)) value.toLong().toString() else value.toString()
}

fun accessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(context, AuditAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
}

internal fun Context.isOnline(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return false
    return manager.activeNetwork != null
}
