package fun.fengwk.kkstudio.platform.plugin.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.plugin.PluginAuthHandler;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthRejectedException;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialRefresher;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.PluginProperties;
import fun.fengwk.kkstudio.platform.plugin.PluginRenewalNotSentException;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.plugin.testing.TestPluginFixtures;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** {@link PluginCredentialRefreshService} 按类型化结果终结刷新，并以租约与版本防止迟到覆盖。 */
class PluginCredentialRefreshServiceTest {

  private static final String PLUGIN_ID = "deeplink.plugin";
  private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
  private static final String OLD_PAYLOAD = "{\"token\":\"old\"}";
  private static final String NEW_PAYLOAD = "{\"token\":\"new\"}";

  private final PluginCredentialRepository repository = mock(PluginCredentialRepository.class);
  private final PluginProperties properties = new PluginProperties();
  private PluginCredentialRefresher refresher;

  /** 没有可刷新 Plugin 时不访问数据库。 */
  @Test
  void skipsTheDatabaseWhenNoPluginRefreshes() {
    PluginCredentialRefreshService service =
        new PluginCredentialRefreshService(
            new StudioPluginRegistry(List.of()), repository, properties, clock());

    assertEquals(0, service.refreshOnce());
    assertEquals(Optional.empty(), service.earliestRefreshAt());
    verifyNoInteractions(repository);
  }

  /** 成功刷新以租约和版本 CAS 替换凭据；没有到期行时结束扫描。 */
  @Test
  void successReplacesTheCredential() {
    PluginCredentialMaterial material =
        new PluginCredentialMaterial(
            "CN", NOW.plusSeconds(3600), NOW.plusSeconds(1800), NEW_PAYLOAD);
    refresher = snapshot -> material;
    PluginCredentialRow claimed = claimed();
    when(repository.claimDue(eq(List.of(PLUGIN_ID)), any(), anyString()))
        .thenReturn(Optional.of(claimed), Optional.empty());
    when(repository.ownsUnexpiredLease(claimed)).thenReturn(true);
    when(repository.finalizeSuccess(
            claimed, NEW_PAYLOAD, "CN", NOW.plusSeconds(3600), NOW.plusSeconds(1800), NOW))
        .thenReturn(true);

    assertEquals(1, service().refreshOnce());
    verify(repository)
        .markExpiredLeasesUncertain(
            List.of(PLUGIN_ID),
            "plugin credential refresh lease expired before a result was observed");
    verify(repository, times(2))
        .claimDue(
            eq(List.of(PLUGIN_ID)), eq(properties.getRefresh().getLeaseDuration()), anyString());
  }

  /** 认证拒绝转为需重新登录，未发出延迟重试，其余异常收敛为结果未知。 */
  @Test
  void typedFailuresMapToTheirStatus() {
    assertFailure(
        snapshot -> {
          throw new PluginAuthRejectedException("revoked");
        },
        PluginCredentialStatus.REAUTH_REQUIRED,
        NOW.plusSeconds(60),
        "revoked");
    assertFailure(
        snapshot -> {
          throw new PluginRenewalNotSentException("offline");
        },
        PluginCredentialStatus.REFRESH_FAILED,
        NOW.plus(properties.getRefresh().getPollDelay()),
        "offline");
    assertFailure(
        snapshot -> {
          throw new IllegalStateException();
        },
        PluginCredentialStatus.REFRESH_UNCERTAIN,
        NOW.plusSeconds(60),
        "plugin credential renewal failed with an unknown outcome");
  }

  /** 已收到但不可用的结果（换了 region、已过期或非交互）不替换旧凭据。 */
  @Test
  void unusableRenewalsAreRetriedWithoutReplacingTheCredential() {
    for (PluginCredentialMaterial material :
        List.of(
            new PluginCredentialMaterial(
                "US", NOW.plusSeconds(3600), NOW.plusSeconds(1800), NEW_PAYLOAD),
            new PluginCredentialMaterial("CN", NOW, NOW, NEW_PAYLOAD),
            PluginCredentialMaterial.staticKey(NEW_PAYLOAD))) {
      assertFailure(
          snapshot -> material,
          PluginCredentialStatus.REFRESH_FAILED,
          NOW.plus(properties.getRefresh().getPollDelay()),
          "plugin credential renewal returned an unusable credential");
    }
  }

