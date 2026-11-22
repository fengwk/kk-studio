package fun.fengwk.kkstudio.platform.daemon;

import fun.fengwk.kkstudio.common.resource.ResourceRef;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Daemon 直传对象存储所需的预签名 PUT 事实。
 *
 * <p>只包含调用方发起请求所必需的信息：方法、已签名 URL、必须显式设置的已签名 headers 与签名过期 epoch 毫秒。bucket 与对象物理 key 由服务端从 uploadId
 * 确定性推导，绝不进入 wire。
 *
 * <p>{@code expiresAtEpochMs} 是权威的签名有效期事实，随票据完整保留；实际请求是否仍被接受最终由对象存储判定。
 */
public record DaemonPresignedPut(
    String method, String url, Map<String, String> headers, long expiresAtEpochMs) {

  /** URL 的字符上限。 */
  public static final int MAX_URL_CHARS = 4096;

  /** headers 的条目上限。 */
  public static final int MAX_HEADERS = 16;

  /** 单个 header 名与值的 UTF-8 字节上限；与 Daemon(Go) 的 {@code len(...) < 1024} 同口径。 */
  public static final int MAX_HEADER_UTF8_BYTES = 1024;

  public DaemonPresignedPut {
    method = requireNonBlank(method, "method");
    if (!"PUT".equals(method)) {
      throw new IllegalArgumentException("method must be PUT");
    }
    url = requireNonBlank(url, "url");
    if (url.length() > MAX_URL_CHARS) {
      throw new IllegalArgumentException("url must not exceed " + MAX_URL_CHARS + " characters");
    }
    headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
    if (headers.size() > MAX_HEADERS) {
      throw new IllegalArgumentException("headers must not exceed " + MAX_HEADERS + " entries");
    }
    headers.forEach(
        (name, value) -> {
          requireNonBlank(name, "header name");
          requireNonBlank(value, "header value");
          if (utf8Length(name, "header name") > MAX_HEADER_UTF8_BYTES
              || utf8Length(value, "header value") > MAX_HEADER_UTF8_BYTES) {
            throw new IllegalArgumentException(
                "header name and value must not exceed " + MAX_HEADER_UTF8_BYTES + " UTF-8 bytes");
          }
        });
    requireEpochMillis(expiresAtEpochMs, "expiresAtEpochMs");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static long requireEpochMillis(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return value;
  }

  private static int utf8Length(String value, String name) {
    try {
      return ResourceRef.utf8Length(value, name);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException(name + " must be valid Unicode", error);
    }
  }

  /** 签名过期时刻；仅供读取方换算展示。 */
  public Instant expiresAt() {
    return Instant.ofEpochMilli(expiresAtEpochMs);
  }
}
