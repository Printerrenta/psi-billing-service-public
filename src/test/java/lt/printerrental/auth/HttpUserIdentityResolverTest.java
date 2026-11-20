package lt.printerrental.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import lt.printerrental.transport.ServiceHttpClient;
import lt.printerrental.transport.UpstreamUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;

class HttpUserIdentityResolverTest {
  @Test
  void wrapsUpstreamUnavailableExceptionInAuthenticationServiceException() {
    var client = mock(ServiceHttpClient.class);
    when(client.send(anyString(), anyString(), anyString(), any(), any(), anyString()))
        .thenThrow(new UpstreamUnavailableException());
    var resolver = new HttpUserIdentityResolver("https://identity.test", client, new ObjectMapper());
    var jwt =
        Jwt.withTokenValue("tok")
            .header("alg", "EdDSA")
            .subject("alice")
            .build();

    assertThatThrownBy(() -> resolver.resolve(jwt))
        .isInstanceOf(AuthenticationServiceException.class)
        .hasCauseInstanceOf(UpstreamUnavailableException.class);
  }

  @Test
  void internalSecurityFailsFastOnUnknownModeOrBlankEmails() {
    var config = new InternalSecurityConfiguration();
    var http = mock(HttpSecurity.class, RETURNS_SELF);

    assertThatThrownBy(() -> config.internalSecurity(http, "typo-mode", "", "", ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Unknown INTERNAL_AUTH_MODE");

    assertThatThrownBy(
            () -> config.internalSecurity(http, "google", "https://billing.test", " ,  , ", ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Internal Google audience and callers are required");
  }
}
