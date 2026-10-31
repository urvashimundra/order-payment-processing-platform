package com.paymentplatform.merchant_service.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    private static JwtUtil withSecret(String secret) {
        JwtUtil util = new JwtUtil();
        ReflectionTestUtils.setField(util, "secretKey", secret);
        return util;
    }

    private static final String SECRET = "0123456789abcdef0123456789abcdef-test-secret";

    @Test
    void tokenCarriesMerchantIdAndRole() {
        UUID merchantId = UUID.randomUUID();
        JwtUtil util = withSecret(SECRET);

        String token = util.generateAccessToken("a@b.com", merchantId, "OWNER");
        Claims claims = util.verifyAccessToken(token);

        assertThat(claims.getSubject()).isEqualTo("a@b.com");
        assertThat(util.extractMerchantId(claims)).isEqualTo(merchantId.toString());
        assertThat(util.extractRole(claims)).isEqualTo("OWNER");
        assertThat(claims.getExpiration()).isAfter(new java.util.Date());
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        String token = withSecret(SECRET).generateAccessToken("a@b.com", UUID.randomUUID(), "OWNER");
        JwtUtil attacker = withSecret("another-secret-another-secret-another-secret");
        assertThatThrownBy(() -> attacker.verifyAccessToken(token)).isInstanceOf(Exception.class);
    }
}
