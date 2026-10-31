package com.paymentplatform.payment_service.dto;

import com.paymentplatform.common_lib.dto.PaymentProcessorResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentProcessorResponseJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void everyVariantSurvivesTheJsonRoundTripBetweenVaultAndPaymentService() {
        // regression: card payments failed because the interface could not be deserialised
        PaymentProcessorResponse[] samples = {
                new PaymentProcessorResponse.Pending("ref-1"),
                new PaymentProcessorResponse.Success("ref-2", "bank-2"),
                new PaymentProcessorResponse.Failure("CARD_DECLINED", "declined")
        };
        for (PaymentProcessorResponse sample : samples) {
            String json = mapper.writeValueAsString(sample);
            PaymentProcessorResponse back = mapper.readValue(json, PaymentProcessorResponse.class);
            assertThat(back).isEqualTo(sample);
        }
    }
}
