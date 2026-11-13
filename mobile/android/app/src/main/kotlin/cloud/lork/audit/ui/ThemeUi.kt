package cloud.lork.audit

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cloud.lork.audit.engine.LinkPhase
import cloud.lork.audit.engine.SourcePickerPage
import cloud.lork.audit.engine.ParseMode
import cloud.lork.audit.engine.Readiness
import cloud.lork.audit.engine.auditReadiness
import cloud.lork.audit.engine.parseMessages
import cloud.lork.audit.send.AuditAccessibilityService

private val Ink = Color(0xFF1B2A4A)
private val Muted = Color(0xFF64748B)
private val Teal = Color(0xFF0F766E)
private val TealSoft = Color(0xFFE6F4F1)
private val Page = Color(0xFFF3F6FB)
private val Line = Color(0xFFE2E8F0)
private val AmberBg = Color(0xFFFFF6E8)
private val Amber = Color(0xFFB45309)
private val GreenBg = Color(0xFFE8F8F1)
private val Danger = Color(0xFFDC2626)
private val CardShape = RoundedCornerShape(22.dp)

@Composable
fun AppRoot(model: HomeViewModel = viewModel()) {
    val link by model.link.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(link.email) { model.reload() }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme {
            Scaffold(
                containerColor = Page,
                bottomBar = {
                    if (model.screen != Screen.Settings && !model.showReview) {
                        BottomNav(model.screen) { model.screen = it }
                    }
                },
            ) { padding ->
                Column(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when {
                        model.showReview -> ReviewPage(model)
                        model.screen == Screen.Audit -> AuditHome(model, link.email, link.phase, context)
                        model.screen == Screen.Search -> SearchHome(model, context)
                        model.screen == Screen.Send -> SendHome(model, context)
                        model.screen == Screen.Settings -> LinkHome(model, context)
                    }
                }
            }
            if (model.sourcePickerState.isOpen) SourcePickerDialog(model, context)
        }
    }
}

