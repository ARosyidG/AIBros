package com.example.aibros

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import android.webkit.CookieManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.imageview.ShapeableImageView
import android.widget.Spinner
import android.widget.ArrayAdapter
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import android.util.Log
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit
import androidx.appcompat.app.AlertDialog
import android.widget.ProgressBar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.security.MessageDigest
import org.json.JSONException

data class TranslationCache(val translatedList: List<String>, val timestamp: Long) : Serializable

class MainActivity : AppCompatActivity() {

    lateinit var webView: WebView
    lateinit var urlInput: EditText
    lateinit var btnGo: ShapeableImageView
    lateinit var btnTranslate: FloatingActionButton
    lateinit var spinnerFrom: Spinner
    lateinit var spinnerTo: Spinner
    private var loadingDialog: AlertDialog? = null
    lateinit var btnClearCache: ShapeableImageView

    val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun getCacheKey(url: String, fromLang: String, toLang: String): String {
        val input = "$url|$fromLang|$toLang"
        val digest = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) } + ".cache"
    }

    private fun getCacheFile(url: String, fromLang: String, toLang: String): File {
        val cacheDir = File(filesDir, "translations")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        return File(cacheDir, getCacheKey(url, fromLang, toLang))
    }

    private fun saveTranslationCache(url: String, fromLang: String, toLang: String, translatedList: List<String>) {
        try {
            val cache = TranslationCache(translatedList, System.currentTimeMillis())
            val file = getCacheFile(url, fromLang, toLang)
            ObjectOutputStream(FileOutputStream(file)).use { it.writeObject(cache) }
        } catch (e: Exception) {
            Log.e("CACHE", "Failed to save cache", e)
        }
    }

    private fun loadTranslationCache(url: String, fromLang: String, toLang: String): List<String>? {
        val file = getCacheFile(url, fromLang, toLang)
        if (!file.exists()) return null
        try {
            ObjectInputStream(FileInputStream(file)).use { input ->
                val cache = input.readObject() as TranslationCache
                val age = System.currentTimeMillis() - cache.timestamp
                if (age < 24 * 60 * 60 * 1000) {
                    return cache.translatedList
                } else {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e("CACHE", "Failed to load cache", e)
        }
        return null
    }

    /**
     * Replaces the text in the previously stored translation nodes with the translated list.
     * Assumes that window.translationNodes exists and is an array of text nodes.
     */
    private fun replaceTextInWebView(translatedList: List<String>) {
        val jsonArray = JSONArray(translatedList).toString()
        val js = """
        (function(translations) {
            if (!window.translationNodes || window.translationNodes.length !== translations.length) {
                console.error('Mismatch: translation nodes count = ' + (window.translationNodes ? window.translationNodes.length : 'undefined') + ', translations length = ' + translations.length);
                return -1;
            }
            for (var i = 0; i < translations.length; i++) {
                window.translationNodes[i].nodeValue = translations[i];
            }
            // Clear the stored nodes to free memory
            window.translationNodes = null;
            return translations.length;
        })($jsonArray);
    """.trimIndent()
        webView.evaluateJavascript(js) { result ->
            Log.d("REPLACE", "Replaced $result nodes")
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Replaced $result text nodes", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Extracts text from the page, applying exclusions (buttons, short links, already‑target‑language text).
     * Stores the text nodes that need translation in window.translationNodes and returns the texts as a JSON string.
     * @param targetLang The language we are translating to (used for language detection).
     */
    private fun getTextFromPage(targetLang: String, callback: (String) -> Unit) {
        val js = """
        (function(targetLang) {
            function isExcluded(node) {
                // 1. Check ancestors for button/input/link
                var el = node.parentElement;
                while (el) {
                    var tag = el.tagName;
                    if (tag === 'BUTTON') return true;
                    if (tag === 'INPUT') {
                        var type = el.type;
                        if (type === 'button' || type === 'submit' || type === 'reset') return true;
                    }
                    if (tag === 'A') {
                        // Exclude anchor text if its length <= 10 characters
                        return node.nodeValue.trim().length <= 10;
                    }
                    el = el.parentElement;
                }
                
                // 2. Language detection: if target language is English or Indonesian (Latin script),
                //    we consider text that contains only ASCII characters as already in target language.
                //    This is a simple heuristic; you can extend it for other languages.
                if (targetLang === 'English' || targetLang === 'Indonesia') {
                    var text = node.nodeValue;
                    // Allow letters, digits, spaces, punctuation, but no non-ASCII
                    if (/^[\x00-\x7F]*$/.test(text)) {
                        return true; // Already in target language (Latin script)
                    }
                }
                return false;
            }
            
            var walker = document.createTreeWalker(
                document.body,
                NodeFilter.SHOW_TEXT,
                null,
                false
            );
            var texts = [];
            var nodes = [];
            var node;
            while (node = walker.nextNode()) {
                if (node.nodeValue.trim().length > 0 && !isExcluded(node)) {
                    texts.push(node.nodeValue);
                    nodes.push(node);
                }
            }
            // Store the nodes globally for later replacement
            window.translationNodes = nodes;
            return JSON.stringify(texts);
        })('$targetLang');
    """.trimIndent()

        webView.evaluateJavascript(js) { result ->
            try {
                val arrayString = result.removeSurrounding("\"")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                callback(arrayString)
            } catch (e: Exception) {
                Log.e("getTextFromPage", "Failed to parse result: $result", e)
                callback("[]")
            }
        }
    }

    private fun deleteCacheForCurrentPage() {
        val currentUrl = webView.url ?: run {
            Toast.makeText(this, "No page loaded", Toast.LENGTH_SHORT).show()
            return
        }
        val fromLang = spinnerFrom.selectedItem.toString()
        val toLang = spinnerTo.selectedItem.toString()

        val cacheFile = getCacheFile(currentUrl, fromLang, toLang)
        if (cacheFile.exists()) {
            if (cacheFile.delete()) {
                Toast.makeText(this, "Cache deleted for this page", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to delete cache", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No cache found for this page", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseGeminiResponse(raw: String): List<String> {
        try {
            val json = JSONObject(raw)
            val text = json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")

            return try {
                JSONArray(text).let { array ->
                    (0 until array.length()).map { array.getString(it) }
                }
            } catch (e: JSONException) {
                val quoted = JSONObject.quote(text)
                val cleaned = quoted.substring(1, quoted.length - 1)
                val array = JSONArray(cleaned)
                (0 until array.length()).map { array.getString(it) }
            }
        } catch (e: Exception) {
            Log.e("API_PARSE", "Failed to parse", e)
            return emptyList()
        }
    }

    private fun translateChunk(
        chunk: List<String>,
        fromLang: String,
        toLang: String,
        callback: (List<String>) -> Unit
    ) {
        if (BuildConfig.GEMINI_API_KEY.isEmpty()) {
            Toast.makeText(this, "GEMINI_API_KEY is not set. Please add it to local.properties.", Toast.LENGTH_LONG).show()
            return
        }
        val jsonArray = JSONArray(chunk).toString()

        val prompt = """
            Translate this JSON array from $fromLang to $toLang.
            Keep the same order.
            Return ONLY JSON array.
            $jsonArray
        """.trimIndent()

        val bodyJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                    })
                })
            })
        }.toString()
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-lite-preview:generateContent")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-goog-api-key", BuildConfig.GEMINI_API_KEY)
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("API", "Error: ${e.message}")
                runOnUiThread {
                    loadingDialog?.dismiss()
                    btnTranslate.isEnabled = true
                    Toast.makeText(this@MainActivity, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.body?.string() ?: ""
                Log.d("API_RAW", result)

                runOnUiThread {
                    val translatedList = parseGeminiResponse(result)
                    if (translatedList.isNotEmpty()) {
                        callback(translatedList)
                    } else {
                        Toast.makeText(this@MainActivity, "Translation failed: invalid response", Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        urlInput = findViewById(R.id.urlInput)
        btnGo = findViewById(R.id.btnGo)
        btnTranslate = findViewById(R.id.btnTranslate)

        spinnerFrom = findViewById(R.id.spinnerFrom)
        spinnerTo = findViewById(R.id.spinnerTo)

        val fromLanguages = arrayOf("Japanese", "Chinese", "Korean")
        val toLanguages = arrayOf("English", "Indonesia")

        val adapterFrom = ArrayAdapter(this, android.R.layout.simple_spinner_item, fromLanguages)
        adapterFrom.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        val adapterTo = ArrayAdapter(this, android.R.layout.simple_spinner_item, toLanguages)
        adapterTo.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        spinnerFrom.adapter = adapterFrom
        spinnerTo.adapter = adapterTo

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.userAgentString = webView.settings.userAgentString
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.loadUrl("https://ncode.syosetu.com/n6134fz/1/")

        btnGo.setOnClickListener {
            var url = urlInput.text.toString()
            if (!url.startsWith("http")) {
                url = "https://$url"
            }
            webView.loadUrl(url)
        }
        btnClearCache = findViewById(R.id.btnClearCache)
        btnClearCache.setOnClickListener {
            deleteCacheForCurrentPage()
        }
        btnTranslate.setOnClickListener {
            btnTranslate.isEnabled = false

            val fromLang = spinnerFrom.selectedItem.toString()
            val toLang = spinnerTo.selectedItem.toString()
            val currentUrl = webView.url ?: ""

            val progressBar = ProgressBar(this).apply {
                isIndeterminate = true
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            loadingDialog = MaterialAlertDialogBuilder(this)
                .setTitle("Translating")
                .setMessage("Please wait...")
                .setView(progressBar)
                .setCancelable(false)
                .show()

            val cached = loadTranslationCache(currentUrl, fromLang, toLang)
            if (cached != null) {
                runOnUiThread {
                    loadingDialog?.dismiss()
                    btnTranslate.isEnabled = true
                    replaceTextInWebView(cached)
                    Toast.makeText(this, "Translation loaded from cache", Toast.LENGTH_SHORT).show()
                }
                return@setOnClickListener
            }

            // Extract texts that need translation (excluding already‑target‑language text)
            getTextFromPage(toLang) { textJson ->
                if (textJson.isEmpty() || textJson == "[]") {
                    runOnUiThread {
                        loadingDialog?.dismiss()
                        btnTranslate.isEnabled = true
                        Toast.makeText(this, "No text found on page.", Toast.LENGTH_SHORT).show()
                    }
                    return@getTextFromPage
                }

                val allTexts = try {
                    JSONArray(textJson).let { array ->
                        (0 until array.length()).map { array.getString(it) }
                    }
                } catch (e: JSONException) {
                    emptyList()
                }

                if (allTexts.isEmpty()) {
                    runOnUiThread {
                        loadingDialog?.dismiss()
                        btnTranslate.isEnabled = true
                        Toast.makeText(this, "No text nodes need translation.", Toast.LENGTH_SHORT).show()
                    }
                    return@getTextFromPage
                }

                val chunkSize = 120
                val chunks = allTexts.chunked(chunkSize)
                val translatedChunks = mutableListOf<List<String>>()
                var currentChunkIndex = 0

                fun translateNext() {
                    if (currentChunkIndex >= chunks.size) {
                        val combined = translatedChunks.flatten()
                        runOnUiThread {
                            loadingDialog?.dismiss()
                            btnTranslate.isEnabled = true
                            if (combined.isNotEmpty() && combined.size == allTexts.size) {
                                saveTranslationCache(currentUrl, fromLang, toLang, combined)
                                replaceTextInWebView(combined)
                                Toast.makeText(this, "Translation completed!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "Translation failed for some chunks.", Toast.LENGTH_LONG).show()
                            }
                        }
                        return
                    }

                    (loadingDialog?.findViewById<android.widget.TextView>(android.R.id.message))?.text =
                        getString(R.string.translating_chunk, currentChunkIndex + 1, chunks.size)

                    val chunk = chunks[currentChunkIndex]
                    translateChunk(chunk, fromLang, toLang) { translatedList ->
                        if (translatedList.isNotEmpty()) {
                            translatedChunks.add(translatedList)
                            currentChunkIndex++
                            translateNext()
                        } else {
                            runOnUiThread {
                                loadingDialog?.dismiss()
                                btnTranslate.isEnabled = true
                                Toast.makeText(this, "Translation failed for chunk ${currentChunkIndex + 1}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                translateNext()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }
}