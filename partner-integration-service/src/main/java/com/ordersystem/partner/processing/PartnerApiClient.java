package com.ordersystem.partner.processing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.partner.PartnerTask;
import com.ordersystem.partner.Tracer;
import com.ordersystem.partner.config.PartnerSettings;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Sends one order operation to the seller's API. The eventId is the idempotency key, so a retry
 * after a lost response cannot create a second effect at the partner.
 */
@Component
public class PartnerApiClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json;
    private final PartnerSettings settings;
    private final Tracer tracer;

    public PartnerApiClient(ObjectMapper json, PartnerSettings settings, Tracer tracer) {
        this.json = json;
        this.settings = settings;
        this.tracer = tracer;
    }

    /** Returns the call duration in nanoseconds; throws when the partner did not accept it. */
    public long submit(PartnerTask task) throws Exception {
        var event = task.event();
        long start = System.nanoTime();
        tracer.trace("external_start", task, "attemptId", tracer.instance() + ":" + event.eventId() + ":" + task.attempts());
        var request = HttpRequest.newBuilder(URI.create(settings.partnerUrl() + "/orders"))
                .timeout(Duration.ofMillis(settings.callTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", event.eventId())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(event)))
                .build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            long duration = System.nanoTime() - start;
            tracer.trace("external_result", task, "durationMs", duration / 1e6, "status", response.statusCode());
            if (response.statusCode() != 200) {
                throw new DeliveryDeferredException(DeliveryDeferredException.Reason.PARTNER_REJECTED,
                        "Partner status " + response.statusCode());
            }
            return duration;
        } catch (DeliveryDeferredException rejected) {
            throw rejected;
        } catch (Exception failure) {
            tracer.trace("external_error", task, "durationMs", (System.nanoTime() - start) / 1e6, "error", failure.toString());
            throw failure;
        }
    }
}
