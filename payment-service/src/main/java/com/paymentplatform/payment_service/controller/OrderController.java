package com.paymentplatform.payment_service.controller;

import com.paymentplatform.common_lib.context.MerchantContext;
import com.paymentplatform.payment_service.dto.request.CreateOrderRequest;
import com.paymentplatform.payment_service.dto.response.OrderResponse;
import com.paymentplatform.payment_service.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.paymentplatform.common_lib.exception.BusinessRuleViolationException;
import com.paymentplatform.payment_service.dto.response.PaymentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final MerchantContext merchantContext;

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<OrderResponse> create(@RequestBody @Valid CreateOrderRequest request) {
        validateAmountAndExpiry(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(orderService.create(merchantContext.getMerchantId(), request));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getById(@PathVariable java.util.UUID orderId) {
        return ResponseEntity.ok(orderService.getById(merchantContext.getMerchantId(), orderId));
    }

    @GetMapping("/{orderId}/payments")
    public ResponseEntity<java.util.List<PaymentResponse>> listPayments(@PathVariable java.util.UUID orderId) {
        return ResponseEntity.ok(orderService.listPayments(merchantContext.getMerchantId(), orderId));
    }

    @PostMapping("/{orderId}/cancel")
    public ResponseEntity<OrderResponse> cancel(@PathVariable java.util.UUID orderId) {
        return ResponseEntity.ok(orderService.cancel(merchantContext.getMerchantId(), orderId));
    }

    /** FIX: zero / negative amounts, odd currencies and already-expired orders used to be accepted. */
    private void validateAmountAndExpiry(CreateOrderRequest request) {
        if (request.amount().getAmountUnits() <= 0) {
            throw new BusinessRuleViolationException("INVALID_AMOUNT", "Amount must be greater than zero");
        }
        if (!"INR".equals(request.amount().getCurrency())) {
            throw new BusinessRuleViolationException("UNSUPPORTED_CURRENCY", "Only INR is supported");
        }
        if (request.expiresAt() != null && request.expiresAt().isBefore(java.time.LocalDateTime.now())) {
            throw new BusinessRuleViolationException("INVALID_EXPIRY", "expiresAt must be in the future");
        }
    }
}
