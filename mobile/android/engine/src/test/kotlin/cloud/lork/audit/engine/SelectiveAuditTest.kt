package cloud.lork.audit.engine

import org.junit.Assert.*
import org.junit.Test

class SelectiveAuditTest {
    private val management = listOf(listOf("رقم", "اسم"), listOf("1001", "الأول"), listOf("1001", "الأخير"))
    private val agent = listOf(listOf("رقم", "اسم", "", "راتب"), listOf("1001", "أ", "", "40"), listOf("1001", "ب", "", "10"))

    private fun preparation(
        managementRows: List<List<String>> = management,
        agentRows: List<List<String>> = agent,
        title: String = "الهدف",
        create: Boolean = true,
        discount: Double = 0.0,
    ) = prepareSelectiveAudit(
        "a@example.com", "1", "٠٠١٠٠١", managementRows, agentRows, AuditColumns(),
        "m", "الإدارة", "a", "الوكيل", title, create, discount, "#8b5cf6", "#facc15",
    )

    private fun sheets() = FakeSheets().apply {
        put("m", "الإدارة", management.map { it.toMutableList() })
        put("a", "الوكيل", agent.map { it.toMutableList() })
    }

    @Test
    fun advancedUsesFirstManagementMatchAndEveryAgentSalary() {
        val plan = preparation(discount = 10.0).plan!!
        assertEquals(listOf("1001", "الأول"), plan.managementRow)
        assertEquals("سحب وكيل راتبين", plan.statusLabel)
        assertEquals(50.0, plan.salaryBefore, 0.0)
        assertEquals(45.0, plan.salaryAfter, 0.0)
        assertEquals("1001", plan.memberId)
        assertEquals(50.0, preparation().plan!!.salaryAfter, 0.0)
    }

    @Test
    fun createsCopiesColorsAllMatchingRowsAndRetryDoesNotAppendTwice() {
        val gateway = sheets()
        val plan = preparation().plan!!
        val apply = SelectiveAuditApply(gateway, MemoryOpStore())
        assertTrue(apply.apply(plan, "a@example.com", "1").applied)
        assertEquals(listOf(listOf("1001", "الأول")), gateway.read("m", "الهدف"))
        assertEquals(1, gateway.appends)
        assertEquals(mapOf(0 to "#8b5cf6"), gateway.readBackgrounds("m", "الهدف"))
        assertEquals(mapOf(1 to "#8b5cf6", 2 to "#8b5cf6"), gateway.readBackgrounds("m", "الإدارة"))
        assertEquals(mapOf(1 to "#8b5cf6", 2 to "#8b5cf6"), gateway.readBackgrounds("a", "الوكيل"))
        val colorWrites = gateway.colors
        assertTrue(apply.apply(plan, "a@example.com", "1").applied)
        assertEquals(1, gateway.appends)
        assertEquals(colorWrites, gateway.colors)
    }

    @Test
    fun preexistingMemberIncludingFirstTargetRowIsNotCopiedOrRecolored() {
        val gateway = sheets().apply { put("m", "الهدف", listOf(mutableListOf("001001", "قديم"))) }
        val report = SelectiveAuditApply(gateway, MemoryOpStore()).apply(preparation().plan!!, "a@example.com", "1")
        assertFalse(report.applied)
        assertTrue(report.alreadyPresent)
        assertEquals(0, gateway.appends)
        assertEquals(0, gateway.colors)
    }

    @Test
    fun lostAppendCallbackIsReadBackBeforeAnyRepeat() {
        val gateway = sheets().apply { throwAfterAppend = true }
        val apply = SelectiveAuditApply(gateway, MemoryOpStore())
        val plan = preparation().plan!!
        assertFalse(apply.apply(plan, "a@example.com", "1").applied)
        assertEquals(1, gateway.appends)
        assertTrue(apply.apply(plan, "a@example.com", "1").applied)
        assertEquals(1, gateway.appends)
    }

