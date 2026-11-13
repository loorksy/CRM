package cloud.lork.audit.data

import cloud.lork.audit.db.AppDao
import cloud.lork.audit.db.AuditMarkEntity
import cloud.lork.audit.db.CycleEntity
import cloud.lork.audit.db.KvEntity
import cloud.lork.audit.db.OpEntity
import cloud.lork.audit.db.PlanEntity
import cloud.lork.audit.db.SendItemEntity
import cloud.lork.audit.db.SendSessionEntity
import cloud.lork.audit.db.SnapshotEntity
import cloud.lork.audit.engine.AuditColumns
import cloud.lork.audit.engine.AuditMark
import cloud.lork.audit.engine.ColorTarget
import cloud.lork.audit.engine.ExistingTargetSheet
import cloud.lork.audit.engine.OpStatus
import cloud.lork.audit.engine.OpStore
import cloud.lork.audit.engine.PasteBatch
import cloud.lork.audit.engine.PasteRow
import cloud.lork.audit.engine.SheetAccessException
import cloud.lork.audit.engine.SearchHit
import cloud.lork.audit.engine.SheetApply
import cloud.lork.audit.engine.SelectiveAuditApply
import cloud.lork.audit.engine.prepareSelectiveAudit
import cloud.lork.audit.engine.SnapshotBook
import cloud.lork.audit.engine.StoredOp
import cloud.lork.audit.engine.WritePlan
import cloud.lork.audit.engine.buildWritePlan
import cloud.lork.audit.engine.markManualFromSearch
import cloud.lork.audit.engine.normalizeUserId
import cloud.lork.audit.engine.runPayrollAuditCore
import cloud.lork.audit.engine.searchUserInCycle
import cloud.lork.audit.google.SheetsRest
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Sources(
    val mgmtSs: String = "",
    val mgmtSheet: String = "",
    val agentSs: String = "",
    val agentSheet: String = "",
    val userSs: String = "",
    val userSheet: String = "",
) {
    val complete: Boolean
        get() = listOf(mgmtSs, mgmtSheet, agentSs, agentSheet, userSs, userSheet).all { it.isNotBlank() }
}

class RoomOps(private val dao: AppDao) : OpStore {
    override fun get(key: String): StoredOp? = dao.op(key)?.let {
        StoredOp(it.key, OpStatus.valueOf(it.status), it.detail)
    }

    override fun save(op: StoredOp) {
        dao.saveOp(OpEntity(op.key, op.status.name, op.detail))
    }
}

class AuditStore(private val dao: AppDao, private val sheets: SheetsRest) {
    fun cycles(email: String): List<CycleEntity> = dao.cycles(email)

    fun createCycle(email: String, name: String): CycleEntity {
        require(email.isNotBlank()) { "اربط حساب Google قبل إنشاء دورة." }
        require(name.isNotBlank()) { "أدخل اسم الدورة المالية." }
        val id = dao.insertActiveCycle(
            CycleEntity(
                name = name.trim(), accountEmail = email, discountRate = 0.0,
                agentColor = "#8b5cf6", managementColor = "#facc15",
                columnsJson = encodeColumns(AuditColumns()), sourcesJson = encodeSources(Sources()),
                userInfoHash = null, active = true, updatedAt = System.currentTimeMillis(),
            ),
        )
        return dao.cycle(id)!!
    }

    fun selectCycle(email: String, id: Long): CycleEntity? = dao.activateCycle(email, id, System.currentTimeMillis())

    fun renameCycle(email: String, id: Long, name: String): CycleEntity? {
        require(name.isNotBlank()) { "أدخل اسم الدورة المالية." }
        if (dao.renameCycle(email, id, name.trim(), System.currentTimeMillis()) == 0) return null
        return dao.cycle(id)
    }

    fun ensureCycle(email: String, name: String = "الدورة الحالية"): CycleEntity {
        dao.activeCycle(email)?.let { return it }
        dao.cycles(email).firstOrNull()?.let { return selectCycle(email, it.id)!! }
        return createCycle(email, name)
    }

