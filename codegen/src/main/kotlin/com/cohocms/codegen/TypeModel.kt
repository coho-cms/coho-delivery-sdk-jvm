package com.cohocms.codegen

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * The type model: a language-neutral description of one ref's content types, produced by the
 * Coho server. See `type-model.schema.json` in coho-sdk-spec. Only what the generator reads is
 * here; keys it does not know are ignored, which is what lets a minor version add them.
 */
class TypeModel(
    val irVersion: String,
    val source: Source,
    /** Content types by slug. */
    val types: Map<String, ContentType>,
    /** Since 1.2: component types by slug, the items repeaters hold. */
    val components: Map<String, ContentType>,
    /** Since 1.4: traits by slug, named groups of fields that types take. Never types. */
    val traits: Map<String, ContentType>,
    val warnings: List<String>,
)
{
    class Source(val project: String, val ref: String, val immutable: Boolean, val schemaHash: String)

    companion object
    {
        /** The type model's major version this generator reads. */
        const val SUPPORTED_MAJOR = 1

        private val mapper = ObjectMapper()

        fun parse(text: String, from: String): TypeModel
        {
            val json = try
            {
                mapper.readTree(text)
            }
            catch (_: Exception)
            {
                throw CodegenException("$from is not JSON")
            }
            return read(json)
        }

        /**
         * Checks that [json] is a type model this generator can read. An unknown major version is
         * refused: the shape may mean something this generator cannot know.
         */
        fun read(json: JsonNode): TypeModel
        {
            if (!json.isObject)
            {
                throw CodegenException("The type model is not a JSON object")
            }
            val version = json.path("irVersion").asText("")
            if (!Regex("""\d+\.\d+""").matches(version))
            {
                throw CodegenException("The type model has no valid irVersion: ${json.get("irVersion")}")
            }
            if (version.substringBefore('.').toInt() != SUPPORTED_MAJOR)
            {
                throw CodegenException("The type model is version $version; this generator reads $SUPPORTED_MAJOR.x. Upgrade coho-codegen.")
            }
            val source = json.get("source")?.takeIf { it.isObject } ?: throw CodegenException("The type model has no source")
            if (json.get("types")?.isObject != true)
            {
                throw CodegenException("The type model has no types")
            }
            return TypeModel(
                irVersion = version,
                source = Source(
                    source.path("project").asText(),
                    source.path("ref").asText(),
                    source.path("immutable").asBoolean(false),
                    source.path("schemaHash").asText(),
                ),
                types = group(json, "types", "Type"),
                components = group(json, "components", "Component type"),
                traits = group(json, "traits", "Trait"),
                warnings = json.path("warnings").map { "${it.path("type").asText()}.${it.path("field").asText()}: ${it.path("message").asText()}" },
            )
        }

        private fun group(json: JsonNode, key: String, what: String): Map<String, ContentType>
        {
            val node = json.get(key) ?: return emptyMap()
            if (!node.isObject)
            {
                throw CodegenException("The type model's $key are not an object")
            }
            return node.properties().associate()
            { (slug, type) ->
                if (!type.path("fields").isArray)
                {
                    throw CodegenException("$what '$slug' in the type model has no fields")
                }
                slug to ContentType(
                    slug = type.path("slug").asText(slug),
                    name = type.path("name").asText(slug),
                    extends = type.get("extends")?.asText(),
                    abstract = type.path("abstract").asBoolean(false),
                    traits = type.path("traits").map { it.asText() },
                    fields = type.path("fields").map()
                    { field ->
                        Field(
                            id = field.path("id").asText(),
                            name = field.path("name").asText(),
                            kind = field.path("kind").asText(),
                            always = field.path("presence").asText() == "always",
                            localized = field.path("localized").asBoolean(false),
                            sortable = field.path("sortable").asBoolean(false),
                            many = field.path("many").asBoolean(false),
                            accept = field.path("accept").map { it.asText() },
                            of = field.path("of").map { it.asText() },
                            values = field.path("values").map { it.asText() },
                            textFormat = field.get("textFormat")?.asText(),
                            inherited = field.path("inherited").asBoolean(false),
                            trait = field.get("trait")?.asText(),
                        )
                    },
                )
            }
        }
    }
}

/** A content type, a component type, or a trait. Its [fields] already include what it inherits. */
class ContentType(
    val slug: String,
    val name: String,
    val extends: String?,
    val abstract: Boolean,
    val traits: List<String>,
    val fields: List<Field>,
)

/**
 * One field.
 *
 * @property kind Open: a kind added after this generator was written is read as JSON.
 * @property always Whether delivery always includes the field: the one rule for optionality.
 * @property inherited The field reaches the type exactly as its parent has it.
 * @property trait The trait the field comes from.
 */
class Field(
    val id: String,
    val name: String,
    val kind: String,
    val always: Boolean,
    val localized: Boolean,
    val sortable: Boolean,
    val many: Boolean,
    val accept: List<String>,
    val of: List<String>,
    val values: List<String>,
    val textFormat: String?,
    val inherited: Boolean,
    val trait: String?,
)

/** A type model, a configuration or a server answer the generator cannot use. */
class CodegenException(message: String) : RuntimeException(message)
