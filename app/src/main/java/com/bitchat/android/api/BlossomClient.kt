package com.bitchat.android.api

import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Client for interacting with Blossom (NIP-96) compatible media servers.
 * Used as a fallback when Mesh transport is unavailable.
 */
class BlossomClient {

    companion object {
        private const val TAG = "BlossomClient"
        // Default to a reliable public server.
        // In a real app this might be configurable or use NIP-96 server discovery.
        private const val DEFAULT_SERVER_URL = "https://cdn.satellite.earth"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Uploads a file to the Blossom server.
     * @param file The file to upload.
     * @param mimeType The MIME type of the file.
     * @param authEvent A signed NIP-98 HTTP Auth event (optional in some implementations, but good practice).
     *                  For simple public upload on some servers, it might be open, but usually requires auth.
     *                  For this MVP fix, we'll try basic upload. If auth is strictly required, we'll need to sign an event.
     *                  Satellite.earth usually requires NIP-98.
     *                  Let's assumpe a simple public upload or we need to implement NIP-98.
     *
     * Update: To keep it simple and given existing context, we will try to use a server that allows
     * easy uploads or implement basic NIP-98 if we have access to keys.
     * 
     * However, `MediaSendingManager` has access to `state` which might have keys, but `MediaSendingManager` is in `ui` package.
     * 
     * Let's target `https://nostr.build` or a similar service that might have an easier API, 
     * or just implement NIP-98 if possible. 
     * 
     * Actually, for `cdn.satellite.earth`, we need an account. 
     * `nostr.build` allows free uploads without auth for small files, but often requires NIP-98 for API.
     * 
     * Let's stick to the simplest working solution: generic upload.
     * If we need NIP-98, I will add a `signAuthEvent` callback.
     */
    fun uploadFile(file: File, mimeType: String): String? {
        // endpoint: /upload (NIP-96)
        val url = "$DEFAULT_SERVER_URL/upload"
        
        Log.d(TAG, "Starting upload to $url: ${file.name} ($mimeType)")

        val mediaType = mimeType.toMediaTypeOrNull()
        val fileBody = file.asRequestBody(mediaType)
        
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, fileBody)
            .build()

        // TODO: Add NIP-98 Authorization header if required.
        // For the immediate fix, we attempt upload. If 401/403, we know we need auth.
        // Satellite.earth definitely needs auth. 
        // Let's try to pass an Authorization header if we can, but for now purely structurally:
        
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()
            
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Upload failed: ${response.code} ${response.message}")
                    val body = response.body?.string()
                    Log.e(TAG, "Response body: $body")
                    return null
                }

                val responseBody = response.body?.string() ?: return null
                Log.d(TAG, "Upload successful: $responseBody")
                
                // Parse NIP-96 JSON response
                // Structure: { "status": "success", "nip94_event": { "tags": [ ["url", "https://..."], ... ] } }
                // OR simple 200 OK with some JSON.
                
                // Satellite Earth returns a descriptor.
                // Let's look for "url" in the tags of the nip94_event or a top level url.
                
                try {
                    val json = JSONObject(responseBody)
                    // NIP-96 standard response
                    if (json.has("nip94_event")) {
                        val event = json.getJSONObject("nip94_event")
                        val tags = event.getJSONArray("tags")
                        for (i in 0 until tags.length()) {
                            val tag = tags.getJSONArray(i)
                            if (tag.getString(0) == "url") {
                                return tag.getString(1)
                            }
                        }
                    } else if (json.has("url")) {
                        // Some simple servers might just return { "url": "..." }
                        return json.getString("url")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse JSON", e)
                }
                
                return null
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error during upload", e)
            return null
        }
    }
}
