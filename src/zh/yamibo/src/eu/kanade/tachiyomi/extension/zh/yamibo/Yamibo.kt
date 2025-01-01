package eu.kanade.tachiyomi.extension.zh.yamibo

import android.app.Application
import android.content.Intent
import android.util.Log
import eu.kanade.tachiyomi.AppInfo
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

@Suppress("unused")
class Yamibo : HttpSource() {
    override val name = "百合会论坛"
    override val baseUrl = "https://bbs.yamibo.com"
    override val lang = "zh"
    override val supportsLatest = true

    private val forumUrl = "/index.php?mobile=2"
    private val searchPageUrl = "/search.php?mod=forum&mobile=2"
    private val searchUrl = "/search.php?mod=forum"
    private val popularUrl =
        "/forum.php?mod=forumdisplay&fid=30&filter=heat&orderby=heats&mobile=2"
    private val latestUrl =
        "/forum.php?mod=forumdisplay&fid=30&filter=lastpost&orderby=lastpost&mobile=2"

    override val client: OkHttpClient = network.cloudflareClient

    // `headers` created here is only used in requests for thumbnails.
    // See app/src/main/java/eu/kanade/tachiyomi/data/coil/MangaCoverFetcher.kt
    override fun headersBuilder() = Headers.Builder()
        .add("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        .add("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        .add("DNT", "1")
        .add("Referer", "$baseUrl/")
        .add("Sec-Fetch-Dest", "image")
        .add("Sec-Fetch-Mode", "no-cors")
        .add("Sec-Fetch-Site", "cross-site")
        .add("Upgrade-Insecure-Requests", "1")
        .add(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.3 Tachiyomi/${AppInfo.getVersionName()}",
        )

    private fun documentHeadersBuilder() = headersBuilder()
        .set(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        )
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "none")
        .set("Sec-Fetch-User", "?1")

    private val documentHeaders by lazy { documentHeadersBuilder().build() }

    private fun documentHeaders(referer: String) = documentHeaders.newBuilder()
        .set("Referer", referer)
        .build()

    private fun imageHeaders(referer: String?): Headers {
        return if (referer != null) {
            headers.newBuilder()
                .set("Referer", referer)
                .build()
        } else {
            headers
        }
    }

    private val parseMangaSelector = "div.threadlist > ul > li.list"

    private val parseMangaNextPageSelector = "div.pg > a.nxt"

    private val searchMap = mutableMapOf<String, HttpUrl>()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        Log.d("Yamibo", "searchMangaRequest: $page $query")

        if (page != 1) {
            var lastUrl = searchMap[query]
            Log.d("Yamibo", "lastUrl: $lastUrl")
            if (lastUrl == null) {
                lastUrl =
                    client.newCall(searchMangaRequest(1, query, filters)).execute().request.url
                searchMap[query] = lastUrl
                Log.d("Yamibo", "fetched lastUrl: $lastUrl")
            }

            val url = lastUrl.newBuilder().setQueryParameter("page", page.toString()).build()
            return GET(url.toString(), documentHeaders(lastUrl.toString()))
        }

        val pageUrl = baseUrl + searchPageUrl
        val formHash =
            client.newCall(GET(pageUrl, documentHeaders(baseUrl + forumUrl)))
                .execute()
                .asJsoup()
                .select("input[name=formhash]")
                .attr("value")

        Log.d("Yamibo", "formHash: $formHash")

        val form = FormBody.Builder()
            .add("formhash", formHash)
            .add("srhfid", "")
            .add("srchtxt", query)
            .add("searchsubmit", "yes")
            .build()

