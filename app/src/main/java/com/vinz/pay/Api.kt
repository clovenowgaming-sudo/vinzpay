package com.vinz.pay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Api {
    var base = ""
    var token = ""

    suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject =
        withContext(Dispatchers.IO) {
            var b = base.trim().trimEnd('/')
            if (!b.startsWith("http://") && !b.startsWith("https://")) b = "http://$b"
            val c = URL(b + path).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 10000
                c.readTimeout = 15000
                c.setRequestProperty("Content-Type", "application/json")
                if (token.isNotEmpty()) c.setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = c.responseCode
                val s = (if (code in 200..299) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.readText() ?: "{}"
                val j = try { JSONObject(s) } catch (e: Exception) { JSONObject() }
                if (code !in 200..299) throw Exception(j.optString("error", "Error $code"))
                j
            } catch (e: java.io.IOException) {
                throw Exception("Tidak bisa terhubung ke $b (${e.javaClass.simpleName}: ${e.message}). Pastikan server jalan & URL benar.")
            } finally {
                c.disconnect()
            }
        }
}
