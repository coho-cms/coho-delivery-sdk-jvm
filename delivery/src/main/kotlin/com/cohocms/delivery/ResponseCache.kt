package com.cohocms.delivery

/**
 * A private cache of delivery responses, honouring `Cache-Control` and `ETag`.
 *
 * Private is what makes it allowed: the client holds the key, so it may keep keyed responses
 * that a shared cache must not. A tag named directly is `immutable` for a year and is fetched
 * once. An environment is fresh for its short `max-age`, then revalidated with
 * `If-None-Match`, which costs a `304` rather than a body when nothing was promoted.
 */
internal class ResponseCache(private val maxEntries: Int)
{
    /** The body as it arrived: each read parses its own copy, so no caller changes another's. */
    class Cached(val body: ByteArray, val etag: String?, val freshUntil: Long)

    // Access order, so the eldest is the least recently used.
    private val entries = object : LinkedHashMap<String, Cached>(16, 0.75f, true)
    {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>) = size > maxEntries
    }

    @Synchronized
    fun get(url: String): Cached? = entries[url]

    @Synchronized
    fun set(url: String, cached: Cached)
    {
        entries[url] = cached
    }

    @Synchronized
    fun delete(url: String)
    {
        entries.remove(url)
    }

    companion object
    {
        /**
         * Until when, in milliseconds since the epoch, a response may be used without asking,
         * from its `Cache-Control`; or null when it must not be stored at all. A response that
         * makes no claim is stale at once, so it is only ever reused after a `304`.
         */
        fun freshUntil(cacheControl: String?, now: Long): Long?
        {
            var noStore = false
            var noCache = false
            var maxAge: Long? = null
            for (directive in (cacheControl ?: "").split(','))
            {
                val (name, value) = directive.trim().lowercase().split('=', limit = 2).let { it[0] to it.getOrNull(1) }
                when
                {
                    name == "no-store" -> noStore = true
                    name == "no-cache" -> noCache = true
                    name == "max-age" -> maxAge = value?.toLongOrNull()?.takeIf { it >= 0 }
                }
            }
            return when
            {
                noStore -> null
                noCache || maxAge == null -> now
                else -> now + maxAge!! * 1000
            }
        }
    }
}
