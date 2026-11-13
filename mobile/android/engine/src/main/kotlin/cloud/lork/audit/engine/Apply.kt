package cloud.lork.audit.engine

data class PasteRow(val userId: String, val cells: List<String>)

data class PasteBatch(val sheetTitle: String, val rows: List<PasteRow>, val skippedAudited: Int, val skippedExisting: Int)

data class ColorTarget(val userId: String, val hex: String)

data class AuditMark(val userId: String, val source: String, val type: String, val salary: String)

data class WritePlan(
    val planId: String,
    val accountEmail: String,
    val cycleKey: String,
    val userInfoSpreadsheetId: String,
    val userInfoSheet: String,
    val userInfoHash: String,
    val userInfoCoreHash: String = "",
    val mgmtSpreadsheetId: String,
    val mgmtSheet: String,
    val agentSpreadsheetId: String,
    val agentSheet: String,
    val salaryCol: String,
    val statusCol: String,
    val dataStartRow1: Int,
    val userIdsInOrder: List<String>,
    val sheetsToCreate: List<String>,
    val pastes: List<PasteBatch>,
    val salaryColumn: List<String>,
    val statusColumn: List<String>,
    val mgmtColors: List<ColorTarget>,
    val agentColors: List<ColorTarget>,
    val auditMarks: List<AuditMark>,
    val userIdColumnIndex: Int = 2,
    val mgmtUserIdColumnIndex: Int = 0,
    val agentUserIdColumnIndex: Int = 0,
    val blockedCode: String? = null,
    val blockedMessage: String? = null,
) {
    val blocked: Boolean get() = blockedCode != null
}

data class ExistingTargetSheet(val title: String, val rows: List<List<String>>)

