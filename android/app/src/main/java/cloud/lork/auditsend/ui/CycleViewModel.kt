package cloud.lork.auditsend.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.lork.auditengine.AuditColumns
import cloud.lork.auditengine.applyPayrollAudit
import cloud.lork.auditsend.AuditApplication
import cloud.lork.auditsend.data.CycleEntity
import cloud.lork.auditsend.data.Repository
import cloud.lork.auditsend.data.SheetKind
import cloud.lork.auditsend.data.blankRows
import cloud.lork.auditsend.data.importWorkbook
import cloud.lork.auditsend.data.jsonToColors
import cloud.lork.auditsend.data.jsonToRows
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SheetBundle(
    val rows: List<List<String>>,
    val colors: Map<Int, String>,
)

data class ReadyCycle(
    val cycle: CycleEntity,
    val sheets: Map<String, SheetBundle>,
    val message: String = "",
)

class CycleListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository((app as AuditApplication).database)
    val cycles = repo.observeCycles().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(name: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repo.createCycle(name)
            onCreated(id)
        }
    }

    fun delete(cycle: CycleEntity) {
        viewModelScope.launch { repo.deleteCycle(cycle) }
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = CycleListViewModel(app) as T
        }
    }
}

class CycleViewModel(app: Application, private val cycleId: Long) : AndroidViewModel(app) {
    private val repo = Repository((app as AuditApplication).database)
    private val message = MutableStateFlow("")

    val state: StateFlow<ReadyCycle?> = combine(
        repo.observeCycle(cycleId),
        repo.observeSheets(cycleId),
        message,
    ) { cycle, sheets, note ->
        if (cycle == null) return@combine null
        val mapped = SheetKind.all.associateWith { kind ->
            val entity = sheets.firstOrNull { it.kind == kind }
            SheetBundle(
                rows = entity?.let { jsonToRows(it.rowsJson) } ?: blankRows(kind),
                colors = entity?.let { jsonToColors(it.colorsJson) } ?: emptyMap(),
            )
        }
        ReadyCycle(cycle, mapped, note)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun editCell(kind: String, row: Int, col: Int, value: String) {
        val current = state.value ?: return
        val sheet = current.sheets[kind] ?: return
        val rows = sheet.rows.map { it.toMutableList() }.toMutableList()
        while (rows.size <= row) rows.add(mutableListOf())
        val line = rows[row]
        while (line.size <= col) line.add("")
        line[col] = value
        save(kind, rows.map { it.toList() }, sheet.colors)
    }

    fun addRow(kind: String) {
        val current = state.value ?: return
        val sheet = current.sheets[kind] ?: return
        val width = sheet.rows.maxOfOrNull { it.size } ?: 1
        val rows = sheet.rows + listOf(List(width.coerceAtLeast(1)) { "" })
        save(kind, rows, sheet.colors)
    }

    fun deleteRow(kind: String, index: Int) {
        val current = state.value ?: return
        val sheet = current.sheets[kind] ?: return
        if (sheet.rows.size <= 1 || index !in sheet.rows.indices) return
        val rows = sheet.rows.filterIndexed { i, _ -> i != index }
        val colors = sheet.colors.mapNotNull { (row, color) ->
            when {
                row == index -> null
                row > index -> row - 1 to color
                else -> row to color
            }
        }.toMap()
        save(kind, rows, colors)
    }

    fun saveSettings(cycle: CycleEntity) {
        viewModelScope.launch { repo.updateCycle(cycle) }
    }

    fun setAuditOpen(open: Boolean) {
        val cycle = state.value?.cycle ?: return
        viewModelScope.launch { repo.updateCycle(cycle.copy(auditOpen = open)) }
    }

    fun runAudit() {
        val current = state.value ?: return
        if (!current.cycle.auditOpen) {
            message.value = "جلسة التدقيق مغلقة"
            return
        }
        val cycle = current.cycle
        val user = current.sheets.getValue(SheetKind.USER_INFO).rows
        val management = current.sheets.getValue(SheetKind.MANAGEMENT)
        val agent = current.sheets.getValue(SheetKind.AGENT).rows
        val applied = applyPayrollAudit(
            userInfo = user,
            management = management.rows,
            agent = agent,
            columns = AuditColumns(
                userInfoUserIdCol = cycle.userInfoUserIdCol,
                userInfoTitleCol = cycle.userInfoTitleCol,
                userInfoSalaryCol = cycle.userInfoSalaryCol,
                cycleMgmtUserIdCol = cycle.mgmtUserIdCol,
                cycleAgentUserIdCol = cycle.agentUserIdCol,
                cycleAgentSalaryCol = cycle.agentSalaryCol,
            ),
            statusCol = cycle.userInfoStatusCol,
            discountRatePct = cycle.discountRatePct,
            agentColor = cycle.agentColor,
            managementColor = cycle.managementColor,
        )
        val summary = buildString {
            append("الكل ${applied.summary.total}")
            append(" · سحب وكالة ${applied.summary.agent}")
            append(" · سحب إدارة ${applied.summary.management}")
            append(" · غير موجود ${applied.summary.notFound}")
            if (applied.duplicateIds.isNotEmpty()) {
                append(" · مكرر: ")
                append(applied.duplicateIds.joinToString("، "))
                append(" في الصفوف ")
                append(applied.duplicateRowNumbers.joinToString("، "))
            }
        }
        viewModelScope.launch {
            repo.saveSheet(cycleId, SheetKind.USER_INFO, applied.userInfo, emptyMap())
            repo.saveSheet(cycleId, SheetKind.MANAGEMENT, management.rows, applied.managementColors)
            repo.updateCycle(cycle.copy(lastSummary = summary))
            message.value = summary
        }
    }

    fun import(bytes: ByteArray, preferredKind: String) {
        val mapped = importWorkbook(bytes, preferredKind)
        if (mapped.isEmpty()) {
            message.value = "الملف لا يحتوي أوراقاً"
            return
        }
        viewModelScope.launch {
            mapped.forEach { (kind, rows) ->
                val colors = if (kind == SheetKind.MANAGEMENT) emptyMap() else state.value?.sheets?.get(kind)?.colors.orEmpty()
                repo.saveSheet(cycleId, kind, rows.ifEmpty { blankRows(kind) }, if (kind == SheetKind.MANAGEMENT) emptyMap() else colors)
            }
            message.value = "تم استيراد ${mapped.size} ورقة"
        }
    }

    fun exportBytes(): ByteArray? {
        val sheets = state.value?.sheets ?: return null
        return repo.exportBook(sheets.mapValues { it.value.rows })
    }

    private fun save(kind: String, rows: List<List<String>>, colors: Map<Int, String>) {
        viewModelScope.launch { repo.saveSheet(cycleId, kind, rows, colors) }
    }

    companion object {
        fun factory(app: Application, cycleId: Long): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return CycleViewModel(app, cycleId) as T
                }
            }
    }
}
