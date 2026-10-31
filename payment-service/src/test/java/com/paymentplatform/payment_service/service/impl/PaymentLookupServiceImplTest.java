package com.paymentplatform.payment_service.service.impl;

import com.paymentplatform.common_lib.enums.PaymentEvent;
import com.paymentplatform.common_lib.enums.PaymentStatus;
import com.paymentplatform.payment_service.entity.Payment;
import com.paymentplatform.payment_service.repository.PaymentRepository;
import com.paymentplatform.payment_service.statemachine.PaymentTransitionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentLookupServiceImplTest {

    @Test
    void markSettledGoesThroughTheStateMachineAndOnlyTouchesCapturedPayments() {
        PaymentRepository repository = mock(PaymentRepository.class);
        PaymentTransitionService transitions = mock(PaymentTransitionService.class);
        var service = new PaymentLookupServiceImpl(repository, transitions);

        Payment captured = Payment.builder().id(UUID.randomUUID()).status(PaymentStatus.CAPTURED).build();
        Payment failed = Payment.builder().id(UUID.randomUUID()).status(PaymentStatus.FAILED).build();
        when(repository.findAllById(any())).thenReturn(List.of(captured, failed));

        service.markSettled(List.of(captured.getId(), failed.getId()));

        verify(transitions).apply(captured, PaymentEvent.SETTLE);
        verify(transitions, never()).apply(failed, PaymentEvent.SETTLE);
        assertThat(captured.getSettledAt()).isNotNull();
        assertThat(failed.getSettledAt()).isNull();
    }
}
