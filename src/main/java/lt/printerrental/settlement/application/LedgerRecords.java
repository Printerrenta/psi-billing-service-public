package lt.printerrental.settlement.application;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lt.printerrental.billing.domain.ChargeCalculator;

public final class LedgerRecords {
  private LedgerRecords() {}

  public record Invoice(
      UUID id,
      String owner,
      UUID contractId,
      LocalDate period,
      String kind,
      LocalDate issuedOn,
      LocalDate dueOn,
      ChargeCalculator.Calculation calculation,
      BigDecimal amountDue,
      BigDecimal creditApplied,
      BigDecimal debitApplied,
      BigDecimal currentUsageNet,
      BigDecimal currentUsageVat,
      long assessmentRevision,
      String actor) {}

  public record Adjustment(
      UUID id,
      String owner,
      UUID sourceInvoice,
      long sourceRevision,
      BigDecimal netDelta,
      BigDecimal vatDelta,
      BigDecimal remainingGross) {}

  public record Payment(
      UUID id,
      String owner,
      BigDecimal amount,
      LocalDate receivedOn,
      String reference,
      String actor,
      Instant reversedAt,
      UUID reversalKey,
      String reversalReason) {}

  public record Summary(
      BigDecimal invoiced,
      BigDecimal outstanding,
      BigDecimal overdue,
      BigDecimal received,
      BigDecimal advance,
      BigDecimal correctionCredit,
      BigDecimal unbilledDebit,
      long version) {}

  public record AppliedAdjustment(
      UUID id,
      UUID sourceInvoice,
      BigDecimal sourceNetDelta,
      BigDecimal sourceVatDelta,
      BigDecimal grossApplied) {}

  public record AllocationHistory(
      UUID id,
      UUID paymentId,
      UUID invoiceId,
      BigDecimal amount,
      long generation,
      Instant allocatedAt,
      Instant reversedAt) {}
}
