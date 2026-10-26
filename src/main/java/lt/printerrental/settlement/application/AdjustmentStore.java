package lt.printerrental.settlement.application;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lt.printerrental.settlement.application.LedgerRecords.*;

public interface AdjustmentStore {
  List<Adjustment> adjustments(String owner);

  List<Adjustment> adjustmentHistory(String owner, int page, int size);

  void adjustment(Adjustment adjustment);

  void applied(UUID invoice, UUID adjustment, BigDecimal gross, BigDecimal remaining);
}
