package fun.fengwk.kkstudio.platform.daemon.server;

import fun.fengwk.kkstudio.platform.daemon.protocol.ImageInputTier;

import java.time.Duration;
import java.util.Objects;

/** 核心每次判定现读的运行期设置；只包含平台资源约束。 */
public record EnvironmentServerSettings(
    Duration heartbeatTimeout,
    long maxOutputBytes,
    ImageInputTier imageInputTier,
    long temporaryResourceTtlSeconds,
    long temporaryResourceCleanupIntervalSeconds) {

  public EnvironmentServerSettings {
    heartbeatTimeout = Objects.requireNonNull(heartbeatTimeout, "heartbeatTimeout");
    if (heartbeatTimeout.isZero() || heartbeatTimeout.isNegative()) {
      throw new IllegalArgumentException("heartbeatTimeout must be positive");
    }
    if (maxOutputBytes <= 0L) {
      throw new IllegalArgumentException("maxOutputBytes must be positive");
    }
    imageInputTier = Objects.requireNonNull(imageInputTier, "imageInputTier");
    if (imageInputTier == ImageInputTier.IMAGE_INPUT_TIER_UNSPECIFIED
        || imageInputTier == ImageInputTier.UNRECOGNIZED) {
      throw new IllegalArgumentException("imageInputTier must be a supported image tier");
    }
    if (temporaryResourceTtlSeconds <= 0L) {
      throw new IllegalArgumentException("temporaryResourceTtlSeconds must be positive");
    }
    if (temporaryResourceCleanupIntervalSeconds <= 0L) {
      throw new IllegalArgumentException(
          "temporaryResourceCleanupIntervalSeconds must be positive");
    }
  }
}
