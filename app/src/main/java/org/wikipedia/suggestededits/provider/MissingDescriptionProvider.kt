package org.wikipedia.suggestededits.provider

import io.reactivex.Observable
import io.reactivex.functions.BiFunction
import org.wikipedia.dataclient.Service
import org.wikipedia.dataclient.ServiceFactory
import org.wikipedia.dataclient.WikiSite
import org.wikipedia.dataclient.mwapi.MwQueryPage
import org.wikipedia.dataclient.mwapi.MwQueryResponse
import org.wikipedia.dataclient.restbase.page.RbPageSummary
import org.wikipedia.page.PageTitle
import org.wikipedia.wikidata.Entities
import java.util.*
import java.util.concurrent.Semaphore

object MissingDescriptionProvider {
    private val mutex : Semaphore = Semaphore(1)

    private val articlesWithMissingDescriptionCache : Stack<String> = Stack()
    private var articlesWithMissingDescriptionCacheLang : String = ""
    private val articlesWithTranslatableDescriptionCache : Stack<Pair<PageTitle, PageTitle>> = Stack()
    private var articlesWithTranslatableDescriptionCacheFromLang : String = ""
    private var articlesWithTranslatableDescriptionCacheToLang : String = ""

    private val imagesWithMissingCaptionsCache : Stack<MwQueryPage> = Stack()
    private var imagesWithMissingCaptionsCacheLang : String = ""
    private val imagesWithTranslatableCaptionCache : Stack<Pair<String, MwQueryPage>> = Stack()
    private var imagesWithTranslatableCaptionCacheFromLang : String = ""
    private var imagesWithTranslatableCaptionCacheToLang : String = ""

    // TODO: add a maximum-retry limit -- it's currently infinite, or until disposed.

    @Deprecated("Remove when the new API is deployed to production.")
    fun getNextArticleWithMissingDescription(wiki: WikiSite): Observable<RbPageSummary> {
        return Observable.fromCallable { mutex.acquire() }.flatMap {
            var cachedTitle = ""
            if (articlesWithMissingDescriptionCacheLang != wiki.languageCode()) {
                // evict the cache if the language has changed.
                articlesWithMissingDescriptionCache.clear()
            }
            if (!articlesWithMissingDescriptionCache.empty()) {
                cachedTitle = articlesWithMissingDescriptionCache.pop()
            }

            if (cachedTitle.isNotEmpty()) {
                Observable.just(cachedTitle)
            } else {
                ServiceFactory.get(wiki).randomWithPageProps
                        .map<String> { response ->
                            var title: String? = null
                            articlesWithMissingDescriptionCacheLang = wiki.languageCode()
                            for (page in response.query()!!.pages()!!) {
                                if (page.pageProps() == null || page.pageProps()!!.isDisambiguation || !page.description().isNullOrEmpty()) {
                                    continue
                                }
                                articlesWithMissingDescriptionCache.push(page.title())
                            }
                            if (!articlesWithMissingDescriptionCache.empty()) {
                                title = articlesWithMissingDescriptionCache.pop()
                            }
                            if (title == null) {
                                throw ListEmptyException()
                            }
                            title
                        }
            }
        }.flatMap { title -> ServiceFactory.getRest(wiki).getSummary(null, title) }
                .retry { t: Throwable -> t is ListEmptyException }
                .doFinally { mutex.release() }
    }

    fun getNextArticleWithMissingDescriptionNew(wiki: WikiSite): Observable<RbPageSummary> {
        return ServiceFactory.get(wiki).getEditorTaskMissingDescriptions(wiki.languageCode())
                .map<MwQueryPage> { response ->
                    response.query()!!.pages()!![0]
                }
                .flatMap { page: MwQueryPage -> ServiceFactory.getRest(wiki).getSummary(null, page.title()) }
                .retry { t: Throwable -> t is ListEmptyException }
    }

