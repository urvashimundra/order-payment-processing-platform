package com.paymentplatform.payment_service.processor;


import com.paymentplatform.common_lib.dto.PaymentProcessorRequest;
import com.paymentplatform.common_lib.dto.PaymentProcessorResponse;

public interface PaymentProcessor {

    PaymentProcessorResponse charge(PaymentProcessorRequest request);

}