    fun auditSessionClosed(cycle: CycleEntity): Boolean = dao.kv(auditSessionKey(cycle)) == "closed"

    fun setAuditSessionClosed(cycle: CycleEntity, closed: Boolean) {
        val current = dao.cycle(cycle.id) ?: return
        if (current.accountEmail != cycle.accountEmail) return
        dao.saveKv(KvEntity(auditSessionKey(cycle), if (closed) "closed" else "open"))
    }

    private fun auditSessionKey(cycle: CycleEntity) = "audit-session:${cycle.accountEmail}:${cycle.id}"

    fun targetSheets(cycle: CycleEntity): List<String> {
        val sources = decodeSources(cycle.sourcesJson)
        if (sources.mgmtSs.isBlank()) return emptyList()
        return sheets.listSheets(sources.mgmtSs)
    }

    fun manualAdvanced(cycle: CycleEntity, query: String, targetTitle: String, createIfMissing: Boolean, discountRate: Double = 0.0): String {
        return try {
            val current = dao.cycle(cycle.id) ?: return "الدورة غير موجودة."
            if (current.accountEmail != cycle.accountEmail) return "هذه الدورة لا تخص الحساب الحالي."
            val sources = decodeSources(current.sourcesJson)
            val snap = dao.latestSnapshot(current.id, current.accountEmail)
                ?: return "حمّل بيانات الدورة قبل التدقيق الانتقائي."
            if (snap.fingerprint != fingerprint(sources)) return "تغيّرت مصادر الدورة. أعد تحميل البيانات قبل التدقيق الانتقائي."
            val prepared = prepareSelectiveAudit(
                current.accountEmail, current.id.toString(), query,
                decodeRows(snap.managementJson), decodeRows(snap.agentJson), decodeColumns(current.columnsJson),
                sources.mgmtSs, sources.mgmtSheet, sources.agentSs, sources.agentSheet,
                targetTitle, createIfMissing, discountRate, current.agentColor, current.managementColor,
            )
            val plan = prepared.plan ?: return prepared.message
            val report = SelectiveAuditApply(sheets, RoomOps(dao)).apply(plan, current.accountEmail, current.id.toString())
            if (report.applied) {
                dao.saveMark(AuditMarkEntity(
                    id = "${current.id}|${plan.memberId}", cycleId = current.id, userId = plan.memberId,
                    status = "مدقق", source = "تدقيق من أداة البحث",
                    detail = "${plan.statusLabel} · ${plan.targetTitle} · ${plan.salaryBefore} / ${plan.salaryAfter}",
                ))
            }
            report.message
        } catch (e: Exception) {
            "تعذر تنفيذ التدقيق الانتقائي: ${e.message ?: "خطأ في الاتصال"}"
        }
    }

    fun saveSources(cycle: CycleEntity, sources: Sources, columns: AuditColumns, discount: Double, agentColor: String, managementColor: String) {
        dao.saveCycle(
            cycle.copy(
                sourcesJson = encodeSources(sources),
                columnsJson = encodeColumns(columns),
                discountRate = discount,
                agentColor = agentColor,
                managementColor = managementColor,
                updatedAt = System.currentTimeMillis(),
                active = true,
            ),
        )
    }