@Composable
private fun BottomNav(current: Screen, onSelect: (Screen) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(8.dp)
            .background(Color.White)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NavItem("التدقيق", current == Screen.Audit) { onSelect(Screen.Audit) }
        NavItem("البحث", current == Screen.Search) { onSelect(Screen.Search) }
        NavItem("الإرسال", current == Screen.Send) { onSelect(Screen.Send) }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            when (label) { "الإرسال" -> Icons.AutoMirrored.Filled.Send; "البحث" -> Icons.Filled.Search; else -> Icons.Filled.Check },
            contentDescription = null,
            tint = if (selected) Teal else Muted,
        )
        Text(label, color = if (selected) Teal else Muted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
private fun CyclePanel(model: HomeViewModel) {
    SoftCard {
        Text("الدورة المالية", color = Muted, fontSize = 12.sp)
        SelectLine(model.cycle?.name ?: "اختر دورة أو أنشئ دورة مالية") {
            model.cyclePickerOpen = !model.cyclePickerOpen
        }
        if (model.cyclePickerOpen || model.cycle == null) {
            Text("الدورات المحفوظة لهذا الحساب على الهاتف", color = Muted, fontSize = 12.sp)
            model.cycles.forEach { item ->
                OutlineButton("تحميل ${item.name}", Modifier.fillMaxWidth(), !model.busy) { model.selectCycle(item.id) }
            }
            if (model.cycle != null) {
                MiniField("تعديل اسم الدورة الحالية", model.cycleRename) { model.cycleRename = it }
                OutlineButton("حفظ اسم الدورة", Modifier.fillMaxWidth(), !model.busy) { model.renameCycle() }
            }
            MiniField("اسم دورة جديدة", model.newCycleName) { model.newCycleName = it }
            OutlineButton("إنشاء دورة", Modifier.fillMaxWidth(), !model.busy) { model.createCycle() }
        }
        if (model.cycle != null) {
            Text(if (model.auditClosed) "تم إغلاق جلسة التدقيق" else "جلسة التدقيق نشطة", color = Teal, fontSize = 12.sp)
            OutlineButton(if (model.auditClosed) "إعادة فتح جلسة التدقيق" else "إغلاق التدقيق", Modifier.fillMaxWidth(), !model.busy) {
                model.toggleAuditSession()
            }
        }
    }
}

@Composable
private fun AuditHome(model: HomeViewModel, email: String?, phase: LinkPhase, context: Context) {
    val ready = model.hasCurrentSnapshot()
    val online = context.isOnline()
    Header("تدقيق الرواتب", "اختر الدورة ثم زامن وطبّق التدقيق", onSettings = { model.screen = Screen.Settings })
    StatusPill(model.googleAuthorizedNow(), email ?: "اربط حساب Google")
    CyclePanel(model)
    if (model.cycle != null) {
        SoftCard {
            Text("أوراق الدورة", color = Ink, fontWeight = FontWeight.Bold)
            Text("الإدارة: ${model.sources.mgmtSheet.ifBlank { "غير محددة" }} · الوكيل: ${model.sources.agentSheet.ifBlank { "غير محددة" }}", color = Muted, fontSize = 13.sp)
            Text("معلومات المستخدمين: ${model.sources.userSheet.ifBlank { "غير محددة" }}", color = Muted, fontSize = 13.sp)
            Text("الأعمدة: رقم ${model.colUser}، ورقة ${model.colTitle}، راتب ${model.colSalary} · خصم ${model.discount}%", color = Muted, fontSize = 12.sp)
            OutlineButton("اختيار الجداول والأوراق والأعمدة", Modifier.fillMaxWidth(), !model.busy) { model.screen = Screen.Settings }
            Text(syncCaption(model, ready), color = Muted, fontSize = 12.sp)
        }
        val total = model.rows.size
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("المستخدمون", total.toString(), Modifier.weight(1f))
            Stat("سحب وكالة", model.rows.count { it.contains("وكيل") }.toString(), Modifier.weight(1f))
            Stat("سحب إدارة", model.rows.count { it.contains("ادارة") || it.contains("إدارة") }.toString(), Modifier.weight(1f))
        }
        SoftCard {
            Text("تدقيق الدورة", color = Ink, fontWeight = FontWeight.Bold)
            Text(if (online) "يزامن أوراق الدورة ثم يكتب الرواتب والحالات، وينسخ صفوف الإدارة إلى أوراق العناوين ويطبّق الألوان." else "دون اتصال: المعاينة من آخر لقطة محفوظة، وتطبيق النتائج عند الاتصال.", color = Muted, fontSize = 13.sp)
            PrimaryButton(if (model.busy) "جارٍ تنفيذ العملية…" else if (online) "تدقيق — مزامنة وتطبيق" else "معاينة دون إنترنت", !model.busy) { model.runAudit(context) }
            if (ready && online) OutlineButton("معاينة محلية فقط", Modifier.fillMaxWidth(), !model.busy) { model.previewOffline() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = model.force, enabled = !model.busy, onCheckedChange = { model.force = it })
                Text("فرض الإعادة إذا لم يتغيّر جدول المستخدمين", color = Muted, fontSize = 12.sp)
            }
        }
    }
    if (model.message.isNotBlank()) Banner(TealSoft, Teal, model.message)
    if (model.rows.isNotEmpty() || model.planId != null) OutlineButton("عرض نتائج آخر تدقيق", Modifier.fillMaxWidth()) { model.showReview = true }
}

