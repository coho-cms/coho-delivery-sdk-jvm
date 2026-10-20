package com.cohocms.delivery

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.function.Consumer
import java.util.function.LongSupplier

/** One GET, as the client sends it. */
class TransportRequest(val url: String, val headers: Map<String, String>)

/** Its answer. Header names are lower case. */
class TransportResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray)

/**
 * How requests reach Coho. The default is the JDK's `HttpClient`; pass your own to set
 * timeouts, proxies or instrumentation, or to answer from somewhere else in tests.
 */
fun interface Transport
{
    fun send(request: TransportRequest): CompletableFuture<TransportResponse>

    companion object
    {
        /** The JDK's HTTP client, following no redirects, with a ten-second connect timeout. */
        @JvmStatic
        @JvmOverloads
        fun jdk(client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()): Transport =
            Transport()
            { request ->
                val builder = HttpRequest.newBuilder(URI.create(request.url)).GET()
                request.headers.forEach { (name, value) -> builder.header(name, value) }
                client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray()).thenApply()
                { response ->
                    val headers = response.headers().map().mapKeys { it.key.lowercase() }.mapValues { it.value.first() }
                    TransportResponse(response.statusCode(), headers, response.body())
                }
            }
    }
}

/**
 * How a client reaches Coho and keeps what it reads.
 *
 * @property transport How requests are sent. Defaults to the JDK's `HttpClient`.
 * @property cacheSize How many responses the private cache holds; 0 turns it off.
 * @property onIssue Called once per issue, for the life of the client. Defaults to a warning
 *   on the `com.cohocms.delivery` logger.
 */
data class ClientOptions @JvmOverloads constructor(
    val transport: Transport = Transport.jdk(),
    val cacheSize: Int = 1000,
    val onIssue: Consumer<Issue> = Consumer { issue -> LOGGER.warning("[coho] ${issue.message}") },
    internal val clock: LongSupplier = LongSupplier { System.currentTimeMillis() },
)
{
    fun withTransport(transport: Transport) = copy(transport = transport)

    fun withCacheSize(cacheSize: Int) = copy(cacheSize = cacheSize)

    fun withOnIssue(onIssue: Consumer<Issue>) = copy(onIssue = onIssue)

    internal companion object
    {
        val LOGGER: java.util.logging.Logger = java.util.logging.Logger.getLogger("com.cohocms.delivery")
    }
}

/** How many reference hops to follow, and in which locale. */
data class ReadOptions @JvmOverloads constructor(
    /** How many reference hops to follow from each root, 0 to 5. The server's default is 0. */
    val depth: Int? = null,
    /** The locale to project to. Without one, the client's own, or none. */
    val locale: String? = null,
)
{
    fun withDepth(depth: Int) = copy(depth = depth)

    fun withLocale(locale: String) = copy(locale = locale)

    companion object
    {
        /** No depth and no locale: the client's defaults. */
        @JvmField
        val DEFAULT = ReadOptions()

        @JvmStatic
        fun depth(depth: Int) = ReadOptions(depth = depth)
    }
}

/**
 * The delivery API, untyped: entries come back as the server sends them. Each read has a
 * blocking form and an `Async` one, which returns a `CompletableFuture`; from Kotlin
 * coroutines, `await()` it.
 *
 * Keeps a private cache of responses, following `Cache-Control` and `ETag`.
 */
