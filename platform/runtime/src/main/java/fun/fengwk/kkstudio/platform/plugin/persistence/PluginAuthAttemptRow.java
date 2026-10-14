package fun.fengwk.kkstudio.platform.plugin.persistence;

import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogFingerprint;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@code plugin_auth_attempt} 的单行状态：一次 pending 交互式认证 attempt 的冻结期望。
 *
 * <p>它只承载 nonce 摘要、期望认证身份（代际 + 上下文指纹）、期望配置修订、用户选定 region 与过期时刻：既不保存回调地址、token、登录 URL，也不保存明文秘密或原始
 * nonce。因此 complete 无法从本行恢复出任何可用秘密，只能证明「该 nonce 曾被本部署签发且尚未被消费」。
 *
 * @param pluginId Plugin 安装身份，同时也是该 pending attempt 的唯一键
 * @param nonceHash 一次性 nonce 的 64 位小写 SHA-256 摘要
 * @param authGeneration prepare 时该 Plugin 的认证代际
 * @param contextFingerprint prepare 时该 Plugin 的认证上下文指纹
 * @param configRevision prepare 时该 Plugin 的配置修订
 * @param region 用户选定的 region 候选，完成材料必须与它逐字相等
 * @param expiresAt attempt 失效时刻（毫秒精度），必须严格晚于 prepare 时刻
 */
public record PluginAuthAttemptRow(
    String pluginId,
    String nonceHash,
    UUID authGeneration,
    String contextFingerprint,
    long configRevision,
    String region,
    Instant expiresAt) {

  private static final Pattern REGION_SYNTAX = Pattern.compile("[\\x21-\\x7e]{1,64}");

  public PluginAuthAttemptRow {
    Objects.requireNonNull(pluginId, "pluginId");
    Objects.requireNonNull(nonceHash, "nonceHash");
    Objects.requireNonNull(authGeneration, "authGeneration");
    Objects.requireNonNull(contextFingerprint, "contextFingerprint");
    Objects.requireNonNull(region, "region");
    Objects.requireNonNull(expiresAt, "expiresAt");
    if (!PluginCatalogFingerprint.isFingerprint(nonceHash)) {
      throw new IllegalArgumentException("nonceHash must be a 64-char lowercase SHA-256");
    }
    if (!PluginCatalogFingerprint.isFingerprint(contextFingerprint)) {
      throw new IllegalArgumentException("contextFingerprint must be a 64-char lowercase SHA-256");
    }
    if (configRevision <= 0) {
      throw new IllegalArgumentException("configRevision must be positive");
    }
    if (!REGION_SYNTAX.matcher(region).matches()) {
      throw new IllegalArgumentException("region must be a bounded non-blank printable candidate");
    }
  }
}
