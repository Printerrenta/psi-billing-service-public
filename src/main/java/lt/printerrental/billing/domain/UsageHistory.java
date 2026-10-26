package lt.printerrental.billing.domain;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

public final class UsageHistory {
  public record Reading(UUID id, LocalDate asOf, List<Long> counters) {
    public Reading {
      Objects.requireNonNull(id);
      Objects.requireNonNull(asOf);
      counters = List.copyOf(counters);
      Quantities.counters(counters);
    }
  }

  public record Interval(LocalDate from, LocalDate to, Quantities quantities, String basis) {
    public Interval {
      if (from.isAfter(to)) throw new IllegalArgumentException("Invalid usage interval");
    }

    public long days() {
      return ChronoUnit.DAYS.between(from, to) + 1;
    }
  }

  private final Agreement agreement;
  private final List<Interval> intervals;

  public UsageHistory(Agreement agreement, List<Reading> readings) {
    this.agreement = agreement;
    var result = new ArrayList<Interval>();
    var previous = Quantities.counters(agreement.baseline());
    var from = agreement.start();
    var sorted = readings.stream().sorted(Comparator.comparing(Reading::asOf)).toList();
    for (var reading : sorted) {
      if (reading.asOf().isBefore(from) || reading.asOf().isAfter(agreement.usageEnd()))
        throw new IllegalArgumentException("Reading outside usage interval or duplicated date");
      var current = Quantities.counters(reading.counters());
      result.add(new Interval(from, reading.asOf(), current.subtract(previous), "ACTUAL"));
      from = reading.asOf().plusDays(1);
      previous = current;
    }
    intervals = List.copyOf(result);
  }

  public List<Interval> forMonth(YearMonth month, boolean estimate) {
    var start = max(month.atDay(1), agreement.start());
    var end = min(month.atEndOfMonth(), agreement.usageEnd());
    if (start.isAfter(end)) return List.of();
    var result = new ArrayList<Interval>();
    LocalDate covered = start.minusDays(1);
    for (var interval : intervals) {
      if (interval.to().isBefore(start) || interval.from().isAfter(end)) continue;
      var a = max(start, interval.from());
      var b = min(end, interval.to());
      var basis = interval.from().equals(a) && interval.to().equals(b) ? "ACTUAL" : "ALLOCATED";
      result.add(
          new Interval(
              a,
              b,
              interval.quantities().fraction(ChronoUnit.DAYS.between(a, b) + 1, interval.days()),
              basis));
      covered = max(covered, b);
    }
    if (covered.isBefore(end)) {
      if (!estimate) throw new IllegalStateException("Usage is not fully observed");
      var history = lastFullActualBefore(month);
      var from = max(start, covered.plusDays(1));
      var days = ChronoUnit.DAYS.between(from, end) + 1;
      result.add(
          new Interval(
              from,
              end,
              history.map(h -> h.quantities().fraction(days, h.days())).orElse(Quantities.zero()),
              history.isPresent() ? "ESTIMATED" : "NO_HISTORY"));
    }
    return List.copyOf(result);
  }

  public boolean covers(YearMonth month) {
    var end = min(month.atEndOfMonth(), agreement.usageEnd());
    return end.isBefore(agreement.start())
        || month.atDay(1).isAfter(agreement.usageEnd())
        || intervals.stream().anyMatch(i -> !i.to().isBefore(end));
  }

  private Optional<Interval> lastFullActualBefore(YearMonth month) {
    return intervals.stream()
        .filter(
            i ->
                i.from().getDayOfMonth() == 1
                    && i.to().equals(YearMonth.from(i.from()).atEndOfMonth())
                    && i.to().isBefore(month.atDay(1)))
        .reduce((a, b) -> b);
  }

  static LocalDate min(LocalDate a, LocalDate b) {
    return a.isBefore(b) ? a : b;
  }

  static LocalDate max(LocalDate a, LocalDate b) {
    return a.isAfter(b) ? a : b;
  }
}
