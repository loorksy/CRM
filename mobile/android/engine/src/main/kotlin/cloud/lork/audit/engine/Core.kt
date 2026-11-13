package cloud.lork.audit.engine

import java.security.MessageDigest
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** مطابقة `normalizeForNumber` في services/payrollSearchService.js */
fun normalizeForNumber(value: Any?): String {
    if (value == null) return ""
    var out = value.toString()
        .replace(Regex("[\u200B-\u200D\u2060\uFEFF\u200E\u200F\u202A-\u202E]"), "")
        .trim()
    val arabic = "٠١٢٣٤٥٦٧٨٩"
    val persian = "۰۱۲۳۴۵۶۷۸۹"
    val western = "0123456789"
    for (i in 0 until 10) {
        out = out.replace(arabic[i].toString(), western[i].toString())
            .replace(persian[i].toString(), western[i].toString())
    }
    return out.replace(Regex("[,،\u066C\\s]"), "")
}

private val jsFloatPrefix = Regex("^[+-]?(?:Infinity|(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)")

/** parseFloat في جافاسكربت: يقرأ أطول بادئة رقمية، و«123abc» تصبح 123. */
fun jsParseFloat(raw: String): Double {
    // Parse the entire exponent together: multiplying by pow(10) changes rounding
    // and overflows even when JavaScript parseFloat returns a finite value.
    val prefix = jsFloatPrefix
        .find(raw.trim())?.value ?: return Double.NaN
    return prefix.toDoubleOrNull() ?: Double.NaN
}

fun jsMathRound(x: Double): Double {
    if (x.isNaN() || x.isInfinite()) return x
    return floor(x + 0.5)
}

/** `String(Math.floor(n))` للأرقام ضمن المجال الآمن. */
fun jsFloorToString(n: Double): String {
    val f = floor(n)
    if (!f.isFinite()) return f.toString()
    if (kotlin.math.abs(f) <= 9007199254740991.0) return f.toLong().toString()
    return f.toString()
}

/** مطابقة `normalizeUserId`. الأصفار البادئة تُحذف لأن parseFloat ثم floor هو سلوك المصدر، وليس قاعدة جديدة. */
fun normalizeUserId(value: Any?): String {
    val s = normalizeForNumber(value)
    if (s.isEmpty()) return ""
    val num = jsParseFloat(s)
    if (!num.isNaN() && num.isFinite()) return jsFloorToString(num)
    return s
}

fun columnLetterToIndex(letter: String?): Int? {
    if (letter.isNullOrEmpty()) return null
    val s = letter.trim().uppercase()
    var idx = 0
    for (ch in s) {
        val c = ch.code - 65
        if (c !in 0..25) return null
        idx = idx * 26 + (c + 1)
    }
    return idx - 1
}

fun columnIndexToLetter(index: Int): String {
    var s = ""
    var n = index + 1
    while (n > 0) {
        val r = (n - 1) % 26
        s = ('A'.code + r).toChar() + s
        n = (n - 1) / 26
    }
    return s.ifEmpty { "A" }
}

/**
 * فهرس عمود البحث في routes/search.js: الحرف الأول فقط (`charCodeAt(0) - 65`).
 * محرك التدقيق يستخدم [columnLetterToIndex]. الفرق موثّق ولا يُستخدم في الكتابة.
 */
fun legacySearchColumnIndex(letter: String?): Int {
    val s = (letter ?: "A").trim().uppercase()
    if (s.isEmpty()) return 0
    return s[0].code - 65
}

fun cellAt(row: List<String>, index: Int): String {
    if (index < 0 || index >= row.size) return ""
    return row[index]
}

fun isHeaderRowUserInfo(row: List<String>, col: Int): Boolean {
    val c = normalizeForNumber(cellAt(row, col))
    val n = jsParseFloat(c)
    return c.isEmpty() || n.isNaN() || !n.isFinite()
}

