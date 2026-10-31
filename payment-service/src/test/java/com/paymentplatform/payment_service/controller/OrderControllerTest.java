package com.paymentplatform.payment_service.controller;

import com.paymentplatform.common_lib.context.MerchantContext;
import com.paymentplatform.common_lib.entity.Money;
import com.paymentplatform.common_lib.exception.BusinessRuleViolationException;
import com.paymentplatform.payment_service.dto.request.CreateOrderRequest;
import com.paymentplatform.payment_service.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OrderControllerTest {

    private OrderService orderService;
    private MerchantContext merchantContext;
    private OrderController controller;
    private UUID merchantId;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        merchantContext = new MerchantContext();
        merchantId = UUID.randomUUID();
        merchantContext.setMerchantId(merchantId);
        controller = new OrderController(merchantContext, orderService);
    }

    private static CreateOrderRequest order(Money amount, LocalDateTime expiresAt) {
        return new CreateOrderRequest(amount, "rcpt-1", null, expiresAt, null);
    }

    @Test
    void zeroAmountIsRejectedBeforeTouchingTheService() {
        assertThatThrownBy(() -> controller.create(order(Money.inr(0), null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("greater than zero");
        verifyNoInteractions(orderService);
    }

    @Test
    void negativeAmountIsRejected() {
        assertThatThrownBy(() -> controller.create(order(Money.inr(-5), null)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void nonInrCurrencyIsRejected() {
        assertThatThrownBy(() -> controller.create(order(Money.of(100, "USD"), null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("INR");
    }

    @Test
    void expiryInThePastIsRejected() {
        assertThatThrownBy(() -> controller.create(order(Money.inr(100), LocalDateTime.now().minusDays(1))))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void validOrderIsPassedToTheServiceWithTheAuthenticatedMerchant() {
        CreateOrderRequest request = order(Money.inr(50000), LocalDateTime.now().plusHours(1));
        var response = controller.create(request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        verify(orderService).create(merchantId, request);
    }

    @Test
    void getOrderCancelAndListPaymentsAreNowExposed() {
        UUID orderId = UUID.randomUUID();
        controller.getById(orderId);
        controller.listPayments(orderId);
        controller.cancel(orderId);
        verify(orderService).getById(merchantId, orderId);
        verify(orderService).listPayments(merchantId, orderId);
        verify(orderService).cancel(merchantId, orderId);
    }
}
