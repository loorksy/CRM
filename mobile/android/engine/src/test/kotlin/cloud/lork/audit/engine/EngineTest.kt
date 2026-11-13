package cloud.lork.audit.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

class PayrollParityTest {
    private val json = JSONObject(
        PayrollParityTest::class.java.getResource("/payroll-fixtures.json")!!.readText(),
    )

    @Test
    fun auditMatchesOriginalEngine() {
        val cases = json.getJSONArray("cases")
        val seeded = JSONArray(PayrollParityTest::class.java.getResource("/payroll-seeded-fixtures.json")!!.readText())
        for (i in 0 until seeded.length()) cases.put(seeded.getJSONObject(i))
        for (i in 0 until cases.length()) {
            val item = cases.getJSONObject(i)
            val input = item.getJSONObject("input")
            val expected = item.getJSONObject("output")
            val cols = input.optJSONObject("columns") ?: JSONObject()
            val out = runPayrollAuditCore(
                managementRows = rows(input.getJSONArray("managementRows")),
                agentRows = rows(input.getJSONArray("agentRows")),
                userInfoRows = rows(input.getJSONArray("userInfoRows")),
                columns = AuditColumns(
                    userInfoUserIdCol = cols.optString("userInfoUserIdCol", "C"),
                    userInfoTitleCol = cols.optString("userInfoTitleCol", "D"),
                    cycleMgmtUserIdCol = cols.optString("cycleMgmtUserIdCol", "A"),
                    cycleAgentUserIdCol = cols.optString("cycleAgentUserIdCol", "A"),
                    cycleAgentSalaryCol = cols.optString("cycleAgentSalaryCol", "D"),
                ),
                discountRatePct = input.optDouble("discountRatePct", 0.0),
                agentColor = if (input.has("agentColor")) input.getString("agentColor") else null,
                managementColor = if (input.has("managementColor")) input.getString("managementColor") else null,
            )
            val name = item.getString("name")
            val summary = expected.getJSONObject("summary")
            assertEquals(name, summary.getInt("total"), out.summary.total)
            assertEquals(name, summary.getInt("agent"), out.summary.agent)
            assertEquals(name, summary.getInt("management"), out.summary.management)
            assertEquals(name, summary.getInt("notFound"), out.summary.notFound)
            assertEquals(name, expected.getInt("dataStart"), out.dataStart)
            val results = expected.getJSONArray("results")
            assertEquals(name, results.length(), out.results.size)
            for (r in 0 until results.length()) {
                val exp = results.getJSONObject(r)
                val got = out.results[r]
                assertEquals(name, exp.getString("userId"), got.userId)
                assertEquals(name, exp.getString("title"), got.title)
                assertEquals(name, exp.getString("type"), got.type)
                assertEquals(name, exp.getString("statusLabel"), got.statusLabel)
                assertEquals(name, exp.getInt("rowIndex"), got.rowIndex)
                assertSameNumber(name, exp.get("salaryValue"), got.salaryValue)
                assertNullableInt(name, exp, "mgmtSheetRowIndex", got.mgmtSheetRowIndex)
                val idxs = exp.getJSONArray("agentSheetRowIndices")
                assertEquals(name, idxs.length(), got.agentSheetRowIndices.size)
                for (k in 0 until idxs.length()) {
                    assertEquals(name, idxs.getInt(k), got.agentSheetRowIndices[k])
                }
            }
            val byTitle = expected.getJSONObject("byTitle")
            assertEquals(name, byTitle.length(), out.byTitle.size)
            for (key in byTitle.keys()) {
                val expItems = byTitle.getJSONArray(key)
                val gotItems = out.byTitle[key] ?: error(name)
                assertEquals(expItems.length(), gotItems.size)
                for (k in 0 until expItems.length()) {
                    assertEquals(expItems.getJSONObject(k).getString("type"), gotItems[k].type)
                    assertEquals(expItems.getJSONObject(k).getString("color"), gotItems[k].color)
                    assertEquals(expItems.getJSONObject(k).getString("userId"), normalizeUserId(gotItems[k].managementRow[0]))
                }
            }
        }
    }

