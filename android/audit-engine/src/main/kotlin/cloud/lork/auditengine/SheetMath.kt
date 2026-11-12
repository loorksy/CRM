package cloud.lork.auditengine

private val bidiAndMarks = Regex("[\\u200B-\\u200D\\u2060\\uFEFF\\u200E\\u200F\\u202A-\\u202E]")
private val leadingNumber = Regex("^[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?")

fun normalizeForNumber(str: String?): String {
    if (str == null) return ""
    var out = str.replace(bidiAndMarks, "").trim()
    val arabic = "٠١٢٣٤٥٦٧٨٩"
    val persian = "۰۱۲۳۴۵۶۷۸۹"
    val western = "0123456789"
    for (i in 0 until 10) {
        out = out.replace(arabic[i].toString(), western[i].toString())
            .replace(persian[i].toString(), western[i].toString())
    }
    return out.replace(Regex("[,،\\u066C\\s]"), "")
}

fun jsParseFloat(s: String): Double? {
    if (s.isEmpty()) return null
    val match = leadingNumber.find(s) ?: return null
    return match.value.toDoubleOrNull()
}

fun normalizeUserId(value: String?): String {
    val s = normalizeForNumber(value)
    if (s.isEmpty()) return ""
    val num = jsParseFloat(s)
    if (num != null && num.isFinite()) return kotlin.math.floor(num).toLong().toString()
    return s
}

fun columnLetterToIndex(letter: String?): Int? {
    if (letter.isNullOrEmpty()) return null
    val s = letter.trim().uppercase()
    var idx = 0
    for (ch in s) {
        val c = ch.code - 65
        if (c !in 0..25) return null
        idx = idx * 26 + (c + 1)
    }
    return idx - 1
}

fun indexToColumnLetter(index: Int): String {
    var n = index + 1
    val chars = StringBuilder()
    while (n > 0) {
        val rem = (n - 1) % 26
        chars.append(('A'.code + rem).toChar())
        n = (n - 1) / 26
    }
    return chars.reverse().toString()
}

fun isHeaderRowUserInfo(row: List<String>, colC: Int): Boolean {
    val c = normalizeForNumber(row.getOrElse(colC) { "" })
    val n = jsParseFloat(c)
    return c.isEmpty() || n == null || !n.isFinite()
}

fun isHeaderRowCycle(row: List<String>, colIndex: Int): Boolean {
    val first = normalizeForNumber(row.getOrElse(colIndex) { "" })
    if (first.isEmpty()) return true
    val n = jsParseFloat(first)
    return n == null || !n.isFinite()
}
