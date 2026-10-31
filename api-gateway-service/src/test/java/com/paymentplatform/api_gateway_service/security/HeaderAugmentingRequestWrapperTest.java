package com.paymentplatform.api_gateway_service.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class HeaderAugmentingRequestWrapperTest {

    @Test
    void blockedClientHeaderIsInvisibleAndVerifiedHeaderWins() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("x-merchant-id", "attacker");     // note: lower-case, as a client could send it
        request.addHeader("X-Request-Id", "keep-me");

        HeaderAugmentingRequestWrapper wrapper = new HeaderAugmentingRequestWrapper(request);
        wrapper.blockHeader("X-Merchant-Id");
        wrapper.putHeader("X-Merchant-Id", "verified");

        assertThat(wrapper.getHeader("X-Merchant-Id")).isEqualTo("verified");
        assertThat(wrapper.getHeader("x-merchant-id")).isEqualTo("verified");
        assertThat(Collections.list(wrapper.getHeaders("X-Merchant-Id"))).containsExactly("verified");
        assertThat(wrapper.getHeader("X-Request-Id")).isEqualTo("keep-me");
    }

    @Test
    void blockedHeaderThatIsNotReplacedDisappearsCompletely() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Role", "ADMIN");

        HeaderAugmentingRequestWrapper wrapper = new HeaderAugmentingRequestWrapper(request);
        wrapper.blockHeader("X-User-Role");

        assertThat(wrapper.getHeader("X-User-Role")).isNull();
        assertThat(Collections.list(wrapper.getHeaders("X-User-Role"))).isEmpty();
        assertThat(Collections.list(wrapper.getHeaderNames())).noneMatch(n -> n.equalsIgnoreCase("X-User-Role"));
    }
}
