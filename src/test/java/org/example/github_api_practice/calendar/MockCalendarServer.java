package org.example.github_api_practice.calendar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class MockCalendarServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    final AtomicInteger creates = new AtomicInteger();
    final AtomicInteger deletes = new AtomicInteger();
    final AtomicInteger refreshes = new AtomicInteger();
    volatile int createStatus;
    volatile int deleteStatus;
    volatile int refreshStatus;
    volatile String createBody;
    volatile String createResponse;
    volatile String refreshResponse;
    volatile String tokenForm;
    volatile String authorization;
    volatile String deletedPath;
    volatile CountDownLatch createEntered;
    volatile CountDownLatch releaseCreate;

    MockCalendarServer() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/calendar/v3/calendars/primary/events", exchange -> {
                authorization = exchange.getRequestHeaders().getFirst("Authorization");
                if (exchange.getRequestMethod().equals("POST")) {
                    creates.incrementAndGet();
                    createBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    if (createEntered != null) {
                        createEntered.countDown();
                        try {
                            if (!releaseCreate.await(4, TimeUnit.SECONDS)) {
                                throw new IOException("mock release timeout");
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IOException("mock interrupted");
                        }
                    }
                    write(exchange, createStatus, createResponse);
                } else {
                    deletes.incrementAndGet();
                    deletedPath = exchange.getRequestURI().getPath();
                    write(exchange, deleteStatus, "{}");
                }
            });
            server.createContext("/token", exchange -> {
                refreshes.incrementAndGet();
                tokenForm = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                write(exchange, refreshStatus, refreshResponse);
            });
            reset();
            server.start();
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    void reset() {
        if (releaseCreate != null) {
            releaseCreate.countDown();
        }
        creates.set(0);
        deletes.set(0);
        refreshes.set(0);
        createStatus = 200;
        deleteStatus = 204;
        refreshStatus = 200;
        createResponse = "{\"id\":\"event123\",\"htmlLink\":\"https://example.test/event\"}";
        refreshResponse = "{\"access_token\":\"renewed-access-fixture\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";
        createBody = null;
        tokenForm = null;
        authorization = null;
        deletedPath = null;
        createEntered = null;
        releaseCreate = null;
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
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
