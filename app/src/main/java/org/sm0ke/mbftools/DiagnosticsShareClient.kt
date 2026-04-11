package org.sm0ke.mbftools

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class SharedLogReceipt(
        val code: String,
        val command: String,
        val viewerUrl: String,
        val summaryUrl: String,
        val messageUrl: String,
        val summary: String
)

object DiagnosticsShareClient {
    fun upload(backendUrl: String, payload: JSONObject): SharedLogReceipt {
        val normalizedUrl = backendUrl.trim()
        require(normalizedUrl.isNotBlank()) { "Enter the Apps Script web app URL first." }

        val connection = (URL(normalizedUrl).openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

        val body =
                runCatching {
                            BufferedReader(
                                            InputStreamReader(
                                                    if (connection.responseCode in 200..299) {
                                                        connection.inputStream
                                                    } else {
                                                        connection.errorStream
                                                    },
                                                    Charsets.UTF_8
                                            )
                                    )
                                    .use { it.readText() }
                        }
                        .getOrDefault("")

        val json =
                runCatching { JSONObject(body) }
                        .getOrElse {
                            throw IllegalStateException(
                                    body.ifBlank {
                                        "The log backend returned an invalid response."
                                    }
                            )
                        }

        if (connection.responseCode !in 200..299 || !json.optBoolean("ok", false)) {
            throw IllegalStateException(
                    json.optString("error").ifBlank {
                        "The log backend rejected the upload."
                    }
            )
        }

        return SharedLogReceipt(
                code = json.optString("code"),
                command = json.optString("command"),
                viewerUrl = json.optString("viewerUrl"),
                summaryUrl = json.optString("summaryUrl"),
                messageUrl = json.optString("messageUrl"),
                summary = json.optString("summary")
        )
    }
}
