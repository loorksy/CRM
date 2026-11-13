package cloud.lork.audit.data

import cloud.lork.audit.engine.DriveFile
import cloud.lork.audit.engine.SourcePickerContext
import cloud.lork.audit.engine.SourceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceBindingTest {
    private val original = Sources("management", "Main", "agent", "Salaries", "users", "Members")

    @Test
    fun existingCyclesWithoutFileNamesStillDecode() {
        val decoded = decodeSources("""{"mgmtSs":"management","mgmtSheet":"Main","agentSs":"agent","agentSheet":"Salaries","userSs":"users","userSheet":"Members"}""")
        assertEquals(original, decoded)
        assertTrue(decoded.complete)
        assertEquals("", decoded.mgmtFileName)
    }

    @Test
    fun eachRoleKeepsItsOwnFileAndSheetAfterSavingAndReloading() {
        var selected = original
        val roles = listOf("إدارة", "وكيل", "مستخدمون")
        roles.forEachIndexed { index, role ->
            selected = selected.withSelection(SourceSelection(SourcePickerContext("account", 1, role),
                DriveFile("file-$index", "اسم الملف $index"), "ورقة $index"))
            selected = decodeSources(encodeSources(selected))
        }
        assertEquals("file-0", selected.mgmtSs)
        assertEquals("ورقة 0", selected.mgmtSheet)
        assertEquals("اسم الملف 0", selected.mgmtFileName)
        assertEquals("file-1", selected.agentSs)
        assertEquals("ورقة 1", selected.agentSheet)
        assertEquals("اسم الملف 1", selected.agentFileName)
        assertEquals("file-2", selected.userSs)
        assertEquals("ورقة 2", selected.userSheet)
        assertEquals("اسم الملف 2", selected.userFileName)
    }

    @Test
    fun displayNameChangesDoNotInvalidateSnapshotButFileOrTabChangesDo() {
        assertEquals(fingerprint(original), fingerprint(original.copy(mgmtFileName = "Renamed spreadsheet")))
        assertNotEquals(fingerprint(original), fingerprint(original.copy(mgmtSs = "another-file")))
        assertNotEquals(fingerprint(original), fingerprint(original.copy(mgmtSheet = "another-tab")))
    }
}
