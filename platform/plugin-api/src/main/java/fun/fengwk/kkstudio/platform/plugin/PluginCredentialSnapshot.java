package fun.fengwk.kkstudio.platform.plugin;

import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次调用所需的解密凭据快照：只有 Plugin 侧调用与刷新路径能看到它。
 *
 * <p>与 {@link PluginCredentialMaterial} 一样只有两种真实形态：交互凭据携带 region 与 {@code expiresAt}，静态密钥两者都为
 * {@code null}（没有过期，也没有自动刷新）。
 *
 * <p>{@code version} 与 {@code encryptedPayload} 是解析或领取该快照时的行版本和原始密文，供调用期认证拒绝做 CAS；密文本身不是可展示的秘密， 但
 * {@link #toString()} 仍然省略它。{@code payloadJson} 是 {@link PluginCredentialMaterial#payloadJson()}
 * 的原文。
 *
 * <p>{@code authGeneration} 与 {@code contextFingerprint}
 * 是解析该快照时的权威认证身份：调用期作废与迟到刷新都必须带上它们，才能区分「同一行的旧状态」与「断连重建后的 同版本新行」以及「同一代际下配置目标变化后的新上下文」。它们随快照作为调用期拒绝的
 * CAS 条件之一，同样不出现在管理面投影中。
 */
public record PluginCredentialSnapshot(
    String pluginId,
    UUID authGeneration,
    String contextFingerprint,
    String region,
    Instant expiresAt,
    long version,
    byte[] encryptedPayload,
    String payloadJson) {

  public PluginCredentialSnapshot {
    pluginId = Objects.requireNonNull(pluginId, "pluginId");
    authGeneration = Objects.requireNonNull(authGeneration, "authGeneration");
    contextFingerprint = Objects.requireNonNull(contextFingerprint, "contextFingerprint");
    encryptedPayload = Objects.requireNonNull(encryptedPayload, "encryptedPayload").clone();
    payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
    if ((region == null) != (expiresAt == null)) {
      throw new IllegalArgumentException(
          "a snapshot must carry region and expiresAt together or neither");
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
  }

  @Override
  public byte[] encryptedPayload() {
    return encryptedPayload.clone();
  }

  /** 该快照的冻结凭据身份引用，供 scoped 访问核对权威 manifest 与行是否仍属于同一代际/上下文。 */
  public PluginCredentialReference reference() {
    return new PluginCredentialReference(pluginId, authGeneration, contextFingerprint);
  }

  /** 该快照是否为交互凭据（携带 region 与过期时刻）。 */
  public boolean interactive() {
    return region != null;
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
        && authGeneration.equals(that.authGeneration)
        && contextFingerprint.equals(that.contextFingerprint)
        && Objects.equals(region, that.region)
        && Objects.equals(expiresAt, that.expiresAt)
        && Arrays.equals(encryptedPayload, that.encryptedPayload)
        && payloadJson.equals(that.payloadJson);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        pluginId,
        authGeneration,
        contextFingerprint,
        region,
        expiresAt,
        version,
        Arrays.hashCode(encryptedPayload),
        payloadJson);
  }

  @Override
  public String toString() {
    return "PluginCredentialSnapshot[pluginId="
        + pluginId
        + ", authGeneration="
        + authGeneration
        + ", contextFingerprint="
        + contextFingerprint
        + ", region="
        + region
        + ", expiresAt="
        + expiresAt
        + ", version="
        + version
        + ", payload=<redacted>]";
  }
}
