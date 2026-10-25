package com.littlelatinocat

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class LittleLatinoCatProvider : MainAPI() {
    override var mainUrl = "https://www.peelink2.com"
    override var name = "Little latino cat"
    override var lang = "es"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf("/inicio" to "Películas")

    private fun cards(doc: Document): List<SearchResponse> {
        return doc.select("a[href]:has(img)").mapNotNull { anchor ->
            val url = anchor.absUrl("href")
            val parsed = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
            if (parsed.host?.removePrefix("www.") != "peelink2.com") return@mapNotNull null
            if (!Regex("/\\d{4}/\\d{2}/").containsMatchIn(parsed.path ?: "")) return@mapNotNull null
            val image = anchor.selectFirst("img") ?: return@mapNotNull null
            val title = image.attr("alt").ifBlank { image.attr("title") }
                .ifBlank { anchor.attr("title") }.ifBlank { anchor.text() }.trim()
            if (title.isBlank()) return@mapNotNull null
            val poster = image.absUrl("data-src").ifBlank { image.absUrl("src") }
            newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
        }.distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = mainUrl + request.data + if (page > 1) "/page/$page" else ""
        val doc = app.get(url).document
        val items = cards(doc)
        val next = doc.selectFirst("a.next, a[rel=next]") != null ||
            doc.select("a[href]").any { it.absUrl("href").contains("/page/${page + 1}") }
        return newHomePageResponse(listOf(HomePageList(request.name, items)), next)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return cards(app.get("$mainUrl/?s=$encoded").document)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title, h1.post-title, h1")?.text()
            ?.takeIf { it.isNotBlank() } ?: throw ErrorLoadingException("No se encontró el título de la ficha")
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")
            plot = doc.selectFirst("meta[name=description]")?.attr("content")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val content = doc.selectFirst(".entry-content, .post-body, article") ?: return false
        val candidates = content.select("iframe[src], iframe[data-src], a[href]").mapNotNull { element ->
            val url = when {
                element.hasAttr("data-src") -> element.absUrl("data-src")
                element.tagName() == "iframe" -> element.absUrl("src")
                else -> element.absUrl("href")
            }
            val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return@mapNotNull null
            if (!url.startsWith("https://")) return@mapNotNull null
            if (host.removePrefix("www.") == "peelink2.com") return@mapNotNull null
            val videoHost = listOf("voe.sx", "filemoon.sx", "filemoon.to", "streamtape.com", "doodstream.com", "dood.to", "streamwish.to", "vidhide.com", "mixdrop.co", "mixdrop.to")
                .any { host == it || host.endsWith(".$it") }
            if (!videoHost) return@mapNotNull null
            url
        }.distinct().take(12)
        var found = false
        for (url in candidates) {
            loadExtractor(url, data, subtitleCallback) { link ->
                found = true
                callback(link)
            }
        }
        return found
    }
}
