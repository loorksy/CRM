package cloud.lork.audit

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import cloud.lork.audit.data.Sources
import cloud.lork.audit.data.encodeRows
import cloud.lork.audit.data.fingerprint
import cloud.lork.audit.db.SnapshotEntity
import cloud.lork.audit.engine.AuditColumns
import cloud.lork.audit.engine.LinkPhase

/** Native Room/ViewModel checks. Uses an isolated synthetic account; never contacts Google. */
class WorkflowInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        // onCreate starts the instrumentation thread before Application.onCreate returns.
        waitForIdleSync()
        val output = Bundle()
        val checks = mutableListOf<String>()
        var failure: Throwable? = null
        val app = targetContext.applicationContext as LorkApp
        val previousLink = app.session.state.value
        val email = "instrumentation-${System.currentTimeMillis()}@example.invalid"
        fun verify(name: String, condition: Boolean) {
            check(condition) { name }
            checks += "PASS $name"
        }
        try {
            runOnMainSync {
                try {
                    app.session.state.value = previousLink.copy(email = email, phase = LinkPhase.AUTHORIZED)
                    val model = HomeViewModel(app)
                    model.reload()
                    verify("No anonymous cycle is created", model.cycle == null && model.cycles.isEmpty())
                    model.newCycleName = "First cycle"
                    model.createCycle()
                    val first = requireNotNull(model.cycle)
                    verify("Named cycle is saved by the real ViewModel", first.name == "First cycle")
                    model.cycleRename = "Renamed cycle"
                    model.renameCycle()
                    verify("Renaming survives a Room reload", app.db.dao().cycle(first.id)?.name == "Renamed cycle")
                    model.toggleAuditSession()
                    verify("Closing session is persisted per cycle", app.store.auditSessionClosed(first))
                    model.toggleAuditSession()
                    verify("Session can be reopened", !app.store.auditSessionClosed(first))
                    model.newCycleName = "Second cycle"
                    model.createCycle()
                    val second = requireNotNull(model.cycle)
                    verify("New cycle has independent sources", second.id != first.id && !model.sources.complete)
                    verify("Only the selected cycle is active", app.store.cycles(email).count { it.active } == 1)
                    verify("Another account cannot select this cycle", app.store.selectCycle("other@example.invalid", first.id) == null)
                    model.selectCycle(first.id)
                    val sources = Sources("test-management", "Management", "test-agent", "Agent", "test-users", "Users")
                    val current = requireNotNull(app.db.dao().cycle(first.id))
                    app.store.saveSources(current, sources, AuditColumns(), 10.0, "#8b5cf6", "#facc15")
                    app.db.dao().insertSnapshot(SnapshotEntity(
                        cycleId = first.id, accountEmail = email, fingerprint = fingerprint(sources), complete = true,
                        managementJson = encodeRows(listOf(listOf("ID", "Name"), listOf("1001", "Sample"))),
                        agentJson = encodeRows(listOf(listOf("ID", "Name", "", "Salary"),
                            listOf("1001", "Sample", "", "40"), listOf("1001", "Sample", "", "10"))),
                        userInfoJson = encodeRows(listOf(listOf("", "", "ID", "Sheet"), listOf("", "", "1001", "Target"))),
                        rowCount = 2, createdAt = System.currentTimeMillis(),
                    ))
                    model.reload()
                    verify("Selected cycle recognizes its own snapshot", model.hasCurrentSnapshot())
                    val loaded = requireNotNull(model.cycle)
                    val found = requireNotNull(app.store.searchHit(loaded, "1001"))
                    verify("Search discount defaults to zero independently of bulk discount", found.salaryBefore == 50.0 && found.salaryAfter == 50.0)
                    verify("Search accepts its own discount", app.store.searchHit(loaded, "1001", 10.0)?.salaryAfter == 45.0)
                    val preview = app.store.preview(loaded, online = false, force = false).first
                    verify("Offline preview produces a complete local plan", preview != null && !preview.blocked)
                    verify("Offline preview does not mark users as applied", app.db.dao().marks(first.id).isEmpty())
                    model.searchText = "old result"
                    model.planId = preview?.planId
                    model.selectCycle(second.id)
                    verify("Switching cycles clears the prior report and plan", model.searchText.isEmpty() && model.planId == null)
                    verify("A second cycle never borrows the first snapshot", !model.hasCurrentSnapshot())
                    app.session.state.value = previousLink.copy(email = "another@example.invalid", phase = LinkPhase.AUTHORIZED)
                    model.reload()
                    verify("An account without cycles clears the previous cycle", model.cycle == null && model.rows.isEmpty() && model.planId == null)
                } catch (t: Throwable) { failure = t }
            }
        } catch (t: Throwable) {
            failure = t
        } finally {
            runOnMainSync { app.session.state.value = previousLink }
            app.db.runInTransaction {
                val db = app.db.openHelper.writableDatabase
                db.execSQL("DELETE FROM plans WHERE accountEmail = ?", arrayOf(email))
                db.execSQL("DELETE FROM snapshots WHERE accountEmail = ?", arrayOf(email))
                db.execSQL("DELETE FROM kv WHERE k LIKE ?", arrayOf("audit-session:$email:%"))
                db.execSQL("DELETE FROM cycles WHERE account_email = ?", arrayOf(email))
            }
        }
        output.putString("stream", checks.joinToString("\n", postfix = "\n") +
            (failure?.let { "FAIL ${it.stackTraceToString()}" } ?: "${checks.size} native checks passed\n"))
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, output)
    }
}
