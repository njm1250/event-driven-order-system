package com.ordersystem.partner.support;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-process seller API: records each request's idempotency key, with a per-seller delay. */
public final class StubPartnerApi implements AutoCloseable {
    private final HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Long> delayBySeller = new ConcurrentHashMap<>();

    public StubPartnerApi() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/orders", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes());
            requests.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            String seller = body.replaceAll(".*\"sellerId\":\"([^\"]+)\".*", "$1");
            try {
                Thread.sleep(delayBySeller.getOrDefault(seller, 0L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void delay(String seller, long millis) {
        delayBySeller.put(seller, millis);
    }

    public List<String> requests() {
        return requests;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
