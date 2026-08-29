package com.systemdesign.Idempotency.model;

import lombok.Data;

@Data
public class PaymentRequest {

    private double amount;
    private String currency;
    private String customerId;

}
