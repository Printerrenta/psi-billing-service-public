package lt.printerrental.billing.domain;

import java.math.*;
import java.time.*;
import java.util.*;

public record Agreement(
    UUID id,
    String owner,
    LocalDate start,
    LocalDate end,
    LocalDate returnedOn,
    List<Long> baseline,
    List<Terms> terms) {
  public record Terms(
      UUID id,
      LocalDate from,
      BigDecimal monthlyFee,
      BigDecimal monoPrice,
      BigDecimal colorPrice,
      BigDecimal vatPercent,
      int paymentDays) {
    public Terms {
      Objects.requireNonNull(id);
      Objects.requireNonNull(from);
      for (var n : List.of(monthlyFee, monoPrice, colorPrice, vatPercent))
        if (n.signum() < 0) throw new IllegalArgumentException("Negative terms");
      if (vatPercent.compareTo(new BigDecimal("100")) > 0 || paymentDays < 0 || paymentDays > 365)
        throw new IllegalArgumentException("Invalid terms");
    }

    public BigDecimal price(int category) {
      return (category % 2 == 0 ? monoPrice : colorPrice)
          .multiply(BigDecimal.valueOf(category < 2 ? 1 : 2));
    }
  }

  public Agreement {
    Objects.requireNonNull(id);
    if (owner == null
        || owner.isBlank()
        || start == null
        || end == null
        || start.isAfter(end)
        || returnedOn != null && (returnedOn.isBefore(start) || returnedOn.isAfter(end)))
      throw new IllegalArgumentException("Invalid agreement");
    baseline = List.copyOf(baseline);
    Quantities.counters(baseline);
    terms = terms.stream().sorted(Comparator.comparing(Terms::from)).toList();
    if (terms.isEmpty()
        || !terms.get(0).from().equals(start)
        || terms.stream().map(Terms::from).distinct().count() != terms.size())
      throw new IllegalArgumentException(
          "Terms must begin at contract start without duplicate dates");
  }

  public LocalDate usageEnd() {
    return returnedOn == null ? end : returnedOn;
  }

  public Terms at(LocalDate date) {
    return terms.stream().filter(t -> !t.from().isAfter(date)).reduce((a, b) -> b).orElseThrow();
  }
}
