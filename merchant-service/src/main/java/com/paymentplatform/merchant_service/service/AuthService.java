package com.paymentplatform.merchant_service.service;


import com.paymentplatform.merchant_service.dto.request.LoginRequest;
import com.paymentplatform.merchant_service.dto.request.MerchantSignupRequest;
import com.paymentplatform.merchant_service.dto.response.LoginResponse;
import com.paymentplatform.merchant_service.dto.response.MerchantResponse;

public interface AuthService {
    MerchantResponse signup(MerchantSignupRequest request);

    LoginResponse login(LoginRequest request);
}
