package com.paymentplatform.merchant_service.mapper;

import com.paymentplatform.merchant_service.dto.request.MerchantSignupRequest;
import com.paymentplatform.merchant_service.dto.response.MerchantResponse;
import com.paymentplatform.merchant_service.entity.Merchant;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface MerchantMapper {

    Merchant toEntityFromSignUpRequest(MerchantSignupRequest request);

    MerchantResponse toResponse(Merchant merchant);
}
