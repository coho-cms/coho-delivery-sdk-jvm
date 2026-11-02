package golden

import com.cohocms.delivery.Answering
import com.cohocms.delivery.ClientOptions
import com.cohocms.delivery.ContentConfig
import com.cohocms.delivery.EntryGraph
import com.cohocms.delivery.EntryFactory
import com.cohocms.delivery.Issue
import com.cohocms.delivery.ImageOptions
import com.cohocms.delivery.Linked
import com.cohocms.delivery.ListOptions
import com.cohocms.delivery.ReadOptions
import com.cohocms.delivery.ReleasedConfig
import com.cohocms.delivery.Replay
import com.cohocms.delivery.SingletonReader
import com.cohocms.delivery.Spec
import com.cohocms.delivery.hydrate
import com.cohocms.delivery.resolved
import golden.deliveryfixture.coho.Article
import golden.deliveryfixture.coho.Citation
import golden.deliveryfixture.coho.Coho
import golden.deliveryfixture.coho.Figure
import golden.deliveryfixture.coho.Note
import golden.deliveryfixture.coho.OutlineSection
import golden.deliveryfixture.coho.Point
import golden.deliveryfixture.coho.Step
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Kotlin the generator writes, read through: the committed code for the spec's fixtures, run
 * against the recordings, and written the way a Kotlin app writes it.
 */
class GeneratedKotlinTest
{
    private fun coho() = Coho(ContentConfig(ReleasedConfig("https://delivery.test", "p1", "test-key"), "v0.0.1"), ClientOptions(Replay()))

    @Test
    fun `a typed read returns the type's class, with its references followed`()
    {
        val post = coho().article.bySlug("chain-1", ReadOptions(depth = 2, locale = "en-US"))!!

        assertEquals("Chain 1", post.title)
        val second = post.related.resolved().single()
        assertEquals("absent", (second.related[1] as Linked.Unresolved).reason)
        val third = assertIs<Article>(second.related[0].value)
        assertEquals(Linked.Unresolved(post.id, "cycle"), third.related.single())
    }

    @Test
    fun `a list is ordered by a sortable field, descending`()
    {
        val page = coho().article.list(ListOptions(order = Article.SortBy.Rank.descending, limit = 3, locale = "en-US"))

        val ranks = page.items.map { it.rank ?: 0 }
        assertEquals(ranks.sortedDescending(), ranks)
        assertTrue(page.hasMore)
    }

    @Test
    fun `assets and repeater items are typed`()
    {
        val coho = coho()
        val figure = coho.figure.byId("00000000-0000-7000-8000-0000000000f1")!!
        assertTrue(figure.image!!.image(ImageOptions.fill(800, 400)).endsWith("fit=fill&fx=0.25&fy=0.75"))
        assertNull(figure.poster)

        val sources: List<Citation> = coho.figure.byId("00000000-0000-7000-8000-0000000000f2")!!.sources
        assertEquals(listOf(2022L, 2010L), sources.map { it.year })
    }

    @Test
    fun `lists and localized item fields read in the requested locale`()
    {
        val figure = coho().figure.byId("00000000-0000-7000-8000-0000000000f3", ReadOptions(locale = "de-DE"))!!

        val steps: List<Step> = figure.steps
        assertEquals(listOf("Messen", null), steps.map { it.label })
        // None in this locale, on purpose: an empty list, as delivery sent it.
        val keywords: List<String>? = figure.keywords
        assertEquals(emptyList(), keywords)
        val sizes: List<Long> = figure.sizes
        assertEquals(listOf(3L, 5L), sizes)
    }

    @Test
    fun `a localized list the locale has no translation of is null, and an unlocalized one empty`()
    {
        val json = Spec.body("entry-with-localized-items").deepCopy<com.fasterxml.jackson.databind.JsonNode>()
        val fields = json.path("entries").path("00000000-0000-7000-8000-0000000000f3").path("fields") as com.fasterxml.jackson.databind.node.ObjectNode
        fields.remove(listOf("keywords", "sizes"))

        val figure = assertIs<Figure>(hydrate(EntryGraph.of(json), Coho.FACTORIES) { }.single())
        assertNull(figure.keywords)
        assertEquals(emptyList(), figure.sizes)
    }

    @Test
    fun `items hold items, typed at every level, in the requested locale`()
    {
        val outline = coho().outline.byId("00000000-0000-7000-8000-0000000000b1", ReadOptions(locale = "en-US"))!!

        val section: OutlineSection = outline.sections.single()
        assertEquals("00000000-0000-7000-8000-0000000000b2" to "outlineSection", section.id to section.type)
        assertEquals("Plan", section.heading)
        val first: Point = section.points.single()
        val second: Point = first.children.single()
        val third: Point = second.children.single()
        // Only in pt-BR, so left out in en-US.
        assertEquals(listOf("Survey", "Measure", null), listOf(first.text, second.text, third.text))
        assertEquals(
            listOf("00000000-0000-7000-8000-0000000000b3", "00000000-0000-7000-8000-0000000000b4", "00000000-0000-7000-8000-0000000000b5"),
            listOf(first.id, second.id, third.id),
        )
        assertTrue(listOf(first, second, third).all { it.type == "point" })
        assertEquals(emptyList(), third.children)
        val notes: List<Note> = section.notes
        assertEquals(listOf("Bring cones"), notes.map { it.text })
    }

