package com.qtekfun.ultimatecamera.core.luts.catalogue

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LutCatalogueTest {
    private val repoLuts = File("../luts")
    private val catalogueText = File(repoLuts, "catalog.json").readText()

    @Test
    fun repositoryCatalogueIsValidAndSeedsOppo() {
        val catalogue = LutCatalogue.parse(catalogueText)
        val oppo = catalogue.luts.filter { it.maker == "OPPO" }
        assertEquals(setOf("O-Log", "O-Log2"), oppo.map { it.inputCurve }.toSet())
        assertTrue(catalogue.find("oppo-o-log2-to-rec709") != null)
        assertTrue(catalogue.find("oppo-o-log-to-rec709") != null)
        for (lut in oppo) {
            // OPPO's terms allow non-commercial, unmodified use only: never bundled (REQ-COL-007/008).
            assertEquals(lut.id, LicenceState.LINK_ONLY, lut.licence)
            assertNull(lut.bundledPath)
            assertTrue(lut.download.url.startsWith("https://www.oppo.com/"))
            assertTrue(lut.fileName.endsWith(".cube"))
        }
    }

    @Test
    fun noLutFileIsCommittedUnlessARedistributableEntryNamesIt() {
        val catalogue = LutCatalogue.parse(catalogueText)
        val bundled = catalogue.luts.mapNotNull { it.bundledPath }.toSet()
        val lutFiles = repoLuts.walkTopDown().filter { it.isFile && it.extension.lowercase() in setOf("cube", "3dl", "zip") }
        for (file in lutFiles) {
            assertTrue("$file is not a bundled, redistributable catalogue LUT", file.relativeTo(repoLuts.parentFile).path in bundled)
        }
    }

    @Test
    fun schemaFileAgreesWithTheParser() {
        val schema = Json.parseToJsonElement(File(repoLuts, "catalog.schema.json").readText()).jsonObject
        val lut = schema["\$defs"]!!.jsonObject["lut"]!!.jsonObject
        val licences = lut["properties"]!!.jsonObject["licence"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("redistributable", "link-only", "unknown"), licences)
        val required = lut["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        val properties = lut["properties"]!!.jsonObject.keys
        // Every field of CatalogueLut is described, and the optional one is bundledPath.
        assertEquals(properties - "bundledPath", required)
        val version = schema["properties"]!!.jsonObject["schemaVersion"]!!.jsonObject["const"]!!.jsonPrimitive.content
        assertEquals(LutCatalogue.SCHEMA_VERSION.toString(), version)
    }

    @Test
    fun rejectsInvalidCatalogues() {
        fun fails(transform: (String) -> String, contains: String) {
            val e = assertThrows(CatalogueException::class.java) { LutCatalogue.parse(transform(catalogueText)) }
            assertTrue(e.message, e.message!!.contains(contains))
        }
        fails({ it.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2") }, "schemaVersion")
        fails({ it.replaceFirst("https://www.oppo.com/content", "http://www.oppo.com/content") }, "not an https URL")
        fails({ it.replaceFirst("7fdf0c40c849", "XXXX0c40c849") }, "sha256")
        fails({ it.replaceFirst("\"oppo-o-log2-to-rec2020-hlg\"", "\"oppo-o-log2-to-rec709\"") }, "duplicate id")
        fails({ it.replaceFirst("\"licence\": \"link-only\",", "\"licence\": \"link-only\", \"bundledPath\": \"luts/x.cube\",") }, "must not be bundled")
        fails({ it.replaceFirst("\"licence\": \"link-only\",", "\"licence\": \"redistributable\",") }, "bundled file")
        fails({ it.replaceFirst("\"licence\": \"link-only\"", "\"licence\": \"free\"") }, "not valid")
        fails({ it.replaceFirst("\"entryBytes\": 5081625", "\"entryBytes\": 5081625, \"extra\": 1") }, "not valid")
        fails({ it.replaceFirst("O-Log2-technical-transform-tools_v1/O-Log2-to-Rec709", "../O-Log2-to-Rec709") }, "unsafe archiveEntry")
        fails({ it.replaceFirst("\"format\": \"cube\"", "\"format\": \"3dl\"") }, "does not match format")
        fails({ it.replaceFirst("\"entrySha256\": \"6eed", "\"entrySha\": \"6eed") }, "not valid")
    }
}
