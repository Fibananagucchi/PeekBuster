package com.birdoffreedom.peekbuster.ai

import android.util.LruCache
import com.birdoffreedom.peekbuster.model.AppConnection
import com.birdoffreedom.peekbuster.model.AppInfo
import com.birdoffreedom.peekbuster.model.TrustScore
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class AIAnalysis(
    val trustScore: TrustScore,
    val explanation: String,
    val whyDangerous: String,
    val recommendation: String,
    val canBlock: Boolean,
    val confidenceScore: Int = 100,
    val encryptionStatus: String? = null
)

class AIAnalyzer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    
    private val apiKey = "" // NOTE: Your Groq API key should be here (https://console.groq.com/keys)

    companion object {
        private val analysisCache = LruCache<String, AIAnalysis>(100)
        
        fun clearCache() {
            analysisCache.evictAll()
        }
    }

    suspend fun analyzeAppFull(appInfo: AppInfo): AIAnalysis {
        val cacheKey = "app:${appInfo.packageName}:${appInfo.permissions.joinToString(",")}"
        analysisCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val prompt = """
                    You are a fair and technical privacy advisor named PeekBuster.
                    Analyze permissions for ${appInfo.appName}: ${appInfo.permissions.joinToString(", ")}
                    
                    ### FAIRNESS DIRECTIVE:
                    - **DO NOT** use 'DANGEROUS' if the permissions are standard for the app type.
                    - If you set 'DANGEROUS', you **MUST** provide a specific, technical reason in 'whyDangerous'.
                    - If 'whyDangerous' is "None identified" or similar, the 'trustScore' **MUST** be 'SAFE'.
                    
                    ### CLASSIFICATION EXAMPLES:
                    1. App: "Video Editor", Perms: [Camera, Mic, Storage] -> SAFE (Standard use)
                    2. App: "Weather", Perms: [Location, Contacts] -> SUSPICIOUS (Why contacts for weather?)
                    3. App: "Flashlight", Perms: [Camera, Mic, Contacts, Location] -> DANGEROUS (Mic/Contacts/Location are NOT needed for a light)

                    Respond with valid JSON only:
                    {
                        "trustScore": "SAFE" or "SUSPICIOUS" or "DANGEROUS",
                        "explanation": "Technical yet simple 2-sentence explanation.",
                        "whyDangerous": "Specific risks or 'None identified' if safe.",
                        "recommendation": "Next steps for the user.",
                        "canBlock": true,
                        "confidenceScore": 0-100
                    }
                """.trimIndent()

                val response = callGroq(prompt)
                val result = parseFullAnalysis(response)
                analysisCache.put(cacheKey, result)
                result
            } catch (e: Exception) {
                fallbackAnalysis()
            }
        }
    }

    suspend fun analyzeConnectionFull(connection: AppConnection): AIAnalysis {
        val cacheKey = "conn:${connection.packageName}:${connection.domain}"
        analysisCache.get(cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val prompt = """
                    You are a technical network security analyst named PeekBuster.
                    Explain connection for ${connection.appName} to ${connection.domain}.
                    
                    FAIRNESS RULES:
                    - If the traffic is encrypted (HTTPS/TLS), acknowledge this as a standard security practice that PROTECTS user data.
                    - Do not assume an encrypted connection is "hiding" something unless the destination domain is known for malware or data exfiltration.
                    - Distinguish between common CDN/Analytics (Suspicious/Safe) and unknown IP direct-transfers (Dangerous).
                    
                    Respond with valid JSON only:
                    {
                        "trustScore": "SAFE" or "SUSPICIOUS" or "DANGEROUS",
                        "explanation": "What this connection likely does.",
                        "whyDangerous": "Privacy risk assessment.",
                        "recommendation": "Actionable advice.",
                        "canBlock": true,
                        "encryptionStatus": "Encrypted (TLS)" or "Unencrypted" or "Unknown"
                    }
                """.trimIndent()

                val response = callGroq(prompt)
                val result = parseFullAnalysis(response)
                analysisCache.put(cacheKey, result)
                result
            } catch (e: Exception) {
                fallbackAnalysis()
            }
        }
    }

    private fun callGroq(prompt: String): String {
        var lastError: Exception? = null
        
        // Retry logic: 3 attempts with exponential backoff
        for (attempt in 0..2) {
            try {
                if (attempt > 0) {
                    val backoff = (1000L * attempt * attempt)
                    Thread.sleep(backoff)
                }

                val body = gson.toJson(mapOf(
                    "model" to "openai/gpt-oss-120b",
                    "messages" to listOf(
                        mapOf("role" to "user", "content" to prompt)
                    ),
                    "response_format" to mapOf("type" to "json_object"),
                    "temperature" to 0.5
                ))

                val url = "https://api.groq.com/openai/v1/chat/completions"

                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: throw Exception("Empty response")

                if (!response.isSuccessful) {
                    if (response.code == 429) {
                        // Specifically handle rate limit by waiting longer
                        Thread.sleep(2000L * (attempt + 1))
                        throw Exception("Groq Rate limit exceeded")
                    }
                    throw Exception("Groq API Error: ${response.code}")
                }

                val jsonResponse = gson.fromJson(responseBody, Map::class.java)
                val choices = (jsonResponse["choices"] as? List<*>)?.firstOrNull() as? Map<*, *>
                val message = choices?.get("message") as? Map<*, *>
                return message?.get("content") as? String ?: throw Exception("No text in Groq response")
            } catch (e: Exception) {
                lastError = e
                if (attempt == 2) throw e
            }
        }
        throw lastError ?: Exception("Unknown API error")
    }

    private fun parseFullAnalysis(response: String): AIAnalysis {
        return try {
            val clean = response.trim()
            val parsed = gson.fromJson(clean, Map::class.java)
            
            var trustScoreStr = parsed["trustScore"] as? String ?: "UNKNOWN"
            val whyDangerous = parsed["whyDangerous"] as? String ?: ""
            
            // CROSS-CHECK LOGIC: Prevent "DANGEROUS" score if the reasoning is "None" or similar.
            // This fixes hallucinations where AI flags an app as Red but says it's Safe in the text.
            val reasoningIsSafe = whyDangerous.lowercase().let { 
                it.contains("none identified") || it.contains("no specific risks") || it.isBlank() || it == "none" 
            }
            
            if (trustScoreStr == "DANGEROUS" && reasoningIsSafe) {
                trustScoreStr = "SAFE"
            }

            AIAnalysis(
                trustScore = when (trustScoreStr) {
                    "SAFE" -> TrustScore.SAFE
                    "SUSPICIOUS" -> TrustScore.SUSPICIOUS
                    "DANGEROUS" -> TrustScore.DANGEROUS
                    else -> TrustScore.UNKNOWN
                },
                explanation = parsed["explanation"] as? String ?: "",
                whyDangerous = if (reasoningIsSafe) "No privacy threats identified." else whyDangerous,
                recommendation = parsed["recommendation"] as? String ?: "",
                canBlock = parsed["canBlock"] as? Boolean ?: true,
                confidenceScore = (parsed["confidenceScore"] as? Double)?.toInt() ?: 100,
                encryptionStatus = parsed["encryptionStatus"] as? String
            )
        } catch (e: Exception) {
            fallbackAnalysis()
        }
    }

    private fun fallbackAnalysis() = AIAnalysis(
        trustScore = TrustScore.UNKNOWN,
        explanation = "Could not analyze this activity right now (Groq API limit or connection issue).",
        whyDangerous = "Please try again in a few seconds.",
        recommendation = "Try again later.",
        canBlock = false
    )
}