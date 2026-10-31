package com.paymentplatform.payment_service.mapper;


import com.paymentplatform.payment_service.dto.response.OrderResponse;
import com.paymentplatform.payment_service.entity.OrderRecord;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface OrderMapper {

    // FIX: the entity field is called "orderStatus" but the response field is "status", so MapStruct
    // left it empty and every order was returned with "status": null.
    @Mapping(target = "status", source = "orderStatus")
    OrderResponse toResponse(OrderRecord orderRecord);
}
