package com.cohocms.delivery

import java.util.function.LongSupplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeliveryClientTest
{
    private val token = "coho_pt_${"a".repeat(43)}"

    private fun released(transport: Transport, key: String? = KEY, cacheSize: Int = 1000, clock: LongSupplier = LongSupplier { 0L }) =
        DeliveryClient(ReleasedConfig("https://delivery.test", "p1", key), ClientOptions(transport, cacheSize, clock = clock))

    /* REQUESTS */
    /* REQUESTS */
    /* REQUESTS */

    @Test
    fun `requests go to the contract's paths, with the key in a header and the parameters in name order`()
    {
        val answering = Answering()
        val client = DeliveryClient(ReleasedConfig("https://delivery.test/", "p1", "secret"), ClientOptions(answering, 0))

        client.entries("v1.0.0", type = "article", order = "-rank", limit = 3, locale = "en-US")
        client.entryBySlug("release/1", "a b")
        client.entriesById("prod", listOf("id-2", "id-1"), ReadOptions(depth = 1))

        assertEquals(
            listOf(
                "https://delivery.test/v1/projects/p1/v1.0.0/entries?limit=3&locale=en-US&order=-rank&type=article",
                "https://delivery.test/v1/projects/p1/release%2F1/entries-by-slug/a%20b",
                "https://delivery.test/v1/projects/p1/prod/entries-by-id?depth=1&ids=id-2,id-1",
            ),
            answering.asked.map { it.url },
        )
        for (request in answering.asked)
        {
            assertEquals("Bearer secret", request.headers["authorization"])
            assertFalse("secret" in request.url)
        }
    }

    @Test
    fun `a mounted base URL replaces the url and project`()
    {
        val answering = Answering()
        DeliveryClient(MountedConfig("https://proxy.test/coho/v1/projects/p1/"), ClientOptions(answering, 0)).entries("prod")

        assertEquals("https://proxy.test/coho/v1/projects/p1/prod/entries", answering.asked.single().url)
        assertNull(answering.asked.single().headers["authorization"])
    }

    @Test
    fun `no ids asks nothing`()
    {
        val answering = Answering()
        assertEquals(emptyList(), released(answering).entriesById("prod", emptyList()).roots)
        assertTrue(answering.asked.isEmpty())
    }

    @Test
    fun `a config never shows its credential`()
    {
        assertFalse("secret" in ReleasedConfig("https://delivery.test", "p1", "secret").toString())
        assertFalse(token in PreviewConfig("https://coho.test", "acme", "p1", token).toString())
    }

    /* PREVIEWS */
    /* PREVIEWS */
    /* PREVIEWS */

    @Test
    fun `a preview reads through Coho's preview mount, with the token in a header, never from the cache`()
    {
        val answering = Answering(headers = mapOf("content-type" to "application/json", "cache-control" to "no-cache"))
        val client = DeliveryClient(PreviewConfig("https://coho.test/", "acme", "p1", token), ClientOptions(answering))

        client.entries("dev", type = "article", locale = "en-US")
        client.entries("dev", type = "article", locale = "en-US")
        client.entryBySlug("feature/x", "hello")

        assertEquals(
            listOf(
                "https://coho.test/api/v1/accounts/acme/projects/p1/preview/dev/entries?locale=en-US&type=article",
                "https://coho.test/api/v1/accounts/acme/projects/p1/preview/dev/entries?locale=en-US&type=article",
                "https://coho.test/api/v1/accounts/acme/projects/p1/preview/feature%2Fx/entries-by-slug/hello",
            ),
            answering.asked.map { it.url },
        )
        assertTrue(answering.asked.all { it.headers["authorization"] == "Bearer $token" && "if-none-match" !in it.headers })
    }

    @Test
    fun `a preview takes a project token`()
    {
        assertFailsWith<IllegalArgumentException> { PreviewConfig("https://coho.test", "acme", "p1", "eyJhbGciOi") }
    }

    /* REPLAYED */
    /* REPLAYED */
    /* REPLAYED */

    @Test
    fun `reads return the graph as delivery sent it`()
    {
        val client = released(Replay())

        val graph = client.entryBySlug("v0.0.1", "chain-1", ReadOptions(2, "en-US"))!!
        assertEquals(Spec.body("entry-by-slug-depth-2"), graph.json)
        val page = client.entries("v0.0.1", type = "article", order = "-rank", limit = 3, locale = "en-US")
        assertTrue(page.hasMore)
        assertEquals(Spec.body("entries-ordered").path("roots").map { it.asText() }, page.roots)
    }

    @Test
    fun `a missing entry is null, not an exception`()
    {
        assertNull(released(Replay()).entryBySlug("v0.0.1", "nope"))
    }

    @Test
    fun `each refusal raises its code, and says what to check`()
    {
        fun refused(key: String?, read: (DeliveryClient) -> Unit) =
            assertFailsWith<CohoDeliveryException> { read(released(Replay(), key)) }

        refused(KEY) { it.entries("v9.9.9") }.let()
        {
            assertEquals(404 to "REF_NOT_FOUND", it.status to it.code)
            assertTrue("with a project token" in it.message!!)
        }
        assertEquals("INVALID_QUERY", refused(KEY) { it.entries("v0.0.1", order = "rank") }.code)
        assertEquals("KEY_REQUIRED", refused(null) { it.entries("v0.0.1") }.code)
        refused(OTHER_KEY) { it.entries("v0.0.1") }.let()
        {
            assertEquals("PROJECT_NOT_FOUND", it.code)
            assertTrue("issued for this project" in it.message!!)
        }
    }

    @Test
    fun `an answer that is not a problem document is unexpected`()
    {
        val error = assertFailsWith<CohoDeliveryException>()
        {
            released(Answering(502, "<html>Bad gateway</html>", mapOf("content-type" to "text/html"))).entries("prod")
        }
        assertEquals(502 to "UNEXPECTED_RESPONSE", error.status to error.code)
    }

    /* CACHING */
    /* CACHING */
    /* CACHING */

    @Test
    fun `a tag is fetched once, and a stale answer is revalidated`()
    {
        val replay = Replay()
        var now = 0L
        val client = released(replay, clock = { now })

        client.entryBySlug("v0.0.1", "chain-1", ReadOptions(1, "en-US"))
        client.entryBySlug("v0.0.1", "chain-1", ReadOptions(1, "en-US"))
        assertEquals(1, replay.asked.size)

        // A year and a day later, the tag's answer is stale, so the client asks again.
        now += 366L * 24 * 3600 * 1000
        val again = client.entryBySlug("v0.0.1", "chain-1", ReadOptions(1, "en-US"))!!
        assertEquals(Spec.body("entry-by-slug-depth-1"), again.json)
        assertEquals("\"entry-by-slug-depth-1\"", replay.asked[1].headers["if-none-match"])
    }

    @Test
    fun `cacheSize 0 turns the cache off`()
    {
        val replay = Replay()
        val client = released(replay, cacheSize = 0)
        repeat(2) { client.entryBySlug("v0.0.1", "chain-1", ReadOptions(1, "en-US")) }
        assertEquals(2, replay.asked.size)
    }

    @Test
    fun `freshness follows Cache-Control`()
    {
        assertNull(ResponseCache.freshUntil("no-store", 100))
        assertEquals(100L, ResponseCache.freshUntil("no-cache, max-age=60", 100))
        assertEquals(60_100L, ResponseCache.freshUntil("public, max-age=60", 100))
        assertEquals(100L, ResponseCache.freshUntil(null, 100))
    }

    @Test
    fun `the async reads complete with the same`()
    {
        val graph = released(Replay()).entryBySlugAsync("v0.0.1", "chain-1", ReadOptions(2, "en-US")).join()!!
        assertEquals(Spec.body("entry-by-slug-depth-2"), graph.json)
    }
}