    fun download(cycle: CycleEntity): String {
        val sources = decodeSources(cycle.sourcesJson)
        if (!sources.complete) return "اختر ملفات وأوراق الإدارة والوكيل ومعلومات المستخدمين أولاً."
        val book = SnapshotBook()
        val key = "${cycle.accountEmail}|${cycle.id}"
        book.begin(key, cycle.accountEmail, cycle.id.toString(), fingerprint(sources), System.currentTimeMillis())
        try {
            book.put(key, "management", sheets.read(sources.mgmtSs, sources.mgmtSheet))
            book.put(key, "agent", sheets.read(sources.agentSs, sources.agentSheet))
            book.put(key, "userInfo", sheets.read(sources.userSs, sources.userSheet))
        } catch (e: SheetAccessException) {
            book.abort(key)
            return e.message ?: "فشل التنزيل وبقيت النسخة السابقة كما هي."
        }
        if (!book.commit(key)) {
            book.abort(key)
            return "التنزيل غير مكتمل ولم تُستبدل النسخة المحلية الصالحة."
        }
        val snap = book.active(key)!!
        val management = snap.management ?: return "التنزيل غير مكتمل ولم تُستبدل النسخة المحلية الصالحة."
        val agent = snap.agent ?: return "التنزيل غير مكتمل ولم تُستبدل النسخة المحلية الصالحة."
        val userInfo = snap.userInfo ?: return "التنزيل غير مكتمل ولم تُستبدل النسخة المحلية الصالحة."
        dao.insertSnapshot(
            SnapshotEntity(
                cycleId = cycle.id,
                accountEmail = cycle.accountEmail,
                fingerprint = snap.sourceFingerprint,
                complete = true,
                managementJson = encodeRows(management),
                agentJson = encodeRows(agent),
                userInfoJson = encodeRows(userInfo),
                rowCount = userInfo.size,
                createdAt = snap.savedAt,
            ),
        )
        return "تم تنزيل البيانات: إدارة ${management.size} صفاً، وكيل ${agent.size}، مستخدمون ${userInfo.size}."
    }

    fun preview(cycle: CycleEntity, online: Boolean, force: Boolean): Pair<WritePlan?, String> {
        val snap = dao.latestSnapshot(cycle.id, cycle.accountEmail)
            ?: return null to "لا توجد نسخة محلية. نزّل بيانات الدورة أولاً."
        if (snap.fingerprint != fingerprint(decodeSources(cycle.sourcesJson))) {
            return null to "تغيّر ربط الملفات بعد هذه النسخة. نزّل البيانات من جديد حتى لا تُرسل دورة إلى مصدر آخر."
        }
        val sources = decodeSources(cycle.sourcesJson)
        val columns = decodeColumns(cycle.columnsJson)
        val userRows = decodeRows(snap.userInfoJson)
        val mgmtRows = decodeRows(snap.managementJson)
        val agentRows = decodeRows(snap.agentJson)
        val audit = runPayrollAuditCore(
            mgmtRows, agentRows, userRows, columns, cycle.discountRate, cycle.agentColor, cycle.managementColor,
        )
        val titles = if (online) {
            try {
                sheets.listSheets(sources.mgmtSs)
            } catch (e: SheetAccessException) {
                return null to (e.message ?: "تعذر قراءة أوراق الإدارة.")
            }
        } else {
            emptyList()
        }
        val audited = dao.marks(cycle.id).filter { it.status == "مدقق" }.map { it.userId }.toSet()
        val planId = UUID.randomUUID().toString()
        fun planFor(targets: List<ExistingTargetSheet>) = buildWritePlan(
            planId = planId,
            accountEmail = cycle.accountEmail,
            cycleKey = cycle.id.toString(),
            audit = audit,
            columns = columns,
            userInfoRows = userRows,
            userInfoSpreadsheetId = sources.userSs,
            userInfoSheet = sources.userSheet,
            mgmtSpreadsheetId = sources.mgmtSs,
            mgmtSheet = sources.mgmtSheet,
            agentSpreadsheetId = sources.agentSs,
            agentSheet = sources.agentSheet,
            existingSheetTitles = titles,
            existingTargets = targets,
            previouslyAuditedIds = audited,
            previousUserInfoHash = cycle.userInfoHash,
            force = force,
            agentColor = cycle.agentColor,
            managementColor = cycle.managementColor,
        )
        var plan = planFor(emptyList())
        if (online && !plan.blocked) {
            val targets = plan.pastes.map { it.sheetTitle }.distinct().map { title ->
                val rows = try {
                    sheets.read(sources.mgmtSs, title)
                } catch (_: SheetAccessException) {
                    emptyList()
                }
                ExistingTargetSheet(title, rows)
            }
            plan = planFor(targets)
        }
        val note = if (!online) "\nالجهاز دون إنترنت: إنشاء الأوراق ولصق الصفوف يُعاد التحقق منهما عند الاعتماد." else ""
        val summary = summarize(audit.summary.total, audit.summary.agent, audit.summary.management, audit.summary.notFound, plan) + note
        if (!plan.blocked) {
            dao.savePlan(
                PlanEntity(plan.planId, cycle.id, cycle.accountEmail, encodePlan(plan), "preview", summary, System.currentTimeMillis()),
            )
        }
        return plan to summary
    }

