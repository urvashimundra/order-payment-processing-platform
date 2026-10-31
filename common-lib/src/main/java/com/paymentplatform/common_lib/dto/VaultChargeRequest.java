package com.paymentplatform.common_lib.dto;

import com.paymentplatform.common_lib.entity.Money;

import java.util.Map;
import java.util.UUID;

public record VaultChargeRequest(
        UUID paymentId,
        UUID merchantId,
        String token,
        Money amount,
        Map<String, Object> methodDetails
) {
}
