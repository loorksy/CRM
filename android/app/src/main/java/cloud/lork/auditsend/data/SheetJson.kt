package cloud.lork.auditsend.data

import org.json.JSONArray
import org.json.JSONObject

fun rowsToJson(rows: List<List<String>>): String {
    val array = JSONArray()
    rows.forEach { row ->
        val item = JSONArray()
        row.forEach { item.put(it) }
        array.put(item)
    }
    return array.toString()
}

fun jsonToRows(json: String?): List<List<String>> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val array = JSONArray(json)
        List(array.length()) { index ->
            val row = array.getJSONArray(index)
            List(row.length()) { cell -> row.optString(cell) }
        }
    } catch (_: Exception) {
        emptyList()
    }
}

fun colorsToJson(colors: Map<Int, String>): String {
    val obj = JSONObject()
    colors.forEach { (row, color) -> obj.put(row.toString(), color) }
    return obj.toString()
}

fun jsonToColors(json: String?): Map<Int, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return try {
        val obj = JSONObject(json)
        buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val row = key.toIntOrNull() ?: continue
                put(row, obj.optString(key))
            }
        }
    } catch (_: Exception) {
        emptyMap()
    }
}

fun blankUserInfo(): List<List<String>> {
    val header = MutableList(13) { "" }
    header[2] = "رقم المستخدم"
    header[3] = "الاسم"
    header[4] = "الهاتف"
    header[5] = "الرسالة"
    header[11] = "الراتب"
    header[12] = "الحالة"
    return listOf(header, MutableList(13) { "" })
}

fun blankManagement(): List<List<String>> = listOf(listOf("رقم المستخدم"), listOf(""))

fun blankAgent(): List<List<String>> {
    val header = MutableList(4) { "" }
    header[0] = "رقم المستخدم"
    header[3] = "الراتب"
    return listOf(header, MutableList(4) { "" })
}

fun blankRows(kind: String): List<List<String>> = when (kind) {
    SheetKind.MANAGEMENT -> blankManagement()
    SheetKind.AGENT -> blankAgent()
    else -> blankUserInfo()
}
