package fun.fengwk.kkstudio.platform.plugin.persistence;

import java.time.Instant;
import java.util.Objects;

/** 一个待完成的交互式认证 attempt：只有 nonce 摘要、prepare 时的配置修订、选定 region 与过期时刻。 */
public record PluginAuthAttemptRow(
    String pluginId, String nonceHash, long configRevision, String region, Instant expiresAt) {

  public PluginAuthAttemptRow {
    Objects.requireNonNull(pluginId, "pluginId");
    Objects.requireNonNull(nonceHash, "nonceHash");
    Objects.requireNonNull(region, "region");
    Objects.requireNonNull(expiresAt, "expiresAt");
  }
}
