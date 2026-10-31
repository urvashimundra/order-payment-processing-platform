package com.paymentplatform.payment_service.processor.strategy;

import com.paymentplatform.common_lib.dto.PaymentProcessorRequest;
import com.paymentplatform.common_lib.dto.PaymentProcessorResponse;
import com.paymentplatform.common_lib.entity.Money;
import com.paymentplatform.common_lib.enums.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessorsTest {

    private static PaymentProcessorRequest nonCard(PaymentMethod method, Map<String, Object> details) {
        return PaymentProcessorRequest.nonCard(UUID.randomUUID(), method, Money.inr(50000), details);
    }

    // ---- UPI
    @Test
    void upiWithoutAnyDetailsDoesNotCrash() {
        // regression: a missing "vpa" caused a NullPointerException (the old "UPI_FAILED" payments)
        var processor = new UpiPaymentProcessor();
        assertThat(processor.charge(nonCard(PaymentMethod.UPI, null))).isInstanceOf(PaymentProcessorResponse.Pending.class);
        assertThat(processor.charge(nonCard(PaymentMethod.UPI, new HashMap<>()))).isInstanceOf(PaymentProcessorResponse.Pending.class);
    }

    @Test
    void upiNormalVpaIsPendingAndTheMagicFailVpaIsRejected() {
        var processor = new UpiPaymentProcessor();
        assertThat(processor.charge(nonCard(PaymentMethod.UPI, Map.of("vpa", "test@upi"))))
                .isInstanceOf(PaymentProcessorResponse.Pending.class);
        var failure = processor.charge(nonCard(PaymentMethod.UPI, Map.of("vpa", "fail@okaxis")));
        assertThat(failure).isInstanceOf(PaymentProcessorResponse.Failure.class);
        assertThat(((PaymentProcessorResponse.Failure) failure).errorCode()).isEqualTo("UPI_REJECTED");
    }

    // ---- Net banking
    @Test
    void netBankingWithoutBankDoesNotCrash() {
        var processor = new NetBankingPaymentProcessor();
        assertThat(processor.charge(nonCard(PaymentMethod.NETBANKING, new HashMap<>())))
                .isInstanceOf(PaymentProcessorResponse.Pending.class);
    }

    @Test
    void netBankingMagicBankIsRejected() {
        var processor = new NetBankingPaymentProcessor();
        var result = processor.charge(nonCard(PaymentMethod.NETBANKING, Map.of("bank", "BANK_CODE_FAIL")));
        assertThat(result).isInstanceOf(PaymentProcessorResponse.Failure.class);
    }

    // ---- Card
    @Test
    void cardMagicNumbersAreDeclinedOrExpiredAndOthersArePending() {
        var processor = new CardPaymentProcessor();
        var declined = processor.charge(PaymentProcessorRequest.card(UUID.randomUUID(), "4000000000000002", "12/2030", Money.inr(100), Map.of()));
        var expired = processor.charge(PaymentProcessorRequest.card(UUID.randomUUID(), "4000000000000069", "12/2030", Money.inr(100), Map.of()));
        var ok = processor.charge(PaymentProcessorRequest.card(UUID.randomUUID(), "4111111111111111", "12/2030", Money.inr(100), Map.of()));
        assertThat(((PaymentProcessorResponse.Failure) declined).errorCode()).isEqualTo("CARD_DECLINED");
        assertThat(((PaymentProcessorResponse.Failure) expired).errorCode()).isEqualTo("CARD_EXPIRED");
        assertThat(ok).isInstanceOf(PaymentProcessorResponse.Pending.class);
    }
}
