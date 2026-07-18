package app.gyrolet.mpvrx.repository.ai

import kotlinx.serialization.json.JsonObject

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Parses the response variants returned by the supported AI provider protocols. */
internal object AiResponseParser {
  fun openAiCompatible(json: Json, body: String, provider: String): AiGeneratedContent {
    val root = parseObject(json, body, provider)
    throwEmbeddedError(root, provider)
    val choice = root["choices"].asArray()?.firstOrNull().asObject()
      ?: throw IllegalStateException("$provider returned no completion choices")
    choice["error"].asObject()?.let { throw IllegalStateException(errorMessage(it, provider)) }
    val message = choice["message"].asObject()
      ?: throw IllegalStateException("$provider returned no assistant message")
    val text = extractText(message["content"])
    val reasoning = listOf("reasoning", "reasoning_content", "reasoning_details")
      .mapNotNull { extractText(message[it]) }
      .joinToString("\n")
      .trim()
      .ifBlank { null }
    return completed(provider, text, reasoning, collectSources(root, message))
  }

  fun openAiResponses(json: Json, body: String, provider: String): AiGeneratedContent {
    val root = parseObject(json, body, provider)
    throwEmbeddedError(root, provider)
    val text = root["output_text"].stringValue()
      ?: root["output"].asArray().orEmpty()
        .flatMap { it.asObject()?.get("content").asArray().orEmpty() }
        .mapNotNull { part ->
          val obj = part.asObject()
          if (obj?.get("type").stringValue() in setOf("output_text", "text")) extractText(obj?.get("text")) else null
        }
        .joinToString("\n")
        .trim()
        .ifBlank { null }
    val reasoning = root["output"].asArray().orEmpty()
      .mapNotNull { item ->
        val obj = item.asObject()
        if (obj?.get("type").stringValue() == "reasoning") extractText(obj) else null
      }
      .joinToString("\n")
      .trim()
      .ifBlank { null }
    return completed(provider, text, reasoning, collectSources(root))
  }

  fun anthropic(json: Json, body: String, provider: String): AiGeneratedContent {
    val root = parseObject(json, body, provider)
    throwEmbeddedError(root, provider)
    val blocks = root["content"].asArray().orEmpty().mapNotNull { it.asObject() }
    val text = blocks.filter { it["type"].stringValue() == "text" }
      .mapNotNull { it["text"].stringValue() }
      .joinToString("\n")
      .trim()
      .ifBlank { null }
    val reasoning = blocks.filter { it["type"].stringValue() in setOf("thinking", "redacted_thinking") }
      .mapNotNull { extractText(it["thinking"] ?: it["data"]) }
      .joinToString("\n")
      .trim()
      .ifBlank { null }
    return completed(provider, text, reasoning, collectSources(root))
  }

  fun google(json: Json, body: String, provider: String): AiGeneratedContent {
    val root = parseObject(json, body, provider)
    throwEmbeddedError(root, provider)
    val candidate = root["candidates"].asArray()?.firstOrNull().asObject()
      ?: throw IllegalStateException("$provider returned no candidates")
    val parts = candidate["content"].asObject()?.get("parts").asArray().orEmpty()
    val text = parts.mapNotNull { it.asObject()?.get("text").stringValue() }
      .joinToString("")
      .trim()
      .ifBlank { null }
    return completed(provider, text, null, collectSources(root))
  }

  fun error(json: Json, body: String): String = runCatching {
    val root = json.parseToJsonElement(body).asObject()
    root?.get("error").asObject()?.let { errorMessage(it, "AI provider") }
      ?: root?.get("message").stringValue()
      ?: body.take(400)
  }.getOrElse { body.take(400) }

  fun modelArray(json: Json, body: String, provider: String): List<JsonObject> {
    val root = runCatching { json.parseToJsonElement(body) }
      .getOrElse { throw IllegalStateException("$provider returned invalid JSON: ${it.message}") }
    val models = when (root) {
      is JsonArray -> root
      is JsonObject -> root["data"].asArray() ?: root["models"].asArray()
      else -> null
    } ?: throw IllegalStateException("$provider returned an unsupported model-list response")
    return models.mapNotNull { it.asObject() }
  }

  private fun parseObject(json: Json, body: String, provider: String): JsonObject =
    runCatching { json.parseToJsonElement(body).jsonObject }
      .getOrElse { throw IllegalStateException("$provider returned invalid JSON: ${it.message}") }

  private fun completed(
    provider: String,
    text: String?,
    reasoning: String?,
    sources: List<AiSource>,
  ): AiGeneratedContent {
    if (text.isNullOrBlank()) {
      val detail = if (reasoning.isNullOrBlank()) "no text" else "reasoning but no final answer"
      throw IllegalStateException("$provider returned $detail. Select a text model or increase its output limit.")
    }
    return AiGeneratedContent(text.trim(), reasoning, sources)
  }

  private fun throwEmbeddedError(root: JsonObject, provider: String) {
    root["error"].asObject()?.let { throw IllegalStateException(errorMessage(it, provider)) }
  }

  private fun errorMessage(error: JsonObject, provider: String): String =
    error["message"].stringValue()
      ?: error["detail"].stringValue()
      ?: "$provider request failed"

  private fun extractText(element: JsonElement?): String? = when (element) {
    is JsonPrimitive -> element.contentOrNull
    is JsonArray -> element.mapNotNull(::extractText).joinToString("\n").trim().ifBlank { null }
    is JsonObject -> listOf("text", "content", "thinking", "summary", "data")
      .firstNotNullOfOrNull { key -> extractText(element[key]) }
    else -> null
  }

  private fun collectSources(vararg roots: JsonObject): List<AiSource> {
    val sources = linkedMapOf<String, AiSource>()
    val sourceKeys = setOf(
      "annotations",
      "citations",
      "sources",
      "search_results",
      "url_citation",
      "groundingMetadata",
      "grounding_metadata",
      "groundingChunks",
      "grounding_chunks",
      "retrieved_context",
      "web",
    )
    fun visit(element: JsonElement?, inSourceContainer: Boolean = false) {
      when (element) {
        is JsonArray -> element.forEach { visit(it, inSourceContainer) }
        is JsonObject -> {
          val sourceContainer = inSourceContainer || element.keys.any { it in sourceKeys }
          if (sourceContainer) {
            val nested = element["url_citation"].asObject()
            val url = nested?.get("url").stringValue()
              ?: element["url"].stringValue()
              ?: element["uri"].stringValue()
            if (!url.isNullOrBlank() && (url.startsWith("https://") || url.startsWith("http://"))) {
              val title = nested?.get("title").stringValue() ?: element["title"].stringValue()
              sources[url] = AiSource(url, title)
            }
          }
          element.forEach { (key, value) ->
            visit(value, sourceContainer || key in sourceKeys)
          }
        }
        is JsonPrimitive -> {
          val url = element.contentOrNull
          if (inSourceContainer && !url.isNullOrBlank() &&
            (url.startsWith("https://") || url.startsWith("http://"))
          ) {
            sources[url] = AiSource(url)
          }
        }
        else -> Unit
      }
    }
    roots.forEach { visit(it) }
    return sources.values.toList()
  }

  private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
  private fun JsonElement?.asArray(): JsonArray? = this as? JsonArray
  private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.contentOrNull
}
