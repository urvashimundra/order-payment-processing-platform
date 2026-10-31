package com.paymentplatform.payment_service.service.impl;

import com.paymentplatform.common_lib.enums.PaymentMethod;
import com.paymentplatform.common_lib.exception.BusinessRuleViolationException;
import com.paymentplatform.payment_service.dto.request.PaymentInitRequest;
import com.paymentplatform.payment_service.gateway.PaymentGatewayRouter;
import com.paymentplatform.payment_service.mapper.PaymentMapper;
import com.paymentplatform.payment_service.outbox.OutboxEventPublisher;
import com.paymentplatform.payment_service.repository.OrderRepository;
import com.paymentplatform.payment_service.repository.PaymentRepository;
import com.paymentplatform.payment_service.saga.PaymentAuthorizationRecorder;
import com.paymentplatform.payment_service.statemachine.PaymentTransitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PaymentServiceImplTest {

    private PaymentAuthorizationRecorder recorder;
    private PaymentServiceImpl service;

    @BeforeEach
    void setUp() {
        recorder = mock(PaymentAuthorizationRecorder.class);
        service = new PaymentServiceImpl(
                mock(OrderRepository.class),
                mock(PaymentRepository.class),
                mock(PaymentGatewayRouter.class),
                mock(PaymentMapper.class),
                mock(PaymentTransitionService.class),
                mock(OutboxEventPublisher.class),
                recorder);
    }

    @Test
    void cardPaymentWithoutATokenIsRejectedUpFront() {
        var request = new PaymentInitRequest(UUID.randomUUID(), PaymentMethod.CARD, null);
        assertThatThrownBy(() -> service.initiate(UUID.randomUUID(), request, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("token");
        verifyNoInteractions(recorder);
    }

    @Test
    void netBankingWithoutABankIsRejectedUpFront() {
        var request = new PaymentInitRequest(UUID.randomUUID(), PaymentMethod.NETBANKING, Map.of());
        assertThatThrownBy(() -> service.initiate(UUID.randomUUID(), request, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("bank");
        verifyNoInteractions(recorder);
    }
}
