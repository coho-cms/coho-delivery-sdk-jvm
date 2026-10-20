package com.cohocms.delivery

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.function.Consumer

/**
 * The graph's roots, in order, built by [factories], with each reference replaced by its
 * target or a stub.
 *
 * Each root gets its own pass, so its tree holds everything reachable from it. Within a pass
 * an entry is built once and shared, and a link back to an entry still being built becomes a
 * `cycle` stub, so the result has no cycles. Each entry is built as its own type, so a link to
 * a base type holds a subtype.
 */
fun hydrate(graph: EntryGraph, factories: Map<String, EntryFactory>, onIssue: Consumer<Issue>): List<EntryBase> =
    Hydration(graph, factories, onIssue).roots()

internal class Hydration(
    val graph: EntryGraph,
    val factories: Map<String, EntryFactory>,
    private val onIssue: Consumer<Issue>,
)
{
    private val reported = HashSet<String>()

    fun report(kind: String, entry: Entry, field: String, what: String)
    {
        if (reported.add("$kind ${entry.id} $field"))
        {
            onIssue.accept(Issue(kind, entry.id, entry.slug, entry.type, field, "${entry.type} '${entry.slug}' $what"))
        }
    }

    fun roots(): List<EntryBase> = graph.roots.mapNotNull()
    { id ->
        graph.entries[id]?.let { Pass().build(it) as? EntryBase }
    }

    /** One root's tree. */
    inner class Pass
    {
        val hydration: Hydration get() = this@Hydration

        private val built = HashMap<String, Any>()
        private val building = HashSet<String>()

        /** The entry as its type's class, or a stub if it could not be built. */
        fun build(entry: Entry): Any
        {
            built[entry.id]?.let { return it }
            building.add(entry.id)
            val reader = EntryReader(this, entry, entry.id, entry.type, entry.slug, entry.locale, entry.fields, "")
            val result = try
            {
                (factories[entry.type]?.create(reader) ?: HydratedEntry(reader)) as? EntryBase
                    ?: Linked.Unresolved(entry.id, "type")
            }
            catch (error: RuntimeException)
            {
                report("invalid-value", entry, "", "could not be read, and is left out: $error")
                Linked.Unresolved(entry.id, "invalid")
            }
            building.remove(entry.id)
            built[entry.id] = result
            return result
        }

        fun <T : Any> resolve(from: Entry, field: String, id: String, type: Class<T>): Linked<T>
        {
            if (id in building)
            {
                return Linked.Unresolved(id, "cycle")
            }
            val target = built[id] ?: graph.entries[id]?.let { build(it) }
            if (target == null)
            {
                val reason = graph.unresolved[id]
                if (reason == null)
                {
                    report("unlisted-link", from, field, "links '$field' to $id, which delivery neither returned nor reported")
                }
                return Linked.Unresolved(id, reason ?: "absent")
            }
            return when
            {
                target is Linked.Unresolved -> target
                type.isInstance(target) -> Linked.Resolved(type.cast(target))
                else -> Linked.Unresolved(id, "type")
            }
        }

        fun item(from: Entry, field: String, index: Int, node: ObjectNode): Any?
        {
            val component = node.path("_type").asText("")
            val factory = factories[component] ?: return null
            val reader = EntryReader(this, from, node.path("_id").asText(""), component, "", null, node, "$field/$index/")
            return try
            {
                factory.create(reader)
            }
            catch (error: RuntimeException)
            {
                report("invalid-value", from, "$field/$index", "has an item in '$field' that could not be read, and is left out: $error")
                null
            }
        }
    }
}

/**
 * One entry's, or one repeater item's, values, as its generated constructor reads them: each
 * field by its id, typed. A reader never throws. A value of the wrong kind is reported and
 * read as null, and a field the type always has, when missing, as its type's empty value.
 *
 * @property raw The values as delivery sent them, with references still ids.
 */
