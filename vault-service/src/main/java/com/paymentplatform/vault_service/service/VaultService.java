package com.paymentplatform.vault_service.service;


import com.paymentplatform.common_lib.dto.PaymentProcessorResponse;
import com.paymentplatform.common_lib.entity.Money;
import com.paymentplatform.vault_service.dto.request.TokenizeRequest;
import com.paymentplatform.vault_service.dto.response.TokenizeResponse;

import java.util.Map;
import java.util.UUID;

public interface VaultService {

    TokenizeResponse tokenize(TokenizeRequest request, UUID merchantId);

    PaymentProcessorResponse charge(UUID paymentId, UUID merchantId, String token, Money amount, Map<String, Object> methodDetails);
}
