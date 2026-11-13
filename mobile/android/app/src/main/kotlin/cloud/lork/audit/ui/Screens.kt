@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cloud.lork.audit

import android.app.Activity
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
import cloud.lork.audit.data.withSelection
import cloud.lork.audit.data.decodeColumns
import cloud.lork.audit.data.decodeSources
import cloud.lork.audit.db.CycleEntity
import cloud.lork.audit.db.SendItemEntity
import cloud.lork.audit.engine.AuditColumns
import cloud.lork.audit.engine.DriveFile
import cloud.lork.audit.engine.SourcePicker
import cloud.lork.audit.engine.SourcePickerContext
import cloud.lork.audit.engine.SourcePickerPage
import cloud.lork.audit.engine.SheetAccessException
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

enum class Screen { Audit, Search, Send, Settings }

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app as LorkApp
    val link = graph.session.state
    var screen by mutableStateOf(Screen.Audit)
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    var cycle by mutableStateOf<CycleEntity?>(null)
    var cycles by mutableStateOf<List<CycleEntity>>(emptyList())
    var newCycleName by mutableStateOf("")
    var cycleRename by mutableStateOf("")
    var cyclePickerOpen by mutableStateOf(false)
    var auditClosed by mutableStateOf(false)
    var auditApplied by mutableStateOf(false)
    var auditOfflinePreview by mutableStateOf(false)
    var searchAllCycles by mutableStateOf(false)
    var searchDiscount by mutableStateOf("0")
    var globalSearchResults by mutableStateOf<List<Pair<CycleEntity, String>>>(emptyList())
    var manualTarget by mutableStateOf("")
    var manualNewTarget by mutableStateOf("")
    var manualTargets by mutableStateOf<List<String>>(emptyList())
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
    private val sourcePicker = SourcePicker()
    private var sourceChoiceGeneration = 0L
    var sourcePickerState by mutableStateOf(sourcePicker.state)
        private set
    var role by mutableStateOf("إدارة")
    var pasted by mutableStateOf("")
    var columnHint by mutableStateOf("")
    var sendItems by mutableStateOf<List<SendItemEntity>>(emptyList())
    var sendState by mutableStateOf("IDLE")

    fun reload() {
        refreshSend()
        val email = link.value.email ?: run {
            resetCycleView()
            cycle = null
            cycles = emptyList()
            rows = emptyList()
            return
        }
        cycles = graph.store.cycles(email)
        val current = graph.db.dao().activeCycle(email) ?: cycles.firstOrNull() ?: run {
            resetCycleView()
            cycle = null
            rows = emptyList()
            cyclePickerOpen = true
            return
        }
        if (cycle?.id != current.id || cycle?.accountEmail != current.accountEmail) resetCycleView()
        cycle = current
        cycleRename = current.name
        auditClosed = graph.store.auditSessionClosed(current)
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

    fun createCycle() {
        if (busy) return
        val email = link.value.email ?: run {
            message = "اربط حساب Google أولاً لإنشاء دورة خاصة به."
            screen = Screen.Settings
            return
        }
        if (newCycleName.isBlank()) { message = "أدخل اسم الدورة المالية."; return }
        runCatching { graph.store.createCycle(email, newCycleName.trim()) }.onSuccess {
            newCycleName = ""
            resetCycleView()
            reload()
            screen = Screen.Settings
            message = "أُنشئت الدورة. اربط ورقتي الإدارة والوكيل، ثم جدول معلومات المستخدمين."
        }.onFailure { message = it.message ?: "تعذر إنشاء الدورة." }
    }

    fun renameCycle() {
        if (busy) return
        val current = cycle ?: return
        runCatching { graph.store.renameCycle(current.accountEmail, current.id, cycleRename) }
            .onSuccess { reload(); message = "حُفظ اسم الدورة." }
            .onFailure { message = it.message ?: "تعذر تعديل اسم الدورة." }
    }

    fun selectCycle(id: Long) {
        if (busy) return
        val email = link.value.email ?: return
        if (graph.store.selectCycle(email, id) == null) return
        resetCycleView()
        reload()
        cyclePickerOpen = false
        message = "حُمّلت إعدادات دورة «${cycle?.name}»."
    }

    private fun resetCycleView() {
        sourceChoiceGeneration++
        files = emptyList()
        sources = Sources()
        message = ""
        fileMessage = ""
        pasted = ""
        columnHint = ""
        planId = null
        showReview = false
        searchText = ""
        globalSearchResults = emptyList()
        manualTargets = emptyList()
        manualTarget = ""
        manualNewTarget = ""
        sourcePicker.close()
        sourcePickerState = sourcePicker.state
        auditApplied = false
        auditOfflinePreview = false
    }

    fun toggleAuditSession() {
        if (busy) return
        val current = cycle ?: return
        graph.store.setAuditSessionClosed(current, !auditClosed)
        auditClosed = graph.store.auditSessionClosed(current)
        message = if (auditClosed) "أُغلقت جلسة التدقيق لهذه الدورة وحُفظت إعداداتها." else "أُعيد فتح جلسة التدقيق."
    }

    fun googleAuthorizedNow(): Boolean = !graph.auth.accessToken.isNullOrBlank()

    fun hasCurrentSnapshot(): Boolean = cycle?.let { hasSnapshot(it) } == true

    fun snapshotLine(): String {
        val current = cycle ?: return "لا توجد دورة محفوظة."
        val snap = graph.db.dao().latestSnapshot(current.id, current.accountEmail)
            ?: return "لا توجد نسخة محلية لهذه الدورة."
        if (!hasSnapshot(current)) return "تغيّرت المصادر. نزّل بيانات الدورة مجدداً."
        val whenText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale("ar")).format(Date(snap.createdAt))
        return "النسخة المحلية: $whenText · ${snap.rowCount} صفاً في معلومات المستخدمين."
    }

    /** Same primary action as payrollRunAudit: synchronize, then apply. */
    fun runAudit(context: Context) {
        if (busy) return
        if (cycle == null) {
            cyclePickerOpen = true
            message = "اختر دورة مالية أو أنشئ دورة ثم اربط أوراقها."
            return
        }
        if (!context.isOnline()) { previewOffline(); return }
        if (!sources.complete) {
            screen = Screen.Settings
            message = "حدد ورقتي الإدارة والوكيل وجدول معلومات المستخدمين لهذه الدورة."
            return
        }
        if (!requireGoogleAuthorization()) return
        if (!savePreferences()) return
        val current = cycle ?: return
        val forced = force
        busy = true
        auditApplied = false
        auditOfflinePreview = false
        planId = null
        message = "1/2 مزامنة بيانات الدورة من Google…"
        viewModelScope.launch(Dispatchers.IO) {
            var pendingPlan: String? = null
            val text = runCatching {
                val sync = graph.store.download(current)
                if (!sync.startsWith("تم تنزيل")) return@runCatching sync
                withContext(Dispatchers.Main) { message = "2/2 تطبيق الرواتب والحالات والألوان…" }
                val (plan, summary) = graph.store.preview(current, true, forced)
                if (plan == null || plan.blocked) return@runCatching summary
                pendingPlan = plan.planId
                graph.store.approve(current, plan.planId)
            }.getOrElse { it.message ?: "تعذر إكمال التدقيق. راجع الاتصال وأعد المحاولة." }
            withContext(Dispatchers.Main) {
                busy = false
                message = text
                auditApplied = pendingPlan?.let { graph.db.dao().plan(it)?.status == "succeeded" } == true
                planId = if (auditApplied) null else pendingPlan
                cycle = graph.db.dao().cycle(current.id)
                cycles = graph.store.cycles(current.accountEmail)
                rows = cycle?.let { graph.store.rowsPreview(it) }.orEmpty()
                showReview = true
                if (hasCurrentSnapshot()) graph.session.note(LinkEvent.DataReady)
            }
        }
    }

    private fun requireGoogleAuthorization(): Boolean {
        if (link.value.phase == LinkPhase.WAITING_CONSENT) {
            screen = Screen.Settings
            message = "أكمل موافقة Google قبل متابعة العملية."
            return false
        }
        if (!graph.auth.accessToken.isNullOrBlank()) return true
        screen = Screen.Settings
        message = "أعد تفويض Google قبل قراءة الجداول أو الكتابة فيها."
        if (link.value.phase != LinkPhase.WAITING_CONSENT) graph.session.begin()
        return false
    }

    fun previewOffline() {
        if (busy) return
        val current = cycle ?: return
        if (!hasSnapshot(current)) {
            message = "لا توجد لقطة صالحة لهذه الدورة. اتصل بالإنترنت وشغّل التدقيق لتنزيلها."
            return
        }
        busy = true
        auditApplied = false
        auditOfflinePreview = true
        viewModelScope.launch(Dispatchers.IO) {
            val text = runCatching { graph.store.preview(current, false, force).second }
                .getOrElse { it.message ?: "تعذرت المعاينة." }
            withContext(Dispatchers.Main) {
                busy = false
                message = text
                planId = null
                rows = graph.store.rowsPreview(current)
                showReview = true
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
                auditApplied = graph.db.dao().plan(id)?.status == "succeeded"
                if (auditApplied) planId = null
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
        if (busy) return
        val rate = searchDiscount.replace(',', '.').toDoubleOrNull()
        if (rate == null || !rate.isFinite() || rate !in 0.0..100.0) {
            message = "خصم البحث يجب أن يكون بين 0 و100."; return
        }
        if (searchAllCycles) {
            globalSearchResults = cycles.mapNotNull { item ->
                val hit = graph.store.searchHit(item, query, rate)
                if (hit != null && (hit.inMgmt || hit.inAgent)) item to graph.store.search(item, query, rate) else null
            }
            searchText = if (globalSearchResults.isEmpty()) "لا توجد نتائج في الدورات التي نُزّلت بياناتها." else ""
        } else {
            val current = cycle ?: return
            globalSearchResults = emptyList()
            searchText = graph.store.search(current, query, rate)
        }
    }

    fun loadManualTargets(context: Context) {
        val current = cycle ?: return
        if (busy) return
        if (!context.isOnline()) { message = "عرض أوراق الهدف يحتاج اتصالاً."; return }
        if (!requireGoogleAuthorization()) return
        busy = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { graph.store.targetSheets(current) }
            withContext(Dispatchers.Main) {
                busy = false
                result.onSuccess { manualTargets = it }.onFailure { message = it.message ?: "تعذر تحميل أوراق الإدارة." }
            }
        }
    }

    fun manual(context: Context) {
        val current = cycle ?: return
        if (busy) return
        if (!context.isOnline()) { message = "التدقيق الانتقائي يكتب في Google ويحتاج اتصالاً."; return }
        if (!requireGoogleAuthorization()) return
        val target = manualNewTarget.trim().ifBlank { manualTarget }
        if (target.isBlank()) { message = "اختر ورقة هدف أو أدخل اسم ورقة جديدة."; return }
        busy = true
        val member = query
        val create = manualNewTarget.isNotBlank()
        viewModelScope.launch(Dispatchers.IO) {
            val text = runCatching { graph.store.manualAdvanced(current, member, target, create) }
                .getOrElse { it.message ?: "تعذر تنفيذ التدقيق الانتقائي." }
            withContext(Dispatchers.Main) {
                busy = false
                message = text
                query = member
                searchText = graph.store.search(current, member, searchDiscount.toDoubleOrNull() ?: 0.0)
            }
        }
    }

    fun savePreferences(): Boolean {
        val current = cycle ?: return false
        val rate = discount.replace(',', '.').toDoubleOrNull()
        if (rate == null || !rate.isFinite() || rate !in 0.0..100.0) {
            message = "نسبة الخصم يجب أن تكون بين 0 و100."; return false
        }
        val required = listOf(colUser, colTitle, colSalary, colMgmt, colAgent, colAgentSalary)
        if ((required + listOfNotNull(colStatus.takeIf { it.isNotBlank() })).any {
                cloud.lork.audit.engine.columnLetterToIndex(it) == null
            }) { message = "استخدم أحرف الأعمدة مثل A أو AA."; return false }
        if (listOf(agentColor, managementColor).any { !Regex("#[0-9a-fA-F]{6}").matches(it) }) {
            message = "استخدم لوناً بالصيغة #RRGGBB."; return false
        }
        val columns = AuditColumns(colUser, colTitle, colSalary, colStatus, colMgmt, colAgent, colAgentSalary)
        graph.store.saveSources(current, sources, columns, rate, agentColor, managementColor)
        cycle = graph.db.dao().cycle(current.id)
        planId = null
        showReview = false
        rows = cycle?.let { graph.store.rowsPreview(it) }.orEmpty()
        graph.session.note(if (sources.complete) LinkEvent.SourcesReady else LinkEvent.SourcesPartial)
        message = if (sources.complete) "حُفظ ربط الدورة. اضغط تدقيق لمزامنة البيانات وتطبيق النتائج." else "حُفظ ما اكتمل، وما زال أحد المصادر ناقصاً."
        return true
    }

    fun openSourcePicker(sourceRole: String, context: Context) {
        if (busy) return
        val current = cycle ?: run { message = "اختر دورة أولاً."; return }
        if (current.accountEmail != link.value.email) return
        role = sourceRole
        fileMessage = ""
        pasted = ""
        sourcePicker.open(SourcePickerContext(current.accountEmail, current.id, sourceRole), files)
        sourcePickerState = sourcePicker.state
        if (files.isEmpty()) refreshPickerFiles(context)
    }

    fun closeSourcePicker() {
        sourcePicker.close()
        sourcePickerState = sourcePicker.state
    }

    fun pickerQuery(value: String) {
        sourcePicker.setQuery(value)
        sourcePickerState = sourcePicker.state
    }

    fun pickerBack() {
        sourcePicker.showFiles()
        sourcePickerState = sourcePicker.state
    }

    fun refreshPickerFiles(context: Context) {
        if (!sourcePicker.state.isOpen) return
        val request = sourcePicker.requestFiles()
        sourcePickerState = sourcePicker.state
        if (!context.isOnline()) {
            sourcePicker.failed(request, "network", "عرض جداول Google يحتاج اتصالاً بالإنترنت.")
            sourcePickerState = sourcePicker.state
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { graph.sheets.listAllSpreadsheets() }
            withContext(Dispatchers.Main) {
                result.onSuccess { listing ->
                    if (sourcePicker.filesLoaded(request, listing) && listing.errorCode == null) files = listing.files
                }.onFailure {
                    sourcePicker.failed(request, (it as? SheetAccessException)?.code ?: "network",
                        it.message ?: "تعذر تحميل قائمة الملفات. أعد المحاولة.")
                }
                sourcePickerState = sourcePicker.state
            }
        }
    }

    fun openPickerFile(file: DriveFile, context: Context) {
        if (!sourcePicker.state.isOpen) return
        val request = sourcePicker.openFile(file)
        sourcePickerState = sourcePicker.state
        if (!context.isOnline()) {
            sourcePicker.failed(request, "network", "قراءة أوراق الملف تحتاج اتصالاً بالإنترنت.")
            sourcePickerState = sourcePicker.state
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { graph.sheets.listSheets(file.id) }
            withContext(Dispatchers.Main) {
                result.onSuccess { sourcePicker.sheetsLoaded(request, it) }.onFailure {
                    sourcePicker.failed(request, (it as? SheetAccessException)?.code ?: "network",
                        it.message ?: "تعذر قراءة أوراق هذا الملف. أعد المحاولة أو اختر ملفاً آخر.")
                }
                sourcePickerState = sourcePicker.state
            }
        }
    }

    fun retrySourcePicker(context: Context) {
        val file = sourcePicker.state.selectedFile
        if (sourcePicker.state.page == SourcePickerPage.SHEETS && file != null) openPickerFile(file, context)
        else refreshPickerFiles(context)
    }

    fun reauthorizeSourcePicker() {
        closeSourcePicker()
        if (link.value.phase != LinkPhase.WAITING_CONSENT) graph.session.begin()
    }

    fun choosePickerSheet(title: String) {
        val selection = sourcePicker.chooseSheet(title) ?: return
        sourcePickerState = sourcePicker.state
        val current = cycle ?: return
        if (selection.context.accountEmail != link.value.email || selection.context.cycleId != current.id ||
            selection.context.accountEmail != current.accountEmail) return
        // Save only the chosen source. Other edited settings still use the explicit Save button.
        val saved = graph.db.dao().cycle(current.id) ?: return
        val persisted = decodeSources(saved.sourcesJson).withSelection(selection)
        graph.store.saveSources(saved, persisted, decodeColumns(saved.columnsJson), saved.discountRate,
            saved.agentColor, saved.managementColor)
        cycle = graph.db.dao().cycle(saved.id)
        sources = sources.withSelection(selection)
        planId = null
        showReview = false
        rows = cycle?.let { graph.store.rowsPreview(it) }.orEmpty()
        columnHint = ""
        message = "تم ربط ${selection.context.role} بالملف «${selection.file.name.ifBlank { selection.file.id }}» / الورقة «$title»."
        val choiceGeneration = ++sourceChoiceGeneration
        if (selection.context.role == "مستخدمون") {
            val originalColumns = listOf(colUser, colTitle, colSalary, colStatus)
            viewModelScope.launch(Dispatchers.IO) {
                val header = runCatching { graph.sheets.read(selection.file.id, title).firstOrNull() }.getOrNull()
                withContext(Dispatchers.Main) {
                    val active = cycle
                    if (choiceGeneration != sourceChoiceGeneration || active?.id != selection.context.cycleId || active.accountEmail != selection.context.accountEmail ||
                        sources.userSs != selection.file.id || sources.userSheet != title) return@withContext
                    if (header != null && originalColumns == listOf(colUser, colTitle, colSalary, colStatus)) {
                        val guess = suggestColumns(header)
                        columnHint = if (guess.ambiguous) "راجع أحرف الأعمدة يدوياً؛ عناوين الورقة غير حاسمة." else
                            "اقتراح الأعمدة من الورقة المحددة؛ احفظ الإعدادات بعد مراجعتها."
                        if (!guess.ambiguous) {
                            guess.userId?.let { colUser = it }
                            guess.title?.let { colTitle = it }
                            guess.salary?.let { colSalary = it }
                            guess.status?.let { colStatus = it }
                        }
                    }
                }
            }
        }
    }

    fun applyPastedId(context: Context) {
        val id = parseSpreadsheetId(pasted)
        if (id == null) { fileMessage = "الرابط أو معرّف الملف غير مفهوم."; return }
        if (!sourcePicker.state.isOpen) openSourcePicker(role, context)
        if (sourcePicker.state.isOpen) openPickerFile(DriveFile(id, "ملف من الرابط"), context)
    }

    fun showSendBubble(context: Context) {
        if (!Settings.canDrawOverlays(context)) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            message = "اسمح بالظهور فوق التطبيقات ثم افتح فقاعة الإرسال."
            return
        }
        if (!accessibilityEnabled(context) || AuditAccessibilityService.instance == null) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            message = "فعّل خدمة تدقيق لورك ثم افتح الفقاعة."
            return
        }
        ContextCompat.startForegroundService(context,
            Intent(context, FloatingBubbleService::class.java).setAction(FloatingBubbleService.ACTION_SHOW_PANEL))
        (context as? Activity)?.moveTaskToBack(true)
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