fun isHeaderRowCycle(row: List<String>, colIndex: Int?): Boolean {
    val col = colIndex ?: 0
    val first = normalizeForNumber(cellAt(row, col))
    if (first.isEmpty()) return true
    val n = jsParseFloat(first)
    return n.isNaN() || !n.isFinite()
}

data class AuditColumns(
    val userInfoUserIdCol: String = "C",
    val userInfoTitleCol: String = "D",
    val userInfoSalaryCol: String = "L",
    val userInfoStatusCol: String = "",
    val cycleMgmtUserIdCol: String = "A",
    val cycleAgentUserIdCol: String = "A",
    val cycleAgentSalaryCol: String = "D",
)

data class AuditResultRow(
    val userId: String,
    val title: String,
    val type: String,
    val managementRow: List<String>?,
    val mgmtSheetRowIndex: Int?,
    val agentSheetRowIndices: List<Int>,
    val salaryValue: Any,
    val statusLabel: String,
    val rowIndex: Int,
)

data class ColoredTarget(
    val managementRow: List<String>,
    val color: String,
    val type: String,
)

data class AuditSummary(val total: Int, val agent: Int, val management: Int, val notFound: Int)

data class AuditOutput(
    val results: List<AuditResultRow>,
    val byTitle: Map<String, List<ColoredTarget>>,
    val summary: AuditSummary,
    val dataStart: Int,
)

fun runPayrollAuditCore(
    managementRows: List<List<String>>,
    agentRows: List<List<String>>,
    userInfoRows: List<List<String>>,
    columns: AuditColumns = AuditColumns(),
    discountRatePct: Double = 0.0,
    agentColor: String? = null,
    managementColor: String? = null,
): AuditOutput {
    val colC = columnLetterToIndex(columns.userInfoUserIdCol) ?: 2
    val colD = columnLetterToIndex(columns.userInfoTitleCol) ?: 3
    val cycleMgmtCol = columnLetterToIndex(columns.cycleMgmtUserIdCol) ?: 0
    val cycleAgentCol = columnLetterToIndex(columns.cycleAgentUserIdCol) ?: 0
    val salaryIdx = columnLetterToIndex(columns.cycleAgentSalaryCol) ?: 3

    val dataStart = if (userInfoRows.isNotEmpty() && isHeaderRowUserInfo(userInfoRows[0], colC)) 1 else 0
    val dataRows = userInfoRows.drop(dataStart)

    val rate = if (discountRatePct.isNaN()) 0.0 else discountRatePct
    val discountMultiplier = max(0.0, min(1.0, 1.0 - rate / 100.0))

    val agentHeader = if (agentRows.isNotEmpty() && isHeaderRowCycle(agentRows[0], cycleAgentCol)) 1 else 0
    val agentData = if (agentHeader == 1) agentRows.drop(1) else agentRows
    val agentByUserId = linkedMapOf<String, MutableList<Triple<List<String>, Int, Int>>>()
    agentData.forEachIndexed { idx, row ->
        val id = normalizeUserId(cellAt(row, cycleAgentCol))
        if (id.isEmpty()) return@forEachIndexed
        agentByUserId.getOrPut(id) { mutableListOf() }
            .add(Triple(row, idx, agentHeader + idx))
    }

    val mgmtHeader = if (managementRows.isNotEmpty() && isHeaderRowCycle(managementRows[0], cycleMgmtCol)) 1 else 0
    val mgmtData = if (mgmtHeader == 1) managementRows.drop(1) else managementRows
    val mgmtByUserId = linkedMapOf<String, Pair<List<String>, Int>>()
    mgmtData.forEachIndexed { idx, row ->
        val id = normalizeUserId(cellAt(row, cycleMgmtCol))
        if (id.isNotEmpty()) mgmtByUserId[id] = row to (mgmtHeader + idx)
    }

    val agentColorVal = agentColor?.takeIf { it.isNotEmpty() } ?: "#3b82f6"
    val mgmtColorVal = managementColor?.takeIf { it.isNotEmpty() } ?: "#10b981"
    val results = mutableListOf<AuditResultRow>()
    val byTitle = linkedMapOf<String, MutableList<ColoredTarget>>()

    dataRows.forEachIndexed { i, row ->
        val uid = normalizeUserId(cellAt(row, colC))
        val titleRaw = cellAt(row, colD).trim()
        val title = titleRaw.ifEmpty { "صف_${i + 1}" }
        val agentMatches = if (uid.isEmpty()) emptyList() else agentByUserId[uid].orEmpty()
        val mgmtEntry = if (uid.isEmpty()) null else mgmtByUserId[uid]
        val mgmtRow = mgmtEntry?.first
        val inAgent = agentMatches.isNotEmpty()
        val inMgmt = mgmtRow != null

        var type = "غير موجود"
        var salaryValue: Any = ""
        var statusLabel = ""
        if (inAgent && inMgmt) {
            type = if (agentMatches.size > 1) "سحب وكالة - راتبيين" else "سحب وكالة"
            val raw = agentMatches.map { match ->
                val n = jsParseFloat(normalizeForNumber(cellAt(match.first, salaryIdx)))
                if (n.isNaN() || !n.isFinite()) 0.0 else n
            }
            val sumRaw = raw.sum()
            salaryValue = jsMathRound(sumRaw * discountMultiplier * 100.0) / 100.0
            statusLabel = if (agentMatches.size > 1) "سحب وكيل راتبين" else "سحب وكيل"
        } else if (inMgmt) {
            type = "سحب إدارة"
            salaryValue = 0
            statusLabel = "سحب ادارة"
        }

        results += AuditResultRow(
            userId = uid,
            title = title,
            type = type,
            managementRow = mgmtRow,
            mgmtSheetRowIndex = mgmtEntry?.second,
            agentSheetRowIndices = agentMatches.map { it.third },
            salaryValue = salaryValue,
            statusLabel = statusLabel,
            rowIndex = dataStart + i + 1,
        )

        if ((type.startsWith("سحب وكالة") || type == "سحب إدارة") && mgmtRow != null) {
            val bucket = byTitle.getOrPut(title) { mutableListOf() }
            val rowColor = if (type.startsWith("سحب وكالة")) agentColorVal else mgmtColorVal
            bucket += ColoredTarget(mgmtRow, rowColor, type)
        }
    }

    val summary = AuditSummary(
        total = results.size,
        agent = results.count { it.type.startsWith("سحب وكالة") },
        management = results.count { it.type == "سحب إدارة" },
        notFound = results.count { it.type == "غير موجود" },
    )
    return AuditOutput(results, byTitle, summary, dataStart)
}

