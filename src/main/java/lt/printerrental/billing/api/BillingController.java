package lt.printerrental.billing.api;

import java.util.*;
import lt.printerrental.auth.CurrentUser;
import lt.printerrental.billing.application.*;
import lt.printerrental.integration.ContractSnapshot;
import lt.printerrental.settlement.application.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/billing")
public class BillingController {
  private final BillingContractService contracts;
  private final InvoiceService invoices;
  private final SettlementService settlement;
  private final CurrentUser user;

  public BillingController(
      BillingContractService contracts,
      InvoiceService invoices,
      SettlementService settlement,
      CurrentUser user) {
    this.contracts = contracts;
    this.invoices = invoices;
    this.settlement = settlement;
    this.user = user;
  }

  @PostMapping("/contracts/{id}/sync")
  public ContractSnapshot sync(@PathVariable UUID id) {
    return contracts.sync(id);
  }

  @GetMapping("/contracts/{id}")
  public ContractSnapshot contract(@PathVariable UUID id) {
    return contracts.get(id);
  }

  @PostMapping("/contracts/{id}/invoices")
  public InvoiceService.View issue(@PathVariable UUID id, @RequestBody InvoiceService.Issue input) {
    return invoices.issue(id, input);
  }

  public record Reconcile(UUID requestKey) {}

  @PostMapping("/contracts/{id}/reconciliation")
  public InvoiceService.View reconcile(@PathVariable UUID id, @RequestBody Reconcile input) {
    return invoices.settle(id, input.requestKey());
  }

  @GetMapping("/invoices/{id}")
  public InvoiceService.View invoice(@PathVariable UUID id) {
    return invoices.view(id);
  }

  @GetMapping("/invoices")
  public List<InvoiceService.View> invoices(
      @RequestParam(required = false) String owner,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return invoices.list(owner == null ? user.subject() : owner, page, size);
  }

  @GetMapping("/account")
  public LedgerRecords.Summary balance(@RequestParam(required = false) String owner) {
    return settlement.balance(owner == null ? user.subject() : owner);
  }

  @GetMapping("/payments")
  public List<LedgerRecords.Payment> payments(
      @RequestParam(required = false) String owner,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return settlement.payments(owner == null ? user.subject() : owner, page, size);
  }

  @PostMapping("/payments")
  public LedgerRecords.Payment register(@RequestBody SettlementService.Register input) {
    return settlement.register(input);
  }

  @PostMapping("/payments/{id}/reverse")
  public LedgerRecords.Payment reverse(
      @PathVariable UUID id, @RequestBody SettlementService.Reverse input) {
    return settlement.reverse(id, input);
  }

  @GetMapping("/adjustments")
  public List<LedgerRecords.Adjustment> adjustments(
      @RequestParam(required = false) String owner,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return settlement.adjustments(owner == null ? user.subject() : owner, page, size);
  }

  @GetMapping("/allocations")
  public List<LedgerRecords.AllocationHistory> allocations(
      @RequestParam(required = false) String owner,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return settlement.allocations(owner == null ? user.subject() : owner, page, size);
  }
}
