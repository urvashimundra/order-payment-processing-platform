package com.paymentplatform.common_lib.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignerUtilTest {

    private final SignerUtil signer = new SignerUtil();

    @Test
    void matchesTheKnownHmacSha256TestVector() {
        // Standard HMAC-SHA256 test vector (RFC-style example)
        String sig = signer.sign("The quick brown fox jumps over the lazy dog", "key");
        assertThat(sig).isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void sameInputGivesSameSignatureAndDifferentSecretGivesDifferentOne() {
        assertThat(signer.sign("payload", "secret")).isEqualTo(signer.sign("payload", "secret"));
        assertThat(signer.sign("payload", "secret")).isNotEqualTo(signer.sign("payload", "other"));
        assertThat(signer.sign("payload", "secret")).isNotEqualTo(signer.sign("payload2", "secret"));
    }
}
