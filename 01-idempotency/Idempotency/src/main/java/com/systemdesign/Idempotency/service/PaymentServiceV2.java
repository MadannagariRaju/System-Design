package com.systemdesign.Idempotency.service;

import com.systemdesign.Idempotency.Repository.IdempotencyRecordRepository;
import com.systemdesign.Idempotency.entity.IdempotencyRecord;
import com.systemdesign.Idempotency.model.IdempotencyStatus;
import com.systemdesign.Idempotency.model.PaymentRequest;
import com.systemdesign.Idempotency.model.PaymentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
public class PaymentServiceV2 {

    private final IdempotencyRecordRepository idempotencyRecordRepository;

    public PaymentServiceV2(IdempotencyRecordRepository idempotencyRecordRepository) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
    }


    public PaymentResponse processPayment(String idempotencyKey, PaymentRequest paymentRequest) {

        // 1. check database

        var existingRecord = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey);
        if(existingRecord.isPresent()) {

//            log.info("Duplicate Payment Request");
            IdempotencyRecord idempotencyRecord = existingRecord.get();

            PaymentResponse paymentResponse;
            // ALready completed
            if(idempotencyRecord.getStatus() == IdempotencyStatus.COMPLETED) {
                paymentResponse = new PaymentResponse(idempotencyRecord.getTransactionId(),IdempotencyStatus.SUCCESS);
                return paymentResponse;
            }
            if(idempotencyRecord.getStatus() == IdempotencyStatus.PENDING) {
                paymentResponse = new PaymentResponse(idempotencyRecord.getTransactionId(),IdempotencyStatus.PENDING);
                return paymentResponse;
            }
            if(idempotencyRecord.getStatus() == IdempotencyStatus.FAILED) {
                paymentResponse = new PaymentResponse(idempotencyRecord.getTransactionId(),IdempotencyStatus.FAILED);
                return paymentResponse;
            }
        }

        try{
            IdempotencyRecord pendingRecord = new IdempotencyRecord(idempotencyKey,IdempotencyStatus.PENDING, LocalDateTime.now());
            idempotencyRecordRepository.saveAndFlush(pendingRecord);
            Thread.sleep(5000);
        }catch (DataIntegrityViolationException e) {
            throw new IllegalStateException("Another request is already processing this payment");
        }catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 2. simulate the payment processing as you like
        IdempotencyRecord record = idempotencyRecordRepository.findByIdempotencyKey(idempotencyKey).orElseThrow();
        log.info("Payment Processing .....");
        String transactionId = "TXN_" + UUID.randomUUID();

        try {

            record.setTransactionId(transactionId);
            record.setStatus(IdempotencyStatus.COMPLETED);
            record.setUpdatedAt(LocalDateTime.now());
        }catch (Exception e) {
            record.setStatus(IdempotencyStatus.FAILED);
            log.error("Payment is Failed");
        }finally {
            idempotencyRecordRepository.save(record);
        }

        idempotencyRecordRepository.save(record);

        PaymentResponse paymentResponse = new PaymentResponse(transactionId, IdempotencyStatus.SUCCESS);

        return paymentResponse;
    }
}