fun buildWritePlan(
    planId: String,
    accountEmail: String,
    cycleKey: String,
    audit: AuditOutput,
    columns: AuditColumns,
    userInfoRows: List<List<String>>,
    userInfoSpreadsheetId: String,
    userInfoSheet: String,
    mgmtSpreadsheetId: String,
    mgmtSheet: String,
    agentSpreadsheetId: String,
    agentSheet: String,
    existingSheetTitles: List<String>,
    existingTargets: List<ExistingTargetSheet>,
    previouslyAuditedIds: Set<String>,
    previousUserInfoHash: String?,
    force: Boolean,
    agentColor: String,
    managementColor: String,
): WritePlan {
    val userIdCol = columnLetterToIndex(columns.userInfoUserIdCol) ?: 2
    val mgmtCol = columnLetterToIndex(columns.cycleMgmtUserIdCol) ?: 0
    val salaryLetter = columns.userInfoSalaryCol.trim().uppercase().ifEmpty { "L" }
    val salaryIdx = columnLetterToIndex(salaryLetter) ?: 11
    val statusLetter = columns.userInfoStatusCol.trim().uppercase().ifEmpty { columnIndexToLetter(salaryIdx + 1) }
    val hash = hashUserInfoRows(userInfoRows)
    val coreHash = hashIgnoringAuditColumns(userInfoRows, salaryLetter, statusLetter)
    val dup = findDuplicateUserInfoMemberIds(userInfoRows, columns.userInfoUserIdCol)
    if (dup.duplicateIds.isNotEmpty()) {
        return emptyPlan(
            planId, accountEmail, cycleKey, userInfoSpreadsheetId, userInfoSheet, hash,
            mgmtSpreadsheetId, mgmtSheet, agentSpreadsheetId, agentSheet, salaryLetter, statusLetter,
            "USER_INFO_DUPLICATE_IDS",
            coreHash,
            "جدول معلومات المستخدمين يحتوي رقم مستخدم مكرراً في الصفوف: " +
                dup.duplicateRowNumbers.take(20).joinToString(", "),
        )
    }
    if (!previousUserInfoHash.isNullOrEmpty() && previousUserInfoHash == hash && !force) {
        return emptyPlan(
            planId, accountEmail, cycleKey, userInfoSpreadsheetId, userInfoSheet, hash,
            mgmtSpreadsheetId, mgmtSheet, agentSpreadsheetId, agentSheet, salaryLetter, statusLetter,
            "USER_INFO_UNCHANGED",
            coreHash,
            "لم يتغيّر جدول معلومات المستخدمين منذ آخر تدقيق. يمكن فرض الإعادة بعد المراجعة.",
        )
    }

    val names = assignSheetNames(audit.byTitle.keys.toList())
    val orderedSafe = names.values.toList()
    val uniqueByLower = mutableListOf<String>()
    val seenLower = mutableSetOf<String>()
    for (name in orderedSafe) {
        val lower = name.lowercase()
        if (seenLower.add(lower)) uniqueByLower += name
    }
    val existingLower = existingSheetTitles.map { it.lowercase() }.toSet()
    val toCreate = uniqueByLower.filter { it.lowercase() !in existingLower }

    val pastes = mutableListOf<PasteBatch>()
    for ((title, items) in audit.byTitle) {
        val sheetName = names[title] ?: continue
        if (items.isEmpty()) continue
        val existingRows = existingTargets.firstOrNull { it.title == sheetName }?.rows.orEmpty()
        val existingIds = linkedSetOf<String>()
        existingRows.forEachIndexed { idx, row ->
            if (idx == 0 && isHeaderRowCycle(row, mgmtCol)) return@forEachIndexed
            val id = normalizeUserId(cellAt(row, mgmtCol))
            if (id.isNotEmpty()) existingIds += id
        }
        val seen = mutableSetOf<String>()
        val kept = mutableListOf<PasteRow>()
        var skipAudited = 0
        var skipExisting = 0
        for (item in items) {
            val id = normalizeUserId(cellAt(item.managementRow, mgmtCol))
            if (id.isEmpty() || seen.contains(id)) continue
            if (previouslyAuditedIds.contains(id)) {
                skipAudited++
                continue
            }
            if (existingIds.contains(id)) {
                skipExisting++
                continue
            }
            seen += id
            kept += PasteRow(id, item.managementRow)
        }
        if (kept.isNotEmpty() || skipAudited > 0 || skipExisting > 0) {
            pastes += PasteBatch(sheetName, kept, skipAudited, skipExisting)
        }
    }

    val byRowSalary = linkedMapOf<Int, String>()
    val byRowStatus = linkedMapOf<Int, String>()
    for (r in audit.results) {
        if (r.type.startsWith("سحب وكالة") || r.type == "سحب إدارة") {
            byRowSalary[r.rowIndex] = salaryToCell(r.salaryValue)
            byRowStatus[r.rowIndex] = r.statusLabel
        } else if (r.type == "غير موجود") {
            byRowSalary[r.rowIndex] = "0"
            byRowStatus[r.rowIndex] = "غير موجود"
        }
    }
    val minRow = audit.dataStart + 1
    val maxRow = audit.dataStart + (userInfoRows.size - audit.dataStart)
    val salaryColumn = mutableListOf<String>()
    val statusColumn = mutableListOf<String>()
    val userIds = mutableListOf<String>()
    if (maxRow >= minRow) {
        for (rowNum in minRow..maxRow) {
            salaryColumn += byRowSalary[rowNum] ?: ""
            statusColumn += byRowStatus[rowNum] ?: ""
            userIds += normalizeUserId(cellAt(userInfoRows[rowNum - 1], userIdCol))
        }
    }

    val mgmtColors = mutableListOf<ColorTarget>()
    val agentColors = mutableListOf<ColorTarget>()
    val marks = mutableListOf<AuditMark>()
    for (r in audit.results) {
        if (r.userId.isEmpty()) continue
        if (r.type.startsWith("سحب وكالة")) {
            mgmtColors += ColorTarget(r.userId, agentColor)
            r.agentSheetRowIndices.forEach { agentColors += ColorTarget(r.userId, agentColor) }
            marks += AuditMark(r.userId, "تدقيق وكيل من النظام", r.type, salaryToCell(r.salaryValue))
        } else if (r.type == "سحب إدارة") {
            mgmtColors += ColorTarget(r.userId, managementColor)
            marks += AuditMark(r.userId, "تدقيق ادارة من النظام", r.type, salaryToCell(r.salaryValue))
        }
    }

    return WritePlan(
        planId = planId,
        accountEmail = accountEmail,
        cycleKey = cycleKey,
        userInfoSpreadsheetId = userInfoSpreadsheetId,
        userInfoSheet = userInfoSheet,
        userInfoHash = hash,
        userInfoCoreHash = coreHash,
        mgmtSpreadsheetId = mgmtSpreadsheetId,
        mgmtSheet = mgmtSheet,
        agentSpreadsheetId = agentSpreadsheetId,
        agentSheet = agentSheet,
        salaryCol = salaryLetter,
        statusCol = statusLetter,
        dataStartRow1 = minRow,
        userIdsInOrder = userIds,
        sheetsToCreate = toCreate,
        pastes = pastes,
        salaryColumn = salaryColumn,
        statusColumn = statusColumn,
        mgmtColors = mgmtColors.distinctBy { it.userId },
        agentColors = agentColors.distinctBy { it.userId },
        auditMarks = marks.distinctBy { it.userId },
        userIdColumnIndex = userIdCol,
        mgmtUserIdColumnIndex = mgmtCol,
        agentUserIdColumnIndex = columnLetterToIndex(columns.cycleAgentUserIdCol) ?: 0,
    )
}

