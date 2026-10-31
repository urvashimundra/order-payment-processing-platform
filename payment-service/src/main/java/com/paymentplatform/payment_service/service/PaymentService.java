package com.paymentplatform.payment_service.service;

import com.paymentplatform.payment_service.dto.request.PaymentInitRequest;
import com.paymentplatform.payment_service.dto.response.PaymentResponse;

import java.util.UUID;

public interface PaymentService {

    PaymentResponse initiate(UUID merchantId, PaymentInitRequest request, String idempotencyKey);

    PaymentResponse capture(UUID merchantId, UUID paymentId);
    PaymentResponse getPayment(UUID merchantId, UUID paymentId);
    void resolveAuthorization(UUID paymentId, boolean approve, String bankRef, String errorCode, String errorDescription);
}
