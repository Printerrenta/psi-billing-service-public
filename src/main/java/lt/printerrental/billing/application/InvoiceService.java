package lt.printerrental.billing.application;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lt.printerrental.api.ApiProblem;
import lt.printerrental.auth.*;
import lt.printerrental.billing.domain.*;
import lt.printerrental.events.EventRecorder;
import lt.printerrental.integration.*;
import lt.printerrental.settlement.application.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class InvoiceService {
  private final ContractSnapshotStore contracts;
  private final ReadingProjectionStore readings;
  private final InvoiceStore invoiceRows;
  private final AdjustmentStore adjustmentRows;
  private final AccountStore accounts;
  private final UsageReconciliationService reconciliation;
  private final SettlementService settlement;
  private final UserAccess access;
  private final CurrentUser user;
  private final EventRecorder events;
  private final Clock clock;
  private final ChargeCalculator calculator = new ChargeCalculator();
  private final AdjustmentAllocator adjustments = new AdjustmentAllocator();

  public InvoiceService(
      ContractSnapshotStore contracts,
      ReadingProjectionStore readings,
      InvoiceStore invoiceRows,
      AdjustmentStore adjustmentRows,
      AccountStore accounts,
      UsageReconciliationService reconciliation,
      SettlementService settlement,
      UserAccess access,
      CurrentUser user,
      EventRecorder events,
      Clock clock) {
    this.contracts = contracts;
    this.readings = readings;
    this.invoiceRows = invoiceRows;
    this.adjustmentRows = adjustmentRows;
    this.accounts = accounts;

    this.reconciliation = reconciliation;
    this.settlement = settlement;
    this.access = access;
    this.user = user;
    this.events = events;
    this.clock = clock;
  }

  public record Issue(UUID requestKey, YearMonth period) {}

  public record View(
      LedgerRecords.Invoice invoice,
      BigDecimal paid,
      BigDecimal outstanding,
      String status,
      List<LedgerRecords.AppliedAdjustment> adjustments) {
    public View {
      adjustments = List.copyOf(adjustments);
    }
  }

  @Transactional
  public View issue(UUID contract, Issue input) {
    access.staff();
    if (input.requestKey() == null || input.period() == null)
      throw new IllegalArgumentException("Request key and period required");
    return view(
        create(contract, input.requestKey(), input.period(), "MONTHLY", user.subject()).id());
  }

  @Transactional
  public View settle(UUID contract, UUID requestKey) {
    access.staff();
    if (requestKey == null) throw new IllegalArgumentException("Request key required");
    return view(
        create(contract, requestKey, YearMonth.from(today()), "RECONCILIATION", user.subject())
            .id());
  }

  @Transactional
  public void issueSystem(UUID contract, YearMonth period) {
    create(
        contract,
        UUID.nameUUIDFromBytes(
            (contract + ":" + period).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        period,
        "MONTHLY",
        "scheduler");
  }

  private LedgerRecords.Invoice create(
      UUID contract, UUID requestKey, YearMonth month, String kind, String actor) {
    var locked = contracts.lock(contract);
    var s = locked.snapshot();
    if (s.status().equals("DRAFT")) throw new ApiProblem(409, "Draft contract is not billable");
    accounts.lockAccount(s.ownerSubject());
    var existing = existingInvoice(contract, requestKey, month, kind);
    if (existing.isPresent()) return existing.get();
    var agreement = AccountingModel.agreement(s);
    var history = AccountingModel.history(s, readings.readings(contract));
    var calculation = calculate(s, agreement, history, month, kind);
    reconciliation.reconcile(s, locked.version());
    var pending = adjustmentRows.adjustments(s.ownerSubject());
    var plan =
        adjustments.apply(
            calculation.gross(),
            pending.stream()
                .map(a -> new AdjustmentAllocator.Pending(a.id(), a.remainingGross()))
                .toList());
    var invoice =
        newInvoice(
            s, locked.version(), requestKey, month, kind, actor, agreement, calculation, plan);
    invoiceRows.insert(invoice);
    for (var applied : plan.applied())
      adjustmentRows.applied(
          invoice.id(), applied.id(), applied.signedAmount(), applied.remaining());
    settlement.rebalance(s.ownerSubject());
    recordIssued(invoice);
    return invoice;
  }

  private Optional<LedgerRecords.Invoice> existingInvoice(
      UUID contract, UUID requestKey, YearMonth month, String kind) {
    var byKey = invoiceRows.invoice(requestKey);
    if (byKey.isPresent()) {
      var old = byKey.get();
      if (!old.contractId().equals(contract)
          || !old.period().equals(month.atDay(1))
          || !old.kind().equals(kind)) throw new ApiProblem(409, "Invoice id reused");
      return Optional.of(old);
    }
    if (kind.equals("MONTHLY")) {
      var prior = invoiceRows.monthly(contract, month.atDay(1));
      if (prior.isPresent()) return prior;
    }
    return Optional.empty();
  }

  private LedgerRecords.Invoice newInvoice(
      ContractSnapshot s,
      long version,
      UUID requestKey,
      YearMonth month,
      String kind,
      String actor,
      Agreement agreement,
      ChargeCalculator.Calculation calculation,
      AdjustmentAllocator.Plan plan) {
    var termsDate = month.atEndOfMonth().isAfter(s.endDate()) ? s.endDate() : month.atEndOfMonth();
    if (termsDate.isBefore(s.startDate())) termsDate = s.startDate();
    var invoice =
        new LedgerRecords.Invoice(
            requestKey,
            s.ownerSubject(),
            s.contractId(),
            month.atDay(1),
            kind,
            today(),
            today().plusDays(agreement.at(termsDate).paymentDays()),
            calculation,
            plan.amountDue(),
            plan.creditApplied(),
            plan.debitApplied(),
            calculation.usageNet(),
            calculation.usageVat(),
            version,
            actor);
    return invoice;
  }

  private void recordIssued(LedgerRecords.Invoice invoice) {
    events.record(
        "InvoiceIssued",
        invoice.id(),
        invoice.owner(),
        Map.of(
            "contractId",
            invoice.contractId(),
            "period",
            YearMonth.from(invoice.period()).toString(),
            "amountDue",
            invoice.amountDue(),
            "preliminary",
            invoice.calculation().preliminary()));
  }

  private ChargeCalculator.Calculation calculate(
      ContractSnapshot s, Agreement agreement, UsageHistory history, YearMonth month, String kind) {
    ChargeCalculator.Calculation calculation;
    if (kind.equals("MONTHLY")) {
      if (month.atEndOfMonth().isBefore(s.startDate()) || month.atDay(1).isAfter(s.endDate()))
        throw new IllegalArgumentException("Period outside planned contract");
      var end = month.atEndOfMonth().isBefore(s.endDate()) ? month.atEndOfMonth() : s.endDate();
      if (end.isAfter(today()) || end.equals(today()) && s.completedReadingDate() == null)
        throw new ApiProblem(409, "Billing period is not closed");
      if (!history.covers(month) && today().isBefore(month.plusMonths(1).atDay(6)))
        throw new ApiProblem(409, "Customer reading deadline has not passed");
      calculation = calculator.calculate(agreement, month, history.forMonth(month, true));
    } else {
      if (!s.endDate().isBefore(today()))
        throw new ApiProblem(409, "Use monthly invoices during the planned rental period");
      calculation =
          new ChargeCalculator.Calculation(
              List.of(),
              new BigDecimal("0.00"),
              new BigDecimal("0.00"),
              new BigDecimal("0.00"),
              new BigDecimal("0.00"),
              new BigDecimal("0.00"),
              false);
    }
    return calculation;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public View view(UUID id) {
    var i = invoiceRows.invoice(id).orElseThrow(() -> new ApiProblem(404, "Invoice not found"));
    access.owner(i.owner());
    return display(i);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public List<View> list(String owner, int page, int size) {
    access.owner(owner);
    if (page < 0 || page > 1000000 || size < 1 || size > 100)
      throw new IllegalArgumentException("Invalid pagination");
    return invoiceRows.invoices(owner, page, size).stream().map(this::display).toList();
  }

  private View display(LedgerRecords.Invoice i) {
    var paid = invoiceRows.allocated(i.id());
    var due = i.amountDue().subtract(paid);
    var status =
        i.amountDue().signum() == 0
            ? "NO_PAYMENT_DUE"
            : due.signum() == 0
                ? "PAID"
                : i.dueOn().isBefore(today())
                    ? "OVERDUE"
                    : paid.signum() > 0 ? "PARTIALLY_PAID" : "UNPAID";
    return new View(i, paid, due, status, invoiceRows.appliedAdjustments(i.id()));
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(ZoneId.of("Europe/Vilnius")));
  }
}
