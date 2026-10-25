package com.littlelatinocat

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer

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

    private fun normalized(text: String): String = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9]+"), " ").trim()

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
        val tokens = normalized(term).split(' ').filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val home = app.get("$mainUrl/inicio").document
        val forms = home.select("form").mapNotNull { form ->
            val input = form.selectFirst("input[name=s], input[name=q], input[name=search]") ?: return@mapNotNull null
            val action = form.absUrl("action").ifBlank { "$mainUrl/" }
            if (!local(action)) return@mapNotNull null
            val params = form.select("input[type=hidden][name]").associate { it.attr("name") to it.attr("value") }.toMutableMap()
            params[input.attr("name")] = term
            Triple(action, form.attr("method").lowercase(), params.toMap())
        }.distinct().toMutableList()
        if (forms.isEmpty()) forms.add(Triple("$mainUrl/", "get", mapOf("s" to term)))
        var received = false
        for ((action, method, params) in forms.take(3)) {
            try {
                val response = if (method == "post") app.post(action, data = params) else {
                    val encoded = params.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
                    app.get(action + if (action.contains('?')) "&$encoded" else "?$encoded")
                }
                if (response.code !in 200..299) continue
                received = true
                val matches = cards(response.document).filter { item ->
                    val title = normalized(item.name)
                    tokens.all { title.contains(it) }
                }
                if (matches.isNotEmpty()) return matches
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { }
        }
        if (!received) throw ErrorLoadingException("El sitio rechazó la búsqueda. No se mostrarán recomendaciones como resultados.")
        return emptyList()
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
