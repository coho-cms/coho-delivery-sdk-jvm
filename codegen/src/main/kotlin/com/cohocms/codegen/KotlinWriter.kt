package com.cohocms.codegen

import com.cohocms.codegen.Plan.Companion.imageOnly
import com.cohocms.codegen.Plan.Companion.literal

/**
 * Kotlin: a class per type, with a property per Coho field read in its constructor, typed
 * nullable where delivery can leave it out. A type others extend is an `open` class they
 * extend, overriding a field only where they tighten it; any other is final. A trait is an
 * interface of properties, which every type with it implements.
 */
internal class KotlinWriter(private val plan: Plan, private val packageName: String) : Writer
{
    private val available = RUNTIME_TYPES + mapOf(
        "JsonNode" to "com.fasterxml.jackson.databind.JsonNode",
        "OffsetDateTime" to "java.time.OffsetDateTime",
        "CompletableFuture" to "java.util.concurrent.CompletableFuture",
    )

    private fun shape(owner: String, field: Field): Shape
    {
        fun scalar(type: String, read: String) =
            if (field.always) Shape(type, "entry.required$read(${literal(field.id)})", false)
            else Shape("$type?", "entry.${read.replaceFirstChar { it.lowercase() }}(${literal(field.id)})", false)

        return when (field.kind)
        {
            "text", "longText", "choice" -> scalar("String", "Text")
            "integer" -> scalar("Long", "Integer")
            "number" -> scalar("Double", "Number")
            "boolean" -> scalar("Boolean", "Bool")
            "datetime" -> scalar("OffsetDateTime", "DateTime")
            "multichoice" -> Shape("List<String>", "entry.texts(${literal(field.id)})", true)
            "reference" ->
            {
                val target = plan.target(field.of, "EntryBase", "HydratedEntry")
                when
                {
                    field.many -> Shape("List<Linked<$target>>", "entry.links(${literal(field.id)}, $target::class.java)", true)
                    field.always -> Shape("Linked<$target>", "entry.requiredLink(${literal(field.id)}, $target::class.java)", false)
                    else -> Shape("Linked<$target>?", "entry.link(${literal(field.id)}, $target::class.java)", false)
                }
            }
            "asset" ->
            {
                // Embedded by delivery, never a link to follow. One gone on the ref is null, required
                // or not, so a single asset is always nullable; in a list it is left out.
                val (asset, single, many) = if (imageOnly(field)) Triple("ImageAsset", "imageAsset", "imageAssets") else Triple("Asset", "asset", "assets")
                if (field.many) Shape("List<$asset>", "entry.$many(${literal(field.id)})", true)
                else Shape("$asset?", "entry.$single(${literal(field.id)})", false)
            }
            "repeater" ->
            {
                // The runtime leaves out an item of any other component, so the list never holds one.
                val component = plan.target(field.of, "ItemBase", "")
                if (component.isEmpty()) Shape("List<RepeaterItem>", "entry.repeaterItems(${literal(field.id)})", true)
                else Shape("List<$component>", "entry.items(${literal(field.id)}, $component::class.java)", true)
            }
            "json" -> Shape("JsonNode?", "entry.json(${literal(field.id)})", false)
            else ->
            {
                plan.unknown(owner, field)
                Shape("JsonNode?", "entry.json(${literal(field.id)})", false)
            }
        }
    }

