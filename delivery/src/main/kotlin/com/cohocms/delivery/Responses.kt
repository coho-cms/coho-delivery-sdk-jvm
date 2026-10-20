package com.cohocms.delivery

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * One entry, as delivery sends it: one locale's values, flattened.
 *
 * @property slug Unique on its ref.
 * @property type The content type's slug.
 * @property locale The locale this entry was projected for. Null when none was requested.
 * @property fields The author's fields. A localized field with no value in the requested
 *   locale is absent, not null. A reference holds an entry id, or an array of them.
 */
class Entry(
    val id: String,
    val slug: String,
    val type: String,
    val locale: String?,
    val fields: ObjectNode,
)

/**
 * What every read returns: the roots, everything reached from them, and what was not.
 *
 * @property roots The entries asked for that exist, in the order the read defines.
 * @property entries The roots and every entry reached from them, by id.
 * @property unresolved Why a requested or referenced entry is not in [entries]: `depth`
 *   beyond the requested depth, `absent` not on the ref. Open, so a reason the server adds
 *   later still reads.
 * @property warnings What the response could not give in full, such as an asset field whose
 *   asset is gone, which is then null, or left out of a list.
 * @property hasMore Whether a list has another page. False for a read that is not a list.
 * @property json The response as it arrived, for anything these properties leave out.
 */
class EntryGraph(
    val roots: List<String>,
    val entries: Map<String, Entry>,
    val unresolved: Map<String, String>,
    val warnings: List<DeliveryWarning>,
    val hasMore: Boolean,
    val json: JsonNode,
)
{
    companion object
    {
        @JvmField
        val NOTHING = EntryGraph(emptyList(), emptyMap(), emptyMap(), emptyList(), false, com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode())

        /** A graph from delivery's JSON, such as a response read elsewhere. */
        @JvmStatic
        fun of(json: JsonNode): EntryGraph
        {
            val entries = LinkedHashMap<String, Entry>()
            json.path("entries").properties().forEach()
            { (id, entry) ->
                entries[id] = Entry(
                    id = entry.path("id").asText(id),
                    slug = entry.path("slug").asText(""),
                    type = entry.path("type").asText(""),
                    locale = entry.get("locale")?.takeIf { it.isTextual }?.asText(),
                    fields = entry.get("fields") as? ObjectNode ?: com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode(),
                )
            }
            val unresolved = LinkedHashMap<String, String>()
            json.path("unresolved").properties().forEach { (id, reason) -> unresolved[id] = reason.asText() }
            return EntryGraph(
                roots = json.path("roots").map { it.asText() },
                entries = entries,
                unresolved = unresolved,
                warnings = json.path("warnings").map()
                { warning ->
                    DeliveryWarning(
                        warning.path("code").asText(),
                        warning.path("entry").asText(),
                        warning.path("field").asText(),
                        warning.path("asset").asText(),
                    )
                },
                hasMore = json.path("hasMore").asBoolean(false),
                json = json,
            )
        }
    }
}

/** One warning. [code] says what, the rest says where. Show an unknown code; never fail on one. */
data class DeliveryWarning(val code: String, val entry: String, val field: String, val asset: String)

/**
 * A refusal from the delivery API, or an answer the client could not read.
 *
 * Branch on [code], which is stable. The server's codes include `REF_NOT_FOUND`,
 * `KEY_REQUIRED`, `PROJECT_NOT_FOUND`, `INVALID_QUERY`, `RESOLUTION_LIMIT`,
 * `ARTIFACT_UNREADABLE` and `POOL_UNAVAILABLE`; the last two are the server's problem, not
 * the request's. A preview adds `UNAUTHENTICATED`, for a token that has expired or been
 * revoked, and `ACCOUNT_SUSPENDED`, `ACCOUNT_CLOSED` and `USER_SUSPENDED`. An answer that is
 * not a problem document, such as a proxy's error page, has the code `UNEXPECTED_RESPONSE`.
 *
 * @property problem The problem document as the server sent it, when there was one.
 */
class CohoDeliveryException(
    val status: Int,
    val code: String,
    message: String,
    val problem: JsonNode? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
