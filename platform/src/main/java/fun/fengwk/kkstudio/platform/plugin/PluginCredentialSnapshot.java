package fun.fengwk.kkstudio.platform.plugin;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次调用所需的解密凭据快照：只有 Plugin 侧刷新路径能看到它，且不携带任何 lease、version 或密文。
 *
 * <p>{@code payloadJson} 是 {@link PluginCredentialMaterial#payloadJson()} 的原文；{@link #toString()}
 * 有意省略它。
 */
public record PluginCredentialSnapshot(
    String pluginId, String region, Instant expiresAt, String payloadJson) {

  public PluginCredentialSnapshot {
    pluginId = Objects.requireNonNull(pluginId, "pluginId");
    region = Objects.requireNonNull(region, "region");
    expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
  }

  @Override
  public String toString() {
    return "PluginCredentialSnapshot[pluginId="
        + pluginId
        + ", region="
        + region
        + ", expiresAt="
        + expiresAt
        + ", payload="
        + "<redacted>"
        + "]";
  }
}
