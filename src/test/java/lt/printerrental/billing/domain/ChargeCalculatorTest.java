package lt.printerrental.billing.domain;

import static org.assertj.core.api.Assertions.*;

import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChargeCalculatorTest {
  final ChargeCalculator calculator = new ChargeCalculator();

  Agreement.Terms terms(LocalDate from, String fee, String mono, String vat) {
    return new Agreement.Terms(
        UUID.randomUUID(),
        from,
        new BigDecimal(fee),
        new BigDecimal(mono),
        new BigDecimal("0.03"),
        new BigDecimal(vat),
        14);
  }

  Agreement contract(LocalDate from, LocalDate to, List<Agreement.Terms> terms) {
    return new Agreement(
        UUID.randomUUID(), "alice", from, to, null, List.of(0L, 0L, 0L, 0L), terms);
  }

  @Test
  void allFourCountersAndVatUseSeparateCorrectRates() {
    var start = LocalDate.of(2026, 10, 1);
    var end = LocalDate.of(2026, 10, 31);
    var a = contract(start, end, List.of(terms(start, "31", "0.01", "21")));
    var usage =
        List.of(
            new UsageHistory.Interval(
                start, end, Quantities.counters(List.of(100L, 50L, 10L, 5L)), "ACTUAL"));
    var result = calculator.calculate(a, YearMonth.of(2026, 10), usage);
    assertThat(result.net()).isEqualByComparingTo("34.00");
    assertThat(result.vat()).isEqualByComparingTo("7.14");
    assertThat(result.gross()).isEqualByComparingTo("41.14");
    assertThat(result.usageNet()).isEqualByComparingTo("3.00");
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-02", "2028-02", "2026-04", "2026-10"})
  void actualMonthLengthControlsProration(String value) {
    var month = YearMonth.parse(value);
    var start = month.atDay(5);
    var end = month.atDay(14);
    var a =
        contract(
            start,
            end,
            List.of(terms(start, Integer.toString(month.lengthOfMonth()), "0.01", "0")));
    assertThat(calculator.calculate(a, month, List.of()).net()).isEqualByComparingTo("10.00");
  }

  @Test
  void midmonthTermsSplitFixedAndPageAmountsByDays() {
    var start = LocalDate.of(2026, 10, 1);
    var end = LocalDate.of(2026, 10, 31);
    var a =
        contract(
            start,
            end,
            List.of(terms(start, "31", "0.01", "0"), terms(start.plusDays(15), "62", "0.02", "0")));
    var usage =
        List.of(
            new UsageHistory.Interval(
                start, end, Quantities.counters(List.of(310L, 0L, 0L, 0L)), "ACTUAL"));
    var result = calculator.calculate(a, YearMonth.of(2026, 10), usage);
    assertThat(result.net()).isEqualByComparingTo("51.70");
    assertThat(result.usageNet()).isEqualByComparingTo("4.70");
  }

  @Test
  void earlyReturnDoesNotRemovePlannedFixedRent() {
    var start = LocalDate.of(2026, 10, 1);
    var end = LocalDate.of(2026, 12, 31);
    var a =
        new Agreement(
            UUID.randomUUID(),
            "alice",
            start,
            end,
            LocalDate.of(2026, 10, 5),
            List.of(0L, 0L, 0L, 0L),
            List.of(terms(start, "31", "0.01", "0")));
    var history =
        new UsageHistory(
            a,
            List.of(
                new UsageHistory.Reading(
                    UUID.randomUUID(), LocalDate.of(2026, 10, 5), List.of(10L, 0L, 0L, 0L))));
    var november =
        calculator.calculate(
            a, YearMonth.of(2026, 11), history.forMonth(YearMonth.of(2026, 11), true));
    assertThat(november.net()).isEqualByComparingTo("31.00");
    assertThat(november.usageNet()).isZero();
  }

  @Test
  void vatAndHalfCentLineRoundingMatchDisplayedTotals() {
    var start = LocalDate.of(2026, 10, 1);
    var a = contract(start, start, List.of(terms(start, "0.155", "0.01", "21")));
    var r = calculator.calculate(a, YearMonth.of(2026, 10), List.of());
    assertThat(r.net()).isEqualByComparingTo("0.01");
    assertThat(r.gross())
        .isEqualByComparingTo(
            r.lines().stream()
                .map(ChargeCalculator.Line::gross)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
  }

  @Test
  void negativeDifferencesAndOverlappingTermsAreRejected() {
    assertThatThrownBy(
            () ->
                Quantities.counters(List.of(1L, 0L, 0L, 0L))
                    .subtract(Quantities.counters(List.of(2L, 0L, 0L, 0L))))
        .isInstanceOf(IllegalArgumentException.class);
    var start = LocalDate.of(2026, 10, 1);
    assertThatThrownBy(
            () ->
                contract(
                    start,
                    start.plusDays(1),
                    List.of(terms(start, "31", "0.01", "0"), terms(start, "31", "0.02", "0"))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void forecastUsesDailyRateOfLastFullActualMonth() {
    var start = LocalDate.of(2026, 1, 1);
    var a = contract(start, LocalDate.of(2026, 12, 31), List.of(terms(start, "31", "0.01", "0")));
    var history =
        new UsageHistory(
            a,
            List.of(
                new UsageHistory.Reading(
                    UUID.randomUUID(), LocalDate.of(2026, 1, 31), List.of(310L, 62L, 31L, 0L))));
    var february = history.forMonth(YearMonth.of(2026, 2), true);
    assertThat(february.get(0).quantities().values().get(0)).isEqualByComparingTo("280");
    assertThat(february.get(0).basis()).isEqualTo("ESTIMATED");
    assertThat(history.forMonth(YearMonth.of(2026, 3), true).get(0).quantities().values().get(0))
        .isEqualByComparingTo("310");
  }

  @Test
  void noHistoryDoesNotFabricateCounterReadings() {
    var start = LocalDate.of(2026, 1, 1);
    var a = contract(start, start.plusMonths(2), List.of(terms(start, "31", "0.01", "0")));
    var intervals = new UsageHistory(a, List.of()).forMonth(YearMonth.of(2026, 1), true);
    assertThat(intervals.get(0).basis()).isEqualTo("NO_HISTORY");
    assertThat(intervals.get(0).quantities().values()).allMatch(n -> n.signum() == 0);
  }

  @Test
  void lateCumulativeReadingsAllocateWithoutLosingTotalQuantity() {
    var start = LocalDate.of(2026, 1, 1);
    var a = contract(start, LocalDate.of(2026, 3, 31), List.of(terms(start, "31", "0.01", "0")));
    var history =
        new UsageHistory(
            a,
            List.of(
                new UsageHistory.Reading(UUID.randomUUID(), a.end(), List.of(900L, 0L, 0L, 0L))));
    BigDecimal total = BigDecimal.ZERO;
    for (int month = 1; month <= 3; month++)
      for (var interval : history.forMonth(YearMonth.of(2026, month), false)) {
        total = total.add(interval.quantities().values().get(0));
        assertThat(interval.basis()).isEqualTo("ALLOCATED");
      }
    assertThat(total).isEqualByComparingTo("900");
  }
}
