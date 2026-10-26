package lt.printerrental.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.*;
import lt.printerrental.billing.application.*;
import lt.printerrental.events.DomainEvent;
import org.springframework.stereotype.Component;

@Component
public class BillingEventHandler implements IncomingEventHandler {
  private final ContractSnapshotStore contracts;
  private final ReadingProjectionStore readings;
  private final UsageReconciliationService reconciliation;
  private final ObjectMapper json;

  public BillingEventHandler(
      ContractSnapshotStore contracts,
      ReadingProjectionStore readings,
      UsageReconciliationService reconciliation,
      ObjectMapper json) {
    this.contracts = contracts;
    this.readings = readings;
    this.reconciliation = reconciliation;
    this.json = json;
  }

  public boolean supports(String type) {
    return Set.of("ContractAccountingChanged", "MeterReadingAccepted").contains(type);
  }

  public boolean apply(DomainEvent event) {
    if (event.type().equals("ContractAccountingChanged")) {
      var s = json.convertValue(event.data().get("snapshot"), ContractSnapshot.class);
      if (!s.contractId().equals(event.aggregateId())
          || !Objects.equals(s.ownerSubject(), event.ownerSubject()))
        throw new IllegalArgumentException("Snapshot owner or aggregate mismatch");
      if (contracts.receive(s)) {
        var locked = contracts.lock(s.contractId());
        reconciliation.reconcile(locked.snapshot(), locked.version());
      }
      return true;
    }
    var existing = contracts.find(event.aggregateId());
    if (existing.isEmpty()) return false;
    var locked = contracts.lock(event.aggregateId());
    var s = locked.snapshot();
    if (!s.ownerSubject().equals(event.ownerSubject()))
      throw new IllegalArgumentException("Reading owner mismatch");
    for (var key : java.util.List.of("readingId", "asOf", "counters", "streamRevision"))
      if (event.data().get(key) == null)
        throw new IllegalArgumentException("Missing accepted-reading field");
    var id = UUID.fromString(event.data().get("readingId").toString());
    var date = LocalDate.parse(event.data().get("asOf").toString());
    var counts =
        json.convertValue(
            event.data().get("counters"),
            new com.fasterxml.jackson.core.type.TypeReference<List<Long>>() {});
    var revision = Long.parseLong(event.data().get("streamRevision").toString());
    if (date.isBefore(s.startDate()) || date.isAfter(s.endDate()))
      throw new IllegalArgumentException("Reading outside contract");
    if (readings.receive(s.contractId(), id, date, counts, revision)) {
      var current = contracts.bump(s.contractId());
      reconciliation.reconcile(s, current);
    }
    return true;
  }
}
