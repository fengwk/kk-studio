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
import java.util.Optional;
import java.util.UUID;

/**
 * Plugin 凭据刷新编排：到期行一次只 claim 一行，发请求前再核验 lease 仍属于自己，然后在事务外只刷新一次并以围栏终结。
 *
 * <p>状态机与文档一致：
 *
 * <pre>
 * due row
 *   -> 单语句短事务：claim 恰好一行 refresh lease（version + 1）
 *   -> 发请求前核验当前 lease token / version 仍属于自己且截止有效
 *   -> 事务外 external refresh exactly once
 *   -> 单语句短事务：
 *        success -> replace encrypted payload + expiry + nextRefreshAt
 *        auth rejection -> REAUTH_REQUIRED
 *        not sent, or a definitive but unusable renewal result -> REFRESH_FAILED + delayed retry
 *        sent but no definitive response -> REFRESH_UNCERTAIN
 * expired in-flight lease -> REFRESH_UNCERTAIN, never reclaim-and-send
 * </pre>
 *
 * <p>只领取当前 JVM 已安装 Plugin 的行，未安装 Plugin 的密文保持 dormant。一次扫描最多循环 {@link #MAX_REFRESH_PASSES}
 * 次，避免单次调度被无限到期行占住。lease 只解决多节点互斥，不承诺外部 exactly-once：节点在 HTTP 前后崩溃时没有节点能证明请求是否发出，因此过期 lease 一律收敛为
 * {@code REFRESH_UNCERTAIN}，绝不重新 claim。核验失败时不得再调用 refresher。
 */
@Slf4j
public final class PluginCredentialRefreshService {

  /** 一次扫描最多 just-in-time claim 并刷新的行数；其余到期行由下一次扫描继续。 */
  public static final int MAX_REFRESH_PASSES = 16;

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
   * 执行一次扫描并返回本次真正终结的行数（{@code finalizeSuccess} / {@code finalizeFailure} 成功）。
   *
   * <p>先原子收敛过期 in-flight lease（这些行永不重放），再循环最多 {@link #MAX_REFRESH_PASSES} 次：每次只 claim 一行、立即刷新并
   * finalize，然后才领取下一行。
   *
   * <p>一旦某一行没有产生终态写入（释放 lease、丢失所有权或 CAS 竞争失败），本轮立即结束：该行仍然到期，继续循环只会重复领取同一行。
   * 返回值因此也是调度器判断「本轮是否有进展」的依据。
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
    int finalized = 0;
    for (int pass = 0; pass < MAX_REFRESH_PASSES; pass++) {
      PluginCredentialRow claimed = claimOne(installed);
      if (claimed == null) {
        return finalized;
      }
      boolean progressed;
      try {
        progressed = refreshClaimed(claimed);
      } catch (RuntimeException error) {
        log.warn(
            "Unexpected failure while refreshing plugin credential {}", claimed.pluginId(), error);
        progressed = false;
      }
      if (!progressed) {
        return finalized;
      }
      finalized++;
    }
    return finalized;
  }

  /** 已安装 Plugin 中下一次可调度刷新时刻；没有可调度行时为空。 */
  public Optional<Instant> earliestRefreshAt() {
    List<String> installed = registry.pluginIds();
    if (installed.isEmpty()) {
      return Optional.empty();
    }
    return repository.earliestRefreshAt(installed, clock.instant());
  }

  private PluginCredentialRow claimOne(List<String> installed) {
    Instant now = clock.instant();
    try {
      List<PluginCredentialRow> claimed =
          repository.claimDue(
              installed,
              now,
              now.plus(properties.getRefresh().getLeaseDuration()),
              UUID.randomUUID().toString(),
              1);
      return claimed.isEmpty() ? null : claimed.get(0);
    } catch (RuntimeException error) {
      log.warn("Cannot claim due plugin credentials for refresh", error);
      return null;
    }
  }

  /**
   * 刷新已 claim 的单行，返回本次是否产生了成功的终态写入。
   *
   * <p>{@code false} 表示该行仍然到期（释放 lease、丢失所有权或 CAS 竞争失败），调用方必须结束本轮扫描而不是立刻重新领取同一行。
   */
  private boolean refreshClaimed(PluginCredentialRow row) {
    SecretKey key = keyLoader.load().orElse(null);
    if (key == null) {
      // 主密钥不可用：本轮不写任何终态，释放 lease 让后续扫描重试。
      releaseLease(row);
      return false;
    }
    String payload;
    try {
      payload = codec.decrypt(key, row.pluginId(), row.region(), row.encryptedPayload());
    } catch (RuntimeException error) {
      return finalizeFailure(
          row, PluginCredentialStatus.REAUTH_REQUIRED, row.nextRefreshAt(), UNREADABLE_ERROR);
    }
    StudioPlugin plugin = registry.find(row.pluginId()).orElse(null);
    PluginCredentialRefresher refresher = plugin == null ? null : plugin.refresher().orElse(null);
    if (refresher == null) {
      releaseLease(row);
      return false;
    }
    PluginCredentialSnapshot snapshot =
        new PluginCredentialSnapshot(
            row.pluginId(),
            row.region(),
            row.expiresAt(),
            row.version(),
            row.encryptedPayload(),
            payload);
    if (!stillOwnsLease(row)) {
      log.debug(
          "Lost plugin credential refresh lease before calling refresher for {}", row.pluginId());
      return false;
    }
    Instant sentAt = clock.instant();
    PluginCredentialMaterial material;
    try {
      material = refresher.refresh(snapshot);
    } catch (PluginAuthRejectedException rejection) {
      return finalizeFailure(
          row,
          PluginCredentialStatus.REAUTH_REQUIRED,
          row.nextRefreshAt(),
          boundedError(rejection.getMessage(), "plugin credential was rejected"));
    } catch (PluginRenewalNotSentException notSent) {
      return finalizeFailure(
          row,
          PluginCredentialStatus.REFRESH_FAILED,
          sentAt.plus(properties.getRefresh().getPollDelay()),
          boundedError(notSent.getMessage(), "plugin credential renewal was not sent"));
    } catch (PluginKeyUnavailableException keyError) {
      releaseLease(row);
      return false;
    } catch (RuntimeException error) {
      return finalizeFailure(
          row,
          PluginCredentialStatus.REFRESH_UNCERTAIN,
          row.nextRefreshAt(),
          boundedError(error.getMessage(), UNEXPECTED_ERROR));
    }
    Instant completedAt = clock.instant();
    byte[] envelope;
    try {
      requireUsable(plugin.descriptor(), row, material, completedAt);
      envelope = codec.encrypt(key, row.pluginId(), material.region(), material.payloadJson());
    } catch (IllegalArgumentException error) {
      return finalizeFailure(
          row,
          PluginCredentialStatus.REFRESH_FAILED,
          completedAt.plus(properties.getRefresh().getPollDelay()),
          INVALID_RENEWAL_ERROR);
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
            completedAt,
            completedAt);
    if (!finalized) {
      log.debug("Lost plugin credential refresh race for {}", row.pluginId());
    }
    return finalized;
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

  /** 终结为失败状态，返回是否真的写入了终态（CAS 竞争失败时为 {@code false}）。 */
  private boolean finalizeFailure(
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
    return finalized;
  }

  /** 外部调用前的最后一道围栏：token、version 与截止时刻任一失效都不得外呼。 */
  private boolean stillOwnsLease(PluginCredentialRow row) {
    return repository.ownsUnexpiredLease(
        row.pluginId(), row.refreshLeaseToken(), row.version(), clock.instant());
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
