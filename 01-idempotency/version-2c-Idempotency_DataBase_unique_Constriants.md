# Idempotency

## Overview

Idempotency is a mechanism that ensures that the same logical request does not produce multiple business operations when the request is retried or received multiple times.

It is especially important in systems such as:

- Payment systems
- Order processing
- Booking systems
- Money transfers
- Subscription systems

For example, if a payment request is sent twice with the same idempotency key, the system should not charge the customer twice.

---

# Version 1 — In-Memory Idempotency

The initial implementation used an in-memory `HashMap` to store the relationship between an idempotency key and its response.

```text
Idempotency-Key       Response
-----------------------------------
ABC123                TXN-1001
ABC456                TXN-1002
```

### Flow

```text
Request
   |
   v
Check HashMap
   |
   +---- Key exists ----> Return existing response
   |
   +---- Key doesn't exist
              |
              v
        Process payment
              |
              v
        Store response
              |
              v
        Return response
```

### Problem

The data exists only inside one JVM.

If multiple application instances are running:

```text
                 Load Balancer
                /             \
               v               v
           JVM 1             JVM 2
             |                 |
          HashMap 1          HashMap 2
```

JVM 1 does not know what is stored in JVM 2.

Therefore, in-memory storage is not suitable for a distributed application.

---

# Version 2 — Database-backed Idempotency

To make idempotency records shared across application instances, we moved the storage from memory to PostgreSQL.

```text
                 Load Balancer
                /             \
               v               v
           JVM 1             JVM 2
                \             /
                 \           /
                    PostgreSQL
```

Now all application instances can access the same idempotency records.

---

# Version 2C — Database UNIQUE Constraint

## Problem

Even when using a shared database, two concurrent requests can attempt to create the same idempotency key.

For example:

```text
Request A                         Request B
    |                                 |
    | Check ABC123                    | Check ABC123
    |                                 |
    v                                 v
 NOT FOUND                         NOT FOUND
    |                                 |
    | Process payment                 | Process payment
    |                                 |
    v                                 v
 TXN-1001                          TXN-1002
    |                                 |
    | INSERT ABC123                   | INSERT ABC123
    |                                 |
    v                                 v
 SUCCESS                           FAILURE
```

Both requests saw that the key did not exist.

This is a **race condition**.

---

# Database UNIQUE Constraint

We can ask PostgreSQL to enforce uniqueness on the idempotency key.

```sql
CREATE TABLE idempotency_records (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(255) UNIQUE NOT NULL,
    transaction_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL
);
```

The important part is:

```sql
idempotency_key VARCHAR(255) UNIQUE
```

This tells the database:

> Only one record can exist for a particular idempotency key.

---

# Example

Initially:

```text
id | idempotency_key | transaction_id
-------------------------------------
1  | ABC123          | TXN-1001
```

Another request tries:

```sql
INSERT INTO idempotency_records
(idempotency_key, transaction_id, status)
VALUES ('ABC123', 'TXN-1002', 'SUCCESS');
```

PostgreSQL checks the unique constraint:

```text
ABC123 already exists
        |
        v
UNIQUE constraint violation
        |
        v
INSERT rejected
```

Therefore:

```text
ABC123 → TXN-1001
```

remains the only record.

---

# Why Not Just Check Before Inserting?

We could write:

```java
if (!repository.existsByIdempotencyKey(key)) {
    repository.save(record);
}
```

But this is not safe under concurrency.

Two requests can execute the check at almost exactly the same time:

```text
Thread A                         Thread B
    |                                |
    | exists?                       | exists?
    |                                |
    v                                v
   NO                               NO
    |                                |
    | save                           | save
    v                                v
```

Both requests believe they are allowed to insert.

This is called a **check-then-act race condition**.

The database `UNIQUE` constraint provides the final guarantee.

---

# Multiple JVMs

One of the major advantages of a database constraint is that it works across application instances.

```text
                 Load Balancer
                /             \
               v               v
           Application 1   Application 2
              JVM 1           JVM 2
                 \             /
                  \           /
                   PostgreSQL
                       |
               UNIQUE constraint
                       |
                idempotency_key
```

It does not matter which JVM receives the request.

The database is shared by both applications and enforces the uniqueness rule.

---

# Important Limitation

A `UNIQUE` constraint prevents duplicate **database records**.

It does not automatically prevent duplicate **business operations**.

Consider:

```text
Request A
   |
   v
Check DB
   |
 NOT FOUND
   |
   v
Process payment
   |
   v
₹1000 charged
   |
   v
INSERT ABC123
   |
 SUCCESS
```

At the same time:

```text
Request B
   |
   v
Check DB
   |
 NOT FOUND
   |
   v
Process payment
   |
   v
₹1000 charged again
   |
   v
INSERT ABC123
   |
 FAILURE - UNIQUE constraint
```

The database successfully prevented the second row.

However, the second payment may already have been processed.

Therefore:

> A database `UNIQUE` constraint protects data integrity, but by itself it does not guarantee that the business operation happens only once.

---

# Key Learning

There are two different problems:

### Problem 1 — Duplicate database records

```text
ABC123 → TXN-1001
ABC123 → TXN-1002
```

A `UNIQUE` constraint solves this.

### Problem 2 — Duplicate business operations

```text
Payment A → ₹1000
Payment B → ₹1000
```

A `UNIQUE` constraint alone does not solve this.

This distinction is critical when designing idempotent APIs.

---

# Current Architecture

```text
                     Client
                       |
                       | Idempotency-Key
                       v
                Load Balancer
                  /         \
                 v           v
              JVM 1         JVM 2
                 \           /
                  \         /
                   PostgreSQL
                       |
             UNIQUE(idempotency_key)
```

---

# What We Learned

- Why idempotency keys need shared storage in distributed applications.
- Why `HashMap` is not sufficient for multiple JVMs.
- Why `synchronized` only protects threads within one JVM.
- How a database `UNIQUE` constraint prevents duplicate idempotency records.
- Why a simple `check → process → save` sequence has a race condition.
- Why preventing duplicate database rows is different from preventing duplicate business operations.
- Why database constraints should be used as a final data-integrity guarantee.

---

# Next Step — Version 2D

The next version will focus on controlling the lifecycle of an idempotency request.

We will introduce states such as:

```text
PENDING
   |
   v
PROCESSING
   |
   v
COMPLETED
```

and investigate how transactions, concurrency, and atomic operations can be used to make the idempotency implementation safer.

Eventually, we will move to Redis and compare:

```text
Database-based Idempotency
          vs
Redis-based Idempotency
```