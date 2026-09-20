package com.example.gpsarrivalalarm

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONArray

/**
 * ユーザー操作による住所・駅名・施設名検索。
 *
 * 端末依存の Geocoder は候補を1件しか返さないことがあるため、
 * 検索ボタン押下時は Nominatim から複数候補を取得し、失敗時だけ Geocoder に戻す。
 */
data class PlaceSearchResult(
    val title: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double
)

class PlaceSearcher(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = mutableMapOf<String, List<PlaceSearchResult>>()
    private var lastRemoteSearchAt = 0L

    fun search(
        query: String,
        onResult: (Result<List<PlaceSearchResult>>) -> Unit
    ) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            deliver { onResult(Result.success(emptyList())) }
            return
        }

        synchronized(cache) {
            cache[normalized]?.let { cached ->
                deliver { onResult(Result.success(cached)) }
                return
            }
        }

        Thread {
            try {
                val results = runCatching { searchWithNominatim(normalized) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?: searchWithGeocoder(normalized)
                synchronized(cache) { cache[normalized] = results }
                deliver { onResult(Result.success(results)) }
            } catch (t: Throwable) {
                deliver { onResult(Result.failure(searchFailure(t))) }
            }
        }.start()
    }

    private fun searchWithNominatim(query: String): List<PlaceSearchResult> {
        waitForRemoteSearchSlot()
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        val url = URL(
            "$NOMINATIM_URL?format=jsonv2&addressdetails=1" +
                "&limit=$MAX_RESULTS&accept-language=ja&countrycodes=jp&q=$encodedQuery"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = REMOTE_TIMEOUT_MILLIS
            readTimeout = REMOTE_TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }

        return try {
            if (connection.responseCode !in 200..299) {
                throw IOException("検索サービスの応答エラー: ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONArray(body).toSearchResults()
        } finally {
            connection.disconnect()
        }
    }

    private fun searchWithGeocoder(query: String): List<PlaceSearchResult> {
        if (!Geocoder.isPresent()) {
            throw IllegalStateException(
                "この端末では住所検索サービスを利用できません。地図をタップして目的地を選択してください。"
            )
        }
        val geocoder = Geocoder(appContext, Locale.JAPAN)
        @Suppress("DEPRECATION")
        val addresses = geocoder.getFromLocationName(query, MAX_RESULTS).orEmpty()
        return addresses.toSearchResults()
    }

    private fun List<Address>.toSearchResults(): List<PlaceSearchResult> =
        mapNotNull { address ->
            if (!address.hasLatitude() || !address.hasLongitude()) return@mapNotNull null

            val lines = (0..address.maxAddressLineIndex)
                .mapNotNull { index -> address.getAddressLine(index)?.trim() }
                .filter { it.isNotBlank() }

            val subtitle = lines.joinToString(" ").ifBlank {
                listOfNotNull(
                    address.countryName,
                    address.adminArea,
                    address.locality,
                    address.subLocality,
                    address.thoroughfare,
                    address.subThoroughfare
                ).filter { it.isNotBlank() }.distinct().joinToString(" ")
            }

            val title = listOfNotNull(
                address.featureName,
                address.premises,
                address.thoroughfare,
                address.locality
            ).firstOrNull { it.isNotBlank() }
                ?: subtitle.substringBefore('、').substringBefore(',').ifBlank { "検索結果" }

            PlaceSearchResult(
                title = title,
                subtitle = subtitle,
                latitude = address.latitude,
                longitude = address.longitude
            )
        }.distinctBy {
            "%.6f,%.6f".format(Locale.US, it.latitude, it.longitude)
        }

    private fun JSONArray.toSearchResults(): List<PlaceSearchResult> =
        (0 until length()).mapNotNull { index ->
            val item = optJSONObject(index) ?: return@mapNotNull null
            val latitude = item.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val longitude = item.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            val displayName = item.optString("display_name").trim()
            val baseTitle = item.optString("name").trim()
                .ifBlank { displayName.substringBefore(',').trim() }
                .ifBlank { "検索結果" }
            val placeType = item.optString("type").lowercase(Locale.ROOT)
            val placeClass = item.optString("class").lowercase(Locale.ROOT)
            val isStation = placeType in STATION_TYPES ||
                (placeClass == "public_transport" && placeType in PUBLIC_TRANSPORT_TYPES)
            val title = if (isStation && !baseTitle.contains("駅")) {
                "${baseTitle}駅"
            } else {
                baseTitle
            }

            PlaceSearchResult(
                title = title,
                subtitle = displayName,
                latitude = latitude,
                longitude = longitude
            )
        }.distinctBy {
            "%.6f,%.6f".format(Locale.US, it.latitude, it.longitude)
        }

    private fun waitForRemoteSearchSlot() {
        synchronized(this) {
            val waitMillis = MIN_REMOTE_INTERVAL_MILLIS -
                (System.currentTimeMillis() - lastRemoteSearchAt)
            if (waitMillis > 0) Thread.sleep(waitMillis)
            lastRemoteSearchAt = System.currentTimeMillis()
        }
    }

    private fun searchFailure(t: Throwable): IllegalStateException {
        val message = t.message.orEmpty()
        return IllegalStateException(
            when {
                message.contains("service", ignoreCase = true) ->
                    "住所検索サービスに接続できませんでした。通信状態を確認して再検索してください。"
                else ->
                    "場所を検索できませんでした。地図をタップして目的地を選択することもできます。"
            },
            t
        )
    }

    private fun deliver(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    companion object {
        private const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
        private const val USER_AGENT = "GpsArrivalAlarm/2.1 (Android map search)"
        private const val MAX_RESULTS = 10
        private const val REMOTE_TIMEOUT_MILLIS = 10_000
        private const val MIN_REMOTE_INTERVAL_MILLIS = 1_000L
        private val STATION_TYPES = setOf(
            "station",
            "train_station",
            "halt",
            "subway_entrance",
            "tram_stop"
        )
        private val PUBLIC_TRANSPORT_TYPES = setOf("platform", "stop", "station")
    }
}