    @Test
    fun normalizationDuplicatesHashAndManualMatchOriginal() {
        val normalized = json.getJSONArray("normalized")
        for (i in 0 until normalized.length()) {
            val row = normalized.getJSONObject(i)
            assertEquals(row.getString("out"), normalizeUserId(row.getString("in")))
        }
        val dup = json.getJSONObject("duplicates")
        val found = findDuplicateUserInfoMemberIds(
            listOf(
                listOf("ت", "اسم", "رقم", "ورقة"),
                listOf("", "", "11", "أ"),
                listOf("", "", "011", "ب"),
                listOf("", "", "12", "ج"),
            ),
            "C",
        )
        assertEquals(jsonArray(dup.getJSONArray("duplicateIds")), found.duplicateIds)
        assertEquals(jsonArray(dup.getJSONArray("duplicateRowNumbers")), found.duplicateRowNumbers.map { it.toString() })
        val hash = json.getJSONObject("hash")
        assertEquals(hash.getString("hex"), hashUserInfoRows(rows(hash.getJSONArray("rows"))))
        val manual = json.getJSONArray("manual")
        for (i in 0 until manual.length()) {
            val item = manual.getJSONObject(i)
            val input = item.getJSONObject("input")
            val output = item.getJSONObject("output")
            val got = classifyManualStatus(
                input.getBoolean("inMgmt"),
                input.getBoolean("inAgent"),
                input.getBoolean("mgmtColored"),
                input.getBoolean("agentColored"),
            )
            assertEquals(output.getString("status"), got.status)
            val source = if (output.isNull("source")) null else output.getString("source")
            assertEquals(source, got.source)
        }
        val salary = json.getJSONObject("salary")
        val computed = computeSalaryWithDiscount(listOf("100", "50", ""), 10.0)
        assertEquals(salary.getDouble("before"), computed.first, 0.0)
        assertEquals(salary.getDouble("after"), computed.second, 0.0)
    }
}

class PlanAndSyncTest {
    private val userHeader = listOf("ت", "اسم", "رقم", "ورقة", "", "", "", "", "", "", "", "راتب")
    private val userRow = listOf("", "", "1001", "الرياض", "", "", "", "", "", "", "", "")
    private val mgmt = listOf(listOf("رقم", "اسم"), listOf("1001", "علي"))
    private val agent = listOf(listOf("رقم", "اسم", "", "راتب"), listOf("1001", "علي", "", "100"))
    private val info = listOf(userHeader, userRow)

    private fun plan(existingTitles: List<String> = listOf("الإدارة"), audited: Set<String> = emptySet(), force: Boolean = false, previousHash: String? = null): WritePlan {
        val audit = runPayrollAuditCore(mgmt, agent, info, discountRatePct = 10.0, agentColor = "#8b5cf6", managementColor = "#facc15")
        return buildWritePlan(
            planId = "p1",
            accountEmail = "a@example.com",
            cycleKey = "c1",
            audit = audit,
            columns = AuditColumns(),
            userInfoRows = info,
            userInfoSpreadsheetId = "ss-u",
            userInfoSheet = "معلومات",
            mgmtSpreadsheetId = "ss-m",
            mgmtSheet = "الإدارة",
            agentSpreadsheetId = "ss-m",
            agentSheet = "الوكيل",
            existingSheetTitles = existingTitles,
            existingTargets = emptyList(),
            previouslyAuditedIds = audited,
            previousUserInfoHash = previousHash,
            force = force,
            agentColor = "#8b5cf6",
            managementColor = "#facc15",
        )
    }

