package eu.kanade.tachiyomi.extension.all.batoto

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import com.squareup.duktape.Duktape
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

open class BatoTo(
    final override val lang: String,
    private val siteLang: String
) : ConfigurableSource, ParsedHttpSource() {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val name: String = "Bato.to"
    override val baseUrl: String = getMirrorPref()!!
    override val id: Long = when (lang) {
        "zh-Hans" -> 2818874445640189582
        "zh-Hant" -> 38886079663327225
        "ro-MD" -> 8871355786189601023
        else -> super.id
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val mirrorPref = ListPreference(screen.context).apply {
            key = "${MIRROR_PREF_KEY}_$lang"
            title = MIRROR_PREF_TITLE
            entries = MIRROR_PREF_ENTRIES
            entryValues = MIRROR_PREF_ENTRY_VALUES
            setDefaultValue(MIRROR_PREF_DEFAULT_VALUE)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString("${MIRROR_PREF_KEY}_$lang", entry).commit()
            }
        }
        val altChapterListPref = CheckBoxPreference(screen.context).apply {
            key = "${ALT_CHAPTER_LIST_PREF_KEY}_$lang"
            title = ALT_CHAPTER_LIST_PREF_TITLE
            summary = ALT_CHAPTER_LIST_PREF_SUMMARY
            setDefaultValue(ALT_CHAPTER_LIST_PREF_DEFAULT_VALUE)

            setOnPreferenceChangeListener { _, newValue ->
                val checkValue = newValue as Boolean
                preferences.edit().putBoolean("${ALT_CHAPTER_LIST_PREF_KEY}_$lang", checkValue).commit()
            }
        }
        screen.addPreference(mirrorPref)
        screen.addPreference(altChapterListPref)
    }

    private fun getMirrorPref(): String? = preferences.getString("${MIRROR_PREF_KEY}_$lang", MIRROR_PREF_DEFAULT_VALUE)
    private fun getAltChapterListPref(): Boolean = preferences.getBoolean("${ALT_CHAPTER_LIST_PREF_KEY}_$lang", ALT_CHAPTER_LIST_PREF_DEFAULT_VALUE)

    companion object {
        private const val MIRROR_PREF_KEY = "MIRROR"
        private const val MIRROR_PREF_TITLE = "Mirror"
        private val MIRROR_PREF_ENTRIES = arrayOf("Bato.to", "Batotoo.com", "Comiko.net", "Battwo.com", "Mangatoto.com", "Mycdhands.com")
        private val MIRROR_PREF_ENTRY_VALUES = arrayOf("https://bato.to", "https://batotoo.com", "https://comiko.net", "https://battwo.com", "https://mangatoto.com", "https://mycdhands.com")
        private val MIRROR_PREF_DEFAULT_VALUE = MIRROR_PREF_ENTRY_VALUES[0]

        private const val ALT_CHAPTER_LIST_PREF_KEY = "ALT_CHAPTER_LIST"
        private const val ALT_CHAPTER_LIST_PREF_TITLE = "Alternative Chapter List"
        private const val ALT_CHAPTER_LIST_PREF_SUMMARY = "If checked, uses an alternate chapter list"
        private const val ALT_CHAPTER_LIST_PREF_DEFAULT_VALUE = false
    }

    override val supportsLatest = true
    private val json: Json by injectLazy()
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/browse?langs=$siteLang&sort=update&page=$page")
    }

    override fun latestUpdatesSelector(): String {
        return when (siteLang) {
            "" -> "div#series-list div.col"
            "en" -> "div#series-list div.col.no-flag"
            else -> "div#series-list div.col:has([data-lang=\"$siteLang\"])"
        }
    }

    override fun latestUpdatesFromElement(element: Element): SManga {
        val manga = SManga.create()
        val item = element.select("a.item-cover")
        val imgurl = item.select("img").attr("abs:src")
        manga.setUrlWithoutDomain(item.attr("href"))
        manga.title = element.select("a.item-title").text().removeEntities()
        manga.thumbnail_url = imgurl
        return manga
    }

    override fun latestUpdatesNextPageSelector() = "div#mainer nav.d-none .pagination .page-item:last-of-type:not(.disabled)"

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/browse?langs=$siteLang&sort=views_w&page=$page")
    }

    override fun popularMangaSelector() = latestUpdatesSelector()

    override fun popularMangaFromElement(element: Element) = latestUpdatesFromElement(element)

    override fun popularMangaNextPageSelector() = latestUpdatesNextPageSelector()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return when {
            query.startsWith("ID:") -> {
                val id = query.substringAfter("ID:")
                client.newCall(GET("$baseUrl/series/$id", headers)).asObservableSuccess()
                    .map { response ->
                        queryIDParse(response, id)
                    }
            }
            query.isNotBlank() -> {
                val url = "$baseUrl/search".toHttpUrl().newBuilder()
                    .addQueryParameter("word", query)
                    .addQueryParameter("page", page.toString())
                filters.forEach { filter ->
                    when (filter) {
                        is LetterFilter -> {
                            if (filter.state == 1) {
                                url.addQueryParameter("mode", "letter")
                            }
                        }
                    }
                }
                client.newCall(GET(url.build().toString(), headers)).asObservableSuccess()
                    .map { response ->
                        queryParse(response)
                    }
            }
            else -> {
                val url = "$baseUrl/browse".toHttpUrlOrNull()!!.newBuilder()
                var min = ""
                var max = ""
                filters.forEach { filter ->
                    when (filter) {
                        is UtilsFilter -> {
                            if (filter.state != 0) {
                                val filterUrl = "$baseUrl/_utils/comic-list?type=${filter.selected}"
                                return client.newCall(GET(filterUrl, headers)).asObservableSuccess()
                                    .map { response ->
                                        queryUtilsParse(response)
                                    }
                            }
                        }
                        is HistoryFilter -> {
                            if (filter.state != 0) {
                                val filterUrl = "$baseUrl/ajax.my.${filter.selected}.paging"
                                return client.newCall(POST(filterUrl, headers, formBuilder().build())).asObservableSuccess()
                                    .map { response ->
                                        queryHistoryParse(response)
                                    }
                            }
                        }
                        is LangGroupFilter -> {
                            if (filter.selected.isEmpty()) {
                                url.addQueryParameter("langs", siteLang)
                            } else {
                                val selection = "${filter.selected.joinToString(",")},$siteLang"
                                url.addQueryParameter("langs", selection)
                            }
                        }
                        is GenreGroupFilter -> {
                            with(filter) {
                                url.addQueryParameter(
                                    "genres", included.joinToString(",") + "|" + excluded.joinToString(",")
                                )
                            }
                        }
                        is StatusFilter -> url.addQueryParameter("release", filter.selected)
                        is SortFilter -> {
                            if (filter.state != null) {
                                val sort = getSortFilter()[filter.state!!.index].value
                                val value = when (filter.state!!.ascending) {
                                    true -> "az"
                                    false -> "za"
                                }
                                url.addQueryParameter("sort", "$sort.$value")
                            }
                        }
                        is OriginGroupFilter -> {
                            if (filter.selected.isNotEmpty()) {
                                url.addQueryParameter("origs", filter.selected.joinToString(","))
                            }
                        }
                        is MinChapterTextFilter -> min = filter.state
                        is MaxChapterTextFilter -> max = filter.state
                    }
                }
                url.addQueryParameter("page", page.toString())

                if (max.isNotEmpty() or min.isNotEmpty()) {
                    url.addQueryParameter("chapters", "$min-$max")
                }

                client.newCall(GET(url.build().toString(), headers)).asObservableSuccess()
                    .map { response ->
                        queryParse(response)
                    }
            }
        }
    }

    private fun queryIDParse(response: Response, id: String): MangasPage {
        val document = response.asJsoup()
        val infoElement = document.select("div#mainer div.container-fluid")
        val manga = SManga.create()
        manga.title = infoElement.select("h3").text().removeEntities()
        manga.thumbnail_url = document.select("div.attr-cover img")
            .attr("abs:src")
        manga.url = infoElement.select("h3 a").attr("abs:href")
        return MangasPage(listOf(manga), false)
    }

    private fun queryParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select(latestUpdatesSelector())
            .map { element -> latestUpdatesFromElement(element) }
        val nextPage = document.select(latestUpdatesNextPageSelector()).first() != null
        return MangasPage(mangas, nextPage)
    }

    private fun queryUtilsParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("tbody > tr")
            .map { element -> searchUtilsFromElement(element) }
        return MangasPage(mangas, false)
    }

    private fun queryHistoryParse(response: Response): MangasPage {
        val json = json.decodeFromString<JsonObject>(response.body!!.string())
        val html = json.jsonObject["html"]!!.jsonPrimitive.content

        val document = Jsoup.parse(html, response.request.url.toString())
        val mangas = document.select(".my-history-item")
            .map { element -> searchHistoryFromElement(element) }
        return MangasPage(mangas, false)
    }

    private fun searchUtilsFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.setUrlWithoutDomain(element.select("td a").attr("href"))
        manga.title = element.select("td a").text()
        manga.thumbnail_url = element.select("img").attr("abs:src")
        return manga
    }

    private fun searchHistoryFromElement(element: Element): SManga {
        val manga = SManga.create()
        manga.setUrlWithoutDomain(element.select(".position-relative a").attr("href"))
        manga.title = element.select(".position-relative a").text()
        manga.thumbnail_url = element.select("img").attr("abs:src")
        return manga
    }

    open fun formBuilder() = FormBody.Builder().apply {
        add("_where", "browse")
        add("first", "0")
        add("limit", "0")
        add("prevPos", "null")
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = throw UnsupportedOperationException("Not used")
    override fun searchMangaSelector() = throw UnsupportedOperationException("Not used")
    override fun searchMangaFromElement(element: Element) = throw UnsupportedOperationException("Not used")
    override fun searchMangaNextPageSelector() = throw UnsupportedOperationException("Not used")

    override fun mangaDetailsRequest(manga: SManga): Request {
        if (manga.url.startsWith("http")) {
            return GET(manga.url, headers)
        }
        return super.mangaDetailsRequest(manga)
    }

    override fun mangaDetailsParse(document: Document): SManga {
        val infoElement = document.select("div#mainer div.container-fluid")
        val manga = SManga.create()
        val workStatus = infoElement.select("div.attr-item:contains(original work) span").text()
        val uploadStatus = infoElement.select("div.attr-item:contains(upload status) span").text()
        manga.title = infoElement.select("h3").text().removeEntities()
        manga.author = infoElement.select("div.attr-item:contains(author) a:first-child").text()
        manga.artist = infoElement.select("div.attr-item:contains(artist) a:last-child").text()
        manga.status = parseStatus(workStatus, uploadStatus)
        manga.genre = infoElement.select(".attr-item b:contains(genres) + span ").joinToString { it.text() }
        manga.description = infoElement.select("div.limit-html").text() + "\n" + infoElement.select(".episode-list > .alert-warning").text().trim()
        manga.thumbnail_url = document.select("div.attr-cover img")
            .attr("abs:src")
        return manga
    }

    private fun parseStatus(workStatus: String?, uploadStatus: String?) = when {
        workStatus == null -> SManga.UNKNOWN
        workStatus.contains("Ongoing") -> SManga.ONGOING
        workStatus.contains("Cancelled") -> SManga.CANCELLED
        workStatus.contains("Hiatus") -> SManga.ON_HIATUS
        workStatus.contains("Completed") -> when {
            uploadStatus?.contains("Ongoing") == true -> SManga.PUBLISHING_FINISHED
            else -> SManga.COMPLETED
        }
        else -> SManga.UNKNOWN
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val url = client.newCall(
            GET(
                when {
                    manga.url.startsWith("http") -> manga.url
                    else -> "$baseUrl${manga.url}"
                }
            )
        ).execute().asJsoup()
        if (getAltChapterListPref() || checkChapterLists(url)) {
            val id = manga.url.substringBeforeLast("/").substringAfterLast("/").trim()
            return client.newCall(GET("$baseUrl/rss/series/$id.xml"))
                .asObservableSuccess()
                .map { altChapterParse(it, manga.title) }
        }
        return super.fetchChapterList(manga)
    }

    private fun altChapterParse(response: Response, title: String): List<SChapter> {
        return Jsoup.parse(response.body!!.string(), response.request.url.toString(), Parser.xmlParser())
            .select("channel > item").map { item ->
                SChapter.create().apply {
                    url = item.selectFirst("guid").text()
                    name = item.selectFirst("title").text().substringAfter(title).trim()
                    date_upload = SimpleDateFormat("E, dd MMM yyyy H:m:s Z", Locale.US).parse(item.selectFirst("pubDate").text())?.time ?: 0L
                }
            }
    }

    private fun checkChapterLists(document: Document): Boolean {
        return document.select(".episode-list > .alert-warning").text().contains("This comic has been marked as deleted and the chapter list is not available.")
    }

    override fun chapterListRequest(manga: SManga): Request {
        if (manga.url.startsWith("http")) {
            return GET(manga.url, headers)
        }
        return super.chapterListRequest(manga)
    }

    override fun chapterListSelector() = "div.main div.p-2"

    override fun chapterFromElement(element: Element): SChapter {
        val chapter = SChapter.create()
        val urlElement = element.select("a.chapt")
        val group = element.select("div.extra > a:not(.ps-3)").text()
        val time = element.select("div.extra > i.ps-3").text()
        chapter.setUrlWithoutDomain(urlElement.attr("href"))
        chapter.name = urlElement.text()
        if (group != "") {
            chapter.scanlator = group
        }
        if (time != "") {
            chapter.date_upload = parseChapterDate(time)
        }
        return chapter
    }

    private fun parseChapterDate(date: String): Long {
        val value = date.split(' ')[0].toInt()

        return when {
            "secs" in date -> Calendar.getInstance().apply {
                add(Calendar.SECOND, value * -1)
            }.timeInMillis
            "mins" in date -> Calendar.getInstance().apply {
                add(Calendar.MINUTE, value * -1)
            }.timeInMillis
            "hours" in date -> Calendar.getInstance().apply {
                add(Calendar.HOUR_OF_DAY, value * -1)
            }.timeInMillis
            "days" in date -> Calendar.getInstance().apply {
                add(Calendar.DATE, value * -1)
            }.timeInMillis
            "weeks" in date -> Calendar.getInstance().apply {
                add(Calendar.DATE, value * 7 * -1)
            }.timeInMillis
            "months" in date -> Calendar.getInstance().apply {
                add(Calendar.MONTH, value * -1)
            }.timeInMillis
            "years" in date -> Calendar.getInstance().apply {
                add(Calendar.YEAR, value * -1)
            }.timeInMillis
            "sec" in date -> Calendar.getInstance().apply {
                add(Calendar.SECOND, value * -1)
            }.timeInMillis
            "min" in date -> Calendar.getInstance().apply {
                add(Calendar.MINUTE, value * -1)
            }.timeInMillis
            "hour" in date -> Calendar.getInstance().apply {
                add(Calendar.HOUR_OF_DAY, value * -1)
            }.timeInMillis
            "day" in date -> Calendar.getInstance().apply {
                add(Calendar.DATE, value * -1)
            }.timeInMillis
            "week" in date -> Calendar.getInstance().apply {
                add(Calendar.DATE, value * 7 * -1)
            }.timeInMillis
            "month" in date -> Calendar.getInstance().apply {
                add(Calendar.MONTH, value * -1)
            }.timeInMillis
            "year" in date -> Calendar.getInstance().apply {
                add(Calendar.YEAR, value * -1)
            }.timeInMillis
            else -> {
                return 0
            }
        }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        if (chapter.url.startsWith("http")) {
            return GET(chapter.url, headers)
        }
        return super.pageListRequest(chapter)
    }

    override fun pageListParse(document: Document): List<Page> {
        val pages = mutableListOf<Page>()

        val script = document.select("script").html()

        if (script.contains("var images =")) {
            /*
             * During kotlinx.serialization migration, the pre-existing code seemed to not work
             * Could not find a case where code would run in practice, so it was commented out.
             */
            throw RuntimeException("Unexpected Branch: Please File A Bug Report describing this issue")
            // val imgJson = json.parseToJsonElement(script.substringAfter("var images = ").substringBefore(";")).jsonObject
            // imgJson.keys.forEachIndexed { i, s -> pages.add(Page(i, imageUrl = imgJson[s]!!.jsonPrimitive.content)) }
        } else if (script.contains("const server =")) { // bato.to
            val duktape = Duktape.create()
            val encryptedServer = script.substringAfter("const server = ").substringBefore(";")
            val batojs = duktape.evaluate(script.substringAfter("const batojs = ").substringBefore(";")).toString()
            val decryptScript = cryptoJS + "CryptoJS.AES.decrypt($encryptedServer, \"$batojs\").toString(CryptoJS.enc.Utf8);"
            val server = duktape.evaluate(decryptScript).toString().replace("\"", "")
            duktape.close()

            json.parseToJsonElement(script.substringAfter("const images = ").substringBefore(";")).jsonArray
                .forEachIndexed { i, it ->
                    val imgUrl = it.jsonPrimitive.content
                    if (script.contains("bato.to/images")) {
                        pages.add(Page(i, imageUrl = imgUrl))
                    } else {
                        pages.add(Page(i, imageUrl = if (server.startsWith("http")) "${server}$imgUrl" else "https:${server}$imgUrl"))
                    }
                }
        } else if (script.contains("const imgHttpLis = ") && script.contains("const batoWord = ") && script.contains(
                "const batoPass = "
            )
        ) {
            val duktape = Duktape.create()
            val imgHttpLis = json.parseToJsonElement(
                script.substringAfter("const imgHttpLis = ").substringBefore(";")
            ).jsonArray
            val batoWord = script.substringAfter("const batoWord = ").substringBefore(";")
            val batoPass =
                duktape.evaluate(script.substringAfter("const batoPass = ").substringBefore(";"))
                    .toString()
            val input =
                cryptoJS + "CryptoJS.AES.decrypt($batoWord, \"$batoPass\").toString(CryptoJS.enc.Utf8);"
            val imgWordLis = json.parseToJsonElement(duktape.evaluate(input).toString()).jsonArray
            duktape.close()

            if (imgHttpLis.size == imgWordLis.size) {
                imgHttpLis.forEachIndexed { i: Int, item ->
                    val imageUrl =
                        "${item.jsonPrimitive.content}?${imgWordLis.get(i).jsonPrimitive.content}"
                    pages.add(
                        Page(
                            i,
                            imageUrl = imageUrl
                        )
                    )
                }
            }
        }

        return pages
    }

    private val cryptoJS by lazy {
        client.newCall(
            GET(
                "https://cdnjs.cloudflare.com/ajax/libs/crypto-js/4.0.0/crypto-js.min.js",
                headers
            )
        ).execute().body!!.string()
    }

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException("Not used")

    private fun String.removeEntities(): String = Parser.unescapeEntities(this, true)

    override fun getFilterList() = FilterList(
        LetterFilter(getLetterFilter(), 0),
        Filter.Separator(),
        Filter.Header("NOTE: Ignored if using text search!"),
        Filter.Separator(),
        SortFilter(getSortFilter().map { it.name }.toTypedArray()),
        StatusFilter(getStatusFilter(), 0),
        GenreGroupFilter(getGenreFilter()),
        OriginGroupFilter(getOrginFilter()),
        LangGroupFilter(getLangFilter()),
        MinChapterTextFilter(),
        MaxChapterTextFilter(),
        Filter.Separator(),
        Filter.Header("NOTE: Filters below are incompatible with any other filters!"),
        Filter.Header("NOTE: Login Required!"),
        Filter.Separator(),
        UtilsFilter(getUtilsFilter(), 0),
        HistoryFilter(getHistoryFilter(), 0),
    )
    class SelectFilterOption(val name: String, val value: String)
    class CheckboxFilterOption(val value: String, name: String, default: Boolean = false) : Filter.CheckBox(name, default)
    class TriStateFilterOption(val value: String, name: String, default: Int = 0) : Filter.TriState(name, default)

    abstract class SelectFilter(name: String, private val options: List<SelectFilterOption>, default: Int = 0) : Filter.Select<String>(name, options.map { it.name }.toTypedArray(), default) {
        val selected: String
            get() = options[state].value
    }

    abstract class CheckboxGroupFilter(name: String, options: List<CheckboxFilterOption>) : Filter.Group<CheckboxFilterOption>(name, options) {
        val selected: List<String>
            get() = state.filter { it.state }.map { it.value }
    }

    abstract class TriStateGroupFilter(name: String, options: List<TriStateFilterOption>) : Filter.Group<TriStateFilterOption>(name, options) {
        val included: List<String>
            get() = state.filter { it.isIncluded() }.map { it.value }

        val excluded: List<String>
            get() = state.filter { it.isExcluded() }.map { it.value }
    }

    abstract class TextFilter(name: String) : Filter.Text(name)

    class SortFilter(sortables: Array<String>) : Filter.Sort("Sort", sortables, Selection(5, false))
    class StatusFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Status", options, default)
    class OriginGroupFilter(options: List<CheckboxFilterOption>) : CheckboxGroupFilter("Origin", options)
    class GenreGroupFilter(options: List<TriStateFilterOption>) : TriStateGroupFilter("Genre", options)
    class MinChapterTextFilter : TextFilter("Min. Chapters")
    class MaxChapterTextFilter : TextFilter("Max. Chapters")
    class LangGroupFilter(options: List<CheckboxFilterOption>) : CheckboxGroupFilter("Languages", options)
    class LetterFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Letter matching mode (Slow)", options, default)
    class UtilsFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Utils comic list", options, default)
    class HistoryFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Personal list", options, default)

    private fun getLetterFilter() = listOf(
        SelectFilterOption("Disabled", "disabled"),
        SelectFilterOption("Enabled", "enabled"),
    )

    private fun getSortFilter() = listOf(
        SelectFilterOption("Z-A", "title"),
        SelectFilterOption("Last Updated", "update"),
        SelectFilterOption("Newest Added", "create"),
        SelectFilterOption("Most Views Totally", "views_a"),
        SelectFilterOption("Most Views 365 days", "views_y"),
        SelectFilterOption("Most Views 30 days", "views_m"),
        SelectFilterOption("Most Views 7 days", "views_w"),
        SelectFilterOption("Most Views 24 hours", "views_d"),
        SelectFilterOption("Most Views 60 minutes", "views_h"),
    )

    private fun getHistoryFilter() = listOf(
        SelectFilterOption("None", ""),
        SelectFilterOption("My History", "history"),
        SelectFilterOption("My Updates", "updates"),
    )

    private fun getUtilsFilter() = listOf(
        SelectFilterOption("None", ""),
        SelectFilterOption("Comics: I Created", "i-created"),
        SelectFilterOption("Comics: I Modified", "i-modified"),
        SelectFilterOption("Comics: I Uploaded", "i-uploaded"),
        SelectFilterOption("Comics: Authorized to me", "i-authorized"),
        SelectFilterOption("Comics: Draft Status", "status-draft"),
        SelectFilterOption("Comics: Hidden Status", "status-hidden"),
        SelectFilterOption("Ongoing and Not updated in 30-60 days", "not-updated-30-60"),
        SelectFilterOption("Ongoing and Not updated in 60-90 days", "not-updated-60-90"),
        SelectFilterOption("Ongoing and Not updated in 90-180 days", "not-updated-90-180"),
        SelectFilterOption("Ongoing and Not updated in 180-360 days", "not-updated-180-360"),
        SelectFilterOption("Ongoing and Not updated in 360-1000 days", "not-updated-360-1000"),
        SelectFilterOption("Ongoing and Not updated more than 1000 days", "not-updated-1000"),
    )

    private fun getStatusFilter() = listOf(
        SelectFilterOption("All", ""),
        SelectFilterOption("Pending", "pending"),
        SelectFilterOption("Ongoing", "ongoing"),
        SelectFilterOption("Completed", "completed"),
        SelectFilterOption("Hiatus", "hiatus"),
        SelectFilterOption("Cancelled", "cancelled"),
    )

    private fun getOrginFilter() = listOf(
        // Values exported from publish.bato.to
        CheckboxFilterOption("zh", "Chinese"),
        CheckboxFilterOption("en", "English"),
        CheckboxFilterOption("ja", "Japanese"),
        CheckboxFilterOption("ko", "Korean"),
        CheckboxFilterOption("af", "Afrikaans"),
        CheckboxFilterOption("sq", "Albanian"),
        CheckboxFilterOption("am", "Amharic"),
        CheckboxFilterOption("ar", "Arabic"),
        CheckboxFilterOption("hy", "Armenian"),
        CheckboxFilterOption("az", "Azerbaijani"),
        CheckboxFilterOption("be", "Belarusian"),
        CheckboxFilterOption("bn", "Bengali"),
        CheckboxFilterOption("bs", "Bosnian"),
        CheckboxFilterOption("bg", "Bulgarian"),
        CheckboxFilterOption("my", "Burmese"),
        CheckboxFilterOption("km", "Cambodian"),
        CheckboxFilterOption("ca", "Catalan"),
        CheckboxFilterOption("ceb", "Cebuano"),
        CheckboxFilterOption("zh_hk", "Chinese (Cantonese)"),
        CheckboxFilterOption("zh_tw", "Chinese (Traditional)"),
        CheckboxFilterOption("hr", "Croatian"),
        CheckboxFilterOption("cs", "Czech"),
        CheckboxFilterOption("da", "Danish"),
        CheckboxFilterOption("nl", "Dutch"),
        CheckboxFilterOption("en_us", "English (United States)"),
        CheckboxFilterOption("eo", "Esperanto"),
        CheckboxFilterOption("et", "Estonian"),
        CheckboxFilterOption("fo", "Faroese"),
        CheckboxFilterOption("fil", "Filipino"),
        CheckboxFilterOption("fi", "Finnish"),
        CheckboxFilterOption("fr", "French"),
        CheckboxFilterOption("ka", "Georgian"),
        CheckboxFilterOption("de", "German"),
        CheckboxFilterOption("el", "Greek"),
        CheckboxFilterOption("gn", "Guarani"),
        CheckboxFilterOption("gu", "Gujarati"),
        CheckboxFilterOption("ht", "Haitian Creole"),
        CheckboxFilterOption("ha", "Hausa"),
        CheckboxFilterOption("he", "Hebrew"),
        CheckboxFilterOption("hi", "Hindi"),
        CheckboxFilterOption("hu", "Hungarian"),
        CheckboxFilterOption("is", "Icelandic"),
        CheckboxFilterOption("ig", "Igbo"),
        CheckboxFilterOption("id", "Indonesian"),
        CheckboxFilterOption("ga", "Irish"),
        CheckboxFilterOption("it", "Italian"),
        CheckboxFilterOption("jv", "Javanese"),
        CheckboxFilterOption("kn", "Kannada"),
        CheckboxFilterOption("kk", "Kazakh"),
        CheckboxFilterOption("ku", "Kurdish"),
        CheckboxFilterOption("ky", "Kyrgyz"),
        CheckboxFilterOption("lo", "Laothian"),
        CheckboxFilterOption("lv", "Latvian"),
        CheckboxFilterOption("lt", "Lithuanian"),
        CheckboxFilterOption("lb", "Luxembourgish"),
        CheckboxFilterOption("mk", "Macedonian"),
        CheckboxFilterOption("mg", "Malagasy"),
        CheckboxFilterOption("ms", "Malay"),
        CheckboxFilterOption("ml", "Malayalam"),
        CheckboxFilterOption("mt", "Maltese"),
        CheckboxFilterOption("mi", "Maori"),
        CheckboxFilterOption("mr", "Marathi"),
        CheckboxFilterOption("mo", "Moldavian"),
        CheckboxFilterOption("mn", "Mongolian"),
        CheckboxFilterOption("ne", "Nepali"),
        CheckboxFilterOption("no", "Norwegian"),
        CheckboxFilterOption("ny", "Nyanja"),
        CheckboxFilterOption("ps", "Pashto"),
        CheckboxFilterOption("fa", "Persian"),
        CheckboxFilterOption("pl", "Polish"),
        CheckboxFilterOption("pt", "Portuguese"),
        CheckboxFilterOption("pt_br", "Portuguese (Brazil)"),
        CheckboxFilterOption("ro", "Romanian"),
        CheckboxFilterOption("rm", "Romansh"),
        CheckboxFilterOption("ru", "Russian"),
        CheckboxFilterOption("sm", "Samoan"),
        CheckboxFilterOption("sr", "Serbian"),
        CheckboxFilterOption("sh", "Serbo-Croatian"),
        CheckboxFilterOption("st", "Sesotho"),
        CheckboxFilterOption("sn", "Shona"),
        CheckboxFilterOption("sd", "Sindhi"),
        CheckboxFilterOption("si", "Sinhalese"),
        CheckboxFilterOption("sk", "Slovak"),
        CheckboxFilterOption("sl", "Slovenian"),
        CheckboxFilterOption("so", "Somali"),
        CheckboxFilterOption("es", "Spanish"),
        CheckboxFilterOption("es_419", "Spanish (Latin America)"),
        CheckboxFilterOption("sw", "Swahili"),
        CheckboxFilterOption("sv", "Swedish"),
        CheckboxFilterOption("tg", "Tajik"),
        CheckboxFilterOption("ta", "Tamil"),
        CheckboxFilterOption("th", "Thai"),
        CheckboxFilterOption("ti", "Tigrinya"),
        CheckboxFilterOption("to", "Tonga"),
        CheckboxFilterOption("tr", "Turkish"),
        CheckboxFilterOption("tk", "Turkmen"),
        CheckboxFilterOption("uk", "Ukrainian"),
        CheckboxFilterOption("ur", "Urdu"),
        CheckboxFilterOption("uz", "Uzbek"),
        CheckboxFilterOption("vi", "Vietnamese"),
        CheckboxFilterOption("yo", "Yoruba"),
        CheckboxFilterOption("zu", "Zulu"),
        CheckboxFilterOption("_t", "Other"),
    )

    private fun getGenreFilter() = listOf(
        TriStateFilterOption("artbook", "Artbook"),
        TriStateFilterOption("cartoon", "Cartoon"),
        TriStateFilterOption("comic", "Comic"),
        TriStateFilterOption("doujinshi", "Doujinshi"),
        TriStateFilterOption("imageset", "Imageset"),
        TriStateFilterOption("manga", "Manga"),
        TriStateFilterOption("manhua", "Manhua"),
        TriStateFilterOption("manhwa", "Manhwa"),
        TriStateFilterOption("webtoon", "Webtoon"),
        TriStateFilterOption("western", "Western"),
        TriStateFilterOption("josei", "Josei"),
        TriStateFilterOption("seinen", "Seinen"),
        TriStateFilterOption("shoujo", "Shoujo"),
        TriStateFilterOption("shoujo_ai", "Shoujo ai"),
        TriStateFilterOption("shounen", "Shounen"),
        TriStateFilterOption("shounen_ai", "Shounen ai"),
        TriStateFilterOption("yaoi", "Yaoi"),
        TriStateFilterOption("yuri", "Yuri"),
        TriStateFilterOption("ecchi", "Ecchi"),
        TriStateFilterOption("mature", "Mature"),
        TriStateFilterOption("adult", "Adult"),
        TriStateFilterOption("gore", "Gore"),
        TriStateFilterOption("violence", "Violence"),
        TriStateFilterOption("smut", "Smut"),
        TriStateFilterOption("hentai", "Hentai"),
        TriStateFilterOption("_4_koma", "4-Koma"),
        TriStateFilterOption("action", "Action"),
        TriStateFilterOption("adaptation", "Adaptation"),
        TriStateFilterOption("adventure", "Adventure"),
        TriStateFilterOption("aliens", "Aliens"),
        TriStateFilterOption("animals", "Animals"),
        TriStateFilterOption("anthology", "Anthology"),
        TriStateFilterOption("cars", "cars"),
        TriStateFilterOption("comedy", "Comedy"),
        TriStateFilterOption("cooking", "Cooking"),
        TriStateFilterOption("crime", "crime"),
        TriStateFilterOption("crossdressing", "Crossdressing"),
        TriStateFilterOption("delinquents", "Delinquents"),
        TriStateFilterOption("dementia", "Dementia"),
        TriStateFilterOption("demons", "Demons"),
        TriStateFilterOption("drama", "Drama"),
        TriStateFilterOption("fantasy", "Fantasy"),
        TriStateFilterOption("fan_colored", "Fan-Colored"),
        TriStateFilterOption("full_color", "Full Color"),
        TriStateFilterOption("game", "Game"),
        TriStateFilterOption("gender_bender", "Gender Bender"),
        TriStateFilterOption("genderswap", "Genderswap"),
        TriStateFilterOption("ghosts", "Ghosts"),
        TriStateFilterOption("gyaru", "Gyaru"),
        TriStateFilterOption("harem", "Harem"),
        TriStateFilterOption("harlequin", "Harlequin"),
        TriStateFilterOption("historical", "Historical"),
        TriStateFilterOption("horror", "Horror"),
        TriStateFilterOption("incest", "Incest"),
        TriStateFilterOption("isekai", "Isekai"),
        TriStateFilterOption("kids", "Kids"),
        TriStateFilterOption("loli", "Loli"),
        TriStateFilterOption("lolicon", "lolicon"),
        TriStateFilterOption("magic", "Magic"),
        TriStateFilterOption("magical_girls", "Magical Girls"),
        TriStateFilterOption("martial_arts", "Martial Arts"),
        TriStateFilterOption("mecha", "Mecha"),
        TriStateFilterOption("medical", "Medical"),
        TriStateFilterOption("military", "Military"),
        TriStateFilterOption("monster_girls", "Monster Girls"),
        TriStateFilterOption("monsters", "Monsters"),
        TriStateFilterOption("music", "Music"),
        TriStateFilterOption("mystery", "Mystery"),
        TriStateFilterOption("netorare", "Netorare/NTR"),
        TriStateFilterOption("ninja", "Ninja"),
        TriStateFilterOption("office_workers", "Office Workers"),
        TriStateFilterOption("oneshot", "Oneshot"),
        TriStateFilterOption("parody", "parody"),
        TriStateFilterOption("philosophical", "Philosophical"),
        TriStateFilterOption("police", "Police"),
        TriStateFilterOption("post_apocalyptic", "Post-Apocalyptic"),
        TriStateFilterOption("psychological", "Psychological"),
        TriStateFilterOption("reincarnation", "Reincarnation"),
        TriStateFilterOption("reverse_harem", "Reverse Harem"),
        TriStateFilterOption("romance", "Romance"),
        TriStateFilterOption("samurai", "Samurai"),
        TriStateFilterOption("school_life", "School Life"),
        TriStateFilterOption("sci_fi", "Sci-Fi"),
        TriStateFilterOption("shota", "Shota"),
        TriStateFilterOption("shotacon", "shotacon"),
        TriStateFilterOption("slice_of_life", "Slice of Life"),
        TriStateFilterOption("sm_bdsm", "SM/BDSM"),
        TriStateFilterOption("space", "Space"),
        TriStateFilterOption("sports", "Sports"),
        TriStateFilterOption("super_power", "Super Power"),
        TriStateFilterOption("superhero", "Superhero"),
        TriStateFilterOption("supernatural", "Supernatural"),
        TriStateFilterOption("survival", "Survival"),
        TriStateFilterOption("thriller", "Thriller"),
        TriStateFilterOption("time_travel", "Time Travel"),
        TriStateFilterOption("traditional_games", "Traditional Games"),
        TriStateFilterOption("tragedy", "Tragedy"),
        TriStateFilterOption("vampires", "Vampires"),
        TriStateFilterOption("video_games", "Video Games"),
        TriStateFilterOption("virtual_reality", "Virtual Reality"),
        TriStateFilterOption("wuxia", "Wuxia"),
        TriStateFilterOption("xianxia", "Xianxia"),
        TriStateFilterOption("xuanhuan", "Xuanhuan"),
        TriStateFilterOption("zombies", "Zombies"),
        // Hidden Genres
        TriStateFilterOption("award_winning", "Award Winning"),
        TriStateFilterOption("youkai", "Youkai"),
        TriStateFilterOption("uncategorized", "Uncategorized")
    )

    private fun getLangFilter() = listOf(
        // Values exported from publish.bato.to
        CheckboxFilterOption("en", "English"),
        CheckboxFilterOption("ar", "Arabic"),
        CheckboxFilterOption("bg", "Bulgarian"),
        CheckboxFilterOption("zh", "Chinese"),
        CheckboxFilterOption("cs", "Czech"),
        CheckboxFilterOption("da", "Danish"),
        CheckboxFilterOption("nl", "Dutch"),
        CheckboxFilterOption("fil", "Filipino"),
        CheckboxFilterOption("fi", "Finnish"),
        CheckboxFilterOption("fr", "French"),
        CheckboxFilterOption("de", "German"),
        CheckboxFilterOption("el", "Greek"),
        CheckboxFilterOption("he", "Hebrew"),
        CheckboxFilterOption("hi", "Hindi"),
        CheckboxFilterOption("hu", "Hungarian"),
        CheckboxFilterOption("id", "Indonesian"),
        CheckboxFilterOption("it", "Italian"),
        CheckboxFilterOption("ja", "Japanese"),
        CheckboxFilterOption("ko", "Korean"),
        CheckboxFilterOption("ms", "Malay"),
        CheckboxFilterOption("pl", "Polish"),
        CheckboxFilterOption("pt", "Portuguese"),
        CheckboxFilterOption("pt_br", "Portuguese (Brazil)"),
        CheckboxFilterOption("ro", "Romanian"),
        CheckboxFilterOption("ru", "Russian"),
        CheckboxFilterOption("es", "Spanish"),
        CheckboxFilterOption("es_419", "Spanish (Latin America)"),
        CheckboxFilterOption("sv", "Swedish"),
        CheckboxFilterOption("th", "Thai"),
        CheckboxFilterOption("tr", "Turkish"),
        CheckboxFilterOption("uk", "Ukrainian"),
        CheckboxFilterOption("vi", "Vietnamese"),
        CheckboxFilterOption("af", "Afrikaans"),
        CheckboxFilterOption("sq", "Albanian"),
        CheckboxFilterOption("am", "Amharic"),
        CheckboxFilterOption("hy", "Armenian"),
        CheckboxFilterOption("az", "Azerbaijani"),
        CheckboxFilterOption("be", "Belarusian"),
        CheckboxFilterOption("bn", "Bengali"),
        CheckboxFilterOption("bs", "Bosnian"),
        CheckboxFilterOption("my", "Burmese"),
        CheckboxFilterOption("km", "Cambodian"),
        CheckboxFilterOption("ca", "Catalan"),
        CheckboxFilterOption("ceb", "Cebuano"),
        CheckboxFilterOption("zh_hk", "Chinese (Cantonese)"),
        CheckboxFilterOption("zh_tw", "Chinese (Traditional)"),
        CheckboxFilterOption("hr", "Croatian"),
        CheckboxFilterOption("en_us", "English (United States)"),
        CheckboxFilterOption("eo", "Esperanto"),
        CheckboxFilterOption("et", "Estonian"),
        CheckboxFilterOption("fo", "Faroese"),
        CheckboxFilterOption("ka", "Georgian"),
        CheckboxFilterOption("gn", "Guarani"),
        CheckboxFilterOption("gu", "Gujarati"),
        CheckboxFilterOption("ht", "Haitian Creole"),
        CheckboxFilterOption("ha", "Hausa"),
        CheckboxFilterOption("is", "Icelandic"),
        CheckboxFilterOption("ig", "Igbo"),
        CheckboxFilterOption("ga", "Irish"),
        CheckboxFilterOption("jv", "Javanese"),
        CheckboxFilterOption("kn", "Kannada"),
        CheckboxFilterOption("kk", "Kazakh"),
        CheckboxFilterOption("ku", "Kurdish"),
        CheckboxFilterOption("ky", "Kyrgyz"),
        CheckboxFilterOption("lo", "Laothian"),
        CheckboxFilterOption("lv", "Latvian"),
        CheckboxFilterOption("lt", "Lithuanian"),
        CheckboxFilterOption("lb", "Luxembourgish"),
        CheckboxFilterOption("mk", "Macedonian"),
        CheckboxFilterOption("mg", "Malagasy"),
        CheckboxFilterOption("ml", "Malayalam"),
        CheckboxFilterOption("mt", "Maltese"),
        CheckboxFilterOption("mi", "Maori"),
        CheckboxFilterOption("mr", "Marathi"),
        CheckboxFilterOption("mo", "Moldavian"),
        CheckboxFilterOption("mn", "Mongolian"),
        CheckboxFilterOption("ne", "Nepali"),
        CheckboxFilterOption("no", "Norwegian"),
        CheckboxFilterOption("ny", "Nyanja"),
        CheckboxFilterOption("ps", "Pashto"),
        CheckboxFilterOption("fa", "Persian"),
        CheckboxFilterOption("rm", "Romansh"),
        CheckboxFilterOption("sm", "Samoan"),
        CheckboxFilterOption("sr", "Serbian"),
        CheckboxFilterOption("sh", "Serbo-Croatian"),
        CheckboxFilterOption("st", "Sesotho"),
        CheckboxFilterOption("sn", "Shona"),
        CheckboxFilterOption("sd", "Sindhi"),
        CheckboxFilterOption("si", "Sinhalese"),
        CheckboxFilterOption("sk", "Slovak"),
        CheckboxFilterOption("sl", "Slovenian"),
        CheckboxFilterOption("so", "Somali"),
        CheckboxFilterOption("sw", "Swahili"),
        CheckboxFilterOption("tg", "Tajik"),
        CheckboxFilterOption("ta", "Tamil"),
        CheckboxFilterOption("ti", "Tigrinya"),
        CheckboxFilterOption("to", "Tonga"),
        CheckboxFilterOption("tk", "Turkmen"),
        CheckboxFilterOption("ur", "Urdu"),
        CheckboxFilterOption("uz", "Uzbek"),
        CheckboxFilterOption("yo", "Yoruba"),
        CheckboxFilterOption("zu", "Zulu"),
        CheckboxFilterOption("_t", "Other"),
        // Lang options from bato.to brows not in publish.bato.to
        CheckboxFilterOption("eu", "Basque"),
        CheckboxFilterOption("pt-PT", "Portuguese (Portugal)"),
    ).filterNot { it.value == siteLang }
}
