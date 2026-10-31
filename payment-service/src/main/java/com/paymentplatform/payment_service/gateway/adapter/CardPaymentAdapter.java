package com.paymentplatform.payment_service.gateway.adapter;

import com.paymentplatform.common_lib.dto.PaymentProcessorResponse;
import com.paymentplatform.common_lib.dto.VaultChargeRequest;
import com.paymentplatform.payment_service.client.VaultServiceClient;
import com.paymentplatform.payment_service.gateway.PaymentAdapter;
import com.paymentplatform.payment_service.gateway.dto.PaymentRequest;
import com.paymentplatform.payment_service.gateway.dto.PaymentResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@RequiredArgsConstructor
@Component
public class CardPaymentAdapter implements PaymentAdapter {

    private final VaultServiceClient vaultServiceClient;

    @Override
    @CircuitBreaker(name = "vault-service")
    @Retry(name = "vault-service")
    public PaymentResult initiate(PaymentRequest request) {
        Object tokenValue = request.methodDetails() != null ? request.methodDetails().get("token") : null;
        if (tokenValue == null || tokenValue.toString().isBlank()) {
            return new PaymentResult.Failure("CARD_TOKEN_MISSING", "Card payments require methodDetails.token");
        }

        PaymentProcessorResponse response = vaultServiceClient.charge(
                new VaultChargeRequest(request.paymentId(), request.merchantId(), tokenValue.toString(),
                        request.amount(), request.methodDetails())
        );

        return switch (response) {
            case PaymentProcessorResponse.Success success -> new PaymentResult.Success(success.bankReference());
            case PaymentProcessorResponse.Failure failure -> new PaymentResult.Failure(failure.errorCode(), failure.errorDescription());
            case PaymentProcessorResponse.Pending pending -> new PaymentResult.Pending(pending.processorReference());
        };
    }

    @Override
    public PaymentResult capture(UUID paymentId) {
        return new PaymentResult.Success("CARD_REF");
    }
}
