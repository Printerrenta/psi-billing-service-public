package lt.printerrental.transport;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ServiceHttpClient {
  private static final Logger LOG = LoggerFactory.getLogger(ServiceHttpClient.class);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
  private final ServiceCredentials credentials;
  private final HttpClient client =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public ServiceHttpClient(ServiceCredentials credentials) {
    this.credentials = credentials;
  }

  public HttpResponse<byte[]> send(
      String serviceUrl,
      String path,
      String method,
      byte[] body,
      String contentType,
      String bearer) {
    String base = null;
    String normalizedPath =
        path == null ? "" : (path.isEmpty() || path.startsWith("/") ? path : "/" + path);
    try {
      base = normalizeBaseUrl(serviceUrl);
      var target = URI.create(base + normalizedPath);
      var request = HttpRequest.newBuilder(target).timeout(REQUEST_TIMEOUT);
      if (contentType != null) request.header("Content-Type", contentType);
      if (bearer != null) request.header("Authorization", bearer);
      var cloud = credentials.identityToken(base);
      if (cloud != null) request.header("X-Serverless-Authorization", "Bearer " + cloud);
      request.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(body));
      return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      LOG.warn(
          "Upstream request interrupted: {} {}{}", method, base, stripQuery(normalizedPath), error);
      throw new UpstreamUnavailableException();
    } catch (Exception error) {
      LOG.warn("Upstream request failed: {} {}{}", method, base, stripQuery(normalizedPath), error);
      throw new UpstreamUnavailableException();
    }
  }

  private static String normalizeBaseUrl(String serviceUrl) {
    if (serviceUrl == null || serviceUrl.isBlank())
      throw new IllegalArgumentException("Service URL is required");
    var base = serviceUrl.strip();
    while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    return base;
  }

  private static String stripQuery(String path) {
    int query = path.indexOf('?');
    return query >= 0 ? path.substring(0, query) : path;
  }
}