@Composable
private fun SearchHome(model: HomeViewModel, context: Context) {
    Header("البحث والتدقيق", "استعلام عن مستخدم ثم تدقيقه في الورقة المختارة")
    CyclePanel(model)
    SearchField(model.query, "رقم المستخدم") { if (!model.busy) model.query = it }
    MiniField("خصم البحث (%)", model.searchDiscount) { if (!model.busy) model.searchDiscount = it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = model.searchAllCycles, onCheckedChange = { model.searchAllCycles = it })
        Text("البحث الشامل في كل الدورات المحفوظة", color = Ink, fontSize = 13.sp)
    }
    PrimaryButton("بحث", !model.busy) { model.search() }
    model.globalSearchResults.forEach { (item, result) ->
        SoftCard {
            Text(item.name, color = Ink, fontWeight = FontWeight.Bold)
            Text(result, color = Muted, fontSize = 13.sp)
            OutlineButton("اختيار هذه الدورة للتدقيق", Modifier.fillMaxWidth(), !model.busy) {
                model.selectCycle(item.id)
                model.searchAllCycles = false
                model.search()
            }
        }
    }
    if (model.searchText.isNotBlank()) {
        SoftCard { Text(model.searchText, color = Ink) }
        if (model.hasCurrentSnapshot()) SoftCard {
            Text("التدقيق الانتقائي · ${model.cycle?.name.orEmpty()}", color = Ink, fontWeight = FontWeight.Bold)
            Text("نسخ صف الإدارة إلى ورقة الهدف وتلوين الصفوف وتحديث حالة المستخدم.", color = Muted, fontSize = 13.sp)
            OutlineButton("تحميل أوراق جدول الإدارة", Modifier.fillMaxWidth(), !model.busy) { model.loadManualTargets(context) }
            model.manualTargets.forEach { title ->
                SelectLine((if (model.manualTarget == title) "✓ " else "") + title) {
                    model.manualTarget = title
                    model.manualNewTarget = ""
                }
            }
            Text("الورقة المختارة: ${model.manualTarget.ifBlank { "لم تُختر" }}", color = Muted, fontSize = 12.sp)
            MiniField("أو اسم ورقة جديدة", model.manualNewTarget) { model.manualNewTarget = it }
            PrimaryButton("تنفيذ التدقيق الانتقائي في Google", !model.busy) { model.manual(context) }
        }
    }
    if (model.message.isNotBlank()) Text(model.message, color = Ink)
}

@Composable
private fun ReviewPage(model: HomeViewModel) {
    val context = LocalContext.current
    Header("نتيجة التدقيق", model.cycle?.name ?: "الدورة", onBack = { model.showReview = false })
    Banner(if (model.auditApplied) GreenBg else AmberBg, if (model.auditApplied) Teal else Amber,
        if (model.auditApplied) "تم تطبيق النتائج في Google والتحقق منها" else if (model.auditOfflinePreview) "معاينة محلية فقط — لم تُكتب نتائج هذه المعاينة في Google" else "راجع تقرير العملية أدناه؛ لم يُؤكّد اكتمال التدقيق")
    SoftCard {
        Text(model.rows.size.toString(), color = Ink, fontWeight = FontWeight.Bold, fontSize = 36.sp)
        Text("صف نتيجة", color = Muted)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("الكل", "الإدارة", "الوكيل").forEach { name ->
            FilterPill(name, model.resultFilter == name) { model.resultFilter = name }
        }
    }
    val visible = model.rows.filter { matchesFilter(it, model.resultFilter) }
    if (visible.isEmpty()) {
        Text("لا توجد صفوف في هذا التصنيف.", color = Muted)
    }
    if (visible.size > 40) Text("عرض أول 40 نتيجة من ${visible.size}؛ الاعتماد يشمل جميع النتائج.", color = Muted)
    visible.take(40).forEach { line ->
        val parts = line.split(" · ")
        SoftCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(parts.getOrElse(0) { "—" }, color = Ink, fontWeight = FontWeight.Bold)
                Text(parts.getOrElse(2) { "" }, color = Ink)
            }
            Text(parts.getOrElse(1) { "" }, color = Muted, fontSize = 13.sp)
        }
    }
    if (model.message.isNotBlank()) Text(model.message, color = Ink, fontSize = 13.sp)
    if (model.planId != null) {
        PrimaryButton("استكمال الكتابة المتوقفة", !model.busy) { model.approve() }
        Text("تستكمل الخطوات الناقصة من العملية المحفوظة بعد التحقق مما كُتب.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    if (model.auditOfflinePreview) PrimaryButton("الاتصال والمزامنة وتطبيق التدقيق", !model.busy) { model.runAudit(context) }
    OutlineButton("العودة للتدقيق", Modifier.fillMaxWidth()) { model.showReview = false }
}

@Composable
private fun SendHome(model: HomeViewModel, context: Context) {
    val notices = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.showSendBubble(context) }
    var accessOn by remember { mutableStateOf(false) }
    var overlayOn by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        accessOn = accessibilityEnabled(context) && AuditAccessibilityService.instance != null
        overlayOn = Settings.canDrawOverlays(context)
        model.refreshSend()
        onPauseOrDispose { }
    }
    Header("الإرسال", "افتح الفقاعة ثم جهّز الرسائل داخل واتساب")
    SoftCard {
        Text("الإرسال من الفقاعة العائمة", color = Ink, fontWeight = FontWeight.Bold)
        Text("افتح محادثة واتساب المطلوبة، والصق الرسائل في الفقاعة. تفصل الفقاعة الفقرات، وتعرض العدد، وتتيح سرعة من 1 إلى 1000 رسالة في الدقيقة.", color = Muted, fontSize = 13.sp)
        Text("يمكنك تعديل النص والسرعة والبدء والإيقاف من الفقاعة دون الرجوع لهذه الشاشة.", color = Muted, fontSize = 13.sp)
    }
    PermitRow("إمكانية الوصول", accessOn) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    PermitRow("الظهور فوق التطبيقات", overlayOn) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
    }
    PrimaryButton("فتح فقاعة الإرسال") {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notices.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else model.showSendBubble(context)
    }
    Text("الوجهة هي محادثة واتساب المفتوحة. الضغط على الإرسال لا يؤكد التسليم.", color = Muted, fontSize = 12.sp)
    if (model.message.isNotBlank()) Text(model.message, color = Ink)
}

