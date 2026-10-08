package com.ordersystem.partner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.partner.config.PartnerSettings;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes one JSON line per processing stage. The experiment collector reads these lines from the
 * process log, so stage names are a contract with experiments/*.py.
 */
@Component
public class Tracer {
    private final ObjectMapper json;
    private final PartnerSettings settings;
    private final String instance = UUID.randomUUID().toString();
    private final AtomicLong count = new AtomicLong();
    private final java.util.Set<String> stages;

    public Tracer(ObjectMapper json, PartnerSettings settings) {
        this.json = json;
        this.settings = settings;
        this.stages = settings.tracedStages();
    }

    public String instance() { return instance; }
    public long count() { return count.get(); }

    public void trace(String stage, PartnerTask task, Object... detail) {
        // Long load runs keep only the stages their analysis reads; every line costs CPU on this host.
        if (!settings.traceEnabled() || (!stages.isEmpty() && !stages.contains(stage))) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("time", System.currentTimeMillis());
        data.put("stage", stage);
        data.put("instance", instance);
        data.put("code", settings.codeVersion());
        data.put("mode", settings.mode().label());
        data.put("thread", Thread.currentThread().getName());
        if (task != null) {
            var event = task.event();
            data.put("runId", event.runId());
            data.put("sellerId", event.sellerId());
            data.put("orderId", event.orderId());
            data.put("eventId", event.eventId());
            data.put("sequence", event.sequence());
            data.put("operation", event.operation());
            data.put("topic", task.topic());
            data.put("partition", task.partition());
            data.put("offset", task.offset());
            data.put("attempt", task.attempts());
        }
        for (int i = 0; i + 1 < detail.length; i += 2) data.put(detail[i].toString(), detail[i + 1]);
        try {
            System.out.println("TRACE " + json.writeValueAsString(data));
            count.incrementAndGet();
        } catch (Exception e) {
            throw new IllegalStateException("Telemetry failed", e);
        }
    }
}