    @Deprecated("Remove when the new API is deployed to production.")
    fun getNextArticleWithMissingDescription(sourceWiki: WikiSite, targetLang: String, sourceLangMustExist: Boolean): Observable<Pair<RbPageSummary, RbPageSummary>> {
        return Observable.fromCallable { mutex.acquire() }.flatMap {
            val targetWiki = WikiSite.forLanguageCode(targetLang)
            var cachedPair: Pair<PageTitle, PageTitle>? = null
            if (articlesWithTranslatableDescriptionCacheFromLang != sourceWiki.languageCode()
                    || articlesWithTranslatableDescriptionCacheToLang != targetLang) {
                // evict the cache if the language has changed.
                articlesWithTranslatableDescriptionCache.clear()
            }
            if (!articlesWithTranslatableDescriptionCache.empty()) {
                cachedPair = articlesWithTranslatableDescriptionCache.pop()
            }

            if (cachedPair != null) {
                Observable.just(cachedPair)
            } else {
                ServiceFactory.get(sourceWiki).randomWithPageProps
                        .flatMap { response: MwQueryResponse ->
                            val qNumbers = ArrayList<String>()
                            for (page in response.query()!!.pages()!!) {
                                if (page.pageProps() == null || page.pageProps()!!.isDisambiguation || page.pageProps()!!.wikiBaseItem.isEmpty()) {
                                    continue
                                }
                                qNumbers.add(page.pageProps()!!.wikiBaseItem)
                            }
                            ServiceFactory.get(WikiSite(Service.WIKIDATA_URL))
                                    .getWikidataLabelsAndDescriptions(qNumbers.joinToString("|"))
                        }
                        .map<Pair<PageTitle, PageTitle>> { response ->
                            var sourceAndTargetPageTitles: Pair<PageTitle, PageTitle>? = null
                            articlesWithTranslatableDescriptionCacheFromLang = sourceWiki.languageCode()
                            articlesWithTranslatableDescriptionCacheToLang = targetLang
                            for (q in response.entities()!!.keys) {
                                val entity = response.entities()!![q]
                                if (entity == null
                                        || entity.descriptions().containsKey(targetLang)
                                        || sourceLangMustExist && !entity.descriptions().containsKey(sourceWiki.languageCode())
                                        || !entity.sitelinks().containsKey(sourceWiki.dbName())
                                        || !entity.sitelinks().containsKey(targetWiki.dbName())) {
                                    continue
                                }
                                articlesWithTranslatableDescriptionCache.push(Pair(PageTitle(entity.sitelinks()[targetWiki.dbName()]!!.title, targetWiki),
                                        PageTitle(entity.sitelinks()[sourceWiki.dbName()]!!.title, sourceWiki)))
                            }
                            if (!articlesWithTranslatableDescriptionCache.empty()) {
                                sourceAndTargetPageTitles = articlesWithTranslatableDescriptionCache.pop()
                            }
                            if (sourceAndTargetPageTitles == null) {
                                throw ListEmptyException()
                            }
                            sourceAndTargetPageTitles
                        }
            }
        }.flatMap { sourceAndTargetPageTitles: Pair<PageTitle, PageTitle> -> getSummary(sourceAndTargetPageTitles) }
                .retry { t: Throwable -> t is ListEmptyException }
                .doFinally { mutex.release() }
    }

    fun getNextArticleWithMissingDescriptionNew(sourceWiki: WikiSite, targetLang: String): Observable<Pair<RbPageSummary, RbPageSummary>> {
        val targetWiki = WikiSite.forLanguageCode(targetLang)
        return ServiceFactory.get(sourceWiki).getEditorTaskTranslatableDescriptions(sourceWiki.languageCode(), targetLang)
                .flatMap { response: MwQueryResponse ->
                    val qNumbers = ArrayList<String>()
                    for (page in response.query()!!.pages()!!) {
                        qNumbers.add(page.title())
                    }
                    ServiceFactory.get(WikiSite(Service.WIKIDATA_URL))
                            .getWikidataLabelsAndDescriptions(qNumbers.joinToString("|"))
                }
                .map<Pair<PageTitle, PageTitle>> { response ->
                    var sourceAndTargetPageTitles: Pair<PageTitle, PageTitle>? = null
                    for (q in response.entities()!!.keys) {
                        val entity = response.entities()!![q]
                        if (entity == null
                                || entity.descriptions().containsKey(targetLang)
                                || !entity.descriptions().containsKey(sourceWiki.languageCode())
                                || !entity.sitelinks().containsKey(sourceWiki.dbName())
                                || !entity.sitelinks().containsKey(targetWiki.dbName())) {
                            continue
                        }
                        sourceAndTargetPageTitles = Pair(PageTitle(entity.sitelinks()[sourceWiki.dbName()]!!.title, sourceWiki),
                                PageTitle(entity.sitelinks()[targetWiki.dbName()]!!.title, targetWiki))
                        break
                    }
                    if (sourceAndTargetPageTitles == null) {
                        throw ListEmptyException()
                    }
                    sourceAndTargetPageTitles
                }
                .flatMap { sourceAndTargetPageTitles: Pair<PageTitle, PageTitle> -> getSummary(sourceAndTargetPageTitles) }
                .retry { t: Throwable -> t is ListEmptyException }
    }

    private fun getSummary(titles: Pair<PageTitle, PageTitle>): Observable<Pair<RbPageSummary, RbPageSummary>> {
        return Observable.zip(ServiceFactory.getRest(titles.first.wikiSite).getSummary(null, titles.first.prefixedText),
                ServiceFactory.getRest(titles.second.wikiSite).getSummary(null, titles.second.prefixedText),
                BiFunction<RbPageSummary, RbPageSummary, Pair<RbPageSummary, RbPageSummary>> { source, target -> Pair(source, target) })
    }

