package cloud.lork.audit.engine

/** The search screen's execute-audit-advanced flow, independently of payroll user-info writes. */
data class SelectiveAuditPlan(
    val operationId: String,
    val accountEmail: String,
    val cycleKey: String,
    val memberId: String,
    val managementSpreadsheetId: String,
    val managementSheet: String,
    val agentSpreadsheetId: String,
    val agentSheet: String,
    val targetTitle: String,
    val createIfMissing: Boolean,
    val managementIdColumn: Int,
    val agentIdColumn: Int,
    val managementRow: List<String>,
    val statusLabel: String,
    val salaryBefore: Double,
    val salaryAfter: Double,
    val inAgent: Boolean,
    val agentColor: String,
    val managementColor: String,
)

data class SelectiveAuditPreparation(val plan: SelectiveAuditPlan?, val message: String)
data class SelectiveAuditReport(val applied: Boolean, val message: String, val alreadyPresent: Boolean = false)

fun prepareSelectiveAudit(
    accountEmail: String,
    cycleKey: String,
    memberRaw: String,
    managementRows: List<List<String>>,
    agentRows: List<List<String>>,
    columns: AuditColumns,
    managementSpreadsheetId: String,
    managementSheet: String,
    agentSpreadsheetId: String,
    agentSheet: String,
    targetTitleRaw: String,
    createIfMissing: Boolean,
    discountRate: Double,
    agentColor: String,
    managementColor: String,
): SelectiveAuditPreparation {
    val id = normalizeUserId(memberRaw)
    if (id.isBlank()) return SelectiveAuditPreparation(null, "أدخل رقم المستخدم أولاً.")
    val title = targetTitleRaw.trim()
    if (title.isBlank()) return SelectiveAuditPreparation(null, "اختر ورقة هدف أو اكتب اسماً لورقة جديدة في جدول الإدارة.")
    if (title.length > 100 || title.any { it in ":\\/?*[]" }) {
        return SelectiveAuditPreparation(null, "اسم الورقة غير صالح. الحد 100 حرف، ولا يقبل : \\ / ? * [ ].")
    }
    if (listOf(managementSpreadsheetId, managementSheet, agentSpreadsheetId, agentSheet).any { it.isBlank() }) {
        return SelectiveAuditPreparation(null, "اختر جداول وأوراق الدورة أولاً.")
    }
    // Search's advanced audit deliberately uses the first management match and skips row 1.
    val managementIdColumn = legacySearchColumnIndex(columns.cycleMgmtUserIdCol.ifBlank { "A" })
    val agentIdColumn = legacySearchColumnIndex(columns.cycleAgentUserIdCol.ifBlank { "A" })
    val salaryColumn = columnLetterToIndex(columns.cycleAgentSalaryCol.ifBlank { "D" }) ?: 3
    val managementRow = managementRows.drop(1).firstOrNull {
        normalizeUserId(cellAt(it, managementIdColumn)) == id
    }
    val agentMatches = agentRows.drop(1).filter { normalizeUserId(cellAt(it, agentIdColumn)) == id }
    if (managementRow == null) {
        return SelectiveAuditPreparation(null, if (agentMatches.isEmpty()) {
            "هذا المستخدم غير موجود في بيانات الدورة المختارة."
        } else {
            "المستخدم موجود في الوكيل فقط؛ لا يوجد صف إدارة لنسخه إلى الورقة الهدف."
        })
    }
    val status = when {
        agentMatches.size > 1 -> "سحب وكيل راتبين"
        agentMatches.isNotEmpty() -> "سحب وكيل"
        else -> "سحب ادارة"
    }
    val salary = computeSalaryWithDiscount(agentMatches.map { cellAt(it, salaryColumn) }, discountRate)
    fun color(value: String, fallback: String) = value.takeIf { Regex("#[0-9a-fA-F]{6}").matches(it) } ?: fallback
    val chosenAgent = color(agentColor, "#8b5cf6")
    val chosenManagement = color(managementColor, "#facc15")
    val operationId = hashUserInfoRows(listOf(
        listOf(accountEmail, cycleKey, managementSpreadsheetId, managementSheet, agentSpreadsheetId, agentSheet,
            title, id, status, salary.first.toString(), salary.second.toString(), chosenAgent, chosenManagement),
        managementRow,
    ) + agentMatches)
    return SelectiveAuditPreparation(
        SelectiveAuditPlan(operationId, accountEmail, cycleKey, id, managementSpreadsheetId, managementSheet,
            agentSpreadsheetId, agentSheet, title, createIfMissing, managementIdColumn, agentIdColumn,
            managementRow, status, salary.first, salary.second, agentMatches.isNotEmpty(), chosenAgent, chosenManagement),
        "نسخ صف الإدارة إلى «$title» وتلوين الصفوف وتحديث حالة التدقيق.",
    )
}