    @Test
    fun previewDoesNotRequireNetworkAndBlocksUnchangedHash() {
        val first = plan()
        assertFalse(first.blocked)
        assertEquals(listOf("الرياض"), first.sheetsToCreate)
        assertEquals("90", first.salaryColumn.single())
        assertEquals("سحب وكيل", first.statusColumn.single())
        val again = plan(previousHash = first.userInfoHash)
        assertEquals("USER_INFO_UNCHANGED", again.blockedCode)
        val forced = plan(previousHash = first.userInfoHash, force = true, audited = setOf("1001"))
        assertFalse(forced.blocked)
        assertTrue(forced.pastes.single().rows.isEmpty())
        assertEquals(1, forced.pastes.single().skippedAudited)
    }

    @Test
    fun duplicateUserInfoBlocksBeforeChanges() {
        val rows = listOf(userHeader, userRow, listOf("", "", "01001", "الرياض"))
        val audit = runPayrollAuditCore(mgmt, agent, rows)
        val built = buildWritePlan(
            "p", "a@example.com", "c1", audit, AuditColumns(), rows,
            "ss-u", "معلومات", "ss-m", "الإدارة", "ss-m", "الوكيل",
            emptyList(), emptyList(), emptySet(), null, false, "#8b5cf6", "#facc15",
        )
        assertEquals("USER_INFO_DUPLICATE_IDS", built.blockedCode)
    }

    @Test
    fun conflictBeforeWriteDoesNotMutate() {
        val built = plan()
        val fake = FakeSheets()
        fake.put("ss-u", "معلومات", info.map { it.toMutableList() })
        fake.put("ss-m", "الإدارة", mgmt.map { it.toMutableList() })
        fake.put("ss-m", "الوكيل", agent.map { it.toMutableList() })
        fake.grids.getValue("ss-u" to "معلومات")[1][2] = "9999"
        val report = SheetApply(fake, MemoryOpStore()).apply(built, "a@example.com", "c1")
        assertEquals("needs_review", report.status)
        assertFalse(fake.titles["ss-m"].orEmpty().contains("الرياض"))
        assertEquals(0, fake.appends)
    }

    @Test
    fun successfulApplyVerifiesAndDoesNotRepeat() {
        val built = plan()
        val fake = FakeSheets()
        fake.put("ss-u", "معلومات", info.map { it.toMutableList() })
        fake.put("ss-m", "الإدارة", mgmt.map { it.toMutableList() })
        fake.put("ss-m", "الوكيل", agent.map { it.toMutableList() })
        val ops = MemoryOpStore()
        val report = SheetApply(fake, ops).apply(built, "a@example.com", "c1")
        assertEquals(report.stages.joinToString(" | "), "succeeded", report.status)
        assertEquals(1, fake.appends)
        assertEquals(2, fake.colors)
        val again = SheetApply(fake, ops).apply(built, "a@example.com", "c1")
        assertEquals("succeeded", again.status)
        assertEquals(1, fake.appends)
        assertEquals(2, fake.colors)
    }

    @Test
    fun networkDropAfterAppendIsVerifiedBeforeRetry() {
        val built = plan()
        val fake = FakeSheets()
        fake.put("ss-u", "معلومات", info.map { it.toMutableList() })
        fake.put("ss-m", "الإدارة", mgmt.map { it.toMutableList() })
        fake.put("ss-m", "الوكيل", agent.map { it.toMutableList() })
        fake.throwAfterAppend = true
        val ops = MemoryOpStore()
        val first = SheetApply(fake, ops).apply(built, "a@example.com", "c1")
        assertEquals("failed", first.status)
        assertEquals(1, fake.appends)
        fake.throwAfterAppend = false
        val second = SheetApply(fake, ops).apply(built, "a@example.com", "c1")
        assertEquals("succeeded", second.status)
        assertEquals(1, fake.appends)
    }

    @Test
    fun accountSwitchRefusesWrite() {
        val report = SheetApply(FakeSheets(), MemoryOpStore()).apply(plan(), "other@example.com", "c1")
        assertEquals("needs_review", report.status)
    }

