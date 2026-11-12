package cloud.lork.auditengine

data class AuditColumns(
    val userInfoUserIdCol: String = "C",
    val userInfoTitleCol: String = "D",
    val userInfoSalaryCol: String = "L",
    val cycleMgmtUserIdCol: String = "A",
    val cycleAgentUserIdCol: String = "A",
    val cycleAgentSalaryCol: String = "D",
)

data class AuditResultRow(
    val userId: String,
    val title: String,
    val type: String,
    val mgmtSheetRowIndex: Int?,
    val salaryValue: Double?,
    val statusLabel: String,
    val rowIndex: Int,
)

data class AuditSummary(
    val total: Int,
    val agent: Int,
    val management: Int,
    val notFound: Int,
)

data class AppliedAudit(
    val userInfo: List<List<String>>,
    val managementColors: Map<Int, String>,
    val summary: AuditSummary,
    val duplicateIds: List<String>,
    val duplicateRowNumbers: List<Int>,
    val results: List<AuditResultRow>,
)

fun findDuplicateUserInfoMemberIds(
    userInfoRows: List<List<String>>,
    userInfoUserIdCol: String?,
): Pair<List<String>, List<Int>> {
    val colC = columnLetterToIndex(userInfoUserIdCol ?: "C") ?: 2
    val dataStart = if (userInfoRows.isNotEmpty() && isHeaderRowUserInfo(userInfoRows[0], colC)) 1 else 0
    val seen = HashMap<String, Int>()
    val duplicateIds = mutableListOf<String>()
    val duplicateRowNumbers = mutableListOf<Int>()
    for (i in dataStart until userInfoRows.size) {
        val id = normalizeUserId(userInfoRows[i].getOrElse(colC) { "" })
        if (id.isEmpty()) continue
        if (seen.containsKey(id)) {
            duplicateIds.add(id)
            duplicateRowNumbers.add(i + 1)
        } else {
            seen[id] = i + 1
        }
    }
    return duplicateIds.distinct() to duplicateRowNumbers
}

