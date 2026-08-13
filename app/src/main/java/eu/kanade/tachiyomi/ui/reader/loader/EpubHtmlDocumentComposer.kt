package eu.kanade.tachiyomi.ui.reader.loader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

internal object EpubHtmlDocumentComposer {

    fun compose(sanitizedFragment: String): String {
        val document = Jsoup.parse("<!doctype html><html><head></head><body></body></html>")
        document.outputSettings()
            .syntax(Document.OutputSettings.Syntax.html)
            .prettyPrint(false)
        document.head().appendElement("meta")
            .attr("name", "viewport")
            .attr("content", "width=device-width, initial-scale=1")

        val fragment = Jsoup.parseBodyFragment(sanitizedFragment)
        fragment.body().childNodes().toList().forEach { node ->
            val target = if ((node as? Element).isTopLevelStyle()) document.head() else document.body()
            target.appendChild(node)
        }
        return document.outerHtml()
    }

    private fun Element?.isTopLevelStyle(): Boolean = when (this?.normalName()) {
        "style" -> true
        "link" -> attr("rel")
            .lowercase()
            .split(' ', '\t', '\n', '\r', '\u000C')
            .any { it == "stylesheet" }
        else -> false
    }
}