class DeliveryClient @JvmOverloads constructor(
    val config: DeliveryConfig,
    private val options: ClientOptions = ClientOptions(),
)
{
    private val cache = if (options.cacheSize > 0) ResponseCache(options.cacheSize) else null

    /* READS */
    /* READS */
    /* READS */

    /**
     * A page of entries on [ref], in the order [order] asks for, or by id. [order] is a
     * sortable field of [type], prefixed `-` for descending, and needs [type].
     */
    @JvmOverloads
    fun entriesAsync(
        ref: String,
        type: String? = null,
        order: String? = null,
        limit: Int? = null,
        offset: Int? = null,
        depth: Int? = null,
        locale: String? = null,
    ): CompletableFuture<EntryGraph> =
        get(
            "${at(ref)}/entries",
            mapOf("type" to type, "order" to order, "limit" to limit, "offset" to offset, "depth" to depth, "locale" to locale),
        ).thenApply { EntryGraph.of(it) }

    /** One entry by id, or null if it is not on [ref]. */
    @JvmOverloads
    fun entryAsync(ref: String, id: String, options: ReadOptions = ReadOptions.DEFAULT): CompletableFuture<EntryGraph?> =
        orNull(get("${at(ref)}/entries/${encode(id)}", query(options)))

    /** One entry by slug, or null if it is not on [ref]. */
    @JvmOverloads
    fun entryBySlugAsync(ref: String, slug: String, options: ReadOptions = ReadOptions.DEFAULT): CompletableFuture<EntryGraph?> =
        orNull(get("${at(ref)}/entries-by-slug/${encode(slug)}", query(options)))

    /** Several entries by id, at most 100. An id not on [ref] is reported as `absent`. */
    @JvmOverloads
    fun entriesByIdAsync(ref: String, ids: List<String>, options: ReadOptions = ReadOptions.DEFAULT): CompletableFuture<EntryGraph>
    {
        if (ids.isEmpty())
        {
            return CompletableFuture.completedFuture(EntryGraph.NOTHING)
        }
        return get("${at(ref)}/entries-by-id", query(options) + ("ids" to ids.joinToString(","))).thenApply { EntryGraph.of(it) }
    }

    @JvmOverloads
    fun entries(
        ref: String,
        type: String? = null,
        order: String? = null,
        limit: Int? = null,
        offset: Int? = null,
        depth: Int? = null,
        locale: String? = null,
    ): EntryGraph = join(entriesAsync(ref, type, order, limit, offset, depth, locale))

    @JvmOverloads
    fun entry(ref: String, id: String, options: ReadOptions = ReadOptions.DEFAULT): EntryGraph? = join(entryAsync(ref, id, options))

    @JvmOverloads
    fun entryBySlug(ref: String, slug: String, options: ReadOptions = ReadOptions.DEFAULT): EntryGraph? =
        join(entryBySlugAsync(ref, slug, options))

    @JvmOverloads
    fun entriesById(ref: String, ids: List<String>, options: ReadOptions = ReadOptions.DEFAULT): EntryGraph =
        join(entriesByIdAsync(ref, ids, options))

    /* REQUESTS */
    /* REQUESTS */
    /* REQUESTS */

    private fun get(path: String, query: Map<String, Any?>): CompletableFuture<JsonNode>
    {
        val url = "${config.base}$path${queryString(query)}"
        val cached = cache?.get(url)
        if (cached != null && cached.freshUntil > options.clock.asLong)
        {
            return CompletableFuture.completedFuture(MAPPER.readTree(cached.body))
        }

        val headers = LinkedHashMap<String, String>()
        headers["accept"] = "application/json"
        config.credential?.let { headers["authorization"] = "Bearer $it" }
        cached?.etag?.let { headers["if-none-match"] = it }

        return options.transport.send(TransportRequest(url, headers)).thenApply()
        { response ->
            if (response.status == 304 && cached != null)
            {
                remember(url, cached.body, response, cached.etag)
                return@thenApply MAPPER.readTree(cached.body)
            }
            if (response.status !in 200..299)
            {
                throw refusal(response, config.reads)
            }
            val body = try
            {
                MAPPER.readTree(response.body)
            }
            catch (error: java.io.IOException)
            {
                throw CohoDeliveryException(
                    response.status,
                    "UNEXPECTED_RESPONSE",
                    "Delivery answered ${response.status} with a body that is not JSON",
                    cause = error,
                )
            }
            remember(url, response.body, response)
            body
        }
    }

    private fun remember(url: String, body: ByteArray, response: TransportResponse, previousEtag: String? = null)
    {
        val cache = cache ?: return
        val now = options.clock.asLong
        val until = ResponseCache.freshUntil(response.headers["cache-control"], now)
        val etag = response.headers["etag"] ?: previousEtag
        // Nothing to gain from keeping a body that can neither be served nor revalidated.
        if (until == null || (until <= now && etag == null))
        {
            cache.delete(url)
        }
        else
        {
            cache.set(url, ResponseCache.Cached(body, etag, until))
        }
    }

    private fun orNull(read: CompletableFuture<JsonNode>): CompletableFuture<EntryGraph?> =
        read.handle()
        { json, error ->
            val cause = (error as? CompletionException)?.cause ?: error
            when
            {
                cause == null -> EntryGraph.of(json)
                cause is CohoDeliveryException && cause.code == "ENTRY_NOT_FOUND" -> null
                else -> throw (cause as? RuntimeException ?: CompletionException(cause))
            }
        }

    internal companion object
    {
        val MAPPER = ObjectMapper()

        fun at(ref: String) = "/${encode(ref)}"

        fun query(options: ReadOptions): Map<String, Any?> = mapOf("depth" to options.depth, "locale" to options.locale)

        /**
         * Parameters in name order, whatever order the caller gave, so two call sites asking the
         * same question share one URL, and so one cache entry here and one at any edge. Commas
         * are left readable: `ids` is a comma-separated list.
         */
        fun queryString(query: Map<String, Any?>): String
        {
            val parts = query.entries
                .filter { it.value != null }
                .sortedBy { it.key }
                .map { "${it.key}=${encode(it.value.toString()).replace("%2C", ",")}" }
            return if (parts.isEmpty()) "" else "?${parts.joinToString("&")}"
        }

        /**
         * What to check, for the refusals whose answer is deliberately vague. A credential for
         * another project is answered exactly as a project that does not exist, so that one
         * customer's cannot confirm another's project; and each side serves only its own refs.
         */
        val HINTS = mapOf(
            Reads.RELEASED to mapOf(
                "PROJECT_NOT_FOUND" to " Check that the key was issued for this project.",
                "REF_NOT_FOUND" to " Delivery serves tags and the environments pointing at them; read a branch, or dev, with a project token.",
            ),
            Reads.PREVIEW to mapOf(
                "PROJECT_NOT_FOUND" to " Check that the token was issued for this project, and that the account is the one it belongs to.",
                "REF_NOT_FOUND" to " A preview reads branches, and environments pointing at one such as dev; a release is read from delivery, with a key.",
            ),
        )

        fun refusal(response: TransportResponse, reads: Reads): CohoDeliveryException
        {
            // Either JSON type: the contract says `application/problem+json`, and servers before
            // 2026-10-13 labelled problems `application/json`.
            val problem = if ((response.headers["content-type"] ?: "").contains("json"))
            {
                runCatching { MAPPER.readTree(response.body) }.getOrNull()?.takeIf { it.path("code").isTextual }
            }
            else
            {
                null
            }
            if (problem == null)
            {
                return CohoDeliveryException(response.status, "UNEXPECTED_RESPONSE", "Delivery answered ${response.status} with no problem document")
            }
            val code = problem.path("code").asText()
            val text = (problem.get("detail")?.asText() ?: problem.path("title").asText()).trimEnd('.')
            return CohoDeliveryException(response.status, code, "$code: $text.${HINTS[reads]?.get(code) ?: ""}", problem)
        }
    }
}

/** A blocking read's result, with the refusal it may raise rather than its wrapper. */
internal fun <T> join(future: CompletableFuture<T>): T =
    try
    {
        future.join()
    }
    catch (error: CompletionException)
    {
        throw error.cause ?: error
    }
