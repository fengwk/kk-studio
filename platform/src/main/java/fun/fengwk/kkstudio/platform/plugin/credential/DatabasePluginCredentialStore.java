package fun.fengwk.kkstudio.platform.plugin.credential;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.PluginKeyUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;

import javax.crypto.SecretKey;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code plugin_credential} 上唯一的凭据读写实现。
 *
 * <p>读路径先确认主密钥可用，再做 GCM 认证解密；写路径先在内存中加密，再以单条 upsert 落库。失败一律 fail closed：主密钥不可用或密文认证失败时既不返回
 * 旧凭据也不写新凭据，而是以 {@code KEY_UNAVAILABLE} 表达。
 *
 * <p>{@link #resolve(String)} 还要反映刷新互斥：claim 后新的调用解析返回可重试的 {@code AUTH_REFRESHING}，避免与可能使旧 token
 * 失效的 renewal 并发；已经取得快照的调用允许自然结束。
 */
public final class DatabasePluginCredentialStore implements PluginCredentialStore {

  private final PluginCredentialRepository repository;
  private final PluginCredentialCodec codec;
  private final PluginCredentialKeyLoader keyLoader;
  private final Clock clock;

  public DatabasePluginCredentialStore(
      PluginCredentialRepository repository,
      PluginCredentialCodec codec,
      PluginCredentialKeyLoader keyLoader,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.keyLoader = Objects.requireNonNull(keyLoader, "keyLoader");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public PluginCredentialProjection projection(String pluginId) {
    SecretKey key = keyLoader.load().orElse(null);
    if (key == null) {
      return PluginCredentialProjection.keyUnavailable();
    }
    Optional<PluginCredentialRow> row = repository.find(pluginId);
    if (row.isEmpty()) {
      return PluginCredentialProjection.notConnected();
    }
    PluginCredentialRow credential = row.get();
    if (!decryptable(key, credential)) {
      return PluginCredentialProjection.keyUnavailable();
    }
    return new PluginCredentialProjection(
        credential.status(),
        credential.region(),
        credential.expiresAt(),
        credential.nextRefreshAt(),
        credential.lastRefreshedAt(),
        credential.lastRefreshError());
  }

  @Override
  public PluginCredentialSnapshot resolve(String pluginId) {
    SecretKey key = keyLoader.load().orElse(null);
    if (key == null) {
      throw unavailable(
          PluginCredentialUnavailableReason.KEY_UNAVAILABLE,
          "plugin credential key is unavailable");
    }
    Optional<PluginCredentialRow> row = repository.find(pluginId);
    if (row.isEmpty()) {
      throw unavailable(PluginCredentialUnavailableReason.NOT_CONNECTED, "plugin is not connected");
    }
    PluginCredentialRow credential = row.get();
    String payload = decrypt(key, credential);

    Instant now = clock.instant();
    if (credential.leased() && credential.refreshLeaseUntil().isAfter(now)) {
      throw unavailable(
          PluginCredentialUnavailableReason.AUTH_REFRESHING,
          "plugin credential refresh is in flight");
    }
    switch (credential.status()) {
      case REAUTH_REQUIRED -> throw unavailable(
          PluginCredentialUnavailableReason.REAUTH_REQUIRED,
          "plugin credential was rejected and must be re-authenticated");
      case REFRESH_UNCERTAIN -> throw unavailable(
          PluginCredentialUnavailableReason.REFRESH_UNCERTAIN,
          "plugin credential refresh outcome is unknown; re-authenticate the plugin");
      case CONNECTED, REFRESH_FAILED -> {
        // REFRESH_FAILED 只表示一次可证明未发出的刷新失败：旧凭据在本地时限内仍然可用。
      }
      default -> throw unavailable(
          PluginCredentialUnavailableReason.KEY_UNAVAILABLE,
          "plugin credential status is not readable");
    }
    if (!credential.expiresAt().isAfter(now)) {
      throw unavailable(
          PluginCredentialUnavailableReason.EXPIRED,
          "plugin credential is expired; re-authenticate the plugin");
    }
    return new PluginCredentialSnapshot(
        credential.pluginId(), credential.region(), credential.expiresAt(), payload);
  }

  @Override
  public PluginCredentialProjection save(String pluginId, PluginCredentialMaterial material) {
    Objects.requireNonNull(material, "material");
    SecretKey key = keyLoader.load().orElse(null);
    if (key == null) {
      throw new PluginKeyUnavailableException("plugin credential key is unavailable");
    }
    Instant now = clock.instant();
    if (!material.expiresAt().isAfter(now)) {
      throw new IllegalArgumentException("credential expiresAt must be in the future");
    }
    if (material.nextRefreshAt().isBefore(now)) {
      throw new IllegalArgumentException("credential nextRefreshAt must not be in the past");
    }
    byte[] envelope = codec.encrypt(key, pluginId, material.region(), material.payloadJson());
    PluginCredentialRow row =
        new PluginCredentialRow(
            pluginId,
            envelope,
            material.region(),
            material.expiresAt(),
            material.nextRefreshAt(),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            null,
            null,
            0L,
            now,
            now);
    repository.upsert(row);
    return projection(pluginId);
  }

  @Override
  public void delete(String pluginId) {
    repository.delete(pluginId);
  }

  private boolean decryptable(SecretKey key, PluginCredentialRow credential) {
    try {
      decrypt(key, credential);
      return true;
    } catch (PluginCredentialUnavailableException error) {
      return false;
    }
  }

  private String decrypt(SecretKey key, PluginCredentialRow credential) {
    try {
      return codec.decrypt(
          key, credential.pluginId(), credential.region(), credential.encryptedPayload());
    } catch (RuntimeException error) {
      throw unavailable(
          PluginCredentialUnavailableReason.KEY_UNAVAILABLE,
          "plugin credential payload cannot be authenticated");
    }
  }

  private static PluginCredentialUnavailableException unavailable(
      PluginCredentialUnavailableReason reason, String message) {
    return new PluginCredentialUnavailableException(reason, message);
  }
}
