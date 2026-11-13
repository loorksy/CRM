package cloud.lork.audit.engine

import org.junit.Assert.*
import org.junit.Test

class SourcePickerTest {
    private val management = SourcePickerContext("account@example.invalid", 1, "إدارة")
    private val fileA = DriveFile("file-a", "الإدارة")
    private val fileB = DriveFile("file-b", "معلومات المستخدمين")

    @Test
    fun all713FilesRemainSearchableAndSelectable() {
        val picker = SourcePicker()
        picker.open(management)
        val request = picker.requestFiles()
        val files = (1..713).map { DriveFile("id-$it", "ملف $it") }
        picker.filesLoaded(request, DriveListResult(files, null, "تم العثور على 713 جدولاً"))
        assertEquals(713, picker.state.filteredFiles.size)
        picker.setQuery("ملف 713")
        assertEquals(listOf(files.last()), picker.state.filteredFiles)
        val sheets = picker.openFile(picker.state.filteredFiles.single())
        picker.sheetsLoaded(sheets, listOf("رواتب"))
        val selection = picker.chooseSheet("رواتب")!!
        assertEquals(files.last(), selection.file)
        assertEquals(management, selection.context)
        assertFalse(picker.state.isOpen)
    }

    @Test
    fun clickingAFileImmediatelyShowsSheetStepAndLoadingState() {
        val picker = SourcePicker()
        picker.open(management, listOf(fileA))
        val request = picker.openFile(fileA)
        assertEquals(SourcePickerPage.SHEETS, picker.state.page)
        assertEquals(fileA, picker.state.selectedFile)
        assertTrue(picker.state.loading)
        assertNull(picker.chooseSheet("Tab"))
        picker.sheetsLoaded(request, listOf("Tab", "Tab", "Other"))
        assertEquals(listOf("Tab", "Other"), picker.state.sheetTitles)
        assertFalse(picker.state.loading)
        assertEquals("Tab", picker.chooseSheet("Tab")!!.sheetTitle)
        assertNull(picker.chooseSheet("Tab"))
    }

    @Test
    fun switchingFilesClearsOldTabsAndRejectsLateSuccessOrError() {
        val picker = SourcePicker()
        picker.open(management)
        val old = picker.openFile(fileA)
        picker.sheetsLoaded(old, listOf("Old tab"))
        val current = picker.openFile(fileB)
        assertTrue(picker.state.sheetTitles.isEmpty())
        assertNull(picker.chooseSheet("Old tab"))
        picker.sheetsLoaded(old, listOf("Late old tab"))
        picker.failed(old, "403", "Late error")
        assertTrue(picker.state.loading)
        assertEquals("", picker.state.errorCode)
        picker.sheetsLoaded(current, listOf("Current tab"))
        assertNull(picker.chooseSheet("Old tab"))
        assertEquals(fileB, picker.chooseSheet("Current tab")!!.file)
    }

    @Test
    fun metadataFailureRetainsCodeAndMessageInsteadOfPretendingThereAreNoFiles() {
        val picker = SourcePicker()
        picker.open(management, listOf(fileA))
        val request = picker.openFile(fileA)
        picker.failed(request, "403:SERVICE_DISABLED", "فعّل Google Sheets API ثم أعد المحاولة.")
        assertEquals("403:SERVICE_DISABLED", picker.state.errorCode)
        assertEquals("فعّل Google Sheets API ثم أعد المحاولة.", picker.state.errorMessage)
        assertEquals(fileA, picker.state.selectedFile)
        assertEquals(listOf(fileA), picker.state.files)
        assertFalse(picker.state.loading)
        assertNull(picker.chooseSheet("Any tab"))
        val retry = picker.openFile(fileA)
        assertEquals("", picker.state.errorMessage)
        picker.sheetsLoaded(retry, listOf("Recovered"))
        assertNotNull(picker.chooseSheet("Recovered"))
    }

