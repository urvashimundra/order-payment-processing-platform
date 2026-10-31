package com.paymentplatform.common_lib.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.BytesEncryptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesEncryptionConfigTest {

    private static String base64Key(int bytes) {
        byte[] key = new byte[bytes];
        for (int i = 0; i < bytes; i++) key[i] = (byte) (i + 1);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void encryptsAndDecryptsRoundTrip() {
        BytesEncryptor encryptor = new AesEncryptionConfig().masterKeyEncryptor(base64Key(32));
        byte[] plain = "webhook-secret-123".getBytes(StandardCharsets.UTF_8);

        byte[] cipher = encryptor.encrypt(plain);

        assertThat(cipher).isNotEqualTo(plain);
        assertThat(new String(encryptor.decrypt(cipher), StandardCharsets.UTF_8)).isEqualTo("webhook-secret-123");
    }

    @Test
    void sameInputEncryptsDifferentlyEachTimeBecauseOfRandomIv() {
        BytesEncryptor encryptor = new AesEncryptionConfig().masterKeyEncryptor(base64Key(32));
        byte[] plain = "abc".getBytes(StandardCharsets.UTF_8);
        assertThat(encryptor.encrypt(plain)).isNotEqualTo(encryptor.encrypt(plain));
    }

    @Test
    void rejectsKeysWithAnInvalidLength() {
        assertThatThrownBy(() -> new AesEncryptionConfig().masterKeyEncryptor(base64Key(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16, 24 or 32 bytes");
    }
}
