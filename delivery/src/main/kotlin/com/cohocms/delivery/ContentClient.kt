package com.cohocms.delivery

import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * Hydrated reads on one ref: what generated code builds its typed client from, so the logic
 * lives in this package and reaches every library with an update.
 *
 * Each read has a blocking form and an `Async` one, which returns a `CompletableFuture`; from
 * Kotlin coroutines, `await()` it.
 *
 * @param factories How to build each content and component type, by slug. Generated code
 *   writes them; without them, entries are [HydratedEntry]s.
 */
class ContentClient @JvmOverloads constructor(
    val config: ContentConfig,
    private val factories: Map<String, EntryFactory> = emptyMap(),
    options: ClientOptions = ClientOptions(),
)
{
    /** The untyped client underneath, for anything the hydrated reads do not cover. */
    val delivery = DeliveryClient(config.delivery, options)

    private val issues = Issues(options.onIssue)

    internal fun locale(asked: String?) = asked ?: config.locale

    internal fun all(graph: EntryGraph): List<EntryBase> = hydrate(graph, factories, issues)

    internal fun first(graph: EntryGraph?): EntryBase? = graph?.let { all(it).firstOrNull() }

    private fun read(options: ReadOptions) = options.copy(locale = locale(options.locale))

    /** The entry with this slug, whatever its type, or null. */
    @JvmOverloads
    fun bySlugAsync(slug: String, options: ReadOptions = ReadOptions.DEFAULT): CompletableFuture<EntryBase?> =
        delivery.entryBySlugAsync(config.ref, slug, read(options)).thenApply { first(it) }

    /** The entry with this id, whatever its type, or null. */
    @JvmOverloads
    fun byIdAsync(id: String, options: ReadOptions = ReadOptions.DEFAULT): CompletableFuture<EntryBase?> =
        delivery.entryAsync(config.ref, id, read(options)).thenApply { first(it) }

    /** The entries that exist, sorted by id. Ids not on the ref are left out. */
    @JvmOverloads
    fun byIdsAsync(ids: List<String>, options: ReadOptions = ReadOptions.DEFAULT): CompletableFuture<List<EntryBase>> =
        delivery.entriesByIdAsync(config.ref, ids, read(options)).thenApply { all(it) }

    @JvmOverloads
    fun bySlug(slug: String, options: ReadOptions = ReadOptions.DEFAULT): EntryBase? = join(bySlugAsync(slug, options))

    @JvmOverloads
    fun byId(id: String, options: ReadOptions = ReadOptions.DEFAULT): EntryBase? = join(byIdAsync(id, options))

    @JvmOverloads
    fun byIds(ids: List<String>, options: ReadOptions = ReadOptions.DEFAULT): List<EntryBase> = join(byIdsAsync(ids, options))

    /**
     * Reads of one content type, as [type]. Delivery lists a type with the types that extend
     * it, and each comes back as its own class, a subclass of [type].
     */
    fun <T : Any, O : SortOrder> type(slug: String, type: Class<T>): TypeReader<T, O> = TypeReader(this, slug, type)

    /** Once per issue for the life of the client, so a page served a thousand times reports its broken entry once. */
    private class Issues(private val onIssue: Consumer<Issue>) : Consumer<Issue>
    {
        private val reported = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        override fun accept(issue: Issue)
        {
            if (reported.add("${issue.kind} ${issue.id} ${issue.field}"))
            {
                onIssue.accept(issue)
            }
        }
    }
}

/** Reads of one content type, as [T]. [O] is its sort order, its generated `SortBy`. */
class TypeReader<T : Any, O : SortOrder> internal constructor(
    private val content: ContentClient,
    private val slug: String,
    private val type: Class<T>,
)
{
    /** A slug is unique per ref, not per type, so a typed read checks what it found. */
    private fun of(entry: EntryBase?): T? = entry?.takeIf { type.isInstance(it) }?.let { type.cast(it) }

    /** The entry with this slug, or null if there is none or it is of another type. */
    @JvmOverloads
    fun bySlugAsync(slug: String, options: ReadOptions = ReadOptions.DEFAULT): CompletableFuture<T?> =
        content.bySlugAsync(slug, options).thenApply { of(it) }

    /** The entry with this id, or null if there is none or it is of another type. */
    @JvmOverloads
    fun byIdAsync(id: String, options: ReadOptions = ReadOptions.DEFAULT): CompletableFuture<T?> =
        content.byIdAsync(id, options).thenApply { of(it) }

    /** A page of the type's entries, its subtypes' included, in the order [options] asks for. */
    @JvmOverloads
    fun listAsync(options: ListOptions<O> = ListOptions()): CompletableFuture<Page<T>> =
        content.delivery.entriesAsync(
            content.config.ref,
            type = slug,
            order = options.order?.value,
            limit = options.limit,
            offset = options.offset,
            depth = options.depth,
            locale = content.locale(options.locale),
        ).thenApply { graph -> Page(content.all(graph).mapNotNull { of(it) }, graph.hasMore) }

    @JvmOverloads
    fun bySlug(slug: String, options: ReadOptions = ReadOptions.DEFAULT): T? = join(bySlugAsync(slug, options))

    @JvmOverloads
    fun byId(id: String, options: ReadOptions = ReadOptions.DEFAULT): T? = join(byIdAsync(id, options))

    @JvmOverloads
    fun list(options: ListOptions<O> = ListOptions()): Page<T> = join(listAsync(options))
}
