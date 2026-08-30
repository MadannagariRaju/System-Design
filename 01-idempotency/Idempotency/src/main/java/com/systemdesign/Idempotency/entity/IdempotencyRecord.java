package com.systemdesign.Idempotency.entity;

import com.systemdesign.Idempotency.model.IdempotencyStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.cglib.core.Local;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@Table(name = "idempotency_records",
        uniqueConstraints = {
            @UniqueConstraint(columnNames = "idempotencyKey")
        })
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(nullable = false, unique = true)
    private  String idempotencyKey;

    @Column
    private String transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IdempotencyStatus status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    public IdempotencyRecord(String idempotencyKey, String transactionId, IdempotencyStatus status) {
        this.idempotencyKey = idempotencyKey;
        this.transactionId = transactionId;
        this.status = status;
    }

    public IdempotencyRecord(String idempotencyKey, IdempotencyStatus status, LocalDateTime createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.createdAt = createdAt;
    }

}