    fun approve(cycle: CycleEntity, planId: String): String {
        val stored = dao.plan(planId) ?: return "لا توجد خطة محفوظة للاعتماد."
        if (stored.accountEmail != cycle.accountEmail || stored.cycleId != cycle.id) {
            return "هذه الخطة لا تخص الحساب أو الدورة الحالية."
        }
        val plan = decodePlan(stored.planJson)
        val report = SheetApply(sheets, RoomOps(dao)).apply(plan, cycle.accountEmail, cycle.id.toString())
        dao.savePlan(stored.copy(status = report.status, summary = report.message))
        if (report.status == "succeeded") {
            report.appliedMarks.forEach { mark ->
                dao.saveMark(
                    AuditMarkEntity(
                        id = "${cycle.id}|${mark.userId}",
                        cycleId = cycle.id,
                        userId = mark.userId,
                        status = "مدقق",
                        source = mark.source,
                        detail = mark.type,
                    ),
                )
            }
            dao.saveCycle(cycle.copy(userInfoHash = plan.userInfoHash, updatedAt = System.currentTimeMillis()))
        }
        return report.message
    }

    fun searchHit(cycle: CycleEntity, query: String, discountRate: Double = 0.0): SearchHit? {
        val snap = dao.latestSnapshot(cycle.id, cycle.accountEmail) ?: return null
        if (snap.fingerprint != fingerprint(decodeSources(cycle.sourcesJson))) return null
        val id = normalizeUserId(query)
        val cached = dao.mark(cycle.id, id)
        val marks = dao.marks(cycle.id)
        return searchUserInCycle(
            queryRaw = query,
            managementRows = decodeRows(snap.managementJson),
            agentRows = decodeRows(snap.agentJson),
            columns = decodeColumns(cycle.columnsJson),
            discountRatePct = discountRate,
            auditedAgentIds = marks.filter { it.source.contains("وكيل") || it.source.contains("يدوي") || it.source.contains("النظام") }.map { it.userId }.toSet(),
            auditedMgmtIds = marks.filter { it.source.contains("ادارة") || it.source.contains("إدارة") || it.source.contains("يدوي") }.map { it.userId }.toSet(),
            hasCache = cached != null,
            cachedStatus = cached?.status,
            cachedSource = cached?.source,
        )
    }

    fun search(cycle: CycleEntity, query: String, discountRate: Double = 0.0): String {
        if (normalizeUserId(query).isBlank()) return "رقم المستخدم غير صالح."
        val snap = dao.latestSnapshot(cycle.id, cycle.accountEmail) ?: return "لا توجد بيانات محلية للبحث."
        if (snap.fingerprint != fingerprint(decodeSources(cycle.sourcesJson))) return "تغيّرت مصادر الدورة. أعد تحميل البيانات قبل البحث."
        val hit = searchHit(cycle, query, discountRate) ?: return "رقم المستخدم غير صالح."
        return "الحالة: ${hit.logicalStatus}\nالتدقيق: ${hit.auditStatus}" +
            (hit.auditSource?.let { " — $it" } ?: "") +
            "\nقبل الخصم: ${hit.salaryBefore} / بعد الخصم: ${hit.salaryAfter}" +
            (hit.name?.let { "\nالاسم: $it" } ?: "")
    }

