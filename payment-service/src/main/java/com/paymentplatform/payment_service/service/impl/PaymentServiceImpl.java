package com.paymentplatform.payment_service.service.impl;

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
import com.paymentplatform.payment_service.gateway.PaymentGatewayRouter;
import com.paymentplatform.payment_service.gateway.dto.PaymentRequest;
import com.paymentplatform.payment_service.gateway.dto.PaymentResult;
import com.paymentplatform.payment_service.mapper.PaymentMapper;
import com.paymentplatform.payment_service.outbox.OutboxEventPublisher;
import com.paymentplatform.payment_service.repository.OrderRepository;
import com.paymentplatform.payment_service.repository.PaymentRepository;
import com.paymentplatform.payment_service.saga.PaymentAuthorizationRecorder;
import com.paymentplatform.payment_service.service.PaymentService;
import com.paymentplatform.payment_service.statemachine.PaymentTransitionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGatewayRouter paymentGatewayRouter;
    private final PaymentMapper paymentMapper;
    private final PaymentTransitionService paymentTransitionService;
    private final OutboxEventPublisher eventPublisher;
    private final PaymentAuthorizationRecorder paymentAuthorizationRecorder;

    @Override
    public PaymentResponse initiate(UUID merchantId, PaymentInitRequest request, String idempotencyKey) {

        validateMethodDetails(request);

        if (idempotencyKey != null) {
            var existing = paymentAuthorizationRecorder.findExistingAttempt(merchantId, idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotency replay for paymentId: {}", existing.get().id());
                return existing.get();
            }
        }

        Payment payment = paymentAuthorizationRecorder.recordPayment(merchantId, request, idempotencyKey);

        PaymentRequest paymentRequest = new PaymentRequest(payment.getId(),
                request.orderId(), merchantId,
                payment.getAmount(), request.method(),
                request.methodDetails());

        PaymentResult result;
        try {
            result = paymentGatewayRouter.initiate(paymentRequest);
        } catch (Exception e) {
            log.error("Payment gateway call failed for paymentId={}, method={}", payment.getId(), request.method(), e);
            return paymentAuthorizationRecorder.compensateAuthorizationFailure(payment.getId(),
                    "PAYMENT_GATEWAY_ROUTER_UNREACHABLE", e.getMessage());
        }

        return paymentAuthorizationRecorder.applyGatewayResult(payment.getId(), result);
    }


    /** FIX: missing card token / bank used to end as a NullPointerException deep inside the processors. */
    private void validateMethodDetails(PaymentInitRequest request) {
        var details = request.methodDetails();
        if (request.method() == com.paymentplatform.common_lib.enums.PaymentMethod.CARD
                && (details == null || details.get("token") == null)) {
            throw new com.paymentplatform.common_lib.exception.BusinessRuleViolationException(
                    "INVALID_PAYMENT_DETAILS", "CARD payments require methodDetails.token");
        }
        if (request.method() == com.paymentplatform.common_lib.enums.PaymentMethod.NETBANKING
                && (details == null || details.get("bank") == null)) {
            throw new com.paymentplatform.common_lib.exception.BusinessRuleViolationException(
                    "INVALID_PAYMENT_DETAILS", "NETBANKING payments require methodDetails.bank");
        }
    }

    @Override
    @Transactional
    public PaymentResponse capture(UUID merchantId, UUID paymentId) {

//        Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
//                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        Payment payment = paymentRepository.findByIdAndMerchantIdForUpdate(paymentId, merchantId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_REQUEST);

        PaymentResult paymentResult = paymentGatewayRouter.capture(payment.getMethod(), paymentId);

        if(paymentResult instanceof  PaymentResult.Success success) {
            paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_SUCCESS);
            payment.setCapturedAt(LocalDateTime.now());
            // FIX: a manual capture left the order un-PAID (only the auto-capture path set it)
            OrderRecord paidOrder = payment.getOrder();
            paidOrder.setOrderStatus(OrderStatus.PAID);
            orderRepository.save(paidOrder);
            log.info("Payment captured, paymentID: {}", paymentId);
        } else if(paymentResult instanceof  PaymentResult.Failure failure) {
            paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_FAIL);
            payment.setErrorCode(failure.errorCode());
            payment.setErrorDescription(failure.errorDescription());
            log.warn("Payment capture failed, paymentID: {}", paymentId);
        }

        payment = paymentRepository.save(payment);

        eventPublisher.publish(EventAggregateType.PAYMENT, payment.getId(), "PAYMENT_STATUS_CHANGED",
                Map.of("orderId", payment.getOrder().getId().toString(),
                        "paymentId", payment.getId().toString(),
                        "merchantId", merchantId.toString(),
                        "paymentStatus", payment.getStatus().name(),
                        "amountUnits", payment.getAmount().getAmountUnits(),
                        "amountCurrency", payment.getAmount().getCurrency(),
                        "paymentMethod", payment.getMethod()
                )
        );

        return paymentMapper.toResponse(payment);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID merchantId, UUID paymentId) {
        Payment payment = paymentRepository
                .findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Payment", paymentId));

        return paymentMapper.toResponse(payment);
    }

    @Override
    @Transactional
    public void resolveAuthorization(UUID paymentId, boolean approve,
                                     String bankRef, String errorCode, String errorDescription) {

//        Payment payment = paymentRepository.findById(paymentId)
//                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        if (payment.getStatus() != PaymentStatus.AUTHORIZING) {
            log.warn("Payment is not in Authorizing state, paymentID: {}, status: {}", paymentId, payment.getStatus());
            return;
        }

        OrderRecord orderRecord = payment.getOrder();

        if (approve) {
            paymentTransitionService.apply(payment, PaymentEvent.AUTHORIZE_SUCCESS);
            payment.setBankReference(bankRef);
            payment.setAuthorizedAt(LocalDateTime.now());

            // Auto-capture
            paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_REQUEST);
            PaymentResult captureResult = paymentGatewayRouter.capture(payment.getMethod(), paymentId);

            if(captureResult instanceof PaymentResult.Success success) {
                paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_SUCCESS);
                payment.setCapturedAt(LocalDateTime.now());
                orderRecord.setOrderStatus(OrderStatus.PAID);
            } else if (captureResult instanceof  PaymentResult.Failure failure){
                paymentTransitionService.apply(payment, PaymentEvent.CAPTURE_FAIL);
                payment.setErrorCode(failure.errorCode());
                payment.setErrorDescription(failure.errorDescription());
            }
        } else {
            paymentTransitionService.apply(payment, PaymentEvent.AUTHORIZE_FAIL);
            payment.setErrorCode(errorCode);
            payment.setErrorDescription(errorDescription);
            payment.setFailedAt(LocalDateTime.now());
        }

        paymentRepository.save(payment);
        orderRepository.save(orderRecord);

        eventPublisher.publish(EventAggregateType.PAYMENT, payment.getId(), "PAYMENT_STATUS_CHANGED",
                Map.of("orderId", payment.getOrder().getId().toString(),
                        "paymentId", payment.getId().toString(),
                        "merchantId", payment.getMerchantId().toString(),
                        "paymentStatus", payment.getStatus().name(),
                        "amountUnits", payment.getAmount().getAmountUnits(),
                        "amountCurrency", payment.getAmount().getCurrency(),
                        "paymentMethod", payment.getMethod()
                )
        );
    }
}