    @Test
    fun canceledAndDifferentRoleCycleOrAccountRejectPendingResults() {
        val contexts = listOf(
            management,
            management.copy(role = "وكيل"),
            management.copy(cycleId = 2),
            management.copy(accountEmail = "another@example.invalid"),
        )
        val picker = SourcePicker()
        for (context in contexts) {
            picker.open(management)
            val stale = picker.openFile(fileA)
            picker.close()
            picker.open(context)
            picker.sheetsLoaded(stale, listOf("Stale"))
            picker.failed(stale, "401", "Stale authorization error")
            assertEquals(context, picker.state.context)
            assertEquals(SourcePickerPage.FILES, picker.state.page)
            assertTrue(picker.state.sheetTitles.isEmpty())
            assertEquals("", picker.state.errorMessage)
            assertNull(picker.chooseSheet("Stale"))
        }
    }

    @Test
    fun goingBackCancelsMetadataAndRestoresTheSameFileSearch() {
        val picker = SourcePicker()
        picker.open(management, listOf(fileA, fileB))
        picker.setQuery("معلومات")
        val request = picker.openFile(fileB)
        picker.showFiles()
        picker.sheetsLoaded(request, listOf("Should not appear"))
        assertEquals(SourcePickerPage.FILES, picker.state.page)
        assertEquals(listOf(fileB), picker.state.filteredFiles)
        assertNull(picker.state.selectedFile)
        assertFalse(picker.state.loading)
    }

    @Test
    fun oldFileListResponsesCannotReplaceNewerFileOrTabRequests() {
        val picker = SourcePicker()
        picker.open(management)
        val oldList = picker.requestFiles()
        val current = picker.openFile(fileA)
        picker.filesLoaded(oldList, DriveListResult(listOf(fileB), null, "Late list"))
        assertEquals(SourcePickerPage.SHEETS, picker.state.page)
        assertEquals(fileA, picker.state.selectedFile)
        assertTrue(picker.state.loading)
        picker.sheetsLoaded(current, listOf("Selected"))
        assertEquals("Selected", picker.chooseSheet("Selected")!!.sheetTitle)
    }

    @Test
    fun emptySheetListAndDriveErrorsRemainVisibleAndDoNotPermitSelection() {
        val picker = SourcePicker()
        picker.open(management)
        val request = picker.requestFiles()
        picker.filesLoaded(request, DriveListResult(emptyList(), "401", "انتهت صلاحية التفويض."))
        assertEquals("401", picker.state.errorCode)
        assertEquals("انتهت صلاحية التفويض.", picker.state.errorMessage)
        val sheetRequest = picker.openFile(fileA)
        picker.sheetsLoaded(sheetRequest, emptyList())
        assertEquals("NO_SHEETS", picker.state.errorCode)
        assertTrue(picker.state.errorMessage.isNotBlank())
        assertNull(picker.chooseSheet(""))
    }

    @Test
    fun duplicateCallbackDoesNotOverwriteACompletedRequest() {
        val picker = SourcePicker()
        picker.open(management)
        val request = picker.openFile(fileA)
        assertTrue(picker.sheetsLoaded(request, listOf("First result")))
        assertFalse(picker.sheetsLoaded(request, listOf("Duplicate result")))
        assertFalse(picker.failed(request, "network", "Duplicate failure"))
        assertEquals(listOf("First result"), picker.state.sheetTitles)
        assertEquals("", picker.state.errorMessage)
    }

    @Test
    fun pastedFileCanBeOpenedWithoutBeingInTheDriveListing() {
        val picker = SourcePicker()
        picker.open(management, listOf(fileA))
        val pasted = DriveFile("shared-pasted-id", "ملف ملصوق")
        val request = picker.openFile(pasted)
        picker.sheetsLoaded(request, listOf("Shared tab"))
        assertEquals(pasted, picker.chooseSheet("Shared tab")!!.file)
    }
}
