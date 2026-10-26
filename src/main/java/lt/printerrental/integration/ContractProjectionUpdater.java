package lt.printerrental.integration;

import lt.printerrental.billing.application.UsageReconciliationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContractProjectionUpdater {
  private final ContractSnapshotStore store;
  private final UsageReconciliationService reconciliation;

  public ContractProjectionUpdater(
      ContractSnapshotStore store, UsageReconciliationService reconciliation) {
    this.store = store;
    this.reconciliation = reconciliation;
  }

  @Transactional
  public void receive(ContractSnapshot snapshot) {
    if (store.receive(snapshot)) {
      var locked = store.lock(snapshot.contractId());
      reconciliation.reconcile(locked.snapshot(), locked.version());
    }
  }
}
