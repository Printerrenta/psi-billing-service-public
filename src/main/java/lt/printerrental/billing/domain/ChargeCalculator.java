package lt.printerrental.billing.domain;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Pure decimal calculation: no HTTP, persistence, clock or messaging dependencies. */
public final class ChargeCalculator {
  public record Line(
      String kind,
      String category,
      UUID termsId,
      LocalDate from,
      LocalDate to,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal vatPercent,
      BigDecimal net,
      BigDecimal vat,
      BigDecimal gross,
      String basis) {}

  public record Calculation(
      List<Line> lines,
      BigDecimal net,
      BigDecimal vat,
      BigDecimal gross,
      BigDecimal usageNet,
      BigDecimal usageVat,
      boolean preliminary) {
    public Calculation {
      lines = List.copyOf(lines);
    }
  }

  public Calculation calculate(
      Agreement agreement, YearMonth month, List<UsageHistory.Interval> usage) {
    var from = max(agreement.start(), month.atDay(1));
    var to = min(agreement.end(), month.atEndOfMonth());
    if (from.isAfter(to)) throw new IllegalArgumentException("Month outside agreement");
    var lines = new ArrayList<Line>();
    String[] categories = {"A4_MONO", "A4_COLOR", "A3_MONO", "A3_COLOR"};
    for (int t = 0; t < agreement.terms().size(); t++) {
      var terms = agreement.terms().get(t);
      var end =
          t + 1 < agreement.terms().size()
              ? agreement.terms().get(t + 1).from().minusDays(1)
              : agreement.end();
      var a = max(from, terms.from());
      var b = min(to, end);
      if (a.isAfter(b)) continue;
      var fixed =
          terms
              .monthlyFee()
              .multiply(BigDecimal.valueOf(days(a, b)))
              .divide(BigDecimal.valueOf(month.lengthOfMonth()), Quantities.PRECISION);
      lines.add(
          line(
              "FIXED",
              "RENT",
              terms,
              a,
              b,
              BigDecimal.valueOf(days(a, b)),
              terms.monthlyFee(),
              fixed,
              "CONTRACT_DAYS"));
      for (var interval : usage) {
        var x = max(a, interval.from());
        var y = min(b, interval.to());
        if (x.isAfter(y)) continue;
        var quantities = interval.quantities().fraction(days(x, y), interval.days());
        for (int c = 0; c < 4; c++) {
          var q = quantities.values().get(c);
          lines.add(
              line(
                  "USAGE",
                  categories[c],
                  terms,
                  x,
                  y,
                  q,
                  terms.price(c),
                  q.multiply(terms.price(c)),
                  interval.basis()));
        }
      }
    }
    var net = BigDecimal.ZERO;
    var vat = BigDecimal.ZERO;
    var uNet = BigDecimal.ZERO;
    var uVat = BigDecimal.ZERO;
    for (var l : lines) {
      net = net.add(l.net());
      vat = vat.add(l.vat());
      if (l.kind().equals("USAGE")) {
        uNet = uNet.add(l.net());
        uVat = uVat.add(l.vat());
      }
    }
    return new Calculation(
        lines,
        money(net),
        money(vat),
        money(net.add(vat)),
        money(uNet),
        money(uVat),
        usage.stream()
            .anyMatch(i -> i.basis().equals("ESTIMATED") || i.basis().equals("NO_HISTORY")));
  }

  private Line line(
      String kind,
      String category,
      Agreement.Terms terms,
      LocalDate from,
      LocalDate to,
      BigDecimal quantity,
      BigDecimal price,
      BigDecimal exact,
      String basis) {
    var net = money(exact);
    var vat = money(net.multiply(terms.vatPercent()).movePointLeft(2));
    return new Line(
        kind,
        category,
        terms.id(),
        from,
        to,
        quantity,
        price,
        terms.vatPercent(),
        net,
        vat,
        net.add(vat),
        basis);
  }

  public static BigDecimal money(BigDecimal n) {
    return n.setScale(2, RoundingMode.HALF_UP);
  }

  private static long days(LocalDate a, LocalDate b) {
    return ChronoUnit.DAYS.between(a, b) + 1;
  }

  private static LocalDate min(LocalDate a, LocalDate b) {
    return a.isBefore(b) ? a : b;
  }

  private static LocalDate max(LocalDate a, LocalDate b) {
    return a.isAfter(b) ? a : b;
  }
}
