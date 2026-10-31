package com.cohocms.delivery

import java.util.function.Function
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Classes written as generated code writes them. */
open class Article(entry: EntryReader) : EntryBase(entry)
{
    val title: String? = entry.text("title")
    val rank: Long = entry.requiredInteger("rank")
    val related: List<Linked<Article>> = entry.links("related", Article::class.java)
}

class Citation(item: EntryReader) : ItemBase(item)
{
    val title: String = item.requiredText("title")
    val year: Long = item.requiredInteger("year")
}

class Step(item: EntryReader) : ItemBase(item)
{
    val label: String? = item.text("label")
    val minutes: Long? = item.integer("minutes")
}

class Figure(entry: EntryReader) : EntryBase(entry)
{
    val image: Asset? = entry.asset("image")
    val poster: Asset? = entry.asset("poster")
    val gallery: List<Asset> = entry.assets("gallery")
    val sources: List<Citation> = entry.items("sources", Citation::class.java)
    val steps: List<Step> = entry.items("steps", Step::class.java)
    val keywords: List<String>? = entry.textsOrNull("keywords")
    val sizes: List<Long> = entry.integers("sizes")
}

class Outline(entry: EntryReader) : EntryBase(entry)
{
    val sections: List<OutlineSection> = entry.items("sections", OutlineSection::class.java)
}

class OutlineSection(item: EntryReader) : ItemBase(item)
{
    val heading: String? = item.text("heading")
    val points: List<Point> = item.items("points", Point::class.java)
    val notes: List<Note> = item.items("notes", Note::class.java)
}

/** A component that holds itself. */
class Point(item: EntryReader) : ItemBase(item)
{
    val text: String? = item.text("text")
    val children: List<Point> = item.items("children", Point::class.java)
}

class Note(item: EntryReader) : ItemBase(item)
{
    val text: String? = item.text("text")
}

class HydrationTest
{
    private val factories = mapOf(
        "article" to EntryFactory(::Article),
        "figure" to EntryFactory(::Figure),
        "outline" to EntryFactory(::Outline),
        "citation" to EntryFactory(::Citation),
        "note" to EntryFactory(::Note),
        "outlineSection" to EntryFactory(::OutlineSection),
        "point" to EntryFactory(::Point),
        "step" to EntryFactory(::Step),
    )

    private val outline = "00000000-0000-7000-8000-0000000000b1"

    private val ids = listOf("c1", "c2", "c3", "ff").map { "00000000-0000-7000-8000-0000000000$it" }

    private fun graph(name: String) = EntryGraph.of(Spec.body(name))

    private fun hydrated(graph: EntryGraph, factories: Map<String, EntryFactory> = this.factories): Pair<List<EntryBase>, List<Issue>>
    {
        val issues = mutableListOf<Issue>()
        return hydrate(graph, factories) { issues += it } to issues
    }

    /** The graph with one entry's fields changed. */
    private fun edited(name: String, id: String, edit: (com.fasterxml.jackson.databind.node.ObjectNode) -> Unit): EntryGraph
    {
        val json = Spec.body(name).deepCopy<com.fasterxml.jackson.databind.JsonNode>()
        edit(json.path("entries").path(id).path("fields") as com.fasterxml.jackson.databind.node.ObjectNode)
        return EntryGraph.of(json)
    }

