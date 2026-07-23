package com.morningdigest.app.data.repository

import com.morningdigest.app.data.alert.CustomWeatherAlertEngine
import com.morningdigest.app.data.facts.FactProvider
import com.morningdigest.app.data.local.AppDatabase
import com.morningdigest.app.data.local.ReportDao
import com.morningdigest.app.data.local.ReportMapper
import com.morningdigest.app.data.model.BitcoinInfo
import com.morningdigest.app.data.model.ChartPoint
import com.morningdigest.app.data.model.CurrencyInfo
import com.morningdigest.app.data.model.DayPartForecast
import com.morningdigest.app.data.model.DigestReport
import com.morningdigest.app.data.model.NewsHeadline
import com.morningdigest.app.data.model.NewsInfo
import com.morningdigest.app.data.model.OneCallHourly
import com.morningdigest.app.data.model.WeatherAlert
import com.morningdigest.app.data.model.WeatherAlertsInfo
import com.morningdigest.app.data.model.WeatherDayForecast
import com.morningdigest.app.data.model.WeatherToday
import com.morningdigest.app.data.model.WeatherTomorrow
import com.morningdigest.app.data.model.WatchlistEntry
import com.morningdigest.app.data.prefs.AppSettings
import com.morningdigest.app.data.prefs.AssetRef
import com.morningdigest.app.data.prefs.AssetType
import com.morningdigest.app.data.prefs.CurrencyPairConfig
import com.morningdigest.app.data.prefs.CustomAlertRules
import com.morningdigest.app.data.remote.CoinGeckoApi
import com.morningdigest.app.data.remote.CryptoCatalog
import com.morningdigest.app.data.remote.FrankfurterApi
import com.morningdigest.app.data.remote.BusinessFeedCatalog
import com.morningdigest.app.data.remote.NewsFeedCatalog
import com.morningdigest.app.data.remote.OpenWeatherApi
import com.morningdigest.app.data.remote.PoliticsFeedCatalog
import com.morningdigest.app.data.remote.RssFeedFetcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

private const val TAG = "DigestRepository"
private const val HISTORY_LIMIT = 30
// How many of the most recently shown facts to avoid repeating - comfortably
// less than the pool size so there's always a real choice left.
private const val RECENT_FACTS_WINDOW = 10
// Each outlet is polled for a decent-sized pool (NEWS_PER_OUTLET_LIMIT) so
// there's enough to pick from; the combined, deduped result is then sorted
// newest-first and hard-capped at NEWS_TARGET_COUNT for the dashboard/notification.
private const val NEWS_PER_OUTLET_LIMIT = 20
private const val NEWS_TARGET_COUNT = 25
// Politics/Business are focused, single-topic cards - capped tighter than
// the general World News feed per the user's request ("just 10 titles").
private const val TOPIC_NEWS_TARGET_COUNT = 10

/**
 * Fetches weather (today + tomorrow), Bitcoin, EUR->NOK, and world news from
 * several outlets in parallel. Mirrors the original n8n workflow's "Continue
 * On Fail" behaviour: if one source fails, the others still complete and the
 * failing section is marked unavailable ("Unavailable") instead of aborting
 * the whole digest.
 */
