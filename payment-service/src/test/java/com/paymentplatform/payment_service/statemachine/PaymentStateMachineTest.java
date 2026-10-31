package com.paymentplatform.payment_service.statemachine;

import com.paymentplatform.common_lib.enums.PaymentEvent;
import com.paymentplatform.common_lib.enums.PaymentStatus;
import com.paymentplatform.common_lib.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentStateMachineTest {

    private final PaymentStateMachine machine = new PaymentStateMachine();

    @Test
    void happyPathCreatedToSettled() {
        PaymentStatus s = PaymentStatus.CREATED;
        s = machine.transition(s, PaymentEvent.AUTHORIZE_ATTEMPT);
        assertThat(s).isEqualTo(PaymentStatus.AUTHORIZING);
        s = machine.transition(s, PaymentEvent.AUTHORIZE_SUCCESS);
        assertThat(s).isEqualTo(PaymentStatus.AUTHORIZED);
        s = machine.transition(s, PaymentEvent.CAPTURE_REQUEST);
        assertThat(s).isEqualTo(PaymentStatus.CAPTURING);
        s = machine.transition(s, PaymentEvent.CAPTURE_SUCCESS);
        assertThat(s).isEqualTo(PaymentStatus.CAPTURED);
        s = machine.transition(s, PaymentEvent.SETTLE);
        assertThat(s).isEqualTo(PaymentStatus.SETTLED);
    }

    @Test
    void failedAuthorizationEndsInFailed() {
        assertThat(machine.transition(PaymentStatus.AUTHORIZING, PaymentEvent.AUTHORIZE_FAIL))
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void failedCaptureFallsBackToAuthorized() {
        assertThat(machine.transition(PaymentStatus.CAPTURING, PaymentEvent.CAPTURE_FAIL))
                .isEqualTo(PaymentStatus.AUTHORIZED);
    }

    @Test
    void illegalTransitionsAreRejected() {
        assertThatThrownBy(() -> machine.transition(PaymentStatus.AUTHORIZING, PaymentEvent.CAPTURE_REQUEST))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> machine.transition(PaymentStatus.FAILED, PaymentEvent.AUTHORIZE_ATTEMPT))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThatThrownBy(() -> machine.transition(PaymentStatus.CREATED, PaymentEvent.SETTLE))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void aCapturedPaymentCannotBeCapturedAgain() {
        assertThatThrownBy(() -> machine.transition(PaymentStatus.CAPTURED, PaymentEvent.CAPTURE_REQUEST))
                .isInstanceOf(InvalidStateTransitionException.class);
    }
}
