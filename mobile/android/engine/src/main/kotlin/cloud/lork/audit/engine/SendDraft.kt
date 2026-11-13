package cloud.lork.audit.engine

/** A new batch must never silently discard a running, paused, or uncertain batch. */
fun sendQueueReplacementError(session: SendSessionState, items: List<SendItem>): String? {
    if (items.any { it.status == "UNCERTAIN" || it.status == "EXECUTION_STARTED" }) {
        return "توجد رسالة غير مؤكدة. راجعها قبل إنشاء دفعة جديدة."
    }
    if (session.state == "SENDING" || session.state == "WAITING_CHAT") {
        return "الإرسال جارٍ. أوقف الدفعة الحالية قبل إنشاء دفعة جديدة."
    }
    if (items.any { it.status !in setOf("SEND_PRESSED", "SKIPPED", "FAILED") }) {
        return "توجد دفعة محفوظة لم تنتهِ. استأنفها أو أنهِها قبل إنشاء دفعة جديدة."
    }
    return null
}
