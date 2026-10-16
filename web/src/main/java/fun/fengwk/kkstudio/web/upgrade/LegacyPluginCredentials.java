package fun.fengwk.kkstudio.web.upgrade;

import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialReference;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;

import javax.crypto.SecretKey;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 旧 Plugin 凭据的重封装与新增静态密钥：同一张表、两种真实来源，绝不做增量合并或静默兜底。
 *
 * <ul>
 *   <li>旧行只接受「已安装 + 当前真实 DeepLink 声明兼容的 region」，用旧 format 1 读取器与源主密钥解出 opaque 明文，再用当前 format 2
 *       codec 与目标主密钥、候选的真实认证代际 / 上下文指纹重新加密；全部旧状态、版本与时间原样保留，lease 已被排空门禁要求为空，绝不自动续期、登录或把 expired 改写成
 *       CONNECTED。
 *   <li>新静态密钥只来自显式材料：region / expiresAt / nextRefreshAt 全部为 null、CONNECTED、version 0、lease 与刷新字段为
 *       null、创建与更新时刻为操作时刻。
 * </ul>
 *
 * <p>每条写入后都在同一事务里回读并按 format 2 真实 scope 解密，与原明文逐字节比较；比较结果只以「不一致」表达，绝不输出明文或密文片段。
 *
 * <p>包内可见（package-private）：它只是离线升级包内部的凭据边界，不是平台 API。
 */
final class LegacyPluginCredentials {

  private static final PluginCredentialCodec CODEC = new PluginCredentialCodec();

  private static final String LEGACY_ROWS =
      """
      select plugin_id, encrypted_payload, region, expires_at, next_refresh_at, status,
             last_refreshed_at, last_refresh_error, version, create_time, update_time
      from plugin_credential
      order by plugin_id
      """;

  private LegacyPluginCredentials() {}

  /**
   * 逐行重封装全部旧凭据，并在写入后回读校验。
   *
   * @return 实际重封装的旧凭据行数
   */
  static long rewriteLegacy(
      Connection source,
      PluginCredentialRepository repository,
      StudioPluginRegistry registry,
      Map<String, PluginCredentialReference> references,
      SecretKey sourceKey,
      SecretKey targetKey) {
    long rewritten = 0;
    try (Statement statement = source.createStatement();
        ResultSet rows = statement.executeQuery(LEGACY_ROWS)) {
      while (rows.next()) {
        String pluginId = rows.getString("plugin_id");
        String region = rows.getString("region");
        StudioPlugin plugin =
            registry
                .find(pluginId)
                .orElseThrow(
                    () -> reject("legacy credential belongs to a plugin that is not installed"));
        if (!(plugin.authenticationSpec() instanceof PluginAuthSpec.DeepLink deepLink)) {
          throw reject("legacy credential belongs to a plugin without deep-link authentication");
        }
        if (region == null || !deepLink.acceptsRegion(region)) {
          throw reject("legacy credential region is not declared by the installed plugin");
        }
        if (sourceKey == null || targetKey == null) {
          throw reject("legacy credential keys are required to rewrite stored credentials");
        }
        String plaintext =
            LegacyPluginCredentialCodec.decrypt(
                sourceKey, pluginId, region, rows.getBytes("encrypted_payload"));
        PluginCredentialReference reference = reference(references, pluginId);
        byte[] envelope =
            CODEC.encrypt(
                targetKey,
                pluginId,
                reference.authGeneration(),
                reference.contextFingerprint(),
                region,
                plaintext);
        PluginCredentialRow row =
            new PluginCredentialRow(
                pluginId,
                reference.authGeneration(),
                reference.contextFingerprint(),
                envelope,
                region,
                instant(rows, "expires_at"),
                instant(rows, "next_refresh_at"),
                status(rows.getString("status")),
                instant(rows, "last_refreshed_at"),
                rows.getString("last_refresh_error"),
                null,
                null,
                rows.getLong("version"),
                instant(rows, "create_time"),
                instant(rows, "update_time"));
        insert(repository, row);
        verify(repository, pluginId, plaintext, targetKey);
        rewritten++;
      }
    } catch (SQLException error) {
      throw reject("cannot read legacy plugin credentials");
    }
    return rewritten;
  }

  /**
   * 为每个显式静态材料写入一行真实静态密钥，并在写入后回读校验。
   *
   * @return 实际写入的静态密钥行数
   */
  static long installStatic(
      PluginCredentialRepository repository,
      LegacyStaticCredentials staticCredentials,
      Map<String, PluginCredentialReference> references,
      SecretKey targetKey,
      Instant now) {
    Map<String, PluginCredentialMaterial> materials = staticCredentials.materials();
    for (Map.Entry<String, PluginCredentialMaterial> entry : materials.entrySet()) {
      String pluginId = entry.getKey();
      PluginCredentialMaterial material = entry.getValue();
      if (targetKey == null) {
        throw reject("plugin credential key is required to store static credentials");
      }
      PluginCredentialReference reference = reference(references, pluginId);
      byte[] envelope =
          CODEC.encrypt(
              targetKey,
              pluginId,
              reference.authGeneration(),
              reference.contextFingerprint(),
              null,
              material.payloadJson());
      PluginCredentialRow row =
          new PluginCredentialRow(
              pluginId,
              reference.authGeneration(),
              reference.contextFingerprint(),
              envelope,
              null,
              null,
              null,
              PluginCredentialStatus.CONNECTED,
              null,
              null,
              null,
              null,
              0L,
              now,
              now);
      insert(repository, row);
      verify(repository, pluginId, material.payloadJson(), targetKey);
    }
    return materials.size();
  }

  private static void insert(PluginCredentialRepository repository, PluginCredentialRow row) {
    if (!repository.insert(row)) {
      throw reject("a plugin credential row already exists for the converted plugin");
    }
  }

  private static void verify(
      PluginCredentialRepository repository,
      String pluginId,
      String plaintext,
      SecretKey targetKey) {
    PluginCredentialRow stored =
        repository
            .find(pluginId)
            .orElseThrow(() -> reject("plugin credential row is missing after insert"));
    String decrypted;
    try {
      decrypted =
          CODEC.decrypt(
              targetKey,
              pluginId,
              stored.authGeneration(),
              stored.contextFingerprint(),
              stored.region(),
              stored.encryptedPayload());
    } catch (RuntimeException error) {
      throw reject("stored plugin credential cannot be read back with the current format");
    }
    if (!decrypted.equals(plaintext)) {
      throw reject("stored plugin credential does not round-trip to the source payload");
    }
  }

  private static PluginCredentialReference reference(
      Map<String, PluginCredentialReference> references, String pluginId) {
    PluginCredentialReference reference = references.get(pluginId);
    if (reference == null) {
      throw reject(
          "the converted catalog candidate has no authentication reference for the plugin");
    }
    return reference;
  }

  private static PluginCredentialStatus status(String value) {
    try {
      return PluginCredentialStatus.valueOf(value);
    } catch (RuntimeException error) {
      throw reject("legacy credential carries an unknown status");
    }
  }

  private static Instant instant(ResultSet rows, String column) throws SQLException {
    OffsetDateTime value = rows.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  private static LegacyDatabaseUpgradeException reject(String reason) {
    return new LegacyDatabaseUpgradeException(
        LegacyDatabaseUpgradeException.Stage.CREDENTIAL, reason);
  }
}
