package com.example.gpsarrivalalarm

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * Android 標準 Geocoder を使った住所・駅名・施設名検索。
 *
 * 以前の版は公開 Nominatim サーバーへ直接 HTTP 通信していたため、
 * サーバー側の利用制限によって HTTP 403 になる場合がありました。
 * この版では検索時に Nominatim へ直接アクセスしないため、403 を回避します。
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

    fun search(
        query: String,
        onResult: (Result<List<PlaceSearchResult>>) -> Unit
    ) {
        val normalized = query.trim()
        if (normalized.isEmpty()) {
            deliver { onResult(Result.success(emptyList())) }
            return
        }

        if (!Geocoder.isPresent()) {
            deliver {
                onResult(
                    Result.failure(
                        IllegalStateException(
                            "この端末では住所検索サービスを利用できません。地図をタップして目的地を選択してください。"
                        )
                    )
                )
            }
            return
        }

        val geocoder = Geocoder(appContext, Locale.JAPAN)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                geocoder.getFromLocationName(
                    normalized,
                    MAX_RESULTS,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            deliver {
                                onResult(Result.success(addresses.toSearchResults()))
                            }
                        }

                        override fun onError(errorMessage: String?) {
                            deliver {
                                onResult(
                                    Result.failure(
                                        IllegalStateException(
                                            errorMessage?.takeIf { it.isNotBlank() }
                                                ?: "場所を検索できませんでした。地図をタップして選択することもできます。"
                                        )
                                    )
                                )
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                deliver { onResult(Result.failure(searchFailure(t))) }
            }
        } else {
            Thread {
                try {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(normalized, MAX_RESULTS).orEmpty()
                    deliver { onResult(Result.success(addresses.toSearchResults())) }
                } catch (t: Throwable) {
                    deliver { onResult(Result.failure(searchFailure(t))) }
                }
            }.start()
        }
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
        private const val MAX_RESULTS = 5
    }
}
