package org.sm0ke.mbftools

import java.net.HttpURLConnection
import java.net.URL

data class FaqItem(val question: String, val answer: String)

object FaqRepository {
    private const val FAQ_URL =
            "https://raw.githubusercontent.com/SmokeSlate/BSS-Wiki/main/docs/faq.md"

    fun fetchFaqItems(): List<FaqItem> {
        val connection = URL(FAQ_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("Accept", "text/plain")

        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseFaqMarkdown(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseFaqMarkdown(markdown: String): List<FaqItem> {
        val items = mutableListOf<FaqItem>()
        var question: String? = null
        var answerLines = mutableListOf<String>()

        fun flush() {
            val q = question?.trim().orEmpty()
            val a = answerLines.joinToString(" ").trim()
            if (q.isNotEmpty() && a.isNotEmpty()) {
                items.add(FaqItem(cleanMarkdown(q), cleanMarkdown(a)))
            }
            question = null
            answerLines = mutableListOf()
        }

        markdown.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("Q:") -> {
                    flush()
                    question = line.removePrefix("Q:").trim()
                }
                line.startsWith("A:") -> {
                    answerLines.add(line.removePrefix("A:").trim())
                }
                question != null && line.isNotEmpty() -> {
                    answerLines.add(line)
                }
                question != null && line.isEmpty() -> {
                    flush()
                }
            }
        }
        flush()
        return items
    }

    private fun cleanMarkdown(text: String): String {
        return text.replace(Regex("""\[(.+?)\]\(<.*?>\)"""), "$1")
                .replace(Regex("""\[(.+?)\]\(.*?\)"""), "$1")
                .replace("**", "")
                .replace("`", "")
                .replace("  ", " ")
                .trim()
    }
}
