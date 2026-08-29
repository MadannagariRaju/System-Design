package com.systemdesign.Idempotency.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

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

    @Column(nullable = false)
    private String transactionId;

    @Column(nullable = false)
    private String status;

    public IdempotencyRecord(String idempotencyKey, String transactionId, String status) {
        this.idempotencyKey = idempotencyKey;
        this.transactionId = transactionId;
        this.status = status;
    }
}