data class DuplicateIds(val duplicateIds: List<String>, val duplicateRowNumbers: List<Int>)

fun findDuplicateUserInfoMemberIds(userInfoRows: List<List<String>>, userInfoUserIdCol: String?): DuplicateIds {
    val col = columnLetterToIndex(userInfoUserIdCol ?: "C") ?: 2
    val dataStart = if (userInfoRows.isNotEmpty() && isHeaderRowUserInfo(userInfoRows[0], col)) 1 else 0
    val seen = linkedMapOf<String, Int>()
    val duplicateIds = mutableListOf<String>()
    val duplicateRowNumbers = mutableListOf<Int>()
    for (i in dataStart until userInfoRows.size) {
        val id = normalizeUserId(cellAt(userInfoRows[i], col))
        if (id.isEmpty()) continue
        if (seen.containsKey(id)) {
            duplicateIds += id
            duplicateRowNumbers += i + 1
        } else {
            seen[id] = i + 1
        }
    }
    return DuplicateIds(duplicateIds.distinct(), duplicateRowNumbers)
}

/** نفس `hashUserInfoRows` في routes/sheet.js: SHA-256 لـ JSON.stringify لمصفوفة النصوص. */
fun hashUserInfoRows(rows: List<List<String>>): String {
    val json = jsonStringifyMatrix(rows)
    val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

fun jsonStringifyMatrix(rows: List<List<String>>): String {
    val sb = StringBuilder()
    sb.append('[')
    rows.forEachIndexed { i, row ->
        if (i > 0) sb.append(',')
        sb.append('[')
        row.forEachIndexed { j, cell ->
            if (j > 0) sb.append(',')
            sb.append(jsonStringifyString(cell))
        }
        sb.append(']')
    }
    sb.append(']')
    return sb.toString()
}

fun jsonStringifyString(value: String): String {
    val sb = StringBuilder()
    sb.append('"')
    for (ch in value) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
    }
    sb.append('"')
    return sb.toString()
}

