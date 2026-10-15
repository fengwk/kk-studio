package fun.fengwk.kkstudio.platform.plugin.credential;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;

import javax.crypto.SecretKey;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code plugin_credential} 上唯一的凭据读取实现。
 *
 * <p>读路径先确认主密钥可用，再做 GCM 认证解密；失败一律 fail closed：主密钥不可用或密文认证失败时既不返回旧凭据也不写新凭据，而是以 {@code
 * KEY_UNAVAILABLE} 表达。写入不在本类：管理面只通过 catalog CAS 事务入口提交，本类不提供任何无条件写入口。
 *
 * <p>{@link #resolve(PluginCredentialReference)} 是唯一的解析入口：每次都从权威 catalog 与权威行回读，要求引用仍是当前 manifest
 * 的该 Plugin 坐标（代际 + 上下文指纹），且行仍属于同一代际与上下文；旧上下文、已撤销代际或断连重建后的新行都无法再解析。
 *
 * <p>解析还要反映刷新互斥：claim 后新的调用解析返回可重试的 {@code AUTH_REFRESHING}，避免与可能使旧 token 失效的 renewal
 * 并发；已经取得快照的调用允许自然结束。
 */
public final class DatabasePluginCredentialStore implements PluginCredentialStore {

  static final String AUTH_REJECTED_ERROR =
      "plugin credential was rejected by the provider and must be re-authenticated";
  static final String STALE_REFERENCE_ERROR =
      "plugin credential reference is no longer current for this plugin";

  private final PluginCredentialRepository repository;
  private final PluginConfigurationRepository configurationRepository;
  private final PluginCredentialCodec codec;
  private final PluginCredentialKeyLoader keyLoader;
  private final Clock clock;

  public DatabasePluginCredentialStore(
      PluginCredentialRepository repository,
      PluginConfigurationRepository configurationRepository,
      PluginCredentialCodec codec,
      PluginCredentialKeyLoader keyLoader,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.configurationRepository =
        Objects.requireNonNull(configurationRepository, "configurationRepository");
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
  public PluginCredentialSnapshot resolve(PluginCredentialReference reference) {
    Objects.requireNonNull(reference, "reference");
    SecretKey key = requireKey();
    requireCurrentReference(reference);
    Optional<PluginCredentialRow> found = repository.find(reference.pluginId());
    if (found.isEmpty()) {
      throw unavailable(PluginCredentialUnavailableReason.NOT_CONNECTED, "plugin is not connected");
    }
    PluginCredentialRow credential = found.get();
    // 行必须仍属于引用的代际与上下文：旧引用绝不拿到新 Key，新引用也绝不读取旧行。
    if (!credential.authGeneration().equals(reference.authGeneration())
        || !credential.contextFingerprint().equals(reference.contextFingerprint())) {
      throw unavailable(PluginCredentialUnavailableReason.NOT_CONNECTED, STALE_REFERENCE_ERROR);
    }
    Instant now = clock.instant();
    requireNotRefreshing(credential, now);
    requireReadableStatus(credential);
    if (credential.expiresAt() != null && !credential.expiresAt().isAfter(now)) {
      throw unavailable(
          PluginCredentialUnavailableReason.EXPIRED,
          "plugin credential is expired; re-authenticate the plugin");
    }
    return snapshotOf(credential, decrypt(key, credential));
  }

  @Override
  public boolean rejectUsed(PluginCredentialSnapshot snapshot, String error) {
    Objects.requireNonNull(snapshot, "snapshot");
    String bounded = PluginCredentialRefreshService.boundedError(error, AUTH_REJECTED_ERROR);
    return repository.rejectUsedCredential(
        snapshot.pluginId(),
        snapshot.authGeneration(),
        snapshot.contextFingerprint(),
        snapshot.version(),
        snapshot.encryptedPayload(),
        bounded,
        clock.instant());
  }

  private SecretKey requireKey() {
    SecretKey key = keyLoader.load().orElse(null);
    if (key == null) {
      throw unavailable(
          PluginCredentialUnavailableReason.KEY_UNAVAILABLE,
          "plugin credential key is unavailable");
    }
    return key;
  }

  /** 引用必须仍是当前权威 manifest 的该 Plugin 坐标；catalog 不可用或坐标已变化都 fail closed。 */
  private void requireCurrentReference(PluginCredentialReference reference) {
    PluginCatalogRecord catalog;
    try {
      catalog = configurationRepository.readCatalog();
    } catch (RuntimeException error) {
      throw unavailable(
          PluginCredentialUnavailableReason.KEY_UNAVAILABLE,
          "plugin catalog is unavailable; plugin credential access fails closed");
    }
    if (catalog == null) {
      throw unavailable(
          PluginCredentialUnavailableReason.KEY_UNAVAILABLE,
          "plugin catalog is unavailable; plugin credential access fails closed");
    }
    PluginCatalogManifest.Entry entry = catalog.manifest().plugins().get(reference.pluginId());
    if (entry == null
        || !entry.authGeneration().equals(reference.authGeneration())
        || !entry.contextFingerprint().equals(reference.contextFingerprint())) {
      throw unavailable(PluginCredentialUnavailableReason.NOT_CONNECTED, STALE_REFERENCE_ERROR);
    }
  }

  private static void requireNotRefreshing(PluginCredentialRow credential, Instant now) {
    if (credential.leased() && credential.refreshLeaseUntil().isAfter(now)) {
      throw unavailable(
          PluginCredentialUnavailableReason.AUTH_REFRESHING,
          "plugin credential refresh is in flight");
    }
  }

  private static void requireReadableStatus(PluginCredentialRow credential) {
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
  }

  private static PluginCredentialSnapshot snapshotOf(
      PluginCredentialRow credential, String payload) {
    return new PluginCredentialSnapshot(
        credential.pluginId(),
        credential.authGeneration(),
        credential.contextFingerprint(),
        credential.region(),
        credential.expiresAt(),
        credential.version(),
        credential.encryptedPayload(),
        payload);
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
          key,
          credential.pluginId(),
          credential.authGeneration(),
          credential.contextFingerprint(),
          credential.region(),
          credential.encryptedPayload());
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