    fun getNextImageWithMissingCaption(lang: String): Observable<MwQueryPage> {
        return Observable.fromCallable { mutex.acquire() }.flatMap {
            var cachedTitle: MwQueryPage? = null
            if (imagesWithMissingCaptionsCacheLang != lang) {
                // evict the cache if the language has changed.
                imagesWithMissingCaptionsCache.clear()
            }
            if (!imagesWithMissingCaptionsCache.empty()) {
                cachedTitle = imagesWithMissingCaptionsCache.pop()
            }

            if (cachedTitle != null) {
                Observable.just(cachedTitle)
            } else {
                ServiceFactory.get(WikiSite(Service.COMMONS_URL)).randomWithImageInfo
                        .flatMap<Entities, MwQueryPage>({ result: MwQueryResponse ->
                            val pages = result.query()!!.pages()
                            val mNumbers = ArrayList<String>()
                            for (page in pages!!) {
                                if (page.imageInfo()?.mimeType == "image/jpeg") {
                                    mNumbers.add("M" + page.pageId())
                                }
                            }
                            if (mNumbers.isEmpty()) {
                                throw ListEmptyException()
                            }
                            ServiceFactory.get(WikiSite(Service.COMMONS_URL)).getWikidataLabelsAndDescriptions(mNumbers.joinToString("|"))
                        }, { mwQueryResponse, entities ->
                            imagesWithMissingCaptionsCacheLang = lang
                            for (m in entities.entities()!!.keys) {
                                if (entities.entities()!![m]?.labels() != null && entities.entities()!![m]?.labels()!!.containsKey(lang)) {
                                    continue
                                }
                                for (page in mwQueryResponse.query()!!.pages()!!) {
                                    if (m == "M" + page.pageId()) {
                                        imagesWithMissingCaptionsCache.push(page)
                                    }
                                }
                            }
                            var item: MwQueryPage? = null
                            if (!imagesWithMissingCaptionsCache.empty()) {
                                item = imagesWithMissingCaptionsCache.pop()
                            }
                            if (item == null) {
                                throw ListEmptyException()
                            }
                            item
                        })
                        .retry { t: Throwable -> t is ListEmptyException }
            }
        }.doFinally { mutex.release() }
    }

    fun getNextImageWithMissingCaption(sourceLang: String, targetLang: String): Observable<Pair<String, MwQueryPage>> {
        return Observable.fromCallable { mutex.acquire() }.flatMap {
            var cachedPair: Pair<String, MwQueryPage>? = null
            if (imagesWithTranslatableCaptionCacheFromLang != sourceLang
                    || imagesWithTranslatableCaptionCacheToLang != targetLang) {
                // evict the cache if the language has changed.
                imagesWithTranslatableCaptionCache.clear()
            }
            if (!imagesWithTranslatableCaptionCache.empty()) {
                cachedPair = imagesWithTranslatableCaptionCache.pop()
            }

            if (cachedPair != null) {
                Observable.just(cachedPair)
            } else {
                ServiceFactory.get(WikiSite(Service.COMMONS_URL)).randomWithImageInfo
                        .flatMap<Entities, Pair<String, MwQueryPage>>({ result: MwQueryResponse ->
                            val pages = result.query()!!.pages()
                            val mNumbers = ArrayList<String>()
                            for (page in pages!!) {
                                if (page.imageInfo()?.mimeType == "image/jpeg") {
                                    mNumbers.add("M" + page.pageId())
                                }
                            }
                            if (mNumbers.isEmpty()) {
                                throw ListEmptyException()
                            }
                            ServiceFactory.get(WikiSite(Service.COMMONS_URL)).getWikidataLabelsAndDescriptions(mNumbers.joinToString("|"))
                        }, { mwQueryResponse, entities ->
                            imagesWithTranslatableCaptionCacheFromLang = sourceLang
                            imagesWithTranslatableCaptionCacheToLang = targetLang

                            for (m in entities.entities()!!.keys) {
                                if (entities.entities()!![m]?.labels() == null || !entities.entities()!![m]?.labels()!!.containsKey(sourceLang)
                                        || entities.entities()!![m]?.labels()!!.containsKey(targetLang)) {
                                    continue
                                }
                                for (page in mwQueryResponse.query()!!.pages()!!) {
                                    if (m == "M" + page.pageId()) {
                                        imagesWithTranslatableCaptionCache.push(Pair(entities.entities()!![m]?.labels()!![sourceLang]!!.value(), page))
                                    }
                                }
                            }
                            var item: Pair<String, MwQueryPage>? = null
                            if (!imagesWithTranslatableCaptionCache.empty()) {
                                item = imagesWithTranslatableCaptionCache.pop()
                            }
                            if (item == null) {
                                throw ListEmptyException()
                            }
                            item
                        })
                        .retry { t: Throwable -> t is ListEmptyException }
            }
        }.doFinally { mutex.release() }
    }

    private class ListEmptyException : RuntimeException()
}