        return POST(baseUrl + searchUrl, documentHeaders(pageUrl), form)
    }

    companion object {
        private fun tidFromUrl(url: String): Int {
            Log.d("Yamibo", "tidFromUrl: $url")
            return url.substringAfter("thread-").substringBefore("-").toInt()
        }

        private fun urlFromTid(tid: Int) = "/thread-$tid-1-1.html"

        private fun tidFromAnyUrl(url: String): Int {
            Log.d("Yamibo", "tidFromAnyUrl: $url")
            val t = url.substringAfter("thread-")
            return if (t.length == url.length) {
                url.substringAfter("tid=").substringBefore("&")
            } else {
                t.substringBefore("-")
            }.toInt()
        }
    }

    private val baseHttpUrl = baseUrl.toHttpUrl()

    private fun tidFromAnyHttpUrlChecked(url: HttpUrl): Int? {
        Log.d("Yamibo", "tidFromAnyHttpUrlChecked: $url")
        if (url.host != baseHttpUrl.host) {
            return null
        }
        val path = url.encodedPath
        try {
            if (path == "/forum.php") {
                return url.queryParameter("tid")?.toInt()
            }
            if (path.startsWith("/thread-")) {
                return tidFromUrl(path)
            }
        } catch (_: NumberFormatException) {
        }
        return null
    }

    private fun SManga.tid() = tidFromUrl(url)

    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
    }

    private val dateMap = mutableMapOf<Int, Long>()

    private fun parseMangaFromElement(element: Element): SManga {
        val rawUrl = element.selectFirst("> a")!!.attr("href")
        Log.d("Yamibo", "parseMangaFromElement: $rawUrl")

        val tid = tidFromAnyUrl(rawUrl)
        val details = detailMap[tid]
        if (details != null) {
            Log.d("Yamibo", "parseMangaFromElement: cached: $tid $details")
            return toSManga(urlFromTid(tid), details)
        }

        val muser = element.selectFirst("div.muser")!!
        val date = muser.selectFirst("span.mtime")!!.text().trim()
        dateMap[tid] = dateFormat.parse(date)!!.time

        val r = SManga.create().apply {
            url = urlFromTid(tid)
            author = muser.selectFirst("h3")!!.text().trim()
            title = element.selectFirst("div.threadlist_tit > em")!!.text().trim()
            description = element.selectFirst("div.threadlist_mes")!!.text().trim()
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        }
        Log.d("Yamibo", "parseMangaFromElement: $tid ${r.author} ${r.title} ${r.url}")
        return r
    }

    private val allowedFids = setOf(13, 30, 37, 46)

    private fun parseMangaFromElementChecked(element: Element): SManga? {
        val fid = element.selectFirst("div.threadlist_foot > ul > li.mr > a")!!
            .attr("href").substringAfter("fid=").substringBefore("&").toInt()

        if (fid !in allowedFids) {
            Log.d("Yamibo", "maybeParseMangaFromElement: fid $fid not allowed")
            return null
        }

        Log.d("Yamibo", "maybeSearchMangaFromElement: fid $fid")
        return parseMangaFromElement(element)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        Log.d("Yamibo", "searchMangaParse: ${response.request.method} ${response.request.url}")

        val document = response.asJsoup()
        val mangas =
            document.select(parseMangaSelector).mapNotNull { parseMangaFromElementChecked(it) }
        val hasNext = document.selectFirst(parseMangaNextPageSelector) != null

        Log.d("Yamibo", "searchMangaParse: got ${mangas.size} $hasNext")
        return MangasPage(mangas, hasNext)
    }

    private fun mangaProtoFromTid(tid: Int) =
        SManga.create().apply {
            url = urlFromTid(tid)
            title = "ID = $tid"
        }

    override fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> {
        Log.d("Yamibo", "fetchSearchManga: $page $query $filters")
        val theQuery = query.trim()

        assert(filters.isEmpty())
        if (theQuery.isEmpty()) {
            return Observable.just(MangasPage(emptyList(), false))
        }

        val rawTids = theQuery.removePrefix("tid:")
        if (rawTids.length != theQuery.length) {
            val tids = rawTids.split(",").mapNotNull {
                try {
                    it.toInt()
                } catch (_: NumberFormatException) {
                    null
                }
            }
            Log.d("Yamibo", "fetchSearchManga: tids: $tids")
            return Observable.from(tids)
                .map { mangaProtoFromTid(it) }
                .flatMap { fetchMangaDetails(it) }
                .toList()
                .map { MangasPage(it, false) }
        }

        var url = theQuery.removePrefix("url:")
        if (url.length == theQuery.length) {
            url = theQuery.removePrefix("https://")
        }
        if (url.length != theQuery.length) {
            try {
                val tid = tidFromAnyUrl(url)
                Log.d("Yamibo", "fetchSearchManga: tid: $tid")
                return fetchMangaDetails(mangaProtoFromTid(tid)).map {
                    MangasPage(listOf(it), false)
                }
            } catch (_: NumberFormatException) {
            }
        }

        return Observable.defer {
            client.newCall(searchMangaRequest(page, theQuery, filters)).asObservableSuccess()
        }
            .map { response ->
                Log.d("Yamibo", "$theQuery -> ${response.request.url}")
                searchMap[theQuery] = response.request.url
                searchMangaParse(response)
            }
    }

    private val imgMap = mutableMapOf<String, String>()

    private fun toImageUrl(referer: String, url: String): String {
        Log.d("Yamibo", "toImageUrl: $referer -> $url")
        val r = baseHttpUrl.resolve(url)!!.toString()
        Log.d("Yamibo", "toImageUrl: $r")
        imgMap[r] = baseHttpUrl.resolve(referer)!!.toString()
        return r
    }

    override fun imageRequest(page: Page): Request {
        val url = page.imageUrl!!
        val referer = imgMap[url]
        Log.d("Yamibo", "imageRequest: $referer -> $url")
        return GET(url, imageHeaders(referer))
    }

    private class MangaDetails(
        val title: String,
        val author: String,
        val genre: String,
        val description: String,
        val imgUrls: List<String>,
        val links: List<Pair<String, Int>>,
    ) {
        override fun toString(): String {
            return "[$author $title ${imgUrls.size}]"
        }
    }

    private fun toSManga(theUrl: String, details: MangaDetails): SManga {
        Log.d("Yamibo", "MangaDetails.toSManga: $theUrl $details")
        val img = details.imgUrls.firstOrNull()
        val r = SManga.create().apply {
            url = theUrl
            title = details.title
            author = details.author
            genre = details.genre
            description = details.description
            thumbnail_url = if (img != null) {
                toImageUrl(theUrl, img)
            } else {
                null
            }
            update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            initialized = true
        }
        return r
    }

    // Cache the image URLs to save requests in `fetchPageList` after `fetchMangaDetails`.
    private val detailMap = mutableMapOf<Int, MangaDetails>()

    private fun parseDetails(document: Document, tid: Int): MangaDetails {
        Log.d("Yamibo", "parseDetails: $tid ${document.location()}")

        // Exclude images in the top banner.
        val thread = document.selectFirst("div.viewthread")!!
        val forum = document.selectFirst("div.header > h2")!!.text().trim()

        val authi = thread.selectFirst("ul.authi")!!
        val theAuthor = authi.selectFirst("li.mtit > span.z")!!.text().trim()
        val date = authi.selectFirst("li.mtime")!!.ownText().trim()
        dateMap[tid] = dateFormat.parse(date)!!.time

        val head = thread.selectFirst("div.view_tit")!!
        val category = head.selectFirst("em")!!.text().trim().trimStart('[').trimEnd(']')

        // Find posts from the author and without quotes.
        val posts = Elements(
            thread.select("div.pi").filter { it: Element ->
                it.parent()?.selectFirst("ul.authi > li.mtit > span.z")?.text()
                    ?.trim() == theAuthor && it.selectFirst("div.quote") == null
            },
        )
        val msg = posts.select("div.message")

        // Remove garbled texts.
        msg.select("font.jammer").remove()
        msg.select("span[style=\"display:none\"]").remove()

        // Remove the edited time.
        msg.select("i.pstatus").remove()

        // Remove other images.
        posts.select("div.avatar").remove()

        // smiley, hrline
        posts.select("img[src*=\"static/image\"]").remove()

        val theLinks = msg.select("a[href]").mapNotNull {
            baseHttpUrl.resolve(it.attr("href"))?.let { target ->
                tidFromAnyHttpUrlChecked(target)?.let { r ->
                    Pair(it.text().trim(), r)
                }
            }
        }

        val r = MangaDetails(
            author = theAuthor,
            title = head.ownText().trim(),
            genre = "$forum, $category",
            description = msg.joinToString("\n") { it.text().trim() },
            // `ul.img_one` after `div.message` can also contain images.
            imgUrls = posts.select("img[src]").map { it.attr("src") },
            links = theLinks,
        )
        detailMap[tid] = r
        Log.d("Yamibo", "parseDetails: $tid $r")
        return r
    }

    private fun fetchDetails(tid: Int, url: String): Observable<MangaDetails> {
        Log.d("Yamibo", "fetchDetails: $tid $url")
        val r = detailMap[tid]
        if (r != null) {
            Log.d("Yamibo", "fetchDetails: cached $tid $r")
            return Observable.just(r)
        }

        return client.newCall(GET(baseUrl + url, headers))
            .asObservableSuccess()
            .map { parseDetails(it.asJsoup(), tid) }
    }

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        val tid = manga.tid()
        Log.d("Yamibo", "fetchMangaDetails: $tid ${manga.title}")
        return fetchDetails(tid, manga.url).map { toSManga(manga.url, it) }
    }

    override fun mangaDetailsParse(response: Response) =
        throw UnsupportedOperationException("mangaDetailsParse")

    private val prevLink = mutableMapOf<Int, Int>()

    private fun SManga.chapters(): List<SChapter> {
        val tid = tid()
        Log.d("Yamibo", "SManga.chapters: $tid")
        if (thumbnail_url.isNullOrEmpty()) {
            return emptyList()
        }
        val date = dateMap[tid]!!
        val r = SChapter.create()
        r.name = "第1页"
        r.date_upload = date
        r.url = url

        val links = detailMap[tid]?.links
        if (links.isNullOrEmpty()) {
            return listOf(r)
        }

        val chapters = mutableListOf(r)
        for ((text, target) in links) {
            val c = SChapter.create()
            c.name = "➤ $text"
            c.date_upload = date
            c.url = target.toString()
            prevLink[target] = tid
            chapters.add(c)
        }
        return chapters
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val tid = manga.tid()
        Log.d("Yamibo", "fetchChapterList: $tid ${manga.title}")
        if (!manga.initialized || tid !in dateMap) {
            return fetchMangaDetails(manga).map { it.chapters() }
        }
        return Observable.just(manga.chapters())
    }

    override fun chapterListParse(response: Response) =
        throw UnsupportedOperationException("chapterListParse")

    private fun toPageList(referer: String, urls: List<String>): List<Page> {
        return urls.mapIndexed { i, url ->
            Page(i, "", toImageUrl(referer, url))
        }
    }

    private val app = Injekt.get<Application>()

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        val url = chapter.url
        val name = chapter.name
        Log.d("Yamibo", "fetchPageList: $name: $url")
        if (chapter.name.startsWith("➤")) {
            val tid = chapter.url.toInt()
            val tids = mutableListOf(tid)
            var prev = prevLink[tid]
            while (prev != null && prev !in tids) {
                tids.add(prev)
                prev = prevLink[prev]
            }

            val query = "tid:" + tids.reversed().joinToString(",")
            val intent = Intent().apply {
                action = "eu.kanade.tachiyomi.SEARCH"
                putExtra("query", query)
                putExtra("package", "eu.kanade.tachiyomi.extension.zh.yamibo")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            Log.d("Yamibo", "fetchPageList: $name: navigate to $query via $app")
            try {
                app.startActivity(intent)
            } catch (e: Exception) {
                Log.e("Yamibo", "fetchPageList: failed to send intent: $e")
            }
            return Observable.just(emptyList())
        }
        return fetchDetails(tidFromUrl(url), url).map { toPageList(url, it.imgUrls) }
    }

    override fun pageListParse(response: Response) =
        throw UnsupportedOperationException("pageListParse")

    override fun imageUrlParse(response: Response) =
        throw UnsupportedOperationException("imageUrlParse")

    override fun popularMangaRequest(page: Int): Request {
        Log.d("Yamibo", "popularMangaRequest: $page")
        return GET(
            baseUrl + if (page > 1) {
                "$popularUrl&page=$page"
            } else {
                popularUrl
            },
            documentHeaders,
        )
    }

    override fun popularMangaParse(response: Response): MangasPage {
        Log.d("Yamibo", "popularMangaParse: ${response.request.method} ${response.request.url}")

        val document = response.asJsoup()
        val mangas =
            document.select(parseMangaSelector).map { parseMangaFromElement(it) }
        val hasNext = document.selectFirst(parseMangaNextPageSelector) != null

        Log.d("Yamibo", "popularMangaParse: got ${mangas.size} $hasNext")
        return MangasPage(mangas, hasNext)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        Log.d("Yamibo", "latestUpdatesRequest: $page")
        return GET(
            baseUrl + if (page > 1) {
                "$latestUrl&page=$page"
            } else {
                latestUrl
            },
            documentHeaders,
        )
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        Log.d("Yamibo", "latestUpdatesParse: ${response.request.method} ${response.request.url}")
        return popularMangaParse(response)
    }
}