private fun salaryToCell(value: Any): String = when (value) {
    is Double -> {
        if (value.isNaN()) "" else {
            val rounded = jsMathRound(value * 100.0) / 100.0
            if (rounded == kotlin.math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
        }
    }
    is Int -> value.toString()
    is Long -> value.toString()
    else -> value.toString()
}

private fun emptyPlan(
    planId: String,
    accountEmail: String,
    cycleKey: String,
    userInfoSpreadsheetId: String,
    userInfoSheet: String,
    hash: String,
    mgmtSpreadsheetId: String,
    mgmtSheet: String,
    agentSpreadsheetId: String,
    agentSheet: String,
    salaryCol: String,
    statusCol: String,
    code: String,
    coreHash: String,
    message: String,
) = WritePlan(
    planId = planId,
    accountEmail = accountEmail,
    cycleKey = cycleKey,
    userInfoSpreadsheetId = userInfoSpreadsheetId,
    userInfoSheet = userInfoSheet,
        userInfoHash = hash,
        userInfoCoreHash = coreHash,
        mgmtSpreadsheetId = mgmtSpreadsheetId,
    mgmtSheet = mgmtSheet,
    agentSpreadsheetId = agentSpreadsheetId,
    agentSheet = agentSheet,
    salaryCol = salaryCol,
    statusCol = statusCol,
    dataStartRow1 = 1,
    userIdsInOrder = emptyList(),
    sheetsToCreate = emptyList(),
    pastes = emptyList(),
    salaryColumn = emptyList(),
    statusColumn = emptyList(),
    mgmtColors = emptyList(),
    agentColors = emptyList(),
    auditMarks = emptyList(),
    blockedCode = code,
    blockedMessage = message,
)

class SheetAccessException(val code: String, message: String) : Exception(message)

interface SheetGateway {
    fun listSheets(spreadsheetId: String): List<String>
    fun read(spreadsheetId: String, title: String): List<List<String>>
    fun addSheets(spreadsheetId: String, titles: List<String>)
    fun updateRange(spreadsheetId: String, a1: String, values: List<List<String>>)
    fun append(spreadsheetId: String, a1: String, values: List<List<String>>)
    fun colorRows(spreadsheetId: String, title: String, zeroBasedRows: List<Int>, hex: String)
    fun readBackgrounds(spreadsheetId: String, title: String): Map<Int, String>
}

enum class OpStatus { PENDING, SUCCEEDED, NEEDS_REVIEW }

data class StoredOp(val key: String, val status: OpStatus, val detail: String)

interface OpStore {
    fun get(key: String): StoredOp?
    fun save(op: StoredOp)
}

class MemoryOpStore : OpStore {
    private val map = linkedMapOf<String, StoredOp>()
    override fun get(key: String): StoredOp? = map[key]
    override fun save(op: StoredOp) { map[op.key] = op }
    fun all(): List<StoredOp> = map.values.toList()
}

data class ApplyReport(
    val status: String,
    val message: String,
    val stages: List<String>,
    val appliedMarks: List<AuditMark>,
)

fun explainApply(report: ApplyReport): String = report.message

class SheetApply(private val sheets: SheetGateway, private val ops: OpStore) {
    fun apply(plan: WritePlan, currentAccount: String, currentCycleKey: String): ApplyReport {
        val stages = mutableListOf<String>()
        if (plan.blocked) {
            return ApplyReport("blocked", plan.blockedMessage ?: "الخطة موقوفة", stages, emptyList())
        }
        if (currentAccount != plan.accountEmail || currentCycleKey != plan.cycleKey) {
            return ApplyReport(
                "needs_review",
                "تغيّر الحساب أو الدورة بعد بناء الخطة. لن تُكتب النتائج على مصدر آخر.",
                stages,
                emptyList(),
            )
        }
        val live = try {
            sheets.read(plan.userInfoSpreadsheetId, plan.userInfoSheet)
        } catch (e: SheetAccessException) {
            return ApplyReport("failed", e.message ?: "تعذر قراءة جدول المستخدمين", stages, emptyList())
        }
        if (hashUserInfoRows(live) != plan.userInfoHash && !alreadyWrittenByThisPlan(plan, live)) {
            stages += "تعارض: تغيّر جدول معلومات المستخدمين قبل الاعتماد"
            return ApplyReport(
                "needs_review",
                "تغيّرت بيانات المصدر بعد التدقيق. أعد التدقيق قبل أي كتابة.",
                stages,
                emptyList(),
            )
        }
        val liveIds = liveUserIds(live, plan)
        if (liveIds != plan.userIdsInOrder) {
            stages += "تعارض: ترتيب أرقام المستخدمين لم يعد مطابقاً"
            return ApplyReport(
                "needs_review",
                "تغيّر ترتيب الصفوف أو أرقامها. الخطة تحتاج مراجعة ولن تُعاد الكتابة تلقائياً.",
                stages,
                emptyList(),
            )
        }
        try {
            ensureSheets(plan, stages)
            pasteRows(plan, stages)
            writeSalaryAndStatus(plan, live, stages)
            color(plan, stages)
        } catch (e: SheetAccessException) {
            stages += "توقف: ${e.code}"
            return ApplyReport("failed", e.message ?: "فشل الاتصال", stages, emptyList())
        } catch (e: ReviewStop) {
            stages += e.message ?: "تحتاج مراجعة"
            return ApplyReport("needs_review", e.message ?: "تحتاج مراجعة", stages, emptyList())
        }

        stages += "اكتملت الكتابة بعد التحقق"
        return ApplyReport("succeeded", "تم اعتماد النتائج بعد التحقق مما كُتب.", stages, plan.auditMarks)
    }

    private fun alreadyWrittenByThisPlan(plan: WritePlan, live: List<List<String>>): Boolean {
        if (plan.userInfoCoreHash.isEmpty()) return false
        if (plan.salaryColumn.isEmpty() && plan.statusColumn.isEmpty()) return false
        val core = hashIgnoringAuditColumns(live, plan.salaryCol, plan.statusCol)
        if (core != plan.userInfoCoreHash) return false
        return columnMatches(live, plan.salaryCol, plan.dataStartRow1, plan.salaryColumn) &&
            columnMatches(live, plan.statusCol, plan.dataStartRow1, plan.statusColumn)
    }

    private fun liveUserIds(live: List<List<String>>, plan: WritePlan): List<String> {
        if (plan.userIdsInOrder.isEmpty()) return emptyList()
        val start = (plan.dataStartRow1 - 1).coerceAtLeast(0)
        if (start >= live.size) return emptyList()
        val slice = live.drop(start).take(plan.userIdsInOrder.size)
        if (slice.size != plan.userIdsInOrder.size) return emptyList()
        return slice.map { normalizeUserId(cellAt(it, plan.userIdColumnIndex)) }
    }

    private fun ensureSheets(plan: WritePlan, stages: MutableList<String>) {
        for (title in plan.sheetsToCreate) {
            val key = "plan:${plan.planId}:sheet:$title"
            if (ops.get(key)?.status == OpStatus.SUCCEEDED) {
                stages += "ورقة «$title» مسجّلة سابقاً"
                continue
            }
            val existing = sheets.listSheets(plan.mgmtSpreadsheetId)
            if (existing.any { it.equals(title, ignoreCase = true) }) {
                ops.save(StoredOp(key, OpStatus.SUCCEEDED, "موجودة"))
                stages += "ورقة «$title» موجودة ولن تُنشأ مرة ثانية"
                continue
            }
            sheets.addSheets(plan.mgmtSpreadsheetId, listOf(title))
            val after = sheets.listSheets(plan.mgmtSpreadsheetId)
            if (after.none { it.equals(title, ignoreCase = true) }) {
                ops.save(StoredOp(key, OpStatus.NEEDS_REVIEW, "لم تظهر الورقة بعد الإنشاء"))
                throw ReviewStop("أُرسل إنشاء الورقة «$title» ولم يمكن تأكيدها. لن يُعاد الإنشاء تلقائياً.")
            }
            ops.save(StoredOp(key, OpStatus.SUCCEEDED, "أُنشئت"))
            stages += "أُنشئت ورقة «$title»"
        }
    }

    private fun pasteRows(plan: WritePlan, stages: MutableList<String>) {
        val mgmtCol = plan.mgmtUserIdColumnIndex
        for (batch in plan.pastes) {
            for (row in batch.rows) {
                val key = "plan:${plan.planId}:paste:${batch.sheetTitle}:${row.userId}"
                if (ops.get(key)?.status == OpStatus.SUCCEEDED) continue
                val current = sheets.read(plan.mgmtSpreadsheetId, batch.sheetTitle)
                val ids = idsIn(current, mgmtCol)
                if (row.userId in ids) {
                    ops.save(StoredOp(key, OpStatus.SUCCEEDED, "موجود مسبقاً"))
                    stages += "الصف ${row.userId} موجود في «${batch.sheetTitle}» ولن يُلصق ثانية"
                    continue
                }
                val range = quoteSheet(batch.sheetTitle) + "!A:ZZ"
                sheets.append(plan.mgmtSpreadsheetId, range, listOf(row.cells))
                val after = sheets.read(plan.mgmtSpreadsheetId, batch.sheetTitle)
                val afterIds = idsIn(after, mgmtCol)
                if (row.userId !in afterIds) {
                    ops.save(StoredOp(key, OpStatus.NEEDS_REVIEW, "لم يُرَ المعرف بعد الإلحاق"))
                    throw ReviewStop("تعذر تأكيد لصق ${row.userId}. الحالة تحتاج مراجعة ولن يُعاد اللصق تلقائياً.")
                }
                ops.save(StoredOp(key, OpStatus.SUCCEEDED, "أُلصق"))
                stages += "أُلصق ${row.userId} في «${batch.sheetTitle}»"
            }
        }
    }

    private fun idsIn(rows: List<List<String>>, col: Int): Set<String> {
        val ids = linkedSetOf<String>()
        rows.forEachIndexed { idx, row ->
            if (idx == 0 && isHeaderRowCycle(row, col)) return@forEachIndexed
            val id = normalizeUserId(cellAt(row, col))
            if (id.isNotEmpty()) ids += id
        }
        return ids
    }

    private fun writeSalaryAndStatus(plan: WritePlan, live: List<List<String>>, stages: MutableList<String>) {
        if (plan.salaryColumn.isEmpty()) return
        val key = "plan:${plan.planId}:salary-status"
        if (ops.get(key)?.status == OpStatus.SUCCEEDED) return
        val start = plan.dataStartRow1
        val end = start + plan.salaryColumn.size - 1
        val salaryRange = quoteSheet(plan.userInfoSheet) + "!${plan.salaryCol}$start:${plan.salaryCol}$end"
        val statusRange = quoteSheet(plan.userInfoSheet) + "!${plan.statusCol}$start:${plan.statusCol}$end"
        val before = sheets.read(plan.userInfoSpreadsheetId, plan.userInfoSheet)
        if (columnMatches(before, plan.salaryCol, start, plan.salaryColumn) &&
            columnMatches(before, plan.statusCol, start, plan.statusColumn)
        ) {
            ops.save(StoredOp(key, OpStatus.SUCCEEDED, "مطابق قبل إعادة الكتابة"))
            stages += "أعمدة الراتب والحالة مطابقة ولن تُعاد كتابتها"
            return
        }
        sheets.updateRange(plan.userInfoSpreadsheetId, salaryRange, plan.salaryColumn.map { listOf(it) })
        sheets.updateRange(plan.userInfoSpreadsheetId, statusRange, plan.statusColumn.map { listOf(it) })
        val after = sheets.read(plan.userInfoSpreadsheetId, plan.userInfoSheet)
        if (!columnMatches(after, plan.salaryCol, start, plan.salaryColumn) ||
            !columnMatches(after, plan.statusCol, start, plan.statusColumn)
        ) {
            ops.save(StoredOp(key, OpStatus.NEEDS_REVIEW, "القراءة بعد الكتابة لا تطابق الخطة"))
            throw ReviewStop("كُتبت خلايا الراتب أو الحالة ولم تتطابق عند التحقق. تحتاج مراجعة ولن تُعاد الكتابة تلقائياً.")
        }
        ops.save(StoredOp(key, OpStatus.SUCCEEDED, "تم التحقق"))
        stages += "كُتبت أعمدة الراتب والحالة وتمت قراءتها للتحقق"
        @Suppress("UNUSED_VARIABLE")
        val unused = live
    }

    private fun columnMatches(rows: List<List<String>>, letter: String, startRow1: Int, expected: List<String>): Boolean {
        val idx = columnLetterToIndex(letter) ?: return false
        expected.forEachIndexed { i, want ->
            val row = rows.getOrNull(startRow1 - 1 + i) ?: return false
            val got = cellAt(row, idx)
            if (!sameCell(want, got)) return false
        }
        return true
    }

    private fun color(plan: WritePlan, stages: MutableList<String>) {
        colorSide(plan.planId, plan.mgmtSpreadsheetId, plan.mgmtSheet, plan.mgmtColors, "mgmt", plan.mgmtUserIdColumnIndex, stages)
        colorSide(plan.planId, plan.agentSpreadsheetId, plan.agentSheet, plan.agentColors, "agent", plan.agentUserIdColumnIndex, stages)
    }

    private fun colorSide(
        planId: String,
        spreadsheetId: String,
        title: String,
        targets: List<ColorTarget>,
        side: String,
        userIdColumn: Int,
        stages: MutableList<String>,
    ) {
        if (targets.isEmpty() || title.isEmpty()) return
        val rows = sheets.read(spreadsheetId, title)
        val pending = mutableListOf<Pair<ColorTarget, Int>>()
        for (target in targets) {
            val key = "plan:$planId:color:$side:${target.userId}"
            if (ops.get(key)?.status == OpStatus.SUCCEEDED) continue
            val hits = rows.mapIndexedNotNull { idx, row ->
                if (normalizeUserId(cellAt(row, userIdColumn)) == target.userId) idx else null
            }
            if (hits.isEmpty()) {
                ops.save(StoredOp(key, OpStatus.NEEDS_REVIEW, "لا صف بهذا المعرف"))
                throw ReviewStop("تلوين ${target.userId} غير محسوم: المعرف غير موجود في الورقة الحالية.")
            }
            val already = sheets.readBackgrounds(spreadsheetId, title)
            if (hits.all { sameColor(target.hex, already[it]) }) {
                ops.save(StoredOp(key, OpStatus.SUCCEEDED, "اللون مطابق قبل الإعادة"))
                stages += "تلوين $side للمستخدم ${target.userId} مؤكد ولن يُعاد"
                continue
            }
            hits.forEach { pending += target to it }
        }
        val byColor = pending.groupBy { it.first.hex }
        for ((hex, group) in byColor) {
            sheets.colorRows(spreadsheetId, title, group.map { it.second }.distinct(), hex)
        }
        val backgrounds = sheets.readBackgrounds(spreadsheetId, title)
        val byUser = pending.groupBy { it.first.userId }
        for ((userId, group) in byUser) {
            val key = "plan:$planId:color:$side:$userId"
            val hex = group.first().first.hex
            val confirmed = group.all { sameColor(hex, backgrounds[it.second]) }
            if (!confirmed) {
                ops.save(StoredOp(key, OpStatus.NEEDS_REVIEW, "اللون غير مؤكد"))
                throw ReviewStop("تعذر تأكيد تلوين $userId. تحتاج مراجعة ولن يُعاد التلوين تلقائياً.")
            }
            ops.save(StoredOp(key, OpStatus.SUCCEEDED, "تم التحقق"))
        }
        if (pending.isNotEmpty()) stages += "تلوين $side بعد التحقق"
    }
}

private class ReviewStop(message: String) : Exception(message)

fun sameColor(expected: String, actual: String?): Boolean {
    if (actual.isNullOrBlank()) return false
    fun channel(hex: String, index: Int): Int? {
        val h = hex.removePrefix("#")
        if (h.length != 6) return null
        return h.substring(index, index + 2).toIntOrNull(16)
    }
    return (0 until 6 step 2).all { i ->
        val a = channel(expected, i) ?: return false
        val b = channel(actual, i) ?: return false
        kotlin.math.abs(a - b) <= 1
    }
}

fun hashIgnoringAuditColumns(rows: List<List<String>>, salaryLetter: String, statusLetter: String): String {
    val salary = columnLetterToIndex(salaryLetter) ?: -1
    val status = columnLetterToIndex(statusLetter) ?: -1
    val needed = maxOf(salary, status, -1) + 1
    val width = maxOf(needed, rows.maxOfOrNull { it.size } ?: 0)
    val masked = rows.map { row ->
        List(width) { index ->
            if (index == salary || index == status) "" else row.getOrNull(index).orEmpty()
        }
    }
    return hashUserInfoRows(masked)
}

fun sameCell(expected: String, actual: String): Boolean {
    if (expected == actual) return true
    if (expected.isEmpty() && actual.isEmpty()) return true
    val a = jsParseFloat(normalizeForNumber(expected))
    val b = jsParseFloat(normalizeForNumber(actual))
    return !a.isNaN() && !b.isNaN() && a == b
}

data class Snap(
    val key: String,
    val accountEmail: String,
    val cycleKey: String,
    val management: List<List<String>>?,
    val agent: List<List<String>>?,
    val userInfo: List<List<String>>?,
    val complete: Boolean,
    val savedAt: Long,
    val sourceFingerprint: String,
)

class SnapshotBook {
    private val active = linkedMapOf<String, Snap>()
    private val drafts = linkedMapOf<String, Snap>()

    fun begin(key: String, accountEmail: String, cycleKey: String, fingerprint: String, now: Long) {
        drafts[key] = Snap(key, accountEmail, cycleKey, null, null, null, false, now, fingerprint)
    }

    fun put(key: String, role: String, rows: List<List<String>>) {
        val draft = drafts[key] ?: return
        drafts[key] = when (role) {
            "management" -> draft.copy(management = rows)
            "agent" -> draft.copy(agent = rows)
            "userInfo" -> draft.copy(userInfo = rows)
            else -> draft
        }
    }

    fun commit(key: String): Boolean {
        val draft = drafts[key] ?: return false
        val user = draft.userInfo
        val mgmt = draft.management
        val agent = draft.agent
        val ok = user != null && mgmt != null && agent != null &&
            user.isNotEmpty() &&
            !(mgmt.isEmpty() && agent.isEmpty())
        if (!ok) return false
        active[key] = draft.copy(complete = true)
        drafts.remove(key)
        return true
    }

    fun abort(key: String) {
        drafts.remove(key)
    }

    fun active(key: String): Snap? = active[key]
}

enum class LinkPhase {
    NONE,
    CANCELED,
    WAITING_CONSENT,
    AUTH_FAILED,
    AUTHORIZED,
    SOURCES_PARTIAL,
    SOURCES_READY,
    DATA_READY,
}

data class LinkState(
    val phase: LinkPhase = LinkPhase.NONE,
    val email: String? = null,
    val message: String = "لم يُربط حساب Google بعد.",
    val diagnostic: String = "",
)

sealed class LinkEvent {
    data object Cancelled : LinkEvent()
    data class ConsentShown(val emailHint: String?) : LinkEvent()
    data class Authorized(val email: String) : LinkEvent()
    data class AuthFailed(val diagnostic: String, val message: String) : LinkEvent()
    data object SourcesPartial : LinkEvent()
    data object SourcesReady : LinkEvent()
    data object DataReady : LinkEvent()
    data object AccountSwitched : LinkEvent()
}

fun reduceLink(state: LinkState, event: LinkEvent): LinkState = when (event) {
    LinkEvent.Cancelled -> state.copy(
        phase = LinkPhase.CANCELED,
        message = "أُلغي اختيار الحساب أو الموافقة. الربط لم يكتمل.",
        diagnostic = "GS-CANCEL",
    )
    is LinkEvent.ConsentShown -> state.copy(
        phase = LinkPhase.WAITING_CONSENT,
        email = event.emailHint ?: state.email,
        message = "بانتظار إكمال الموافقة. اختيار الحساب وحده لا يعني اكتمال الربط.",
        diagnostic = "GS-WAIT",
    )
    is LinkEvent.Authorized -> state.copy(
        phase = LinkPhase.AUTHORIZED,
        email = event.email,
        message = "تم تفويض الحساب. اختر ملفات الدورة ثم نزّل البيانات.",
        diagnostic = "",
    )
    is LinkEvent.AuthFailed -> state.copy(
        phase = LinkPhase.AUTH_FAILED,
        message = event.message,
        diagnostic = event.diagnostic,
    )
    LinkEvent.SourcesPartial -> state.copy(
        phase = LinkPhase.SOURCES_PARTIAL,
        message = "ما زال أحد مصادر الدورة ناقصاً: الإدارة أو الوكيل أو معلومات المستخدمين.",
        diagnostic = "",
    )
    LinkEvent.SourcesReady -> state.copy(
        phase = LinkPhase.SOURCES_READY,
        message = "اكتمل اختيار الأوراق. الخطوة التالية تنزيل بيانات الدورة إلى الهاتف.",
        diagnostic = "",
    )
    LinkEvent.DataReady -> state.copy(
        phase = LinkPhase.DATA_READY,
        message = "توجد نسخة محلية صالحة. يمكن التدقيق دون تنزيل جديد.",
        diagnostic = "",
    )
    LinkEvent.AccountSwitched -> LinkState(
        phase = LinkPhase.NONE,
        message = "تغيّر الحساب. لن تُستخدم بيانات الحساب السابق مع هذا الربط.",
        diagnostic = "GS-SWITCH",
    )
}

enum class Readiness { NEED_ACCOUNT, NEED_AUTH, NEED_SOURCES, NEED_DOWNLOAD, OFFLINE_NEED_DOWNLOAD, READY }

data class ReadinessAdvice(val readiness: Readiness, val message: String, val action: String)

fun auditReadiness(link: LinkState, hasSnapshot: Boolean, online: Boolean): ReadinessAdvice {
    if (hasSnapshot && link.phase != LinkPhase.NONE && link.phase != LinkPhase.CANCELED) {
        return ReadinessAdvice(
            Readiness.READY,
            "البيانات المحلية جاهزة لهذه الدورة والحساب. المزامنة اختيارية.",
            "تنفيذ التدقيق",
        )
    }
    if (!online && !hasSnapshot) {
        return ReadinessAdvice(
            Readiness.OFFLINE_NEED_DOWNLOAD,
            "لا توجد نسخة محلية. التدقيق يعمل دون إنترنت بعد تنزيل البيانات مرة واحدة.",
            "تنزيل بيانات الدورة",
        )
    }
    return when (link.phase) {
        LinkPhase.NONE, LinkPhase.CANCELED -> ReadinessAdvice(
            Readiness.NEED_ACCOUNT,
            "اربط حساب Google من الإعدادات قبل اختيار الجداول.",
            "فتح الإعدادات",
        )
        LinkPhase.WAITING_CONSENT, LinkPhase.AUTH_FAILED -> ReadinessAdvice(
            Readiness.NEED_AUTH,
            link.message,
            "إعادة محاولة الربط",
        )
        LinkPhase.AUTHORIZED, LinkPhase.SOURCES_PARTIAL -> ReadinessAdvice(
            Readiness.NEED_SOURCES,
            "اختيار الحساب لا يكفي. حدّد ملفات وأوراق الإدارة والوكيل ومعلومات المستخدمين.",
            "إكمال المصادر",
        )
        LinkPhase.SOURCES_READY, LinkPhase.DATA_READY -> ReadinessAdvice(
            Readiness.NEED_DOWNLOAD,
            "الأوراق محددة ولا توجد بيانات على الهاتف. نزّل بيانات الدورة ثم دقّق محلياً.",
            "تنزيل بيانات الدورة",
        )
    }
}

data class DriveFile(val id: String, val name: String)

data class DrivePage(val files: List<DriveFile>?, val nextToken: String?, val errorCode: String?, val errorMessage: String?)

data class DriveListResult(val files: List<DriveFile>, val errorCode: String?, val message: String)

fun collectDriveFiles(maxPages: Int = 20, fetch: (pageToken: String?) -> DrivePage): DriveListResult {
    val all = mutableListOf<DriveFile>()
    var token: String? = null
    var pages = 0
    val seen = mutableSetOf<String>()
    while (pages < maxPages) {
        val page = fetch(token)
        pages++
        if (page.errorCode != null) {
            val msg = when (page.errorCode) {
                "401" -> "انتهت صلاحية التفويض. أعد الربط."
                "403" -> "الحساب لا يملك صلاحية عرض الملفات. هذا ليس عدم وجود جداول."
                "timeout" -> "تأخرت الاستجابة. أعد المحاولة."
                "network" -> "انقطع الاتصال أثناء جلب قائمة الملفات."
                else -> page.errorMessage ?: "تعذر قراءة قائمة الملفات."
            }
            return DriveListResult(emptyList(), page.errorCode, msg)
        }
        val files = page.files.orEmpty()
        for (f in files) if (seen.add(f.id)) all += f
        val next = page.nextToken
        if (next.isNullOrEmpty() || !seen.add("token:$next")) break
        token = next
    }
    if (all.isEmpty()) {
        return DriveListResult(emptyList(), null, "لا توجد جداول في هذا الحساب ضمن الصلاحية الممنوحة.")
    }
    return DriveListResult(all, null, "تم العثور على ${all.size} جدولاً")
}

fun safeDiagnostic(code: String, raw: String?): String {
    val cleaned = (raw ?: "")
        .replace(Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]+"), "bearer [redacted]")
        .replace(Regex("ya29\\.[A-Za-z0-9._\\-]+"), "[redacted]")
        .replace(Regex("(?i)access_token\"?\\s*[:=]\\s*\"?[A-Za-z0-9._\\-]+"), "access_token=[redacted]")
    return (code + " " + cleaned.take(160)).trim()
}

data class ManualAuditUpdate(
    val status: String,
    val source: String,
    val auditedAgentIds: Set<String>,
    val auditedMgmtIds: Set<String>,
)

/** مطابقة POST /api/search/execute-audit: تعليم محلي دون كتابة Google. */
fun markManualFromSearch(
    memberRaw: String,
    auditedAgentIds: Set<String>,
    auditedMgmtIds: Set<String>,
): ManualAuditUpdate? {
    val member = normalizeUserId(memberRaw)
    if (member.isEmpty()) return null
    return ManualAuditUpdate(
        status = "مدقق",
        source = "مدقق من صفحة البحث",
        auditedAgentIds = auditedAgentIds + member,
        auditedMgmtIds = auditedMgmtIds + member,
    )
}
