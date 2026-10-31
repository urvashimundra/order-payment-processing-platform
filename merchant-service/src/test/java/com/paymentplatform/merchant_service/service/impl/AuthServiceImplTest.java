package com.paymentplatform.merchant_service.service.impl;

import com.paymentplatform.common_lib.enums.BusinessType;
import com.paymentplatform.common_lib.enums.MerchantStatus;
import com.paymentplatform.common_lib.enums.UserRole;
import com.paymentplatform.common_lib.exception.AuthenticationFailedException;
import com.paymentplatform.common_lib.exception.DuplicateResourceException;
import com.paymentplatform.merchant_service.dto.request.LoginRequest;
import com.paymentplatform.merchant_service.dto.request.MerchantSignupRequest;
import com.paymentplatform.merchant_service.dto.response.LoginResponse;
import com.paymentplatform.merchant_service.entity.AppUser;
import com.paymentplatform.merchant_service.entity.Merchant;
import com.paymentplatform.merchant_service.mapper.MerchantMapper;
import com.paymentplatform.merchant_service.repository.AppUserRepository;
import com.paymentplatform.merchant_service.repository.MerchantRepository;
import com.paymentplatform.merchant_service.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceImplTest {

    private AppUserRepository appUserRepository;
    private MerchantRepository merchantRepository;
    private MerchantMapper merchantMapper;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        appUserRepository = mock(AppUserRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        merchantMapper = mock(MerchantMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtUtil = mock(JwtUtil.class);
        service = new AuthServiceImpl(appUserRepository, merchantRepository, merchantMapper, passwordEncoder, jwtUtil);
    }

    @Test
    void unknownEmailAndWrongPasswordGiveTheSameError() {
        when(appUserRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        AppUser user = AppUser.builder().email("real@example.com").passwordHash("hash").role(UserRole.OWNER)
                .merchant(Merchant.builder().id(UUID.randomUUID()).build()).build();
        when(appUserRepository.findByEmail("real@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        Throwable unknownEmail = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.login(new LoginRequest("nobody@example.com", "whatever")));
        Throwable wrongPassword = org.assertj.core.api.Assertions.catchThrowable(
                () -> service.login(new LoginRequest("real@example.com", "wrong")));

        // regression: unknown email used to be a 404 "User not found" (reveals who has an account)
        assertThat(unknownEmail).isInstanceOf(AuthenticationFailedException.class);
        assertThat(wrongPassword).isInstanceOf(AuthenticationFailedException.class);
        assertThat(unknownEmail.getMessage()).isEqualTo(wrongPassword.getMessage());
    }

    @Test
    void loginIgnoresEmailCaseAndSurroundingSpaces() {
        UUID merchantId = UUID.randomUUID();
        AppUser user = AppUser.builder().email("user@example.com").passwordHash("hash").role(UserRole.OWNER)
                .merchant(Merchant.builder().id(merchantId).build()).build();
        when(appUserRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret123", "hash")).thenReturn(true);
        when(jwtUtil.generateAccessToken("user@example.com", merchantId, "OWNER")).thenReturn("the-token");

        LoginResponse response = service.login(new LoginRequest("  User@Example.COM ", "secret123"));

        assertThat(response.accessToken()).isEqualTo("the-token");
    }

    @Test
    void signupStoresTheEmailInLowerCaseAndStartsInPendingKyc() {
        when(merchantRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(merchantMapper.toEntityFromSignUpRequest(any())).thenReturn(new Merchant());
        when(merchantRepository.save(any(Merchant.class))).thenAnswer(i -> i.getArgument(0));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");

        service.signup(new MerchantSignupRequest("Alice", "Alice@Example.COM", "password123", "Shop", BusinessType.PROPRIETORSHIP));

        ArgumentCaptor<AppUser> savedUser = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).save(savedUser.capture());
        assertThat(savedUser.getValue().getEmail()).isEqualTo("alice@example.com");
        assertThat(savedUser.getValue().getRole()).isEqualTo(UserRole.OWNER);

        ArgumentCaptor<Merchant> savedMerchant = ArgumentCaptor.forClass(Merchant.class);
        verify(merchantRepository).save(savedMerchant.capture());
        assertThat(savedMerchant.getValue().getEmail()).isEqualTo("alice@example.com");
        assertThat(savedMerchant.getValue().getStatus()).isEqualTo(MerchantStatus.PENDING_KYC);
    }

    @Test
    void signupWithAnExistingEmailIsRejectedEvenIfTheCaseDiffers() {
        when(merchantRepository.existsByEmail("alice@example.com")).thenReturn(true);
        assertThatThrownBy(() -> service.signup(
                new MerchantSignupRequest("Alice", "ALICE@example.com", "password123", "Shop", BusinessType.PROPRIETORSHIP)))
                .isInstanceOf(DuplicateResourceException.class);
    }
}