class DigestRepository(
    private val weatherApi: OpenWeatherApi,
    private val coinGeckoApi: CoinGeckoApi,
    private val frankfurterApi: FrankfurterApi,
    private val rssFetcher: RssFeedFetcher,
    private val reportDao: ReportDao
) {

    suspend fun buildFreshReport(settings: AppSettings): DigestReport = coroutineScope {
        val cityQuery = "${settings.city},${settings.country}"

        // Previously-seen headline keys, so a fresh fetch can push genuinely
        // new stories to the top of the list (see fetchNews below) instead of
        // relying purely on each outlet's own pubDate, which some feeds omit
        // or misreport. Tracked separately per section so Politics/Business
        // each get their own "new on top" ordering instead of sharing one.
        val previousReport = runCatching {
            reportDao.getLatest()?.let { ReportMapper.toDomain(it) }
        }.getOrNull()
        // Facts shown in the last several reports (not just the very last
        // one), so a small refresh-happy testing session doesn't keep
        // cycling through the same handful of facts - skip all of them
        // where possible when picking a new one below.
        val recentFactTexts = runCatching {
            reportDao.getRecent(RECENT_FACTS_WINDOW)
                .mapNotNull { ReportMapper.toDomain(it).dailyFact.text.takeIf(String::isNotBlank) }
                .toSet()
        }.getOrElse { emptySet() }
        fun keysOf(info: NewsInfo?) = info?.headlines?.map { it.title.trim().lowercase() }?.toSet() ?: emptySet()
        val previousHeadlineKeys = keysOf(previousReport?.news)
        val previousPoliticsKeys = keysOf(previousReport?.politicsNews)
        val previousBusinessKeys = keysOf(previousReport?.businessNews)

        val todayDeferred = async { fetchWeatherToday(cityQuery, settings.weatherApiKey) }
        val tomorrowBundleDeferred = async { fetchTomorrowAndUpcoming(cityQuery, settings.weatherApiKey) }
        val bitcoinDeferred = async { fetchBitcoin() }
        val currencyDeferred = async { fetchCurrency(settings.currencyBase, settings.currencyTarget) }
        val newsDeferred = async { fetchNews(settings, previousHeadlineKeys) }
        val politicsDeferred = async { fetchPoliticsNews(settings, previousPoliticsKeys) }
        val businessDeferred = async { fetchBusinessNews(settings, previousBusinessKeys) }
        val marketsNewsDeferred = async { runCatching { fetchMarketsNews() }.getOrElse { NewsInfo(available = false) } }
        val cryptoNewsDeferred = async { runCatching { fetchCryptoNews() }.getOrElse { NewsInfo(available = false) } }
        val alertsDeferred = async {
            if (settings.weatherAlertsEnabled || settings.customAlertRules.enabled)
                fetchWeatherAlerts(cityQuery, settings.weatherApiKey, settings.customAlertRules)
            else WeatherAlertsInfo(available = false)
        }
        val watchlistDeferred = async {
            if (settings.extraCurrencyPairs.isNotEmpty()) fetchExtraCurrencyPairs(settings.extraCurrencyPairs)
            else emptyList()
        }

        val (tomorrow, upcomingDays) = tomorrowBundleDeferred.await()

        DigestReport(
            timestampMillis = System.currentTimeMillis(),
            weatherToday = todayDeferred.await(),
            weatherTomorrow = tomorrow,
            upcomingDays = upcomingDays,
            bitcoin = bitcoinDeferred.await(),
            currency = currencyDeferred.await(),
            news = newsDeferred.await(),
            // Re-picked on every refresh (manual or scheduled) so it changes
            // whenever the user pulls to refresh, not just once per day -
            // excluding whatever's shown up in the last several reports so
            // it doesn't keep cycling through the same few facts.
            dailyFact = FactProvider.randomFact(excludeTexts = recentFactTexts),
            weatherAlerts = alertsDeferred.await(),
            politicsNews = politicsDeferred.await(),
            businessNews = businessDeferred.await(),
            watchlist = watchlistDeferred.await(),
            marketsNews = marketsNewsDeferred.await(),
            cryptoNews = cryptoNewsDeferred.await()
        )
    }

    private suspend fun fetchWeatherToday(cityQuery: String, apiKey: String): WeatherToday =
        runCatching {
            val r = weatherApi.getCurrentWeather(cityQuery, apiKey = apiKey)
            WeatherToday(
                temp = r.main?.temp,
                feelsLike = r.main?.feelsLike,
                humidity = r.main?.humidity,
                windSpeed = r.wind?.speed,
                pressure = r.main?.pressure,
                sunrise = r.sys?.sunrise?.let { TimeUnit.SECONDS.toMillis(it) },
                sunset = r.sys?.sunset?.let { TimeUnit.SECONDS.toMillis(it) },
                description = r.weather?.firstOrNull()?.description,
                icon = r.weather?.firstOrNull()?.icon,
                tempMin = r.main?.tempMin,
                tempMax = r.main?.tempMax,
                available = true
            )
        }.getOrElse { WeatherToday(available = false) }

    /**
     * Single forecast call, split into "tomorrow" (detailed, as before) plus
     * up to two more days ("day after tomorrow", "day after that") for the
     * Next 3 Days strip - reusing one API response instead of calling the
     * forecast endpoint again per extra day.
     */
    private suspend fun fetchTomorrowAndUpcoming(cityQuery: String, apiKey: String): Pair<WeatherTomorrow, List<WeatherDayForecast>> =
        runCatching {
            val forecast = weatherApi.getForecast(cityQuery, apiKey = apiKey)
            val list = forecast.list.orEmpty()
            if (list.isEmpty()) return@runCatching WeatherTomorrow(available = false) to emptyList()

            val firstDate = list.first().dtTxt.substringBefore(" ")
            // Group every slot after today by calendar date, in order, so
            // "tomorrow", "day after", and "day after that" can be pulled out
            // cleanly instead of relying on a fixed item-count slice.
            val futureByDate = list
                .filter { it.dtTxt.substringBefore(" ") != firstDate }
                .groupBy { it.dtTxt.substringBefore(" ") }
                .toSortedMap()
            val futureDates = futureByDate.keys.toList()

            var tomorrowItems = futureDates.getOrNull(0)?.let { futureByDate[it] }.orEmpty()
            if (tomorrowItems.isEmpty()) tomorrowItems = list.take(8)
            if (tomorrowItems.isEmpty()) return@runCatching WeatherTomorrow(available = false) to emptyList()

            val avgTemp = tomorrowItems.map { it.main.temp }.average()
            val midday = tomorrowItems.firstOrNull { it.dtTxt.contains("12:00:00") }
                ?: tomorrowItems[tomorrowItems.size / 2]
            val maxPop = tomorrowItems.mapNotNull { it.pop }.maxOrNull() ?: 0.0
            val minTemp = tomorrowItems.minOf { it.main.temp }
            val maxTemp = tomorrowItems.maxOf { it.main.temp }
            val avgHumidity = tomorrowItems.map { it.main.humidity }.average()

            // Morning/afternoon/evening breakdown: pick the 3h slot closest to
            // 09:00, 15:00 and 21:00 respectively, so the card can show more
            // than just a single midday snapshot.
            fun closestTo(hour: Int) = tomorrowItems.minByOrNull { item ->
                val itemHour = item.dtTxt.substringAfter(" ").substringBefore(":").toIntOrNull() ?: 12
                kotlin.math.abs(itemHour - hour)
            }
            val parts = listOfNotNull(
                closestTo(9)?.let { DayPartForecast("Morning", it.main.temp, it.weather.firstOrNull()?.description, it.weather.firstOrNull()?.icon) },
                closestTo(15)?.let { DayPartForecast("Afternoon", it.main.temp, it.weather.firstOrNull()?.description, it.weather.firstOrNull()?.icon) },
                closestTo(21)?.let { DayPartForecast("Evening", it.main.temp, it.weather.firstOrNull()?.description, it.weather.firstOrNull()?.icon) }
            )

            val tomorrow = WeatherTomorrow(
                avgTemp = Math.round(avgTemp * 10) / 10.0,
                minTemp = Math.round(minTemp * 10) / 10.0,
                maxTemp = Math.round(maxTemp * 10) / 10.0,
                humidity = Math.round(avgHumidity).toInt(),
                windSpeed = midday.wind?.speed,
                description = midday.weather.firstOrNull()?.description,
                icon = midday.weather.firstOrNull()?.icon,
                rainChancePercent = Math.round(maxPop * 100).toInt(),
                parts = parts,
                available = true
            )

            // Day-after-tomorrow and the day after that, each condensed to a
            // single high/low/icon summary for the compact strip.
            val dayFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val weekdayFormat = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
            val upcoming = futureDates.drop(1).take(2).mapNotNull { dateKey ->
                val items = futureByDate[dateKey] ?: return@mapNotNull null
                if (items.isEmpty()) return@mapNotNull null
                val dayMidday = items.firstOrNull { it.dtTxt.contains("12:00:00") } ?: items[items.size / 2]
                val label = runCatching { weekdayFormat.format(dayFormat.parse(dateKey)!!) }.getOrElse { dateKey }
                WeatherDayForecast(
                    dayLabel = label,
                    minTemp = Math.round(items.minOf { it.main.temp } * 10) / 10.0,
                    maxTemp = Math.round(items.maxOf { it.main.temp } * 10) / 10.0,
                    description = dayMidday.weather.firstOrNull()?.description,
                    icon = dayMidday.weather.firstOrNull()?.icon,
                    rainChancePercent = items.mapNotNull { it.pop }.maxOrNull()?.let { Math.round(it * 100).toInt() } ?: 0
                )
            }

            tomorrow to upcoming
        }.getOrElse { WeatherTomorrow(available = false) to emptyList() }

    private suspend fun fetchBitcoin(): BitcoinInfo = runCatching {
        val r = coinGeckoApi.getBitcoinPrice()
        val prices = r.bitcoin ?: error("no bitcoin data")
        BitcoinInfo(
            eur = prices.eur,
            usd = prices.usd,
            nok = prices.nok,
            change24hPercent = prices.eurChange24h,
            available = true
        )
    }.getOrElse { BitcoinInfo(available = false) }

    private suspend fun fetchCurrency(base: String, target: String): CurrencyInfo = runCatching {
        val r = frankfurterApi.getRate(from = base, to = target)
        val rate = r.rates?.get(target) ?: error("no $target rate")

        // 24h change: Frankfurter has no built-in change field (unlike CoinGecko),
        // so we separately fetch a previous day's rate and diff it ourselves.
        //
        // Bug fix: the old version always compared against "system today minus
        // 1 calendar day". Frankfurter only publishes one rate per business
        // day (ECB rates aren't updated on weekends/holidays), so "latest"
        // and "yesterday" frequently resolved to the exact same business-day
        // rate - e.g. on a Saturday, "latest" is Friday's rate, and "yesterday"
        // (Friday) is *also* Friday's rate, so the diff was always 0. The fix
        // anchors on the actual date Frankfurter says "latest" belongs to
        // (r.date), then walks backwards a few calendar days until it finds a
        // response whose own returned date actually differs, guaranteeing a
        // real day-over-day comparison instead of a same-day one.
        val change = runCatching {
            val latestDateStr = r.date ?: error("no date on latest rate")
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            val latestDate = fmt.parse(latestDateStr) ?: error("unparseable date")

            var prevResponse: com.morningdigest.app.data.model.FrankfurterResponse? = null
            for (daysBack in 1..7) {
                val candidateDateStr = fmt.format(java.util.Date(latestDate.time - TimeUnit.DAYS.toMillis(daysBack.toLong())))
                val candidate = frankfurterApi.getRateOn(candidateDateStr, from = base, to = target)
                // Frankfurter snaps weekend/holiday requests to the nearest prior
                // business day, so only accept it once its own date differs from "latest".
                if (candidate.date != null && candidate.date != latestDateStr) {
                    prevResponse = candidate
                    break
                }
            }

            val prevRate = prevResponse?.rates?.get(target)
            if (prevRate != null && prevRate != 0.0) ((rate - prevRate) / prevRate) * 100.0 else null
        }.getOrNull()

        CurrencyInfo(rate = rate, change24hPercent = change, baseCurrency = base, targetCurrency = target, available = true)
    }.getOrElse { CurrencyInfo(baseCurrency = base, targetCurrency = target, available = false) }

    /**
     * Extra "From -> To" pairs from Settings > Currency Pair, on top of the
     * primary Currency Pair card. Each side can be a fiat currency or a
     * crypto coin in any combination (USD->BTC, ETH->EUR, BTC->ETH, ...).
     *
     * Rather than special-casing every combination, every asset's value is
     * first expressed in USD ("how much is 1 unit of this worth in USD"),
     * using at most one batched Frankfurter call for all the fiat codes
     * involved and one batched CoinGecko call for all the crypto ids
     * involved - regardless of how many pairs are configured. Each pair's
     * rate is then just usdValue(from) / usdValue(to).
     */
    private suspend fun fetchExtraCurrencyPairs(pairs: List<CurrencyPairConfig>): List<WatchlistEntry> {
        if (pairs.isEmpty()) return emptyList()

        val fiatCodes = pairs.flatMap { listOf(it.from, it.to) }
            .filter { it.type == AssetType.CURRENCY && !it.code.equals("USD", ignoreCase = true) }
            .map { it.code.uppercase() }
            .distinct()
        val cryptoIds = pairs.flatMap { listOf(it.from, it.to) }
            .filter { it.type == AssetType.CRYPTO }
            .map { it.code }
            .distinct()

        // usdValue(X) = how many USD is 1 unit of X worth.
        val fiatUsdValues: Map<String, Double> = if (fiatCodes.isEmpty()) emptyMap() else runCatching {
            // rates here are "units of code per 1 USD", so invert to get "USD per 1 unit of code".
            frankfurterApi.getRate(from = "USD", to = fiatCodes.joinToString(","))
                .rates.orEmpty()
                .mapNotNull { (code, unitsPerUsd) -> if (unitsPerUsd > 0) code to (1.0 / unitsPerUsd) else null }
                .toMap()
        }.getOrElse { emptyMap() }

        val cryptoUsdValues: Map<String, Double> = if (cryptoIds.isEmpty()) emptyMap() else runCatching {
            coinGeckoApi.getPrices(ids = cryptoIds.joinToString(","))
                .mapNotNull { (id, price) -> price.usd?.let { id to it } }
                .toMap()
        }.getOrElse { emptyMap() }

        fun usdValueOf(asset: AssetRef): Double? = when (asset.type) {
            AssetType.CURRENCY -> if (asset.code.equals("USD", ignoreCase = true)) 1.0 else fiatUsdValues[asset.code.uppercase()]
            AssetType.CRYPTO -> cryptoUsdValues[asset.code]
        }

        fun labelOf(asset: AssetRef): String = when (asset.type) {
            AssetType.CURRENCY -> asset.code.uppercase()
            AssetType.CRYPTO -> CryptoCatalog.ALL.firstOrNull { it.id == asset.code }?.symbol ?: asset.code
        }

        return pairs.map { pair ->
            val fromUsd = usdValueOf(pair.from)
            val toUsd = usdValueOf(pair.to)
            val rate = if (fromUsd != null && toUsd != null && toUsd != 0.0) fromUsd / toUsd else null
            WatchlistEntry(
                id = "${pair.from.type}:${pair.from.code}->${pair.to.type}:${pair.to.code}",
                label = "${labelOf(pair.from)} → ${labelOf(pair.to)}",
                isCrypto = pair.from.type == AssetType.CRYPTO || pair.to.type == AssetType.CRYPTO,
                value = rate,
                available = rate != null
            )
        }
    }

    /** Roughly a week of Bitcoin price history (in EUR), for the tap-to-see-chart on the Bitcoin card. */
    suspend fun fetchBitcoinHistory(days: Int = 7): List<ChartPoint> = runCatching {
        val r = coinGeckoApi.getMarketChart(vsCurrency = "eur", days = days)
        r.prices.orEmpty().mapNotNull { point ->
            if (point.size < 2) null else ChartPoint(timestampMillis = point[0].toLong(), value = point[1])
        }
    }.getOrElse { emptyList() }

    /** Roughly two weeks of daily rates for the configured currency pair, for the tap-to-see-chart on the currency card. */
    suspend fun fetchCurrencyHistory(base: String, target: String, days: Int = 14): List<ChartPoint> = runCatching {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val end = java.util.Date()
        val start = java.util.Date(end.time - TimeUnit.DAYS.toMillis(days.toLong()))
        val r = frankfurterApi.getTimeSeries(startDate = fmt.format(start), endDate = fmt.format(end), from = base, to = target)
        r.rates.orEmpty().mapNotNull { (dateStr, rates) ->
            val value = rates[target] ?: return@mapNotNull null
            val millis = runCatching { fmt.parse(dateStr)?.time }.getOrNull() ?: return@mapNotNull null
            ChartPoint(timestampMillis = millis, value = value)
        }.sortedBy { it.timestampMillis }
    }.getOrElse { emptyList() }

    /**
     * Severe weather alerts + custom alert rules for the configured city.
     *
     * Official severe alerts and the UV-index rule both require OpenWeather's
     * One Call 3.0 endpoint, which needs a *separate* paid subscription beyond
     * a normal free API key (the free key already used for /weather and
     * /forecast does NOT automatically include it). That call is therefore
     * fetched independently and allowed to fail on its own.
     *
     * The temp-above/below, wind, rain-probability, thunderstorm and snow
     * rules do NOT need One Call at all - they're evaluated from the same
     * free 3-hourly /forecast endpoint the "Tomorrow" card already uses, so
     * they keep working even when the API key has no One Call access (a
     * previous version of this incorrectly ran everything through the single
     * One Call call, so any 401 there silently zeroed out every custom rule,
     * not just the two that actually needed it).
     */
    private suspend fun fetchWeatherAlerts(
        cityQuery: String,
        apiKey: String,
        customAlertRules: CustomAlertRules = CustomAlertRules()
    ): WeatherAlertsInfo {
        val oneCall = runCatching {
            val geo = weatherApi.geocode(cityQuery, apiKey = apiKey).firstOrNull() ?: error("no geocoding match")
            val lat = geo.lat ?: error("no lat")
            val lon = geo.lon ?: error("no lon")
            weatherApi.getAlerts(lat = lat, lon = lon, apiKey = apiKey)
        }.getOrNull()

        val officialAlerts = oneCall?.alerts.orEmpty().map { a ->
            WeatherAlert(
                event = a.event ?: "Weather Alert",
                description = a.description ?: "",
                senderName = a.senderName ?: "",
                startMillis = a.start?.let { TimeUnit.SECONDS.toMillis(it) },
                endMillis = a.end?.let { TimeUnit.SECONDS.toMillis(it) }
            )
        }

        if (!customAlertRules.enabled) {
            return if (oneCall != null) WeatherAlertsInfo(alerts = officialAlerts, available = true)
            else WeatherAlertsInfo(available = false)
        }

        // Free-tier forecast (3-hourly), reused here purely to evaluate rules -
        // no One Call access required for temp/wind/rain/thunderstorm/snow.
        val forecastPoints = runCatching {
            weatherApi.getForecast(cityQuery, apiKey = apiKey).list.orEmpty().map { item ->
                OneCallHourly(
                    dt = item.dt,
                    temp = item.main.temp,
                    windSpeed = item.wind?.speed,
                    pop = item.pop,
                    uvi = null,
                    weather = item.weather
                )
            }
        }.getOrElse { emptyList() }

        // Best-effort UV enrichment: only filled in where the One Call hourly
        // block succeeded and has a reading within an hour of a forecast slot.
        // If One Call isn't available, uvi just stays null and the UV rule
        // simply never matches - it doesn't affect any other rule.
        val oneCallHourly = oneCall?.hourly.orEmpty()
        val evaluationPoints = if (oneCallHourly.isEmpty()) {
            forecastPoints
        } else {
            forecastPoints.map { point ->
                val nearest = oneCallHourly.minByOrNull { kotlin.math.abs(it.dt - point.dt) }
                if (nearest != null && kotlin.math.abs(nearest.dt - point.dt) <= TimeUnit.HOURS.toSeconds(1)) {
                    point.copy(uvi = nearest.uvi)
                } else point
            }
        }

        if (evaluationPoints.isEmpty() && oneCall == null) {
            // Both the free forecast and the One Call fetch failed - genuinely unavailable.
            return WeatherAlertsInfo(available = false)
        }

        val customAlerts = CustomWeatherAlertEngine.evaluate(evaluationPoints, officialAlerts, customAlertRules)
        return WeatherAlertsInfo(alerts = officialAlerts, available = true, customAlerts = customAlerts)
    }

    /**
     * Re-fetches just the weather-alerts section (official + custom rules) and
     * merges it into the latest saved report, used by the hourly background
     * alert-check worker so a fresh custom-alert match shows up on the
     * dashboard without waiting for the next full digest refresh.
     */
    suspend fun refreshWeatherAlertsSection(settings: AppSettings): DigestReport {
        val current = getLatestReport() ?: saveReport(buildFreshReport(settings))
        val cityQuery = "${settings.city},${settings.country}"
        val updatedAlerts = if (settings.weatherAlertsEnabled || settings.customAlertRules.enabled) {
            fetchWeatherAlerts(cityQuery, settings.weatherApiKey, settings.customAlertRules)
        } else {
            WeatherAlertsInfo(available = false)
        }
        val updated = current.copy(weatherAlerts = updatedAlerts)
        reportDao.insert(ReportMapper.toEntity(updated))
        return updated
    }

    private suspend fun fetchNews(settings: AppSettings, previousHeadlineKeys: Set<String> = emptySet()): NewsInfo {
        // Only the feeds the user picked in Settings, so the digest actually
        // reflects what they care about instead of one fixed source list.
        val selectedFeeds = NewsFeedCatalog.byIds(settings.selectedNewsFeedIds)
        // Every outlet the user has added themselves is fetched too - there's
        // no cap here, so adding 20 custom outlets pulls headlines from all 20.
        val feedsToFetch = selectedFeeds.map { it.label to it.url } +
            settings.customFeeds.filter { it.url.isNotBlank() }.map { it.label to it.url }
        return fetchNewsFromFeeds(feedsToFetch, NEWS_TARGET_COUNT, previousHeadlineKeys)
    }

    /** Optional dedicated US Politics card - only fetched when the user has turned it on in Settings. */
    private suspend fun fetchPoliticsNews(settings: AppSettings, previousHeadlineKeys: Set<String> = emptySet()): NewsInfo {
        if (!settings.politicsNewsEnabled) return NewsInfo(available = false)
        val feeds = PoliticsFeedCatalog.byIds(settings.selectedPoliticsFeedIds).map { it.label to it.url }
        return fetchNewsFromFeeds(feeds, TOPIC_NEWS_TARGET_COUNT, previousHeadlineKeys)
    }

    /** Optional dedicated Business News card - only fetched when the user has turned it on in Settings. */
    private suspend fun fetchBusinessNews(settings: AppSettings, previousHeadlineKeys: Set<String> = emptySet()): NewsInfo {
        if (!settings.businessNewsEnabled) return NewsInfo(available = false)
        val feeds = BusinessFeedCatalog.byIds(settings.selectedBusinessFeedIds).map { it.label to it.url }
        return fetchNewsFromFeeds(feeds, TOPIC_NEWS_TARGET_COUNT, previousHeadlineKeys)
    }

    // Hardcoded, always-on sources feeding just the analyst report cards
    // below (Bully/Beary/Satoshi) - not user-configurable like the main
    // News Sources settings, since these are small, single-purpose feeds
    // that specific cards need rather than a full topic section. Reuses the
    // exact same fetch/parse pipeline as every other news source in the app.
    private suspend fun fetchMarketsNews(): NewsInfo =
        fetchNewsFromFeeds(
            listOf("MarketWatch" to "http://feeds.marketwatch.com/marketwatch/topstories/"),
            TOPIC_NEWS_TARGET_COUNT,
            emptySet()
        )

    private suspend fun fetchCryptoNews(): NewsInfo =
        fetchNewsFromFeeds(
            listOf("CoinDesk" to "https://www.coindesk.com/arc/outboundfeeds/rss/"),
            TOPIC_NEWS_TARGET_COUNT,
            emptySet()
        )

    /**
     * Shared fetch/dedupe/sort pipeline used by the main World News feed and
     * by the optional single-topic Politics/Business cards - only the source
     * list and the resulting headline cap differ between them.
     */
    private suspend fun fetchNewsFromFeeds(
        feedsToFetch: List<Pair<String, String>>,
        targetCount: Int,
        previousHeadlineKeys: Set<String>
    ): NewsInfo = coroutineScope {
        if (feedsToFetch.isEmpty()) return@coroutineScope NewsInfo(available = false)

        // Fetch every outlet in parallel; a failing outlet just contributes an
        // empty list (via runCatching) instead of breaking the whole digest.
        val perOutlet = feedsToFetch.map { (source, url) ->
            async {
                runCatching { rssFetcher.fetchTopHeadlines(url, source = source, limit = NEWS_PER_OUTLET_LIMIT) }
                    .getOrElse { emptyList() }
            }
        }.awaitAll()

        // Dedupe near-identical headlines (wire stories often get reused
        // verbatim across outlets).
        val seenTitles = mutableSetOf<String>()
        val deduped = mutableListOf<NewsHeadline>()
        for (outlet in perOutlet) {
            for (headline in outlet) {
                val key = headline.title.trim().lowercase()
                if (seenTitles.add(key)) deduped.add(headline)
            }
        }

        // Sort so headlines that weren't in the previous refresh always land
        // above ones that were - this is what makes "new on top, old pushed
        // down" hold on every refresh even when a feed's pubDate is missing,
        // stale, or otherwise unreliable, rather than depending solely on it.
        // Within each of those two groups, still sort newest-first by pubDate.
        val combined = deduped
            .sortedWith(
                compareByDescending<NewsHeadline> { it.title.trim().lowercase() !in previousHeadlineKeys }
                    .thenByDescending { it.pubDateMillis ?: Long.MIN_VALUE }
            )
            .take(targetCount)

        NewsInfo(headlines = combined, available = combined.isNotEmpty())
    }

    suspend fun saveReport(report: DigestReport): DigestReport {
        val id = reportDao.insert(ReportMapper.toEntity(report))
        reportDao.trimTo(HISTORY_LIMIT)
        return report.copy(id = id)
    }

    /**
     * Re-fetches just the World News section and merges it into the latest
     * saved report, leaving weather/bitcoin/currency/politics/business
     * untouched - used by the per-card refresh button so refreshing one
     * section doesn't re-fetch everything else too.
     */
    suspend fun refreshWorldNewsSection(settings: AppSettings): DigestReport {
        val current = getLatestReport() ?: saveReport(buildFreshReport(settings))
        val keys = current.news.headlines.map { it.title.trim().lowercase() }.toSet()
        val updated = current.copy(news = fetchNews(settings, keys))
        reportDao.insert(ReportMapper.toEntity(updated))
        return updated
    }

    /** Same as [refreshWorldNewsSection] but for Bitcoin + Currency + any extra pairs - lets the markets cards retry on their own after a transient failure (e.g. a rate-limited crypto API call) without waiting for the next full digest. */
    suspend fun refreshMarketsSection(settings: AppSettings): DigestReport {
        val current = getLatestReport() ?: saveReport(buildFreshReport(settings))
        val bitcoin = fetchBitcoin()
        val currency = fetchCurrency(settings.currencyBase, settings.currencyTarget)
        val watchlist = if (settings.extraCurrencyPairs.isNotEmpty()) fetchExtraCurrencyPairs(settings.extraCurrencyPairs) else emptyList()
        val marketsNews = runCatching { fetchMarketsNews() }.getOrElse { NewsInfo(available = false) }
        val cryptoNews = runCatching { fetchCryptoNews() }.getOrElse { NewsInfo(available = false) }
        val updated = current.copy(
            bitcoin = bitcoin,
            currency = currency,
            watchlist = watchlist,
            marketsNews = marketsNews,
            cryptoNews = cryptoNews
        )
        reportDao.insert(ReportMapper.toEntity(updated))
        return updated
    }

    /** Same as [refreshMarketsSection] but for Panda's weather briefing - today/tomorrow/upcoming outlook plus alerts. */
    suspend fun refreshWeatherSection(settings: AppSettings): DigestReport {
        val current = getLatestReport() ?: saveReport(buildFreshReport(settings))
        val cityQuery = "${settings.city},${settings.country}"
        val today = fetchWeatherToday(cityQuery, settings.weatherApiKey)
        val (tomorrow, upcoming) = fetchTomorrowAndUpcoming(cityQuery, settings.weatherApiKey)
        val alerts = if (settings.weatherAlertsEnabled || settings.customAlertRules.enabled) {
            fetchWeatherAlerts(cityQuery, settings.weatherApiKey, settings.customAlertRules)
        } else {
            WeatherAlertsInfo(available = false)
        }
        val updated = current.copy(
            weatherToday = today,
            weatherTomorrow = tomorrow,
            upcomingDays = upcoming,
            weatherAlerts = alerts
        )
        reportDao.insert(ReportMapper.toEntity(updated))
        return updated
    }

    /** Same as [refreshWorldNewsSection] but for the optional Politics card. */
    suspend fun refreshPoliticsSection(settings: AppSettings): DigestReport {
        val current = getLatestReport() ?: saveReport(buildFreshReport(settings))
        val keys = current.politicsNews.headlines.map { it.title.trim().lowercase() }.toSet()
        val updated = current.copy(politicsNews = fetchPoliticsNews(settings, keys))
        reportDao.insert(ReportMapper.toEntity(updated))
        return updated
    }

    /** Same as [refreshWorldNewsSection] but for the optional Business card. */
    suspend fun refreshBusinessSection(settings: AppSettings): DigestReport {
        val current = getLatestReport() ?: saveReport(buildFreshReport(settings))
        val keys = current.businessNews.headlines.map { it.title.trim().lowercase() }.toSet()
        val updated = current.copy(businessNews = fetchBusinessNews(settings, keys))
        reportDao.insert(ReportMapper.toEntity(updated))
        return updated
    }

    suspend fun updateSendResult(reportId: Long, report: DigestReport) {
        reportDao.insert(ReportMapper.toEntity(report.copy(id = reportId)))
    }

    suspend fun getLatestReport(): DigestReport? =
        reportDao.getLatest()?.let { ReportMapper.toDomain(it) }

    fun observeHistory(): Flow<List<DigestReport>> =
        reportDao.observeAll().map { list -> list.map { ReportMapper.toDomain(it) } }

    suspend fun getReportById(id: Long): DigestReport? =
        reportDao.getById(id)?.let { ReportMapper.toDomain(it) }

    suspend fun deleteReport(id: Long) = reportDao.deleteById(id)

    suspend fun clearHistory() = reportDao.deleteAll()

    companion object {
        fun create(database: AppDatabase, weatherApi: OpenWeatherApi, coinGeckoApi: CoinGeckoApi, frankfurterApi: FrankfurterApi, rssFetcher: RssFeedFetcher) =
            DigestRepository(weatherApi, coinGeckoApi, frankfurterApi, rssFetcher, database.reportDao())
    }
}
