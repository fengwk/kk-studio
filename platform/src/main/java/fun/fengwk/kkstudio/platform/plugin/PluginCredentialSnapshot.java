package fun.fengwk.kkstudio.platform.plugin;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * 一次调用所需的解密凭据快照：只有 Plugin 侧调用与刷新路径能看到它。
 *
 * <p>{@code version} 与 {@code encryptedPayload} 是解析或领取该快照时的行版本和原始密文，供调用期认证拒绝做 CAS；密文本身不是可展示的秘密， 但
 * {@link #toString()} 仍然省略它。{@code payloadJson} 是 {@link PluginCredentialMaterial#payloadJson()}
 * 的原文。
 */
public record PluginCredentialSnapshot(
    String pluginId,
    String region,
    Instant expiresAt,
    long version,
    byte[] encryptedPayload,
    String payloadJson) {

  public PluginCredentialSnapshot {
    pluginId = Objects.requireNonNull(pluginId, "pluginId");
    region = Objects.requireNonNull(region, "region");
    expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    encryptedPayload = Objects.requireNonNull(encryptedPayload, "encryptedPayload").clone();
    payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
  }

  @Override
  public byte[] encryptedPayload() {
    return encryptedPayload.clone();
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof PluginCredentialSnapshot that)) {
      return false;
    }
    return version == that.version
        && pluginId.equals(that.pluginId)
        && region.equals(that.region)
        && expiresAt.equals(that.expiresAt)
        && Arrays.equals(encryptedPayload, that.encryptedPayload)
        && payloadJson.equals(that.payloadJson);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        pluginId, region, expiresAt, version, Arrays.hashCode(encryptedPayload), payloadJson);
  }

  @Override
  public String toString() {
    return "PluginCredentialSnapshot[pluginId="
        + pluginId
        + ", region="
        + region
        + ", expiresAt="
        + expiresAt
        + ", version="
        + version
        + ", payload=<redacted>]";
  }
}
