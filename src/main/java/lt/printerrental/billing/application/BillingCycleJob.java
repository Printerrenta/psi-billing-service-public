package lt.printerrental.billing.application;

import java.time.*;
import java.util.*;
import lt.printerrental.api.ApiProblem;
import lt.printerrental.integration.ContractSnapshotStore;
import lt.printerrental.settlement.application.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BillingCycleJob {
  private final ContractSnapshotStore contracts;
  private final InvoiceStore invoiceRows;
  private final InvoiceService invoices;
  private final Clock clock;
  private final ZoneId businessZone;

  public BillingCycleJob(
      ContractSnapshotStore contracts,
      InvoiceStore invoiceRows,
      InvoiceService invoices,
      Clock clock) {
    this(contracts, invoiceRows, invoices, clock, "Europe/Vilnius");
  }

  @Autowired
  public BillingCycleJob(
      ContractSnapshotStore contracts,
      InvoiceStore invoiceRows,
      InvoiceService invoices,
      Clock clock,
      @Value("${app.business-zone:Europe/Vilnius}") String businessZone) {
    this.contracts = contracts;
    this.invoiceRows = invoiceRows;
    this.invoices = invoices;
    this.clock = clock;
    this.businessZone = ZoneId.of(businessZone);
  }

  public int closeReady() {
    var today = LocalDate.now(clock.withZone(businessZone));
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
