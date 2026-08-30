package com.systemdesign.Idempotency.service;


import com.systemdesign.Idempotency.model.IdempotencyStatus;
import com.systemdesign.Idempotency.model.PaymentRequest;
import com.systemdesign.Idempotency.model.PaymentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.UUID;


@Slf4j
@Service
public class PaymentService {

    HashMap<String, PaymentResponse> idempotencyStore = new HashMap<>();

    public PaymentResponse processPayment(String idempotencyKey, PaymentRequest paymentRequest) {

        // 1. check if the idempotency key is already exit or not
        if(idempotencyStore.containsKey(idempotencyKey)) {
            log.info("Duplicate Payment Request");
            return idempotencyStore.get(idempotencyKey);
        }

        // 2. simulate the payment processing
        log.info("payment processing ....");

        String txnId = "TXN_" + UUID.randomUUID();

        PaymentResponse paymentResponse = new PaymentResponse(txnId, IdempotencyStatus.COMPLETED);

        idempotencyStore.put(idempotencyKey,paymentResponse);

        return paymentResponse;
    }
}