    @Test
    fun `a nested item of a component the code was not generated with is left out, and reported where it is`()
    {
        val json = Spec.body("entry-with-nested-items").deepCopy<com.fasterxml.jackson.databind.JsonNode>()
        val children = json.at("/entries/00000000-0000-7000-8000-0000000000b1/fields/sections/0/points/0/children") as com.fasterxml.jackson.databind.node.ArrayNode
        children.addObject().put("_id", "00000000-0000-7000-8000-0000000000b9").put("_type", "callout")

        val issues = mutableListOf<Issue>()
        val outline = assertIs<golden.deliveryfixture.coho.Outline>(hydrate(EntryGraph.of(json), Coho.FACTORIES) { issues += it }.single())
        val first = outline.sections.single().points.single()
        assertEquals(listOf("Measure"), first.children.map { it.text })
        assertEquals(listOf("unknown-item sections/0/points/0/children"), issues.map { "${it.kind} ${it.field}" })
    }

    @Test
    fun `a singleton is read by its one getter, at the slug the type model names`()
    {
        // The recordings have no singleton, so its entry is answered here.
        val answering = Answering(
            body = """{"roots":["s"],"entries":{
                "s":{"id":"s","slug":"site-settings","type":"siteSettings","fields":{"siteName":"Acme","home":"h"}},
                "h":{"id":"h","slug":"home-page","type":"homePage","fields":{"title":"Home","featured":[]}}},
                "unresolved":{}}""",
        )
        val coho = golden.singletons.coho.Coho(ContentConfig(ReleasedConfig("https://delivery.test", "p1", "test-key"), "v0.0.1"), ClientOptions(answering))

        val reader: SingletonReader<golden.singletons.coho.SiteSettings> = coho.siteSettings
        val settings = reader.get(ReadOptions(depth = 1))!!
        val siteName: String = settings.siteName
        assertEquals("Acme", siteName)
        // It extends page, so it is a Page too.
        val home: golden.singletons.coho.Page = assertIs<golden.singletons.coho.HomePage>(settings.home?.value)
        assertEquals("Home", home.title)
        assertEquals("https://delivery.test/v1/projects/p1/v0.0.1/entries-by-slug/site-settings?depth=1", answering.asked.first().url)
        // The entry at another singleton's slug is of another type, so it is not that singleton's.
        assertNull(coho.homePage.getAsync().join())
    }

    @Test
    fun `a singleton is null before its entry is first written`()
    {
        val coho = golden.singletons.coho.Coho(ContentConfig(ReleasedConfig("https://delivery.test", "p1", "test-key"), "v0.0.1"), ClientOptions(Answering()))
        assertNull(coho.siteSettings.get())
    }

    @Test
    fun `an untyped read is the entry's own class`()
    {
        assertIs<Article>(coho().bySlug("chain-1", ReadOptions(locale = "en-US")))
        assertIs<Figure>(coho().byIdAsync("00000000-0000-7000-8000-0000000000f1").join())
    }

    @Test
    fun `a family read keeps each entry as its own class, and a link to a base type holds a subtype`()
    {
        val json = com.fasterxml.jackson.databind.ObjectMapper().readTree(
            """{"roots":["l","s"],"entries":{
                "l":{"id":"l","slug":"home","type":"landingPage","fields":{"rank":1}},
                "s":{"id":"s","slug":"svc","type":"servicePage","fields":{"summary":"x","related":["l"]}}},
                "unresolved":{}}""",
        )
        val (landing, service) = hydrate(EntryGraph.of(json), golden.inheritance.coho.Coho.FACTORIES) { }

        assertIs<golden.inheritance.coho.LandingPage>(landing)
        val page: golden.inheritance.coho.Page = assertIs<golden.inheritance.coho.ServicePage>(service)
        assertIs<golden.inheritance.coho.LandingPage>(page.related.single().value)
    }

    @Test
    fun `an entry of any type with a trait is one`()
    {
        val seo: Class<*> = golden.traits.coho.Seo::class.java
        assertTrue(seo.isAssignableFrom(golden.traits.coho.Article::class.java))
        assertTrue(EntryFactory::class.java.isAssignableFrom(golden.traits.coho.Coho.FACTORIES.getValue("article").javaClass))
    }
}