    @Test
    fun interruptedColoringResumesWithoutRepeatingAppend() {
        val base = sheets()
        var fail = true
        val gateway = object : SheetGateway by base {
            override fun colorRows(spreadsheetId: String, title: String, zeroBasedRows: List<Int>, hex: String) {
                if (fail) { fail = false; throw SheetAccessException("NETWORK", "انقطع الاتصال") }
                base.colorRows(spreadsheetId, title, zeroBasedRows, hex)
            }
        }
        val apply = SelectiveAuditApply(gateway, MemoryOpStore())
        val plan = preparation().plan!!
        assertFalse(apply.apply(plan, "a@example.com", "1").applied)
        assertEquals(1, base.appends)
        assertTrue(apply.apply(plan, "a@example.com", "1").applied)
        assertEquals(1, base.appends)
        assertEquals(3, base.colors)
    }

    @Test
    fun newTargetRequiresCreateAndSourceChangeStopsBeforeCopy() {
        val gateway = sheets()
        val apply = SelectiveAuditApply(gateway, MemoryOpStore())
        assertFalse(apply.apply(preparation(create = false).plan!!, "a@example.com", "1").applied)
        assertFalse(gateway.listSheets("m").contains("الهدف"))
        gateway.grids["m" to "الإدارة"]!![1][1] = "تغيّر الاسم"
        val report = apply.apply(preparation().plan!!, "a@example.com", "1")
        assertFalse(report.applied)
        assertTrue(report.message.contains("تغيّر"))
        assertEquals(0, gateway.appends)
    }

    @Test
    fun accountAndCycleMismatchStopBeforeAnyWrite() {
        val gateway = sheets()
        val apply = SelectiveAuditApply(gateway, MemoryOpStore())
        val plan = preparation().plan!!
        assertFalse(apply.apply(plan, "other@example.com", "1").applied)
        assertFalse(apply.apply(plan, "a@example.com", "2").applied)
        assertEquals(0, gateway.appends)
        assertFalse(gateway.listSheets("m").contains("الهدف"))
    }

    @Test
    fun managementOnlyUsesManagementColorAndAgentOnlyCannotCopy() {
        val gateway = sheets()
        val plan = preparation(agentRows = listOf(agent.first())).plan!!
        assertEquals("سحب ادارة", plan.statusLabel)
        assertTrue(SelectiveAuditApply(gateway, MemoryOpStore()).apply(plan, "a@example.com", "1").applied)
        assertEquals("#facc15", gateway.readBackgrounds("m", "الهدف")[0])
        assertTrue(gateway.readBackgrounds("a", "الوكيل").isEmpty())
        assertNull(preparation(managementRows = listOf(management.first())).plan)
    }

    @Test
    fun invalidTitlesAndMissingMembersAreRejectedButExistingSourceIsNoOp() {
        assertNull(preparation(title = "").plan)
        assertNull(preparation(title = "bad/name").plan)
        assertNull(preparation(title = "a".repeat(101)).plan)
        assertNull(preparation(managementRows = listOf(management.first()), agentRows = listOf(agent.first())).plan)
        val gateway = sheets()
        val plan = preparation(title = "الإدارة").plan!!
        val report = SelectiveAuditApply(gateway, MemoryOpStore()).apply(plan, "a@example.com", "1")
        assertTrue(report.alreadyPresent)
        assertEquals(0, gateway.appends)
    }

    @Test
    fun operationReceiptTracksTargetAndRelevantSourceSnapshot() {
        val first = preparation().plan!!
        val otherTarget = preparation(title = "هدف آخر").plan!!
        val changedAgent = preparation(agentRows = agent + listOf(listOf("1001", "ج", "", "5"))).plan!!
        assertNotEquals(first.operationId, otherTarget.operationId)
        assertNotEquals(first.operationId, changedAgent.operationId)
        assertEquals(first.operationId, preparation().plan!!.operationId)
    }
}
