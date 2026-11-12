package cloud.lork.auditsend.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.lork.auditengine.columnLetterToIndex
import cloud.lork.auditengine.indexToColumnLetter
import cloud.lork.auditengine.isHeaderRowUserInfo
import cloud.lork.auditsend.AuditApplication
import cloud.lork.auditsend.data.CycleEntity
import cloud.lork.auditsend.data.SheetKind
import cloud.lork.auditsend.send.BubbleService
import cloud.lork.auditsend.send.SendQueue
import cloud.lork.auditsend.send.WhatsAppAccessibilityService

private val kinds = listOf(SheetKind.USER_INFO, SheetKind.MANAGEMENT, SheetKind.AGENT)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycleScreen(cycleId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as AuditApplication
    val vm: CycleViewModel = viewModel(
        key = "cycle-$cycleId",
        factory = CycleViewModel.factory(app, cycleId),
    )
    val ready by vm.state.collectAsStateWithLifecycle()
    val cycleState = ready
    if (cycleState == null) {
        Scaffold(topBar = { TopAppBar(title = { Text("جاري التحميل") }) }) { padding ->
            Text("جاري فتح الدورة", modifier = Modifier.padding(padding).padding(24.dp))
        }
        return
    }

    var tab by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Triple<String, Int, Int>?>(null) }
    var draft by remember { mutableStateOf("") }
    var selectedRows by remember { mutableStateOf(setOf<Int>()) }
    var pendingSend by remember { mutableStateOf<List<SendQueue.Item>?>(null) }
    val titles = listOf("المستخدمون", "الإدارة", "الوكيل", "تدقيق", "إرسال")
    val kind = kinds.getOrNull(tab)

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || kind == null && tab > 2) return@rememberLauncherForActivityResult
        val preferred = kind ?: SheetKind.USER_INFO
        context.contentResolver.openInputStream(uri)?.use { input ->
            vm.import(input.readBytes(), preferred)
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = vm.exportBytes() ?: return@rememberLauncherForActivityResult
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
    }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val items = pendingSend
        pendingSend = null
        if (granted && !items.isNullOrEmpty()) {
            SendQueue.start(items)
            ContextCompat.startForegroundService(context, Intent(context, BubbleService::class.java))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(cycleState.cycle.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    TextButton(onClick = { settings = true }) { Text("الأعمدة") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                titles.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            if (cycleState.message.isNotBlank()) {
                Text(
                    cycleState.message,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            when {
                kind != null -> SheetPane(
                    kind = kind,
                    bundle = cycleState.sheets.getValue(kind),
                    onEdit = { row, col, value ->
                        editing = Triple(kind, row, col)
                        draft = value
                    },
                    onAdd = { vm.addRow(kind) },
                    onDelete = { vm.deleteRow(kind, it) },
                    onImport = { importLauncher.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream")) },
                    onExport = { exportLauncher.launch("${cycleState.cycle.name}.xlsx") },
                )
                tab == 3 -> AuditPane(
                    cycle = cycleState.cycle,
                    onRun = { vm.runAudit() },
                    onClose = { vm.setAuditOpen(false) },
                    onReopen = { vm.setAuditOpen(true) },
                )
                else -> SendPane(
                    cycle = cycleState.cycle,
                    rows = cycleState.sheets.getValue(SheetKind.USER_INFO).rows,
                    selected = selectedRows,
                    onSelected = { selectedRows = it },
                    onStart = { items ->
                        if (!Settings.canDrawOverlays(context)) {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                            return@SendPane
                        }
                        if (!accessibilityEnabled(context)) {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            return@SendPane
                        }
                        if (Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) {
                            pendingSend = items
                            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            return@SendPane
                        }
                        SendQueue.start(items)
                        ContextCompat.startForegroundService(context, Intent(context, BubbleService::class.java))
                    },
                )
            }
        }
    }

    val cell = editing
    if (cell != null) {
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("تعديل ${indexToColumnLetter(cell.third)}${cell.second + 1}") },
            text = {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.editCell(cell.first, cell.second, cell.third, draft)
                    editing = null
                }) { Text("حفظ") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("إلغاء") } },
        )
    }
    if (settings) {
        SettingsDialog(
            cycle = cycleState.cycle,
            onDismiss = { settings = false },
            onSave = {
                vm.saveSettings(it)
                settings = false
            },
        )
    }
}

