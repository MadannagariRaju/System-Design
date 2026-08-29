# Idempotency

A practical implementation of **Idempotency** using Java and Spring Boot.

This project demonstrates how idempotency prevents duplicate operations when the same request is submitted multiple times, especially in scenarios such as **payment processing**.

---

## 1. What are we building?

We are building a small **Payment Service** that exposes the following API:

```http
POST /payments
```

### Example Request

```json
{
    "amount": 1000,
    "currency": "INR",
    "customerId": "CUST101"
}
```

The client also sends an **Idempotency-Key** in the request header:

```http
Idempotency-Key: abc-123
```

The idempotency key is extremely important because it allows the server to identify repeated requests.

---

# 2. The Problem We Are Solving

Imagine a customer is making a payment of ₹1,000.

The client sends:

```http
POST /payments
```

The server receives the request and successfully processes the payment.

However, the response is lost because of a network problem.

```text
Client
  |
  | Payment ₹1000
  ↓
Payment Service
  |
  ↓
Payment processed ✅
  |
  X
  |
Response lost
```

The client does not know whether the payment was successful.

So it retries the request:

```text
Client
  |
  | Payment ₹1000 again
  ↓
Payment Service
  |
  ↓
Payment processed AGAIN ❌
```

The customer could potentially be charged twice:

```text
₹1000
+
₹1000
------
₹2000 ❌
```

This is a serious problem, especially in **payment systems**.

---

# 3. How Idempotency Solves the Problem

The client sends a unique idempotency key with the request:

```http
Idempotency-Key: abc-123
```

### First Request

```text
Client
   |
   | abc-123
   ↓
Payment Service
   |
   | First time?
   ↓
Process Payment
   |
   ↓
TXN1001
```

The server stores the result:

```text
abc-123 → TXN1001
```

### Retry Request

The client sends the same request again with the same key:

```text
Client
   |
   | abc-123
   ↓
Payment Service
   |
   | Have I seen abc-123?
   ↓
   YES
   |
   ↓
Return TXN1001
```

The payment is **not processed again**.

The same request produces the same result.

---

# 4. What Does Idempotency Mean?

An operation is **idempotent** when performing the same operation multiple times has the same effect as performing it once.

For example:

```text
Request 1 → Process Payment → TXN1001
Request 2 → Same Idempotency-Key → TXN1001
Request 3 → Same Idempotency-Key → TXN1001
```

The payment should only be processed once.

---

# 5. Version 1 — In-Memory Implementation

We will implement idempotency in multiple stages.

The first version uses an in-memory `Map`.

```text
Request
   ↓
Idempotency-Key
   ↓
Map
   ↓
Already processed?
   ├── YES → Return existing response
   │
   └── NO
        ↓
   Process payment
        ↓
   Store response
        ↓
   Return response
```

This implementation is intentionally simple.

The goal is to first understand the **core concept of idempotency** before introducing databases, Redis, concurrency, and distributed systems.

---

# 6. Project Structure

```text
01-idempotency/
│
├── README.md
├── pom.xml
│
└── src/
    ├── main/
    │   └── java/
    │       └── com/
    │           └── example/
    │               └── idempotency/
    │                   │
    │                   ├── IdempotencyApplication.java
    │                   │
    │                   ├── controller/
    │                   │   └── PaymentController.java
    │                   │
    │                   ├── service/
    │                   │   └── PaymentService.java
    │                   │
    │                   └── model/
    │                       ├── PaymentRequest.java
    │                       └── PaymentResponse.java
    │
    └── test/
        └── java/
            └── com/
                └── example/
                    └── idempotency/
```

---

# 7. Responsibilities of Each Class

## PaymentController

The controller receives HTTP requests.

```http
POST /payments
```

It extracts the `Idempotency-Key` and passes the request to the service.

---

## PaymentService

The service contains the core idempotency logic.

It determines:

```text
Have we already processed this idempotency key?
```

If yes:

```text
Return the previously stored response.
```

If no:

```text
Process the payment.
Store the response.
Return the response.
```

---

## PaymentRequest

Represents the incoming payment request.

Example:

```json
{
    "amount": 1000,
    "currency": "INR",
    "customerId": "CUST101"
}
```

---

## PaymentResponse

Represents the result of the payment.

Example:

```json
{
    "transactionId": "TXN1001",
    "status": "SUCCESS"
}
```

---

# 8. Where Do We Store the Idempotency Key?

For Version 1, we use:

```java
Map<String, PaymentResponse>
```

For example:

```text
Idempotency Key     Result
----------------------------
abc-123             TXN1001
abc-456             TXN1002
abc-789             TXN1003
```

You can think of this as a simple notebook maintained by the application.

When a request arrives:

```text
abc-123
```

we check:

```text
Does abc-123 already exist?
```

### If YES

```text
Return the existing result.
```

### If NO

