package com.paymentplatform.common_lib.config;

import org.springframework.security.crypto.encrypt.AesBytesEncryptor;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.security.crypto.keygen.KeyGenerators;

import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

public class AesEncryptionConfig {

    public BytesEncryptor masterKeyEncryptor(String masterKey) {
        byte[] masterKeyBytes = Base64.getDecoder().decode(masterKey);
        if (masterKeyBytes.length != 16 && masterKeyBytes.length != 24 && masterKeyBytes.length != 32) {
            throw new IllegalStateException("Encryption key must decode (Base64) to 16, 24 or 32 bytes but was "
                    + masterKeyBytes.length + " bytes. Generate one with: openssl rand -base64 32");
        }
        // FIX: the JCE key algorithm must be "AES" (not the cipher transformation string)
        SecretKeySpec masterDecKey = new SecretKeySpec(masterKeyBytes, "AES");
        return new AesBytesEncryptor(masterDecKey, KeyGenerators.secureRandom(12),
                AesBytesEncryptor.CipherAlgorithm.GCM);
    }
}
