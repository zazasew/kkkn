package com.morningdigest.app.data.facts

import com.morningdigest.app.data.model.DigestReport
import com.morningdigest.app.data.prefs.MascotCharacter
import kotlin.math.abs
import kotlin.math.roundToInt

/** One analyst's generated briefing. [generatedAtMillis] reflects when the underlying data was last fetched. */
data class AssistantReport(val body: String, val generatedAtMillis: Long)

/**
 * Builds each analyst's independent daily briefing purely from data the app
 * already has (weather, live RSS news, Bitcoin/currency/watchlist prices) -
 * no chat, no user interaction, no invented numbers. Topics this app has no
 * real data source for (economic calendar, GDP, Fear & Greed Index, stock
 * sectors, dividend data) are simply left out rather than fabricated.
 *
 * Every report is a pure function of the current [DigestReport], so calling
 * this again after any partial refresh (weather-only, markets-only, etc.)
 * naturally only changes the text for the analyst whose underlying data
 * actually changed - there's no separate "regenerate" event system needed.
 */
object AssistantReportBuilder {

    fun build(character: MascotCharacter, report: DigestReport?): AssistantReport {
        val body = when (character) {
            MascotCharacter.PANDA -> buildPanda(report)
            MascotCharacter.OWL -> buildOwl(report)
            MascotCharacter.BULL -> buildBull(report)
            MascotCharacter.BEAR -> buildBear(report)
            MascotCharacter.FOX -> buildFox(report)
            MascotCharacter.CAT -> buildCat(report)
        }
        return AssistantReport(body, report?.timestampMillis ?: System.currentTimeMillis())
    }

    private fun buildPanda(report: DigestReport?): String {
        if (report == null) return "Weather data isn't available right now - pull to refresh and I'll take a look."
        val w = report.weatherToday
        if (!w.available) return "Weather data isn't available right now - pull to refresh and I'll take a look."
        val parts = mutableListOf<String>()
        val desc = w.description?.replaceFirstChar { it.uppercase() } ?: "Changeable conditions"
        val temp = w.temp?.roundToInt()
        parts += if (temp != null) "$desc today, ${temp}°C." else "$desc today."

        val t = report.weatherTomorrow
        if (t.available) {
            val tDesc = t.description?.lowercase() ?: "changeable weather"
            val tTemp = t.avgTemp?.roundToInt()
            parts += "Tomorrow looks like $tDesc" + (tTemp?.let { ", around $it°C." } ?: ".")
        }

        WeatherOutfitAdvisor.suggestionFor(w)?.let { parts += it }

        val alerts = report.weatherAlerts
        if (alerts.available && alerts.alerts.isNotEmpty()) {
            parts += "⚠️ ${alerts.alerts.first().event} is active in your area."
        }
        return parts.joinToString(" ")
    }

    private fun buildOwl(report: DigestReport?): String {
        val headlines = report?.news?.headlines.orEmpty()
        if (headlines.isEmpty()) return "No world news available right now - make sure World News is on in Settings, or pull to refresh."
        val lead = headlines.first().title
        val second = headlines.getOrNull(1)?.title
        return if (second != null) "Top story right now: $lead. Also developing: $second" else "Top story right now: $lead"
    }

    private fun buildBull(report: DigestReport?): String {
        if (report == null) return "Market data isn't available yet - pull to refresh."
        val movers = collectMovers(report)
        val best = movers.maxByOrNull { it.second }
        val parts = mutableListOf<String>()
        parts += when {
            best != null && best.second > 0 ->
                "${best.first} is up ${"%.2f".format(best.second)}% today - momentum looks constructive."
            best != null ->
                "Things are pulling back a little across the board today, but the bigger trend still looks fine - good setups take patience."
            else -> "No tracked assets to report on yet - add a currency or crypto pair in Settings to get a real read on this."
        }
        report.marketsNews.headlines.firstOrNull()?.let { parts += "In the news: ${it.title}" }
        return parts.joinToString(" ")
    }

