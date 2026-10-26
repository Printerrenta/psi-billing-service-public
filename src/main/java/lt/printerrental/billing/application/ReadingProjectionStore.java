package lt.printerrental.billing.application;

import java.time.LocalDate;
import java.util.*;
import lt.printerrental.billing.domain.UsageHistory;

public interface ReadingProjectionStore {
  boolean receive(
      UUID contract, UUID readingId, LocalDate asOf, List<Long> counters, long streamRevision);

  List<UsageHistory.Reading> readings(UUID contract);
}
