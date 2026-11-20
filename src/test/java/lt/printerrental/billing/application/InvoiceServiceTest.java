package lt.printerrental.billing.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lt.printerrental.auth.*;
import lt.printerrental.events.EventRecorder;
import lt.printerrental.integration.*;
import lt.printerrental.settlement.application.*;
import org.junit.jupiter.api.Test;

class InvoiceServiceTest {
  @Test
  void returnsOneReconciliationAcrossDifferentKeysAndMonths() {
    var start = LocalDate.of(2026, 1, 1);
    var id = UUID.randomUUID();
    var snapshot = new ContractSnapshot(id, UUID.randomUUID(), "customer", "model", false, false,
        "COMPLETED", start, start.plusMonths(1), start.plusDays(20), start,
        List.of(0L, 0L, 0L, 0L), 2L, List.of(new ContractSnapshot.Terms(UUID.randomUUID(), start,
        new BigDecimal("35"), new BigDecimal("0.01"), BigDecimal.ZERO, new BigDecimal("21"), 14)));
    var contracts = mock(ContractSnapshotStore.class);
    when(contracts.lock(id)).thenReturn(new ContractSnapshotStore.Locked(snapshot, 2L));
    var rows = mock(InvoiceStore.class);
    var stored = new HashMap<UUID, LedgerRecords.Invoice>();
    when(rows.invoice(any())).thenAnswer(call -> Optional.ofNullable(stored.get(call.getArgument(0))));
    when(rows.reconciliation(id)).thenAnswer(call -> stored.values().stream()
        .filter(row -> row.kind().equals("RECONCILIATION")).findFirst());
    when(rows.allocated(any())).thenReturn(BigDecimal.ZERO);
    doAnswer(call -> { LedgerRecords.Invoice row = call.getArgument(0); stored.put(row.id(), row); return null; }).when(rows).insert(any());
    var user = mock(CurrentUser.class);
    when(user.subject()).thenReturn("staff");
    var instant = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-03-11T12:00:00Z"));
    var clock = new Clock() {
      public Instant instant() { return instant.get(); }
      public ZoneId getZone() { return ZoneOffset.UTC; }
      public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
    };
    var service = new InvoiceService(contracts, mock(ReadingProjectionStore.class), rows,
        mock(AdjustmentStore.class), mock(AccountStore.class), mock(UsageReconciliationService.class),
        mock(SettlementService.class), mock(UserAccess.class), user, mock(EventRecorder.class),
        clock);
    var key = UUID.randomUUID();
    var first = service.settle(id, key);
    var second = service.settle(id, UUID.randomUUID());
    assertThat(second.invoice().id()).isEqualTo(first.invoice().id());
    instant.set(Instant.parse("2026-04-11T12:00:00Z"));
    assertThat(service.settle(id, key).invoice().id()).isEqualTo(first.invoice().id());
    assertThat(service.settle(id, UUID.randomUUID()).invoice().id()).isEqualTo(first.invoice().id());
    assertThat(stored).hasSize(1);
  }

