package com.cohocms.delivery

import com.fasterxml.jackson.databind.JsonNode
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Checks that a webhook request came from Coho, and reads its event.
 *
 * Coho signs each request as Standard Webhooks does: HMAC-SHA256 with the endpoint's secret
 * over `{webhook-id}.{webhook-timestamp}.{body}`, written `v1,…` in `webhook-signature`.
 * While a secret is being rotated, the header carries a signature for each.
 */
object Webhooks
{
    /** How far `webhook-timestamp` may be from the receiver's clock, either way: five minutes. */
    @JvmField
    val DEFAULT_TOLERANCE: Duration = Duration.ofSeconds(300)

    private const val SECRET_PREFIX = "whsec_"

    /**
     * The event a request carries, once its signature and timestamp are checked.
     *
     * @param body The body exactly as it arrived. Never parse and re-serialize it first: the
     *   signature is over the bytes Coho sent.
     * @param headers The request's headers. Names are matched ignoring case.
     * @param secret The endpoint's signing secret, `whsec_…`.
     * @param now The receiver's time. Pass one only in tests.
     * @param tolerance How far `webhook-timestamp` may be from [now], either way.
     * @throws CohoWebhookException when the request is refused; its [CohoWebhookException.reason] says why.
     * @throws IllegalArgumentException when a secret is not `whsec_` and base64, or a body
     *   that verifies is not a Coho event, which only a sender holding the secret could make.
     */
    @JvmStatic
    @JvmOverloads
    fun verify(
        body: ByteArray,
        headers: Map<String, String>,
        secret: String,
        now: Instant = Instant.now(),
        tolerance: Duration = DEFAULT_TOLERANCE,
    ): WebhookEvent = verify(body, headers, listOf(secret), now, tolerance)

    /** The same, accepting a request signed with any of [secrets]: both, while one is rotated. */
    @JvmStatic
    @JvmOverloads
    fun verify(
        body: ByteArray,
        headers: Map<String, String>,
        secrets: List<String>,
        now: Instant = Instant.now(),
        tolerance: Duration = DEFAULT_TOLERANCE,
    ): WebhookEvent
    {
        check(body, headers, secrets, now, tolerance)
        return WebhookEvent.of(body)
    }

    /** The same, for a body read as text. It is checked as its UTF-8 bytes. */
    @JvmStatic
    @JvmOverloads
    fun verify(
        body: String,
        headers: Map<String, String>,
        secret: String,
        now: Instant = Instant.now(),
        tolerance: Duration = DEFAULT_TOLERANCE,
    ): WebhookEvent = verify(body.toByteArray(Charsets.UTF_8), headers, listOf(secret), now, tolerance)

    /** The same, for a body read as text, signed with any of [secrets]. */
    @JvmStatic
    @JvmOverloads
    fun verify(
        body: String,
        headers: Map<String, String>,
        secrets: List<String>,
        now: Instant = Instant.now(),
        tolerance: Duration = DEFAULT_TOLERANCE,
    ): WebhookEvent = verify(body.toByteArray(Charsets.UTF_8), headers, secrets, now, tolerance)

    /** Refuses a request whose headers, timestamp, or signature do not check out; reads nothing. */
    internal fun check(body: ByteArray, headers: Map<String, String>, secrets: List<String>, now: Instant, tolerance: Duration)
    {
        require(secrets.isNotEmpty()) { "A webhook needs a secret to verify it with" }
        val keys = secrets.map { key(it) }

        val id = header(headers, "webhook-id")
        val timestamp = header(headers, "webhook-timestamp")
        val signature = header(headers, "webhook-signature")
        if (id == null || timestamp == null || signature == null)
        {
            val missing = listOf("webhook-id" to id, "webhook-timestamp" to timestamp, "webhook-signature" to signature)
                .filter { it.second == null }
                .map { it.first }
            throw CohoWebhookException(WebhookRefusal.HEADERS, "The request has no ${missing.joinToString(" or ")} header")
        }

        val sent = timestamp.toLongOrNull()
            ?: throw CohoWebhookException(WebhookRefusal.TIMESTAMP, "webhook-timestamp is not a Unix time: $timestamp")
        val clock = now.epochSecond
        val leeway = tolerance.seconds
        if (sent < clock - leeway || sent > clock + leeway)
        {
            throw CohoWebhookException(WebhookRefusal.TIMESTAMP, "webhook-timestamp is more than $leeway seconds from now, so the request may be a replay")
        }

        // Only v1 signatures: a later version, such as v1a, is for a receiver that knows it.
        val candidates = signature.split(' ').filter { it.startsWith("v1,") }.mapNotNull()
        { candidate ->
            try
            {
                Base64.getDecoder().decode(candidate.substring(3))
            }
            catch (_: IllegalArgumentException)
            {
                null
            }
        }
        val prefix = "$id.$timestamp.".toByteArray(Charsets.UTF_8)
        val verified = keys.any()
        { key ->
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(key)
            mac.update(prefix)
            val expected = mac.doFinal(body)
            candidates.any { MessageDigest.isEqual(expected, it) }
        }
        if (!verified)
        {
            throw CohoWebhookException(WebhookRefusal.SIGNATURE, "No v1 signature in webhook-signature matches the body with the secret given")
        }
    }

