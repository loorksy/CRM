package cloud.lork.auditengine

import kotlin.test.Test
import kotlin.test.assertEquals

class XlsxWorkbookTest {
    @Test
    fun roundTripKeepsArabicCellsAndGaps() {
        val rows = listOf(
            listOf("رقم", "", "الاسم"),
            listOf("12", "", "أحمد"),
        )
        val book = XlsxWorkbook.Book(
            listOf(
                XlsxWorkbook.NamedSheet("معلومات المستخدمين", rows),
                XlsxWorkbook.NamedSheet("الإدارة", listOf(listOf("12"))),
                XlsxWorkbook.NamedSheet("الوكيل", listOf(listOf("A", "", "", "50"))),
            )
        )
        val again = XlsxWorkbook.read(XlsxWorkbook.write(book.sheets))
        assertEquals(listOf("معلومات المستخدمين", "الإدارة", "الوكيل"), again.sheets.map { it.name })
        assertEquals("أحمد", again.sheets[0].rows[1][2])
        assertEquals("", again.sheets[0].rows[1][1])
        assertEquals("50", again.sheets[2].rows[0][3])
    }
}
