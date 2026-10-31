package com.paymentplatform.common_lib.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void addsAndSubtractsInTheSameCurrency() {
        Money total = Money.inr(500).add(Money.inr(250));
        assertThat(total.getAmountUnits()).isEqualTo(750);
        assertThat(total.subtract(Money.inr(50)).getAmountUnits()).isEqualTo(700);
        assertThat(total.getCurrency()).isEqualTo("INR");
    }

    @Test
    void refusesToMixCurrencies() {
        assertThatThrownBy(() -> Money.inr(1).add(Money.of(1, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.inr(1).subtract(Money.of(1, "USD")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalMoneyIsEqual() {
        assertThat(Money.inr(100)).isEqualTo(Money.of(100, "INR"));
    }
}
