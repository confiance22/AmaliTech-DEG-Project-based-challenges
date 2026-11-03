# Idempotency-Gateway (FinSafe Transactions Ltd.)

A payment-processing idempotency layer that guarantees a payment is processed exactly once, even when clients retry the same request.

## Architecture Diagram`n`nSequence diagram source: `architecture.puml`.

```mermaid
sequenceDiagram
    participant C as Client
    participant G as Gateway
    participant S as In-Memory Store

    C->>G: POST /process-payment (Idempotency-Key, body)
    G->>S: lookup key
    alt key not found
        S-->>G: not found
        G->>S: create entry (in-flight)
        G->>G: process payment (2s delay)
        G->>S: store response, mark complete, notify
        G-->>C: 201 Created + body
    else key found, same body, completed
        S-->>G: cached response
        G-->>C: 201 Created + body (X-Cache-Hit: true)
    else key found, different body
        S-->>G: mismatch
        G-->>C: 422 Unprocessable Entity
    else key found, in-flight
        G->>S: wait on entry lock
        S-->>G: completed response
        G-->>C: 201 Created + body (X-Cache-Hit: true)
    end
```

## Setup Instructions

Prerequisites: JDK 17+ and Gradle (or use the bundled wrapper if available).

Run:

```
gradle run
```

Or compile and run manually:

```
javac -d out src/main/java/com/amalitech/*.java
java -cp out com.amalitech.IdempotencyGateway
```

Server listens on `http://localhost:3000`.

## API Documentation

### POST /process-payment

Headers:
- `Idempotency-Key: <unique-string>` (required)

Body:
```json
{"amount": 100, "currency": "GHS"}
```

Responses:
- `201 Created` `{"message":"Charged 100 GHS"}` (first request)
- `201 Created` + `X-Cache-Hit: true` (duplicate, cached)
- `422 Unprocessable Entity` `{"error":"Idempotency key already used for a different request body."}` (key reuse with different body)
- `400 Bad Request` (missing key or invalid body)

### GET /health
Returns `{"status":"ok"}`.

## Design Decisions

- In-memory `ConcurrentHashMap` keyed by idempotency key; per-entry lock with `wait()/notifyAll()` handles in-flight (bonus) race conditions.
- Request body is hashed (SHA-256) for exact duplicate detection; mismatched payloads are rejected with 422.
- Keys expire after 24 hours (TTL) with periodic cleanup (Developer's Choice) to bound memory and prevent stale replays.
- No external dependencies; uses JDK built-in `com.sun.net.httpserver`.

## Developer's Choice

**Idempotency-Key TTL (24h) with automatic cleanup.** Real payment systems must not retain idempotency keys forever; expiring them bounds memory usage while still covering realistic retry windows. Cleanup runs every minute.

## Git Hygiene

Multiple commits with meaningful messages are used throughout development.

