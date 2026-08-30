package com.systemdesign.Idempotency.service;

import com.systemdesign.Idempotency.model.IdempotencyStatus;
import com.systemdesign.Idempotency.model.PaymentRequest;
import com.systemdesign.Idempotency.model.PaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceV3 {

    private final StringRedisTemplate stringRedisTemplate;

    public PaymentResponse processPayment(String idempotencyKey, PaymentRequest paymentRequest) {

        String redisKey = "Idempotency : "+ idempotencyKey;

        Boolean claimed = stringRedisTemplate.opsForValue().setIfAbsent(
                redisKey, "PENDING"
        );

        if(!Boolean.TRUE.equals(claimed)) {
            throw new IllegalStateException("Request is already being processed");
        }

        log.info("Processing payments ....");

        String transactionId = "TXN-" + UUID.randomUUID();

        stringRedisTemplate.opsForValue().set(redisKey,"COMPLETED:"+transactionId);

        return new PaymentResponse(transactionId,IdempotencyStatus.SUCCESS);
    }
}
