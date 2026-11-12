package cloud.lork.auditengine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

object XlsxWorkbook {
    data class Book(val sheets: List<NamedSheet>)
    data class NamedSheet(val name: String, val rows: List<List<String>>)

    private const val MAX_ROWS = 3000
    private const val MAX_COLS = 40

    fun read(bytes: ByteArray): Book {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    files[entry.name.removePrefix("/")] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val shared = readSharedStrings(files["xl/sharedStrings.xml"])
        val workbook = files["xl/workbook.xml"] ?: return Book(emptyList())
        val rels = files["xl/_rels/workbook.xml.rels"]
        val sheets = readSheetList(workbook)
        val targets = readRelationships(rels)
        val named = sheets.map { (name, relId) ->
            val target = targets[relId] ?: return@map NamedSheet(name, emptyList())
            val path = if (target.startsWith("/")) target.removePrefix("/") else "xl/$target"
            val xml = files[path] ?: files["xl/$target"]
            NamedSheet(name, if (xml == null) emptyList() else readSheet(xml, shared))
        }
        return Book(named)
    }

    fun write(sheets: List<NamedSheet>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(
                "[Content_Types].xml",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  ${sheets.indices.joinToString("") { i ->
                    """<Override PartName="/xl/worksheets/sheet${i + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
                }}
                </Types>
                """.trimIndent(),
            )
            put(
                "_rels/.rels",
                """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                </Relationships>
                """.trimIndent(),
            )
            put(
                "xl/workbook.xml",
                buildString {
                    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                    append("""<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""")
                    sheets.forEachIndexed { i, sheet ->
                        append("""<sheet name="${xml(sheet.name)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""")
                    }
                    append("</sheets></workbook>")
                },
            )
            put(
                "xl/_rels/workbook.xml.rels",
                buildString {
                    append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
                    append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
                    sheets.indices.forEach { i ->
                        append("""<Relationship Id="rId${i + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${i + 1}.xml"/>""")
                    }
                    append("</Relationships>")
                },
            )
            sheets.forEachIndexed { i, sheet ->
                put("xl/worksheets/sheet${i + 1}.xml", sheetXml(sheet.rows))
            }
        }
        return out.toByteArray()
    }

    private fun sheetXml(rows: List<List<String>>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { rIndex, row ->
            val cells = row.mapIndexedNotNull { cIndex, value ->
                if (value.isEmpty()) null else {
                    val ref = indexToColumnLetter(cIndex) + (rIndex + 1)
                    """<c r="$ref" t="inlineStr"><is><t>${xml(value)}</t></is></c>"""
                }
            }
            if (cells.isNotEmpty()) {
                append("""<row r="${rIndex + 1}">""")
                cells.forEach { append(it) }
                append("</row>")
            }
        }
        append("</sheetData></worksheet>")
    }

    private fun readSharedStrings(bytes: ByteArray?): List<String> {
        if (bytes == null) return emptyList()
        val doc = parse(bytes)
        val items = doc.getElementsByTagName("si")
        val values = ArrayList<String>(items.length)
        for (i in 0 until items.length) {
            val node = items.item(i) as Element
            val texts = node.getElementsByTagName("t")
            val combined = buildString {
                for (t in 0 until texts.length) append(texts.item(t).textContent ?: "")
            }
            values.add(combined)
        }
        return values
    }

    private fun readSheetList(bytes: ByteArray): List<Pair<String, String>> {
        val doc = parse(bytes)
        val nodes = doc.getElementsByTagName("sheet")
        val out = ArrayList<Pair<String, String>>(nodes.length)
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val name = el.getAttribute("name")
            val rel = el.getAttribute("r:id").ifEmpty { el.getAttribute("id") }
            out.add(name to rel)
        }
        return out
    }

    private fun readRelationships(bytes: ByteArray?): Map<String, String> {
        if (bytes == null) return emptyMap()
        val doc = parse(bytes)
        val nodes = doc.getElementsByTagName("Relationship")
        val out = HashMap<String, String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            out[el.getAttribute("Id")] = el.getAttribute("Target")
        }
        return out
    }

    private fun readSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
        val doc = parse(bytes)
        val cells = doc.getElementsByTagName("c")
        val grid = HashMap<Int, HashMap<Int, String>>()
        var maxRow = 0
        for (i in 0 until cells.length) {
            val el = cells.item(i) as Element
            val ref = el.getAttribute("r")
            val row = ref.dropWhile { it.isLetter() }.toIntOrNull()?.minus(1) ?: continue
            val col = columnLetterToIndex(ref.takeWhile { it.isLetter() }) ?: continue
            if (row !in 0 until MAX_ROWS || col !in 0 until MAX_COLS) continue
            val value = cellValue(el, shared)
            grid.getOrPut(row) { HashMap() }[col] = value
            if (row > maxRow) maxRow = row
        }
        if (grid.isEmpty()) return emptyList()
        val last = minOf(maxRow, MAX_ROWS - 1)
        return (0..last).map { r ->
            val cols = grid[r].orEmpty()
            val width = (cols.keys.maxOrNull() ?: -1) + 1
            List(width) { c -> cols[c].orEmpty() }
        }
    }

    private fun cellValue(el: Element, shared: List<String>): String {
        val type = el.getAttribute("t")
        if (type == "inlineStr") {
            val texts = el.getElementsByTagName("t")
            return buildString {
                for (i in 0 until texts.length) append(texts.item(i).textContent ?: "")
            }
        }
        val raw = directChildText(el, "v")
        if (type == "s") {
            val index = raw.toIntOrNull() ?: return ""
            return shared.getOrElse(index) { "" }
        }
        return raw
    }

    private fun directChildText(el: Element, tag: String): String {
        val children = el.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child is Element && child.tagName == tag) return child.textContent ?: ""
        }
        return ""
    }

    private fun parse(bytes: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) {
        }
        factory.setExpandEntityReferences(false)
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
