package fun.fengwk.kkstudio.platform.plugin;

import java.time.Instant;
import java.util.Objects;

/**
 * 一次调用所需的凭据快照：只有 Plugin 侧调用与刷新路径能看到它。
 *
 * <p>与 {@link PluginCredentialMaterial} 一样只有两种形态：交互凭据携带 region 与 {@code expiresAt}，静态密钥两者都为 {@code
 * null}。{@code version} 是读取时的行版本，调用期认证拒绝以它做 CAS，因此迟到的拒绝不会作废已经替换的新凭据。 {@link #toString()} 省略 {@code
 * payloadJson}。
 */
public record PluginCredentialSnapshot(
    String pluginId, String region, Instant expiresAt, long version, String payloadJson) {

  public PluginCredentialSnapshot {
    pluginId = Objects.requireNonNull(pluginId, "pluginId");
    payloadJson = Objects.requireNonNull(payloadJson, "payloadJson");
    if ((region == null) != (expiresAt == null)) {
      throw new IllegalArgumentException(
          "a snapshot must carry region and expiresAt together or neither");
    }
    if (version < 0) {
      throw new IllegalArgumentException("version must not be negative");
    }
  }

  /** 是否为交互凭据（携带 region 与本地时限）。 */
  public boolean interactive() {
    return region != null;
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
