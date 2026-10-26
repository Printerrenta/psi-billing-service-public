package lt.printerrental.billing.domain;

import java.math.*;
import java.util.*;

public final class AdjustmentAllocator {
  public record Pending(UUID id, BigDecimal remaining) {}

  public record Applied(UUID id, BigDecimal signedAmount, BigDecimal remaining) {}

  public record Plan(
      BigDecimal amountDue,
      BigDecimal debitApplied,
      BigDecimal creditApplied,
      List<Applied> applied) {
    public Plan {
      applied = List.copyOf(applied);
    }
  }

  public Plan apply(BigDecimal base, List<Pending> pending) {
    if (base.signum() < 0 || base.scale() > 2)
      throw new IllegalArgumentException("Invalid invoice balance");
    var result = new ArrayList<Applied>();
    var due = base;
    var debit = BigDecimal.ZERO;
    var credit = BigDecimal.ZERO;
    for (var p : pending)
      if (p.remaining().signum() > 0) {
        due = due.add(p.remaining());
        debit = debit.add(p.remaining());
        result.add(new Applied(p.id(), p.remaining(), BigDecimal.ZERO));
      }
    for (var p : pending)
      if (p.remaining().signum() < 0 && due.signum() > 0) {
        var amount = p.remaining().negate().min(due);
        due = due.subtract(amount);
        credit = credit.add(amount);
        result.add(new Applied(p.id(), amount.negate(), p.remaining().add(amount)));
      }
    return new Plan(due.setScale(2), debit.setScale(2), credit.setScale(2), result);
  }
}