@Composable
private fun LinkHome(model: HomeViewModel, context: Context) {
    val link by model.link.collectAsState()
    Header("ربط أوراق الدورة", model.cycle?.name ?: "أنشئ دورة أولاً", onBack = { model.screen = Screen.Audit })
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusPill(link.email != null && link.phase != LinkPhase.AUTH_FAILED && link.phase != LinkPhase.CANCELED && link.phase != LinkPhase.NONE, "")
            Column(Modifier.weight(1f)) {
                Text(if (model.googleAuthorizedNow()) "حساب Google مرتبط" else "تفويض Google يحتاج متابعة", color = Ink, fontWeight = FontWeight.Bold)
                link.email?.let { Text(it, color = Muted, fontSize = 13.sp) }
                Text(link.message, color = Muted, fontSize = 13.sp)
            }
        }
        if (link.diagnostic.isNotBlank()) Text("رمز التشخيص: ${link.diagnostic}", color = Amber, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        PrimaryButton(if (link.phase == LinkPhase.NONE || link.phase == LinkPhase.CANCELED) "ربط Google" else "إعادة محاولة الربط", !model.busy && link.phase != LinkPhase.WAITING_CONSENT) {
            (context.applicationContext as LorkApp).session.begin()
        }
    }
    CyclePanel(model)
    if (model.cycle == null) {
        if (model.message.isNotBlank()) Text(model.message, color = Ink)
        return
    }
    Text("اضغط على الدور، ثم اختر الملف والورقة من نافذة الاختيار.", color = Muted, fontSize = 13.sp)
    SourceCard("الإدارة", "اختيار جدول الإدارة", model.sources.mgmtSheet, model.sources.mgmtFileName,
        model.role == "إدارة") { model.openSourcePicker("إدارة", context) }
    SourceCard("الوكيل", "اختيار جدول الوكيل", model.sources.agentSheet, model.sources.agentFileName,
        model.role == "وكيل") { model.openSourcePicker("وكيل", context) }
    SourceCard("معلومات المستخدمين", "اختيار جدول معلومات المستخدمين", model.sources.userSheet, model.sources.userFileName,
        model.role == "مستخدمون") { model.openSourcePicker("مستخدمون", context) }
    OutlineButton("اختيار ملف لدور ${model.role}", Modifier.fillMaxWidth(), !model.busy) { model.openSourcePicker(model.role, context) }
    if (model.columnHint.isNotBlank()) Text(model.columnHint, color = Amber, fontSize = 13.sp)
    SelectLine(if (model.columnsOpen) "إخفاء إعدادات الأعمدة والخصم" else "إعدادات الأعمدة والخصم") {
        model.columnsOpen = !model.columnsOpen
    }
    if (model.columnsOpen) {
        MiniField("خصم النسبة", model.discount) { model.discount = it }
        MiniField("لون سحب الوكالة", model.agentColor) { model.agentColor = it }
        MiniField("لون سحب الإدارة", model.managementColor) { model.managementColor = it }
        MiniField("عمود رقم المستخدم", model.colUser) { model.colUser = it }
        MiniField("عمود العنوان", model.colTitle) { model.colTitle = it }
        MiniField("عمود الراتب", model.colSalary) { model.colSalary = it }
        MiniField("عمود الحالة", model.colStatus) { model.colStatus = it }
        MiniField("عمود رقم الإدارة", model.colMgmt) { model.colMgmt = it }
        MiniField("عمود رقم الوكيل", model.colAgent) { model.colAgent = it }
        MiniField("عمود راتب الوكيل", model.colAgentSalary) { model.colAgentSalary = it }
    }
    PrimaryButton("حفظ إعدادات الدورة", !model.busy) {
        if (model.savePreferences()) model.screen = Screen.Audit
    }
    OutlineButton("مزامنة لقطة للعمل دون إنترنت", Modifier.fillMaxWidth(), !model.busy) {
        if (model.savePreferences()) model.download(false, context)
    }
    if (model.message.isNotBlank()) Text(model.message, color = Ink)
    OutlineButton("تصدير النسخة المحلية", Modifier.fillMaxWidth()) { model.export(context) }
    Text(model.diagnostic(context), color = Muted, fontSize = 12.sp)
    OutlineButton("نسخ التشخيص", Modifier.fillMaxWidth()) { model.copyDiagnostic(context) }
}

