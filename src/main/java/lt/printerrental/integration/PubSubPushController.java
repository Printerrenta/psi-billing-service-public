package lt.printerrental.integration;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class PubSubPushController {
  private static final Logger LOG = LoggerFactory.getLogger(PubSubPushController.class);
  private final DurableEventInbox inbox;
  private final ObjectMapper json;
  private final Set<String> subscriptions;

  public PubSubPushController(
      DurableEventInbox inbox,
      ObjectMapper json,
      @Value("${PUBSUB_SUBSCRIPTIONS:}") String subscriptions) {
    this.inbox = inbox;
    this.json = json;
    this.subscriptions =
        Set.copyOf(
            Arrays.stream(subscriptions.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList());
  }

  @PostMapping("/internal/events")
  public ResponseEntity<Void> receive(@RequestBody JsonNode body) {
    var subscription = body.path("subscription").asText();
    if (!subscriptions.contains(subscription)) {
      LOG.warn("Rejected Pub/Sub push for unexpected subscription {}", subscription);
      throw new IllegalArgumentException("Unexpected subscription");
    }
    var data = body.path("message").path("data").asText();
    if (data.isEmpty() || data.length() > 262144) {
      LOG.warn(
          "Rejected Pub/Sub push for subscription {} with invalid payload size {}",
          subscription,
          data.length());
      throw new IllegalArgumentException("Invalid event size");
    }
    try {
      inbox.accept(
          json.readValue(
              Base64.getDecoder().decode(data), lt.printerrental.events.DomainEvent.class));
    } catch (java.io.IOException | IllegalArgumentException e) {
      LOG.warn("Rejected invalid Pub/Sub event from subscription {}", subscription, e);
      throw new IllegalArgumentException("Invalid event");
    }
    return ResponseEntity.noContent().build();
  }
}
