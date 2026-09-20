package fun.fengwk.kkstudio.platform.plugin.credential;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.platform.plugin.PluginAuthRejectedException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialRefresher;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginKeyUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginProperties;
import fun.fengwk.kkstudio.platform.plugin.PluginRenewalNotSentException;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;

import javax.crypto.SecretKey;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Plugin 凭据刷新编排：一次扫描把到期行 claim 出来、在事务外只刷新一次、再以 lease token 与 version 围栏终结。
 *
 * <p>状态机与文档一致：
 *
 * <pre>
 * due row
 *   -> 单语句短事务：claim refresh lease（version + 1）
 *   -> 事务外 external refresh exactly once
 *   -> 单语句短事务：
 *        success -> replace encrypted payload + expiry + nextRefreshAt
 *        auth rejection -> REAUTH_REQUIRED
 *        not sent, or a definitive but unusable renewal result -> REFRESH_FAILED + delayed retry
 *        sent but no definitive response -> REFRESH_UNCERTAIN
 * expired in-flight lease -> REFRESH_UNCERTAIN, never reclaim-and-send
 * </pre>
 *
 * <p>只领取当前 JVM 已安装 Plugin 的行，未安装 Plugin 的密文保持 dormant。lease 只解决多节点互斥，不承诺外部 exactly-once：节点在 HTTP
 * 前后崩溃 时没有节点能证明请求是否发出，因此过期 lease 一律收敛为 {@code REFRESH_UNCERTAIN}，绝不重新 claim。
 */
@Slf4j
public final class PluginCredentialRefreshService {

  /** 一次扫描最多 claim 的行数；其余到期行由下一次扫描继续。 */
  public static final int MAX_CLAIM_BATCH = 16;

  private static final String LEASE_EXPIRED_ERROR =
      "plugin credential refresh lease expired before a result was observed";
  private static final String UNREADABLE_ERROR =
      "plugin credential payload cannot be authenticated; re-authenticate the plugin";
  private static final String INVALID_RENEWAL_ERROR =
      "plugin credential renewal returned an unusable credential";
  private static final String UNEXPECTED_ERROR =
      "plugin credential renewal failed with an unknown outcome";
  private static final int MAX_ERROR_BYTES = 4096;

  private final StudioPluginRegistry registry;
  private final PluginCredentialRepository repository;
  private final PluginCredentialCodec codec;
  private final PluginCredentialKeyLoader keyLoader;
  private final PluginProperties properties;
  private final Clock clock;

  public PluginCredentialRefreshService(
      StudioPluginRegistry registry,
      PluginCredentialRepository repository,
      PluginCredentialCodec codec,
      PluginCredentialKeyLoader keyLoader,
      PluginProperties properties,
      Clock clock) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.keyLoader = Objects.requireNonNull(keyLoader, "keyLoader");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * 执行一次扫描并返回本次 claim 的行数。
   *
   * <p>先原子收敛过期 in-flight lease（这些行永不重放），再 claim 到期行并逐行刷新；单行失败不影响其它行。
   */
  public int refreshOnce() {
    List<String> installed = registry.pluginIds();
    if (installed.isEmpty()) {
      return 0;
    }
    Instant now = clock.instant();
    try {
      int recovered = repository.markExpiredLeasesUncertain(installed, now, LEASE_EXPIRED_ERROR);
      if (recovered > 0) {
        log.warn(
            "Converged {} expired plugin credential refresh lease(s) to REFRESH_UNCERTAIN",
            recovered);
      }
    } catch (RuntimeException error) {
      log.warn("Cannot converge expired plugin credential refresh leases", error);
      return 0;
    }
    List<PluginCredentialRow> claimed;
    try {
      claimed =
          repository.claimDue(
              installed,
              now,
              now.plus(properties.getRefresh().getLeaseDuration()),
              UUID.randomUUID().toString(),
              MAX_CLAIM_BATCH);
    } catch (RuntimeException error) {
      log.warn("Cannot claim due plugin credentials for refresh", error);
      return 0;
    }
    for (PluginCredentialRow row : claimed) {
      try {
        refreshClaimed(row);
      } catch (RuntimeException error) {
        log.warn("Unexpected failure while refreshing plugin credential {}", row.pluginId(), error);
      }
    }
    return claimed.size();
  }