fun safeSheetTitle(title: String?): String {
    val cleaned = (title ?: "Sheet").replace(Regex("[:*?/\\\\]"), "_").take(100)
    return cleaned.ifEmpty { "Sheet" }
}

fun assignSheetNames(titlesInOrder: List<String>): Map<String, String> {
    val used = linkedSetOf<String>()
    val map = linkedMapOf<String, String>()
    for (title in titlesInOrder) {
        var st = safeSheetTitle(title)
        while (used.contains(st)) st = st + "_" + used.size
        used.add(st)
        map[title] = st
    }
    return map
}

data class ManualStatus(val status: String, val source: String?)

fun classifyManualStatus(inMgmt: Boolean, inAgent: Boolean, mgmtColored: Boolean, agentColored: Boolean): ManualStatus {
    val hasAnyColor = mgmtColored || agentColored
    if (!inMgmt && !inAgent) return ManualStatus("غير مدقق", null)
    if (!hasAnyColor) return ManualStatus("غير مدقق", null)
    if (agentColored && !mgmtColored) return ManualStatus("مدقق", "مدقق وكيل يدوي")
    if (mgmtColored && !agentColored) return ManualStatus("مدقق", "مدقق ادارة يدوي")
    return ManualStatus("مدقق", "مدقق يدوي")
}

fun computeSalaryWithDiscount(rawSalaries: List<Any?>, discountRatePct: Double): Pair<Double, Double> {
    val nums = rawSalaries.map { v ->
        val n = jsParseFloat(normalizeForNumber(v))
        if (n.isNaN() || !n.isFinite()) 0.0 else n
    }
    val sum = nums.sum()
    val rate = if (discountRatePct.isNaN()) 0.0 else discountRatePct
    val multiplier = max(0.0, min(1.0, 1.0 - rate / 100.0))
    val after = jsMathRound(sum * multiplier * 100.0) / 100.0
    return sum to after
}

fun finalDisplayedAudit(
    hasCache: Boolean,
    cachedStatus: String?,
    cachedSource: String?,
    manual: ManualStatus,
): ManualStatus {
    val status = if (!hasCache || cachedStatus.isNullOrEmpty()) manual.status else cachedStatus
    val rawSource = if (!hasCache) null else cachedSource
    val source = if (rawSource.isNullOrEmpty()) manual.source else rawSource
    return ManualStatus(status, source)
}

data class SearchHit(
    val logicalStatus: String,
    val salaryBefore: Double,
    val salaryAfter: Double,
    val auditStatus: String,
    val auditSource: String?,
    val name: String?,
    val inMgmt: Boolean,
    val inAgent: Boolean,
)

/**
 * بحث دورة واحدة كما في routes/search.js: إسقاط الصف الأول دائماً،
 * ومطابقة ألوان البحث المفرد عبر `indexOf` (تطابق جزئي) وليس المساواة.
 */
