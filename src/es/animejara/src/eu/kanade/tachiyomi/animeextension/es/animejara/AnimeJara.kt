package eu.kanade.tachiyomi.animeextension.es.animejara

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.streamtapeextractor.StreamTapeExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.ParsedAnimeHttpLegacySource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

@Source
abstract class AnimeJara :
    ParsedAnimeHttpLegacySource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val streamTapeExtractor by lazy { StreamTapeExtractor(client) }

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/catalogo/?paged=$page", headers)

    override fun popularAnimeSelector() = "div.anime-card-wrapper a.anime-card"

    override fun popularAnimeNextPageSelector() = "#paginacion-container a:contains(»)"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.attr("abs:href"))
        val data = runCatching { JSONObject(element.attr("data-anime")) }.getOrNull()
        title = data?.optString("titulo").orEmpty().ifBlank { element.selectFirst("img")?.attr("alt").orEmpty() }
        thumbnail_url = data?.optString("poster")?.takeIf { it.isNotBlank() } ?: element.selectFirst("img")?.attr("abs:src")
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/catalogo/?estado=Emision&paged=$page", headers)

    override fun latestUpdatesSelector() = popularAnimeSelector()

    override fun latestUpdatesNextPageSelector() = popularAnimeNextPageSelector()

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    // =============================== Search ===============================
    override fun getFilterList(): AnimeFilterList = AnimeJaraFilters.getFilterList()

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/catalogo/".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query)
            AnimeJaraFilters.params(filters).forEach { (k, v) -> addQueryParameter(k, v) }
            if (page > 1) addQueryParameter("paged", page.toString())
        }.build()
        return GET(url, headers)
    }

    override fun searchAnimeSelector() = popularAnimeSelector()

    override fun searchAnimeNextPageSelector() = popularAnimeNextPageSelector()

    override fun searchAnimeFromElement(element: Element) = popularAnimeFromElement(element)

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        title = document.selectFirst(".anime-title-desktop, h1")!!.text()
        thumbnail_url = document.selectFirst("img.main-poster-img")?.attr("abs:src")
        genre = document.select(".anime-info .anime-categorias span").joinToString { it.text() }
        description = document.selectFirst(".anime-info .anime-sinopsis-contenedor")?.wholeText()?.trim()
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeFromElement(element: Element) = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val path = response.request.url.encodedPath

        if (path.startsWith("/movie/")) {
            return listOf(
                SEpisode.create().apply {
                    name = "Película"
                    episode_number = 1F
                    setUrlWithoutDomain(response.request.url.toString())
                },
            )
        }

        val slug = path.trim('/').substringAfterLast('/')
        val script = document.select("script").map { it.data() }.firstOrNull { "TEMPORADAS_DATA" in it }
            ?: return emptyList()
        val json = Regex("""TEMPORADAS_DATA\s*=\s*(\[.*?\]);\s*\n""", RegexOption.DOT_MATCHES_ALL)
            .find(script)?.groupValues?.get(1) ?: return emptyList()
        val seasons = JSONArray(json)
        val multiSeason = seasons.length() > 1
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

        val episodes = buildList {
            for (i in 0 until seasons.length()) {
                val season = seasons.getJSONObject(i)
                val seasonNum = season.optInt("numero_temporada", i + 1)
                val eps = season.getJSONArray("episodios")
                for (j in 0 until eps.length()) {
                    val ep = eps.getJSONObject(j)
                    val num = ep.optString("numero_episodio")
                    val title = ep.optString("nombre_episodio")
                    add(
                        SEpisode.create().apply {
                            url = "/episode/$slug-${seasonNum}x$num/"
                            name = buildString {
                                if (multiSeason) append("T$seasonNum ")
                                append("Episodio $num")
                                if (title.isNotBlank()) append(" - $title")
                            }
                            episode_number = num.toFloatOrNull() ?: 0F
                            date_upload = runCatching { dateFormat.parse(ep.optString("fecha_actualizacion"))!!.time }.getOrDefault(0L)
                            scanlator = ep.optJSONArray("idiomas")?.let { a -> (0 until a.length()).joinToString { a.getString(it) } }
                        },
                    )
                }
            }
        }
        return episodes.reversed()
    }

    // ============================ Video Links =============================
    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoListParse(response: Response): List<Video> {
        val html = response.body.string()
        val document = Jsoup.parse(html, response.request.url.toString())
        val langs = document.select(".boton-idioma .lang-name").map { it.text().trim() }

        val embeds = Regex("""const enlaces\s*=\s*(\[.*?\])""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?.let { m -> JSONArray(m.groupValues[1]).let { a -> (0 until a.length()).map { a.getString(it) } } }
            ?: listOfNotNull(document.selectFirst("iframe[id^=iframe-video]")?.attr("abs:src"))

        return embeds.flatMapIndexed { i, embed -> videosFromEmbed(embed, langs.getOrNull(i).orEmpty()) }
    }

    private fun videosFromEmbed(embedUrl: String, lang: String): List<Video> {
        val embedHeaders = headers.newBuilder().set("Referer", "$baseUrl/").build()
        val body = runCatching { client.newCall(GET(embedUrl, embedHeaders)).execute().use { it.body.string() } }
            .getOrNull() ?: return emptyList()
        val prefix = if (lang.isBlank()) "" else "$lang - "
        val playerHeaders = headers.newBuilder().set("Referer", embedUrl).build()

        return Regex("""playVideo\((?:&quot;|")(.*?)(?:&quot;|")\)""").findAll(body)
            .map { it.groupValues[1] }
            .distinct()
            .flatMap { url ->
                runCatching {
                    when {
                        "streamtape" in url -> streamTapeExtractor.videosFromUrl(url, "${prefix}Streamtape")
                        listOf("filelions", "hgcloud", "hglink", "vidhide").any { it in url } ->
                            runBlocking { VidHideExtractor(client, playerHeaders).videosFromUrl(url) { q -> "${prefix}VidHide: $q" } }
                        // shortcut: the "nyuu" player is obfuscated and skipped, add it if it is needed
                        else -> emptyList()
                    }
                }.getOrDefault(emptyList())
            }.toList()
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val lang = preferences.getString(PREF_LANG_KEY, PREF_LANG_DEFAULT)!!
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareByDescending<Video> { it.videoTitle.contains(lang, true) }
                .thenByDescending { it.videoTitle.contains(quality) },
        )
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_LANG_KEY
            title = "Idioma preferido"
            entries = LANG_LIST
            entryValues = LANG_LIST
            setDefaultValue(PREF_LANG_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Calidad preferida"
            entries = QUALITY_LIST
            entryValues = QUALITY_LIST
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_LANG_KEY = "preferred_lang"
        private const val PREF_LANG_DEFAULT = "LATINO"
        private val LANG_LIST = arrayOf("LATINO", "CASTELLANO", "JAPONES")

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "1080"
        private val QUALITY_LIST = arrayOf("1080", "720", "480", "360")
    }
}
