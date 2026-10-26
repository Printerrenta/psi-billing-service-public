package lt.printerrental.settlement.application;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lt.printerrental.api.ApiProblem;
import lt.printerrental.auth.*;
import lt.printerrental.events.EventRecorder;
import lt.printerrental.settlement.domain.PaymentAllocator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SettlementService {
  private final PaymentStore paymentRows;
  private final InvoiceStore invoiceRows;
  private final AdjustmentStore adjustmentRows;
  private final AccountStore accounts;
  private final UserAccess access;
  private final CurrentUser user;
  private final Clock clock;
  private final EventRecorder events;
  private final PaymentAllocator allocator = new PaymentAllocator();

  public SettlementService(
      PaymentStore paymentRows,
      InvoiceStore invoiceRows,
      AdjustmentStore adjustmentRows,
      AccountStore accounts,
      UserAccess access,
      CurrentUser user,
      Clock clock,
      EventRecorder events) {
    this.paymentRows = paymentRows;
    this.invoiceRows = invoiceRows;
    this.adjustmentRows = adjustmentRows;
    this.accounts = accounts;

    this.access = access;
    this.user = user;
    this.clock = clock;
    this.events = events;
  }

  public record Register(
      UUID id, String ownerSubject, BigDecimal amount, LocalDate receivedOn, String reference) {}

  public record Reverse(UUID requestKey, long version, String reason) {}

  @Transactional
  public LedgerRecords.Payment register(Register input) {
    access.staff();
    if (input.id() == null
        || input.ownerSubject() == null
        || input.ownerSubject().isBlank()
        || input.amount() == null
        || input.amount().signum() <= 0
        || input.amount().stripTrailingZeros().scale() > 2
        || input.amount().precision() - input.amount().scale() > 14
        || input.receivedOn() == null
        || input.receivedOn().isAfter(today())
        || input.reference() == null
        || input.reference().strip().length() < 3
        || input.reference().strip().length() > 200)
      throw new IllegalArgumentException("Invalid manual payment");
    if (!accounts.knownOwner(input.ownerSubject()))
      throw new ApiProblem(404, "Customer accounting is not initialized");
    accounts.lockAccount(input.ownerSubject());
    var prior = paymentRows.payment(input.id());
    if (prior.isPresent()) {
      var p = prior.get();
      if (!p.owner().equals(input.ownerSubject())
          || p.amount().compareTo(input.amount()) != 0
          || !p.receivedOn().equals(input.receivedOn())
          || !p.reference().equals(input.reference().strip()))
        throw new ApiProblem(409, "Payment id reused for another transaction");
      return p;
    }
    var payment =
        new LedgerRecords.Payment(
            input.id(),
            input.ownerSubject(),
            input.amount().setScale(2),
            input.receivedOn(),
            input.reference().strip(),
            user.subject(),
            null,
            null,
            null);
    paymentRows.payment(payment);
    rebalance(input.ownerSubject());
    events.record(
        "PaymentReceived",
        payment.id(),
        payment.owner(),
        Map.of("amount", payment.amount(), "receivedOn", payment.receivedOn()));
    return payment;
  }

  @Transactional
  public LedgerRecords.Payment reverse(UUID id, Reverse input) {
    access.staff();
    var initial =
        paymentRows.payment(id).orElseThrow(() -> new ApiProblem(404, "Payment not found"));
    var version = accounts.lockAccount(initial.owner());
    var p = paymentRows.payment(id).orElseThrow();
    if (input.requestKey() == null
        || input.reason() == null
        || input.reason().strip().length() < 5
        || input.reason().strip().length() > 2000)
      throw new IllegalArgumentException("Reversal key and reason required");
    if (p.reversedAt() != null) {
      if (input.requestKey().equals(p.reversalKey())) return p;
      throw new ApiProblem(409, "Payment already reversed");
    }
    if (version != input.version()) throw new ApiProblem(409, "Account changed; reload");
    paymentRows.reverse(id, input.requestKey(), user.subject(), input.reason().strip());
    rebalance(p.owner());
    events.record("PaymentReversed", p.id(), p.owner(), Map.of("amount", p.amount()));
    return paymentRows.payment(id).orElseThrow();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void rebalance(String owner) {
    var generation = accounts.bumpAccount(owner);
    paymentRows.allocations(
        owner,
        allocator.allocate(invoiceRows.debts(owner), paymentRows.activePayments(owner)),
        generation);
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public LedgerRecords.Summary balance(String owner) {
    access.owner(owner);
    return accounts.summary(owner, today());
  }

  @Transactional(readOnly = true)
  public List<LedgerRecords.Payment> payments(String owner, int page, int size) {
    access.owner(owner);
    page(page, size);
    return paymentRows.payments(owner, page, size);
  }

  private LocalDate today() {
    return LocalDate.now(clock.withZone(ZoneId.of("Europe/Vilnius")));
  }

  private static void page(int page, int size) {
    if (page < 0 || page > 1000000 || size < 1 || size > 100)
      throw new IllegalArgumentException("Invalid pagination");
  }

  @Transactional(readOnly = true)
  public List<LedgerRecords.Adjustment> adjustments(String owner, int page, int size) {
    access.owner(owner);
    page(page, size);
    return adjustmentRows.adjustmentHistory(owner, page, size);
  }

  @Transactional(readOnly = true)
  public List<LedgerRecords.AllocationHistory> allocations(String owner, int page, int size) {
    access.owner(owner);
    page(page, size);
    return paymentRows.allocationHistory(owner, page, size);
  }
}
