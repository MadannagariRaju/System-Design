# Idempotency – Version 2: Database-Based Approach

## Overview

This version implements idempotency using **PostgreSQL** as the shared source of truth.

The main idea is to ensure that for a given `Idempotency-Key`, only one request can successfully claim the operation.

The lifecycle is:

```text
Request
   |
   v
Try to create idempotency record
   |
   v
PENDING
   |
   v
Process business operation
   |
   v
COMPLETED
```

The database `UNIQUE` constraint protects the idempotency key from being inserted more than once.

---

## 1. The Problem

A simple implementation might do:

```text
Check whether key exists
        |
        v
If not found
        |
        v
Process payment
        |
        v
Save idempotency record
```

This is vulnerable to a race condition.

Two concurrent requests can both execute the check before either request inserts the record:

```text
Request A                    Request B
    |                            |
    v                            v
Check DB                      Check DB
    |                            |
NOT FOUND                    NOT FOUND
    |                            |
    v                            v
Process payment              Process payment
    |                            |
TXN-1001                     TXN-1002
```

This can result in the same business operation being performed twice.

---

# 2. Database UNIQUE Constraint

The `idempotency_key` column should be unique.

Example:

```sql
CREATE TABLE idempotency_records (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    transaction_id VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP
);
```

The important part is:

```sql
idempotency_key VARCHAR(255) UNIQUE
```

This guarantees that the database cannot contain:

```text
ABC123 → TXN-1001
ABC123 → TXN-1002
```

Only one record with `ABC123` can exist.

---

# 3. Why the UNIQUE Constraint Is Important

Application-level checking alone is not enough:

```java
if (!repository.existsByIdempotencyKey(key)) {
    repository.save(record);
}
```

Two requests can both see that the key does not exist.

The database `UNIQUE` constraint acts as the final authority:

```text
Request A ─────┐
               |
Request B ─────┼──> PostgreSQL
               |
Request C ─────┘
                    |
                    v
             UNIQUE constraint
```

Only one request can successfully create the record.

---

# 4. Idempotency States

Instead of storing only a final result, we maintain the state of the operation.

```java
public enum IdempotencyStatus {
    PENDING,
    COMPLETED,
    FAILED
}
```

### PENDING

The request has claimed the idempotency key and is currently processing the operation.

```text
ABC123 → PENDING
```

### COMPLETED

The business operation completed successfully.

```text
ABC123 → COMPLETED → TXN-1001
```

### FAILED

The business operation failed.

```text
ABC123 → FAILED
```

---

# 5. Entity Structure

Example entity:

```java
@Entity
@Table(
    name = "idempotency_records",
    uniqueConstraints = {
        @UniqueConstraint(columnNames = "idempotencyKey")
    }
)
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @Column
    private String transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdempotencyStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;
}
```

Notice that `transactionId` can be `null`.

Why?

Because when the record is first created:

```text
ABC123 → PENDING
```

the payment has not completed yet.

---

# 6. Complete Request Flow

## First Request

Suppose the client sends:

```http
POST /payments
Idempotency-Key: ABC123
```

The application checks the database:

```text
ABC123 does not exist
```

Then it creates:

```text
ABC123 → PENDING
```

Now the request owns the operation.

Then:

```text
PENDING
   |
   v
Process payment
   |
   v
TXN-1001
   |
   v
COMPLETED
```

Final database state:

```text
+--------+-----------+-------------+
| key    | status    | transaction |
+--------+-----------+-------------+
| ABC123 | COMPLETED | TXN-1001    |
+--------+-----------+-------------+
```

---

# 7. Retry Request

The client sends the same request again:

```http
POST /payments
Idempotency-Key: ABC123
```

The application finds:

```text
ABC123 → COMPLETED → TXN-1001
```

Therefore it should NOT process the payment again.

It simply returns the stored result:

```json
{
    "transactionId": "TXN-1001",
    "status": "SUCCESS"
}
```

The important point is:

```text
First request  → TXN-1001
Retry request  → TXN-1001
```

The retry receives the same logical result.

---

# 8. Concurrent Requests

Suppose two requests arrive at nearly the same time:

```text
Request A → ABC999
Request B → ABC999
```

Both may initially check the database and see:

```text
ABC999 → NOT FOUND
```

Then both attempt:

```text
INSERT ABC999 → PENDING
```

The database decides the winner:

```text
Request A → INSERT → SUCCESS
Request B → INSERT → UNIQUE VIOLATION
```

The flow becomes:

```text
Request A                    Request B
    |                            |
    v                            v
Create PENDING              Create PENDING
    |                            |
SUCCESS                      UNIQUE ERROR
    |                            |
    v                            v
Process payment             DO NOT PROCESS
    |                       payment
    v
COMPLETED
```

This is the key improvement.

Request B must not continue to process the business operation after discovering that another request already claimed the key.

---

# 9. Important Limitation

The database `UNIQUE` constraint solves:

> Duplicate idempotency records.

It does NOT automatically solve:

> Duplicate external business operations.

For example:

```text
Request A                  Request B
    |                          |
    v                          v
PENDING                    PENDING attempt
    |                          |
    v                          v
Charge customer             Charge customer
    |                          |
SUCCESS                    SUCCESS
```

If the payment operation happens before the application discovers that another request won the idempotency key, a duplicate external operation may still occur.

Therefore:

```text
UNIQUE constraint
        ≠
Complete payment idempotency
```