    private fun buildBear(report: DigestReport?): String {
        if (report == null) return "Market data isn't available yet - pull to refresh."
        val movers = collectMovers(report)
        val worst = movers.minByOrNull { it.second }
        val mostVolatile = movers.maxByOrNull { abs(it.second) }
        val parts = mutableListOf<String>()
        parts += if (worst != null && worst.second < 0) {
            "${worst.first} is down ${"%.2f".format(abs(worst.second))}% today - worth keeping an eye on."
        } else {
            "Nothing alarming in your tracked assets today, but conditions can shift fast - keep your risk limits in mind regardless."
        }
        if (mostVolatile != null && abs(mostVolatile.second) >= 3.0 && mostVolatile.first != worst?.first) {
            parts += "${mostVolatile.first} is seeing bigger swings than usual - that's worth watching closely."
        }
        report.marketsNews.headlines.firstOrNull()?.let { parts += "Also in the news: ${it.title}" }
        return parts.joinToString(" ")
    }

    private fun buildFox(report: DigestReport?): String {
        if (report == null) return "Crypto data isn't available yet - pull to refresh."
        val parts = mutableListOf<String>()
        val b = report.bitcoin
        if (b.available) {
            val change = b.change24hPercent ?: 0.0
            parts += "Bitcoin is ${if (change >= 0) "up" else "down"} ${"%.2f".format(abs(change))}% today, around €${"%,.0f".format(b.eur ?: 0.0)}."
        }
        report.watchlist.filter { it.isCrypto && it.available && it.change24hPercent != null }.take(2).forEach { entry ->
            val change = entry.change24hPercent!!
            parts += "${entry.label} is ${if (change >= 0) "up" else "down"} ${"%.2f".format(abs(change))}%."
        }
        report.cryptoNews.headlines.firstOrNull()?.let { parts += "Crypto headline: ${it.title}" }
        if (parts.isEmpty()) return "No crypto data to report on yet - check your Bitcoin and watchlist settings."
        return parts.joinToString(" ")
    }

    private fun buildCat(report: DigestReport?): String {
        val headlines = report?.businessNews?.headlines.orEmpty()
        if (headlines.isEmpty()) return "No business news available right now - make sure Business News is on in Settings, or pull to refresh."
        val lead = headlines.first().title
        val second = headlines.getOrNull(1)?.title
        return if (second != null) "Worth watching: $lead. Also: $second" else "Worth watching: $lead"
    }

    /** Every tracked price-moving asset (Bitcoin, main currency pair, watchlist entries) as (label, change%) pairs. */
    private fun collectMovers(report: DigestReport): List<Pair<String, Double>> {
        val list = mutableListOf<Pair<String, Double>>()
        if (report.bitcoin.available) report.bitcoin.change24hPercent?.let { list += "Bitcoin" to it }
        if (report.currency.available) {
            report.currency.change24hPercent?.let {
                list += "${report.currency.baseCurrency}/${report.currency.targetCurrency}" to it
            }
        }
        report.watchlist.forEach { entry ->
            if (entry.available) entry.change24hPercent?.let { list += entry.label to it }
        }
        return list
    }

    /**
     * A fuller, multi-line version of each report for the dedicated
     * per-character detail screen - more headlines/movers than the one-line
     * dashboard summary, not just a single sentence. Each returned string is
     * one row in that screen's list.
     */
    fun buildDetailLines(character: MascotCharacter, report: DigestReport?): List<String> {
        if (report == null) return listOf("No data available yet - pull to refresh.")
        return when (character) {
            MascotCharacter.PANDA -> detailPanda(report)
            MascotCharacter.OWL -> detailNewsList(report.news.headlines, "world news")
            MascotCharacter.BULL -> detailMovers(report, ascending = false) + detailNewsList(report.marketsNews.headlines, "markets news", asHeader = false)
            MascotCharacter.BEAR -> detailMovers(report, ascending = true) + detailNewsList(report.marketsNews.headlines, "markets news", asHeader = false)
            MascotCharacter.FOX -> detailFox(report)
            MascotCharacter.CAT -> detailNewsList(report.businessNews.headlines, "business news")
        }
    }

