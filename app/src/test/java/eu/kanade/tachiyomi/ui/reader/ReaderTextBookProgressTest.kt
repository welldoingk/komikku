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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ReaderTextBookProgressTest {

    @Test
    fun `normal reopen resolves the same locator at section 2 after reflow`() {
        val storage = InMemoryEpubLocatorStorage()
        val persistence = EpubLocatorPersistence(storage)
        val publication = publication("chapter-1.xhtml", "chapter-2.xhtml")
        val settledSection = section(
            "section-1",
            "chapter-2.xhtml",
            "<h2 id='opening'>Second section prose remains stable across layout reflow.</h2>",
        )

        val locator = createEpubLocator(settledSection, sectionIndex = 1, sectionCount = 2)
        persistence.persist(7L, publication, locator, sectionIndex = 1, sectionCount = 2, incognito = false)
        val restored = persistence.restore(
            chapterId = 7L,
            publication = publication,
            sections = listOf(
                section("section-0", "chapter-1.xhtml", "<p>First</p>"),
                section(
                    "section-1",
                    "chapter-2.xhtml",
                    "<article><div><h2 id='opening'>Second section prose remains stable across layout reflow.</h2></div></article>",
                ),
            ),
            fallbackSectionIndex = 0,
        )

        assertEquals(1, restored?.sectionIndex, "section 2 is the second spine item (zero-based index 1)")
        assertEquals("#opening", restored?.fragment)
        val encoded = storage.valueFor(7L)
        assertNotNull(encoded)
        val stored = Json.parseToJsonElement(encoded!!).jsonObject
        assertEquals("publication-1", stored.getValue("fingerprint").jsonPrimitive.content)
        val storedLocator = stored.getValue("locator").jsonObject
        assertEquals("chapter-2.xhtml#opening", storedLocator.getValue("href").jsonPrimitive.content)
        assertEquals("#opening", storedLocator.getValue("cssSelector").jsonPrimitive.content)
        assertTrue(storedLocator.getValue("textQuote").jsonObject.getValue("exact").jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun `changed spine markup falls back deterministically to quoted context`() {
        val storage = InMemoryEpubLocatorStorage()
        val persistence = EpubLocatorPersistence(storage)
        val publication = publication("old-intro.xhtml", "old-target.xhtml")
        val targetText = "A durable quoted passage identifies the logical target after markup and href changes."
        val locator = createEpubLocator(
            section("section-1", "old-target.xhtml", "<p id='target'>$targetText</p>"),
            sectionIndex = 1,
            sectionCount = 2,
        )
        persistence.persist(8L, publication, locator, sectionIndex = 1, sectionCount = 2, incognito = false)

        val restored = persistence.restore(
            chapterId = 8L,
            publication = publication("renamed-intro.xhtml", "renamed-target.xhtml"),
            sections = listOf(
                section("new-0", "renamed-intro.xhtml", "<main>Different prose</main>"),
                section("new-1", "renamed-target.xhtml", "<article><em>$targetText</em></article>"),
            ),
            fallbackSectionIndex = 0,
        )

        assertEquals(EpubRestoredLocation(1), restored)
    }

    @Test
    fun `quote prefix and suffix deterministically disambiguate repeated exact text`() {
        val storage = InMemoryEpubLocatorStorage()
        val persistence = EpubLocatorPersistence(storage)
        val publication = publication("first.xhtml", "second.xhtml")
        persistence.persist(
            chapterId = 81L,
            publication = publication,
            locator = BookLocator.create(
                href = "missing.xhtml",
                cssSelector = "#missing",
                textQuote = BookTextQuote(
                    exact = "shared phrase",
                    prefix = "Target prefix ",
                    suffix = " target suffix",
                ),
            ),
            sectionIndex = 0,
            sectionCount = 2,
            incognito = false,
        )

        val restored = persistence.restore(
            81L,
            publication,
            listOf(
                section("s0", "first.xhtml", "<p>Wrong prefix shared phrase wrong suffix</p>"),
                section("s1", "second.xhtml", "<p>Target prefix shared phrase target suffix</p>"),
            ),
            0,
        )

        assertEquals(EpubRestoredLocation(1), restored)
    }

    @Test
    fun `restore order prefers href then selector then quote`() {
        val sections = listOf(
            section("s0", "first.xhtml", "<p id='selector-target'>Quoted target</p>"),
            section("s1", "second.xhtml", "<p id='href-target'>Href target</p>"),
        )
        val publication = publication("first.xhtml", "second.xhtml")

        val hrefStorage = InMemoryEpubLocatorStorage()
        EpubLocatorPersistence(hrefStorage).persist(
            chapterId = 9L,
            publication = publication,
            locator = BookLocator.create(
                href = "second.xhtml#href-target",
                cssSelector = "#selector-target",
                textQuote = BookTextQuote("Quoted target"),
                totalProgression = 0.0,
            ),
            sectionIndex = 0,
            sectionCount = 2,
            incognito = false,
        )
        assertEquals(1, EpubLocatorPersistence(hrefStorage).restore(9L, publication, sections, 0)?.sectionIndex)

        val selectorStorage = InMemoryEpubLocatorStorage()
        EpubLocatorPersistence(selectorStorage).persist(
            chapterId = 10L,
            publication = publication,
            locator = BookLocator.create(
                href = "missing.xhtml",
                cssSelector = "#href-target",
                textQuote = BookTextQuote("Quoted target"),
                totalProgression = 0.0,
            ),
            sectionIndex = 0,
            sectionCount = 2,
            incognito = false,
        )
        assertEquals(1, EpubLocatorPersistence(selectorStorage).restore(10L, publication, sections, 0)?.sectionIndex)
    }

    @Test
    fun `invalid stored section clamps to the first valid section`() {
        val storage = InMemoryEpubLocatorStorage()
        val persistence = EpubLocatorPersistence(storage)
        val publication = publication("first.xhtml", "second.xhtml")
        persistence.persist(
            chapterId = 11L,
            publication = publication,
            locator = BookLocator.create(href = "missing.xhtml", cssSelector = "#missing"),
            sectionIndex = 99,
            sectionCount = 100,
            incognito = false,
        )

        val restored = persistence.restore(11L, publication, sections("first.xhtml", "second.xhtml"), 99)

        assertEquals(EpubRestoredLocation(0), restored)
    }

    @Test
    fun `refreshed section count uses clamped total progression before stale section index`() {
        val storage = InMemoryEpubLocatorStorage()
        val persistence = EpubLocatorPersistence(storage)
        val oldPublication = publication("a.xhtml", "b.xhtml", "c.xhtml", "d.xhtml", "old-end.xhtml")
        persistence.persist(
            chapterId = 12L,
            publication = oldPublication,
            locator = BookLocator.create(
                href = "missing.xhtml",
                cssSelector = "#missing",
                totalProgression = 1.0,
            ),
            sectionIndex = 4,
            sectionCount = 5,
            incognito = false,
        )

        val refreshed = sections("new-a.xhtml", "new-b.xhtml", "new-end.xhtml")
        val restored = persistence.restore(
            12L,
            publication("new-a.xhtml", "new-b.xhtml", "new-end.xhtml"),
            refreshed,
            0,
        )

        assertEquals(EpubRestoredLocation(2), restored)
    }

    @Test
    fun `incognito navigation persists nothing`() {
        val storage = InMemoryEpubLocatorStorage()
        val persistence = EpubLocatorPersistence(storage)
        val publication = publication("only.xhtml")

        persistence.persist(
            chapterId = 13L,
            publication = publication,
            locator = createEpubLocator(section("s0", "only.xhtml", "<p>Private prose</p>"), 0, 1),
            sectionIndex = 0,
            sectionCount = 1,
            incognito = true,
        )

        assertEquals(0, storage.writeCount)
        assertNull(storage.valueFor(13L))
    }

    @Test
    fun `malformed stored locator is harmless and invalid fallback selects first section`() {
        val storage = InMemoryEpubLocatorStorage().apply {
            putForChapter(14L, "{\"fingerprint\":7,\"locator\":\"not-an-object\"}")
        }
        val persistence = EpubLocatorPersistence(storage)

        val restored = persistence.restore(
            14L,
            publication("first.xhtml", "second.xhtml"),
            sections("first.xhtml", "second.xhtml"),
            200,
        )

        assertEquals(EpubRestoredLocation(0), restored)
    }

    @Test
    fun `interrupted local record is replaced by the latest repeated settled navigation`() {
        val storage = InMemoryEpubLocatorStorage().apply {
            putForChapter(141L, "{interrupted-write")
        }
        val persistence = EpubLocatorPersistence(storage)
        val publication = publication("first.xhtml", "second.xhtml")
        persistence.persist(
            141L,
            publication,
            createEpubLocator(section("s0", "first.xhtml", "<p>First</p>"), 0, 2),
            0,
            2,
            incognito = false,
        )
        persistence.persist(
            141L,
            publication,
            createEpubLocator(section("s1", "second.xhtml", "<p>Second</p>"), 1, 2),
            1,
            2,
            incognito = false,
        )

        val restored = persistence.restore(141L, publication, sections("first.xhtml", "second.xhtml"), 0)

        assertEquals(EpubRestoredLocation(1), restored)
        assertEquals(2, storage.writeCount)
    }

    @Test
    fun `untrusted locator text remains inert and falls through to progression`() {
        val storage = InMemoryEpubLocatorStorage()
        val persistence = EpubLocatorPersistence(storage)
        val publication = publication("a.xhtml", "b.xhtml", "c.xhtml")
        persistence.persist(
            chapterId = 15L,
            publication = publication,
            locator = BookLocator.create(
                href = "javascript:alert(1)",
                cssSelector = "#x');background:url(https://invalid.example)",
                textQuote = BookTextQuote("<script>alert(1)</script>"),
                totalProgression = 0.5,
            ),
            sectionIndex = 0,
            sectionCount = 3,
            incognito = false,
        )

        val restored = persistence.restore(15L, publication, sections("a.xhtml", "b.xhtml", "c.xhtml"), 0)

        assertEquals(EpubRestoredLocation(1), restored)
    }

    @Test
    fun `stale publication fingerprint cannot restore locator or disposable hints`() {
        val storage = InMemoryEpubLocatorStorage()
        val persistence = EpubLocatorPersistence(storage)
        persistence.persist(
            chapterId = 16L,
            publication = publication("old.xhtml"),
            locator = BookLocator.create(href = "old.xhtml", cssSelector = "body", totalProgression = 1.0),
            sectionIndex = 0,
            sectionCount = 1,
            incognito = false,
        )

        val restored = persistence.restore(
            16L,
            publication("first.xhtml", "second.xhtml", fingerprint = "publication-2"),
            sections("first.xhtml", "second.xhtml"),
            1,
        )

        assertEquals(EpubRestoredLocation(0), restored)
    }

    @Test
    fun `reader activity and viewer integrate restore and settled persistence without changing page sync`() {
        val root = projectRoot()
        val viewer = root.resolve(VIEWER_SOURCE).toFile().readText()
        val activity = root.resolve(ACTIVITY_SOURCE).toFile().readText()
        val viewModel = root.resolve(VIEW_MODEL_SOURCE).toFile().readText()

        assertTrue(viewer.contains("override fun onPageFinished"))
        assertTrue(viewer.contains("if (url != navigation.baseUrl) return"))
        assertTrue(viewer.contains("activity.persistEpubLocator("))
        assertTrue(viewer.contains("activity.restoreEpubLocator("))
        assertTrue(activity.contains("incognito = viewModel.isIncognitoMode()"))
        assertTrue(viewModel.contains("internal fun isIncognitoMode(): Boolean = incognitoMode"))
        assertTrue(viewModel.contains("updateChapterProgress(selectedChapter, page/* SY --> */, hasExtraPage"))
        assertFalse(
            viewModel.substringAfter(
                "internal fun isIncognitoMode",
            ).substringBefore("fun getSource").contains("BookLocator"),
        )
    }

    private fun publication(vararg hrefs: String, fingerprint: String = "publication-1") = TextBookPublication(
        fingerprint = BookFingerprint(fingerprint),
        spine = hrefs.mapIndexed { index, href -> BookSection("section-$index", href) },
        tableOfContents = emptyList(),
    )

    private fun sections(vararg hrefs: String) = hrefs.mapIndexed { index, href ->
        section("section-$index", href, "<p>Section $index safe prose</p>")
    }

    private fun section(id: String, href: String, html: String) = BookSectionContent(id, href, html)

    private fun projectRoot(): Path {
        var candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(candidate.resolve(VIEWER_SOURCE))) {
            candidate = candidate.parent ?: error("Unable to locate reader sources")
        }
        return candidate
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

        fun valueFor(chapterId: Long): String? = values["chapter-$chapterId"]

        fun putForChapter(chapterId: Long, value: String) {
            values["chapter-$chapterId"] = value
        }
    }

    private companion object {
        const val VIEWER_SOURCE = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/EpubTextViewer.kt"
        const val ACTIVITY_SOURCE = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt"
        const val VIEW_MODEL_SOURCE = "app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderViewModel.kt"
    }
}