    fun manual(cycle: CycleEntity, query: String): String {
        val marks = dao.marks(cycle.id)
        val update = markManualFromSearch(
            query,
            marks.map { it.userId }.toSet(),
            marks.map { it.userId }.toSet(),
        ) ?: return "رقم المستخدم غير صالح."
        dao.saveMark(
            AuditMarkEntity(
                id = "${cycle.id}|${normalizeUserId(query)}",
                cycleId = cycle.id,
                userId = normalizeUserId(query),
                status = update.status,
                source = update.source,
                detail = "search",
            ),
        )
        return "عُلّم المستخدم مدققاً محلياً. هذا لا يكتب في Google حتى اعتماد خطة التدقيق."
    }

    fun rowsPreview(cycle: CycleEntity): List<String> {
        val snap = dao.latestSnapshot(cycle.id, cycle.accountEmail) ?: return emptyList()
        if (snap.fingerprint != fingerprint(decodeSources(cycle.sourcesJson))) return emptyList()
        val audit = runPayrollAuditCore(
            decodeRows(snap.managementJson),
            decodeRows(snap.agentJson),
            decodeRows(snap.userInfoJson),
            decodeColumns(cycle.columnsJson),
            cycle.discountRate,
            cycle.agentColor,
            cycle.managementColor,
        )
        return audit.results.map { row ->
            val salary = if (row.salaryValue is Number) row.salaryValue.toString() else row.salaryValue.toString()
            "${row.userId.ifBlank { "—" }} · ${row.statusLabel.ifBlank { row.type }} · $salary · ${row.title}"
        }
    }

    fun exportJson(email: String): String {
        val root = JSONObject()
        root.put("account", email)
        root.put("cycles", JSONArray().apply {
            dao.allCycles().filter { it.accountEmail == email }.forEach { cycle ->
                put(
                    JSONObject()
                        .put("id", cycle.id)
                        .put("name", cycle.name)
                        .put("sources", cycle.sourcesJson)
                        .put("hash", cycle.userInfoHash ?: ""),
                )
            }
        })
        root.put("snapshots", JSONArray().apply {
            dao.allSnapshots().filter { it.accountEmail == email }.forEach { snap ->
                put(JSONObject().put("cycleId", snap.cycleId).put("rows", snap.rowCount).put("at", snap.createdAt))
            }
        })
        return root.toString(2)
    }
}

fun fingerprint(sources: Sources): String =
    listOf(sources.mgmtSs, sources.mgmtSheet, sources.agentSs, sources.agentSheet, sources.userSs, sources.userSheet).joinToString("|")

fun encodeSources(sources: Sources): String = JSONObject()
    .put("mgmtSs", sources.mgmtSs)
    .put("mgmtSheet", sources.mgmtSheet)
    .put("agentSs", sources.agentSs)
    .put("agentSheet", sources.agentSheet)
    .put("userSs", sources.userSs)
    .put("userSheet", sources.userSheet)
    .toString()

fun decodeSources(json: String): Sources {
    if (json.isBlank()) return Sources()
    val o = JSONObject(json)
    return Sources(
        o.optString("mgmtSs"),
        o.optString("mgmtSheet"),
        o.optString("agentSs"),
        o.optString("agentSheet"),
        o.optString("userSs"),
        o.optString("userSheet"),
    )
}

fun encodeColumns(columns: AuditColumns): String = JSONObject()
    .put("userId", columns.userInfoUserIdCol)
    .put("title", columns.userInfoTitleCol)
    .put("salary", columns.userInfoSalaryCol)
    .put("status", columns.userInfoStatusCol)
    .put("mgmt", columns.cycleMgmtUserIdCol)
    .put("agent", columns.cycleAgentUserIdCol)
    .put("agentSalary", columns.cycleAgentSalaryCol)
    .toString()