    override fun type(type: ContentType, component: Boolean): GeneratedFile
    {
        val name = plan.classes.getValue(type.slug)
        val parentType = plan.parent(type)
        val parent = parentType?.let { plan.classes.getValue(it.slug) }
        val base = parent ?: if (component) "ItemBase" else "EntryBase"
        val open = plan.isOpen(type.slug)
        val traits = plan.traitsOf(type)
        val traitFieldIds = type.traits.flatMap { plan.model.traits[it]?.fields.orEmpty() }.map { it.id }.toSet()
        val parentShapes = parentType?.fields?.associate { it.id to shape(parentType.slug, it).type }.orEmpty()
        val properties = plan.properties(type.slug, type.fields)
        val modifier = when
        {
            type.abstract -> "abstract "
            open -> "open "
            else -> ""
        }

        val body = StringBuilder()
        body.line(kdoc(type.name, plan.summary(type, component)))
        body.line("${modifier}class $name(entry: EntryReader) : $base(entry)${traits.joinToString("") { ", $it" }}")
        body.line("{")
        var first = true
        for (field in type.fields)
        {
            val shape = shape(type.slug, field)
            val inherited = parentShapes[field.id]
            // As its parent has it: inherited, with nothing to declare.
            if (inherited == shape.type)
            {
                continue
            }
            val keyword = when
            {
                inherited != null || field.id in traitFieldIds -> "override val"
                open -> "open val"
                else -> "val"
            }
            if (!first)
            {
                body.line()
            }
            first = false
            body.line("$INDENT${kdoc(field.name, plan.note(field, shape.list))}")
            body.line("$INDENT$keyword ${properties.getValue(field.id)}: ${shape.type} = ${shape.read}")
        }
        sortBy(type, component)?.let()
        {
            if (!first)
            {
                body.line()
            }
            first = false
            body.append(it)
        }
        if (first)
        {
            // Nothing of its own: a class with no body.
            body.setLength(body.length - "{\n".length)
            return file(name, body.toString())
        }
        body.line("}")
        return file(name, body.toString())
    }

    private fun sortBy(type: ContentType, component: Boolean): String?
    {
        val sortable = type.fields.filter { it.sortable }
        if (component || sortable.isEmpty())
        {
            return null
        }
        val out = StringBuilder()
        out.line("$INDENT/** The fields a list of these can be ordered by, each ascending, or its [descending]. */")
        out.line("${INDENT}class SortBy private constructor(override val value: String) : SortOrder")
        out.line("$INDENT{")
        out.line("$INDENT$INDENT/** The same field, descending. */")
        out.line("$INDENT${INDENT}val descending: SortBy get() = SortBy(\"-\" + value.trimStart('-'))")
        out.line()
        out.line("$INDENT${INDENT}companion object")
        out.line("$INDENT$INDENT{")
        sortable.forEachIndexed()
        { index, field ->
            if (index > 0)
            {
                out.line()
            }
            out.line("$INDENT$INDENT$INDENT${kdoc(field.name, "Ascending.")}")
            out.line("$INDENT$INDENT${INDENT}@JvmField")
            out.line("$INDENT$INDENT${INDENT}val ${field.id.replaceFirstChar { it.uppercase() }} = SortBy(${literal(field.id)})")
        }
        out.line("$INDENT$INDENT}")
        out.line("$INDENT}")
        return out.toString()
    }

    override fun trait(trait: ContentType): GeneratedFile
    {
        val name = plan.classes.getValue(trait.slug)
        val properties = plan.properties(trait.slug, trait.fields)
        val body = StringBuilder()
        body.line(kdoc(trait.name, "A trait: fields every type with it has. Take it to accept an entry of any of them."))
        body.line("interface $name")
        body.line("{")
        trait.fields.forEachIndexed()
        { index, field ->
            val shape = shape(trait.slug, field)
            if (index > 0)
            {
                body.line()
            }
            body.line("$INDENT${kdoc(field.name, plan.note(field, shape.list))}")
            body.line("${INDENT}val ${properties.getValue(field.id)}: ${shape.type}")
        }
        body.line("}")
        return file(name, body.toString())
    }

