package com.systemdesign.Idempotency.service;

import com.systemdesign.Idempotency.Repository.IdempotencyRecordRepository;
import com.systemdesign.Idempotency.entity.IdempotencyRecord;
import com.systemdesign.Idempotency.model.PaymentRequest;
import com.systemdesign.Idempotency.model.PaymentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
public class PaymentServiceV2 {

    private final IdempotencyRecordRepository idempotencyRecordRepository;

    public PaymentServiceV2(IdempotencyRecordRepository idempotencyRecordRepository) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
    }


    public synchronized PaymentResponse processPayment(String idempotencyKey, PaymentRequest paymentRequest) {

        // 1. check database

        var existingRecord = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey);
        if(existingRecord.isPresent()) {

            log.info("Duplicate Payment Request");
            IdempotencyRecord idempotencyRecord = existingRecord.get();
            PaymentResponse paymentResponse = new PaymentResponse(idempotencyRecord.getTransactionId(),idempotencyRecord.getStatus());
            return paymentResponse;
        }

        // delay for testing

        try{
            log.info("slept for 5 sec's");
            Thread.sleep(5000);
        }catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }




        // 2. simulate the payment processing as you like

        log.info("Payment Processing .....");
        String transactionId = "TXN_" + UUID.randomUUID();
        PaymentResponse paymentResponse = new PaymentResponse(transactionId,"SUCCESS");

        IdempotencyRecord record = new IdempotencyRecord(idempotencyKey,transactionId,"SUCCESS");

        idempotencyRecordRepository.save(record);

        return paymentResponse;
    }
}