fun decodeColumns(json: String): AuditColumns {
    if (json.isBlank()) return AuditColumns()
    val o = JSONObject(json)
    return AuditColumns(
        userInfoUserIdCol = o.optString("userId", "C"),
        userInfoTitleCol = o.optString("title", "D"),
        userInfoSalaryCol = o.optString("salary", "L"),
        userInfoStatusCol = o.optString("status", ""),
        cycleMgmtUserIdCol = o.optString("mgmt", "A"),
        cycleAgentUserIdCol = o.optString("agent", "A"),
        cycleAgentSalaryCol = o.optString("agentSalary", "D"),
    )
}

fun encodeRows(rows: List<List<String>>): String = JSONArray().apply {
    rows.forEach { row -> put(JSONArray().apply { row.forEach { put(it) } }) }
}.toString()

fun decodeRows(json: String): List<List<String>> {
    if (json.isBlank()) return emptyList()
    val array = JSONArray(json)
    return buildList {
        for (i in 0 until array.length()) {
            val row = array.getJSONArray(i)
            add(buildList { for (j in 0 until row.length()) add(row.optString(j)) })
        }
    }
}

private fun summarize(total: Int, agent: Int, management: Int, missing: Int, plan: WritePlan): String {
    if (plan.blocked) return plan.blockedMessage ?: "الخطة موقوفة"
    val creates = plan.sheetsToCreate.size
    val pastes = plan.pastes.sumOf { it.rows.size }
    val skipped = plan.pastes.sumOf { it.skippedAudited + it.skippedExisting }
    return "النتائج: $total صفاً. سحب وكالة $agent، سحب إدارة $management، غير موجود $missing.\n" +
        "ستُنشأ $creates ورقة، ويُلصق $pastes صفاً، وتُتخطى $skipped لأن الرقم مدقق أو موجود.\n" +
        "لم تُكتب أي خلية في Google. الاعتماد هو خطوة منفصلة."
}

fun encodePlan(plan: WritePlan): String {
    val root = JSONObject()
    root.put("planId", plan.planId)
    root.put("accountEmail", plan.accountEmail)
    root.put("cycleKey", plan.cycleKey)
    root.put("userInfoSpreadsheetId", plan.userInfoSpreadsheetId)
    root.put("userInfoSheet", plan.userInfoSheet)
    root.put("userInfoHash", plan.userInfoHash)
    root.put("userInfoCoreHash", plan.userInfoCoreHash)
    root.put("mgmtSpreadsheetId", plan.mgmtSpreadsheetId)
    root.put("mgmtSheet", plan.mgmtSheet)
    root.put("agentSpreadsheetId", plan.agentSpreadsheetId)
    root.put("agentSheet", plan.agentSheet)
    root.put("salaryCol", plan.salaryCol)
    root.put("statusCol", plan.statusCol)
    root.put("dataStartRow1", plan.dataStartRow1)
    root.put("userIdColumnIndex", plan.userIdColumnIndex)
    root.put("mgmtUserIdColumnIndex", plan.mgmtUserIdColumnIndex)
    root.put("agentUserIdColumnIndex", plan.agentUserIdColumnIndex)
    root.put("userIds", JSONArray(plan.userIdsInOrder))
    root.put("create", JSONArray(plan.sheetsToCreate))
    root.put("salary", JSONArray(plan.salaryColumn))
    root.put("status", JSONArray(plan.statusColumn))
    root.put("pastes", JSONArray().apply {
        plan.pastes.forEach { batch ->
            put(
                JSONObject()
                    .put("sheet", batch.sheetTitle)
                    .put("skippedAudited", batch.skippedAudited)
                    .put("skippedExisting", batch.skippedExisting)
                    .put("rows", JSONArray().apply {
                        batch.rows.forEach { row ->
                            put(JSONObject().put("id", row.userId).put("cells", JSONArray(row.cells)))
                        }
                    }),
            )
        }
    })
    fun colors(list: List<ColorTarget>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.userId).put("hex", it.hex)) }
    }
    root.put("mgmtColors", colors(plan.mgmtColors))
    root.put("agentColors", colors(plan.agentColors))
    root.put("marks", JSONArray().apply {
        plan.auditMarks.forEach {
            put(JSONObject().put("id", it.userId).put("source", it.source).put("type", it.type).put("salary", it.salary))
        }
    })
    return root.toString()
}

