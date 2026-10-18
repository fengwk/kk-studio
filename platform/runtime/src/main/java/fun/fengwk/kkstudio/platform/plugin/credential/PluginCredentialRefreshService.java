package fun.fengwk.kkstudio.platform.plugin.credential;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.platform.plugin.PluginAuthRejectedException;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialRefresher;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginProperties;
import fun.fengwk.kkstudio.platform.plugin.PluginRenewalNotSentException;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 交互凭据的刷新：领取到期行、调用 Plugin 刷新器、按确定的结果终结。
 *
 * <p>结果分类只依据类型化异常：{@link PluginAuthRejectedException} 是确定性拒绝（需重新登录），{@link
 * PluginRenewalNotSentException} 证明请求未发出（有界延迟后重试），其余异常与租约过期都是结果未知，不重放也不再使用旧凭据。 终结以租约 token 与行版本做
 * CAS，因此迟到的刷新结果不会覆盖人工替换后的新凭据。
 */
@Slf4j
public final class PluginCredentialRefreshService {

  /** 单次扫描最多终结的行数，避免一次扫描长期占用调度线程。 */
  public static final int MAX_REFRESH_PASSES = 16;

  private static final String LEASE_EXPIRED_ERROR =
      "plugin credential refresh lease expired before a result was observed";
  private static final String INVALID_RENEWAL_ERROR =
      "plugin credential renewal returned an unusable credential";
  private static final String UNEXPECTED_ERROR =
      "plugin credential renewal failed with an unknown outcome";
  private static final int MAX_ERROR_BYTES = 4096;

  private final StudioPluginRegistry registry;
  private final PluginCredentialRepository repository;
  private final PluginProperties properties;
  private final Clock clock;

  public PluginCredentialRefreshService(
      StudioPluginRegistry registry,
      PluginCredentialRepository repository,
      PluginProperties properties,
      Clock clock) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** 收敛过期租约并刷新到期行，返回本次终结的行数。 */
  public int refreshOnce() {
    List<String> refreshable = refreshablePluginIds();
    if (refreshable.isEmpty()) {
      return 0;
    }
    int recovered =
        repository.markExpiredLeasesUncertain(refreshable, clock.instant(), LEASE_EXPIRED_ERROR);
    if (recovered > 0) {
      log.warn(
          "Converged {} expired plugin credential refresh lease(s) to REFRESH_UNCERTAIN",
          recovered);
    }
    int finalized = 0;
    for (int pass = 0; pass < MAX_REFRESH_PASSES; pass++) {
      Instant now = clock.instant();
      Optional<PluginCredentialRow> claimed =
          repository.claimDue(
              refreshable,
              now,
              now.plus(properties.getRefresh().getLeaseDuration()),
              UUID.randomUUID().toString());
      if (claimed.isEmpty() || !refreshClaimed(claimed.get())) {
        return finalized;
      }
      finalized++;
    }
    return finalized;
  }

  /** 未被租约持有的最早下一次刷新时刻。 */
  public Optional<Instant> earliestRefreshAt() {
    List<String> refreshable = refreshablePluginIds();
    return refreshable.isEmpty()
        ? Optional.empty()
        : repository.earliestRefreshAt(refreshable, clock.instant());
  }

  private List<String> refreshablePluginIds() {
    return registry.plugins().stream()
        .filter(plugin -> plugin.refresher().isPresent())
        .map(plugin -> plugin.descriptor().pluginId())
        .toList();
  }

  private boolean refreshClaimed(PluginCredentialRow row) {
    StudioPlugin plugin = registry.find(row.pluginId()).orElseThrow();
    PluginCredentialRefresher refresher = plugin.refresher().orElseThrow();
    if (!repository.ownsUnexpiredLease(row, clock.instant())) {
      return false;
    }
    PluginCredentialSnapshot snapshot =
        new PluginCredentialSnapshot(
            row.pluginId(), row.region(), row.expiresAt(), row.version(), row.payloadJson());
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
    } catch (RuntimeException unknown) {
      return finalizeFailure(
          row,
          PluginCredentialStatus.REFRESH_UNCERTAIN,
          row.nextRefreshAt(),
          boundedError(unknown.getMessage(), UNEXPECTED_ERROR));
    }
    Instant completedAt = clock.instant();
    if (!usable(plugin, row, material, completedAt)) {
      // 已收到但不可用的结果：不替换旧凭据，有界延迟后重试。
      return finalizeFailure(
          row,
          PluginCredentialStatus.REFRESH_FAILED,
          completedAt.plus(properties.getRefresh().getPollDelay()),
          INVALID_RENEWAL_ERROR);
    }
    boolean finalized =
        repository.finalizeSuccess(
            row,
            material.payloadJson(),
            material.region(),
            material.expiresAt(),
            material.nextRefreshAt(),
            completedAt);
    if (!finalized) {
      log.debug("Lost plugin credential refresh race for {}", row.pluginId());
    }
    return finalized;
  }

  /** 刷新结果必须仍是同一 region 的交互凭据，且尚未过期。 */
  private static boolean usable(
      StudioPlugin plugin,
      PluginCredentialRow row,
      PluginCredentialMaterial material,
      Instant now) {
    return material != null
        && material.interactive()
        && material.region().equals(row.region())
        && (!(plugin.authenticationSpec() instanceof PluginAuthSpec.DeepLink deepLink)
            || deepLink.acceptsRegion(material.region()))
        && material.expiresAt().isAfter(now);
  }

  private boolean finalizeFailure(
      PluginCredentialRow row, PluginCredentialStatus status, Instant nextRefreshAt, String error) {
    boolean finalized = repository.finalizeFailure(row, status, nextRefreshAt, error);
    if (!finalized) {
      log.debug("Lost plugin credential refresh race for {}", row.pluginId());
    }
    return finalized;
  }

  /** 有界错误摘要：去空白与换行、限制 4096 UTF-8 字节，空值退化为固定文本。 */
  static String boundedError(String message, String fallback) {
    String normalized =
        message == null ? "" : message.strip().replace('\n', ' ').replace('\r', ' ');
    if (normalized.isEmpty()) {
      return fallback;
    }
    int length = normalized.length();
    while (normalized.substring(0, length).getBytes(StandardCharsets.UTF_8).length
        > MAX_ERROR_BYTES) {
      length--;
    }
    return normalized.substring(0, length);
  }
}
