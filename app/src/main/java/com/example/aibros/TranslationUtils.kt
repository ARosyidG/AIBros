package com.example.aibros

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest

object TranslationUtils {

    private const val CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /**
     * Computes a SHA-1-based filename for the translation cache.
     */
    fun getCacheKey(url: String, fromLang: String, toLang: String): String {
        val input = "$url|$fromLang|$toLang"
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".cache"
    }

    /**
     * Ensures a URL has a scheme prefix. Adds "https://" if none is present.
     */
    fun normalizeUrl(url: String): String {
        return if (url.startsWith("http")) url else "https://$url"
    }

    /**
     * Returns true if the cache entry is younger than [maxAgeMs] milliseconds.
     */
    fun isCacheFresh(cache: TranslationCache, maxAgeMs: Long = CACHE_MAX_AGE_MS): Boolean {
        return (System.currentTimeMillis() - cache.timestamp) < maxAgeMs
    }

    /**
     * Splits a list of strings into sub-lists of at most [chunkSize] items.
     */
    fun chunkTexts(texts: List<String>, chunkSize: Int = 120): List<List<String>> {
        return texts.chunked(chunkSize)
    }

    /**
     * Parses the raw JSON string returned by the Gemini API into a list of translated strings.
     * Returns an empty list if parsing fails for any reason.
     */
    fun parseGeminiResponse(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return try {
            val json = JSONObject(raw)
            val text = json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            try {
                JSONArray(text).let { array ->
                    (0 until array.length()).map { array.getString(it) }
                }
            } catch (e: JSONException) {
                // Fallback: the text itself may be a JSON-escaped array string
                val quoted = JSONObject.quote(text)
                val cleaned = quoted.substring(1, quoted.length - 1)
                val array = JSONArray(cleaned)
                (0 until array.length()).map { array.getString(it) }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
