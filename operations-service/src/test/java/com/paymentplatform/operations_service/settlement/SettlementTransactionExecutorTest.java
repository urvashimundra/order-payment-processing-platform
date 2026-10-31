package com.paymentplatform.operations_service.settlement;

import com.paymentplatform.common_lib.dto.PaymentSettlementView;
import com.paymentplatform.common_lib.dto.SettlementBankDetails;
import com.paymentplatform.common_lib.entity.Money;
import com.paymentplatform.common_lib.enums.EventAggregateType;
import com.paymentplatform.common_lib.enums.SettlementStatus;
import com.paymentplatform.operations_service.entity.Settlement;
import com.paymentplatform.operations_service.entity.SettlementPayment;
import com.paymentplatform.operations_service.entity.SettlementPaymentId;
import com.paymentplatform.operations_service.outbox.OutboxEventPublisher;
import com.paymentplatform.operations_service.repository.SettlementPaymentRepository;
import com.paymentplatform.operations_service.repository.SettlementRepository;
import com.paymentplatform.operations_service.settlement.dto.BankTransferResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementTransactionExecutorTest {

    private SettlementRepository settlementRepository;
    private SettlementPaymentRepository settlementPaymentRepository;
    private BankTransferProcessor bankTransferProcessor;
    private OutboxEventPublisher outboxEventPublisher;
    private SettlementIntegrationGateway gateway;
    private SettlementTransactionExecutor executor;

    @BeforeEach
    void setUp() {
        settlementRepository = mock(SettlementRepository.class);
        settlementPaymentRepository = mock(SettlementPaymentRepository.class);
        bankTransferProcessor = mock(BankTransferProcessor.class);
        outboxEventPublisher = mock(OutboxEventPublisher.class);
        gateway = mock(SettlementIntegrationGateway.class);
        executor = new SettlementTransactionExecutor(settlementRepository, settlementPaymentRepository,
                bankTransferProcessor, outboxEventPublisher, gateway);
    }

    @Test
    void newSettlementHasTheNotNullColumnsFilledAndMovesToTransferPending() {
        UUID merchantId = UUID.randomUUID();
        when(gateway.findUnsettledCaptured(merchantId))
                .thenReturn(List.of(new PaymentSettlementView(UUID.randomUUID(), 100000, 0, "INR")));
        when(gateway.getSettlementBankDetails(merchantId)).thenReturn(new SettlementBankDetails("123456", "HDFC0001", "Shop"));
        when(bankTransferProcessor.initiate(any(), any(), any(), anyString(), anyString())).thenReturn(new BankTransferResult("TXN_1"));
        when(settlementRepository.save(any(Settlement.class))).thenAnswer(i -> {
            Settlement s = i.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });

        executor.processForMerchant(merchantId, LocalDate.now());

        ArgumentCaptor<Settlement> saved = ArgumentCaptor.forClass(Settlement.class);
        verify(settlementRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        Settlement settlement = saved.getAllValues().getFirst();
        // regression: refundAmount and bankReference are NOT NULL columns but were never set
        assertThat(settlement.getRefundAmount()).isNotNull();
        assertThat(settlement.getRefundAmount().getAmountUnits()).isZero();
        assertThat(settlement.getBankReference()).isNotNull();
        // 2% fee + 18% GST on the fee: 100000 - 2000 - 360 = 97640
        assertThat(settlement.getFeeAmount().getAmountUnits()).isEqualTo(2000);
        assertThat(settlement.getGstAmount().getAmountUnits()).isEqualTo(360);
        assertThat(settlement.getNetAmount().getAmountUnits()).isEqualTo(97640);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.TRANSFER_PENDING);
        assertThat(settlement.getBankReference()).isEqualTo("TXN_1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void settlementEventPayloadContainsPlainIdsNotTheWholeEntity() {
        UUID settlementId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        Settlement settlement = Settlement.builder().id(settlementId).merchantId(merchantId)
                .netAmount(Money.inr(97640)).status(SettlementStatus.TRANSFER_PENDING).build();
        when(settlementRepository.findById(settlementId)).thenReturn(Optional.of(settlement));
        when(settlementPaymentRepository.findBySettlement(settlement)).thenReturn(
                List.of(SettlementPayment.builder().id(new SettlementPaymentId(settlementId, paymentId)).build()));

        executor.resolveTransfer(settlementId, null, null);

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(outboxEventPublisher).publish(eq(EventAggregateType.SETTLEMENT), eq(settlementId),
                eq("SETTLEMENT_PROCESSED"), payload.capture());
        // regression: the whole Settlement entity used to be put into the payload
        assertThat(payload.getValue().get("settlementId")).isEqualTo(settlementId.toString());
        assertThat(payload.getValue().get("merchantId")).isEqualTo(merchantId.toString());
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PROCESSED);
        verify(gateway).markSettled(List.of(paymentId));
    }
}
