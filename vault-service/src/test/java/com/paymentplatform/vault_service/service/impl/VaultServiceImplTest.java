package com.paymentplatform.vault_service.service.impl;

import com.paymentplatform.common_lib.config.AesEncryptionConfig;
import com.paymentplatform.common_lib.dto.PaymentProcessorResponse;
import com.paymentplatform.common_lib.entity.Money;
import com.paymentplatform.vault_service.dto.request.TokenizeRequest;
import com.paymentplatform.vault_service.dto.response.TokenizeResponse;
import com.paymentplatform.vault_service.entity.CardToken;
import com.paymentplatform.vault_service.entity.VaultCard;
import com.paymentplatform.vault_service.processor.CardPaymentProcessor;
import com.paymentplatform.vault_service.repository.CardTokenRepository;
import com.paymentplatform.vault_service.repository.VaultCardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.encrypt.BytesEncryptor;

import java.time.Year;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VaultServiceImplTest {

    private CardTokenRepository cardTokenRepository;
    private VaultCardRepository vaultCardRepository;
    private VaultServiceImpl service;
    private final UUID merchantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        cardTokenRepository = mock(CardTokenRepository.class);
        vaultCardRepository = mock(VaultCardRepository.class);
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) (i + 7);
        BytesEncryptor master = new AesEncryptionConfig().masterKeyEncryptor(Base64.getEncoder().encodeToString(key));
        service = new VaultServiceImpl(cardTokenRepository, vaultCardRepository, master, new CardPaymentProcessor());

        when(vaultCardRepository.save(any(VaultCard.class))).thenAnswer(i -> i.getArgument(0));
        when(cardTokenRepository.save(any(CardToken.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** Tokenizes a card and wires the repository so the new token can be found again. */
    private String tokenize(String pan) {
        TokenizeResponse response = service.tokenize(
                new TokenizeRequest(pan, "123", 12, Year.now().getValue() + 2, null, "Test User"), merchantId);
        ArgumentCaptor<CardToken> saved = ArgumentCaptor.forClass(CardToken.class);
        verify(cardTokenRepository).save(saved.capture());
        when(cardTokenRepository.findByTokenAndRevokedAtIsNull(response.token())).thenReturn(Optional.of(saved.getValue()));
        return response.token();
    }

    @Test
    void tokenizeNeverReturnsTheRealCardNumber() {
        TokenizeResponse response = service.tokenize(
                new TokenizeRequest("4111111111111111", "123", 12, Year.now().getValue() + 2, null, "Test User"), merchantId);
        assertThat(response.token()).startsWith("tok_");
        assertThat(response.lastFour()).isEqualTo("1111");
        assertThat(response.toString()).doesNotContain("4111111111111111");
    }

    @Test
    void ownerCanChargeItsOwnToken() {
        String token = tokenize("4111111111111111");
        var result = service.charge(UUID.randomUUID(), merchantId, token, Money.inr(50000), Map.of());
        assertThat(result).isInstanceOf(PaymentProcessorResponse.Pending.class);
    }

    @Test
    void anotherMerchantCannotUseTheToken() {
        // regression (security): any merchant could charge any other merchant's token
        String token = tokenize("4111111111111111");
        var result = service.charge(UUID.randomUUID(), UUID.randomUUID(), token, Money.inr(50000), Map.of());
        assertThat(result).isInstanceOf(PaymentProcessorResponse.Failure.class);
        assertThat(((PaymentProcessorResponse.Failure) result).errorCode()).isEqualTo("CARD_TOKEN_INVALID");
    }

    @Test
    void unknownTokenIsAFailureNotAnException() {
        when(cardTokenRepository.findByTokenAndRevokedAtIsNull("tok_unknown")).thenReturn(Optional.empty());
        var result = service.charge(UUID.randomUUID(), merchantId, "tok_unknown", Money.inr(100), Map.of());
        assertThat(((PaymentProcessorResponse.Failure) result).errorCode()).isEqualTo("CARD_TOKEN_INVALID");
    }

    @Test
    void theMagicDeclineCardIsDeclined() {
        String token = tokenize("4000000000000002");
        var result = service.charge(UUID.randomUUID(), merchantId, token, Money.inr(100), Map.of());
        assertThat(((PaymentProcessorResponse.Failure) result).errorCode()).isEqualTo("CARD_DECLINED");
    }
}
