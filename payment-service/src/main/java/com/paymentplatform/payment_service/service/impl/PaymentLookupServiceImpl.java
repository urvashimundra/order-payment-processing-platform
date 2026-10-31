package com.paymentplatform.payment_service.service.impl;

import com.paymentplatform.common_lib.dto.PaymentSettlementView;
import com.paymentplatform.common_lib.enums.PaymentStatus;
import com.paymentplatform.payment_service.api.PaymentLookupService;
import com.paymentplatform.payment_service.entity.Payment;
import com.paymentplatform.payment_service.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentLookupServiceImpl implements PaymentLookupService {

    private final PaymentRepository paymentRepository;
    private final com.paymentplatform.payment_service.statemachine.PaymentTransitionService paymentTransitionService;

    @Override
    @Transactional
    public List<PaymentSettlementView> findUnsettledCapturedPayments(UUID merchantId) {
        List<Payment> paymentList = paymentRepository
                .findByMerchantIdAndStatusForUpdate(merchantId, PaymentStatus.CAPTURED);

        return paymentList.stream()
                .map(p -> new PaymentSettlementView(
                        p.getId(),
                        p.getAmount().getAmountUnits(),
                        0, // TODO: replace with actual refund values from RefundRepository
                        p.getAmount().getCurrency()))
                .toList();
    }

    @Override
    @Transactional
    public void markSettled(List<UUID> paymentList) {
        LocalDateTime now = LocalDateTime.now();
        List<Payment> payments = paymentRepository.findAllById(paymentList);
        for (Payment payment : payments) {
            // FIX: previously set SETTLED directly, skipping the state machine and the transition log
            if (payment.getStatus() != PaymentStatus.CAPTURED) {
                log.warn("Skipping settle for paymentId={} in status {}", payment.getId(), payment.getStatus());
                continue;
            }
            paymentTransitionService.apply(payment, com.paymentplatform.common_lib.enums.PaymentEvent.SETTLE);
            payment.setSettledAt(now);
        }
        paymentRepository.saveAll(payments);
    }
}













