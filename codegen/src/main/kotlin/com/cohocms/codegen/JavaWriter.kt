package com.cohocms.codegen

import com.cohocms.codegen.Plan.Companion.constant
import com.cohocms.codegen.Plan.Companion.doc
import com.cohocms.codegen.Plan.Companion.imageOnly
import com.cohocms.codegen.Plan.Companion.literal

/**
 * Java: a class per type, with a field per Coho field read in its constructor and a getter for
 * each, `getPublishedAt()`. A type others extend is a class they extend; any other is final. A
 * trait is an interface of getters, which every type with it implements. Nullability is
 * declared with JSpecify's `@Nullable`, which Kotlin and IDEs read.
 */
internal class JavaWriter(private val plan: Plan, private val packageName: String) : Writer
{
    private val available = RUNTIME_TYPES + mapOf(
        "JsonNode" to "com.fasterxml.jackson.databind.JsonNode",
        "OffsetDateTime" to "java.time.OffsetDateTime",
        "List" to "java.util.List",
        "Map" to "java.util.Map",
        "CompletableFuture" to "java.util.concurrent.CompletableFuture",
        "Nullable" to "org.jspecify.annotations.Nullable",
    )

    private fun shape(owner: String, field: Field): Shape
    {
        val nullable = if (field.always) "" else "@Nullable "
        fun scalar(primitive: String, boxed: String, read: String) =
            if (field.always) Shape(primitive, "entry.required$read(${literal(field.id)})", false)
            else Shape("@Nullable $boxed", "entry.${read.replaceFirstChar { it.lowercase() }}(${literal(field.id)})", false)

        return when (field.kind)
        {
            "text", "longText", "choice" -> scalar("String", "String", "Text")
            "integer" -> scalar("long", "Long", "Integer")
            "number" -> scalar("double", "Double", "Number")
            "boolean" -> scalar("boolean", "Boolean", "Bool")
            "datetime" -> scalar("OffsetDateTime", "OffsetDateTime", "DateTime")
            "multichoice" -> Shape("List<String>", "entry.texts(${literal(field.id)})", true)
            "reference" ->
            {
                val target = plan.target(field.of, "EntryBase", "HydratedEntry")
                when
                {
                    field.many -> Shape("List<Linked<$target>>", "entry.links(${literal(field.id)}, $target.class)", true)
                    field.always -> Shape("Linked<$target>", "entry.requiredLink(${literal(field.id)}, $target.class)", false)
                    else -> Shape("${nullable}Linked<$target>", "entry.link(${literal(field.id)}, $target.class)", false)
                }
            }
            "asset" ->
            {
                // Embedded by delivery, never a link to follow. One gone on the ref is null, required
                // or not, so a single asset is always nullable; in a list it is left out.
                val (asset, single, many) = if (imageOnly(field)) Triple("ImageAsset", "imageAsset", "imageAssets") else Triple("Asset", "asset", "assets")
                if (field.many) Shape("List<$asset>", "entry.$many(${literal(field.id)})", true)
                else Shape("@Nullable $asset", "entry.$single(${literal(field.id)})", false)
            }
            "repeater" ->
            {
                // The runtime leaves out an item of any other component, so the list never holds one.
                val component = plan.target(field.of, "ItemBase", "")
                if (component.isEmpty()) Shape("List<RepeaterItem>", "entry.repeaterItems(${literal(field.id)})", true)
                else Shape("List<$component>", "entry.items(${literal(field.id)}, $component.class)", true)
            }
            "json" -> Shape("@Nullable JsonNode", "entry.json(${literal(field.id)})", false)
            else ->
            {
                plan.unknown(owner, field)
                Shape("@Nullable JsonNode", "entry.json(${literal(field.id)})", false)
            }
        }
    }

    private fun getter(property: String) = "get${property.replaceFirstChar { it.uppercase() }}"

