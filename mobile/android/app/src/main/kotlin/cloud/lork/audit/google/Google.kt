package cloud.lork.audit.google

import android.app.PendingIntent
import android.content.Context
import cloud.lork.audit.engine.DriveFile
import cloud.lork.audit.engine.DrivePage
import cloud.lork.audit.engine.SheetAccessException
import cloud.lork.audit.engine.SheetGateway
import cloud.lork.audit.engine.collectDriveFiles
import cloud.lork.audit.engine.quoteSheet
import cloud.lork.audit.engine.safeDiagnostic
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

sealed class AuthStep {
    data class Ready(val email: String, val token: String) : AuthStep()
    data class NeedsConsent(val pending: PendingIntent) : AuthStep()
    data class Failed(val diagnostic: String, val message: String) : AuthStep()
}

class GoogleAuth(context: Context) {
    private val app = context.applicationContext
    private val client = Identity.getAuthorizationClient(app)
    private val scopes = listOf(
        Scope(SHEETS_SCOPE),
        Scope(DRIVE_SCOPE),
    )

    @Volatile
    var accessToken: String? = null
        private set

    fun clear() {
        accessToken = null
    }

    fun playServicesStatus(): AuthStep? {
        val code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app)
        if (code == ConnectionResult.SUCCESS) return null
        return AuthStep.Failed(
            "GS-PLAY-$code",
            "خدمات Google Play غير متاحة على هذا الجهاز. التدقيق على البيانات المحفوظة يبقى ممكناً.",
        )
    }

    suspend fun authorize(): AuthStep = withContext(Dispatchers.IO) {
        playServicesStatus()?.let { return@withContext it }
        try {
            val result = client.authorize(request()).await()
            if (result.hasResolution()) {
                val pending = result.pendingIntent
                    ?: return@withContext AuthStep.Failed("GS-NO-UI", "تعذر فتح شاشة الموافقة.")
                return@withContext AuthStep.NeedsConsent(pending)
            }
            val token = result.accessToken
            if (token.isNullOrBlank()) {
                return@withContext AuthStep.Failed("GS-NO-TOKEN", "لم يُرجع Google رمز وصول. اختيار الحساب لا يكفي.")
            }
            accessToken = token
            val email = readEmail(token)
            AuthStep.Ready(email, token)
        } catch (e: ApiException) {
            accessToken = null
            mapApi(e)
        } catch (e: Exception) {
            accessToken = null
            AuthStep.Failed(safeDiagnostic("GS-AUTH", e.message), "تعذر إكمال تفويض Google.")
        }
    }

    private fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(scopes)
        .build()

    private fun mapApi(e: ApiException): AuthStep {
        val code = e.statusCode
        val message = when (code) {
            CommonStatusCodes.CANCELED, 12501 -> "أُلغيت الموافقة قبل اكتمال الربط."
            CommonStatusCodes.NETWORK_ERROR -> "انقطع الاتصال أثناء التفويض."
            CommonStatusCodes.TIMEOUT -> "تأخرت استجابة التفويض."
            7 -> "انقطع الاتصال أثناء التفويض."
            else -> "فشل تفويض Google. رمز الحالة $code."
        }
        val diagnostic = if (code == CommonStatusCodes.CANCELED || code == 12501) "GS-CANCEL" else "GS-API-$code"
        return AuthStep.Failed(diagnostic, message)
    }

    private fun readEmail(token: String): String {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/about?fields=user(emailAddress,displayName)")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw SheetAccessException(response.code.toString(), "تعذر قراءة هوية الحساب بعد التفويض.")
            }
            val user = JSONObject(body).optJSONObject("user")
            return user?.optString("emailAddress").orEmpty().ifBlank {
                user?.optString("displayName").orEmpty().ifBlank { "حساب Google" }
            }
        }
    }

    companion object {
        const val SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets"
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    }
}

class SheetsRest(private val token: () -> String) : SheetGateway {
    private val http = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun listSpreadsheetPage(pageToken: String?): DrivePage {
        val query = URLEncoder.encode("mimeType='application/vnd.google-apps.spreadsheet' and trashed=false", "UTF-8")
        val page = if (pageToken.isNullOrEmpty()) "" else "&pageToken=" + URLEncoder.encode(pageToken, "UTF-8")
        val url = "https://www.googleapis.com/drive/v3/files?q=$query&fields=nextPageToken,files(id,name)&pageSize=100" +
            "&supportsAllDrives=true&includeItemsFromAllDrives=true$page"
        return try {
            val body = get(url)
            val files = body.optJSONArray("files") ?: JSONArray()
            val list = buildList {
                for (i in 0 until files.length()) {
                    val file = files.getJSONObject(i)
                    add(DriveFile(file.getString("id"), file.optString("name")))
                }
            }
            DrivePage(list, body.optString("nextPageToken").ifBlank { null }, null, null)
        } catch (e: SheetAccessException) {
            DrivePage(null, null, e.code, e.message)
        }
    }

