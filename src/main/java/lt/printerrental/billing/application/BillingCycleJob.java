package lt.printerrental.billing.application;

import java.time.*;
import java.util.*;
import lt.printerrental.api.ApiProblem;
import lt.printerrental.integration.ContractSnapshotStore;
import lt.printerrental.settlement.application.*;
import org.springframework.stereotype.Component;

@Component
public class BillingCycleJob {
  private final ContractSnapshotStore contracts;
  private final InvoiceStore invoiceRows;
  private final InvoiceService invoices;
  private final Clock clock;

  public BillingCycleJob(
      ContractSnapshotStore contracts,
      InvoiceStore invoiceRows,
      InvoiceService invoices,
      Clock clock) {
    this.contracts = contracts;
    this.invoiceRows = invoiceRows;

    this.invoices = invoices;
    this.clock = clock;
  }

  public int closeReady() {
    var today = LocalDate.now(clock.withZone(ZoneId.of("Europe/Vilnius")));
    int created = 0;
    for (var id : contracts.ids()) {
      var s = contracts.find(id).orElseThrow();
      if (s.status().equals("DRAFT")) continue;
      var last =
          YearMonth.from(s.endDate()).isBefore(YearMonth.from(today))
              ? YearMonth.from(s.endDate())
              : YearMonth.from(today).minusMonths(1);
      for (var month = YearMonth.from(s.startDate());
          !month.isAfter(last);
          month = month.plusMonths(1)) {
        if (invoiceRows.monthly(id, month.atDay(1)).isPresent()) continue;
        try {
          invoices.issueSystem(id, month);
          created++;
        } catch (ApiProblem e) {
          if (e.status() != 409) throw e;
        }
        if (created >= 10) return created;
      }
    }
    return created;
  }
}