    @Test
    fun partialSnapshotDoesNotReplaceCompleteOne() {
        val book = SnapshotBook()
        book.begin("k", "a@example.com", "c1", "fp", 1)
        book.put("k", "management", mgmt)
        book.put("k", "agent", agent)
        book.put("k", "userInfo", info)
        assertTrue(book.commit("k"))
        book.begin("k", "a@example.com", "c1", "fp2", 2)
        book.put("k", "management", listOf(listOf("ناقص")))
        assertFalse(book.commit("k"))
        assertEquals(2, book.active("k")!!.management!!.size)
        book.abort("k")
        assertEquals(2, book.active("k")!!.management!!.size)
    }

    @Test
    fun linkPhasesAndReadinessStayDistinct() {
        var state = LinkState()
        state = reduceLink(state, LinkEvent.Cancelled)
        assertEquals(LinkPhase.CANCELED, state.phase)
        assertEquals("GS-CANCEL", state.diagnostic)
        state = reduceLink(state, LinkEvent.ConsentShown("a@example.com"))
        assertEquals(LinkPhase.WAITING_CONSENT, state.phase)
        val ready = auditReadiness(state, hasSnapshot = false, online = true)
        assertEquals(Readiness.NEED_AUTH, ready.readiness)
        state = reduceLink(state, LinkEvent.Authorized("a@example.com"))
        assertEquals(Readiness.NEED_SOURCES, auditReadiness(state, false, true).readiness)
        state = reduceLink(state, LinkEvent.SourcesReady)
        assertEquals(Readiness.NEED_DOWNLOAD, auditReadiness(state, false, true).readiness)
        assertEquals(Readiness.OFFLINE_NEED_DOWNLOAD, auditReadiness(state, false, false).readiness)
        state = reduceLink(state, LinkEvent.DataReady)
        assertEquals(Readiness.READY, auditReadiness(state, true, false).readiness)
        val denied = reduceLink(LinkState(), LinkEvent.AuthFailed("GS-403-DRIVE", "لا توجد صلاحية"))
        assertEquals(LinkPhase.AUTH_FAILED, denied.phase)
        assertFalse(denied.diagnostic.contains("ya29"))
    }

    @Test
    fun driveErrorIsNotAnEmptyFileList() {
        val denied = collectDriveFiles { DrivePage(null, null, "403", "forbidden") }
        assertEquals("403", denied.errorCode)
        assertTrue(denied.files.isEmpty())
        assertTrue(denied.message.contains("صلاحية"))
        var calls = 0
        val pages = collectDriveFiles { token ->
            calls++
            if (token == null) DrivePage(listOf(DriveFile("1", "أ")), "next", null, null)
            else DrivePage(listOf(DriveFile("2", "ب")), null, null, null)
        }
        assertEquals(2, pages.files.size)
        assertNull(pages.errorCode)
        val empty = collectDriveFiles { DrivePage(emptyList(), null, null, null) }
        assertNull(empty.errorCode)
        assertTrue(empty.message.contains("لا توجد"))
    }

    @Test
    fun searchKeepsOriginalLabelsAndCachePriority() {
        val hit = searchUserInCycle(
            "1001",
            mgmt,
            agent,
            discountRatePct = 10.0,
            auditedAgentIds = setOf("1001"),
            hasCache = false,
        )
        assertEquals("سحب وكيل", hit!!.logicalStatus)
        assertEquals(90.0, hit.salaryAfter, 0.0)
        assertEquals("مدقق", hit.auditStatus)
        assertEquals("مدقق وكيل يدوي", hit.auditSource)
        val cached = searchUserInCycle(
            "1001", mgmt, agent,
            auditedAgentIds = setOf("1001"),
            hasCache = true,
            cachedStatus = "مدقق",
            cachedSource = "تدقيق وكيل من النظام",
        )
        assertEquals("تدقيق وكيل من النظام", cached!!.auditSource)
        val loose = searchUserInCycle("1001", mgmt, agent, auditedAgentIds = setOf("xx1001"))
        assertEquals("مدقق", loose!!.auditStatus)
        val manual = markManualFromSearch("01001", emptySet(), emptySet())
        assertEquals(setOf("1001"), manual!!.auditedAgentIds)
        assertEquals("مدقق من صفحة البحث", manual.source)
    }