    override fun client(): GeneratedFile
    {
        val model = plan.model
        val types = model.types.values.sortedBy { it.slug }
        val source = model.source
        val body = StringBuilder()
        body.line("/**")
        body.line(" * Typed reads of every content type, on the ref and in the locale the config names. Each read")
        body.line(" * has a blocking form and an `Async` one; from a coroutine, `await()` it. The credential comes")
        body.line(" * from the app: this library reads no environment.")
        body.line(" */")
        body.line("class Coho @JvmOverloads constructor(config: ContentConfig, options: ClientOptions = ClientOptions())")
        body.line("{")
        body.line("$INDENT/** The untyped reads underneath, for anything the typed ones do not cover. */")
        body.line("${INDENT}val content = ContentClient(config, FACTORIES, options)")
        for (type in types)
        {
            val name = plan.classes.getValue(type.slug)
            body.line()
            body.line("$INDENT${kdoc(type.name, if (plan.isOpen(type.slug)) "Its lists hold the types that extend it too, each as its own class." else "")}")
            body.line("${INDENT}val ${plan.readers.getValue(type.slug)}: TypeReader<$name, ${order(type)}> = content.type(${literal(type.slug)}, $name::class.java)")
        }
        for ((method, parameter, doc) in listOf(
            Triple("bySlug", "slug", "The entry with this slug, whatever its type, or null."),
            Triple("byId", "id", "The entry with this id, whatever its type, or null."),
        ))
        {
            body.line()
            body.line("$INDENT/** $doc */")
            body.line("$INDENT@JvmOverloads")
            body.line("${INDENT}fun $method($parameter: String, options: ReadOptions = ReadOptions.DEFAULT): EntryBase? = content.$method($parameter, options)")
            body.line()
            body.line("$INDENT@JvmOverloads")
            body.line(
                "${INDENT}fun ${method}Async($parameter: String, options: ReadOptions = ReadOptions.DEFAULT): CompletableFuture<EntryBase?> =\n" +
                    "$INDENT${INDENT}content.${method}Async($parameter, options)",
            )
        }
        body.line()
        body.line("$INDENT/** The entries that exist, sorted by id. Ids not on the ref are left out. */")
        body.line("$INDENT@JvmOverloads")
        body.line("${INDENT}fun byIds(ids: List<String>, options: ReadOptions = ReadOptions.DEFAULT): List<EntryBase> = content.byIds(ids, options)")
        body.line()
        body.line("${INDENT}companion object")
        body.line("$INDENT{")
        body.line("$INDENT$INDENT/** How to build each type, by slug. */")
        val factories = (types.filter { !it.abstract } + model.components.values.filter { !it.abstract }.sortedBy { it.slug })
            .map { "${literal(it.slug)} to EntryFactory(::${plan.classes.getValue(it.slug)})" }
        body.line("$INDENT$INDENT@JvmField")
        if (factories.isEmpty())
        {
            body.line("$INDENT${INDENT}val FACTORIES: Map<String, EntryFactory> = emptyMap()")
        }
        else
        {
            body.line("$INDENT${INDENT}val FACTORIES: Map<String, EntryFactory> = mapOf(")
            factories.forEach { body.line("$INDENT$INDENT$INDENT$it,") }
            body.line("$INDENT$INDENT)")
        }
        body.line()
        body.line("$INDENT$INDENT/** Where these types came from, for reporting which release a build was made from. */")
        body.line("$INDENT$INDENT@JvmField")
        body.line(
            "$INDENT${INDENT}val SOURCE = Source(${literal(source.project)}, ${literal(source.ref)}, ${source.immutable}, " +
                "${literal(source.schemaHash)}, ${literal(model.irVersion)})",
        )
        body.line()
        body.line("$INDENT$INDENT/** The types an entry can be: every content type that is not abstract. */")
        body.line("$INDENT$INDENT@JvmField")
        body.line("$INDENT${INDENT}val CONTENT_TYPES: List<String> = listOf(${plan.concrete.joinToString(", ") { literal(it.slug) }})")
        body.line("$INDENT}")
        body.line("}")
        return file("Coho", body.toString())
    }

    private fun order(type: ContentType) =
        if (type.fields.any { it.sortable }) "${plan.classes.getValue(type.slug)}.SortBy" else "NoOrder"

    private fun file(name: String, body: String): GeneratedFile
    {
        val lines = mutableListOf("$GENERATED_MARK Do not edit: run ./gradlew cohoGenerate.", "", "package $packageName", "")
        val imported = imports(body, available)
        if (imported.isNotEmpty())
        {
            lines += imported.map { "import $it" }
            lines += ""
        }
        return GeneratedFile("$name.kt", lines.joinToString("\n") + "\n" + body)
    }
}

private const val INDENT = "    "

private fun kdoc(name: String, note: String = "") = Plan.doc(name, note, nested = true)
