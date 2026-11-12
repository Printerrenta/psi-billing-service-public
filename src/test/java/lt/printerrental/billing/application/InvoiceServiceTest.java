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
}