    fun listAllSpreadsheets() = collectDriveFiles { listSpreadsheetPage(it) }

    fun listSheetTitles(spreadsheetId: String): List<String> = meta(spreadsheetId).map { it.first }

    override fun listSheets(spreadsheetId: String): List<String> = listSheetTitles(spreadsheetId)

    override fun read(spreadsheetId: String, title: String): List<List<String>> {
        val range = encode(quoteSheet(title) + "!A:ZZ")
        val body = get("https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId/values/$range?valueRenderOption=FORMATTED_VALUE")
        val values = body.optJSONArray("values") ?: return emptyList()
        return buildList {
            for (i in 0 until values.length()) {
                val row = values.optJSONArray(i) ?: JSONArray()
                add(buildList {
                    for (j in 0 until row.length()) add(if (row.isNull(j)) "" else row.get(j).toString())
                })
            }
        }
    }

    override fun addSheets(spreadsheetId: String, titles: List<String>) {
        val requests = JSONArray()
        titles.forEach { title ->
            requests.put(JSONObject().put("addSheet", JSONObject().put("properties", JSONObject().put("title", title))))
        }
        post(
            "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId:batchUpdate",
            JSONObject().put("requests", requests),
        )
    }

    override fun updateRange(spreadsheetId: String, a1: String, values: List<List<String>>) {
        val range = encode(a1)
        val body = JSONObject()
            .put("range", a1)
            .put("majorDimension", "ROWS")
            .put("values", toJson(values))
        put("https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId/values/$range?valueInputOption=USER_ENTERED", body)
    }

    override fun append(spreadsheetId: String, a1: String, values: List<List<String>>) {
        val range = encode(a1)
        val body = JSONObject().put("majorDimension", "ROWS").put("values", toJson(values))
        post(
            "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId/values/$range:append?valueInputOption=USER_ENTERED&insertDataOption=INSERT_ROWS",
            body,
        )
    }

    override fun colorRows(spreadsheetId: String, title: String, zeroBasedRows: List<Int>, hex: String) {
        val sheetId = meta(spreadsheetId).firstOrNull { it.first == title }?.second
            ?: throw SheetAccessException("404", "الورقة «$title» غير موجودة للتلوين")
        val requests = JSONArray()
        zeroBasedRows.distinct().forEach { row ->
            requests.put(
                JSONObject().put(
                    "repeatCell",
                    JSONObject()
                        .put(
                            "range",
                            JSONObject()
                                .put("sheetId", sheetId)
                                .put("startRowIndex", row)
                                .put("endRowIndex", row + 1)
                                .put("startColumnIndex", 0)
                                .put("endColumnIndex", 26),
                        )
                        .put(
                            "cell",
                            JSONObject().put(
                                "userEnteredFormat",
                                JSONObject().put("backgroundColor", hexJson(hex)),
                            ),
                        )
                        .put("fields", "userEnteredFormat.backgroundColor"),
                ),
            )
        }
        if (requests.length() == 0) return
        post("https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId:batchUpdate", JSONObject().put("requests", requests))
    }

    override fun readBackgrounds(spreadsheetId: String, title: String): Map<Int, String> {
        val range = encode(quoteSheet(title) + "!A1:A5000")
        val url = "https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId?includeGridData=true&ranges=$range" +
            "&fields=sheets(data(rowData(values(userEnteredFormat(backgroundColor)))))"
        val body = get(url)
        val sheets = body.optJSONArray("sheets") ?: return emptyMap()
        if (sheets.length() == 0) return emptyMap()
        val data = sheets.getJSONObject(0).optJSONArray("data") ?: return emptyMap()
        if (data.length() == 0) return emptyMap()
        val rowData = data.getJSONObject(0).optJSONArray("rowData") ?: return emptyMap()
        val out = linkedMapOf<Int, String>()
        for (i in 0 until rowData.length()) {
            val values = rowData.optJSONObject(i)?.optJSONArray("values") ?: continue
            val bg = values.optJSONObject(0)?.optJSONObject("userEnteredFormat")?.optJSONObject("backgroundColor") ?: continue
            out[i] = rgbToHex(bg)
        }
        return out
    }

    private fun meta(spreadsheetId: String): List<Pair<String, Int>> {
        val body = get("https://sheets.googleapis.com/v4/spreadsheets/$spreadsheetId?fields=sheets.properties(title,sheetId)")
        val sheets = body.optJSONArray("sheets") ?: JSONArray()
        return buildList {
            for (i in 0 until sheets.length()) {
                val props = sheets.getJSONObject(i).getJSONObject("properties")
                add(props.getString("title") to props.getInt("sheetId"))
            }
        }
    }

