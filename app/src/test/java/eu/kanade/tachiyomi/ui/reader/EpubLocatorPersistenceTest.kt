package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.source.model.BookFingerprint
import eu.kanade.tachiyomi.source.model.BookLocator
import eu.kanade.tachiyomi.source.model.BookSection
import eu.kanade.tachiyomi.source.model.BookSectionContent
import eu.kanade.tachiyomi.source.model.BookTextQuote
import eu.kanade.tachiyomi.source.model.TextBookPublication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EpubLocatorPersistenceTest {

    @Test
    fun `normal reopen restores the href after a reflow`() {
        // Given: a locator saved before a section was inserted ahead of it.
        val persistence = EpubLocatorPersistence(InMemoryEpubLocatorStorage())
        persistence.persist(
            7L,
            publication("chapter-0.xhtml", "chapter-1.xhtml"),
            BookLocator.create(
                href = "chapter-1.xhtml#opening",
                cssSelector = "#opening",
            ),
            1,
            2,
            incognito = false,
        )

        // When: the same publication is reopened with a reflowed spine.
        val restored = persistence.restore(
            7L,
            publication("chapter-0.xhtml", "preface.xhtml", "chapter-1.xhtml"),
            sections(
                "chapter-0.xhtml",
                "preface.xhtml",
                "chapter-1.xhtml",
                html = "<p id=\"opening\">Chapter 1</p>",
            ),
            fallbackSectionIndex = 0,
        )

        // Then: its durable href wins over the stale section index.
        assertEquals(EpubRestoredLocation(sectionIndex = 2, fragment = "#opening"), restored)
    }

    @Test
    fun `changed spine falls back to quoted context after href and selector miss`() {
        // Given: a reflow removed the original spine entry and anchor.
        val persistence = EpubLocatorPersistence(InMemoryEpubLocatorStorage())
        persistence.persist(
            7L,
            publication("old.xhtml"),
            BookLocator.create(
                href = "old.xhtml",
                cssSelector = "#missing",
                textQuote = BookTextQuote(exact = "unique restored sentence"),
            ),
            0,
            1,
            incognito = false,
        )

        // When: the quote survives in a different spine entry.
        val restored = persistence.restore(
            7L,
            publication("new.xhtml", "moved.xhtml"),
            sections("new.xhtml", "moved.xhtml", html = "<p>unique restored sentence</p>"),
            fallbackSectionIndex = 0,
        )

        // Then: quoted context locates the moved content.
        assertEquals(EpubRestoredLocation(sectionIndex = 1), restored)
    }

    @Test
    fun `stale href fragment falls back instead of pinning a missing anchor`() {
        // Given: the stored href carries a fragment that a later reflow removed.
        val persistence = EpubLocatorPersistence(InMemoryEpubLocatorStorage())
        persistence.persist(
            7L,
            publication("chapter.xhtml"),
            BookLocator.create(
                href = "chapter.xhtml#gone",
                textQuote = BookTextQuote(exact = "unique restored sentence"),
            ),
            0,
            1,
            incognito = false,
        )

        // When: the href still matches but that section no longer contains the anchor.
        val restored = persistence.restore(
            7L,
            publication("chapter.xhtml", "other.xhtml"),
            sections("chapter.xhtml", "other.xhtml", html = "<p>unique restored sentence</p>"),
            fallbackSectionIndex = 0,
        )

        // Then: restoration keeps falling back instead of returning the dead fragment.
        assertEquals(EpubRestoredLocation(sectionIndex = 1), restored)
    }

    @Test
    fun `selector restores after href changes`() {
        // Given: reflow changed the section href but preserved an element id.
        val persistence = EpubLocatorPersistence(InMemoryEpubLocatorStorage())
        persistence.persist(
            7L,
            publication("old.xhtml"),
            BookLocator.create(
                href = "old.xhtml",
                cssSelector = "#target",
            ),
            0,
            1,
            incognito = false,
        )

        // When: the anchor survives in a replacement section.
        val restored = persistence.restore(
            7L,
            publication("replacement.xhtml"),
            sections("replacement.xhtml", html = "<p id=\"target\">Moved</p>"),
            0,
        )

        // Then: selector resolution precedes section-hint fallback.
        assertEquals(EpubRestoredLocation(sectionIndex = 0, fragment = "#target"), restored)
    }

    @Test
    fun `fingerprint mismatch rejects stale locator before href lookup`() {
        // Given: a record for an earlier publication version.
        val persistence = EpubLocatorPersistence(InMemoryEpubLocatorStorage())
        persistence.persist(
            7L,
            publication("chapter.xhtml", fingerprint = "old"),
            BookLocator.create(
                href = "chapter.xhtml#opening",
                cssSelector = "#opening",
            ),
            0,
            1,
            incognito = false,
        )

        // When: the chapter opens with a new publication fingerprint.
        val restored = persistence.restore(
            7L,
            publication("chapter.xhtml", fingerprint = "new"),
            sections("chapter.xhtml", html = "<p id=\"opening\">New</p>"),
            1,
        )

        // Then: the local reader hint is used without restoring stale fragment data.
        assertEquals(EpubRestoredLocation(sectionIndex = 0), restored)
    }

    @Test
    fun `invalid stored locator starts at the first current section`() {
        // Given: malformed persisted JSON from an old or interrupted local write.
        val storage = InMemoryEpubLocatorStorage().apply { put("chapter-7", "{not-json") }
        val persistence = EpubLocatorPersistence(storage)

        // When: a shorter publication is opened.
        val restored = persistence.restore(
            7L,
            publication("first.xhtml", "last.xhtml"),
            sections("first.xhtml", "last.xhtml"),
            99,
        )

        // Then: no untrusted locator is used to select a section.
        assertEquals(EpubRestoredLocation(sectionIndex = 0), restored)
    }

    @Test
    fun `refreshed section count clamps a stale section hint`() {
        // Given: locator details no longer resolve after the source refreshed its spine.
        val persistence = EpubLocatorPersistence(InMemoryEpubLocatorStorage())
        persistence.persist(
            7L,
            publication("old.xhtml", "old-2.xhtml"),
            BookLocator.create(
                href = "missing.xhtml",
                cssSelector = "#missing",
                totalProgression = 1.0,
            ),
            9,
            10,
            incognito = false,
        )

        // When: the refreshed publication now has three sections.
        val restored = persistence.restore(
            7L,
            publication("one.xhtml", "two.xhtml", "three.xhtml"),
            sections("one.xhtml", "two.xhtml", "three.xhtml"),
            0,
        )

        // Then: the persisted index is clamped against the refreshed count.
        assertEquals(EpubRestoredLocation(sectionIndex = 2), restored)
    }

    @Test
    fun `incognito navigation does not write a locator`() {
        // Given: an incognito reader and a write-counting local store.
        val storage = InMemoryEpubLocatorStorage()
        val persistence = EpubLocatorPersistence(storage)

        // When: navigation settles.
        persistence.persist(
            7L,
            publication("chapter.xhtml"),
            BookLocator.create(
                href = "chapter.xhtml",
                cssSelector = "body",
            ),
            0,
            1,
            incognito = true,
        )

        // Then: no durable record is created.
        assertEquals(0, storage.writeCount)
        assertNull(storage.read("chapter-7"))
    }

    @Test
    fun `hostile locator fields are inert`() {
        // Given: a hostile stored locator with no valid href or fragment.
        val persistence = EpubLocatorPersistence(InMemoryEpubLocatorStorage())
        persistence.persist(
            7L,
            publication("safe.xhtml"),
            BookLocator.create(
                href = "javascript:alert(1)",
                cssSelector = "#<img src=x onerror=alert(1)>",
                textQuote = BookTextQuote(exact = "<script>alert(1)</script>"),
            ),
            0,
            1,
            incognito = false,
        )

        // When: it is restored into a safe local section.
        val restored = persistence.restore(7L, publication("safe.xhtml"), sections("safe.xhtml"), 0)

        // Then: no untrusted field becomes a WebView fragment or section selection.
        assertEquals(EpubRestoredLocation(sectionIndex = 0), restored)
        assertTrue(restored?.fragment == null)
    }

    @Test
    fun `stored locator without block id remains decodable`() {
        val storage = InMemoryEpubLocatorStorage().apply {
            put(
                "chapter-7",
                """
                    {
                      "fingerprint":"publication-1",
                      "locator":{"href":"chapter.xhtml#opening","cssSelector":"#opening"},
                      "sectionIndex":0,
                      "sectionCount":1
                    }
                """.trimIndent(),
            )
        }

        val restored = EpubLocatorPersistence(storage).restore(
            7L,
            publication("chapter.xhtml"),
            sections("chapter.xhtml", html = "<h1 id=\"opening\">Opening</h1>"),
            0,
        )

        assertEquals(EpubRestoredLocation(0, "#opening"), restored)
    }

    @Test
    fun `block id round trips and wins over legacy anchor and quote`() {
        val storage = InMemoryEpubLocatorStorage()
        val persistence = EpubLocatorPersistence(storage)
        val publication = publication("first.xhtml", "quoted.xhtml")
        persistence.persist(
            chapterId = 7L,
            publication = publication,
            locator = BookLocator.create(
                href = "first.xhtml#legacy",
                blockId = "mk-b-1",
                cssSelector = "#legacy",
                textQuote = BookTextQuote("quote in another section"),
                progression = 0.75,
                totalProgression = 1.0,
            ),
            sectionIndex = 0,
            sectionCount = 2,
            incognito = false,
        )

        val restored = persistence.restore(
            7L,
            publication,
            listOf(
                BookSectionContent(
                    "section-0",
                    "first.xhtml",
                    "<h1 id=\"legacy\">Opening</h1><p id=\"mk-b-1\">Target</p>",
                ),
                BookSectionContent("section-1", "quoted.xhtml", "<p>quote in another section</p>"),
            ),
            1,
        )

        assertEquals(EpubRestoredLocation(0, "#mk-b-1"), restored)
        val encodedLocator = Json.parseToJsonElement(storage.read("chapter-7")!!)
            .jsonObject.getValue("locator").jsonObject
        assertEquals("mk-b-1", encodedLocator.getValue("blockId").jsonPrimitive.content)
    }

    @Test
    fun `section progression maps to a block before total progression and section hint`() {
        val persistence = EpubLocatorPersistence(InMemoryEpubLocatorStorage())
        val publication = publication("first.xhtml", "second.xhtml")
        persistence.persist(
            chapterId = 7L,
            publication = publication,
            locator = BookLocator.create(
                href = "first.xhtml",
                blockId = "missing-block",
                cssSelector = "#missing-anchor",
                textQuote = BookTextQuote("missing quote"),
                progression = 0.8,
                totalProgression = 1.0,
            ),
            sectionIndex = 1,
            sectionCount = 2,
            incognito = false,
        )

        val restored = persistence.restore(
            7L,
            publication,
            listOf(
                BookSectionContent(
                    "section-0",
                    "first.xhtml",
                    "<p id=\"mk-b-0\">Short</p><p id=\"mk-b-1\">A much longer target paragraph</p>",
                ),
                BookSectionContent("section-1", "second.xhtml", "<p id=\"mk-b-0\">Last section</p>"),
            ),
            1,
        )

        assertEquals(EpubRestoredLocation(0, "#mk-b-1"), restored)
    }

    @Test
    fun `locator creation records actual section progression and current block context`() {
        val locator = createEpubLocator(
            section = BookSectionContent(
                "section-0",
                "chapter.xhtml",
                "<p id=\"mk-b-0\">Opening</p><p id=\"mk-b-1\">Current paragraph text</p>",
            ),
            sectionIndex = 0,
            sectionCount = 1,
            blockId = "mk-b-1",
            progression = 0.625,
        )

        assertEquals("mk-b-1", locator.blockId)
        assertEquals(0.625, locator.progression)
        assertEquals("chapter.xhtml#mk-b-1", locator.href)
        assertEquals("Current paragraph text", locator.textQuote?.exact)
    }

    private fun publication(vararg hrefs: String, fingerprint: String = "publication-1") = TextBookPublication(
        fingerprint = BookFingerprint(fingerprint),
        spine = hrefs.mapIndexed { index, href -> BookSection("section-$index", href) },
        tableOfContents = emptyList(),
    )

    private fun sections(vararg hrefs: String, html: String = "<p>Section</p>") = hrefs.mapIndexed { index, href ->
        BookSectionContent("section-$index", href, if (index == hrefs.lastIndex) html else "<p>Section $index</p>")
    }

    private class InMemoryEpubLocatorStorage : EpubLocatorStorage {
        private val values = mutableMapOf<String, String>()
        var writeCount = 0
            private set

        override fun read(key: String): String? = values[key]

        override fun write(key: String, value: String) {
            writeCount++
            values[key] = value
        }

        fun put(key: String, value: String) {
            values[key] = value
        }
    }
}