fun searchUserInCycle(
    queryRaw: String,
    managementRows: List<List<String>>,
    agentRows: List<List<String>>,
    columns: AuditColumns = AuditColumns(),
    discountRatePct: Double = 0.0,
    auditedAgentIds: Set<String> = emptySet(),
    auditedMgmtIds: Set<String> = emptySet(),
    hasCache: Boolean = false,
    cachedStatus: String? = null,
    cachedSource: String? = null,
): SearchHit? {
    val queryId = normalizeUserId(queryRaw)
    if (queryId.isEmpty()) return null
    val mgmtIdx = legacySearchColumnIndex(columns.cycleMgmtUserIdCol)
    val agentIdx = legacySearchColumnIndex(columns.cycleAgentUserIdCol)
    val salaryIdx = columnLetterToIndex(columns.cycleAgentSalaryCol) ?: 3
    var inMgmt = false
    var inAgent = false
    val salaries = mutableListOf<String>()
    var name: String? = null
    managementRows.drop(1).forEach { row ->
        val id = normalizeUserId(cellAt(row, mgmtIdx))
        if (id.isNotEmpty() && id == queryId) {
            inMgmt = true
            if (name.isNullOrEmpty() && cellAt(row, 1).isNotEmpty()) name = cellAt(row, 1)
        }
    }
    agentRows.drop(1).forEach { row ->
        val id = normalizeUserId(cellAt(row, agentIdx))
        if (id.isNotEmpty() && id == queryId) {
            inAgent = true
            if (name.isNullOrEmpty() && cellAt(row, 1).isNotEmpty()) name = cellAt(row, 1)
            salaries += cellAt(row, salaryIdx)
        }
    }
    val salary = computeSalaryWithDiscount(salaries, discountRatePct)
    var logical = "غير موجود"
    if (inAgent && inMgmt) logical = if (salaries.size > 1) "سحب وكيل راتبين" else "سحب وكيل"
    else if (inMgmt) logical = "سحب ادارة"
    val agentColored = auditedAgentIds.any { it.isNotEmpty() && it.indexOf(queryId) >= 0 }
    val mgmtColored = auditedMgmtIds.any { it.isNotEmpty() && it.indexOf(queryId) >= 0 }
    val manual = classifyManualStatus(inMgmt, inAgent, mgmtColored, agentColored)
    val shown = finalDisplayedAudit(hasCache, cachedStatus, cachedSource, manual)
    return SearchHit(logical, salary.first, salary.second, shown.status, shown.source, name, inMgmt, inAgent)
}

data class ColumnGuess(
    val userId: String?,
    val title: String?,
    val salary: String?,
    val status: String?,
    val ambiguous: Boolean,
)

fun suggestColumns(headers: List<String>): ColumnGuess {
    fun matches(cell: String, keys: List<String>): Boolean {
        val n = cell.trim().lowercase()
        return keys.any { n.contains(it) }
    }
    val idKeys = listOf("رقم المستخدم", "معرف", "المعرف", "userid", "user id", "user_id")
    val titleKeys = listOf("اسم الورقة", "الورقة", "العنوان")
    val salaryKeys = listOf("الراتب", "salary")
    val statusKeys = listOf("الحالة", "status")
    fun letters(keys: List<String>): List<String> {
        val hits = mutableListOf<String>()
        headers.forEachIndexed { i, h -> if (matches(h, keys)) hits += columnIndexToLetter(i) }
        return hits
    }
    val ids = letters(idKeys)
    val titles = letters(titleKeys)
    val salaries = letters(salaryKeys)
    val statuses = letters(statusKeys)
    val ambiguous = ids.size > 1 || titles.size > 1 || salaries.size > 1 || statuses.size > 1
    return ColumnGuess(
        userId = ids.singleOrNull(),
        title = titles.singleOrNull(),
        salary = salaries.singleOrNull(),
        status = statuses.singleOrNull(),
        ambiguous = ambiguous,
    )
}

fun parseSpreadsheetId(input: String): String? {
    val trimmed = input.trim()
    val fromUrl = Regex("/spreadsheets/d/([a-zA-Z0-9-_]+)").find(trimmed)?.groupValues?.get(1)
    if (fromUrl != null) return fromUrl
    if (Regex("^[a-zA-Z0-9-_]{20,}$").matches(trimmed)) return trimmed
    return null
}

fun quoteSheet(title: String): String = "'" + title.replace("'", "''") + "'"