    @Test
    fun migrationKeepsExistingRows() {
        val conn = DriverManager.getConnection("jdbc:sqlite::memory:")
        conn.createStatement().execute("CREATE TABLE cycles (id INTEGER PRIMARY KEY, name TEXT NOT NULL, account_email TEXT NOT NULL)")
        conn.createStatement().execute("INSERT INTO cycles (id, name, account_email) VALUES (1, 'دورة', 'a@example.com')")
        conn.createStatement().execute(SchemaMigration.V1_TO_V2)
        val rs = conn.createStatement().executeQuery("SELECT name, account_email, exported_note FROM cycles")
        assertTrue(rs.next())
        assertEquals("دورة", rs.getString(1))
        assertEquals("a@example.com", rs.getString(2))
        assertEquals("", rs.getString(3))
        conn.close()
    }

    @Test
    fun diagnosticRedactsTokens() {
        val text = safeDiagnostic("GS-401", "bearer ya29.secret-token failed")
        assertFalse(text.contains("ya29.secret"))
        assertTrue(text.contains("GS-401"))
    }
}

class SendBehaviorTest {
    @Test
    fun parserMatchesBulkSenderModes() {
        val raw = "أ\n\nب\nج"
        assertEquals(listOf("أ", "ب\nج"), parseMessages(raw, ParseMode.PARAGRAPH))
        assertEquals(listOf("أ", "ب", "ج"), parseMessages("أ\nب\nج", ParseMode.LINES))
        assertEquals(listOf("أ\nب\nج", "د"), parseMessages("أ\nب\nج\n\nد", ParseMode.FIXED3))
        assertEquals(listOf("سطر"), parseMessages("سطر", ParseMode.PARAGRAPH_THEN_LINES))
        assertEquals(6000L, delayMs(10))
        assertEquals(60L, delayMs(1000))
        assertEquals(60L, delayMs(5000))
    }

    @Test
    fun uncertainIsNotRetriedAndCrashBeforeResultStaysUncertain() {
        val items = listOf(SendItem("m1", 0, "نص", "EXECUTION_STARTED"), SendItem("m2", 1, "ثاني", "READY"))
        val session = SendSessionState("SENDING", 0, 10, "com.whatsapp|علي")
        val restored = restoreAfterRestart(session, items)
        assertEquals("PAUSED", restored.first.state)
        assertEquals("UNCERTAIN", restored.second[0].status)
        assertEquals("READY", restored.second[1].status)
        val action = nextSendAction(restored.first.copy(state = "SENDING"), restored.second, openChat())
        assertTrue(action is SendAction.Halt)
        assertEquals("m1", (action as SendAction.Halt).uncertainItemId)
    }

    @Test
    fun stopsOnDraftAppSwitchAndMissingControls() {
        val session = SendSessionState("SENDING", 0, 10, null)
        val item = listOf(SendItem("m1", 0, "نص", "READY"))
        val draft = nextSendAction(session, item, openChat(entryText = "مسودة"))
        assertTrue((draft as SendAction.Halt).reason.contains("حقل الكتابة"))
        val switched = nextSendAction(rememberChat(session, openChat()), item, openChat(supported = false, chatKey = "com.notes|x"))
        assertTrue((switched as SendAction.Halt).reason.contains("واتساب"))
        val noSend = nextSendAction(session, item, openChat(sendFound = false))
        assertTrue(noSend is SendAction.PrepareText)
        val noSendAfterTyping = nextSendAction(session, listOf(item.single().copy(status = "TEXT_PREPARED")), openChat(entryText = "نص", sendFound = false))
        assertTrue((noSendAfterTyping as SendAction.Halt).reason.contains("زر الإرسال"))
        val prepared = listOf(SendItem("m1", 0, "نص", "TEXT_PREPARED"))
        val press = nextSendAction(rememberChat(session, openChat(entryText = "نص")), prepared, openChat(entryText = "نص"))
        assertTrue(press is SendAction.PressSend)
        val advanced = afterPress(session.copy(chatKey = "com.whatsapp|علي"), prepared, "m1")
        assertEquals("DONE", advanced.first.state)
        assertEquals("SEND_PRESSED", advanced.second.single().status)
    }