/** A durable append receipt plus read-before-retry prevents repeating an uncertain append. */
class SelectiveAuditApply(private val sheets: SheetGateway, private val ops: OpStore) {
    fun apply(plan: SelectiveAuditPlan, currentAccount: String, currentCycleKey: String): SelectiveAuditReport {
        if (plan.accountEmail != currentAccount || plan.cycleKey != currentCycleKey) {
            return SelectiveAuditReport(false, "هذه العملية لا تخص الحساب أو الدورة الحالية.")
        }
        return try {
            val appendKey = "manual:${plan.operationId}:append"
            var titles = sheets.listSheets(plan.managementSpreadsheetId)
            if (plan.targetTitle !in titles) {
                if (!plan.createIfMissing) return SelectiveAuditReport(false, "الورقة الهدف غير موجودة. اختر ورقة أخرى أو فعّل إنشاء ورقة جديدة.")
                val key = "manual:${plan.operationId}:create"
                ops.save(StoredOp(key, OpStatus.PENDING, "إنشاء الورقة الهدف"))
                sheets.addSheets(plan.managementSpreadsheetId, listOf(plan.targetTitle))
                titles = sheets.listSheets(plan.managementSpreadsheetId)
                if (plan.targetTitle !in titles) return SelectiveAuditReport(false, "تعذر تأكيد إنشاء الورقة الهدف. أعد المحاولة للتحقق قبل الكتابة.")
                ops.save(StoredOp(key, OpStatus.SUCCEEDED, "تم التحقق من الورقة"))
            }
            var targetRows = sheets.read(plan.managementSpreadsheetId, plan.targetTitle)
            fun targetHasMember() = targetRows.any { normalizeUserId(cellAt(it, plan.managementIdColumn)) == plan.memberId }
            val previousAppend = ops.get(appendKey)
            if (targetHasMember() && previousAppend == null) {
                return SelectiveAuditReport(false, "المستخدم موجود مسبقاً في الورقة الهدف. لا حاجة لتكرار الصف.", alreadyPresent = true)
            }
            if (!targetHasMember()) {
                // Confirm that the cached management row still belongs to the current source before copying.
                val source = sheets.read(plan.managementSpreadsheetId, plan.managementSheet)
                val current = source.drop(1).firstOrNull { normalizeUserId(cellAt(it, plan.managementIdColumn)) == plan.memberId }
                if (current == null || trimRow(current) != trimRow(plan.managementRow)) {
                    return SelectiveAuditReport(false, "تغيّر صف الإدارة منذ تحميل الدورة. أعد تحميل البيانات قبل التدقيق الانتقائي.")
                }
                ops.save(StoredOp(appendKey, OpStatus.PENDING, "بدأ نسخ صف الإدارة؛ يجب التحقق قبل الإعادة"))
                sheets.append(plan.managementSpreadsheetId, "${quoteSheet(plan.targetTitle)}!A:ZZ", listOf(plan.managementRow))
                targetRows = sheets.read(plan.managementSpreadsheetId, plan.targetTitle)
                if (!targetHasMember()) return SelectiveAuditReport(false, "تعذر تأكيد نسخ الصف. أعد المحاولة للتحقق، ولن يُكرر صف موجود.")
            }
            ops.save(StoredOp(appendKey, OpStatus.SUCCEEDED, "تم التحقق من وجود المستخدم في الورقة الهدف"))
            val chosen = if (plan.inAgent) plan.agentColor else plan.managementColor
            colorMatching(plan, "target", plan.managementSpreadsheetId, plan.targetTitle, plan.managementIdColumn, chosen, skipHeader = false)
            colorMatching(plan, "management", plan.managementSpreadsheetId, plan.managementSheet, plan.managementIdColumn, chosen)
            if (plan.inAgent) colorMatching(plan, "agent", plan.agentSpreadsheetId, plan.agentSheet, plan.agentIdColumn, plan.agentColor)
            SelectiveAuditReport(true, "تم تنفيذ التدقيق الانتقائي في جدول الإدارة وتحديث حالة البحث.\n${plan.statusLabel}\nقبل الخصم: ${plan.salaryBefore} / بعد الخصم: ${plan.salaryAfter}")
        } catch (e: Exception) {
            SelectiveAuditReport(false, "لم تكتمل عملية التدقيق الانتقائي: ${e.message ?: "تعذر الاتصال"}. أعد المحاولة للتحقق من الخطوات المحفوظة دون تكرار الصف.")
        }
    }

    private fun colorMatching(plan: SelectiveAuditPlan, side: String, spreadsheetId: String, title: String, idColumn: Int, hex: String, skipHeader: Boolean = true) {
        val rows = sheets.read(spreadsheetId, title)
        val indices = rows.indices.filter { (!skipHeader || it > 0) && normalizeUserId(cellAt(rows[it], idColumn)) == plan.memberId }
        if (indices.isEmpty()) throw SheetAccessException("MANUAL_SOURCE_CHANGED", "لم يعد المستخدم موجوداً في ورقة «$title»")
        val key = "manual:${plan.operationId}:color:$side"
        val before = sheets.readBackgrounds(spreadsheetId, title)
        if (!indices.all { sameColor(hex, before[it]) }) {
            ops.save(StoredOp(key, OpStatus.PENDING, "تلوين صفوف المستخدم"))
            sheets.colorRows(spreadsheetId, title, indices, hex)
        }
        val after = sheets.readBackgrounds(spreadsheetId, title)
        if (!indices.all { sameColor(hex, after[it]) }) throw SheetAccessException("MANUAL_COLOR_UNCONFIRMED", "تعذر تأكيد تلوين ورقة «$title»")
        ops.save(StoredOp(key, OpStatus.SUCCEEDED, "تم التحقق من التلوين"))
    }

    private fun trimRow(row: List<String>) = row.dropLastWhile { it.isEmpty() }
}
