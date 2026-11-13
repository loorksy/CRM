package cloud.lork.audit.engine

/** The destination of a selection is captured when its picker is opened. */
data class SourcePickerContext(val accountEmail: String, val cycleId: Long, val role: String)

enum class SourcePickerPage { FILES, SHEETS }

data class SourcePickerState(
    val isOpen: Boolean = false,
    val context: SourcePickerContext? = null,
    val page: SourcePickerPage = SourcePickerPage.FILES,
    val files: List<DriveFile> = emptyList(),
    val query: String = "",
    val selectedFile: DriveFile? = null,
    val sheetTitles: List<String> = emptyList(),
    val loading: Boolean = false,
    val errorMessage: String = "",
    val errorCode: String = "",
    val message: String = "",
) {
    /** Search the entire downloaded list; rendering/pagination must not discard files. */
    val filteredFiles: List<DriveFile>
        get() {
            val needle = query.trim()
            return if (needle.isEmpty()) files else files.filter {
                it.name.contains(needle, ignoreCase = true) || it.id.contains(needle, ignoreCase = true)
            }
        }
}

data class SourcePickerRequest(
    val requestId: Long,
    val context: SourcePickerContext,
    val page: SourcePickerPage,
    val fileId: String? = null,
)

data class SourceSelection(val context: SourcePickerContext, val file: DriveFile, val sheetTitle: String)

/**
 * Pure selection flow. The application performs I/O and returns results with the
 * ticket issued here; obsolete results cannot bind a sheet to a different file,
 * account, financial cycle, or source role.
 */
class SourcePicker {
    var state: SourcePickerState = SourcePickerState()
        private set
    private var generation = 0L
    private var pending: SourcePickerRequest? = null

    fun open(context: SourcePickerContext, cachedFiles: List<DriveFile> = emptyList()) {
        invalidate()
        state = SourcePickerState(
            isOpen = true,
            context = context,
            files = cachedFiles.toList(),
            message = fileCaption(cachedFiles.size),
        )
    }

    fun requestFiles(): SourcePickerRequest {
        val context = requireContext()
        val ticket = SourcePickerRequest(++generation, context, SourcePickerPage.FILES)
        pending = ticket
        state = state.copy(
            page = SourcePickerPage.FILES, selectedFile = null, sheetTitles = emptyList(),
            loading = true, errorMessage = "", errorCode = "", message = "جارٍ تحميل جداول Google…",
        )
        return ticket
    }

    fun filesLoaded(request: SourcePickerRequest, result: DriveListResult): Boolean {
        if (!accepts(request) || request.page != SourcePickerPage.FILES) return false
        if (result.errorCode != null) {
            return failed(request, result.errorCode, result.message)
        }
        pending = null
        state = state.copy(
            files = result.files.toList(), loading = false, errorMessage = "", errorCode = "",
            message = result.message,
        )
        return true
    }

    fun openFile(file: DriveFile): SourcePickerRequest {
        val context = requireContext()
        require(file.id.isNotBlank()) { "معرّف الملف مطلوب." }
        val ticket = SourcePickerRequest(++generation, context, SourcePickerPage.SHEETS, file.id)
        pending = ticket
        state = state.copy(
            page = SourcePickerPage.SHEETS, selectedFile = file, sheetTitles = emptyList(),
            loading = true, errorMessage = "", errorCode = "", message = "جارٍ تحميل أوراق «${file.name.ifBlank { file.id }}»…",
        )
        return ticket
    }

    fun sheetsLoaded(request: SourcePickerRequest, titles: List<String>): Boolean {
        if (!accepts(request) || request.page != SourcePickerPage.SHEETS) return false
        pending = null
        val sheets = titles.filter { it.isNotEmpty() }.distinct()
        state = state.copy(
            sheetTitles = sheets, loading = false,
            errorCode = if (sheets.isEmpty()) "NO_SHEETS" else "",
            errorMessage = if (sheets.isEmpty()) "لا توجد أوراق متاحة في هذا الملف. اختر ملفاً آخر أو أعد المحاولة." else "",
            message = if (sheets.isEmpty()) "" else "اختر الورقة المطلوبة من هذا الملف.",
        )
        return true
    }

    fun failed(request: SourcePickerRequest, code: String, message: String): Boolean {
        if (!accepts(request)) return false
        pending = null
        state = state.copy(
            loading = false,
            errorCode = code,
            errorMessage = message.ifBlank { "تعذر تحميل البيانات. أعد المحاولة." },
            message = "",
        )
        return true
    }

    fun showFiles() {
        if (!state.isOpen) return
        invalidate()
        state = state.copy(
            page = SourcePickerPage.FILES, selectedFile = null, sheetTitles = emptyList(),
            loading = false, errorMessage = "", errorCode = "", message = fileCaption(state.files.size),
        )
    }

    fun close() {
        invalidate()
        state = state.copy(isOpen = false, loading = false)
    }

    fun setQuery(query: String) {
        if (state.isOpen && state.page == SourcePickerPage.FILES) state = state.copy(query = query)
    }

    fun chooseSheet(title: String): SourceSelection? {
        val context = state.context ?: return null
        val file = state.selectedFile ?: return null
        if (!state.isOpen || state.loading || state.page != SourcePickerPage.SHEETS || title !in state.sheetTitles) return null
        val selection = SourceSelection(context, file, title)
        close()
        return selection
    }

    private fun requireContext(): SourcePickerContext {
        check(state.isOpen) { "افتح اختيار المصدر أولاً." }
        return requireNotNull(state.context)
    }

    private fun accepts(request: SourcePickerRequest): Boolean =
        state.isOpen && pending == request && state.context == request.context && state.page == request.page &&
            (request.page != SourcePickerPage.SHEETS || state.selectedFile?.id == request.fileId)

    private fun invalidate() {
        generation++
        pending = null
    }

    private fun fileCaption(count: Int): String =
        if (count == 0) "اختر جدول Google لهذا المصدر." else "تم العثور على $count جدولاً. اختر الملف ثم ورقته."
}
