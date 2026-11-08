package com.genieacs.client.data.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class GeminiAiService(
    private val apiKey: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Models ordered by priority (gemini-3-flash-preview is ultra-fast & highly responsive)
    private val candidateModels = listOf(
        "gemini-3-flash-preview",
        "gemini-flash-lite-latest",
        "gemini-flash-latest",
        "gemini-3.8-flash"
    )

    suspend fun askGemini(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(Exception("Gemini API Key belum diatur"))
        }

        var lastError: Exception? = null

        for (model in candidateModels) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

                val systemInstruction = "Kamu adalah asisten AI cerdas untuk teknisi ISP dan ahli jaringan TR-069 GenieACS. " +
                        "Jawablah pertanyaan secara ringkas, lugas, ramah, dan solutif dalam Bahasa Indonesia (maksimal 2 hingga 3 kalimat). " +
                        "Hindari tanda format markdown seperti bintang, tagar, garis bawah, atau bullet point agar mudah dan nyaman saat dibacakan dengan Text-To-Speech."

                val fullPrompt = "$systemInstruction\n\nPertanyaan teknisi:\n$prompt"

                val bodyJson = JsonObject().apply {
                    val contentsArr = JsonArray().apply {
                        val contentObj = JsonObject().apply {
                            val partsArr = JsonArray().apply {
                                val textObj = JsonObject().apply {
                                    addProperty("text", fullPrompt)
                                }
                                add(textObj)
                            }
                            add("parts", partsArr)
                        }
                        add(contentObj)
                    }
                    add("contents", contentsArr)
                }

                val request = Request.Builder()
                    .url(url)
                    .addHeader("Content-Type", "application/json")
                    .post(bodyJson.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = client.newCall(request).execute()
                val code = response.code
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val root = JsonParser.parseString(responseBody).asJsonObject
                    val candidates = root.getAsJsonArray("candidates")
                    if (candidates != null && candidates.size() > 0) {
                        val firstCand = candidates.get(0).asJsonObject
                        val content = firstCand.getAsJsonObject("content")
                        val parts = content.getAsJsonArray("parts")
                        if (parts != null && parts.size() > 0) {
                            val rawText = parts.get(0).asJsonObject.get("text").asString
                            // Clean up remaining markdown symbols for TTS clarity
                            val cleanText = rawText
                                .replace(Regex("[*#_`~>|\\[\\]()]"), " ")
                                .replace(Regex("\\s+"), " ")
                                .trim()
                            return@withContext Result.success(cleanText)
                        }
                    }
                    return@withContext Result.failure(Exception("Format jawaban Gemini tidak dikenali"))
                } else {
                    lastError = Exception("HTTP $code: $responseBody")
                    // If error is 404 or 503, loop to try next candidate model
                    continue
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        Result.failure(lastError ?: Exception("Gagal menghubungi Gemini AI"))
    }
}