    @Test
    fun `references become their targets, or stubs that say why`()
    {
        val (roots, issues) = hydrated(graph("entry-by-slug-depth-1"))

        val root = assertIs<Article>(roots.single())
        val second = assertIs<Article>(root.related.single().value)
        assertEquals("Chain 2", second.title)
        assertEquals(listOf(Linked.Unresolved(ids[2], "depth"), Linked.Unresolved(ids[3], "absent")), second.related)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `a link back to an entry being built is a cycle stub`()
    {
        val root = assertIs<Article>(hydrated(graph("entry-by-slug-depth-2")).first.single())
        val third = root.related.resolved().single().related.resolved().single()
        assertEquals(listOf<Linked<Article>>(Linked.Unresolved(ids[0], "cycle")), third.related)
    }

    @Test
    fun `a target of a type the field does not link to is a type stub`()
    {
        val root = assertIs<Narrow>(hydrated(graph("entry-by-slug-depth-1"), factories + ("article" to EntryFactory(::Narrow))).first.single())
        assertEquals(listOf<Linked<Figure>>(Linked.Unresolved(ids[1], "type")), root.narrowed)
    }

    @Test
    fun `without factories, entries are untyped and keep their fields`()
    {
        val root = assertIs<HydratedEntry>(hydrated(graph("entry-by-slug-depth-1"), emptyMap()).first.single())
        assertEquals(ids[1], root.fields.path("related").path(0).asText())
    }

    @Test
    fun `a missing field the type always has takes its empty value, and is reported once`()
    {
        val (roots, issues) = hydrated(edited("entry-by-slug-depth-1", ids[0]) { it.remove("rank") })

        assertEquals(0L, assertIs<Article>(roots.single()).rank)
        assertEquals(listOf("missing-field chain-1 rank"), issues.map { "${it.kind} ${it.slug} ${it.field}" })
    }

    @Test
    fun `a value of the wrong kind is reported, and read as empty`()
    {
        val (roots, issues) = hydrated(edited("entry-by-slug-depth-1", ids[0]) { it.put("rank", "high") })

        assertEquals(0L, assertIs<Article>(roots.single()).rank)
        assertEquals(listOf("invalid-value"), issues.map { it.kind })
    }

    @Test
    fun `assets are embedded, and a gone one is null or left out`()
    {
        val figure = assertIs<Figure>(hydrated(graph("entry-with-assets")).first.single())

        val image = assertIs<ImageAsset>(figure.image)
        assertEquals(1600, image.width)
        assertTrue(image.image(ImageOptions.fill(800, 400)).endsWith("?w=800&h=400&fit=fill&fx=0.25&fy=0.75"))
        assertNull(figure.poster)
        assertEquals(listOf("image/jpeg", "image/svg+xml", "application/pdf"), figure.gallery.map { it.file?.mediaType })
    }

    @Test
    fun `repeater items are their components, and an unknown one is left out`()
    {
        val figure = assertIs<Figure>(hydrated(graph("entry-with-repeater")).first.single())
        assertEquals(listOf(2022L, 2010L), figure.sources.map { it.year })
        assertTrue(figure.sources.all { it.id.isNotEmpty() && it.type == "citation" })

        val (roots, issues) = hydrated(graph("entry-with-repeater"), factories - "citation")
        assertEquals(emptyList(), assertIs<Figure>(roots.single()).sources)
        assertEquals(setOf("unknown-item"), issues.map { it.kind }.toSet())
    }

    @Test
    fun `an item's localized field is its locale's value, or null where it has none`()
    {
        val (roots, issues) = hydrated(graph("entry-with-localized-items"))

        val figure = assertIs<Figure>(roots.single())
        assertEquals("de-DE", figure.locale)
        assertEquals(listOf("Messen", null), figure.steps.map { it.label })
        assertEquals(listOf(5L, 2L), figure.steps.map { it.minutes })
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `a list is its values, never a link, and a localized one is empty where the locale has none and null where it has no translation`()
    {
        val figure = assertIs<Figure>(hydrated(graph("entry-with-localized-items")).first.single())
        assertEquals(emptyList(), figure.keywords)
        assertEquals(listOf(3L, 5L), figure.sizes)

        val untranslated = hydrated(
            edited("entry-with-localized-items", "00000000-0000-7000-8000-0000000000f3")
            { fields ->
                fields.remove("keywords")
                fields.remove("sizes")
            },
        )
        val missing = assertIs<Figure>(untranslated.first.single())
        assertNull(missing.keywords)
        // Not localized: missing, it is empty, as a list of references is.
        assertEquals(emptyList(), missing.sizes)
        assertTrue(untranslated.second.isEmpty())

        // An entry's id in a list of text is text, and a value of another kind is left out.
        val (roots, issues) = hydrated(
            edited("entry-with-localized-items", "00000000-0000-7000-8000-0000000000f3")
            { fields ->
                fields.putArray("keywords").add(ids[0]).add(7)
                fields.putArray("sizes").add(3).add("five")
            },
        )
        val edited = assertIs<Figure>(roots.single())
        assertEquals(listOf(ids[0]), edited.keywords)
        assertEquals(listOf(3L), edited.sizes)
        assertEquals(listOf("invalid-value keywords", "invalid-value sizes"), issues.map { "${it.kind} ${it.field}" })
    }

    @Test
    fun `items hold items, built as their components at every level`()
    {
        val (roots, issues) = hydrated(graph("entry-with-nested-items"))

        val section = assertIs<Outline>(roots.single()).sections.single()
        assertEquals("Plan", section.heading)
        val first = section.points.single()
        val second = first.children.single()
        val third = second.children.single()
        assertEquals(listOf("Survey", "Measure", null), listOf(first.text, second.text, third.text))
        assertEquals(emptyList(), third.children)
        assertEquals(listOf("Bring cones"), section.notes.map { it.text })
        assertEquals("00000000-0000-7000-8000-0000000000b5" to "point", third.id to third.type)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `a nested item of an unknown component is left out where it is, and reported at its path`()
    {
        val (roots, issues) = hydrated(
            edited("entry-with-nested-items", outline)
            { fields ->
                val children = fields.path("sections").path(0).path("points").path(0).path("children") as com.fasterxml.jackson.databind.node.ArrayNode
                children.insertObject(0).put("_id", "00000000-0000-7000-8000-0000000000b9").put("_type", "callout").put("text", "Mind the gap")
                val deepest = children.path(1).path("children").path(0) as com.fasterxml.jackson.databind.node.ObjectNode
                deepest.putArray("children").addObject().put("_id", "00000000-0000-7000-8000-0000000000ba").put("_type", "callout")
            },
        )

        val first = assertIs<Outline>(roots.single()).sections.single().points.single()
        // The known sibling survives, with its own children, and the unknown items are gone.
        assertEquals(listOf("Measure"), first.children.map { it.text })
        assertEquals(emptyList(), first.children.single().children.single().children)
        assertEquals(
            listOf(
                "unknown-item sections/0/points/0/children",
                "unknown-item sections/0/points/0/children/1/children/0/children",
            ),
            issues.map { "${it.kind} ${it.field}" },
        )
        assertContains(issues[0].message, "an item of 'callout' in 'sections/0/points/0/children'")

        // A content type's own repeater still filters as before.
        val (top, topIssues) = hydrated(graph("entry-with-nested-items"), factories - "outlineSection")
        assertEquals(emptyList(), assertIs<Outline>(top.single()).sections)
        assertEquals(listOf("unknown-item sections"), topIssues.map { "${it.kind} ${it.field}" })
    }

    @Test
    fun `a value of the wrong kind in a nested item is reported at its full path`()
    {
        val (roots, issues) = hydrated(
            edited("entry-with-nested-items", outline)
            { fields ->
                (fields.path("sections").path(0).path("points").path(0).path("children").path(0) as com.fasterxml.jackson.databind.node.ObjectNode).put("text", 7)
            },
        )

        assertNull(assertIs<Outline>(roots.single()).sections.single().points.single().children.single().text)
        assertEquals(listOf("invalid-value sections/0/points/0/children/0/text"), issues.map { "${it.kind} ${it.field}" })
    }

    @Test
    fun `a typed reader reads its type, and reports each issue once for its life`()
    {
        val issues = mutableListOf<Issue>()
        val content = ContentClient(
            ContentConfig(ReleasedConfig("https://delivery.test", "p1", KEY), "v0.0.1"),
            factories,
            ClientOptions(Replay(), onIssue = { issues += it }),
        )
        val articles = content.type<Article, NoOrder>("article", Article::class.java)

        val page = articles.list(ListOptions(limit = 100, locale = "fr-FR"))
        assertTrue(page.items.isNotEmpty())
        assertTrue(page.items.all { it.locale == "fr-FR" })
        assertEquals("Chain 1", articles.bySlug("chain-1", ReadOptions(2, "en-US"))?.title)
        assertNull(articles.bySlug("nope"))
        // A slug is unique per ref, not per type: a typed read of another type is null.
        assertNull(content.type<Figure, NoOrder>("figure", Figure::class.java).bySlug("chain-1", ReadOptions(locale = "en-US")))
    }

    @Test
    fun `the environment chooses released content or a preview`()
    {
        fun from(vararg values: Pair<String, String>) = CohoEnvironment.from(Function { values.toMap()[it] })

        val released = from("COHO_DELIVERY_URL" to "https://delivery.test", "COHO_PROJECT" to "p1", "COHO_REF" to "prod", "COHO_LOCALE" to "en-US")
        assertEquals(ReleasedConfig("https://delivery.test", "p1"), released.delivery)
        assertEquals("prod" to "en-US", released.ref to released.locale)

        val token = "coho_pt_${"a".repeat(43)}"
        val preview = from("COHO_URL" to "https://coho.test", "COHO_ACCOUNT" to "acme", "COHO_PROJECT" to "p1", "COHO_REF" to "dev", "COHO_PREVIEW_TOKEN" to token)
        assertEquals(PreviewConfig("https://coho.test", "acme", "p1", token), preview.delivery)

        val both = runCatching { from("COHO_PREVIEW_TOKEN" to token, "COHO_DELIVERY_KEY" to "k") }.exceptionOrNull()
        assertTrue("not both" in both!!.message!!)
        val missing = runCatching { from("COHO_PROJECT" to "p1", "COHO_REF" to " ", "COHO_DELIVERY_KEY" to "") }.exceptionOrNull()
        assertEquals("Coho: COHO_DELIVERY_URL, COHO_REF are needed to read released content", missing!!.message)
    }
}

/** An article whose related posts may only be figures. */
class Narrow(entry: EntryReader) : Article(entry)
{
    val narrowed: List<Linked<Figure>> = entry.links("related", Figure::class.java)
}
