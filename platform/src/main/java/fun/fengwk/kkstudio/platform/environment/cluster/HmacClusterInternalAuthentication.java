package fun.fengwk.kkstudio.platform.environment.cluster;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@link ClusterInternalAuthentication} 的 HMAC-SHA256 实现。
 *
 * <p>签名材料为 {@code method \n canonicalRequestPath \n targetInstanceId \n timestamp \n
 * nonce}。校验固定头名、限制头值长度、 校验目标实例、时间窗口与签名常量时间比较，并以 30s 有界内存表拒绝 nonce 重放；任何失败都抛 {@link
 * ClusterInternalAuthenticationException} 且绝不回显秘密。
 */
public final class HmacClusterInternalAuthentication implements ClusterInternalAuthentication {

  public static final String HEADER_TARGET = "X-KK-Cluster-Target";
  public static final String HEADER_TIMESTAMP = "X-KK-Cluster-Timestamp";
  public static final String HEADER_NONCE = "X-KK-Cluster-Nonce";
  public static final String HEADER_SIGNATURE = "X-KK-Cluster-Signature";

  /** 单个认证头的最大允许长度；超出即 fail-closed，避免异常放大或注入。 */
  private static final int MAX_HEADER_LENGTH = 256;

  /** canonical requestPath 的最大允许长度（含 query）；超出即 fail-closed。 */
  private static final int MAX_PATH_LENGTH = 8192;

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final int DEFAULT_MAX_TRACKED_NONCES = 65_536;

  private final byte[] secret;
  private final boolean configured;
  private final UUID localNodeId;
  private final Clock clock;
  private final Duration authWindow;
  private final int maxTrackedNonces;

  /** 有界重放表：nonce -> 首次观察到的时间戳毫秒。只保留 {@code 2 * authWindow} 内的记录。 */
  private final Map<String, Long> seenNonces = new HashMap<>();

  public HmacClusterInternalAuthentication(
      ClusterProperties properties, UUID localNodeId, Clock clock) {
    this(
        properties.getSecret(),
        localNodeId,
        clock,
        properties.getAuthWindow(),
        DEFAULT_MAX_TRACKED_NONCES);
  }

  public HmacClusterInternalAuthentication(
      String secret, UUID localNodeId, Clock clock, Duration authWindow, int maxTrackedNonces) {
    Objects.requireNonNull(localNodeId, "localNodeId");
    Objects.requireNonNull(clock, "clock");
    Objects.requireNonNull(authWindow, "authWindow");
    if (authWindow.isZero() || authWindow.isNegative()) {
      throw new IllegalArgumentException("authWindow must be positive");
    }
    if (maxTrackedNonces <= 0) {
      throw new IllegalArgumentException("maxTrackedNonces must be positive");
    }
    this.configured = secret != null && !secret.isBlank();
    this.secret = this.configured ? secret.getBytes(StandardCharsets.UTF_8) : new byte[0];
    this.localNodeId = localNodeId;
    this.clock = clock;
    this.authWindow = authWindow;
    this.maxTrackedNonces = maxTrackedNonces;
  }

  @Override
  public Map<String, String> sign(String method, String requestPath, ClusterIdeOwnerRoute owner) {
    Objects.requireNonNull(owner, "owner");
    requireConfigured();
    long timestamp = clock.millis();
    String nonce = UUID.randomUUID().toString();
    String signature = signature(method, requestPath, owner.nodeInstanceId(), timestamp, nonce);
    return Map.of(
        HEADER_TARGET,
        owner.nodeInstanceId().toString(),
        HEADER_TIMESTAMP,
        Long.toString(timestamp),
        HEADER_NONCE,
        nonce,
        HEADER_SIGNATURE,
        signature);
  }