@Composable
private fun SourcePickerDialog(model: HomeViewModel, context: Context) {
    val state = model.sourcePickerState
    var showLinkInput by remember(state.context) { mutableStateOf(false) }
    val sourceRole = when (state.context?.role) { "إدارة" -> "الإدارة"; "وكيل" -> "الوكيل"; else -> "معلومات المستخدمين" }
    Dialog(
        onDismissRequest = { if (state.page == SourcePickerPage.SHEETS) model.pickerBack() else model.closeSourcePicker() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f),
            shape = RoundedCornerShape(22.dp), color = Color.White,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("اختيار ملف $sourceRole", color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(if (state.page == SourcePickerPage.FILES) "1 / 2 — اختر ملف Google" else "2 / 2 — اختر الورقة داخل الملف", color = Muted, fontSize = 12.sp)
                    }
                    TextButton(onClick = { model.closeSourcePicker() }) { Text("إغلاق") }
                }
                if (state.page == SourcePickerPage.SHEETS) {
                    TextButton(onClick = { model.pickerBack() }) { Text("العودة إلى الملفات") }
                    Text(state.selectedFile?.name.orEmpty(), color = Ink, fontWeight = FontWeight.SemiBold)
                    Text("تُربط الورقة المختارة بدور $sourceRole فقط.", color = Muted, fontSize = 12.sp)
                } else {
                    SearchField(state.query, "بحث في جميع الملفات") { model.pickerQuery(it) }
                    if (state.files.isNotEmpty()) Text("${state.filteredFiles.size} من ${state.files.size} جدولاً", color = Muted, fontSize = 12.sp)
                    TextButton(onClick = { showLinkInput = !showLinkInput }) { Text(if (showLinkInput) "إخفاء الرابط" else "استخدام رابط ملف") }
                    if (showLinkInput) {
                        SearchField(model.pasted, "الصق رابط ملف أو معرّفه") { model.pasted = it }
                        TextButton(onClick = { model.applyPastedId(context) }, enabled = !state.loading) { Text("عرض أوراق الرابط") }
                        if (model.fileMessage.isNotBlank()) Text(model.fileMessage, color = Amber, fontSize = 12.sp)
                    }
                }
                if (state.loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = Teal, strokeWidth = 2.dp)
                    Text(state.message, color = Muted, modifier = Modifier.weight(1f), fontSize = 13.sp)
                }
                if (state.errorMessage.isNotBlank()) {
                    Banner(AmberBg, Amber, state.errorMessage)
                    Text("رمز الخطأ: ${state.errorCode}", color = Muted, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { model.retrySourcePicker(context) }) { Text("إعادة المحاولة") }
                        if (state.errorCode.startsWith("401") || state.errorCode.contains("INSUFFICIENT_SCOPE")) {
                            TextButton(onClick = { model.reauthorizeSourcePicker() }) { Text("إعادة تفويض Google") }
                        }
                    }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.page == SourcePickerPage.FILES) {
                        items(state.filteredFiles, key = { it.id }) { file ->
                            PickerChoice(file.name.ifBlank { file.id }, "اختيار الملف وعرض أوراقه", !state.loading) {
                                model.openPickerFile(file, context)
                            }
                        }
                        if (!state.loading && state.filteredFiles.isEmpty() && state.errorMessage.isBlank()) item {
                            Text(if (state.query.isBlank()) "لا توجد ملفات متاحة. يمكنك استخدام رابط ملف تملك صلاحية قراءته." else "لا توجد ملفات تطابق البحث.", color = Muted)
                        }
                    } else {
                        items(state.sheetTitles, key = { it }) { title ->
                            PickerChoice(title, "ربط هذه الورقة بدور $sourceRole", !state.loading) { model.choosePickerSheet(title) }
                        }
                    }
                }
                if (state.page == SourcePickerPage.FILES) {
                    TextButton(onClick = { model.refreshPickerFiles(context) }, enabled = !state.loading) { Text("تحديث قائمة الملفات") }
                }
            }
        }
    }
}

