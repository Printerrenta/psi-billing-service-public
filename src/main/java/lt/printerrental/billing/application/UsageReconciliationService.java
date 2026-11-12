package lt.printerrental.billing.application;

import java.time.*;
import java.util.*;
import lt.printerrental.billing.domain.*;
import lt.printerrental.events.EventRecorder;
import lt.printerrental.integration.ContractSnapshot;
import lt.printerrental.settlement.application.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class UsageReconciliationService {
  private final ReadingProjectionStore readings;
  private final InvoiceStore invoiceRows;
  private final AdjustmentStore adjustmentRows;
  private final AccountStore accounts;
  private final EventRecorder events;
  private final ChargeCalculator calculator = new ChargeCalculator();

  public UsageReconciliationService(
      ReadingProjectionStore readings,
      InvoiceStore invoiceRows,
      AdjustmentStore adjustmentRows,
      AccountStore accounts,
      EventRecorder events) {
    this.readings = readings;
    this.invoiceRows = invoiceRows;
    this.adjustmentRows = adjustmentRows;
    this.accounts = accounts;

    this.events = events;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void reconcile(ContractSnapshot snapshot, long revision) {
    accounts.lockAccount(snapshot.ownerSubject());
    var agreement = AccountingModel.agreement(snapshot);
    var history = AccountingModel.history(snapshot, readings.readings(snapshot.contractId()));
    for (var invoice : invoiceRows.issued(snapshot.contractId())) {
      if (!invoice.kind().equals("MONTHLY")) continue;
      var month = YearMonth.from(invoice.period());
      if (invoice.assessmentRevision() >= revision) continue;
      // Preserve the original calculation policy for immutable legacy invoices.
      var reportedPolicy = invoice.calculation().lines().stream()
          .anyMatch(l -> l.basis().equals("REPORTED_DELTA") || l.basis().equals("NO_NEW_READING"));
      if (!reportedPolicy && !history.covers(month)) continue;
      var assessed = reportedPolicy
          ? calculator.calculateReported(agreement, month, history.reportedForMonth(month))
          : calculator.calculate(agreement, month, history.forMonth(month, false));
      var net = assessed.usageNet().subtract(invoice.currentUsageNet());
      var vat = assessed.usageVat().subtract(invoice.currentUsageVat());
      if (net.signum() != 0 || vat.signum() != 0) {
        var adjustment =
            new LedgerRecords.Adjustment(
                UUID.randomUUID(),
                snapshot.ownerSubject(),
                invoice.id(),
                revision,
                net,
                vat,
                net.add(vat));
        adjustmentRows.adjustment(adjustment);
        accounts.bumpAccount(snapshot.ownerSubject());
        events.record(
            "UsageReconciled",
            invoice.id(),
            snapshot.ownerSubject(),
            Map.of(
                "adjustmentId",
                adjustment.id(),
                "period",
                month.toString(),
                "netDelta",
                net,
                "vatDelta",
                vat));
      }
      invoiceRows.reassessed(invoice.id(), assessed.usageNet(), assessed.usageVat(), revision);
    }
  }
}
