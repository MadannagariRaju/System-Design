# Version 2 — Database-Backed Idempotency & Race Conditions

## 1. Objective

In Version 1, we stored idempotency records in an in-memory `HashMap`.

That works for a single application instance, but it has an important limitation:

> Multiple application instances cannot share the same in-memory state.

In Version 2, we move idempotency storage to PostgreSQL.

The goal is to understand:

- Database-backed idempotency
- Race conditions
- Concurrent requests
- Database unique constraints
- JVM-level locking with `synchronized`
- Why `synchronized` is not enough for distributed systems
- Why we eventually need a shared/distributed mechanism such as Redis

---

# 2. Version 1 vs Version 2

## Version 1

```text
Client
  |
  v
Payment Service
  |
  v
HashMap
```

The idempotency information exists only inside the application's memory.

## Version 2

```text
Client
  |
  v
Payment Service
  |
  v
PostgreSQL
```

Now the idempotency information is stored in a shared database.

---

# 3. Why Database-backed Idempotency?

Imagine we have two application instances:

```text
                         Load Balancer
                              |
                    +---------+---------+
                    |                   |
                    v                   v
             Payment Service 1   Payment Service 2
                    |                   |
                    +---------+---------+
                              |
                              v
                         PostgreSQL
```

Suppose the first request contains:

```text
Idempotency-Key: ABC123
```

It reaches Server 1.

Server 1 processes the payment and stores:

```text
ABC123 -> TXN-1001
```

Later, the client retries the same request.

The retry reaches Server 2.

Because both servers use PostgreSQL, Server 2 can find:

```text
ABC123 -> TXN-1001
```

and return the original result instead of processing the payment again.

---

# 4. Database Table

We create an `idempotency_records` table.

Example:

```text
+----+------------------+----------------+---------+
| id | idempotency_key  | transaction_id | status  |
+----+------------------+----------------+---------+
| 1  | ABC123           | TXN-1001       | SUCCESS |
| 2  | ABC456           | TXN-1002       | SUCCESS |
+----+------------------+----------------+---------+
```

The important rule is:

```text
One Idempotency-Key -> One Idempotency Record
```

Therefore, `idempotency_key` should have a unique constraint.

---

# 5. Project Structure

```text
01-idempotency/
│
├── README.md
├── pom.xml
│
└── src/
    ├── main/
    │   ├── java/
    │   │   └── com/example/idempotency/
    │   │       │
    │   │       ├── IdempotencyApplication.java
    │   │       │
    │   │       ├── controller/
    │   │       │   └── PaymentController.java
    │   │       │
    │   │       ├── service/
    │   │       │   └── PaymentService.java
    │   │       │
    │   │       ├── model/
    │   │       │   ├── PaymentRequest.java
    │   │       │   ├── PaymentResponse.java
    │   │       │   └── IdempotencyRecord.java
    │   │       │
    │   │       └── repository/
    │   │           └── IdempotencyRecordRepository.java
    │   │
    │   └── resources/
    │       └── application.properties
    │
    └── test/
        └── java/
```

---

# 6. PostgreSQL Configuration

Add Spring Data JPA and PostgreSQL dependencies.

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>

<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

Create a database:

```sql
CREATE DATABASE system_design;
```

Configure:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/system_design
spring.datasource.username=postgres
spring.datasource.password=your_password

spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true
```

---

# 7. IdempotencyRecord Entity

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

    @Column(nullable = false)
    private String transactionId;

    @Column(nullable = false)
    private String status;

    public IdempotencyRecord() {
    }

    public IdempotencyRecord(
            String idempotencyKey,
            String transactionId,
            String status) {

        this.idempotencyKey = idempotencyKey;
        this.transactionId = transactionId;
        this.status = status;
    }

    // Getters and setters
}
```

The most important part is:

```java
@Column(nullable = false, unique = true)
private String idempotencyKey;
```

This tells the database:

> Two records cannot have the same idempotency key.

---

# 8. Repository

