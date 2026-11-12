package lt.printerrental.billing.application;

import lt.printerrental.billing.domain.*;
import lt.printerrental.integration.ContractSnapshot;

public final class AccountingModel {
  private AccountingModel() {}

  public static Agreement agreement(ContractSnapshot s) {
    return new Agreement(
        s.contractId(),
        s.ownerSubject(),
        s.startDate(),
        s.endDate(),
        s.completedReadingDate(),
        s.baseline(),
        s.terms().stream()
            .map(
                t ->
                    new Agreement.Terms(
                        t.id(),
                        t.effectiveFrom(),
                        t.monthlyFee(),
                        t.monoPrice(),
                        t.colorPrice(),
                        t.vatPercent(),
                        t.paymentTermDays()))
            .toList());
  }

  public static UsageHistory history(
      ContractSnapshot snapshot, java.util.List<UsageHistory.Reading> readings) {
    var agreement = agreement(snapshot);
    var observed = new java.util.ArrayList<>(readings);
    // Completion carries an already accepted metering reading. Use it immediately if
    // its outbox event has not arrived yet; a later accepted correction takes precedence.
    if (snapshot.finalCounters() != null && snapshot.completedReadingDate() != null
        && observed.stream().noneMatch(r -> r.asOf().equals(snapshot.completedReadingDate()))) {
      observed.add(new UsageHistory.Reading(java.util.UUID.nameUUIDFromBytes(
          (snapshot.contractId() + ":completion").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          snapshot.completedReadingDate(), snapshot.finalCounters()));
    }
    return new UsageHistory(
        agreement,
        observed.stream()
            .filter(
                r ->
                    !r.asOf().isBefore(agreement.start())
                        && !r.asOf().isAfter(agreement.usageEnd()))
            .toList());
  }
}
