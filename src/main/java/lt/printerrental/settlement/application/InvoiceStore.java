package lt.printerrental.settlement.application;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import lt.printerrental.settlement.application.LedgerRecords.*;
import lt.printerrental.settlement.domain.PaymentAllocator;

public interface InvoiceStore {
  Optional<Invoice> invoice(UUID id);

  Optional<Invoice> monthly(UUID contract, LocalDate period);

  List<Invoice> issued(UUID contract);

  List<Invoice> invoices(String owner, int page, int size);

  void insert(Invoice invoice);

  void reassessed(UUID invoice, BigDecimal net, BigDecimal vat, long revision);

  BigDecimal allocated(UUID invoice);

  List<PaymentAllocator.Debt> debts(String owner);

  List<AppliedAdjustment> appliedAdjustments(UUID invoice);
}
