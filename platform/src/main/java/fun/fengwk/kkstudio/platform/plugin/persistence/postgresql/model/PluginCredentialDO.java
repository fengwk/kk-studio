package fun.fengwk.kkstudio.platform.plugin.persistence.postgresql.model;

import lombok.Data;

import java.time.Instant;

/** {@code plugin_credential} 行映射（持久化层）；密文列只在持久化与加解密路径间流动，绝不进入 DTO 或日志。 */
@Data
public class PluginCredentialDO {

  private String pluginId;

  private byte[] encryptedPayload;

  private String region;

  private Instant expiresAt;

  private Instant nextRefreshAt;

  private String status;

  private Instant lastRefreshedAt;

  private String lastRefreshError;

  private String refreshLeaseToken;

  private Instant refreshLeaseUntil;

  private long version;

  private Instant createTime;

  private Instant updateTime;
}
