package cloud.lork.auditengine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PayrollAuditTest {
    @Test
    fun agentWithdrawalAppliesDiscountAndWritesSheet() {
        val user = listOf(
            listOf("", "", "رقم", "الاسم"),
            listOf("", "", "15", "أحمد"),
        )
        val applied = applyPayrollAudit(
            userInfo = user,
            management = listOf(listOf("15", "ملاحظة")),
            agent = listOf(listOf("15", "", "", "200")),
            discountRatePct = 10.0,
        )
        assertEquals(1, applied.summary.agent)
        assertEquals(0, applied.summary.notFound)
        assertEquals("180", applied.userInfo[1][11])
        assertEquals("سحب وكيل", applied.userInfo[1][12])
        assertEquals("#3b82f6", applied.managementColors[0])
        assertEquals("سحب وكالة", applied.results.single().type)
    }

    @Test
    fun twoAgentSalariesAndManagementOnlyAndMissing() {
        val user = listOf(
            listOf("", "", "رقم", "الاسم"),
            listOf("", "", "١٢", "أول"),
            listOf("", "", "8", "ثان"),
            listOf("", "", "3", "غائب"),
        )
        val applied = applyPayrollAudit(
            userInfo = user,
            management = listOf(
                listOf("رقم"),
                listOf("12"),
                listOf("8"),
            ),
            agent = listOf(
                listOf("12", "", "", "100"),
                listOf("12", "", "", "1,000"),
            ),
            discountRatePct = 0.0,
        )
        assertEquals("سحب وكالة - راتبيين", applied.results[0].type)
        assertEquals("1100", applied.userInfo[1][11])
        assertEquals("سحب وكيل راتبين", applied.userInfo[1][12])
        assertEquals("سحب إدارة", applied.results[1].type)
        assertEquals("0", applied.userInfo[2][11])
        assertEquals("سحب ادارة", applied.userInfo[2][12])
        assertEquals("#10b981", applied.managementColors[2])
        assertEquals("غير موجود", applied.results[2].type)
        assertEquals("غير موجود", applied.userInfo[3][12])
        assertEquals(1, applied.summary.agent)
        assertEquals(1, applied.summary.management)
        assertEquals(1, applied.summary.notFound)
    }

    @Test
    fun duplicateMemberIdsUseSheetRowNumbers() {
        val (ids, rows) = findDuplicateUserInfoMemberIds(
            listOf(
                listOf("", "", "رقم"),
                listOf("", "", "7"),
                listOf("", "", "7"),
                listOf("", "", "7"),
            ),
            "C",
        )
        assertEquals(listOf("7"), ids)
        assertEquals(listOf(3, 4), rows)
    }

    @Test
    fun columnLettersMatchSpreadsheetIndexes() {
        assertEquals(0, columnLetterToIndex("A"))
        assertEquals(2, columnLetterToIndex("c"))
        assertEquals(25, columnLetterToIndex("Z"))
        assertEquals(26, columnLetterToIndex("AA"))
        assertEquals(null, columnLetterToIndex(""))
        assertEquals("L", indexToColumnLetter(11))
    }
}
