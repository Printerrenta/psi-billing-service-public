package lt.printerrental.settlement.application;

import java.time.*;
import java.util.*;
import lt.printerrental.settlement.application.LedgerRecords.*;

public interface AccountStore {
  long lockAccount(String owner);

  long bumpAccount(String owner);

  boolean knownOwner(String owner);

  Summary summary(String owner, LocalDate today);
}