  @Override
  public void verify(String method, String requestPath, Map<String, String> headers) {
    requireConfigured();
    if (headers == null) {
      throw new ClusterInternalAuthenticationException("missing internal authentication headers");
    }
    String target = header(headers, HEADER_TARGET);
    String timestamp = header(headers, HEADER_TIMESTAMP);
    String nonce = header(headers, HEADER_NONCE);
    String signature = header(headers, HEADER_SIGNATURE);
    if (target == null || timestamp == null || nonce == null || signature == null) {
      throw new ClusterInternalAuthenticationException("missing internal authentication headers");
    }
    if (target.length() > MAX_HEADER_LENGTH
        || timestamp.length() > MAX_HEADER_LENGTH
        || nonce.length() > MAX_HEADER_LENGTH
        || signature.length() > MAX_HEADER_LENGTH) {
      throw new ClusterInternalAuthenticationException("internal authentication header too large");
    }
    UUID targetNodeId;
    try {
      targetNodeId = UUID.fromString(target);
    } catch (IllegalArgumentException error) {
      throw new ClusterInternalAuthenticationException("malformed internal authentication target");
    }
    if (!targetNodeId.equals(localNodeId)) {
      throw new ClusterInternalAuthenticationException("internal authentication target mismatch");
    }
    long requestTimestamp;
    try {
      requestTimestamp = Long.parseLong(timestamp);
    } catch (NumberFormatException error) {
      throw new ClusterInternalAuthenticationException(
          "malformed internal authentication timestamp");
    }
    long now = clock.millis();
    if (Math.abs(now - requestTimestamp) > authWindow.toMillis()) {
      throw new ClusterInternalAuthenticationException(
          "internal authentication timestamp out of window");
    }
    try {
      UUID.fromString(nonce);
    } catch (IllegalArgumentException error) {
      throw new ClusterInternalAuthenticationException("malformed internal authentication nonce");
    }
    String expected = signature(method, requestPath, targetNodeId, requestTimestamp, nonce);
    if (!MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        signature.getBytes(StandardCharsets.US_ASCII))) {
      throw new ClusterInternalAuthenticationException(
          "internal authentication signature mismatch");
    }
    recordNonce(nonce, now);
  }

  private void recordNonce(String nonce, long now) {
    long cutoff = now - 2L * authWindow.toMillis();
    synchronized (seenNonces) {
      seenNonces.entrySet().removeIf(entry -> entry.getValue() < cutoff);
      if (seenNonces.containsKey(nonce)) {
        throw new ClusterInternalAuthenticationException("internal authentication replay rejected");
      }
      if (seenNonces.size() >= maxTrackedNonces) {
        throw new ClusterInternalAuthenticationException(
            "internal authentication nonce capacity exceeded");
      }
      seenNonces.put(nonce, now);
    }
  }

  private String signature(
      String method, String requestPath, UUID target, long timestamp, String nonce) {
    String canonical =
        normalizeMethod(method)
            + "\n"
            + requireCanonicalPath(requestPath)
            + "\n"
            + target
            + "\n"
            + timestamp
            + "\n"
            + nonce;
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
      return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException error) {
      throw new IllegalStateException("HMAC-SHA256 is unavailable", error);
    }
  }

  private static String normalizeMethod(String method) {
    Objects.requireNonNull(method, "method");
    String normalized = method.trim().toUpperCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException("method must not be blank");
    }
    return normalized;
  }

  private static String requireCanonicalPath(String requestPath) {
    Objects.requireNonNull(requestPath, "requestPath");
    if (requestPath.isEmpty() || requestPath.charAt(0) != '/') {
      throw new IllegalArgumentException("requestPath must be an absolute path");
    }
    if (requestPath.length() > MAX_PATH_LENGTH) {
      throw new IllegalArgumentException("requestPath is too long");
    }
    for (int index = 0; index < requestPath.length(); index++) {
      char character = requestPath.charAt(index);
      if (character < 0x20 || character == 0x7F) {
        throw new IllegalArgumentException("requestPath must not contain control characters");
      }
    }
    return requestPath;
  }

  private void requireConfigured() {
    if (!configured) {
      throw new ClusterInternalAuthenticationException(
          "kk-studio.cluster.secret is not configured");
    }
  }

  private static String header(Map<String, String> headers, String name) {
    String direct = headers.get(name);
    if (direct != null) {
      return direct;
    }
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
        return entry.getValue();
      }
    }
    return null;
  }
}