```java
public interface IdempotencyRecordRepository
        extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByIdempotencyKey(
            String idempotencyKey);
}
```

Spring Data JPA generates the required query automatically.

Conceptually, it performs:

```sql
SELECT *
FROM idempotency_records
WHERE idempotency_key = ?;
```

---

# 9. Basic Database-backed Flow

The service initially works like this:

```text
Request
   |
   v
Check Database
   |
   +---- Key exists ----> Return existing response
   |
   +---- Key doesn't exist
                |
                v
         Process Payment
                |
                v
       Save Idempotency Record
                |
                v
         Return Response
```

The important point is that the idempotency state is now shared across application instances.

---

# 10. The Race Condition

Although the previous flow looks correct, it contains a serious problem.

Consider two requests arriving at the same time.

Both contain:

```text
Idempotency-Key: ABC123
```

## Request A

```text
Thread A
   |
   v
Check DB
   |
   v
ABC123 not found
```

At almost the same time:

## Request B

```text
Thread B
   |
   v
Check DB
   |
   v
ABC123 not found
```

Both requests now believe they are the first request.

So:

```text
Thread A                    Thread B
   |                           |
   | Check DB                  | Check DB
   | -> NOT FOUND              | -> NOT FOUND
   |                           |
   v                           v
Process Payment             Process Payment
   |                           |
   v                           v
TXN-1001                    TXN-1002
```

This can result in the payment being processed twice.

---

# 11. Why Does This Happen?

Our code has two separate operations:

```java
repository.findByIdempotencyKey(idempotencyKey);
```

and:

```java
repository.save(record);
```

There is a gap between them:

```text
CHECK
  |
  | <--- Another request can enter here
  |
PROCESS
  |
SAVE
```

This is a classic race condition.

The problem is not simply:

> "Can the database contain duplicate keys?"

The bigger problem is:

> "Can the business operation execute twice before the duplicate is detected?"

---

# 12. Reproducing the Race Condition

To make the race condition easier to observe, temporarily add a delay after the database check.

```java
var existingRecord =
        repository.findByIdempotencyKey(idempotencyKey);

if (existingRecord.isPresent()) {

    IdempotencyRecord record = existingRecord.get();

    return new PaymentResponse(
            record.getTransactionId(),
            record.getStatus()
    );
}

// Artificial delay
try {
    Thread.sleep(5000);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    throw new RuntimeException(e);
}

System.out.println("Processing payment...");
```

The five-second delay creates a window for another request to enter.

---

# 13. Test the Race Condition

Send two requests almost simultaneously.

Both must use:

```text
Idempotency-Key: ABC123
```

Request:

```http
POST /payments
Idempotency-Key: ABC123
Content-Type: application/json
```

Body:

```json
{
    "amount": 1000,
    "currency": "INR",
    "customerId": "CUST101"
}
```

The flow can become:

```text
Request A
    |
    +--> Check DB
    |    -> NOT FOUND
    |
    +--> Wait
    |
    +--> Process Payment
         -> TXN-1001


Request B
    |
    +--> Check DB
    |    -> NOT FOUND
    |
    +--> Wait
    |
    +--> Process Payment
         -> TXN-1002
```

Eventually, one database insert may fail because `ABC123` is unique.

But that is still not good enough.

Why?

Because the payment processing may already have happened twice.

---

# 14. Important Lesson

A unique constraint protects the database:

```text
ABC123 -> TXN-1001
ABC123 -> TXN-1002  X
```

But it does not automatically undo a business operation that happened before the database rejected the duplicate.

Therefore:

> A database unique constraint is necessary, but by itself it is not sufficient for safe idempotent business processing.

---

# 15. First Attempt: `synchronized`

For learning purposes, we can try:

```java
public synchronized PaymentResponse processPayment(
        String idempotencyKey,
        PaymentRequest request) {
```

Now only one thread in the same JVM can execute this method at a time.

The flow becomes:

```text
Thread A
   |
   | acquire lock
   v
Check DB
   |
   v
Process Payment
   |
   v
Save Record
   |
   v
release lock
```