class EntryReader internal constructor(
    private val pass: Hydration.Pass,
    private val owner: Entry,
    val id: String,
    val type: String,
    val slug: String,
    val locale: String?,
    val raw: ObjectNode,
    private val prefix: String,
)
{
    private fun report(kind: String, field: String, what: String) = pass.hydration.report(kind, owner, "$prefix$field", what)

    private fun value(field: String): JsonNode? = raw.get(field)?.takeUnless { it.isNull }

    private fun <T> required(field: String, read: T?, empty: T): T
    {
        if (read != null)
        {
            return read
        }
        if (!raw.has(field))
        {
            report("missing-field", field, "has no '$field', which its type requires")
        }
        return empty
    }

    private fun wrong(field: String, kind: String): Nothing?
    {
        report("invalid-value", field, "has a value in '$field' that is not $kind")
        return null
    }

    /* SCALARS */
    /* SCALARS */
    /* SCALARS */

    /** Text, long text and choices. */
    fun text(field: String): String?
    {
        val node = value(field) ?: return null
        return if (node.isTextual) node.asText() else wrong(field, "text")
    }

    fun requiredText(field: String): String = required(field, text(field), "")

    fun integer(field: String): Long?
    {
        val node = value(field) ?: return null
        return if (node.isIntegralNumber && node.canConvertToLong()) node.asLong() else wrong(field, "a whole number")
    }

    fun requiredInteger(field: String): Long = required(field, integer(field), 0L)

    fun number(field: String): Double?
    {
        val node = value(field) ?: return null
        return if (node.isNumber) node.asDouble() else wrong(field, "a number")
    }

    fun requiredNumber(field: String): Double = required(field, number(field), 0.0)

    fun bool(field: String): Boolean?
    {
        val node = value(field) ?: return null
        return if (node.isBoolean) node.asBoolean() else wrong(field, "true or false")
    }

    fun requiredBool(field: String): Boolean = required(field, bool(field), false)

    /** A date and time, with its offset. */
    fun dateTime(field: String): OffsetDateTime?
    {
        val node = value(field) ?: return null
        if (!node.isTextual)
        {
            return wrong(field, "a date and time")
        }
        return try
        {
            OffsetDateTime.parse(node.asText())
        }
        catch (_: DateTimeParseException)
        {
            wrong(field, "a date and time")
        }
    }

    /** A date and time; when missing, the epoch. */
    fun requiredDateTime(field: String): OffsetDateTime = required(field, dateTime(field), EPOCH)

    /** A JSON field, as it was stored. */
    fun json(field: String): JsonNode? = value(field)

    /** A multichoice: each value chosen. */
    fun texts(field: String): List<String>
    {
        val node = value(field) ?: return emptyList()
        if (!node.isArray)
        {
            wrong(field, "a list")
            return emptyList()
        }
        return node.mapNotNull { if (it.isTextual) it.asText() else wrong(field, "text") }
    }

    /* REFERENCES */
    /* REFERENCES */
    /* REFERENCES */

    /** One reference: its target, as [type] or one of its subtypes, or a stub; or null when there is none. */
    fun <T : Any> link(field: String, type: Class<T>): Linked<T>?
    {
        val node = value(field) ?: return null
        if (node.isArray)
        {
            report("unexpected-value", field, "has several links in '$field', where its type has one")
            return null
        }
        if (!node.isTextual)
        {
            return wrong(field, "a link")
        }
        return pass.resolve(owner, field, node.asText(), type)
    }

    /** A reference the type always has: when missing, an `absent` stub. */
    fun <T : Any> requiredLink(field: String, type: Class<T>): Linked<T> =
        required(field, link(field, type), Linked.Unresolved("", "absent"))

    /** A list of references, each its target or a stub. */
    fun <T : Any> links(field: String, type: Class<T>): List<Linked<T>>
    {
        val node = value(field) ?: return emptyList()
        if (!node.isArray)
        {
            report("unexpected-value", field, "has one link in '$field', where its type has several")
            return emptyList()
        }
        return node.mapNotNull { if (it.isTextual) pass.resolve(owner, field, it.asText(), type) else wrong(field, "a link") }
    }

    /* ASSETS */
    /* ASSETS */
    /* ASSETS */

    /** One asset, embedded by delivery; null when there is none, or it is gone on the ref. */
    fun asset(field: String): Asset?
    {
        val node = value(field) ?: return null
        return if (node.isObject) Asset.of(node) else wrong(field, "an asset")
    }

    /** One asset from a field that accepts only raster images, which always have dimensions. */
    fun imageAsset(field: String): ImageAsset? = asset(field)?.let { it as? ImageAsset ?: wrong(field, "a raster image") }

    /** A list of assets. One gone on the ref is left out by delivery. */
    fun assets(field: String): List<Asset>
    {
        val node = value(field) ?: return emptyList()
        if (!node.isArray)
        {
            wrong(field, "a list")
            return emptyList()
        }
        return node.mapNotNull { if (it.isObject) Asset.of(it) else wrong(field, "an asset") }
    }

    fun imageAssets(field: String): List<ImageAsset> =
        assets(field).mapNotNull { it as? ImageAsset ?: wrong(field, "a raster image") }

    /* REPEATERS */
    /* REPEATERS */
    /* REPEATERS */

    /**
     * A repeater's items, each built as its component type. An item of a component this code
     * has no type for, or one that is not a [type], is left out and reported, so it is never
     * passed off as something it is not.
     */
    fun <T : Any> items(field: String, type: Class<T>): List<T>
    {
        val node = value(field) ?: return emptyList()
        if (!node.isArray)
        {
            wrong(field, "a list")
            return emptyList()
        }
        return node.mapIndexedNotNull()
        { index, item ->
            val built = (item as? ObjectNode)?.let { pass.item(owner, field, index, it) }
            if (built != null && type.isInstance(built))
            {
                type.cast(built)
            }
            else
            {
                val component = item.path("_type").asText("")
                report("unknown-item", field, "has an item of '$component' in '$field', which this code has no type for; it is left out")
                null
            }
        }
    }

    /** A repeater whose components are not on the ref: its items, untyped. */
    fun repeaterItems(field: String): List<RepeaterItem>
    {
        val node = value(field) ?: return emptyList()
        return node.filterIsInstance<ObjectNode>().map()
        { item ->
            RepeaterItem(EntryReader(pass, owner, item.path("_id").asText(""), item.path("_type").asText(""), "", null, item, "$prefix$field/"))
        }
    }

    internal companion object
    {
        val EPOCH: OffsetDateTime = Instant.EPOCH.atOffset(ZoneOffset.UTC)

        val EMPTY: ObjectNode = JsonNodeFactory.instance.objectNode()
    }
}