@Composable
private fun SheetPane(
    kind: String,
    bundle: SheetBundle,
    onEdit: (Int, Int, String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Int) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    var window by remember(kind) { mutableIntStateOf(0) }
    val width = (bundle.rows.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
    val from = window.coerceIn(0, (width - 1).coerceAtLeast(0))
    val to = (from + 3).coerceAtMost(width - 1)
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport) { Text("استيراد") }
            OutlinedButton(onClick = onExport) { Text("تصدير") }
            OutlinedButton(onClick = onAdd) { Text("صف") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { window = (from - 4).coerceAtLeast(0) }, enabled = from > 0) { Text("الأعمدة السابقة") }
            Text("${indexToColumnLetter(from)}–${indexToColumnLetter(to)}")
            TextButton(onClick = { window = (from + 4).coerceAtMost((width - 1).coerceAtLeast(0)) }, enabled = to < width - 1) { Text("التالية") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(bundle.rows, key = { index, _ -> "$kind-$index-${bundle.rows.size}" }) { index, row ->
                val tint = bundle.colors[index]?.let { parseHex(it) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(tint ?: Color.White)
                        .border(1.dp, Color(0xFFE2E8F0))
                        .padding(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { onDelete(index) }) { Text("حذف") }
                    }
                    for (col in from..to) {
                        val value = row.getOrElse(col) { "" }
                        Text(
                            "${indexToColumnLetter(col)}: ${value.ifBlank { "—" }}",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onEdit(index, col, value) }
                                .padding(vertical = 4.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditPane(cycle: CycleEntity, onRun: () -> Unit, onClose: () -> Unit, onReopen: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (cycle.auditOpen) "جلسة التدقيق مفتوحة" else "جلسة التدقيق مغلقة", style = MaterialTheme.typography.titleMedium)
        Text("يكتب الراتب في العمود ${cycle.userInfoSalaryCol} والحالة في ${cycle.userInfoStatusCol}، ويلوّن صفوف الإدارة.")
        if (cycle.lastSummary.isNotBlank()) {
            Text(cycle.lastSummary)
        }
        if (cycle.auditOpen) {
            Button(onClick = onRun, modifier = Modifier.fillMaxWidth()) { Text("تدقيق") }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("إغلاق التدقيق") }
        } else {
            Button(onClick = onReopen, modifier = Modifier.fillMaxWidth()) { Text("إعادة فتح جلسة التدقيق") }
        }
    }
}

@Composable
private fun SendPane(
    cycle: CycleEntity,
    rows: List<List<String>>,
    selected: Set<Int>,
    onSelected: (Set<Int>) -> Unit,
    onStart: (List<SendQueue.Item>) -> Unit,
) {
    val idCol = columnLetterToIndex(cycle.userInfoUserIdCol) ?: 2
    val phoneCol = columnLetterToIndex(cycle.userInfoPhoneCol) ?: 4
    val messageCol = columnLetterToIndex(cycle.userInfoMessageCol) ?: 5
    val dataStart = if (rows.isNotEmpty() && isHeaderRowUserInfo(rows[0], idCol)) 1 else 0
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("الإرسال من هذا الهاتف عبر واتساب. اسحب الفقاعة لأسفل الشاشة لإيقافها.")
        Text("الهاتف ${cycle.userInfoPhoneCol} · الرسالة ${cycle.userInfoMessageCol}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                onSelected(rows.indices.filter { index ->
                    index >= dataStart && SendQueue.phoneDigits(rows[index].getOrElse(phoneCol) { "" }).isNotEmpty()
                }.toSet())
            }) { Text("تحديد من لديهم رقم") }
            TextButton(onClick = { onSelected(emptySet()) }) { Text("مسح") }
        }
        LazyColumn(Modifier.weight(1f)) {
            itemsIndexed(rows, key = { index, _ -> "send-$index-${rows.size}" }) { index, row ->
                if (index < dataStart) return@itemsIndexed
                val phone = SendQueue.phoneDigits(row.getOrElse(phoneCol) { "" })
                val name = row.getOrElse(columnLetterToIndex(cycle.userInfoTitleCol) ?: 3) { "" }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = selected.contains(index),
                        onCheckedChange = { checked ->
                            onSelected(if (checked) selected + index else selected - index)
                        },
                    )
                    Column {
                        Text(name.ifBlank { "صف ${index + 1}" })
                        Text(phone.ifBlank { "بدون رقم" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Button(
            onClick = {
                val items = selected.sorted().mapNotNull { index ->
                    val row = rows.getOrNull(index) ?: return@mapNotNull null
                    val phone = SendQueue.phoneDigits(row.getOrElse(phoneCol) { "" })
                    if (phone.isEmpty()) null else SendQueue.Item(phone, row.getOrElse(messageCol) { "" })
                }
                onStart(items)
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = selected.isNotEmpty(),
        ) { Text("بدء الإرسال") }
    }
}

@Composable
private fun SettingsDialog(cycle: CycleEntity, onDismiss: () -> Unit, onSave: (CycleEntity) -> Unit) {
    var userId by remember { mutableStateOf(cycle.userInfoUserIdCol) }
    var title by remember { mutableStateOf(cycle.userInfoTitleCol) }
    var salary by remember { mutableStateOf(cycle.userInfoSalaryCol) }
    var status by remember { mutableStateOf(cycle.userInfoStatusCol) }
    var phone by remember { mutableStateOf(cycle.userInfoPhoneCol) }
    var message by remember { mutableStateOf(cycle.userInfoMessageCol) }
    var mgmt by remember { mutableStateOf(cycle.mgmtUserIdCol) }
    var agentId by remember { mutableStateOf(cycle.agentUserIdCol) }
    var agentSalary by remember { mutableStateOf(cycle.agentSalaryCol) }
    var discount by remember { mutableStateOf(cycle.discountRatePct.toString()) }
    var agentColor by remember { mutableStateOf(cycle.agentColor) }
    var mgmtColor by remember { mutableStateOf(cycle.managementColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("أعمدة التدقيق والإرسال") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LetterField("معرف المستخدمين", userId) { userId = it }
                LetterField("الاسم", title) { title = it }
                LetterField("الراتب", salary) { salary = it }
                LetterField("الحالة", status) { status = it }
                LetterField("الهاتف", phone) { phone = it }
                LetterField("الرسالة", message) { message = it }
                LetterField("معرف الإدارة", mgmt) { mgmt = it }
                LetterField("معرف الوكيل", agentId) { agentId = it }
                LetterField("راتب الوكيل", agentSalary) { agentSalary = it }
                OutlinedTextField(discount, { discount = it }, label = { Text("نسبة الخصم") }, singleLine = true)
                OutlinedTextField(agentColor, { agentColor = it }, label = { Text("لون الوكيل") }, singleLine = true)
                OutlinedTextField(mgmtColor, { mgmtColor = it }, label = { Text("لون الإدارة") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    cycle.copy(
                        userInfoUserIdCol = userId.uppercase().ifBlank { "C" },
                        userInfoTitleCol = title.uppercase().ifBlank { "D" },
                        userInfoSalaryCol = salary.uppercase().ifBlank { "L" },
                        userInfoStatusCol = status.uppercase().ifBlank { "M" },
                        userInfoPhoneCol = phone.uppercase().ifBlank { "E" },
                        userInfoMessageCol = message.uppercase().ifBlank { "F" },
                        mgmtUserIdCol = mgmt.uppercase().ifBlank { "A" },
                        agentUserIdCol = agentId.uppercase().ifBlank { "A" },
                        agentSalaryCol = agentSalary.uppercase().ifBlank { "D" },
                        discountRatePct = discount.replace(',', '.').toDoubleOrNull() ?: 0.0,
                        agentColor = agentColor.ifBlank { "#8b5cf6" },
                        managementColor = mgmtColor.ifBlank { "#facc15" },
                    ),
                )
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

@Composable
private fun LetterField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

private fun parseHex(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(hex))
} catch (_: Exception) {
    Color.Transparent
}

private fun accessibilityEnabled(context: android.content.Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    val name = "${context.packageName}/${WhatsAppAccessibilityService::class.java.name}"
    return enabled.split(':').any { it.equals(name, ignoreCase = true) }
}