  /** 发出请求前租约已不属于自己时不调用刷新器。 */
  @Test
  void lostLeaseStopsBeforeCallingTheRefresher() {
    refresher = mock(PluginCredentialRefresher.class);
    PluginCredentialRow claimed = claimed();
    when(repository.claimDue(any(), any(), anyString())).thenReturn(Optional.of(claimed));

    assertEquals(0, service().refreshOnce());
    verifyNoInteractions(refresher);
  }

  /** 单次扫描最多终结固定数量的行；最早刷新时刻来自仓储。 */
  @Test
  void boundsPassesAndReportsTheEarliestRefresh() {
    refresher =
        snapshot -> {
          throw new PluginRenewalNotSentException("offline");
        };
    PluginCredentialRow claimed = claimed();
    when(repository.claimDue(any(), any(), anyString())).thenReturn(Optional.of(claimed));
    when(repository.ownsUnexpiredLease(claimed)).thenReturn(true);
    when(repository.finalizeFailure(eq(claimed), any(), any(), anyString())).thenReturn(true);
    when(repository.earliestRefreshAt(List.of(PLUGIN_ID)))
        .thenReturn(Optional.of(NOW.plusSeconds(5)));

    PluginCredentialRefreshService service = service();
    assertEquals(PluginCredentialRefreshService.MAX_REFRESH_PASSES, service.refreshOnce());
    assertEquals(Optional.of(NOW.plusSeconds(5)), service.earliestRefreshAt());
  }

  /** 错误摘要去除首尾空白与换行，并按 UTF-8 字节截断。 */
  @Test
  void boundedErrorNormalizesAndTruncates() {
    assertEquals("a b", PluginCredentialRefreshService.boundedError(" a\nb ", "fallback"));
    assertEquals("fallback", PluginCredentialRefreshService.boundedError(null, "fallback"));
    String bounded = PluginCredentialRefreshService.boundedError("界".repeat(2000), "fallback");
    assertTrue(bounded.getBytes(StandardCharsets.UTF_8).length <= 4096);
  }

  private void assertFailure(
      PluginCredentialRefresher failing,
      PluginCredentialStatus status,
      Instant nextRefreshAt,
      String error) {
    refresher = failing;
    PluginCredentialRow claimed = claimed();
    when(repository.claimDue(any(), any(), anyString()))
        .thenReturn(Optional.of(claimed), Optional.empty());
    when(repository.ownsUnexpiredLease(claimed)).thenReturn(true);
    when(repository.finalizeFailure(claimed, status, nextRefreshAt, error)).thenReturn(true);

    assertEquals(1, service().refreshOnce());
    verify(repository, never()).finalizeSuccess(any(), any(), any(), any(), any(), any());
  }

  private PluginCredentialRefreshService service() {
    return new PluginCredentialRefreshService(
        new StudioPluginRegistry(List.of(new DeepLinkPlugin())), repository, properties, clock());
  }

  private static Clock clock() {
    return Clock.fixed(NOW, ZoneOffset.UTC);
  }

  private static PluginCredentialRow claimed() {
    return new PluginCredentialRow(
        PLUGIN_ID,
        OLD_PAYLOAD,
        "CN",
        NOW.plusSeconds(120),
        NOW.plusSeconds(60),
        PluginCredentialStatus.CONNECTED,
        null,
        null,
        "lease",
        NOW.plus(Duration.ofMinutes(2)),
        4L);
  }

  /** 声明 DeepLink 认证与刷新器的最小 Plugin。 */
  private final class DeepLinkPlugin implements StudioPlugin {

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor(PLUGIN_ID, "DeepLink", "1.0");
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return TestPluginFixtures.EMPTY_SCHEMA;
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.DeepLink(List.of("CN", "US"));
    }

    @Override
    public Optional<PluginAuthHandler> authHandler() {
      return Optional.of(mock(PluginAuthHandler.class));
    }

    @Override
    public Optional<PluginCredentialRefresher> refresher() {
      return Optional.of(snapshot -> refresher.refresh(snapshot));
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      return TestPluginFixtures.emptyInstance();
    }
  }
}
