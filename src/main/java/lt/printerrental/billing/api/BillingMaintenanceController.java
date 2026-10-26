package lt.printerrental.billing.api;

import java.util.Map;
import lt.printerrental.billing.application.BillingCycleJob;
import lt.printerrental.integration.DurableEventInbox;
import org.springframework.web.bind.annotation.*;

@RestController
public class BillingMaintenanceController {
  private final DurableEventInbox inbox;
  private final lt.printerrental.messaging.OutboxDispatchJob outbox;
  private final BillingCycleJob cycles;

  public BillingMaintenanceController(
      DurableEventInbox inbox,
      BillingCycleJob cycles,
      lt.printerrental.messaging.OutboxDispatchJob outbox) {
    this.inbox = inbox;
    this.outbox = outbox;
    this.cycles = cycles;
  }

  @PostMapping("/internal/billing/tick")
  public Map<String, Integer> tick() {
    inbox.retryPending();
    var count = cycles.closeReady();
    outbox.dispatch();
    return Map.of("invoicesCreated", count);
  }
}
