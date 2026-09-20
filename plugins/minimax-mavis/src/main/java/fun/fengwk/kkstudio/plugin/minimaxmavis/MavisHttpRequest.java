package fun.fengwk.kkstudio.plugin.minimaxmavis;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 一次 Mavis HTTP 请求。
 *
 * <p>renewal 的 query 与认证 header 直接携带凭据，因此 {@link #toString()} 只输出方法、退化后的安全 URL 与超时，绝不输出 header 与
 * body。
 */
public record MavisHttpRequest(
    String method, String url, Map<String, String> headers, String body, Duration timeout) {

  public MavisHttpRequest {
    if (method == null || method.isBlank()) {
      throw new MavisValidationException("HTTP method must not be empty");
    }
    if (url == null || url.isBlank()) {
      throw new MavisValidationException("HTTP url must not be empty");
    }
    headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
    timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isNegative() || timeout.isZero()) {
      throw new MavisValidationException("HTTP timeout must be positive");
    }
  }

  @Override
  public String toString() {
    return "MavisHttpRequest[method="
        + method
        + ", url="
        + MavisRedaction.safeUrl(url)
        + ", timeout="
        + timeout
        + "]";
  }
}
