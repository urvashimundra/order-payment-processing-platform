package com.paymentplatform.merchant_service.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.lang.reflect.Method;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyControllerTest {

    @Test
    void revokeEndpointUsesAPathVariableNotTheLiteralTextKeyId() throws Exception {
        // regression: it used to be @DeleteMapping("/keyId"), so DELETE /v1/merchants/api-keys/<id> was a 404/405
        Method revoke = ApiKeyController.class.getMethod("revoke", UUID.class);
        DeleteMapping mapping = revoke.getAnnotation(DeleteMapping.class);
        assertThat(mapping.value()).containsExactly("/{keyId}");
    }
}
