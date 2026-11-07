package org.example.github_api_practice.calendar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class MockCalendarServer implements AutoCloseable {
    private final HttpServer server;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final AtomicInteger creates = new AtomicInteger();
    final AtomicInteger deletes = new AtomicInteger();
    final AtomicInteger lookups = new AtomicInteger();
    final AtomicInteger refreshes = new AtomicInteger();
    final AtomicInteger hideGets = new AtomicInteger();
    final Map<String, String> events = new ConcurrentHashMap<>();
    volatile int createStatus;
    volatile int deleteStatus;
    volatile int lookupStatus;
    volatile int refreshStatus;
    volatile String createBody;
    volatile String createResponse;
    volatile String lookupResponse;
    volatile String refreshResponse;
    volatile String tokenForm;
    volatile String authorization;
    volatile String deletedPath;
    volatile String retryAfter;
    volatile String errorReason;
    volatile boolean dropCreateResponse;
    volatile boolean persistAfterRelease;
    volatile boolean conflictPublishesEvent;
    volatile long createDelayMillis;
    volatile CountDownLatch createEntered;
    volatile CountDownLatch releaseCreate;
    volatile CountDownLatch createPersisted;
    volatile CountDownLatch refreshEntered;
    volatile CountDownLatch releaseRefresh;

    MockCalendarServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/calendar/v3/calendars/primary/events", this::calendar);
            server.createContext("/token", exchange -> {
                refreshes.incrementAndGet();
                tokenForm = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                await(refreshEntered, releaseRefresh);
                write(exchange, refreshStatus, refreshResponse);
            });
            reset();
            server.start();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void calendar(HttpExchange exchange) throws IOException {
        authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String path = exchange.getRequestURI().getPath();
        String id = path.substring(path.lastIndexOf('/') + 1);
        if (exchange.getRequestMethod().equals("GET")) {
            lookups.incrementAndGet();
            if (hideGets.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                write(exchange, 404, "{}");
            } else if (lookupStatus != 0) {
                write(exchange, lookupStatus, lookupResponse == null ? error() : lookupResponse);
            } else {
                write(exchange, events.containsKey(id) ? 200 : 404, events.getOrDefault(id, "{}"));
            }
            return;
        }
        if (exchange.getRequestMethod().equals("POST")) {
            creates.incrementAndGet();
            createBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var body = json.readTree(createBody);
            id = body.get("id").asString();
            String event = createBody.substring(0, createBody.length() - 1) + ",\"status\":\"confirmed\"}";
            boolean accepted = createStatus >= 200 && createStatus < 300;
            if (!persistAfterRelease && (accepted || conflictPublishesEvent)) {
                events.putIfAbsent(id, event);
            }
            await(createEntered, releaseCreate);
            if (createDelayMillis > 0) {
                try {
                    Thread.sleep(createDelayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            if (persistAfterRelease && (accepted || conflictPublishesEvent)) {
                events.putIfAbsent(id, event);
            }
            if (createPersisted != null) {
                createPersisted.countDown();
            }
            if (dropCreateResponse) {
                exchange.close();
                return;
            }
            write(exchange, createStatus, createResponse == null ? accepted ? event : error() : createResponse);
            return;
        }
        deletes.incrementAndGet();
        deletedPath = path;
        if (deleteStatus == 204 || deleteStatus == 404 || deleteStatus == 410) {
            events.remove(id);
        }
        write(exchange, deleteStatus, error());
    }

    private String error() {
        return "{\"error\":{\"errors\":[{\"reason\":\"" + errorReason + "\"}]}}";
    }

    private void await(CountDownLatch entered, CountDownLatch release) throws IOException {
        if (entered == null) {
            return;
        }
        entered.countDown();
        try {
            if (!release.await(4, TimeUnit.SECONDS)) {
                throw new IOException("mock release timeout");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("mock interrupted");
        }
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    void reset() {
        if (releaseCreate != null) {
            releaseCreate.countDown();
        }
        if (releaseRefresh != null) {
            releaseRefresh.countDown();
        }
        events.clear();
        creates.set(0);
        deletes.set(0);
        lookups.set(0);
        refreshes.set(0);
        hideGets.set(0);
        createStatus = 200;
        deleteStatus = 204;
        lookupStatus = 0;
        refreshStatus = 200;
        createResponse = null;
        lookupResponse = null;
        refreshResponse = "{\"access_token\":\"renewed-access-fixture\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";
        createBody = null;
        tokenForm = null;
        authorization = null;
        deletedPath = null;
        retryAfter = null;
        errorReason = "forbidden";
        dropCreateResponse = false;
        persistAfterRelease = false;
        conflictPublishesEvent = false;
        createDelayMillis = 0;
        createEntered = null;
        releaseCreate = null;
        createPersisted = null;
        refreshEntered = null;
        releaseRefresh = null;
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        if (retryAfter != null) {
            exchange.getResponseHeaders().set("Retry-After", retryAfter);
        }
        if (status == 204) {
            exchange.sendResponseHeaders(204, -1);
        } else {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
