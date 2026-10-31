package com.paymentplatform.payment_service.config;

import com.paymentplatform.common_lib.enums.PaymentMethod;
import com.paymentplatform.payment_service.processor.PaymentProcessor;
import com.paymentplatform.payment_service.processor.strategy.CardPaymentProcessor;
import com.paymentplatform.payment_service.processor.strategy.NetBankingPaymentProcessor;
import com.paymentplatform.payment_service.processor.strategy.UpiPaymentProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
@RequiredArgsConstructor
public class PaymentProcessorConfig {

    private final CardPaymentProcessor cardPaymentProcessor;
    private final NetBankingPaymentProcessor netBankingPaymentProcessor;
    private final UpiPaymentProcessor upiPaymentProcessor;

    @Bean
    public Map<PaymentMethod, PaymentProcessor> paymentProcessorMap() {
        return Map.of(
                PaymentMethod.CARD, cardPaymentProcessor,
                PaymentMethod.NETBANKING, netBankingPaymentProcessor,
                PaymentMethod.UPI, upiPaymentProcessor
        );
    }
}
