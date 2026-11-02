package com.cohocms.delivery

import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The spec's webhook signatures, made by the sender's own code: what a receiver accepts and refuses. */
class WebhooksTest
{
    private val signatures = Spec.mapper.readTree(File(Spec.root, "webhooks/signatures.json"))
    private val cases = signatures.path("cases").toList()
    private val tolerance = Duration.ofSeconds(signatures.path("toleranceSeconds").asLong())

    private fun headers(case: JsonNode) = case.path("headers").properties().associate { (name, value) -> name to value.asText() }

    private fun named(name: String) = cases.single { it.path("name").asText() == name }

    @TestFactory
    fun `every case in the spec is accepted or refused as the sender says`() = cases.map()
    { case ->
        DynamicTest.dynamicTest(case.path("name").asText())
        {
            val body = case.path("body").asText()
            val now = Instant.ofEpochSecond(case.path("now").asLong())
            val secrets = listOf(case.path("secret").asText())
            if (case.path("valid").asBoolean())
            {
                Webhooks.check(body.toByteArray(), headers(case), secrets, now, tolerance)
            }
            else
            {
                val refused = assertFailsWith<CohoWebhookException> { Webhooks.check(body.toByteArray(), headers(case), secrets, now, tolerance) }
                assertEquals(case.path("reason").asText(), refused.reason.name.lowercase())
            }
        }
    }

    @Test
    fun `the spec's tolerance is the default`()
    {
        assertEquals(tolerance, Webhooks.DEFAULT_TOLERANCE)
    }

    @Test
    fun `a verified Coho event is read, typed`()
    {
        val case = named("coho-event")
        val event = Webhooks.verify(case.path("body").asText(), headers(case), case.path("secret").asText(), Instant.ofEpochSecond(case.path("now").asLong()))

        assertEquals("evt_01a19cf4b6c07fc4947ff43db9a8961a", event.id)
        assertEquals(case.path("headers").path("webhook-id").asText(), event.id)
        assertEquals("environment.promoted", event.type)
        assertEquals(Instant.parse("2026-11-02T14:03:11.204Z"), event.createdAt)
        assertEquals(WebhookProject("4f6c0b9e-2a51-4d7e-9f0a-6b1c2d3e4f50", "Café Zürich — site"), event.project)
        assertEquals(WebhookActor("person", "9b21c7e4-5d3f-4a2b-8c1d-0e9f8a7b6c5d", null), event.actor)
        assertEquals("v1.4.0", event.data.path("target").path("name").asText())
    }

    @Test
    fun `bytes and text verify alike, and the body's UTF-8 is what is signed`()
    {
        val case = named("coho-event")
        val now = Instant.ofEpochSecond(case.path("now").asLong())
        val bytes = case.path("body").asText().toByteArray(Charsets.UTF_8)

        assertEquals("Café Zürich — site", Webhooks.verify(bytes, headers(case), case.path("secret").asText(), now).project.name)
        val latin1 = String(bytes, Charsets.ISO_8859_1)
        val refused = assertFailsWith<CohoWebhookException> { Webhooks.verify(latin1, headers(case), case.path("secret").asText(), now) }
        assertEquals(WebhookRefusal.SIGNATURE, refused.reason)
    }

    @Test
    fun `any one of several secrets verifies`()
    {
        val case = named("rotation-current-secret")
        val now = Instant.ofEpochSecond(case.path("now").asLong())
        val current = case.path("secret").asText()
        val previous = named("rotation-previous-secret").path("secret").asText()
        val other = named("wrong-secret").path("secret").asText()

        for (secrets in listOf(listOf(current, previous), listOf(previous, current), listOf(other, previous), listOf(other, current)))
        {
            assertEquals("environment.promoted", Webhooks.verify(case.path("body").asText(), headers(case), secrets, now).type)
        }
        // A single signature, from the current secret, against the previous one and another.
        val single = named("coho-event")
        val refused = assertFailsWith<CohoWebhookException>()
        {
            Webhooks.verify(single.path("body").asText(), headers(single), listOf(previous, other), Instant.ofEpochSecond(single.path("now").asLong()))
        }
        assertEquals(WebhookRefusal.SIGNATURE, refused.reason)
    }

    @Test
    fun `header names are matched ignoring case`()
    {
        val case = named("coho-event")
        val now = Instant.ofEpochSecond(case.path("now").asLong())
        val secret = case.path("secret").asText()

        for (spelling in listOf<(String) -> String>({ it.uppercase() }, { it.split('-').joinToString("-") { part -> part.replaceFirstChar(Char::uppercase) } }))
        {
            val spelled = headers(case).mapKeys { spelling(it.key) }
            assertEquals("evt_01a19cf4b6c07fc4947ff43db9a8961a", Webhooks.verify(case.path("body").asText(), spelled, secret, now).id)
        }
    }

    @Test
    fun `the clock defaults to now, so a recorded request has long expired`()
    {
        val case = named("coho-event")
        val refused = assertFailsWith<CohoWebhookException> { Webhooks.verify(case.path("body").asText(), headers(case), case.path("secret").asText()) }
        assertEquals(WebhookRefusal.TIMESTAMP, refused.reason)
    }

    @Test
    fun `a tolerance can be narrowed`()
    {
        val case = named("tolerance-edge-past")
        val refused = assertFailsWith<CohoWebhookException>()
        {
            Webhooks.verify(case.path("body").asText(), headers(case), case.path("secret").asText(), Instant.ofEpochSecond(case.path("now").asLong()), Duration.ofSeconds(60))
        }
        assertEquals(WebhookRefusal.TIMESTAMP, refused.reason)
    }

    @Test
    fun `a timestamp that is not a number, and an empty header, are refused`()
    {
        val case = named("coho-event")
        val now = Instant.ofEpochSecond(case.path("now").asLong())
        val secret = case.path("secret").asText()

        val notANumber = headers(case) + ("webhook-timestamp" to "yesterday")
        assertEquals(WebhookRefusal.TIMESTAMP, assertFailsWith<CohoWebhookException> { Webhooks.verify(case.path("body").asText(), notANumber, secret, now) }.reason)
        val empty = headers(case) + ("webhook-id" to " ")
        assertEquals(WebhookRefusal.HEADERS, assertFailsWith<CohoWebhookException> { Webhooks.verify(case.path("body").asText(), empty, secret, now) }.reason)
    }

    @Test
    fun `a secret that is not whsec_ and base64 is the caller's mistake, not a refusal`()
    {
        val case = named("coho-event")
        val now = Instant.ofEpochSecond(case.path("now").asLong())
        assertFailsWith<IllegalArgumentException> { Webhooks.verify(case.path("body").asText(), headers(case), "whsec_not base64!", now) }
        assertFailsWith<IllegalArgumentException> { Webhooks.verify(case.path("body").asText(), headers(case), emptyList(), now) }
    }

    @Test
    fun `a body that verifies but is not a Coho event is not read as one`()
    {
        val case = named("standard-webhooks-example")
        assertFailsWith<IllegalArgumentException>()
        {
            Webhooks.verify(case.path("body").asText(), headers(case), case.path("secret").asText(), Instant.ofEpochSecond(case.path("now").asLong()))
        }
    }

    @Test
    fun `a system actor has no id`()
    {
        val event = WebhookEvent.of(
            """{"id":"evt_1","type":"project.updated","created_at":"2026-11-02T14:03:11Z","project":{"id":"p1","name":"Site"},"actor":{"kind":"system"},"data":{}}"""
                .toByteArray(),
        )
        assertEquals("system", event.actor.kind)
        assertNull(event.actor.id)
        assertNull(event.actor.label)
    }
}
