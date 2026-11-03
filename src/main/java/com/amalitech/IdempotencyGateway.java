package com.amalitech;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IdempotencyGateway {
    private static final int PORT = 3000;
    private static final long TTL_MS = 24L * 60 * 60 * 1000;
    private static final long PROCESSING_DELAY_MS = 2000;

    private static final Map<String, Entry> store = new ConcurrentHashMap<>();

    private static final Pattern AMOUNT = Pattern.compile("\"amount\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern CURRENCY = Pattern.compile("\"currency\"\\s*:\\s*\"([^\"]+)\"");

    static class Entry {
        final String bodyHash;
        final long expiresAt;
        final Object lock = new Object();
        volatile boolean inFlight = false;
        int statusCode;
        String responseBody;
        volatile boolean completed = false;

        Entry(String bodyHash, long expiresAt) {
            this.bodyHash = bodyHash;
            this.expiresAt = expiresAt;
        }

        boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    static class Result {
        final int statusCode;
        final String body;
        final boolean cacheHit;

        Result(int statusCode, String body, boolean cacheHit) {
            this.statusCode = statusCode;
            this.body = body;
            this.cacheHit = cacheHit;
        }
    }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/process-payment", IdempotencyGateway::handlePayment);
        server.createContext("/health", ex -> {
            byte[] b = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(16));
        server.start();
        System.out.println("Idempotency-Gateway listening on port " + PORT);
    }

    private static void handlePayment(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method not allowed.\"}");
            return;
        }
        String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
        if (key == null || key.trim().isEmpty()) {
            respond(ex, 400, "{\"error\":\"Idempotency-Key header is required.\"}");
            return;
        }
        key = key.trim();
        String rawBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).trim();

        Matcher am = AMOUNT.matcher(rawBody);
        Matcher cm = CURRENCY.matcher(rawBody);
        if (!am.find() || !cm.find()) {
            respond(ex, 400, "{\"error\":\"Body must include numeric amount and string currency.\"}");
            return;
        }
        double amount = Double.parseDouble(am.group(1));
        String currency = cm.group(1);
        String hash = sha256(rawBody);

        try {
            Result result = process(key, hash, amount, currency);
            if (result.cacheHit) ex.getResponseHeaders().set("X-Cache-Hit", "true");
            respond(ex, result.statusCode, result.body);
        } catch (IllegalArgumentException e) {
            respond(ex, 422, "{\"error\":\"Idempotency key already used for a different request body.\"}");
        }
    }

    private static Result process(String key, String hash, double amount, String currency) {
        Entry entry = store.get(key);
        if (entry != null && entry.expired() && !entry.inFlight) {
            store.remove(key, entry);
            entry = null;
        }

        if (entry != null) {
            if (!entry.bodyHash.equals(hash)) {
                throw new IllegalArgumentException("mismatch");
            }
            synchronized (entry.lock) {
                if (entry.inFlight) {
                    while (entry.inFlight) {
                        try { entry.lock.wait(); } catch (InterruptedException ignored) {}
                    }
                }
                return new Result(entry.statusCode, entry.responseBody, true);
            }
        }

        entry = new Entry(hash, System.currentTimeMillis() + TTL_MS);
        Entry existing = store.putIfAbsent(key, entry);
        if (existing != null) {

            if (!existing.bodyHash.equals(hash)) throw new IllegalArgumentException("mismatch");
            synchronized (existing.lock) {
                if (existing.inFlight) {
                    while (existing.inFlight) {
                        try { existing.lock.wait(); } catch (InterruptedException ignored) {}
                    }
                }
                return new Result(existing.statusCode, existing.responseBody, true);
            }
        }


        synchronized (entry.lock) {
            entry.inFlight = true;
        }
        try {
            Thread.sleep(PROCESSING_DELAY_MS);
        } catch (InterruptedException ignored) {}
        String body = "{\"message\":\"Charged " + formatAmount(amount) + " " + currency + "\"}";
        synchronized (entry.lock) {
            entry.statusCode = 201;
            entry.responseBody = body;
            entry.inFlight = false;
            entry.completed = true;
            entry.lock.notifyAll();
        }
        return new Result(201, body, false);
    }

    private static String formatAmount(double amount) {
        if (amount == Math.floor(amount)) return String.valueOf((long) amount);
        return String.valueOf(amount);
    }

    private static boolean parseBody(String raw, double[] amt, String[] cur) {
        int i = raw.indexOf("\"amount\"");
        if (i < 0) return false;
        int colon = raw.indexOf(':', i);
        if (colon < 0) return false;
        int j = colon + 1;
        while (j < raw.length() && Character.isWhitespace(raw.charAt(j))) j++;
        int start = j;
        while (j < raw.length() && (Character.isDigit(raw.charAt(j)) || raw.charAt(j) == '.' || raw.charAt(j) == '-')) j++;
        if (start == j) return false;
        try { amt[0] = Double.parseDouble(raw.substring(start, j)); } catch (NumberFormatException e) { return false; }

        int k = raw.indexOf("\"currency\"");
        if (k < 0) return false;
        int colon2 = raw.indexOf(':', k);
        if (colon2 < 0) return false;
        int q1 = raw.indexOf('"', colon2);
        if (q1 < 0) return false;
        int q2 = raw.indexOf('"', q1 + 1);
        if (q2 < 0) return false;
        cur[0] = raw.substring(q1 + 1, q2);
        if (cur[0].isEmpty()) return false;
        return true;
    }
    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }
}