    private fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }

    private fun key(secret: String): SecretKeySpec
    {
        val encoded = secret.removePrefix(SECRET_PREFIX)
        val bytes = try
        {
            Base64.getDecoder().decode(encoded)
        }
        catch (e: IllegalArgumentException)
        {
            throw IllegalArgumentException("A webhook secret is $SECRET_PREFIX and a key in base64", e)
        }
        require(bytes.isNotEmpty()) { "A webhook secret is $SECRET_PREFIX and a key in base64" }
        return SecretKeySpec(bytes, "HmacSHA256")
    }
}

/**
 * Why a webhook request was refused: the spec's `signature`, `timestamp`, and `headers`.
 */
enum class WebhookRefusal
{
    /** No `v1` signature matches the body with any secret given. */
    SIGNATURE,

    /** `webhook-timestamp` is too far from the receiver's clock, or not a time. */
    TIMESTAMP,

    /** `webhook-id`, `webhook-timestamp`, or `webhook-signature` is missing. */
    HEADERS,
}

/**
 * A webhook request [Webhooks.verify] refused. Answer it with a `401`: Coho does not retry
 * one, and an owner can redeliver it once the receiver is fixed.
 */
class CohoWebhookException(val reason: WebhookRefusal, message: String) : RuntimeException(message)

/**
 * One event, as Coho sends it to a webhook.
 *
 * @property id The event's, the same on every attempt and redelivery, and the `webhook-id`
 *   header: drop an event already handled.
 * @property type Such as `environment.promoted`. Open, so a type added later still reads.
 * @property createdAt When the event happened.
 * @property actor Who made the change.
 * @property data The event's own values: identifiers and counts, never content.
 */
class WebhookEvent(
    val id: String,
    val type: String,
    val createdAt: Instant,
    val project: WebhookProject,
    val actor: WebhookActor,
    val data: JsonNode,
)
{
    override fun toString() = "WebhookEvent($type $id)"

    internal companion object
    {
        fun of(body: ByteArray): WebhookEvent
        {
            val json = try
            {
                DeliveryClient.MAPPER.readTree(body)
            }
            catch (e: java.io.IOException)
            {
                throw IllegalArgumentException("The webhook's body is not JSON", e)
            }
            fun required(node: JsonNode, name: String): String =
                node.get(name)?.takeIf { it.isTextual }?.asText() ?: throw IllegalArgumentException("The webhook's body is not a Coho event: it has no $name")

            val createdAt = try
            {
                Instant.parse(required(json, "created_at"))
            }
            catch (e: DateTimeParseException)
            {
                throw IllegalArgumentException("The webhook's created_at is not a time", e)
            }
            val project = json.path("project")
            val actor = json.path("actor")
            return WebhookEvent(
                id = required(json, "id"),
                type = required(json, "type"),
                createdAt = createdAt,
                project = WebhookProject(required(project, "id"), project.path("name").asText("")),
                actor = WebhookActor(
                    kind = required(actor, "kind"),
                    id = actor.get("id")?.takeIf { it.isTextual }?.asText(),
                    label = actor.get("label")?.takeIf { it.isTextual }?.asText(),
                ),
                data = json.path("data"),
            )
        }
    }
}

/** The project an event happened in. */
data class WebhookProject(val id: String, val name: String)

/**
 * Who made the change.
 *
 * @property kind `person`, `token`, or `system`. Open, so a kind added later still reads.
 * @property id The person's or the token's id. Null for `system`.
 * @property label A token's label. Null for anyone else.
 */
data class WebhookActor(val kind: String, val id: String?, val label: String?)
