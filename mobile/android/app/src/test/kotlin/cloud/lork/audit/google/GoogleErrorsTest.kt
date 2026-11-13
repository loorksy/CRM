package cloud.lork.audit.google

import cloud.lork.audit.engine.DriveFile
import cloud.lork.audit.engine.DriveListResult
import cloud.lork.audit.engine.DrivePage
import cloud.lork.audit.engine.SourcePicker
import cloud.lork.audit.engine.SourcePickerContext
import cloud.lork.audit.engine.collectDriveFiles
import org.junit.Assert.*
import org.junit.Test

class GoogleErrorsTest {
    @Test
    fun sheetsApiDisabledIsNotReportedAsAnEmptyDriveListOrFilePermissionProblem() {
        val response = """{
            "error": {
                "code": 403,
                "message": "Google Sheets API has not been used in project 123 before or it is disabled.",
                "status": "PERMISSION_DENIED",
                "details": [{
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "SERVICE_DISABLED",
                    "domain": "googleapis.com",
                    "metadata": {"service": "sheets.googleapis.com", "consumer": "projects/123"}
                }]
            }
        }"""
        val error = sheetAccessFailure(403, response)
        assertEquals("403:SERVICE_DISABLED", error.code)
        assertTrue(error.message!!.contains("Google Sheets API"))
        assertFalse(error.message!!.contains("123"))
        assertFalse(error.message!!.contains("لا توجد صلاحية"))
    }

    @Test
    fun driveListingCanSucceedWhileSheetsMetadataHasAnActionableFailure() {
        val file = DriveFile("shared-file", "جدول الإدارة")
        val picker = SourcePicker()
        picker.open(SourcePickerContext("test@example.invalid", 1, "إدارة"))
        val filesRequest = picker.requestFiles()
        picker.filesLoaded(filesRequest, DriveListResult(listOf(file), null, "تم العثور على جدول"))
        val tabsRequest = picker.openFile(file)
        val error = sheetAccessFailure(403, """{"error":{"details":[{"reason":"SERVICE_DISABLED"}]}}""")
        picker.failed(tabsRequest, error.code, error.message.orEmpty())
        assertEquals(listOf(file), picker.state.files)
        assertEquals(file, picker.state.selectedFile)
        assertEquals("403:SERVICE_DISABLED", picker.state.errorCode)
        assertTrue(picker.state.errorMessage.contains("Google Sheets API"))
    }

    @Test
    fun driveAggregationKeepsSpecificApiErrorsVisible() {
        val error = sheetAccessFailure(403, """{"error":{"details":[{"reason":"SERVICE_DISABLED"}]}}""", "Google Drive")
        val result = collectDriveFiles { DrivePage(null, null, error.code, error.message) }
        assertEquals(error.code, result.errorCode)
        assertEquals(error.message, result.message)
    }

    @Test
    fun legacyDisabledApiReasonUsesTheRequestedService() {
        val response = """{"error":{"errors":[{"reason":"accessNotConfigured"}]}}"""
        val error = sheetAccessFailure(403, response, "Google Drive")
        assertEquals("403:SERVICE_DISABLED", error.code)
        assertTrue(error.message!!.contains("Google Drive API"))
        assertFalse(error.message!!.contains("Google Sheets"))
    }

    @Test
    fun insufficientOauthScopesAskForNewAuthorization() {
        val cases = listOf(
            """{"error":{"details":[{"reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}""",
            """{"error":{"errors":[{"reason":"insufficientPermissions"}]}}""",
        )
        for (body in cases) {
            val error = sheetAccessFailure(403, body)
            assertEquals("403:INSUFFICIENT_SCOPE", error.code)
            assertTrue(error.message!!.contains("أعد ربط الحساب"))
            assertTrue(error.message!!.contains("الصلاحيات"))
        }
    }

    @Test
    fun genericPermissionFailureDoesNotInventDisabledApiOrScopes() {
        val error = sheetAccessFailure(403, """{"error":{"status":"PERMISSION_DENIED","errors":[{"reason":"forbidden"}]}}""")
        assertEquals("403", error.code)
        assertTrue(error.message!!.contains("مشاركة الملف"))
        assertFalse(error.message!!.contains("غير مفعّلة"))
    }

    @Test
    fun expiredAuthorizationAndMissingFilesHaveDistinctActions() {
        assertEquals("401", sheetAccessFailure(401, "").code)
        assertTrue(sheetAccessFailure(401, "").message!!.contains("أعد ربط الحساب"))
        assertEquals("404", sheetAccessFailure(404, "").code)
        assertTrue(sheetAccessFailure(404, "").message!!.contains("الرابط والحساب"))
    }

    @Test
    fun malformedOrUntrustedBodiesNeverReachTheDisplayedError() {
        val secret = "ya29.private-token"
        val bodies = listOf(
            "not-json Bearer $secret",
            """{"error":{"message":"Bearer $secret access_token=private","details":[{"reason":"$secret"}]}}""",
            """{"error":{"details":[{"reason":{"secret":"$secret"}}],"errors":[null]}}""",
        )
        for (body in bodies) {
            for (status in listOf(400, 401, 403, 404, 429, 500)) {
                val error = sheetAccessFailure(status, body)
                assertFalse(error.message!!.contains(secret))
                assertFalse(error.message!!.contains("Bearer"))
                assertFalse(error.message!!.contains("access_token"))
            }
        }
    }

    @Test
    fun temporaryServerAndQuotaErrorsAreRetryableMessages() {
        assertTrue(sheetAccessFailure(503, "").message!!.contains("مؤقتاً"))
        assertEquals("429", sheetAccessFailure(429, "").code)
        assertEquals("403:RATE_LIMIT", sheetAccessFailure(403, """{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}""").code)
    }
}
