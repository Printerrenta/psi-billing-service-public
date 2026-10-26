package lt.printerrental.billing.application;

import java.util.UUID;
import lt.printerrental.api.ApiProblem;
import lt.printerrental.auth.UserAccess;
import lt.printerrental.integration.*;
import org.springframework.stereotype.Service;

@Service
public class BillingContractService {
  private final ContractAccess remote;
  private final ContractProjectionUpdater updater;
  private final ContractSnapshotStore store;
  private final UserAccess access;

  public BillingContractService(
      ContractAccess remote,
      ContractProjectionUpdater updater,
      ContractSnapshotStore store,
      UserAccess access) {
    this.remote = remote;
    this.updater = updater;
    this.store = store;
    this.access = access;
  }

  public ContractSnapshot sync(UUID id) {
    var snapshot = remote.get(id);
    access.owner(snapshot.ownerSubject());
    updater.receive(snapshot);
    return get(id);
  }

  public ContractSnapshot get(UUID id) {
    var snapshot =
        store.find(id).orElseThrow(() -> new ApiProblem(404, "Contract projection not found"));
    access.owner(snapshot.ownerSubject());
    return snapshot;
  }
}
