package com.paymentplatform.merchant_service.dto.request;


import com.paymentplatform.common_lib.enums.Environment;
import jakarta.validation.constraints.NotNull;

public record CreateApiKeyRequest(
        @NotNull(message = "environment is required (TEST or LIVE)")
        Environment environment
) {
}
