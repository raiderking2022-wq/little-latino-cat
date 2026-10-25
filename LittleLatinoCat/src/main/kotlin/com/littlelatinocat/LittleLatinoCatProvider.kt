package com.littlelatinocat

import android.util.Base64
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

    private fun local(url: String): Boolean = runCatching {
        URI(url).host?.removePrefix("www.") == URI(mainUrl).host?.removePrefix("www.")
    }.getOrDefault(false)

    private fun cards(doc: Document): List<SearchResponse> =
        doc.select(".contenedorh .bloque").mapNotNull { block ->
            val anchor = block.selectFirst("a[href]:has(img)") ?: return@mapNotNull null
            val url = anchor.absUrl("href")
            if (!local(url) || !Regex("/[0-9]{4}/[0-9]{2}/").containsMatchIn(url)) return@mapNotNull null
            val image = anchor.select("img").firstOrNull { !it.attr("src").contains("ver-pelicula.png") } ?: return@mapNotNull null
            val title = image.attr("alt").ifBlank { image.attr("title") }
                .ifBlank { anchor.attr("title") }.ifBlank { block.text() }.trim()
            if (title.isBlank()) return@mapNotNull null
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = image.absUrl("data-src").ifBlank { image.absUrl("src") }
            }
        }.distinctBy { it.url }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trimEnd('/')
        val doc = app.get(mainUrl + path + if (page > 1) "/page/$page" else "").document
        val nextPath = "$path/page/${page + 1}"
        val next = doc.select("a[href]").any {
            val url = it.absUrl("href")
            local(url) && runCatching { URI(url).path.trimEnd('/') == nextPath }.getOrDefault(false)
        }
        return newHomePageResponse(listOf(HomePageList(request.name, cards(doc))), next)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(term, "UTF-8")
        val response = app.get("$mainUrl/?s=$encoded")
        if (response.code !in 200..299) {
            throw ErrorLoadingException("El sitio rechazó la búsqueda (HTTP ${response.code}).")
        }
        val doc = response.document
        if (doc.select("h1,h2,h3").any { it.text().trim().equals("No se ha encontrado", ignoreCase = true) }) return emptyList()
        val anchors = doc.select(".post-body.entry-content > a[href]").filter {
            val url = it.absUrl("href")
            local(url) && Regex("/[0-9]{4}/[0-9]{2}/[^/]+[.]html$").containsMatchIn(url)
        }.distinctBy { it.absUrl("href") }.take(20)
        if (anchors.isEmpty()) {
            throw ErrorLoadingException("La página recibida no contiene el listado de búsqueda esperado. No se mostrarán recomendaciones.")
        }
        val results = mutableListOf<SearchResponse>()
        for (anchor in anchors) {
            val url = anchor.absUrl("href")
            val image = anchor.selectFirst("img")
            var title = image?.attr("alt").orEmpty().ifBlank { anchor.attr("title") }.ifBlank { anchor.text() }.trim()
            var poster = image?.absUrl("data-src").orEmpty().ifBlank { image?.absUrl("src").orEmpty() }
            if (title.isBlank() || poster.isBlank()) {
                try {
                    val detailResponse = app.get(url)
                    if (detailResponse.code in 200..299) {
                        val detail = detailResponse.document
                        val detailTitle = detail.selectFirst("h1.entry-title, h1.post-title, h1")?.text().orEmpty().trim()
                        if (detailTitle.isNotBlank()) title = detailTitle
                        if (poster.isBlank()) poster = detail.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
                    }
                } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { }
            }
            if (title.isBlank()) title = URI(url).path.substringAfterLast('/').removeSuffix(".html").replace('-', ' ')
            results.add(newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster.takeIf { it.isNotBlank() } })
        }
        return results
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

    private fun supportedPlayer(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return listOf("voe.sx", "filemoon.sx", "filemoon.to", "streamtape.com", "doodstream.com", "dood.to", "streamwish.to", "vidhide.com", "mixdrop.co", "mixdrop.to")
            .any { host == it || host.endsWith(".$it") }
    }

    private fun unwrap(url: String): String? {
        if (supportedPlayer(url)) return url
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host?.removePrefix("www.") != "peliculasrey.me") return null
        val path = uri.path ?: return null
        if (!path.startsWith("/red2.php/")) return null
        var value = path.removePrefix("/red2.php/")
        if (value.length !in 8..4096) return null
        repeat(2) {
            value = runCatching { String(Base64.decode(value, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull() ?: return null
        }
        return value.takeIf { supportedPlayer(it) }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data).document
        val candidates = linkedSetOf<String>()
        doc.select("iframe[src], iframe[data-src], a[href]").forEach { element ->
            val url = when {
                element.hasAttr("data-src") -> element.absUrl("data-src")
                element.tagName() == "iframe" -> element.absUrl("src")
                else -> element.absUrl("href")
            }
            unwrap(url)?.let { candidates.add(it) }
        }
        val wrapper = Regex("https://(?:www[.])?peliculasrey[.]me/red2[.]php/([A-Za-z0-9+/=]+)")
        doc.select("script").forEach { script ->
            val text = script.data().replace("\\/", "/")
            wrapper.findAll(text).forEach { match -> unwrap(match.value)?.let { candidates.add(it) } }
        }
        var found = false
        for (url in candidates.take(12)) {
            try {
                loadExtractor(url, data, subtitleCallback) { link -> found = true; callback(link) }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { }
        }
        return found
    }
}
