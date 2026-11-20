package lt.printerrental.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lt.printerrental.billing.application.*;
import lt.printerrental.billing.domain.UsageHistory;
import lt.printerrental.events.DomainEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class DurableEventInboxTest {
  static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {
    private int begins;
    private int commits;
    private int rollbacks;

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      begins++;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      rollbacks++;
    }
  }

  private static DomainEvent readingEvent(
      UUID contractId, LocalDate asOf, long mono, long streamRevision) {
    return new DomainEvent(
        UUID.randomUUID(),
        1,
        "MeterReadingAccepted",
        Instant.parse("2026-03-01T00:00:00Z"),
        contractId,
        "alice",
        Map.of(
            "readingId",
            UUID.randomUUID().toString(),
            "asOf",
            asOf.toString(),
            "counters",
            List.of(mono, 0L, 0L, 0L),
            "streamRevision",
            streamRevision));
  }

  @Test
  void retryPendingIsolatesFailingEventAndCommitsHealthyEvents() {
    var repo = mock(EventInboxRepository.class);
    var handler = mock(IncomingEventHandler.class);
    var tx = new RecordingTransactionManager();
    var inbox = new DurableEventInbox(repo, handler, tx);

    var contractId = UUID.randomUUID();
    var first = readingEvent(contractId, LocalDate.of(2026, 1, 31), 100, 1);
    var poison = readingEvent(contractId, LocalDate.of(2026, 2, 28), 200, 2);
    var third = readingEvent(contractId, LocalDate.of(2026, 3, 31), 300, 3);

    when(repo.pending()).thenReturn(List.of(first, poison, third));
    when(handler.apply(first)).thenReturn(true);
    when(handler.apply(poison)).thenThrow(new IllegalStateException("transient failure"));
    when(handler.apply(third)).thenReturn(true);

    inbox.retryPending();

    assertThat(tx.begins).isEqualTo(3);
    assertThat(tx.commits).isEqualTo(2);
    assertThat(tx.rollbacks).isEqualTo(1);
    verify(repo).processed(first.eventId());
    verify(repo, never()).processed(poison.eventId());
    verify(repo).processed(third.eventId());
  }

  @Test
  void outOfOrderMeterReadingDefersGracefullyUntilMonotonicityIsRestored() {
    var contractId = UUID.randomUUID();
    var snapshot =
        new ContractSnapshot(
            contractId,
            UUID.randomUUID(),
            "alice",
            "maker/large",
            true,
            true,
            "ACTIVE",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 3, 31),
            null,
            LocalDate.of(2026, 1, 1),
            List.of(0L, 0L, 0L, 0L),
            1L,
            List.of(
                new ContractSnapshot.Terms(
                    UUID.randomUUID(),
                    LocalDate.of(2026, 1, 1),
                    new BigDecimal("31.00"),
                    new BigDecimal("0.01"),
                    new BigDecimal("0.03"),
                    new BigDecimal("21"),
                    14)));

    var contracts = mock(ContractSnapshotStore.class);
    var readings = mock(ReadingProjectionStore.class);
    var reconciliation = mock(UsageReconciliationService.class);
    var handler =
        new BillingEventHandler(
            contracts, readings, reconciliation, new ObjectMapper().findAndRegisterModules());

    when(contracts.find(contractId)).thenReturn(Optional.of(snapshot));
    when(contracts.lock(contractId)).thenReturn(new ContractSnapshotStore.Locked(snapshot, 1L));
    when(readings.streamRevision(eq(contractId), any())).thenReturn(OptionalLong.empty());
    when(readings.readings(contractId))
        .thenReturn(
            List.of(
                new UsageHistory.Reading(
                    UUID.randomUUID(), LocalDate.of(2026, 1, 31), List.of(1500L, 0L, 0L, 0L))));

    var outOfOrder = readingEvent(contractId, LocalDate.of(2026, 2, 28), 1000, 3);
    assertThat(handler.apply(outOfOrder)).isFalse();
    verify(readings, never()).receive(any(), any(), any(), any(), anyLong());
    verify(reconciliation, never()).reconcile(any(), anyLong());

    when(readings.readings(contractId))
        .thenReturn(
            List.of(
                new UsageHistory.Reading(
                    UUID.randomUUID(), LocalDate.of(2026, 1, 31), List.of(800L, 0L, 0L, 0L))));
    when(readings.receive(eq(contractId), any(), eq(LocalDate.of(2026, 2, 28)), any(), eq(3L)))
        .thenReturn(true);
    when(contracts.bump(contractId)).thenReturn(2L);

    assertThat(handler.apply(outOfOrder)).isTrue();
    verify(readings)
        .receive(eq(contractId), any(), eq(LocalDate.of(2026, 2, 28)), eq(List.of(1000L, 0L, 0L, 0L)), eq(3L));
    verify(reconciliation).reconcile(snapshot, 2L);
  }

  @Test
  void staleMeterReadingEventIsAcknowledgedWithoutDeferral() {
    var contractId = UUID.randomUUID();
    var snapshot =
        new ContractSnapshot(
            contractId,
            UUID.randomUUID(),
            "alice",
            "maker/large",
            true,
            true,
            "ACTIVE",
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 3, 31),
            null,
            LocalDate.of(2026, 1, 1),
            List.of(0L, 0L, 0L, 0L),
            1L,
            List.of(
                new ContractSnapshot.Terms(
                    UUID.randomUUID(),
                    LocalDate.of(2026, 1, 1),
                    new BigDecimal("31.00"),
                    new BigDecimal("0.01"),
                    new BigDecimal("0.03"),
                    new BigDecimal("21"),
                    14)));

    var contracts = mock(ContractSnapshotStore.class);
    var readings = mock(ReadingProjectionStore.class);
    var reconciliation = mock(UsageReconciliationService.class);
    var handler =
        new BillingEventHandler(
            contracts, readings, reconciliation, new ObjectMapper().findAndRegisterModules());

    when(contracts.find(contractId)).thenReturn(Optional.of(snapshot));
    when(contracts.lock(contractId)).thenReturn(new ContractSnapshotStore.Locked(snapshot, 1L));
    when(readings.streamRevision(contractId, LocalDate.of(2026, 1, 31)))
        .thenReturn(OptionalLong.of(5L));

    var stale = readingEvent(contractId, LocalDate.of(2026, 1, 31), 2500, 3);
    assertThat(handler.apply(stale)).isTrue();
    verify(readings, never()).receive(any(), any(), any(), any(), anyLong());
  }
}
