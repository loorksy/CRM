package cloud.lork.auditsend.data

import cloud.lork.auditengine.XlsxWorkbook
import kotlinx.coroutines.flow.Flow

class Repository(private val db: AppDatabase) {
    fun observeCycles(): Flow<List<CycleEntity>> = db.cycles().observeAll()
    fun observeCycle(id: Long): Flow<CycleEntity?> = db.cycles().observe(id)
    fun observeSheets(id: Long): Flow<List<SheetEntity>> = db.sheets().observe(id)

    suspend fun createCycle(name: String): Long {
        val id = db.cycles().insert(CycleEntity(name = name.ifBlank { "دورة جديدة" }))
        SheetKind.all.forEach { kind ->
            db.sheets().upsert(
                SheetEntity(
                    cycleId = id,
                    kind = kind,
                    rowsJson = rowsToJson(blankRows(kind)),
                ),
            )
        }
        return id
    }

    suspend fun updateCycle(cycle: CycleEntity) = db.cycles().update(cycle)

    suspend fun deleteCycle(cycle: CycleEntity) = db.cycles().delete(cycle)

    suspend fun saveSheet(cycleId: Long, kind: String, rows: List<List<String>>, colors: Map<Int, String>) {
        db.sheets().upsert(
            SheetEntity(
                cycleId = cycleId,
                kind = kind,
                rowsJson = rowsToJson(rows),
                colorsJson = colorsToJson(colors),
            ),
        )
    }

    fun exportBook(sheets: Map<String, List<List<String>>>): ByteArray {
        val named = listOf(
            XlsxWorkbook.NamedSheet("معلومات المستخدمين", sheets[SheetKind.USER_INFO].orEmpty()),
            XlsxWorkbook.NamedSheet("الإدارة", sheets[SheetKind.MANAGEMENT].orEmpty()),
            XlsxWorkbook.NamedSheet("الوكيل", sheets[SheetKind.AGENT].orEmpty()),
        )
        return XlsxWorkbook.write(named)
    }
}

fun importWorkbook(bytes: ByteArray, preferredKind: String?): Map<String, List<List<String>>> {
    val book = XlsxWorkbook.read(bytes)
    if (book.sheets.isEmpty()) return emptyMap()
    if (book.sheets.size == 1 && preferredKind != null) {
        return mapOf(preferredKind to book.sheets[0].rows)
    }
    val assigned = HashMap<String, List<List<String>>>()
    val leftovers = ArrayList<List<List<String>>>()
    book.sheets.forEach { sheet ->
        val kind = classifySheetName(sheet.name)
        if (kind != null && !assigned.containsKey(kind)) assigned[kind] = sheet.rows
        else leftovers.add(sheet.rows)
    }
    SheetKind.all.forEach { kind ->
        if (!assigned.containsKey(kind) && leftovers.isNotEmpty()) {
            assigned[kind] = leftovers.removeAt(0)
        }
    }
    return assigned
}

private fun classifySheetName(name: String): String? {
    val n = name.lowercase()
    return when {
        n.contains("وكيل") || n.contains("agent") -> SheetKind.AGENT
        n.contains("ادار") || n.contains("إدار") || n.contains("mgmt") || n.contains("management") -> SheetKind.MANAGEMENT
        n.contains("مستخدم") || n.contains("user") || n.contains("info") -> SheetKind.USER_INFO
        else -> null
    }
}
