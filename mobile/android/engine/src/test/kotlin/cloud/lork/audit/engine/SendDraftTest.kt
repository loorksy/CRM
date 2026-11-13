package cloud.lork.audit.engine

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SendDraftTest {
    private fun session(state: String) = SendSessionState(state, 0, 100, null)
    private fun items(vararg states: String) = states.mapIndexed { index, status ->
        SendItem("m$index", index, "رسالة $index", status)
    }

    @Test
    fun composerCanStartFirstBatchAndExplicitlyResendCompletedBatch() {
        assertNull(sendQueueReplacementError(session("IDLE"), emptyList()))
        assertNull(sendQueueReplacementError(session("DONE"), items("SEND_PRESSED", "SEND_PRESSED")))
        assertNull(sendQueueReplacementError(session("STOPPED"), items("SEND_PRESSED", "SKIPPED")))
    }

    @Test
    fun editingMustNotReplaceAnActiveOrPausedQueue() {
        assertNotNull(sendQueueReplacementError(session("SENDING"), items("READY")))
        assertNotNull(sendQueueReplacementError(session("WAITING_CHAT"), emptyList()))
        assertNotNull(sendQueueReplacementError(session("PAUSED"), items("SEND_PRESSED", "TEXT_PREPARED", "READY")))
    }

    @Test
    fun uncertaintySurvivesStopAndCannotBeHiddenByStartingAnotherBatch() {
        for (state in listOf("PAUSED", "STOPPED", "DONE")) {
            assertNotNull(sendQueueReplacementError(session(state), items("SEND_PRESSED", "UNCERTAIN")))
            assertNotNull(sendQueueReplacementError(session(state), items("EXECUTION_STARTED")))
        }
    }
}
