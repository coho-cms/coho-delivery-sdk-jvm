package com.cohocms.delivery

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.CompletableFuture

/** The spec's recordings, which every Coho SDK is tested against. */
object Spec
{
    val root = File(System.getProperty("coho.spec") ?: "../spec")
    val mapper = ObjectMapper()

    fun recording(name: String): JsonNode = mapper.readTree(File(root, "delivery/recordings/$name.json"))

    fun body(name: String): JsonNode = recording(name).path("response").path("body")
}

const val BASE = "https://delivery.test/v1/projects/p1"
const val KEY = "test-key"
const val OTHER_KEY = "other-key"

/**
 * Answers from the recordings, as the server did, plus `304` for an `If-None-Match` naming the
 * recording's ETag. Each recording's ETag is its name.
 */
class Replay : Transport
{
    val asked = mutableListOf<TransportRequest>()

    private val all = File(Spec.root, "delivery/recordings").listFiles()!!
        .filter { it.name.endsWith(".json") }
        .map { it.nameWithoutExtension to Spec.mapper.readTree(it) }

    override fun send(request: TransportRequest): CompletableFuture<TransportResponse>
    {
        asked += request
        check(request.url.startsWith(BASE)) { "Not a delivery URL: ${request.url}" }
        val authorization = request.headers["authorization"]
        val auth = when (authorization)
        {
            null -> "none"
            "Bearer $KEY" -> "key"
            else -> "other-key"
        }
        val wanted = normalize(request.url.removePrefix(BASE))
        val (name, recorded) = all.firstOrNull()
        { (_, recorded) ->
            recorded.path("request").path("auth").asText() == auth && normalize(recorded.path("request").path("path").asText()) == wanted
        } ?: error("Nothing recorded for $auth $wanted")

        val response = recorded.path("response")
        val headers = HashMap<String, String>()
        response.path("headers").path("content-type").takeIf { it.isTextual }?.let { headers["content-type"] = it.asText() }
        response.path("headers").path("cache-control").takeIf { it.isTextual }?.let { headers["cache-control"] = it.asText() }
        val etag = if (response.path("headers").path("etag").asBoolean()) "\"$name\"" else null
        etag?.let { headers["etag"] = it }
        if (etag != null && request.headers["if-none-match"] == etag)
        {
            return CompletableFuture.completedFuture(TransportResponse(304, headers, ByteArray(0)))
        }
        return CompletableFuture.completedFuture(
            TransportResponse(response.path("status").asInt(), headers, Spec.mapper.writeValueAsBytes(response.path("body"))),
        )
    }

    /** The path, and the query in name order and decoded, so equal requests compare equal. */
    private fun normalize(pathAndQuery: String): String
    {
        val path = pathAndQuery.substringBefore('?')
        val query = pathAndQuery.substringAfter('?', "")
        val params = if (query.isEmpty()) emptyList() else query.split('&').map { URLDecoder.decode(it, Charsets.UTF_8) }.sorted()
        return "${URLDecoder.decode(path, Charsets.UTF_8)}?${params.joinToString("&")}"
    }
}

/** Answers every request with one response, recording what it was asked. */
class Answering(
    private val status: Int = 200,
    private val body: String = """{"roots":[],"entries":{},"unresolved":{},"hasMore":false}""",
    private val headers: Map<String, String> = mapOf("content-type" to "application/json"),
) : Transport
{
    val asked = mutableListOf<TransportRequest>()

    override fun send(request: TransportRequest): CompletableFuture<TransportResponse>
    {
        asked += request
        return CompletableFuture.completedFuture(TransportResponse(status, headers, body.toByteArray()))
    }
}
