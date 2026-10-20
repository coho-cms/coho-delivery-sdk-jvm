package golden

import com.cohocms.delivery.ClientOptions
import com.cohocms.delivery.ContentConfig
import com.cohocms.delivery.EntryGraph
import com.cohocms.delivery.EntryFactory
import com.cohocms.delivery.ImageOptions
import com.cohocms.delivery.Linked
import com.cohocms.delivery.ListOptions
import com.cohocms.delivery.ReadOptions
import com.cohocms.delivery.ReleasedConfig
import com.cohocms.delivery.Replay
import com.cohocms.delivery.hydrate
import com.cohocms.delivery.resolved
import golden.deliveryfixture.coho.Article
import golden.deliveryfixture.coho.Citation
import golden.deliveryfixture.coho.Coho
import golden.deliveryfixture.coho.Figure
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
