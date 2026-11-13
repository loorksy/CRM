package cloud.lork.audit

import android.app.Application
import android.app.PendingIntent
import cloud.lork.audit.data.AuditStore
import cloud.lork.audit.db.AppDb
import cloud.lork.audit.db.KvEntity
import cloud.lork.audit.engine.LinkEvent
import cloud.lork.audit.engine.LinkPhase
import cloud.lork.audit.engine.LinkState
import cloud.lork.audit.engine.reduceLink
import cloud.lork.audit.google.AuthStep
import cloud.lork.audit.google.GoogleAuth
import cloud.lork.audit.google.SheetsRest
import cloud.lork.audit.send.recoverSendQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class LorkApp : Application() {
    lateinit var db: AppDb
        private set
    lateinit var auth: GoogleAuth
        private set
    lateinit var sheets: SheetsRest
        private set
    lateinit var store: AuditStore
        private set
    lateinit var session: GoogleSession
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        db = AppDb.create(this)
        auth = GoogleAuth(this)
        sheets = SheetsRest { auth.accessToken.orEmpty() }
        store = AuditStore(db.dao(), sheets)
        session = GoogleSession(this)
        session.load()
        recoverSendQueue(this)
    }
}

class GoogleSession(private val app: LorkApp) {
    val state = MutableStateFlow(LinkState())
    var launcher: ((PendingIntent) -> Unit)? = null
    private var attempt = 0
    private var pending = -1

    fun load() {
        val raw = app.db.dao().kv("link") ?: return
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val phase = runCatching { LinkPhase.valueOf(json.optString("phase")) }.getOrDefault(LinkPhase.NONE)
        val restored = LinkState(
            phase = if (phase == LinkPhase.WAITING_CONSENT) LinkPhase.AUTH_FAILED else phase,
            email = json.optString("email").ifBlank { null },
            message = if (phase == LinkPhase.WAITING_CONSENT) {
                "انقطع مسار الموافقة قبل اكتماله. أعد الربط من هذه الشاشة."
            } else {
                json.optString("message").ifBlank { "لم يكتمل الربط." }
            },
            diagnostic = json.optString("diagnostic"),
        )
        state.value = restored
    }

    fun begin() {
        pending = ++attempt
        app.scope.launch {
            state.value = reduceLink(state.value, LinkEvent.ConsentShown(state.value.email))
            persist()
            apply(app.auth.authorize())
        }
    }

    fun completeFromConsent() {
        if (attempt != pending) return
        app.scope.launch { apply(app.auth.authorize()) }
    }

    fun cancelFromConsent() {
        if (attempt != pending) return
        app.auth.clear()
        state.value = reduceLink(state.value, LinkEvent.Cancelled)
        persist()
    }

    fun note(event: LinkEvent) {
        state.value = reduceLink(state.value, event)
        persist()
    }

    private suspend fun apply(step: AuthStep) {
        if (attempt != pending) return
        when (step) {
            is AuthStep.NeedsConsent -> launcher?.invoke(step.pending)
            is AuthStep.Ready -> {
                val previous = state.value.email
                if (!previous.isNullOrBlank() && previous != step.email) {
                    state.value = reduceLink(state.value, LinkEvent.AccountSwitched)
                }
                state.value = reduceLink(state.value, LinkEvent.Authorized(step.email))
                persist()
            }
            is AuthStep.Failed -> {
                app.auth.clear()
                val event = if (step.diagnostic == "GS-CANCEL") {
                    LinkEvent.Cancelled
                } else {
                    LinkEvent.AuthFailed(step.diagnostic, step.message)
                }
                state.value = reduceLink(state.value, event)
                persist()
            }
        }
    }

    private fun persist() {
        val current = state.value
        app.db.dao().saveKv(
            KvEntity(
                "link",
                JSONObject()
                    .put("phase", current.phase.name)
                    .put("email", current.email.orEmpty())
                    .put("message", current.message)
                    .put("diagnostic", current.diagnostic)
                    .toString(),
            ),
        )
    }
}
