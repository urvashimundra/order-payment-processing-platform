package com.paymentplatform.common_lib.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

// FIX: this travels as JSON from vault-service to payment-service (Feign). Without a type marker
// Jackson cannot tell Pending / Success / Failure apart and fails with "cannot construct instance".
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = PaymentProcessorResponse.Pending.class, name = "PENDING"),
        @JsonSubTypes.Type(value = PaymentProcessorResponse.Success.class, name = "SUCCESS"),
        @JsonSubTypes.Type(value = PaymentProcessorResponse.Failure.class, name = "FAILURE")
})
public sealed interface PaymentProcessorResponse permits
        PaymentProcessorResponse.Pending,
        PaymentProcessorResponse.Success,
        PaymentProcessorResponse.Failure {

    record Pending(String processorReference) implements PaymentProcessorResponse {}

    record Success(String processorReference, String bankReference) implements PaymentProcessorResponse{}

    record Failure(String errorCode, String errorDescription) implements PaymentProcessorResponse{}
}