    private fun get(url: String): JSONObject = call(Request.Builder().url(url).get())

    private fun post(url: String, body: JSONObject): JSONObject =
        call(Request.Builder().url(url).post(body.toString().toRequestBody(jsonType)))

    private fun put(url: String, body: JSONObject): JSONObject =
        call(Request.Builder().url(url).put(body.toString().toRequestBody(jsonType)))

    private fun call(builder: Request.Builder): JSONObject {
        val tokenValue = try {
            token()
        } catch (e: Exception) {
            throw SheetAccessException("401", "لا يوجد تفويض صالح.")
        }
        if (tokenValue.isBlank()) throw SheetAccessException("401", "لا يوجد تفويض صالح.")
        val request = builder.header("Authorization", "Bearer $tokenValue").build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val service = if (response.request.url.host == "www.googleapis.com" &&
                        response.request.url.encodedPath.startsWith("/drive/")) "Google Drive" else "Google Sheets"
                    throw sheetAccessFailure(response.code, text, service)
                }
                if (text.isBlank()) return JSONObject()
                return JSONObject(text)
            }
        } catch (e: SheetAccessException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw SheetAccessException("timeout", "تأخرت استجابة Google.")
        } catch (e: IOException) {
            throw SheetAccessException("network", "انقطع الاتصال أثناء المزامنة.")
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun toJson(values: List<List<String>>): JSONArray = JSONArray().apply {
        values.forEach { row -> put(JSONArray().apply { row.forEach { put(it) } }) }
    }

    private fun hexJson(hex: String): JSONObject {
        val h = hex.removePrefix("#")
        fun ch(start: Int) = h.substring(start, start + 2).toInt(16) / 255.0
        return JSONObject().put("red", ch(0)).put("green", ch(2)).put("blue", ch(4))
    }

    private fun rgbToHex(color: JSONObject): String {
        fun ch(name: String) = (color.optDouble(name, 0.0) * 255).roundToInt().coerceIn(0, 255)
        return "#%02x%02x%02x".format(ch("red"), ch("green"), ch("blue"))
    }
}

/**
 * Classify only structured Google reasons. Raw response messages may contain
 * account/project information or credentials and must never be displayed.
 */
internal fun sheetAccessFailure(status: Int, body: String, serviceLabel: String = "Google Sheets"): SheetAccessException {
    val service = if (serviceLabel == "Google Drive") "Google Drive" else "Google Sheets"
    val reasons = mutableSetOf<String>()
    runCatching {
        val error = JSONObject(body).optJSONObject("error") ?: return@runCatching
        for (key in listOf("details", "errors")) {
            val entries = error.optJSONArray(key) ?: continue
            for (index in 0 until entries.length()) {
                val reason = entries.optJSONObject(index)?.opt("reason") as? String ?: continue
                reasons += reason.replace("_", "").replace("-", "").lowercase()
            }
        }
    }
    if (status == 401) return SheetAccessException("401", "انتهت صلاحية تفويض Google. أعد ربط الحساب ثم حاول مجدداً.")
    if (status == 404) return SheetAccessException("404", "الملف أو الورقة غير متاحة. تحقّق من الرابط والحساب وصلاحية المشاركة.")
    if (status == 403) {
        if (reasons.any { it in setOf("servicedisabled", "accessnotconfigured") }) {
            return SheetAccessException("403:SERVICE_DISABLED",
                "خدمة $service غير مفعّلة لهذا التطبيق. فعّل $service API في إعدادات Google Cloud ثم أعد المحاولة.")
        }
        if (reasons.any { it in setOf("accesstokenscopeinsufficient", "insufficientpermissions", "insufficientscope") }) {
            return SheetAccessException("403:INSUFFICIENT_SCOPE",
                "صلاحيات Google الممنوحة لا تكفي للوصول إلى $service. أعد ربط الحساب ووافق على الصلاحيات المطلوبة.")
        }
        if (reasons.any { it in setOf("ratelimitexceeded", "userratelimitexceeded", "quotaexceeded", "resourceexhausted") }) {
            return SheetAccessException("403:RATE_LIMIT", "تم تجاوز حد طلبات $service. انتظر قليلاً ثم أعد المحاولة.")
        }
        return SheetAccessException("403", "لا توجد صلاحية للوصول إلى هذا الملف أو هذه العملية. تحقّق من الحساب ومشاركة الملف.")
    }
    if (status == 429) return SheetAccessException("429", "طلبات كثيرة إلى $service. انتظر قليلاً ثم أعد المحاولة.")
    if (status in 500..599) return SheetAccessException(status.toString(), "تعذرت استجابة $service مؤقتاً. أعد المحاولة لاحقاً.")
    return SheetAccessException(status.toString(), "رفضت $service الطلب ($status). تحقّق من الرابط ثم أعد المحاولة.")
}
