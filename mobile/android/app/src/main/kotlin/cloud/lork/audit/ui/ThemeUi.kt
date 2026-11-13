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
import cloud.lork.audit.engine.LinkPhase
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
                        model.screen == Screen.Send -> SendHome(model, context)
                        model.screen == Screen.Settings -> LinkHome(model, context)
                    }
                }
            }
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
            .padding(horizontal = 28.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NavItem("التدقيق", current == Screen.Audit) { onSelect(Screen.Audit) }
        NavItem("الإرسال", current == Screen.Send) { onSelect(Screen.Send) }
    }
}

@Composable
private fun NavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (label == "الإرسال") Icons.AutoMirrored.Filled.Send else Icons.Filled.Check,
            contentDescription = null,
            tint = if (selected) Teal else Muted,
        )
        Text(label, color = if (selected) Teal else Muted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

@Composable
private fun AuditHome(model: HomeViewModel, email: String?, phase: LinkPhase, context: Context) {
    val ready = model.snapshotLine().startsWith("النسخة")
    val advice = auditReadiness(model.link.value, ready, context.isOnline())
    Header("تدقيق لورك", "التدقيق", onSettings = { model.screen = Screen.Settings })
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("بياناتك جاهزة على الهاتف", color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        StatusPill(phase == LinkPhase.DATA_READY || phase == LinkPhase.SOURCES_READY || email != null, if (email != null) "متصل" else "غير مرتبط")
    }
    SoftCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("الدورة الحالية", color = Muted, fontSize = 12.sp)
                Text(model.cycle?.name ?: "لم تُختر دورة", color = Ink, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(TealSoft)
                    .clickable(enabled = !model.busy) { model.download(false, context) },
                contentAlignment = Alignment.Center,
            ) { Text("↻", color = Teal, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(6.dp))
        Text(syncCaption(model, ready), color = Muted, fontSize = 13.sp)
    }
    val total = model.rows.size
    val agent = model.rows.count { it.contains("وكيل") }
    val management = model.rows.count { it.contains("ادارة") || it.contains("إدارة") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Stat("المستخدمون", if (total == 0) "0" else total.toString(), Modifier.weight(1f))
        Stat("تمت المراجعة", (total - agent - management).coerceAtLeast(0).toString(), Modifier.weight(1f))
        Stat("للمراجعة", (agent + management).toString(), Modifier.weight(1f))
    }
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(TealSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Description, null, tint = Teal)
            }
            Column(Modifier.weight(1f)) {
                Text("ابدأ تدقيق الدورة", color = Ink, fontWeight = FontWeight.Bold)
                Text("راجع النتائج قبل اعتمادها في الجداول", color = Muted, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(if (advice.readiness == Readiness.READY) "تنفيذ التدقيق" else advice.action, !model.busy) {
            model.runAudit(context)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = model.force, onCheckedChange = { model.force = it })
            Text("فرض الإعادة إذا لم يتغيّر جدول المستخدمين", color = Muted, fontSize = 12.sp)
        }
        if (model.message.isNotBlank()) Text(model.message, color = Ink, fontSize = 13.sp)
    }
    SearchField(model.query, "ابحث برقم المستخدم") { model.query = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlineButton("بحث", Modifier.weight(1f), !model.busy) { model.search() }
        OutlineButton("تدقيق يدوي", Modifier.weight(1f), !model.busy) { model.manual() }
    }
    if (model.searchText.isNotBlank()) SoftCard { Text(model.searchText, color = Ink) }
    if (model.planId != null || model.rows.isNotEmpty()) {
        SoftCard {
            Row(
                Modifier.fillMaxWidth().clickable { model.showReview = model.rows.isNotEmpty() || model.planId != null },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("سجل المزامنة والنتائج", color = Ink, fontWeight = FontWeight.SemiBold)
                Text("عرض", color = Teal, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ReviewPage(model: HomeViewModel) {
    Header("مراجعة النتائج", model.cycle?.name ?: "الدورة", onBack = { model.showReview = false })
    Banner(AmberBg, Amber, "لم تُكتب التغييرات في Google بعد")
    SoftCard {
        Text(model.rows.size.toString(), color = Ink, fontWeight = FontWeight.Bold, fontSize = 36.sp)
        Text("تغييراً للمراجعة", color = Muted)
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
        PrimaryButton("اعتماد الكتابة في Google", !model.busy) { model.approve() }
        Text("راجع التغييرات ثم اعتمد الكتابة. تنفيذ التدقيق لا يكتب وحده.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
    OutlineButton("العودة للتدقيق", Modifier.fillMaxWidth()) { model.showReview = false }
}

@Composable
private fun SendHome(model: HomeViewModel, context: Context) {
    val notices = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val preview = parseMessages(model.rawMessages, ParseMode.PARAGRAPH_THEN_LINES)
    val accessOn = accessibilityEnabled(context) && AuditAccessibilityService.instance != null
    val overlayOn = Settings.canDrawOverlays(context)
    Header("الإرسال", null, onSettings = { model.screen = Screen.Settings })
    Banner(GreenBg, Teal, "الإرسال إلى المحادثة المفتوحة. افتح محادثة واتساب المطلوبة أولاً.")
    Text("الرسائل", color = Ink, fontWeight = FontWeight.Bold)
    Text("افصل بين الرسائل بسطر فارغ.", color = Muted, fontSize = 12.sp)
    SearchField(model.rawMessages, "نص الرسائل", singleLine = false) { model.rawMessages = it }
    SoftCard {
        Text("المعاينة · ${preview.size} ${if (preview.size == 2) "رسالتان" else "رسائل"}", color = Teal, fontWeight = FontWeight.SemiBold)
        preview.take(4).forEach { body ->
            Text(body.take(160), color = Ink, modifier = Modifier.padding(top = 6.dp))
        }
    }
    Text("رسالة في الدقيقة", color = Muted, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(10, 60, 300, 1000).forEach { speed ->
            FilterPill(speed.toString(), model.mpm == speed) { model.mpm = speed }
        }
    }
    PermitRow("إمكانية الوصول", accessOn) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
    PermitRow("الظهور فوق التطبيقات", overlayOn) {
        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = model.confirmedChat, onCheckedChange = { model.confirmedChat = it })
        Text("المحادثة المفتوحة هي الوجهة", color = Ink)
    }
    PrimaryButton("بدء الإرسال", !model.busy) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notices.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        model.startSend(context)
    }
    Text("يمكنك الإيقاف من الفقاعة العائمة. الضغط على الإرسال لا يؤكد وصول الرسالة.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    if (model.message.isNotBlank()) Text(model.message, color = Ink)
    if (model.sendItems.isNotEmpty()) {
        Text("الطابور: ${sendStateArabic(model.sendState)}", color = Ink, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlineButton("إيقاف مؤقت", Modifier.weight(1f)) { model.pauseSend() }
            OutlineButton("استئناف", Modifier.weight(1f)) { model.resumeSend() }
        }
        DangerButton("إنهاء الإرسال") { model.stopSend(context) }
        val uncertain = model.sendItems.any { it.status == "UNCERTAIN" || it.status == "EXECUTION_STARTED" }
        if (uncertain) {
            Banner(AmberBg, Amber, "رسالة غير مؤكدة. راجعها قبل إعادة المحاولة.")
            PrimaryButton("راجعت الرسالة") { model.reviewUncertain() }
        }
        model.sendItems.take(12).forEach { item ->
            Text("${item.position + 1}. ${statusArabic(item.status)} — ${item.body.take(80)}", color = Ink, fontSize = 13.sp)
        }
    }
}

@Composable
private fun LinkHome(model: HomeViewModel, context: Context) {
    val link by model.link.collectAsState()
    Header("ربط الجداول", "إعداد مرة واحدة", onBack = { model.screen = Screen.Audit })
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusPill(link.email != null && link.phase != LinkPhase.AUTH_FAILED && link.phase != LinkPhase.CANCELED && link.phase != LinkPhase.NONE, "")
            Column(Modifier.weight(1f)) {
                Text(if (link.email != null) "حساب Google مرتبط" else "لم يُربط حساب Google", color = Ink, fontWeight = FontWeight.Bold)
                Text(link.email ?: link.message, color = Muted, fontSize = 13.sp)
            }
        }
        if (link.diagnostic.isNotBlank()) Text("رمز التشخيص: ${link.diagnostic}", color = Amber, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        PrimaryButton(if (link.phase == LinkPhase.NONE || link.phase == LinkPhase.CANCELED) "ربط Google" else "إعادة محاولة الربط") {
            (context.applicationContext as LorkApp).session.begin()
        }
    }
    SourceCard("الإدارة", "جدول الإدارة", model.sources.mgmtSheet, model.role == "إدارة") { model.role = "إدارة" }
    SourceCard("الوكيل", "جدول الوكيل", model.sources.agentSheet, model.role == "وكيل") { model.role = "وكيل" }
    SourceCard("معلومات المستخدمين", "قاعدة المستخدمين", model.sources.userSheet, model.role == "مستخدمون") { model.role = "مستخدمون" }
    SearchField(model.pasted, "لصق رابط ملف أو معرّفه") { model.pasted = it }
    OutlineButton("استخدام رابط ملف", Modifier.fillMaxWidth()) { model.applyPastedId() }
    OutlineButton(if (model.busy) "جارٍ التحميل" else "عرض الجداول", Modifier.fillMaxWidth(), !model.busy) { model.loadFiles(context) }
    if (model.fileMessage.isNotBlank()) Text(model.fileMessage, color = Ink, fontSize = 13.sp)
    SearchField(model.fileQuery, "بحث في أسماء الملفات") { model.fileQuery = it }
    model.files.filter { it.name.contains(model.fileQuery, ignoreCase = true) }.take(20).forEach { file ->
        SelectLine(file.name.ifBlank { file.id }) { model.openFile(file.id, file.name) }
    }
    if (model.selectedFile.isNotBlank()) Text(model.selectedFile, color = Muted, fontSize = 12.sp)
    model.sheetTitles.forEach { title -> SelectLine(title) { model.chooseSheet(title) } }
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
    PrimaryButton("حفظ الربط وتنزيل البيانات", !model.busy) {
        model.savePreferences()
        if (model.sources.complete) model.download(false, context)
    }
    OutlineButton("تصدير النسخة المحلية", Modifier.fillMaxWidth()) { model.export(context) }
    Text(model.diagnostic(context), color = Muted, fontSize = 12.sp)
    OutlineButton("نسخ التشخيص", Modifier.fillMaxWidth()) { model.copyDiagnostic(context) }
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
private fun SourceCard(title: String, hint: String, value: String, selected: Boolean, onClick: () -> Unit) {
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
        SelectLine(value.ifBlank { hint }, onClick)
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
