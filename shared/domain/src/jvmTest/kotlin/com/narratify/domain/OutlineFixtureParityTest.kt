package com.narratify.domain

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the outline heuristics against the fixtures iOS tests read as well. Only titles, depths
 * and order are shared: the two platforms index offsets into different strings, so each asserts
 * its own offsets. See `docs/superpowers/specs/2026-09-10-reader-outline-sidebar-design.md`.
 */
class OutlineFixtureParityTest {
    @Serializable
    private data class ExpectedItem(val title: String, val depth: Int)

    private val fixtures = File("../../test-fixtures/outline")

    @Test
    fun `every fixture produces the shared outline structure`() {
        val documents = fixtures.listFiles { file -> file.isFile }.orEmpty().sortedBy { it.name }
        assertTrue(documents.isNotEmpty(), "no fixtures found in ${fixtures.absolutePath}")

        for (document in documents) {
            val expectedFile = File(fixtures, "expected/${document.name}.json")
            assertTrue(expectedFile.isFile, "missing expectation for ${document.name}")
            val expected = Json.decodeFromString<List<ExpectedItem>>(expectedFile.readText())

            val parsed = PlainTextNormalizer.parse(
                document.name,
                document.readText(),
                markdown = document.extension == "md",
            )

            assertEquals(
                expected.map { it.title to it.depth },
                parsed.outline.map { it.title to it.depth },
                "outline structure differs for ${document.name}",
            )
            for (item in parsed.outline) {
                assertTrue(
                    parsed.text.startsWith(item.title, item.characterOffset),
                    "${document.name}: offset ${item.characterOffset} does not land on '${item.title}'",
                )
            }
        }
    }
}