The `PENDING → COMPLETED` state model gives us a much better foundation, but real distributed payment systems require additional mechanisms.

---

# 10. Why Not Keep a Database Transaction Open During Payment?

It may be tempting to write:

```java
@Transactional
public PaymentResponse processPayment(...) {

    // Create PENDING

    // Call external payment provider

    // Mark COMPLETED
}
```

This needs careful consideration.

If the payment provider is external:

```text
Application
    |
    | HTTP request
    v
Payment Provider
```

PostgreSQL cannot roll back an external payment.

For example:

```text
PostgreSQL transaction
        |
        v
Payment Provider
        |
        v
₹1000 charged
        |
        v
Application crashes
```

The database transaction cannot undo the external charge.

This is a distributed-systems problem.

---

# 11. Failure Scenario

Consider:

```text
ABC123 → PENDING
        |
        v
Process payment
        |
        v
₹1000 charged
        |
        v
Application crashes
```

The database may still contain:

```text
ABC123 → PENDING
```

But the payment might already have succeeded.

This creates an important state:

```text
UNKNOWN
```

because the application does not know whether the external operation completed.

This is one of the difficult problems we will study later.

---

# 12. State Machine

The idempotency record can be viewed as a state machine:

```text
             ┌───────────┐
             │  PENDING  │
             └─────┬─────┘
                   |
            Process operation
              /                       /                        v              v
     ┌───────────┐   ┌──────────┐
     │ COMPLETED │   │  FAILED  │
     └───────────┘   └──────────┘
```

A successful operation:

```text
PENDING → COMPLETED
```

A failed operation:

```text
PENDING → FAILED
```

---

# 13. Java Service Flow

A simplified service implementation:

```java
public PaymentResponse processPayment(
        String idempotencyKey,
        PaymentRequest request) {

    // 1. Check existing record
    var existingRecord =
            repository.findByIdempotencyKey(idempotencyKey);

    if (existingRecord.isPresent()) {

        IdempotencyRecord record =
                existingRecord.get();

        if (record.getStatus() ==
                IdempotencyStatus.COMPLETED) {

            return new PaymentResponse(
                    record.getTransactionId(),
                    "SUCCESS"
            );
        }

        if (record.getStatus() ==
                IdempotencyStatus.PENDING) {

            throw new IllegalStateException(
                    "Payment is already being processed"
            );
        }

        if (record.getStatus() ==
                IdempotencyStatus.FAILED) {

            throw new IllegalStateException(
                    "Previous payment attempt failed"
            );
        }
    }

    // 2. Claim the key
    try {

        IdempotencyRecord pendingRecord =
                new IdempotencyRecord(
                        idempotencyKey,
                        IdempotencyStatus.PENDING,
                        LocalDateTime.now()
                );

        repository.saveAndFlush(pendingRecord);

    } catch (DataIntegrityViolationException e) {

        throw new IllegalStateException(
                "Another request is already processing this operation"
        );
    }

    // 3. Process business operation
    String transactionId =
            "TXN-" + UUID.randomUUID();

    // 4. Mark as completed
    IdempotencyRecord record =
            repository
                    .findByIdempotencyKey(idempotencyKey)
                    .orElseThrow();

    record.setTransactionId(transactionId);
    record.setStatus(IdempotencyStatus.COMPLETED);
    record.setUpdatedAt(LocalDateTime.now());

    repository.save(record);

    // 5. Return response
    return new PaymentResponse(
            transactionId,
            "SUCCESS"
    );
}
```

---

# 14. Key Concepts Learned

### Race Condition

Two requests can execute the same logic concurrently.

### UNIQUE Constraint

The database guarantees that only one record can exist for a particular idempotency key.

### Claiming the Key

Creating:

```text
ABC123 → PENDING
```

means that one request has claimed responsibility for the operation.

### State Management

The operation moves through:

```text
PENDING → COMPLETED
```

or:

```text
PENDING → FAILED
```

### Retry Handling

A completed idempotency key returns the previously stored result.

### Distributed Application

The database provides shared coordination across multiple JVMs.

---

# 15. Current Architecture

```text
                         Client
                           |
                           v
                    Spring Boot API
                           |
                           v
                  Payment Service
                           |
                 ┌─────────┴─────────┐
                 |                   |
                 v                   v
          PostgreSQL            Payment Provider
                 |                   |
                 |                   |
                 v                   v
       Idempotency Record       External Operation
                 |
                 v
       PENDING → COMPLETED
```

---

# 16. Learning Progression

Our idempotency implementation is progressing like this:

```text
Version 1
   |
   v
Basic Idempotency
   |
   v
Version 2A
   |
   v
Race Condition
   |
   v
Version 2B
   |
   v
synchronized
   |
   v
JVM-level locking
   |
   v
Version 2C
   |
   v
Database UNIQUE constraint
   |
   v
Version 2D
   |
   v
PENDING → COMPLETED
   |
   v
Concurrent request handling
   |
   v
Redis
   |
   v
SET NX + TTL
   |
   v
Distributed Idempotency
```

---

## Main Takeaway

The most important distinction is:

```text
UNIQUE constraint
        |
        v
Prevents duplicate records

PENDING → COMPLETED
        |
        v
Models the lifecycle of the operation

Redis / Distributed Locking
        |
        v
Provides fast distributed coordination
```

These mechanisms solve different parts of the problem.

The goal of this project is not just to make the code work, but to understand **why each mechanism is needed and what problem it solves**.