Thread B must wait:

```text
Thread B
   |
   | waiting...
   |
   v
Thread A releases lock
   |
   v
Check DB
   |
   v
ABC123 exists
   |
   v
Return previous response
```

This can prevent the race condition when both requests reach the same JVM.

---

# 16. But `synchronized` Has a Major Limitation

Consider a production environment:

```text
                    Load Balancer
                         |
             +-----------+-----------+
             |                       |
             v                       v
       Application 1           Application 2
          JVM 1                   JVM 2
             |                       |
          Lock A                  Lock B
```

Request A reaches Application 1:

```text
ABC123 -> Server 1
```

Request B reaches Application 2:

```text
ABC123 -> Server 2
```

Each JVM has its own lock.

Therefore:

```text
Server 1                  Server 2
   |                         |
Lock A                     Lock B
   |                         |
Check DB                   Check DB
   |                         |
NOT FOUND                  NOT FOUND
   |                         |
Process                    Process
   |                         |
TXN-1001                   TXN-1002
```

`synchronized` cannot coordinate locks between different JVMs.

---

# 17. JVM Lock vs Distributed Lock

This is an important system-design distinction.

## JVM-level lock

```text
synchronized
```

Works within:

```text
One JVM
```

It does not work across:

```text
Multiple JVMs
```

## Distributed lock

A distributed lock must be stored somewhere shared:

```text
                 Shared Store
                     |
          +----------+----------+
          |                     |
          v                     v
     Application 1        Application 2
```

Both applications can see the same lock.

Redis is commonly used for this type of distributed coordination.

---

# 18. The Evolution of Our Idempotency Implementation

We are intentionally learning this progressively:

```text
Version 1
    |
    v
In-memory HashMap
    |
    v
Problem: multiple instances don't share state
    |
    v
Version 2
    |
    v
PostgreSQL
    |
    v
Problem: race condition
    |
    v
synchronized
    |
    v
Problem: works only inside one JVM
    |
    v
Distributed coordination
    |
    v
Version 3
    |
    v
Redis
```

---

# 19. Key Concepts Learned

By the end of Version 2, we should understand:

### Idempotency

The same logical operation should produce one authoritative result even if the request is retried.

### Shared State

Multiple application instances need a common place to store idempotency information.

### Race Condition

Two concurrent requests can both observe that a key does not exist and both proceed.

### Unique Constraint

The database can prevent duplicate idempotency records.

### `synchronized`

Provides locking inside a single JVM.

### Distributed Lock

A lock shared across multiple application instances.

### Atomicity

Related operations often need to be treated as one indivisible operation to avoid inconsistent behavior.

---

# 20. What We Will Do Next

Before moving to Redis, we should strengthen Version 2 further.

The next step is:

```text
Database
   |
   v
Race Condition
   |
   v
Unique Constraint
   |
   v
Transaction
   |
   v
Atomicity
   |
   v
Concurrent Request Test
```

After that, we can start **Version 3 — Redis-backed Idempotency**.

The progression will be:

```text
             IDempotency
                  |
       +----------+----------+
       |                     |
       v                     v
   Single JVM          Multiple JVMs
       |                     |
       v                     v
 HashMap              PostgreSQL / Redis
       |                     |
       v                     v
 synchronized         Distributed coordination
       |                     |
       +----------+----------+
                  |
                  v
         Production Design
```

---

# 21. Final Takeaway

The biggest lesson from Version 2 is:

> **Checking whether an idempotency key exists and then processing the request are two separate operations.**

Therefore, this:

```text
CHECK -> PROCESS -> SAVE
```

can be unsafe under concurrent requests.

A production-quality design needs to carefully handle:

```text
Concurrent requests
        +
Shared state
        +
Atomicity
        +
Business operation consistency
```

This is the foundation for understanding why technologies such as **PostgreSQL transactions, Redis atomic operations, distributed locks, and messaging patterns** become important in real-world system design.