    override fun type(type: ContentType, component: Boolean): GeneratedFile
    {
        val name = plan.classes.getValue(type.slug)
        val parent = plan.parent(type)?.let { plan.classes.getValue(it.slug) }
        val base = parent ?: if (component) "ItemBase" else "EntryBase"
        val inherited = plan.inheritedFrom(type)
        val own = type.fields.filter { it.id !in inherited }
        val properties = plan.properties(type.slug, type.fields)
        val traits = plan.traitsOf(type)
        val traitFields = type.traits.flatMap { plan.model.traits[it]?.fields.orEmpty() }.associateBy { it.id }
        val modifier = when
        {
            type.abstract -> "abstract "
            plan.isOpen(type.slug) -> ""
            else -> "final "
        }

        val body = StringBuilder()
        body.line(doc(type.name, plan.summary(type, component)))
        body.line("public ${modifier}class $name extends $base${if (traits.isEmpty()) "" else " implements ${traits.joinToString(", ")}"}")
        body.line("{")
        // A field from a trait has the trait's type here, so the getter overrides the trait's.
        val shapes = own.associate()
        { field ->
            val shape = shape(type.slug, field)
            val declared = traitFields[field.id]?.let { shape(type.slug, it).type } ?: shape.type
            field.id to Shape(declared, shape.read, shape.list)
        }
        for (field in own)
        {
            body.line("${INDENT}private final ${shapes.getValue(field.id).type} ${properties.getValue(field.id)};")
        }
        if (own.isNotEmpty())
        {
            body.line()
        }
        body.line("${INDENT}public $name(EntryReader entry)")
        body.line("$INDENT{")
        body.line("${INDENT}${INDENT}super(entry);")
        for (field in own)
        {
            body.line("${INDENT}${INDENT}this.${properties.getValue(field.id)} = ${shapes.getValue(field.id).read};")
        }
        body.line("$INDENT}")
        for (field in own)
        {
            val shape = shapes.getValue(field.id)
            body.line()
            body.line("$INDENT${doc(field.name, plan.note(field, shape.list))}")
            if (field.id in traitFields)
            {
                body.line("${INDENT}@Override")
            }
            body.line("${INDENT}public ${shape.type} ${getter(properties.getValue(field.id))}()")
            body.line("$INDENT{")
            body.line("${INDENT}${INDENT}return ${properties.getValue(field.id)};")
            body.line("$INDENT}")
        }
        sortBy(type, component)?.let()
        {
            body.line()
            body.append(it)
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
        out.line("$INDENT/** The fields a list of these can be ordered by, each ascending, or its {@link #descending()}. */")
        out.line("${INDENT}public static final class SortBy implements SortOrder")
        out.line("$INDENT{")
        for (field in sortable)
        {
            out.line("$INDENT$INDENT${doc(field.name, "Ascending.")}")
            out.line("$INDENT${INDENT}public static final SortBy ${constant(field.id)} = new SortBy(${literal(field.id)});")
            out.line()
        }
        out.line("$INDENT${INDENT}private final String value;")
        out.line()
        out.line("$INDENT${INDENT}private SortBy(String value)")
        out.line("$INDENT$INDENT{")
        out.line("$INDENT$INDENT${INDENT}this.value = value;")
        out.line("$INDENT$INDENT}")
        out.line()
        out.line("$INDENT$INDENT/** The field, prefixed {@code -} for descending. */")
        out.line("$INDENT$INDENT@Override")
        out.line("$INDENT${INDENT}public String getValue()")
        out.line("$INDENT$INDENT{")
        out.line("$INDENT$INDENT${INDENT}return value;")
        out.line("$INDENT$INDENT}")
        out.line()
        out.line("$INDENT$INDENT/** The same field, descending. */")
        out.line("$INDENT${INDENT}public SortBy descending()")
        out.line("$INDENT$INDENT{")
        out.line("$INDENT$INDENT${INDENT}return new SortBy(\"-\" + value.replaceFirst(\"^-\", \"\"));")
        out.line("$INDENT$INDENT}")
        out.line("$INDENT}")
        return out.toString()
    }

    override fun trait(trait: ContentType): GeneratedFile
    {
        val name = plan.classes.getValue(trait.slug)
        val properties = plan.properties(trait.slug, trait.fields)
        val body = StringBuilder()
        body.line(doc(trait.name, "A trait: fields every type with it has. Take it to accept an entry of any of them."))
        body.line("public interface $name")
        body.line("{")
        trait.fields.forEachIndexed()
        { index, field ->
            val shape = shape(trait.slug, field)
            if (index > 0)
            {
                body.line()
            }
            body.line("$INDENT${doc(field.name, plan.note(field, shape.list))}")
            body.line("$INDENT${shape.type} ${getter(properties.getValue(field.id))}();")
        }
        body.line("}")
        return file(name, body.toString())
    }

    override fun client(): GeneratedFile
    {
        val model = plan.model
        val types = model.types.values.sortedBy { it.slug }
        val body = StringBuilder()
        val source = model.source
        body.line("/**")
        body.line(" * Typed reads of every content type, on the ref and in the locale the config names. Each read")
        body.line(" * has a blocking form and an {@code Async} one. The credential comes from the app: this library")
        body.line(" * reads no environment.")
        body.line(" */")
        body.line("public final class Coho")
        body.line("{")
        body.line("$INDENT/** How to build each type, by slug. */")
        val factories = (types.filter { !it.abstract } + model.components.values.filter { !it.abstract }.sortedBy { it.slug })
            .map { "Map.entry(${literal(it.slug)}, (EntryFactory) ${plan.classes.getValue(it.slug)}::new)" }
        if (factories.isEmpty())
        {
            body.line("${INDENT}public static final Map<String, EntryFactory> FACTORIES = Map.of();")
        }
        else
        {
            body.line("${INDENT}public static final Map<String, EntryFactory> FACTORIES = Map.ofEntries(")
            body.line(factories.joinToString(",\n") { "$INDENT$INDENT$it" })
            body.line("$INDENT);")
        }
        body.line()
        body.line("$INDENT/** Where these types came from, for reporting which release a build was made from. */")
        body.line(
            "${INDENT}public static final Source SOURCE = new Source(${literal(source.project)}, ${literal(source.ref)}, ${source.immutable}, " +
                "${literal(source.schemaHash)}, ${literal(model.irVersion)});",
        )
        body.line()
        body.line("$INDENT/** The types an entry can be: every content type that is not abstract. */")
        body.line("${INDENT}public static final List<String> CONTENT_TYPES = List.of(${plan.concrete.joinToString(", ") { literal(it.slug) }});")
        body.line()
        body.line("${INDENT}private final ContentClient content;")
        for (type in types)
        {
            body.line("${INDENT}private final TypeReader<${plan.classes.getValue(type.slug)}, ${order(type)}> ${plan.readers.getValue(type.slug)};")
        }
        body.line()
        body.line("${INDENT}public Coho(ContentConfig config)")
        body.line("$INDENT{")
        body.line("$INDENT${INDENT}this(config, new ClientOptions());")
        body.line("$INDENT}")
        body.line()
        body.line("${INDENT}public Coho(ContentConfig config, ClientOptions options)")
        body.line("$INDENT{")
        body.line("$INDENT${INDENT}this.content = new ContentClient(config, FACTORIES, options);")
        for (type in types)
        {
            body.line("$INDENT${INDENT}this.${plan.readers.getValue(type.slug)} = content.type(${literal(type.slug)}, ${plan.classes.getValue(type.slug)}.class);")
        }
        body.line("$INDENT}")
        body.line()
        body.line("$INDENT/** The untyped reads underneath, for anything the typed ones do not cover. */")
        body.line("${INDENT}public ContentClient content()")
        body.line("$INDENT{")
        body.line("$INDENT${INDENT}return content;")
        body.line("$INDENT}")
        for (type in types)
        {
            val reader = plan.readers.getValue(type.slug)
            body.line()
            body.line("$INDENT${doc(type.name, if (plan.isOpen(type.slug)) "Its lists hold the types that extend it too, each as its own class." else "")}")
            body.line("${INDENT}public TypeReader<${plan.classes.getValue(type.slug)}, ${order(type)}> $reader()")
            body.line("$INDENT{")
            body.line("$INDENT${INDENT}return $reader;")
            body.line("$INDENT}")
        }
        for ((method, parameter, doc) in listOf(
            Triple("bySlug", "String slug", "The entry with this slug, whatever its type, or null."),
            Triple("byId", "String id", "The entry with this id, whatever its type, or null."),
        ))
        {
            val argument = parameter.substringAfter(' ')
            body.line()
            body.line("$INDENT/** $doc */")
            body.line("${INDENT}public @Nullable EntryBase $method($parameter)")
            body.line("$INDENT{")
            body.line("$INDENT${INDENT}return content.$method($argument, ReadOptions.DEFAULT);")
            body.line("$INDENT}")
            body.line()
            body.line("${INDENT}public @Nullable EntryBase $method($parameter, ReadOptions options)")
            body.line("$INDENT{")
            body.line("$INDENT${INDENT}return content.$method($argument, options);")
            body.line("$INDENT}")
            body.line()
            body.line("${INDENT}public CompletableFuture<@Nullable EntryBase> ${method}Async($parameter, ReadOptions options)")
            body.line("$INDENT{")
            body.line("$INDENT${INDENT}return content.${method}Async($argument, options);")
            body.line("$INDENT}")
        }
        body.line()
        body.line("$INDENT/** The entries that exist, sorted by id. Ids not on the ref are left out. */")
        body.line("${INDENT}public List<EntryBase> byIds(List<String> ids, ReadOptions options)")
        body.line("$INDENT{")
        body.line("$INDENT${INDENT}return content.byIds(ids, options);")
        body.line("$INDENT}")
        body.line("}")
        return file("Coho", body.toString())
    }

    private fun order(type: ContentType) =
        if (type.fields.any { it.sortable }) "${plan.classes.getValue(type.slug)}.SortBy" else "NoOrder"

    private fun file(name: String, body: String): GeneratedFile
    {
        val lines = mutableListOf(HEADER_LINE, "", "package $packageName;", "")
        val imported = imports(body, available)
        if (imported.isNotEmpty())
        {
            lines += imported.map { "import $it;" }
            lines += ""
        }
        return GeneratedFile("$name.java", lines.joinToString("\n") + "\n" + body)
    }

    private companion object
    {
        const val HEADER_LINE = "$GENERATED_MARK Do not edit: run ./gradlew cohoGenerate."
    }
}

internal fun StringBuilder.line(text: String = "")
{
    append(text).append('\n')
}

private const val INDENT = "    "