    @Test
    fun waitsForWhatsAppToOpenBeforePreparingTheFirstMessage() {
        val session = SendSessionState("WAITING_CHAT", 0, 10, null)
        val item = listOf(SendItem("m1", 0, "نص", "READY"))
        val outside = openChat(supported = false).copy(entryFound = false, chatNameReadable = false)
        assertEquals(null, rememberChat(session, outside).chatKey)
        assertTrue(nextSendAction(session, item, outside) is SendAction.Wait)
        val inside = openChat(sendFound = false)
        val bound = rememberChat(session, inside)
        assertEquals("com.whatsapp|علي", bound.chatKey)
        assertTrue(nextSendAction(bound, item, inside) is SendAction.PrepareText)
        assertTrue(nextSendAction(bound, item, outside) is SendAction.Halt)
    }

    @Test
    fun chatChangeBetweenPreparingAndClickingStopsTheQueue() {
        val session = SendSessionState("SENDING", 0, 10, "com.whatsapp|علي")
        val prepared = listOf(SendItem("m1", 0, "نص", "TEXT_PREPARED"))
        val action = nextSendAction(session, prepared, openChat(entryText = "نص", chatKey = "com.whatsapp|آخر"))
        assertTrue(action is SendAction.Halt)
        assertEquals("m1", (action as SendAction.Halt).uncertainItemId)
    }

    @Test
    fun finderRejectsGenericEditableAndAcceptsWhatsAppIds() {
        val notes = MemNode.link(
            MemNode(
                packageName = "com.example.notes",
                children = listOf(MemNode(className = "android.widget.EditText", editable = true, visible = true, text = "")),
            ),
        )
        assertNull(WhatsAppMatch.findEntry(notes))
        assertNull(WhatsAppMatch.findSend(notes))
        val chat = MemNode.link(
            MemNode(
                packageName = "com.whatsapp",
                children = listOf(
                    MemNode(viewId = "com.whatsapp:id/conversation_contact_name", text = "علي"),
                    MemNode(viewId = "com.whatsapp:id/entry", className = "android.widget.EditText", editable = true, text = ""),
                    MemNode(viewId = "com.whatsapp:id/send", clickable = true, description = "إرسال"),
                ),
            ),
        )
        assertEquals("com.whatsapp:id/entry", WhatsAppMatch.findEntry(chat)!!.viewId)
        assertEquals("com.whatsapp:id/send", WhatsAppMatch.findSend(chat)!!.viewId)
        assertEquals("علي", WhatsAppMatch.chatName(chat))
        val looseEdit = MemNode.link(
            MemNode(
                packageName = "com.whatsapp",
                children = listOf(MemNode(className = "android.widget.EditText", editable = true, visible = true)),
            ),
        )
        assertNull(WhatsAppMatch.findEntry(looseEdit))
    }
}

private fun openChat(
    entryText: String = "",
    supported: Boolean = true,
    sendFound: Boolean = true,
    chatKey: String = "com.whatsapp|علي",
) = ChatObservation(
    accessibilityBound = true,
    overlayGranted = true,
    supportedPackage = supported,
    entryFound = true,
    sendFound = sendFound,
    entryText = entryText,
    chatKey = chatKey,
    chatNameReadable = true,
)

private fun rows(array: JSONArray): List<List<String>> {
    val out = mutableListOf<List<String>>()
    for (i in 0 until array.length()) {
        val row = array.getJSONArray(i)
        val cells = mutableListOf<String>()
        for (j in 0 until row.length()) {
            val value = row.get(j)
            cells += if (value == JSONObject.NULL) "" else value.toString()
        }
        out += cells
    }
    return out
}