@Composable
private fun PickerChoice(title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TealSoft)
        .clickable(enabled = enabled, onClick = onClick).padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Ink, fontWeight = FontWeight.SemiBold)
        Text(subtitle, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun Header(title: String, subtitle: String?, onSettings: (() -> Unit)? = null, onBack: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (onBack != null) {
            Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = Ink)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 26.sp)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 13.sp)
        }
        if (onSettings != null) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(Color.White).clickable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Settings, "الإعدادات", tint = Ink) }
        }
    }
}

@Composable
private fun SoftCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(2.dp, CardShape)
            .clip(CardShape)
            .background(Color.White)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = { content() },
    )
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(
        modifier
            .shadow(1.dp, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) Teal else Teal.copy(alpha = 0.4f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold) }
}

@Composable
private fun OutlineButton(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Teal, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Teal, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun DangerButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Danger, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Danger, fontWeight = FontWeight.Bold) }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Teal else Color.White)
            .border(1.dp, if (selected) Teal else Line, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) { Text(label, color = if (selected) Color.White else Ink, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SearchField(value: String, hint: String, singleLine: Boolean = true, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(hint, color = Muted) },
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = Muted) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Teal,
        ),
    )
}

@Composable
private fun Banner(background: Color, accent: Color, text: String) {
    Text(
        text,
        color = accent,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .padding(14.dp),
        fontSize = 13.sp,
    )
}

@Composable
private fun StatusPill(ok: Boolean, label: String) {
    if (label.isBlank() && ok) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF16A34A)))
        return
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (ok) GreenBg else AmberBg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) Color(0xFF16A34A) else Amber))
        if (label.isNotBlank()) Text(label, color = if (ok) Teal else Amber, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PermitRow(label: String, ok: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Ink)
        Text(if (ok) "مفعّل" else "غير مفعّل", color = if (ok) Teal else Amber, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SourceCard(title: String, hint: String, value: String, fileName: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Color.White)
            .border(if (selected) 1.5.dp else 0.dp, if (selected) Teal else Color.Transparent, CardShape)
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Text(title, color = Ink, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (fileName.isNotBlank()) Text(fileName, color = Ink, fontSize = 13.sp)
        SelectLine(if (value.isBlank()) hint else "الورقة: $value", onClick)
    }
}

@Composable
private fun SelectLine(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Ink, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.KeyboardArrowDown, null, tint = Muted)
    }
}

@Composable
private fun MiniField(label: String, value: String, onChange: (String) -> Unit) {
    Text(label, color = Muted, fontSize = 12.sp)
    SearchField(value, label) { onChange(it) }
}

private fun syncCaption(model: HomeViewModel, ready: Boolean): String {
    if (!ready) return "لا توجد نسخة محلية بعد. التنزيل مرة واحدة يكفي للتدقيق دون إنترنت."
    return model.snapshotLine().removePrefix("النسخة المحلية: ").let { "آخر مزامنة $it" }
}

private fun matchesFilter(line: String, filter: String): Boolean = when (filter) {
    "الإدارة" -> line.contains("ادارة") || line.contains("إدارة")
    "الوكيل" -> line.contains("وكيل") || line.contains("وكالة")
    else -> true
}

private fun sendStateArabic(state: String): String = when (state) {
    "SENDING" -> "جارٍ الإرسال"
    "PAUSED" -> "الإرسال متوقف"
    "WAITING_CHAT" -> "بانتظار المحادثة"
    "DONE" -> "اكتمل"
    "STOPPED" -> "تم الإنهاء"
    else -> "جاهز"
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
