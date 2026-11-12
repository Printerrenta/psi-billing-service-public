package lt.printerrental.billing.domain;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReportedUsageTest {
  final LocalDate start = LocalDate.of(2026, 1, 1);
  final Agreement agreement = new Agreement(UUID.randomUUID(), "client", start, LocalDate.of(2026, 12, 31), null,
      List.of(100L, 0L, 0L, 0L), List.of(new Agreement.Terms(UUID.randomUUID(), start, new BigDecimal("31"), new BigDecimal("0.01"), new BigDecimal("0.03"), BigDecimal.ZERO, 14)));
  UsageHistory.Reading reading(String date, long mono) { return new UsageHistory.Reading(UUID.randomUUID(), LocalDate.parse(date), List.of(mono, 0L, 0L, 0L)); }
  BigDecimal usage(UsageHistory history, int month) { return new ChargeCalculator().calculateReported(agreement, YearMonth.of(2026, month), history.reportedForMonth(YearMonth.of(2026, month))).usageNet(); }
  @Test void multipleNonMonthEndReadingsChargeTheirCumulativeDifferenceOnce() {
    var history = new UsageHistory(agreement, List.of(reading("2026-01-10", 150), reading("2026-01-20", 180)));
    assertThat(usage(history, 1)).isEqualByComparingTo("0.80");
    assertThat(usage(history, 2)).isZero();
  }
  @Test void missingMonthsOnlyChargeFixedRentWithoutForecast() {
    var history = new UsageHistory(agreement, List.of(reading("2026-01-20", 180)));
    var result = new ChargeCalculator().calculateReported(agreement, YearMonth.of(2026, 2), history.reportedForMonth(YearMonth.of(2026, 2)));
    assertThat(result.net()).isEqualByComparingTo("31.00");
    assertThat(result.usageNet()).isZero(); assertThat(result.preliminary()).isFalse();
    assertThat(result.lines()).noneMatch(l -> l.basis().equals("ESTIMATED") || l.basis().equals("NO_HISTORY"));
  }
  @Test void laterReadingChargesAllUnreportedUsageInItsMonth() {
    var history = new UsageHistory(agreement, List.of(reading("2026-01-20", 180), reading("2026-03-10", 280)));
    assertThat(usage(history, 1)).isEqualByComparingTo("0.80");
    assertThat(usage(history, 2)).isZero();
    assertThat(usage(history, 3)).isEqualByComparingTo("1.00");
  }
  @Test void backdatedReadingRedistributesChargesWithoutDoubleCounting() {
    var before = new UsageHistory(agreement, List.of(reading("2026-01-20", 180), reading("2026-03-10", 280)));
    var after = new UsageHistory(agreement, List.of(reading("2026-01-20", 180), reading("2026-02-15", 220), reading("2026-03-10", 280)));
    assertThat(usage(after, 2)).isEqualByComparingTo("0.40"); assertThat(usage(after, 3)).isEqualByComparingTo("0.60");
    assertThat(usage(after, 2).add(usage(after, 3))).isEqualByComparingTo(usage(before, 3));
  }
  @Test void aLaterReadingStillUsesHistoricalTariffsForItsActualInterval() {
    var changed = new Agreement(agreement.id(), "client", start, agreement.end(), null, agreement.baseline(),
        List.of(agreement.terms().getFirst(), new Agreement.Terms(UUID.randomUUID(), LocalDate.of(2026, 2, 1), new BigDecimal("31"), new BigDecimal("0.02"), new BigDecimal("0.03"), BigDecimal.ZERO, 14)));
    var history = new UsageHistory(changed, List.of(reading("2026-02-10", 510)));
    var result = new ChargeCalculator().calculateReported(changed, YearMonth.of(2026, 2), history.reportedForMonth(YearMonth.of(2026, 2)));
    // 410 real pages across 41 days: 310 at Jan tariff, 100 at Feb tariff.
    assertThat(result.usageNet()).isEqualByComparingTo("5.10");
    assertThat(result.lines()).filteredOn(l -> l.kind().equals("USAGE") && l.category().equals("A4_MONO"))
        .extracting(ChargeCalculator.Line::unitPrice).containsExactly(new BigDecimal("0.01"), new BigDecimal("0.02"));
  }
}
