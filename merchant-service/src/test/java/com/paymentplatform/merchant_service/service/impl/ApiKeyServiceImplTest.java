package com.paymentplatform.merchant_service.service.impl;

import com.paymentplatform.common_lib.cache.ApiKeyCache;
import com.paymentplatform.common_lib.exception.BusinessRuleViolationException;
import com.paymentplatform.common_lib.exception.ResourceNotFoundException;
import com.paymentplatform.merchant_service.entity.ApiKey;
import com.paymentplatform.merchant_service.entity.Merchant;
import com.paymentplatform.merchant_service.mapper.ApiKeyMapper;
import com.paymentplatform.merchant_service.repository.ApiKeyRepository;
import com.paymentplatform.merchant_service.repository.MerchantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyServiceImplTest {

    private ApiKeyRepository apiKeyRepository;
    private ApiKeyCache apiKeyCache;
    private ApiKeyServiceImpl service;
    private UUID merchantId;
    private UUID keyUuid;

    @BeforeEach
    void setUp() {
        apiKeyRepository = mock(ApiKeyRepository.class);
        apiKeyCache = mock(ApiKeyCache.class);
        service = new ApiKeyServiceImpl(mock(MerchantRepository.class), apiKeyRepository, mock(ApiKeyMapper.class), apiKeyCache);
        merchantId = UUID.randomUUID();
        keyUuid = UUID.randomUUID();
    }

    private ApiKey key(boolean enabled) {
        return ApiKey.builder().id(keyUuid).keyId("pp_test_abc").enabled(enabled)
                .merchant(Merchant.builder().id(merchantId).build()).build();
    }

    @Test
    void rotatingADisabledKeyIsABusinessErrorNotAServerError() {
        when(apiKeyRepository.findById(keyUuid)).thenReturn(Optional.of(key(false)));
        assertThatThrownBy(() -> service.rotate(merchantId, keyUuid)).isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void revokeDisablesTheKeyAndEvictsItFromTheCache() {
        ApiKey key = key(true);
        when(apiKeyRepository.findById(keyUuid)).thenReturn(Optional.of(key));

        service.revoke(merchantId, keyUuid);

        assertThat(key.isEnabled()).isFalse();
        verify(apiKeyCache).evict("pp_test_abc");
    }

    @Test
    void aMerchantCannotRevokeAnotherMerchantsKey() {
        when(apiKeyRepository.findById(keyUuid)).thenReturn(Optional.of(key(true)));
        assertThatThrownBy(() -> service.revoke(UUID.randomUUID(), keyUuid)).isInstanceOf(ResourceNotFoundException.class);
    }
}
