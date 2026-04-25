package com.example.barcodescanner

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** What a successful product lookup returns. Either field may be null individually. */
data class ProductInfo(val name: String?, val size: String?) {
    val hasAnything: Boolean get() = !name.isNullOrBlank() || !size.isNullOrBlank()
}

/**
 * Best-effort barcode -> product info lookup.
 *
 * Tries Open Food Facts first (free, keyless, excellent grocery coverage), then
 * falls back to UPCitemdb's free trial endpoint for everything else (100 req/day,
 * no key required). Returns null on any failure — callers should treat lookup
 * failure as non-fatal and keep the barcode saved regardless.
 */
object ProductLookup {

    private const val TAG = "ProductLookup"
    private const val TIMEOUT_MS = 8_000
    private const val USER_AGENT = "BarcodeScanner/1.0 (Android)"

    /** Barcode formats that are worth looking up (numeric retail codes). */
    private val PRODUCT_FORMATS = setOf("EAN_13", "EAN_8", "UPC_A", "UPC_E", "ITF")

    fun supportsLookup(format: String): Boolean = format in PRODUCT_FORMATS

    suspend fun lookup(code: String, format: String): ProductInfo? = withContext(Dispatchers.IO) {
        if (!supportsLookup(format)) return@withContext null
        val info = tryOpenFoodFacts(code) ?: tryUpcItemDb(code)
        info?.takeIf { it.hasAnything }
    }

    // --- Open Food Facts --------------------------------------------------
    // https://wiki.openfoodfacts.org/API/Read/Product
    // Relevant fields: product_name, product_name_en, generic_name, brands, quantity
    private fun tryOpenFoodFacts(code: String): ProductInfo? {
        val url = "https://world.openfoodfacts.org/api/v2/product/$code.json"
        val body = httpGet(url) ?: return null
        return runCatching {
            val root = JSONObject(body)
            if (root.optInt("status") != 1) return@runCatching null
            val product = root.optJSONObject("product") ?: return@runCatching null

            val rawName = product.optString("product_name").takeIf { it.isNotBlank() }
                ?: product.optString("product_name_en").takeIf { it.isNotBlank() }
                ?: product.optString("generic_name").takeIf { it.isNotBlank() }

            val brand = product.optString("brands")
                .split(",").firstOrNull()?.trim().orEmpty()

            val name = when {
                rawName == null -> null
                brand.isNotEmpty() && !rawName.contains(brand, ignoreCase = true) -> "$brand $rawName"
                else -> rawName
            }

            val size = product.optString("quantity").takeIf { it.isNotBlank() }

            ProductInfo(name, size).takeIf { it.hasAnything }
        }.getOrNull()
    }

    // --- UPCitemdb trial --------------------------------------------------
    // https://www.upcitemdb.com/api/explorer — free trial, 100 requests/day
    // Relevant fields: items[0].title, items[0].size
    private fun tryUpcItemDb(code: String): ProductInfo? {
        val url = "https://api.upcitemdb.com/prod/trial/lookup?upc=$code"
        val body = httpGet(url) ?: return null
        return runCatching {
            val root = JSONObject(body)
            if (root.optString("code") != "OK") return@runCatching null
            val items = root.optJSONArray("items") ?: return@runCatching null
            if (items.length() == 0) return@runCatching null
            val item = items.getJSONObject(0)

            val name = item.optString("title").takeIf { it.isNotBlank() }
            val size = item.optString("size").takeIf { it.isNotBlank() }

            ProductInfo(name, size).takeIf { it.hasAnything }
        }.getOrNull()
    }

    // --- HTTP helper ------------------------------------------------------
    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                Log.d(TAG, "HTTP $code from $url")
                null
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Lookup failed for $url: ${t.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
