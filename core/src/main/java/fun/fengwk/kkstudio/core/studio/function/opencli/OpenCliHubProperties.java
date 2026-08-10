package fun.fengwk.kkstudio.core.studio.function.opencli;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** Canvas Function 访问 OpenCLI Hub 的共享、无凭证配置。 */
@ConfigurationProperties("kk-studio.opencli-hub")
@Data
public class OpenCliHubProperties {

  private boolean enabled;
  private URI baseUrl = URI.create("http://vps-opencli-hub:8080");
  private String instanceId;
  private Duration connectTimeout = Duration.ofSeconds(5);
  private Duration requestTimeout = Duration.ofMinutes(2);
  private Duration longPollTimeout = Duration.ofSeconds(130);
  private int streamBufferBytes = 16 * 1024;
  private int maxJsonResponseBytes = 512 * 1024;
  private int maxErrorResponseBytes = 4096;
  private int maxOutputChars = 65_535;

  public void validate() {
    if (baseUrl == null
        || baseUrl.getScheme() == null
        || (!"http".equalsIgnoreCase(baseUrl.getScheme())
            && !"https".equalsIgnoreCase(baseUrl.getScheme()))
        || baseUrl.getHost() == null
        || baseUrl.getUserInfo() != null
        || baseUrl.getQuery() != null
        || baseUrl.getFragment() != null
        || (baseUrl.getPath() != null
            && !baseUrl.getPath().isEmpty()
            && !"/".equals(baseUrl.getPath()))) {
      throw new IllegalArgumentException(
          "opencli-hub baseUrl must be an HTTP(S) origin without path, userinfo, query or fragment");
    }
    requireBounded(connectTimeout, Duration.ofMillis(1), Duration.ofMinutes(2), "connectTimeout");
    requireBounded(requestTimeout, Duration.ofSeconds(1), Duration.ofMinutes(30), "requestTimeout");
    requirePositive(longPollTimeout, "longPollTimeout");
    if (longPollTimeout.compareTo(Duration.ofSeconds(121)) < 0
        || longPollTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
      throw new IllegalArgumentException(
          "longPollTimeout must be between 121 seconds and 10 minutes");
    }
    if (streamBufferBytes < 1024 || streamBufferBytes > 1024 * 1024) {
      throw new IllegalArgumentException("streamBufferBytes must be between 1024 and 1048576");
    }
    if (maxJsonResponseBytes < 1024 || maxJsonResponseBytes > 4 * 1024 * 1024) {
      throw new IllegalArgumentException("maxJsonResponseBytes must be between 1024 and 4194304");
    }
    if (maxErrorResponseBytes < 256 || maxErrorResponseBytes > 64 * 1024) {
      throw new IllegalArgumentException("maxErrorResponseBytes must be between 256 and 65536");
    }
    if (maxOutputChars < 1024 || maxOutputChars > 1_000_000) {
      throw new IllegalArgumentException("maxOutputChars must be between 1024 and 1000000");
    }
    if (instanceId != null) {
      instanceId = instanceId.strip();
      if (instanceId.isEmpty() || instanceId.length() > 36) {
        throw new IllegalArgumentException("instanceId must be absent or 1..36 characters");
      }
    }
  }

  private static void requirePositive(Duration value, String field) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }

  private static void requireBounded(Duration value, Duration min, Duration max, String field) {
    if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
      throw new IllegalArgumentException(field + " is outside the supported range");
    }
  }
}
