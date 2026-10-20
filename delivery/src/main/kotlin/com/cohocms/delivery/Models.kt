package com.cohocms.delivery

import com.fasterxml.jackson.databind.JsonNode

/**
 * The system values on every entry. Generated classes derive from it, each reading its own
 * fields from the [EntryReader] it is built with.
 *
 * @property type The content type's slug.
 * @property locale The locale the entry was projected for. Null when none was requested.
 */
abstract class EntryBase protected constructor(entry: EntryReader)
{
    val id: String = entry.id
    val type: String = entry.type
    val slug: String = entry.slug
    val locale: String? = entry.locale

    override fun toString() = "${javaClass.simpleName}($type '$slug')"
}

/**
 * The system values on every repeater item: its identity, stable across edits and merges,
 * and the component type it is.
 */
abstract class ItemBase protected constructor(item: EntryReader)
{
    val id: String = item.id
    val type: String = item.type

    override fun toString() = "${javaClass.simpleName}($type $id)"
}

/** An entry of a type no generated code declares: its fields are kept, untyped. */
class HydratedEntry(entry: EntryReader) : EntryBase(entry)
{
    /** The fields as delivery sent them, with references still ids. */
    val fields: JsonNode = entry.raw
}

/** An item of a repeater whose component types are not on the ref, so its fields are untyped. */
class RepeaterItem(item: EntryReader) : ItemBase(item)
{
    val fields: JsonNode = item.raw
}

/** Builds an entry or an item of one type: what generated code registers for each, `BlogPost::new`. */
fun interface EntryFactory
{
    fun create(entry: EntryReader): Any
}

/**
 * A reference: its target, or a stub saying why the target is not here. [value] is the
 * target, or null for a stub.
 */
sealed class Linked<out T : Any>
{
    abstract val id: String
    abstract val value: T?

    val isResolved: Boolean get() = this is Resolved

    /** The target. */
    class Resolved<out T : Any>(override val value: T) : Linked<T>()
    {
        override val id: String get() = (value as? EntryBase)?.id ?: ""

        override fun toString() = "Resolved($value)"
    }

    /**
     * Where the target would be, and why it is not: `depth` beyond the requested depth,
     * `absent` not on the ref, `cycle` where following it would close a loop, `type` where the
     * target is of a type the field does not link to, and `invalid` where it could not be read.
     */
    class Unresolved(override val id: String, val reason: String) : Linked<Nothing>()
    {
        override val value: Nothing? get() = null

        override fun equals(other: Any?) = other is Unresolved && other.id == id && other.reason == reason

        override fun hashCode() = id.hashCode() * 31 + reason.hashCode()

        override fun toString() = "Unresolved($id, $reason)"
    }

    companion object
    {
        /** The targets of a list of references that are here, leaving out the stubs. */
        @JvmStatic
        fun <T : Any> resolved(links: List<Linked<T>>): List<T> = links.mapNotNull { it.value }
    }
}

/** The targets of a list of references that are here, leaving out the stubs. */
fun <T : Any> List<Linked<T>>.resolved(): List<T> = mapNotNull { it.value }

/** A field a list can be ordered by, prefixed `-` for descending. Generated as each type's `SortBy`. */
interface SortOrder
{
    val value: String
}

/** The order of a type with no sortable fields: there is none to give. */
class NoOrder private constructor(override val value: String) : SortOrder

/** A page of entries. */
class Page<out T>(val items: List<T>, val hasMore: Boolean)

/** A list's order, page and depth. */
data class ListOptions<O : SortOrder> @JvmOverloads constructor(
    /** A sortable field of the type, or its descending form. */
    val order: O? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    /** How many reference hops to follow from each entry, 0 to 5. */
    val depth: Int? = null,
    /** The locale to project to. Without one, the client's own, or none. */
    val locale: String? = null,
)
{
    fun withOrder(order: O) = copy(order = order)

    fun withLimit(limit: Int) = copy(limit = limit)

    fun withOffset(offset: Int) = copy(offset = offset)

    fun withDepth(depth: Int) = copy(depth = depth)

    fun withLocale(locale: String) = copy(locale = locale)
}

/**
 * Something about an entry that its type says cannot happen. Reported, never thrown.
 *
 * `missing-field`: a field the type always has is absent, as old entries are after a field
 * becomes required; it takes its type's empty value. `unexpected-value`: one link where the
 * type says many, or the other way round. `unlisted-link`: a link delivery neither resolved
 * nor reported. `unknown-item`: a repeater item of a component the code has no type for, left
 * out. `invalid-value`: a value of the wrong kind, such as text where a number belongs, read
 * as null or its type's empty value.
 */
data class Issue(
    val kind: String,
    val id: String,
    val slug: String,
    val type: String,
    val field: String,
    val message: String,
)

/**
 * Where a generated library's types came from, for reporting which release a build was made from.
 *
 * @property immutable True for a tag, whose types can never change.
 */
data class Source(
    val project: String,
    val ref: String,
    val immutable: Boolean,
    val schemaHash: String,
    val irVersion: String,
)
