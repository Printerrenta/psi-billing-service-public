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
    return new UsageHistory(
        agreement,
        readings.stream()
            .filter(
                r ->
                    !r.asOf().isBefore(agreement.start())
                        && !r.asOf().isAfter(agreement.usageEnd()))
            .toList());
  }
}