fun applyPayrollAudit(
    userInfo: List<List<String>>,
    management: List<List<String>>,
    agent: List<List<String>>,
    columns: AuditColumns = AuditColumns(),
    statusCol: String = "M",
    discountRatePct: Double = 0.0,
    agentColor: String? = null,
    managementColor: String? = null,
): AppliedAudit {
    val colC = columnLetterToIndex(columns.userInfoUserIdCol) ?: 2
    val colD = columnLetterToIndex(columns.userInfoTitleCol) ?: 3
    val colSalary = columnLetterToIndex(columns.userInfoSalaryCol) ?: 11
    val colStatus = columnLetterToIndex(statusCol) ?: 12
    val cycleMgmtCol = columnLetterToIndex(columns.cycleMgmtUserIdCol) ?: 0
    val cycleAgentCol = columnLetterToIndex(columns.cycleAgentUserIdCol) ?: 0
    val cycleAgentSalaryColIdx = columnLetterToIndex(columns.cycleAgentSalaryCol) ?: 3

    val dataStart = if (userInfo.isNotEmpty() && isHeaderRowUserInfo(userInfo[0], colC)) 1 else 0
    val rate = if (discountRatePct.isNaN()) 0.0 else discountRatePct
    val discountMultiplier = (1.0 - rate / 100.0).coerceIn(0.0, 1.0)

    val agentHeader = if (agent.isNotEmpty() && isHeaderRowCycle(agent[0], cycleAgentCol)) 1 else 0
    val agentData = if (agentHeader == 1) agent.drop(1) else agent
    val agentByUserId = HashMap<String, MutableList<Pair<List<String>, Int>>>()
    agentData.forEachIndexed { idx, row ->
        val id = normalizeUserId(row.getOrElse(cycleAgentCol) { "" })
        if (id.isEmpty()) return@forEachIndexed
        agentByUserId.getOrPut(id) { mutableListOf() }
            .add(row to (agentHeader + idx))
    }

    val mgmtHeader = if (management.isNotEmpty() && isHeaderRowCycle(management[0], cycleMgmtCol)) 1 else 0
    val mgmtData = if (mgmtHeader == 1) management.drop(1) else management
    val mgmtByUserId = HashMap<String, Pair<List<String>, Int>>()
    mgmtData.forEachIndexed { idx, row ->
        val id = normalizeUserId(row.getOrElse(cycleMgmtCol) { "" })
        if (id.isNotEmpty()) mgmtByUserId[id] = row to (mgmtHeader + idx)
    }

    val agentColorVal = agentColor?.takeIf { it.isNotBlank() } ?: "#3b82f6"
    val mgmtColorVal = managementColor?.takeIf { it.isNotBlank() } ?: "#10b981"

    val results = mutableListOf<AuditResultRow>()
    for (i in 0 until (userInfo.size - dataStart)) {
        val row = userInfo[dataStart + i]
        val uid = normalizeUserId(row.getOrElse(colC) { "" })
        val title = row.getOrElse(colD) { "" }.trim().ifEmpty { "صف_${i + 1}" }
        val agentMatches = if (uid.isEmpty()) emptyList() else agentByUserId[uid].orEmpty()
        val mgmtEntry = if (uid.isEmpty()) null else mgmtByUserId[uid]
        val inAgent = agentMatches.isNotEmpty()
        val inMgmt = mgmtEntry != null

        var type = "غير موجود"
        var salaryValue: Double? = null
        var statusLabel = ""
        if (inAgent && inMgmt) {
            type = if (agentMatches.size > 1) "سحب وكالة - راتبيين" else "سحب وكالة"
            val sumRaw = agentMatches.sumOf { (agentRow, _) ->
                val n = jsParseFloat(normalizeForNumber(agentRow.getOrElse(cycleAgentSalaryColIdx) { "" }))
                if (n == null || !n.isFinite()) 0.0 else n
            }
            salaryValue = kotlin.math.round(sumRaw * discountMultiplier * 100.0) / 100.0
            statusLabel = if (agentMatches.size > 1) "سحب وكيل راتبين" else "سحب وكيل"
        } else if (inMgmt) {
            type = "سحب إدارة"
            salaryValue = 0.0
            statusLabel = "سحب ادارة"
        }

        results.add(
            AuditResultRow(
                userId = uid,
                title = title,
                type = type,
                mgmtSheetRowIndex = mgmtEntry?.second,
                salaryValue = salaryValue,
                statusLabel = statusLabel,
                rowIndex = dataStart + i + 1,
            )
        )
    }

    val edited = userInfo.map { it.toMutableList() }
    val colors = HashMap<Int, String>()
    for (result in results) {
        val target = edited.getOrNull(result.rowIndex - 1) ?: continue
        if (result.salaryValue != null) {
            ensureWidth(target, colSalary)
            target[colSalary] = formatSalary(result.salaryValue)
        }
        val statusText = result.statusLabel.ifEmpty { if (result.type == "غير موجود") result.type else "" }
        if (statusText.isNotEmpty()) {
            ensureWidth(target, colStatus)
            target[colStatus] = statusText
        }
        val mgmtIndex = result.mgmtSheetRowIndex
        if (mgmtIndex != null && (result.type.startsWith("سحب وكالة") || result.type == "سحب إدارة")) {
            colors[mgmtIndex] = if (result.type.startsWith("سحب وكالة")) agentColorVal else mgmtColorVal
        }
    }

    val summary = AuditSummary(
        total = results.size,
        agent = results.count { it.type.startsWith("سحب وكالة") },
        management = results.count { it.type == "سحب إدارة" },
        notFound = results.count { it.type == "غير موجود" },
    )
    val (duplicateIds, duplicateRows) = findDuplicateUserInfoMemberIds(userInfo, columns.userInfoUserIdCol)
    return AppliedAudit(
        userInfo = edited.map { it.toList() },
        managementColors = colors,
        summary = summary,
        duplicateIds = duplicateIds,
        duplicateRowNumbers = duplicateRows,
        results = results,
    )
}

private fun ensureWidth(row: MutableList<String>, index: Int) {
    while (row.size <= index) row.add("")
}

fun formatSalary(value: Double): String {
    val rounded = kotlin.math.round(value * 100.0) / 100.0
    val asLong = rounded.toLong()
    if (rounded == asLong.toDouble()) return asLong.toString()
    return String.format(java.util.Locale.US, "%.2f", rounded).trimEnd('0').trimEnd('.')
}