```text
Process payment
      ↓
Store result
      ↓
Return result
```

---

# 9. Example

## First Request

```http
POST /payments
Idempotency-Key: abc-123
```

Request:

```json
{
    "amount": 1000,
    "currency": "INR",
    "customerId": "CUST101"
}
```

Response:

```json
{
    "transactionId": "TXN1001",
    "status": "SUCCESS"
}
```

The application stores:

```text
abc-123 → TXN1001
```

---

## Second Request

The client sends the same request again:

```http
POST /payments
Idempotency-Key: abc-123
```

The application checks the stored keys.

```text
abc-123 → Found ✅
```

Instead of processing the payment again, it returns:

```json
{
    "transactionId": "TXN1001",
    "status": "SUCCESS"
}
```

The payment is **not processed a second time**.

---

# 10. Basic Architecture

```text
                 Client
                   |
                   |
                   | POST /payments
                   | Idempotency-Key: abc-123
                   ↓
          ┌──────────────────┐
          │ PaymentController│
          └────────┬─────────┘
                   |
                   ↓
          ┌──────────────────┐
          │  PaymentService  │
          └────────┬─────────┘
                   |
                   ↓
             ┌───────────┐
             │    Map    │
             └─────┬─────┘
                   |
          ┌────────┴─────────┐
          ↓                  ↓
      Key exists?          New Key
          |                  |
          ↓                  ↓
   Return existing      Process Payment
      response                |
                              ↓
                         Store Result
                              |
                              ↓
                       Return Response
```

---

# 11. Implementation Journey

We will progressively improve this implementation.

### Version 1 — In-Memory Map

```text
Client
  ↓
Spring Boot
  ↓
Map
  ↓
Idempotency
```

This helps us understand the basic concept.

---

### Version 2 — Database

We will replace the in-memory storage with a database.

```text
Client
  ↓
Spring Boot
  ↓
Database
  ↓
Idempotency Record
```

This allows the idempotency information to survive application restarts.

---

### Version 3 — Redis

We will introduce Redis for fast idempotency checks.

```text
Client
  ↓
Spring Boot
  ↓
Redis
  ↓
Idempotency Record
```

We will learn about:

- Redis keys
- TTL
- Atomic operations
- `SET NX`
- Distributed systems

---

### Version 4 — Concurrent Requests

We will simulate multiple requests arriving at the same time.

For example:

```text
Request 1 ─────┐
               │
Request 2 ─────┼──→ Payment Service
               │
Request 3 ─────┘
```

We will investigate **race conditions** and learn how to prevent duplicate processing.

---

### Version 5 — Production-Oriented Design

Finally, we will consider real-world failure scenarios:

- What happens when multiple requests arrive simultaneously?
- What happens when the application crashes?
- What happens when the payment succeeds but the idempotency record is not saved?
- What happens when Redis is unavailable?
- How long should an idempotency key be stored?
- What happens when the same key is reused with a different request?
- How do multiple application instances share idempotency information?
- How can database constraints help guarantee uniqueness?

---

# 12. Concepts We Will Learn

Through this project, we will explore:

- Idempotency
- Request retries
- Duplicate requests
- Payment processing
- Idempotency keys
- In-memory storage
- Database persistence
- Redis
- TTL
- Atomic operations
- Race conditions
- Concurrent requests
- Database unique constraints
- Distributed systems
- Failure handling
- Multiple application instances

---

# 13. Learning Progression

```text
                 Idempotency
                      |
                      ↓
             ┌────────────────┐
             │   Version 1    │
             │    HashMap     │
             └───────┬────────┘
                     ↓
             ┌────────────────┐
             │   Version 2    │
             │    Database    │
             └───────┬────────┘
                     ↓
             ┌────────────────┐
             │   Version 3    │
             │     Redis      │
             └───────┬────────┘
                     ↓
             ┌────────────────┐
             │   Version 4    │
             │ Concurrent     │
             │ Requests       │
             └───────┬────────┘
                     ↓
             ┌────────────────┐
             │   Version 5    │
             │ Production     │
             │ Design         │
             └────────────────┘
```

---

# 14. First Goal

For the first version, we will keep everything simple.

The target flow is:

```text
POST /payments
      │
      │ Idempotency-Key: abc-123
      ↓
PaymentController
      ↓
PaymentService
      ↓
Map
      │
      ├── Key exists ──────→ Return old response
      │
      └── Key doesn't exist
                    ↓
              Process Payment
                    ↓
              Save response
                    ↓
              Return response
```

Once Version 1 is working, we will gradually introduce **database → Redis → concurrency → distributed-system problems → production-oriented design**.

---

## Goal of This Repository

This project is part of the larger:

```text
system-design-components
```

repository.

The goal is to learn system design through **hands-on implementation of individual real-world components, patterns, and distributed-system concepts**, rather than learning them only through theory.