package com.cohocms.delivery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Resolving what a body points at (coho-data doc 80 ML-1), against the three shapes the portal
 * writes, and leaving everything else — a reference nothing can place included — exactly as it
 * was. The same cases as `@coho-cms/delivery`'s `prose.test.ts`, because the rule has to be one
 * rule.
 */
class ProseTest
{
    private fun asset(json: String) = Asset.of(Spec.mapper.readTree(json))

    private val bridge = asset("""{"id":"e1","slug":"bridge-at-dusk","url":"https://assets.test/a/h1/bridge.jpg"}""")
    private val tour = asset("""{"id":"e2","slug":"ring-road","url":"https://assets.test/a/h2/tour.mp4"}""")
    private val sheet = asset("""{"id":"e3","slug":"datasheet-2026","url":"https://assets.test/a/h3/d.pdf"}""")

    private val assets = listOf(bridge, tour, sheet)

    @Test
    fun `an image, a video directive and a download link each resolve`()
    {
        assertEquals("![A bridge](${bridge.url})", Prose.resolve("![A bridge](asset:bridge-at-dusk)", assets))
        assertEquals("""::video[The tour]{src="${tour.url}"}""", Prose.resolve("""::video[The tour]{src="asset:ring-road"}""", assets))
        assertEquals("[The datasheet](${sheet.url})", Prose.resolve("[The datasheet](asset:datasheet-2026)", assets))
    }

    @Test
    fun `a Markdown title survives, and an HTML attribute resolves too`()
    {
        assertEquals("""![A bridge](${bridge.url} "At dusk")""", Prose.resolve("""![A bridge](asset:bridge-at-dusk "At dusk")""", assets))
        assertEquals("""<img src="${bridge.url}">""", Prose.resolve("""<img src="asset:bridge-at-dusk">""", assets))
        assertEquals("<video poster='${bridge.url}'>", Prose.resolve("<video poster='asset:bridge-at-dusk'>", assets))
    }

    @Test
    fun `only an attribute that fetches, and only when the value is the whole reference`()
    {
        // `alt` fetches nothing, and a URL with the text inside it is not a reference.
        assertEquals("""<img alt="asset:bridge-at-dusk">""", Prose.resolve("""<img alt="asset:bridge-at-dusk">""", assets))
        assertEquals("[x](https://other.test/asset:bridge-at-dusk)", Prose.resolve("[x](https://other.test/asset:bridge-at-dusk)", assets))

        // Bare text is not a reference either, so a body can write about the scheme.
        assertEquals("Write asset:bridge-at-dusk to name it.", Prose.resolve("Write asset:bridge-at-dusk to name it.", assets))
    }

    @Test
    fun `a reference the entry does not carry is left exactly as written`()
    {
        assertEquals("![Not here](asset:not-here-yet)", Prose.resolve("![Not here](asset:not-here-yet)", assets))

        // An asset with no URL cannot be resolved to either.
        assertEquals("![A](asset:pending)", Prose.resolve("![A](asset:pending)", listOf(asset("""{"id":"e4","slug":"pending"}"""))))
    }

    @Test
    fun `an entry resolves against the assets it carries`()
    {
        val entry = prose("![A bridge](asset:bridge-at-dusk) and [the sheet](asset:datasheet-2026).", named = true)

        assertEquals(
            "![A bridge](${bridge.url}) and [the sheet](${sheet.url}).",
            Prose.resolve(entry.body, entry),
        )
    }

    @Test
    fun `an entry that names nothing leaves every reference standing`()
    {
        val entry = prose("![A bridge](asset:bridge-at-dusk)", named = false)

        assertEquals("![A bridge](asset:bridge-at-dusk)", Prose.resolve(entry.body, entry))
    }

    @Test
    fun `a body that is not there comes back as it was`()
    {
        assertNull(Prose.resolve(null, assets))
        assertEquals("", Prose.resolve("", assets))

        // Nothing to do is the same string back, not a rebuilt one.
        val plain = "Plain prose."
        assertSame(plain, Prose.resolve(plain, assets))
    }

    @Test
    fun `a reference reads back as the slug it names`()
    {
        assertEquals("asset:bridge-at-dusk", Prose.reference("bridge-at-dusk"))
        assertEquals("bridge-at-dusk", Prose.referencedSlug("asset:bridge-at-dusk"))
        assertNull(Prose.referencedSlug("https://assets.test/a/h1/bridge.jpg"))
        assertNull(Prose.referencedSlug("asset:"))
        assertNull(Prose.referencedSlug("asset:not a slug"))
        assertNull(Prose.referencedSlug(null))
    }

    /** An entry with a prose field, hydrated as delivery sends one: `_bodyAssets` among its fields. */
    private fun prose(body: String, named: Boolean): ProseEntry
    {
        val held = if (named) assets.joinToString(",") { Spec.mapper.writeValueAsString(it.toJson()) } else ""
        val json = Spec.mapper.readTree(
            """{"roots":["p1"],"entries":{"p1":{"id":"p1","slug":"a-bridge","type":"article","fields":{
                "body":${Spec.mapper.writeValueAsString(body)},"_bodyAssets":[$held]}}}}""",
        )
        val entries = hydrate(EntryGraph.of(json), mapOf("article" to EntryFactory { ProseEntry(it) })) {}
        return entries.single() as ProseEntry
    }

    /** What generated code declares for a type with a prose field. */
    private class ProseEntry(entry: EntryReader) : EntryBase(entry)
    {
        val body: String? = entry.text("body")
    }

    /** An asset back as delivery sent it, so a hand-built graph can carry one. */
    private fun Asset.toJson(): Map<String, Any?> = mapOf("id" to id, "slug" to slug, "url" to url)
}
