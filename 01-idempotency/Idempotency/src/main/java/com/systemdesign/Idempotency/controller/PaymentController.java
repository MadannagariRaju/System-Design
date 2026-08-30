package com.systemdesign.Idempotency.controller;


import com.systemdesign.Idempotency.model.PaymentRequest;
import com.systemdesign.Idempotency.model.PaymentResponse;
import com.systemdesign.Idempotency.service.PaymentService;
import com.systemdesign.Idempotency.service.PaymentServiceV2;
import com.systemdesign.Idempotency.service.PaymentServiceV3;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentServiceV2 paymentServiceV2;
    private final PaymentServiceV3 paymentServiceV3;

    @PostMapping("v1")
    public PaymentResponse makePayment(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @RequestBody PaymentRequest paymentRequest) {

        PaymentResponse paymentResponse = paymentService.processPayment(idempotencyKey,paymentRequest);
        return paymentResponse;

    }

    @PostMapping("v2")
    public PaymentResponse makePayment1(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                       @RequestBody PaymentRequest paymentRequest) {

        PaymentResponse paymentResponse = paymentServiceV2.processPayment(idempotencyKey,paymentRequest);
        return paymentResponse;

    }

    @PostMapping("v3")
    public PaymentResponse makePayment2(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                        @RequestBody PaymentRequest paymentRequest) {

        PaymentResponse paymentResponse = paymentServiceV3.processPayment(idempotencyKey,paymentRequest);
        return paymentResponse;

    }

}