  @Test
  void listBatchesAllocationsAndAppliedAdjustmentsWithoutPerInvoiceQueries() {
    var rows = mock(InvoiceStore.class);
    var contractId = UUID.randomUUID();
    var calc =
        new lt.printerrental.billing.domain.ChargeCalculator.Calculation(
            List.of(),
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            false);
    var id1 = UUID.randomUUID();
    var id2 = UUID.randomUUID();
    var id3 = UUID.randomUUID();
    var inv1 =
        new LedgerRecords.Invoice(
            id1,
            "customer",
            contractId,
            LocalDate.of(2026, 2, 1),
            "MONTHLY",
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 4, 1),
            calc,
            new BigDecimal("100.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            1L,
            "staff");
    var inv2 =
        new LedgerRecords.Invoice(
            id2,
            "customer",
            contractId,
            LocalDate.of(2026, 1, 1),
            "MONTHLY",
            LocalDate.of(2026, 2, 1),
            LocalDate.of(2026, 3, 1),
            calc,
            new BigDecimal("50.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            1L,
            "staff");
    var inv3 =
        new LedgerRecords.Invoice(
            id3,
            "customer",
            contractId,
            LocalDate.of(2026, 3, 1),
            "MONTHLY",
            LocalDate.of(2026, 3, 10),
            LocalDate.of(2026, 4, 10),
            calc,
            new BigDecimal("80.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            1L,
            "staff");
    var adj1 =
        new LedgerRecords.AppliedAdjustment(
            UUID.randomUUID(), id1, new BigDecimal("5.00"), new BigDecimal("1.05"), new BigDecimal("6.05"));
    var adj2 =
        new LedgerRecords.AppliedAdjustment(
            UUID.randomUUID(), id2, new BigDecimal("-2.00"), new BigDecimal("-0.42"), new BigDecimal("-2.42"));
    var adj3 =
        new LedgerRecords.AppliedAdjustment(
            UUID.randomUUID(), id2, new BigDecimal("1.00"), new BigDecimal("0.21"), new BigDecimal("1.21"));

    when(rows.invoices("customer", 0, 20)).thenReturn(List.of(inv1, inv2, inv3));
    when(rows.allocatedByInvoice(List.of(id1, id2, id3)))
        .thenReturn(Map.of(id1, new BigDecimal("100.00"), id3, new BigDecimal("20.00")));
    when(rows.appliedAdjustmentsByInvoice(List.of(id1, id2, id3)))
        .thenReturn(Map.of(id1, List.of(adj1), id3, List.of(adj2, adj3)));

    var clock = Clock.fixed(Instant.parse("2026-03-11T12:00:00Z"), ZoneOffset.UTC);
    var service =
        new InvoiceService(
            mock(ContractSnapshotStore.class),
            mock(ReadingProjectionStore.class),
            rows,
            mock(AdjustmentStore.class),
            mock(AccountStore.class),
            mock(UsageReconciliationService.class),
            mock(SettlementService.class),
            mock(UserAccess.class),
            mock(CurrentUser.class),
            mock(EventRecorder.class),
            clock);

    var views = service.list("customer", 0, 20);
    assertThat(views).extracting(v -> v.invoice().id()).containsExactly(id1, id2, id3);
    assertThat(views).extracting(InvoiceService.View::status).containsExactly("PAID", "OVERDUE", "PARTIALLY_PAID");
    assertThat(views.get(0).paid()).isEqualByComparingTo("100.00");
    assertThat(views.get(0).outstanding()).isZero();
    assertThat(views.get(0).adjustments()).containsExactly(adj1);
    assertThat(views.get(1).paid()).isZero();
    assertThat(views.get(1).outstanding()).isEqualByComparingTo("50.00");
    assertThat(views.get(1).adjustments()).isEmpty();
    assertThat(views.get(2).paid()).isEqualByComparingTo("20.00");
    assertThat(views.get(2).outstanding()).isEqualByComparingTo("60.00");
    assertThat(views.get(2).adjustments()).containsExactly(adj2, adj3);

    verify(rows, times(1)).allocatedByInvoice(List.of(id1, id2, id3));
    verify(rows, times(1)).appliedAdjustmentsByInvoice(List.of(id1, id2, id3));
    verify(rows, never()).allocated(any());
    verify(rows, never()).appliedAdjustments(any());
  }

  @Test
  void listReturnsEmptyWithoutBatchQueriesWhenPageIsEmpty() {
    var rows = mock(InvoiceStore.class);
    when(rows.invoices("customer", 0, 20)).thenReturn(List.of());
    var service =
        new InvoiceService(
            mock(ContractSnapshotStore.class),
            mock(ReadingProjectionStore.class),
            rows,
            mock(AdjustmentStore.class),
            mock(AccountStore.class),
            mock(UsageReconciliationService.class),
            mock(SettlementService.class),
            mock(UserAccess.class),
            mock(CurrentUser.class),
            mock(EventRecorder.class),
            Clock.fixed(Instant.parse("2026-03-11T12:00:00Z"), ZoneOffset.UTC));

    assertThat(service.list("customer", 0, 20)).isEmpty();
    verify(rows, never()).allocatedByInvoice(any());
    verify(rows, never()).appliedAdjustmentsByInvoice(any());
  }
}
