package com.cohocms.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifying from Java, as a Spring controller does: bytes or text, one secret or several. */
class WebhooksJavaTest
{
    private static JsonNode coho() throws IOException
    {
        for (JsonNode c : Spec.INSTANCE.getMapper().readTree(new File(Spec.INSTANCE.getRoot(), "webhooks/signatures.json")).path("cases"))
        {
            if (c.path("name").asText().equals("rotation-current-secret"))
            {
                return c;
            }
        }
        throw new IllegalStateException("No rotation-current-secret case");
    }

    private static Map<String, String> headers(JsonNode c)
    {
        Map<String, String> headers = new LinkedHashMap<>();
        c.path("headers").properties().forEach(header -> headers.put(header.getKey(), header.getValue().asText()));
        return headers;
    }

    @Test
    void aJavaCallerVerifiesWithEachOverload() throws IOException
    {
        JsonNode c = coho();
        String body = c.path("body").asText();
        String secret = c.path("secret").asText();
        Instant now = Instant.ofEpochSecond(c.path("now").asLong());

        WebhookEvent event = Webhooks.verify(body.getBytes(StandardCharsets.UTF_8), headers(c), secret, now);
        assertEquals("environment.promoted", event.getType());
        assertEquals("person", event.getActor().getKind());
        assertEquals("prod", event.getData().path("environment").asText());

        assertEquals(event.getId(), Webhooks.verify(body, headers(c), List.of(secret), now, Webhooks.DEFAULT_TOLERANCE).getId());

        CohoWebhookException refused = assertThrows(CohoWebhookException.class, () -> Webhooks.verify(body, headers(c), secret, now.plus(Duration.ofHours(1))));
        assertEquals(WebhookRefusal.TIMESTAMP, refused.getReason());
        assertThrows(CohoWebhookException.class, () -> Webhooks.verify(body, headers(c), secret));
    }
}