private fun jsonArray(array: JSONArray): List<String> =
    (0 until array.length()).map { array.get(it).toString() }

private fun assertSameNumber(name: String, expected: Any, actual: Any) {
    if (expected == "" || expected == JSONObject.NULL) {
        assertEquals(name, "", actual)
        return
    }
    val exp = (expected as Number).toDouble()
    val got = (actual as Number).toDouble()
    assertEquals(name, exp, got, 0.000001)
}

private fun assertNullableInt(name: String, json: JSONObject, key: String, actual: Int?) {
    if (json.isNull(key)) assertNull(name, actual) else assertEquals(name, json.getInt(key), actual)
}

object SchemaMigration {
    const val V1_TO_V2 = "ALTER TABLE cycles ADD COLUMN exported_note TEXT NOT NULL DEFAULT ''"
}

class FakeSheets : SheetGateway {
    val grids = linkedMapOf<Pair<String, String>, MutableList<MutableList<String>>>()
    val titles = linkedMapOf<String, MutableList<String>>()
    val backgrounds = linkedMapOf<Pair<String, String>, MutableMap<Int, String>>()
    var appends = 0
    var colors = 0
    var updates = 0
    var throwAfterAppend = false

    fun put(spreadsheetId: String, title: String, rows: List<MutableList<String>>) {
        titles.getOrPut(spreadsheetId) { mutableListOf() }.let { if (title !in it) it += title }
        grids[spreadsheetId to title] = rows.map { it.toMutableList() }.toMutableList()
    }

    override fun listSheets(spreadsheetId: String): List<String> = titles[spreadsheetId].orEmpty()

    override fun read(spreadsheetId: String, title: String): List<List<String>> {
        val grid = grids[spreadsheetId to title] ?: throw SheetAccessException("404", "الورقة غير موجودة")
        return grid.map { it.toList() }
    }

    override fun addSheets(spreadsheetId: String, newTitles: List<String>) {
        val list = titles.getOrPut(spreadsheetId) { mutableListOf() }
        for (title in newTitles) {
            if (list.none { it.equals(title, true) }) list += title
            grids.putIfAbsent(spreadsheetId to title, mutableListOf())
        }
    }

    override fun updateRange(spreadsheetId: String, a1: String, values: List<List<String>>) {
        updates++
        val grid = grids[spreadsheetId to sheetOf(a1)] ?: throw SheetAccessException("404", "الورقة غير موجودة")
        val range = a1.substringAfter('!')
        val match = Regex("([A-Z]+)(\\d+)").find(range) ?: return
        val col = columnLetterToIndex(match.groupValues[1]) ?: return
        val start = match.groupValues[2].toInt() - 1
        values.forEachIndexed { i, row ->
            while (grid.size <= start + i) grid.add(mutableListOf())
            val dest = grid[start + i]
            while (dest.size <= col) dest += ""
            dest[col] = row.firstOrNull().orEmpty()
        }
    }

    override fun append(spreadsheetId: String, a1: String, values: List<List<String>>) {
        val key = spreadsheetId to sheetOf(a1)
        val grid = grids.getOrPut(key) { mutableListOf() }
        values.forEach { grid += it.toMutableList() }
        appends++
        if (throwAfterAppend) throw SheetAccessException("network", "انقطع بعد الكتابة")
    }

    override fun colorRows(spreadsheetId: String, title: String, zeroBasedRows: List<Int>, hex: String) {
        colors++
        val map = backgrounds.getOrPut(spreadsheetId to title) { mutableMapOf() }
        zeroBasedRows.forEach { map[it] = hex }
    }

    override fun readBackgrounds(spreadsheetId: String, title: String): Map<Int, String> =
        backgrounds[spreadsheetId to title].orEmpty()

    private fun sheetOf(a1: String): String = a1.substringBefore('!').trim('\'')
}
