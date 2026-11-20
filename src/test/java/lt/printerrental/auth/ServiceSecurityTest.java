package lt.printerrental.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

/** Verifies the billing service's HTTP security policy and domain error serialization. */
@WebMvcTest(
    controllers = ServiceSecurityTest.Probe.class,
    properties = {
      "NEON_AUTH_BASE_URL=https://auth.test/auth",
      "NEON_AUTH_JWKS_URL=https://auth.test/jwks",
      "INTERNAL_AUTH_MODE=deny"
    })
@Import({
  SecurityConfiguration.class,
  InternalSecurityConfiguration.class,
  ServiceSecurityTest.Probe.class
})
class ServiceSecurityTest {
  @MockitoBean org.springframework.security.oauth2.jwt.JwtDecoder decoder;
  @MockitoBean SigningKeyProvider signingKeys;
  @MockitoBean UserJwtAuthenticationConverter converter;
  @MockitoBean lt.printerrental.messaging.OutboxDispatchJob outbox;
  @Autowired MockMvc mvc;

  @Test
  void restrictsRoutesToBillingEndpointsAndAllowsPublicHealth() throws Exception {
    mvc.perform(get("/health")).andExpect(status().isOk());
    mvc.perform(get("/api/billing/invoices")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/billing/invoices").with(user("alice").roles("CUSTOMER")))
        .andExpect(status().isOk());
    mvc.perform(post("/api/billing/payments").with(user("emp").roles("EMPLOYEE")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/admin/users").with(user("admin").roles("ADMIN")))
        .andExpect(status().isForbidden());
    mvc.perform(post("/internal/events").with(user("admin").roles("ADMIN")))
        .andExpect(status().isForbidden());
  }

  @Test
  void returnsSafeDomainValidationMessagesForIllegalArguments() throws Exception {
    mvc.perform(get("/api/billing/domain-error").with(user("emp").roles("EMPLOYEE")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Period outside planned contract"));
    mvc.perform(get("/api/billing/blank-error").with(user("emp").roles("EMPLOYEE")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value("Invalid request or domain values"));
  }

  @RestController
  static class Probe {
    @RequestMapping({
      "/health",
      "/api/billing/invoices",
      "/api/billing/payments",
      "/api/admin/users",
      "/internal/events"
    })
    String respond() {
      return "ok";
    }

    @GetMapping("/api/billing/domain-error")
    String domainError() {
      throw new IllegalArgumentException("Period outside planned contract");
    }

    @GetMapping("/api/billing/blank-error")
    String blankError() {
      throw new IllegalArgumentException("   ");
    }
  }
}
