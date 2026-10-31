package com.paymentplatform.payment_service.mapper;

import com.paymentplatform.common_lib.enums.OrderStatus;
import com.paymentplatform.payment_service.dto.response.OrderResponse;
import com.paymentplatform.payment_service.entity.OrderRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OrderMapperTest {

    @Test
    void orderStatusIsCopiedToTheResponse() {
        // regression: "status" was always null in order responses
        OrderRecord order = OrderRecord.builder().id(UUID.randomUUID()).orderStatus(OrderStatus.PAID).build();

        OrderResponse response = new OrderMapperImpl().toResponse(order);

        assertThat(response.status()).isEqualTo(OrderStatus.PAID);
        assertThat(response.id()).isEqualTo(order.getId());
    }
}
