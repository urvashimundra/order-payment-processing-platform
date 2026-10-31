package com.paymentplatform.payment_service.saga;

import com.paymentplatform.common_lib.enums.EventAggregateType;
import com.paymentplatform.common_lib.enums.OrderStatus;
import com.paymentplatform.common_lib.enums.PaymentEvent;
import com.paymentplatform.common_lib.enums.PaymentStatus;
import com.paymentplatform.common_lib.exception.BusinessRuleViolationException;
import com.paymentplatform.common_lib.exception.ResourceNotFoundException;
import com.paymentplatform.payment_service.dto.request.PaymentInitRequest;
import com.paymentplatform.payment_service.dto.response.PaymentResponse;
import com.paymentplatform.payment_service.entity.OrderRecord;
import com.paymentplatform.payment_service.entity.Payment;
import com.paymentplatform.payment_service.gateway.dto.PaymentResult;
import com.paymentplatform.payment_service.mapper.PaymentMapper;
import com.paymentplatform.payment_service.outbox.OutboxEventPublisher;
import com.paymentplatform.payment_service.repository.OrderRepository;
import com.paymentplatform.payment_service.repository.PaymentRepository;
import com.paymentplatform.payment_service.statemachine.PaymentTransitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentAuthorizationRecorder {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentTransitionService paymentTransitionService;
    private final OutboxEventPublisher eventPublisher;
    private final PaymentMapper paymentMapper;

    @Transactional
    public Payment recordPayment(UUID merchantId, PaymentInitRequest request, String idempotencyKey) {
        OrderRecord order = orderRepository.findByIdAndMerchantIdForUpdate(request.orderId(), merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", request.orderId()));

        if(order.getOrderStatus() != OrderStatus.CREATED && order.getOrderStatus() != OrderStatus.ATTEMPTED) {
            throw new BusinessRuleViolationException("ORDER_NOT_PAYABLE",
                    "Order cannot accept payment in status: "+order.getOrderStatus());
        }

        // FIX: expired orders could still be paid
        if (order.getExpiresAt() != null && order.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
            throw new BusinessRuleViolationException("ORDER_EXPIRED", "Order has expired");
        }

        order.setOrderStatus(OrderStatus.ATTEMPTED);
        order.setAttempts(order.getAttempts()+1);

        Payment payment = Payment.builder()
                .order(order)
                .merchantId(merchantId)
                .amount(order.getAmount())
                .status(PaymentStatus.CREATED)
                .method(request.method())
                .idempotencyKey(idempotencyKey != null ? idempotencyKey: UUID.randomUUID().toString()) //TODO: idempotency
                .methodDetails(request.methodDetails())
                .build();
        payment = paymentRepository.save(payment);
        paymentTransitionService.apply(payment, PaymentEvent.AUTHORIZE_ATTEMPT);
        return payment;
    }

    @Transactional(readOnly = true)
    public Optional<PaymentResponse> findExistingAttempt(UUID merchantId, String idempotencyKey) {
        return paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                .map(paymentMapper::toResponse);
    }

    @Transactional
    public PaymentResponse compensateAuthorizationFailure(UUID paymentId, String errorCode,
                                                          String errorDescription) {
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        paymentTransitionService.apply(payment, PaymentEvent.AUTHORIZE_FAIL);
        payment.setErrorCode(errorCode);
        payment.setErrorDescription(errorDescription);
        payment.setFailedAt(java.time.LocalDateTime.now());
        payment = paymentRepository.save(payment);

        publishStatusEvent(payment, "PAYMENT_AUTHORIZATION_COMPENSATED");
        return paymentMapper.toResponse(payment);
    }

    @Transactional
    public PaymentResponse applyGatewayResult(UUID paymentId, PaymentResult result) {
        log.info("Applying Gateway result for paymentId: {}", paymentId);
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        switch (result) {
            case PaymentResult.Pending pending -> payment.setProcessorReference(pending.registrationRef());
            case PaymentResult.Failure failure -> {
                paymentTransitionService.apply(payment, PaymentEvent.AUTHORIZE_FAIL);
                payment.setErrorCode(failure.errorCode());
                payment.setErrorDescription(failure.errorDescription());
                payment.setFailedAt(java.time.LocalDateTime.now());
            }
            case PaymentResult.Success success ->
                    log.warn("Invalid state: initiate() gateway call returned Success directly, paymentId={}", paymentId);
        }

        payment = paymentRepository.save(payment);
        publishStatusEvent(payment, "PAYMENT_CREATED");
        log.info("Successfully applied Gateway result for paymentId: {}", paymentId);
        return paymentMapper.toResponse(payment);
    }

    private void publishStatusEvent(Payment payment, String eventType) {
        eventPublisher.publish(EventAggregateType.PAYMENT, payment.getId(), eventType,
                Map.of("orderId", payment.getOrder().getId().toString(),
                        "paymentId", payment.getId().toString(),
                        "merchantId", payment.getMerchantId().toString(),
                        "paymentStatus", payment.getStatus().name(),
                        "amountUnits", payment.getAmount().getAmountUnits(),
                        "amountCurrency", payment.getAmount().getCurrency(),
                        "paymentMethod", payment.getMethod()
                ));
    }
}





















