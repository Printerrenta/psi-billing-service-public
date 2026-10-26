package lt.printerrental.billing.domain;

import java.math.*;
import java.util.*;

/** Four independent physical page counters: A4 mono/color, A3 mono/color. */
public record Quantities(List<BigDecimal> values) {
  public static final MathContext PRECISION = MathContext.DECIMAL128;

  public Quantities {
    values = List.copyOf(values);
    if (values.size() != 4 || values.stream().anyMatch(v -> v == null || v.signum() < 0))
      throw new IllegalArgumentException("Four nonnegative quantities required");
  }

  public static Quantities zero() {
    return new Quantities(Collections.nCopies(4, BigDecimal.ZERO));
  }

  public static Quantities counters(List<Long> counts) {
    if (counts == null || counts.size() != 4 || counts.stream().anyMatch(n -> n == null || n < 0))
      throw new IllegalArgumentException("Invalid counters");
    return new Quantities(counts.stream().map(BigDecimal::valueOf).toList());
  }

  public Quantities subtract(Quantities other) {
    var result = new ArrayList<BigDecimal>();
    for (int i = 0; i < 4; i++) result.add(values.get(i).subtract(other.values.get(i)));
    return new Quantities(result);
  }

  public Quantities fraction(long numerator, long denominator) {
    if (numerator < 0 || denominator <= 0)
      throw new IllegalArgumentException("Invalid day fraction");
    return new Quantities(
        values.stream()
            .map(
                n ->
                    n.multiply(BigDecimal.valueOf(numerator))
                        .divide(BigDecimal.valueOf(denominator), PRECISION))
            .toList());
  }
}
