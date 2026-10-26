package lt.printerrental.settlement.domain;

import java.math.*;
import java.time.*;
import java.util.*;

/** Deterministic FIFO allocation; a payment is not an invoice or a noncash correction. */
public final class PaymentAllocator {
  public record Debt(
      UUID invoiceId, LocalDate dueDate, LocalDate issuedOn, LocalDate period, BigDecimal amount) {
    public Debt {
      if (amount == null || amount.signum() < 0 || amount.scale() > 2)
        throw new IllegalArgumentException("Invalid debt");
    }
  }

  public record Payment(UUID paymentId, LocalDate receivedOn, BigDecimal amount) {
    public Payment {
      if (amount == null || amount.signum() <= 0 || amount.scale() > 2)
        throw new IllegalArgumentException("Invalid payment");
    }
  }

  public record Allocation(UUID invoiceId, UUID paymentId, BigDecimal amount) {}

  public List<Allocation> allocate(List<Debt> debts, List<Payment> payments) {
    var sorted =
        debts.stream()
            .sorted(
                Comparator.comparing(Debt::dueDate)
                    .thenComparing(Debt::issuedOn)
                    .thenComparing(Debt::period)
                    .thenComparing(Debt::invoiceId))
            .toList();
    var due = sorted.stream().map(Debt::amount).toArray(BigDecimal[]::new);
    var result = new ArrayList<Allocation>();
    int cursor = 0;
    for (var p :
        payments.stream()
            .sorted(Comparator.comparing(Payment::receivedOn).thenComparing(Payment::paymentId))
            .toList()) {
      var left = p.amount();
      while (left.signum() > 0 && cursor < sorted.size()) {
        var amount = left.min(due[cursor]);
        if (amount.signum() > 0) {
          result.add(new Allocation(sorted.get(cursor).invoiceId(), p.paymentId(), amount));
          left = left.subtract(amount);
          due[cursor] = due[cursor].subtract(amount);
        }
        if (due[cursor].signum() == 0) cursor++;
      }
    }
    return List.copyOf(result);
  }
}