fun decodePlan(json: String): WritePlan {
    val o = JSONObject(json)
    fun strings(key: String): List<String> {
        val array = o.optJSONArray(key) ?: JSONArray()
        return buildList { for (i in 0 until array.length()) add(array.getString(i)) }
    }
    fun colors(key: String): List<ColorTarget> {
        val array = o.optJSONArray(key) ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                add(ColorTarget(item.getString("id"), item.getString("hex")))
            }
        }
    }
    val pastes = o.optJSONArray("pastes") ?: JSONArray()
    return WritePlan(
        planId = o.getString("planId"),
        accountEmail = o.getString("accountEmail"),
        cycleKey = o.getString("cycleKey"),
        userInfoSpreadsheetId = o.getString("userInfoSpreadsheetId"),
        userInfoSheet = o.getString("userInfoSheet"),
        userInfoHash = o.getString("userInfoHash"),
        userInfoCoreHash = o.optString("userInfoCoreHash"),
        mgmtSpreadsheetId = o.getString("mgmtSpreadsheetId"),
        mgmtSheet = o.getString("mgmtSheet"),
        agentSpreadsheetId = o.getString("agentSpreadsheetId"),
        agentSheet = o.getString("agentSheet"),
        salaryCol = o.getString("salaryCol"),
        statusCol = o.getString("statusCol"),
        dataStartRow1 = o.getInt("dataStartRow1"),
        userIdsInOrder = strings("userIds"),
        sheetsToCreate = strings("create"),
        pastes = buildList {
            for (i in 0 until pastes.length()) {
                val batch = pastes.getJSONObject(i)
                val rows = batch.getJSONArray("rows")
                add(
                    PasteBatch(
                        sheetTitle = batch.getString("sheet"),
                        rows = buildList {
                            for (r in 0 until rows.length()) {
                                val row = rows.getJSONObject(r)
                                val cells = row.getJSONArray("cells")
                                add(PasteRow(row.getString("id"), buildList { for (c in 0 until cells.length()) add(cells.getString(c)) }))
                            }
                        },
                        skippedAudited = batch.optInt("skippedAudited"),
                        skippedExisting = batch.optInt("skippedExisting"),
                    ),
                )
            }
        },
        salaryColumn = strings("salary"),
        statusColumn = strings("status"),
        mgmtColors = colors("mgmtColors"),
        agentColors = colors("agentColors"),
        auditMarks = buildList {
            val marks = o.optJSONArray("marks") ?: JSONArray()
            for (i in 0 until marks.length()) {
                val mark = marks.getJSONObject(i)
                add(AuditMark(mark.getString("id"), mark.getString("source"), mark.getString("type"), mark.getString("salary")))
            }
        },
        userIdColumnIndex = o.optInt("userIdColumnIndex", 2),
        mgmtUserIdColumnIndex = o.optInt("mgmtUserIdColumnIndex", 0),
        agentUserIdColumnIndex = o.optInt("agentUserIdColumnIndex", 0),
    )
}

fun sessionOf(entity: SendSessionEntity?) = cloud.lork.audit.engine.SendSessionState(
    state = entity?.state ?: "IDLE",
    index = entity?.idx ?: 0,
    mpm = entity?.mpm ?: 10,
    chatKey = entity?.chatKey,
)

fun itemsOf(rows: List<SendItemEntity>) = rows.map {
    cloud.lork.audit.engine.SendItem(it.itemId, it.position, it.body, it.status)
}