    private fun detailPanda(report: DigestReport): List<String> {
        val lines = mutableListOf<String>()
        val w = report.weatherToday
        if (w.available) {
            val temp = w.temp?.roundToInt()
            val feels = w.feelsLike?.roundToInt()
            val desc = w.description?.replaceFirstChar { it.uppercase() } ?: "Changeable conditions"
            lines += "Today: $desc" + (temp?.let { ", ${it}°C" } ?: "") + (feels?.let { " (feels like ${it}°C)" } ?: "") + "."
            WeatherOutfitAdvisor.suggestionFor(w)?.let { lines += it }
        } else {
            lines += "Today's weather isn't available right now."
        }
        val t = report.weatherTomorrow
        if (t.available) {
            val tTemp = t.avgTemp?.roundToInt()
            val tDesc = t.description?.replaceFirstChar { it.uppercase() } ?: "Changeable weather"
            lines += "Tomorrow: $tDesc" + (tTemp?.let { ", around ${it}°C" } ?: "") + "."
        }
        report.upcomingDays.forEach { day ->
            val desc = day.description?.replaceFirstChar { it.uppercase() } ?: "Changeable"
            val range = if (day.minTemp != null && day.maxTemp != null) " (${day.minTemp.roundToInt()}°–${day.maxTemp.roundToInt()}°)" else ""
            lines += "${day.dayLabel}: $desc$range."
        }
        val alerts = report.weatherAlerts
        if (alerts.available && alerts.alerts.isNotEmpty()) {
            alerts.alerts.forEach { a ->
                lines += "⚠️ ${a.event}${if (a.senderName.isNotBlank()) " — ${a.senderName}" else ""}"
            }
        }
        alerts.customAlerts.forEach { match -> lines += "${match.label} — ${match.detail}" }
        return lines
    }

    private fun detailMovers(report: DigestReport, ascending: Boolean): List<String> {
        val movers = collectMovers(report)
        if (movers.isEmpty()) return listOf("No tracked assets yet - add a currency or crypto pair in Settings.")
        val sorted = if (ascending) movers.sortedBy { it.second } else movers.sortedByDescending { it.second }
        return sorted.map { (label, change) ->
            "${if (change >= 0) "▲" else "▼"} $label: ${"%.2f".format(abs(change))}%"
        }
    }

    private fun detailFox(report: DigestReport): List<String> {
        val lines = mutableListOf<String>()
        val b = report.bitcoin
        if (b.available) {
            val change = b.change24hPercent ?: 0.0
            lines += "${if (change >= 0) "▲" else "▼"} Bitcoin: ${"%.2f".format(abs(change))}% (€${"%,.0f".format(b.eur ?: 0.0)})"
        }
        report.watchlist.filter { it.isCrypto && it.available }.forEach { entry ->
            val change = entry.change24hPercent
            if (change != null) lines += "${if (change >= 0) "▲" else "▼"} ${entry.label}: ${"%.2f".format(abs(change))}%"
        }
        if (lines.isEmpty()) lines += "No crypto data yet - check your Bitcoin and watchlist settings."
        lines += detailNewsList(report.cryptoNews.headlines, "crypto news", asHeader = false)
        return lines
    }

    private fun detailNewsList(headlines: List<com.morningdigest.app.data.model.NewsHeadline>, label: String, asHeader: Boolean = true): List<String> {
        if (headlines.isEmpty()) {
            return if (asHeader) listOf("No $label available right now - pull to refresh.") else emptyList()
        }
        return headlines.take(15).map { h -> "• ${h.title}${if (h.source.isNotBlank()) " — ${h.source}" else ""}" }
    }
}
