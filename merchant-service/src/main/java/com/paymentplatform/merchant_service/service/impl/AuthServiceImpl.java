package com.paymentplatform.merchant_service.service.impl;

import com.paymentplatform.common_lib.enums.MerchantStatus;
import com.paymentplatform.common_lib.enums.UserRole;
import com.paymentplatform.common_lib.exception.AuthenticationFailedException;
import com.paymentplatform.common_lib.exception.BusinessRuleViolationException;
import com.paymentplatform.common_lib.exception.DuplicateResourceException;
import com.paymentplatform.common_lib.exception.ResourceNotFoundException;
import com.paymentplatform.merchant_service.dto.request.LoginRequest;
import com.paymentplatform.merchant_service.dto.request.MerchantSignupRequest;
import com.paymentplatform.merchant_service.dto.response.LoginResponse;
import com.paymentplatform.merchant_service.dto.response.MerchantResponse;
import com.paymentplatform.merchant_service.entity.AppUser;
import com.paymentplatform.merchant_service.entity.Merchant;
import com.paymentplatform.merchant_service.mapper.MerchantMapper;
import com.paymentplatform.merchant_service.repository.AppUserRepository;
import com.paymentplatform.merchant_service.repository.MerchantRepository;
import com.paymentplatform.merchant_service.security.JwtUtil;
import com.paymentplatform.merchant_service.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    private final AppUserRepository appUserRepository;
    private final MerchantRepository merchantRepository;
    private final MerchantMapper merchantMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    @Override
    @Transactional
    public MerchantResponse signup(MerchantSignupRequest request) {
        // FIX: emails are case-insensitive, so "A@x.com" and "a@x.com" must be the same account
        String email = normalizeEmail(request.email());
        if (merchantRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("DUPLICATE_MERCHANT_EMAIL",
                    "Merchant with email already exists: " + email);
        }

        Merchant merchant = merchantMapper.toEntityFromSignUpRequest(request);
        merchant.setEmail(email);
        merchant.setStatus(MerchantStatus.PENDING_KYC);

        merchant = merchantRepository.save(merchant);

        AppUser appUser = AppUser.builder()
                .email(email)
                .merchant(merchant)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(UserRole.OWNER)
                .build();
        appUserRepository.save(appUser);

        return merchantMapper.toResponse(merchant);
    }

    @Override
    public LoginResponse login(LoginRequest request) {

        String email = normalizeEmail(request.email());

        // FIX (security): unknown email and wrong password now look identical (HTTP 401),
        // so an attacker can't use the response to find out which emails are registered.
        AppUser appUser = appUserRepository.findByEmail(email)
                .orElseThrow(() -> new AuthenticationFailedException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), appUser.getPasswordHash())) {
            throw new AuthenticationFailedException("Invalid email or password");
        }

        String token = jwtUtil.generateAccessToken(email, appUser.getMerchant().getId(), appUser.getRole().toString());

        return new LoginResponse(token);
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}















