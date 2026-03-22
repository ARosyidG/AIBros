package com.example.aibros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class TranslationUtilsTest {

    // ------------------- getCacheKey -------------------

    @Test
    fun getCacheKey_endsWithDotCache() {
        val key = TranslationUtils.getCacheKey("https://example.com", "Japanese", "English")
        assertTrue("Cache key should end with .cache", key.endsWith(".cache"))
    }

    @Test
    fun getCacheKey_isSha1PlusDotCache() {
        // SHA-1 produces 40 hex chars; with ".cache" the total is 46
        val key = TranslationUtils.getCacheKey("https://example.com", "Japanese", "English")
        assertEquals("Cache key should be 46 characters (40 hex + 6 for .cache)", 46, key.length)
    }

    @Test
    fun getCacheKey_isDeterministic() {
        val key1 = TranslationUtils.getCacheKey("https://example.com", "Japanese", "English")
        val key2 = TranslationUtils.getCacheKey("https://example.com", "Japanese", "English")
        assertEquals("Same inputs must produce the same cache key", key1, key2)
    }

    @Test
    fun getCacheKey_differentUrls_produceDifferentKeys() {
        val key1 = TranslationUtils.getCacheKey("https://example.com/page1", "Japanese", "English")
        val key2 = TranslationUtils.getCacheKey("https://example.com/page2", "Japanese", "English")
        assertNotEquals("Different URLs should produce different cache keys", key1, key2)
    }

    @Test
    fun getCacheKey_differentFromLang_produceDifferentKeys() {
        val key1 = TranslationUtils.getCacheKey("https://example.com", "Japanese", "English")
        val key2 = TranslationUtils.getCacheKey("https://example.com", "Chinese", "English")
        assertNotEquals("Different source languages should produce different cache keys", key1, key2)
    }

    @Test
    fun getCacheKey_differentToLang_produceDifferentKeys() {
        val key1 = TranslationUtils.getCacheKey("https://example.com", "Japanese", "English")
        val key2 = TranslationUtils.getCacheKey("https://example.com", "Japanese", "Indonesia")
        assertNotEquals("Different target languages should produce different cache keys", key1, key2)
    }

    @Test
    fun getCacheKey_emptyInputs_doesNotThrow() {
        // Should not throw; result should still be a valid 46-char string
        val key = TranslationUtils.getCacheKey("", "", "")
        assertEquals(46, key.length)
    }

    @Test
    fun getCacheKey_containsOnlyHexCharsAndExtension() {
        val key = TranslationUtils.getCacheKey("https://example.com", "Korean", "Indonesia")
        val hexPart = key.removeSuffix(".cache")
        assertTrue(
            "Hash part should contain only lowercase hex characters",
            hexPart.matches(Regex("[0-9a-f]+"))
        )
    }

    // ------------------- normalizeUrl -------------------

    @Test
    fun normalizeUrl_addsHttpsWhenNoScheme() {
        val result = TranslationUtils.normalizeUrl("example.com")
        assertEquals("https://example.com", result)
    }

    @Test
    fun normalizeUrl_preservesHttpsScheme() {
        val url = "https://example.com/path"
        assertEquals(url, TranslationUtils.normalizeUrl(url))
    }

    @Test
    fun normalizeUrl_preservesHttpScheme() {
        val url = "http://example.com/path"
        assertEquals(url, TranslationUtils.normalizeUrl(url))
    }

    @Test
    fun normalizeUrl_preservesWwwWithoutScheme() {
        val result = TranslationUtils.normalizeUrl("www.example.com")
        assertEquals("https://www.example.com", result)
    }

    @Test
    fun normalizeUrl_emptyString_returnsHttpsPrefix() {
        val result = TranslationUtils.normalizeUrl("")
        assertEquals("https://", result)
    }

    @Test
    fun normalizeUrl_preservesQueryAndFragment() {
        val url = "https://example.com/search?q=hello#top"
        assertEquals(url, TranslationUtils.normalizeUrl(url))
    }

    // ------------------- isCacheFresh -------------------

    @Test
    fun isCacheFresh_returnsTrueForJustCreatedCache() {
        val cache = TranslationCache(listOf("hello"), System.currentTimeMillis())
        assertTrue("Cache created right now should be fresh", TranslationUtils.isCacheFresh(cache))
    }

    @Test
    fun isCacheFresh_returnsFalseForOldCache() {
        val twentyFiveHoursAgo = System.currentTimeMillis() - 25 * 60 * 60 * 1000L
        val cache = TranslationCache(listOf("hello"), twentyFiveHoursAgo)
        assertFalse("Cache older than 24 h should not be fresh", TranslationUtils.isCacheFresh(cache))
    }

    @Test
    fun isCacheFresh_returnsFalseForExactlyExpiredCache() {
        val exactlyOneDayAgo = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        val cache = TranslationCache(listOf("hello"), exactlyOneDayAgo)
        // age == maxAgeMs, so condition is NOT strictly less-than → should be stale
        assertFalse("Cache at exactly 24 h old should not be fresh", TranslationUtils.isCacheFresh(cache))
    }

    @Test
    fun isCacheFresh_honorCustomMaxAge() {
        val fiveMinutesAgo = System.currentTimeMillis() - 5 * 60 * 1000L
        val cache = TranslationCache(listOf("hello"), fiveMinutesAgo)
        // Custom max age of 10 minutes → should be fresh
        assertTrue(TranslationUtils.isCacheFresh(cache, maxAgeMs = 10 * 60 * 1000L))
        // Custom max age of 1 minute → should be stale
        assertFalse(TranslationUtils.isCacheFresh(cache, maxAgeMs = 60 * 1000L))
    }

    // ------------------- chunkTexts -------------------

    @Test
    fun chunkTexts_emptyList_returnsEmptyList() {
        val result = TranslationUtils.chunkTexts(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun chunkTexts_smallerThanChunkSize_returnsOneChunk() {
        val texts = listOf("a", "b", "c")
        val result = TranslationUtils.chunkTexts(texts, chunkSize = 10)
        assertEquals(1, result.size)
        assertEquals(texts, result[0])
    }

    @Test
    fun chunkTexts_exactlyChunkSize_returnsOneChunk() {
        val texts = (1..5).map { "text$it" }
        val result = TranslationUtils.chunkTexts(texts, chunkSize = 5)
        assertEquals(1, result.size)
        assertEquals(texts, result[0])
    }

    @Test
    fun chunkTexts_largerThanChunkSize_returnsMultipleChunks() {
        val texts = (1..7).map { "text$it" }
        val result = TranslationUtils.chunkTexts(texts, chunkSize = 3)
        assertEquals(3, result.size)
        assertEquals(3, result[0].size)
        assertEquals(3, result[1].size)
        assertEquals(1, result[2].size)
    }

    @Test
    fun chunkTexts_preservesAllItems() {
        val texts = (1..25).map { "item$it" }
        val result = TranslationUtils.chunkTexts(texts, chunkSize = 10)
        assertEquals(texts, result.flatten())
    }

    @Test
    fun chunkTexts_defaultChunkSizeIs120() {
        val texts = (1..250).map { "t$it" }
        val result = TranslationUtils.chunkTexts(texts)
        // 250 items with chunk 120 → 3 chunks: 120, 120, 10
        assertEquals(3, result.size)
        assertEquals(120, result[0].size)
        assertEquals(120, result[1].size)
        assertEquals(10, result[2].size)
    }

    // ------------------- parseGeminiResponse -------------------

    private fun buildGeminiResponse(textContent: String): String {
        return """
        {
          "candidates": [
            {
              "content": {
                "parts": [
                  { "text": ${JSONObject.quote(textContent)} }
                ]
              }
            }
          ]
        }
        """.trimIndent()
    }

    @Test
    fun parseGeminiResponse_validJsonArrayResponse_returnsTranslations() {
        val raw = buildGeminiResponse("""["Hello","World"]""")
        val result = TranslationUtils.parseGeminiResponse(raw)
        assertEquals(listOf("Hello", "World"), result)
    }

    @Test
    fun parseGeminiResponse_singleTranslation_returnsListWithOneItem() {
        val raw = buildGeminiResponse("""["こんにちは"]""")
        val result = TranslationUtils.parseGeminiResponse(raw)
        assertEquals(listOf("こんにちは"), result)
    }

    @Test
    fun parseGeminiResponse_emptyJsonArray_returnsEmptyList() {
        val raw = buildGeminiResponse("[]")
        val result = TranslationUtils.parseGeminiResponse(raw)
        assertTrue("Empty JSON array should return empty list", result.isEmpty())
    }

    @Test
    fun parseGeminiResponse_emptyString_returnsEmptyList() {
        val result = TranslationUtils.parseGeminiResponse("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseGeminiResponse_blankString_returnsEmptyList() {
        val result = TranslationUtils.parseGeminiResponse("   ")
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseGeminiResponse_malformedJson_returnsEmptyList() {
        val result = TranslationUtils.parseGeminiResponse("this is not json at all")
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseGeminiResponse_missingCandidatesField_returnsEmptyList() {
        val raw = """{"result": "ok"}"""
        val result = TranslationUtils.parseGeminiResponse(raw)
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseGeminiResponse_emptyCandidatesArray_returnsEmptyList() {
        val raw = """{"candidates": []}"""
        val result = TranslationUtils.parseGeminiResponse(raw)
        assertTrue(result.isEmpty())
    }

    @Test
    fun parseGeminiResponse_multipleTranslations_preservesOrder() {
        val translations = listOf("One", "Two", "Three", "Four", "Five")
        val jsonArray = JSONArray(translations).toString()
        val raw = buildGeminiResponse(jsonArray)
        val result = TranslationUtils.parseGeminiResponse(raw)
        assertEquals(translations, result)
    }

    @Test
    fun parseGeminiResponse_translationsWithSpecialCharacters() {
        val raw = buildGeminiResponse("""["It's \"quoted\"","Line\nBreak","Tab\there"]""")
        val result = TranslationUtils.parseGeminiResponse(raw)
        assertEquals(3, result.size)
    }

    @Test
    fun parseGeminiResponse_unicodeTranslations() {
        val raw = buildGeminiResponse("""["日本語","中文","한국어"]""")
        val result = TranslationUtils.parseGeminiResponse(raw)
        assertEquals(listOf("日本語", "中文", "한국어"), result)
    }
}
