package lt.printerrental.settlement.application;

import java.time.*;
import java.util.*;
import lt.printerrental.settlement.application.LedgerRecords.*;
import lt.printerrental.settlement.domain.PaymentAllocator;

public interface PaymentStore {
  Optional<Payment> payment(UUID id);

  void payment(Payment payment);

  void reverse(UUID id, UUID key, String actor, String reason);

  List<Payment> payments(String owner, int page, int size);

  List<PaymentAllocator.Payment> activePayments(String owner);

  void allocations(String owner, List<PaymentAllocator.Allocation> allocations, long generation);

  List<AllocationHistory> allocationHistory(String owner, int page, int size);
}