  private void refreshClaimed(PluginCredentialRow row) {
    SecretKey key = keyLoader.load().orElse(null);
    if (key == null) {
      // 主密钥不可用：本轮不写任何终态，释放 lease 让后续扫描重试。
      releaseLease(row);
      return;
    }
    String payload;
    try {
      payload = codec.decrypt(key, row.pluginId(), row.region(), row.encryptedPayload());
    } catch (RuntimeException error) {
      finalizeFailure(
          row, PluginCredentialStatus.REAUTH_REQUIRED, row.nextRefreshAt(), UNREADABLE_ERROR);
      return;
    }
    StudioPlugin plugin = registry.find(row.pluginId()).orElse(null);
    PluginCredentialRefresher refresher = plugin == null ? null : plugin.refresher().orElse(null);
    if (refresher == null) {
      releaseLease(row);
      return;
    }
    PluginCredentialSnapshot snapshot =
        new PluginCredentialSnapshot(row.pluginId(), row.region(), row.expiresAt(), payload);
    Instant now = clock.instant();
    PluginCredentialMaterial material;
    try {
      material = refresher.refresh(snapshot);
    } catch (PluginAuthRejectedException rejection) {
      finalizeFailure(
          row,
          PluginCredentialStatus.REAUTH_REQUIRED,
          row.nextRefreshAt(),
          boundedError(rejection.getMessage(), "plugin credential was rejected"));
      return;
    } catch (PluginRenewalNotSentException notSent) {
      finalizeFailure(
          row,
          PluginCredentialStatus.REFRESH_FAILED,
          now.plus(properties.getRefresh().getPollDelay()),
          boundedError(notSent.getMessage(), "plugin credential renewal was not sent"));
      return;
    } catch (PluginKeyUnavailableException keyError) {
      releaseLease(row);
      return;
    } catch (RuntimeException error) {
      finalizeFailure(
          row,
          PluginCredentialStatus.REFRESH_UNCERTAIN,
          row.nextRefreshAt(),
          boundedError(error.getMessage(), UNEXPECTED_ERROR));
      return;
    }
    byte[] envelope;
    try {
      requireUsable(plugin.descriptor(), row, material, now);
      envelope = codec.encrypt(key, row.pluginId(), material.region(), material.payloadJson());
    } catch (IllegalArgumentException error) {
      finalizeFailure(
          row,
          PluginCredentialStatus.REFRESH_FAILED,
          now.plus(properties.getRefresh().getPollDelay()),
          INVALID_RENEWAL_ERROR);
      return;
    }
    boolean finalized =
        repository.finalizeSuccess(
            row.pluginId(),
            row.refreshLeaseToken(),
            row.version(),
            envelope,
            material.region(),
            material.expiresAt(),
            material.nextRefreshAt(),
            now,
            now);
    if (!finalized) {
      log.debug("Lost plugin credential refresh race for {}", row.pluginId());
    }
  }

  /**
   * 校验 Plugin 交还的材料仍属于同一条凭据：region 必须与行一致，时间必须自洽。
   *
   * <p>不满足时**不替换**任何旧凭据，并按「已收到但不可用的刷新结果」收敛为有界延迟重试：它既不是结果未知（响应是确定的），也不应该永久阻断一个 可能只是服务端临时异常的 Plugin。
   */
  private static void requireUsable(
      PluginDescriptor descriptor,
      PluginCredentialRow row,
      PluginCredentialMaterial material,
      Instant now) {
    if (material == null) {
      throw new IllegalArgumentException(INVALID_RENEWAL_ERROR);
    }
    if (!material.region().equals(row.region()) || !descriptor.acceptsRegion(material.region())) {
      throw new IllegalArgumentException(INVALID_RENEWAL_ERROR);
    }
    if (!material.expiresAt().isAfter(now)) {
      throw new IllegalArgumentException(INVALID_RENEWAL_ERROR);
    }
  }

  private void finalizeFailure(
      PluginCredentialRow row, PluginCredentialStatus status, Instant nextRefreshAt, String error) {
    Instant now = clock.instant();
    boolean finalized =
        repository.finalizeFailure(
            row.pluginId(),
            row.refreshLeaseToken(),
            row.version(),
            status,
            nextRefreshAt == null ? row.nextRefreshAt() : nextRefreshAt,
            error,
            now);
    if (!finalized) {
      log.debug("Lost plugin credential refresh race for {}", row.pluginId());
    }
  }

  private void releaseLease(PluginCredentialRow row) {
    boolean released =
        repository.releaseLease(
            row.pluginId(), row.refreshLeaseToken(), row.version(), clock.instant());
    if (!released) {
      log.debug("Lost plugin credential refresh race for {}", row.pluginId());
    }
  }

  /** 有界去敏错误摘要：去空白、限制 4096 UTF-8 字节，空值退化为固定文本。 */
  static String boundedError(String message, String fallback) {
    String normalized =
        message == null ? "" : message.strip().replace('\n', ' ').replace('\r', ' ');
    if (normalized.isEmpty()) {
      return fallback;
    }
    byte[] encoded = normalized.getBytes(StandardCharsets.UTF_8);
    if (encoded.length <= MAX_ERROR_BYTES) {
      return normalized;
    }
    int length = normalized.length();
    while (length > 0
        && normalized.substring(0, length).getBytes(StandardCharsets.UTF_8).length
            > MAX_ERROR_BYTES) {
      length--;
    }
    return normalized.substring(0, length);
  }
}
