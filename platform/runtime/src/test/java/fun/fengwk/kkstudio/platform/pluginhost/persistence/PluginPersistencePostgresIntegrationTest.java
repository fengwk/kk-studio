package fun.fengwk.kkstudio.platform.pluginhost.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.common.error.DomainErrorCode;
import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.plugin.Plugin;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthHandler;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec.DeepLink;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec.StaticKey;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginRegistry;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.pluginhost.catalog.PluginFunctionNamesProjection;
import fun.fengwk.kkstudio.platform.pluginhost.credential.DatabasePluginCredentialStore;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginAgentDependencies;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginConfigurationService;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginContextFactory;
import fun.fengwk.kkstudio.platform.pluginhost.service.PluginManagementService;
import fun.fengwk.kkstudio.platform.pluginhost.testing.TestPluginFixtures;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 插件配置、凭据与认证 attempt 三个仓储在真实 PostgreSQL 上的 SQL 契约。 */
class PluginPersistencePostgresIntegrationTest {

  private static final String PLUGIN = "alpha.plugin";
  private static final String OTHER = "beta.plugin";
  private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
  private static final Duration LEASE = Duration.ofMinutes(2);
  private static final String HASH_1 = "1".repeat(64);
  private static final String HASH_2 = "2".repeat(64);
  private static final String HASH_3 = "3".repeat(64);

  private static SimpleDriverDataSource dataSource;
  private static JdbcClient jdbc;

  private PluginConfigurationRepository configurations;
  private PluginCredentialRepository credentials;
  private PluginAuthAttemptRepository attempts;

  @BeforeAll
  static void connect() {
    dataSource =
        new SimpleDriverDataSource(
            new Driver(),
            PostgresSchemaSupport.POSTGRES.getJdbcUrl(),
            PostgresSchemaSupport.POSTGRES.getUsername(),
            PostgresSchemaSupport.POSTGRES.getPassword());
    jdbc = JdbcClient.create(dataSource);
  }

  @BeforeEach
  void resetSchema() throws SQLException {
    try (Connection conn = PostgresSchemaSupport.newConnection()) {
      PostgresSchemaSupport.resetDatabase(conn);
      PostgresSchemaSupport.applyBaseline(conn);
    }
    configurations = new PluginConfigurationRepository(jdbc);
    credentials = new PluginCredentialRepository(jdbc);
    attempts = new PluginAuthAttemptRepository(jdbc);
    configurations.insertIfMissing(PLUGIN, "{\"enabled\":true}");
    configurations.insertIfMissing(OTHER, "{}");
  }

  /** 默认值只在首次写入；保存以修订 CAS 推进。 */
  @Test
  void configurationSeedsOnceAndSavesWithRevisionCas() {
    configurations.insertIfMissing(PLUGIN, "{\"enabled\":false}");
    assertEquals(
        new PluginConfigurationRow(PLUGIN, 1L, "{\"enabled\":true}"),
        configurations.find(PLUGIN).orElseThrow());

    assertTrue(configurations.update(PLUGIN, 1L, "{\"enabled\":false}"));
    assertFalse(configurations.update(PLUGIN, 1L, "{\"enabled\":true}"));
    assertEquals(
        new PluginConfigurationRow(PLUGIN, 2L, "{\"enabled\":false}"),
        configurations.find(PLUGIN).orElseThrow());
    List<PluginConfigurationRow> allConfigurations = configurations.findAll();
    assertEquals(2, allConfigurations.size());
    assertEquals(
        Set.of(
            new PluginConfigurationRow(PLUGIN, 2L, "{\"enabled\":false}"),
            new PluginConfigurationRow(OTHER, 1L, "{}")),
        Set.copyOf(allConfigurations));
    assertEquals(Optional.empty(), configurations.find("ghost"));
    configurations.notifyChanged(PLUGIN);
  }

  /** 写入新凭据清空刷新状态并推进版本；调用期拒绝只作废读取时的版本。 */
  @Test
  void credentialUpsertAndRejectUseVersionCas() {
    credentials.upsertConnected(PLUGIN, "{\"token\":\"a\"}", "CN", at(60), at(30));
    PluginCredentialRow first = credentials.find(PLUGIN).orElseThrow();
    assertEquals(PluginCredentialStatus.CONNECTED, first.status());
    assertEquals(at(60), first.expiresAt());

    assertTrue(credentials.rejectUsed(PLUGIN, first.generation(), first.version(), "revoked"));
    assertFalse(credentials.rejectUsed(PLUGIN, first.generation(), first.version(), "revoked"));
    PluginCredentialRow rejected = credentials.find(PLUGIN).orElseThrow();
    assertEquals(PluginCredentialStatus.REAUTH_REQUIRED, rejected.status());
    assertEquals("revoked", rejected.lastRefreshError());

    credentials.upsertConnected(PLUGIN, "{\"key\":\"k\"}", null, null, null);
    PluginCredentialRow replaced = credentials.find(PLUGIN).orElseThrow();
    assertEquals(PluginCredentialStatus.CONNECTED, replaced.status());
    assertNull(replaced.lastRefreshError());
    assertNull(replaced.expiresAt());
    assertEquals(rejected.version() + 1, replaced.version());
    assertEquals(first.generation(), replaced.generation());

    credentials.delete(PLUGIN);
    assertTrue(credentials.find(PLUGIN).isEmpty());
  }

  /** 只比较认证字段，且缺失与显式 null 不等价。 */
  @Test
  void scopedLookupIgnoresUnrelatedChangesButDistinguishesMissingAndNull() {
    var plugin = mock(Plugin.class);
    when(plugin.descriptor()).thenReturn(new PluginDescriptor(PLUGIN, "Test", "Test"));
    when(plugin.authenticationSpec()).thenReturn(new StaticKey("key", Set.of("endpoint")));
    var store = new DatabasePluginCredentialStore(credentials, Clock.systemUTC());
    var factory = new PluginContextFactory(store, () -> null, () -> null, () -> null);
    var access =
        factory.create(plugin, new PluginConfig(1L, "{\"enabled\":true}")).credentialAccess();
    assertThrows(PluginCredentialUnavailableException.class, access::resolve);
    credentials.upsertConnected(PLUGIN, "{\"key\":\"sample\"}", null, null, null);
    assertTrue(configurations.update(PLUGIN, 1L, "{\"enabled\":false}"));
    assertEquals("{\"key\":\"sample\"}", access.resolve().payloadJson());
    assertTrue(configurations.update(PLUGIN, 2L, "{\"enabled\":false,\"endpoint\":null}"));
    assertThrows(PluginCredentialUnavailableException.class, access::resolve);
    when(plugin.authenticationSpec()).thenReturn(new StaticKey("key", Set.of()));
    assertEquals(
        "{\"key\":\"sample\"}",
        factory
            .create(plugin, new PluginConfig(1L, "{}"))
            .credentialAccess()
            .resolve()
            .payloadJson());
  }

  /** 在 V1 基线上联合读取配置与最新的凭据。 */
  @Test
  void findsConfigurationWithLatestCredentialOnBaseline() {
    credentials.upsertConnected(PLUGIN, "{\"key\":\"before\"}", null, null, null);
    credentials.upsertConnected(PLUGIN, "{\"key\":\"replacement\"}", null, null, null);

    var configured = credentials.findConfigured(PLUGIN).orElseThrow();
    assertEquals("{\"enabled\":true}", configured.valuesJson());
    assertEquals("{\"key\":\"replacement\"}", configured.credential().payloadJson());
    assertEquals(PluginCredentialStatus.CONNECTED, configured.credential().status());
    assertEquals(1L, configured.credential().version());
  }

  /** 复用初始版本的重建行不受旧调用的迟到拒绝影响。 */
  @Test
  void lateRejectionCannotInvalidateRecreatedCredential() {
    DatabasePluginCredentialStore store =
        new DatabasePluginCredentialStore(credentials, Clock.systemUTC());
    credentials.upsertConnected(PLUGIN, "{\"key\":\"before\"}", null, null, null);
    var before = store.resolve(PLUGIN);
    credentials.delete(PLUGIN);
    credentials.upsertConnected(PLUGIN, "{\"key\":\"after\"}", null, null, null);
    var after = store.resolve(PLUGIN);

    assertEquals(before.version(), after.version());
    assertNotEquals(before.generation(), after.generation());
    assertFalse(store.rejectUsed(before, "late rejection"));
    assertEquals(after, store.resolve(PLUGIN));
    assertTrue(store.rejectUsed(after, "current rejection"));
  }

  /** 领取只取列出的到期行并签发租约；终结要求同一租约与版本。 */
  @Test
  void refreshLeaseLifecycle() {
    credentials.upsertConnected(PLUGIN, "{\"token\":\"a\"}", "CN", at(60), at(-1));
    credentials.upsertConnected(OTHER, "{\"token\":\"b\"}", "CN", at(60), at(-2));
    assertEquals(Optional.of(at(-1)), credentials.earliestRefreshAt(List.of(PLUGIN)));

    PluginCredentialRow claimed =
        credentials.claimDue(List.of(PLUGIN), LEASE, lease("1")).orElseThrow();
    assertEquals(lease("1"), claimed.refreshLeaseToken());
    assertTrue(credentials.claimDue(List.of(PLUGIN), LEASE, lease("2")).isEmpty());
    assertTrue(credentials.earliestRefreshAt(List.of(PLUGIN)).isEmpty());
    assertTrue(credentials.ownsUnexpiredLease(claimed));

    assertTrue(
        credentials.finalizeSuccess(claimed, "{\"token\":\"c\"}", "CN", at(600), at(300), NOW));
    assertFalse(
        credentials.finalizeSuccess(claimed, "{\"token\":\"d\"}", "CN", at(600), at(300), NOW));
    PluginCredentialRow refreshed = credentials.find(PLUGIN).orElseThrow();
    assertEquals("{\"token\":\"c\"}", refreshed.payloadJson());
    assertNull(refreshed.refreshLeaseToken());
    assertEquals(NOW, refreshed.lastRefreshedAt());

    PluginCredentialRow other =
        credentials.claimDue(List.of(OTHER), LEASE, lease("3")).orElseThrow();
    assertTrue(
        credentials.finalizeFailure(
            other, PluginCredentialStatus.REFRESH_FAILED, at(3600), "offline"));
    assertEquals(
        PluginCredentialStatus.REFRESH_FAILED, credentials.find(OTHER).orElseThrow().status());
  }

  /** 过期租约收敛为结果未知，之后不再被领取。 */
  @Test
  void expiredLeasesConvergeToUncertain() {
    credentials.upsertConnected(PLUGIN, "{\"token\":\"a\"}", "CN", at(60), at(-1));
    PluginCredentialRow claimed =
        credentials.claimDue(List.of(PLUGIN), LEASE, lease("expired")).orElseThrow();

    assertEquals(0, credentials.markExpiredLeasesUncertain(List.of(PLUGIN), "lost"));
    // 租约在数据库时钟下过期：旧持有者失去所有权，结果收敛为未知。
    jdbc.sql(
            "update plugin_credential set refresh_lease_until = statement_timestamp()"
                + " - interval '1 second' where plugin_id = ?")
        .params(PLUGIN)
        .update();
    assertFalse(credentials.ownsUnexpiredLease(claimed));
    assertEquals(1, credentials.markExpiredLeasesUncertain(List.of(PLUGIN), "lost"));
    PluginCredentialRow uncertain = credentials.find(PLUGIN).orElseThrow();
    assertEquals(PluginCredentialStatus.REFRESH_UNCERTAIN, uncertain.status());
    assertNull(uncertain.refreshLeaseToken());
    assertTrue(credentials.claimDue(List.of(PLUGIN), LEASE, lease("next")).isEmpty());
  }

  /** 认领轮换摘要；两个阶段都检查有效期，最终只能消费精确认领一次。 */
  @Test
  void authAttemptHasOneOwnerUntilExpiry() {
    new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        .executeWithoutResult(
            status -> {
              long version = configurations.lock(PLUGIN).orElseThrow().version();
              attempts.replace(new PluginAuthAttemptRow(PLUGIN, HASH_1, version, "CN", at(60)));
              assertTrue(attempts.claim(PLUGIN, HASH_1, HASH_2, version, at(60)).isEmpty());
              assertTrue(attempts.claim(PLUGIN, HASH_1, HASH_2, version + 1, NOW).isEmpty());
              PluginAuthAttemptRow claim =
                  attempts.claim(PLUGIN, HASH_1, HASH_2, version, NOW).orElseThrow();
              assertEquals(HASH_2, claim.nonceHash());
              assertTrue(attempts.claim(PLUGIN, HASH_1, HASH_3, version, NOW).isEmpty());
              assertFalse(attempts.consumeClaim(claim, at(60)));
              assertFalse(
                  attempts.consumeClaim(
                      new PluginAuthAttemptRow(PLUGIN, HASH_3, version, "CN", at(60)), NOW));
              assertTrue(attempts.consumeClaim(claim, NOW));
              assertFalse(attempts.consumeClaim(claim, NOW));
            });
  }

  /** 断开已连接插件，既删除旧凭据也让尚未认领的回调无法进入 handler。 */
  @Test
  void disconnectInvalidatesUnclaimedAuth() {
    try (AuthFixture auth = new AuthFixture()) {
      auth.service.completeAuth(PLUGIN, auth.prepare(), "app://initial");
      String oldHandle = auth.prepare();
      auth.service.disconnect(PLUGIN);

      assertTrue(credentials.find(PLUGIN).isEmpty());
      assertEquals(1L, configurations.find(PLUGIN).orElseThrow().version());
      assertInvalidAuth(auth.service, oldHandle, "app://old");
      verify(auth.handler, never()).complete("app://old");
      assertEquals(
          PluginCredentialStatus.CONNECTED,
          auth.service.completeAuth(PLUGIN, auth.prepare(), "app://new").getStatus());
    }
  }

  private enum Invalidation {
    DISCONNECT,
    PREPARE,
    CONFIGURATION_CHANGE
  }

  /** 确认 handler 等待时配置锁已释放；迟到的认领不能覆盖新的登录或写回凭据。 */
  @ParameterizedTest
  @EnumSource(Invalidation.class)
  void inFlightAuthCannotFinalizeAfterInvalidation(Invalidation invalidation) throws Exception {
    try (AuthFixture auth = new AuthFixture();
        var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch entered = new CountDownLatch(1);
      CountDownLatch resume = new CountDownLatch(1);
      when(auth.handler.complete("app://old"))
          .thenAnswer(
              invocation -> {
                entered.countDown();
                assertTrue(resume.await(20, TimeUnit.SECONDS), "handler was not released");
                return auth.material;
              });
      String oldHandle = auth.prepare();
      var pending = workers.submit(() -> assertInvalidAuth(auth.service, oldHandle, "app://old"));
      String newHandle;
      try {
        assertTrue(entered.await(10, TimeUnit.SECONDS), "handler was not entered");
        newHandle =
            workers
                .submit(
                    () -> {
                      assertInvalidAuth(auth.service, oldHandle, "app://duplicate");
                      verify(auth.handler, never()).complete("app://duplicate");
                      switch (invalidation) {
                        case DISCONNECT -> auth.service.disconnect(PLUGIN);
                        case CONFIGURATION_CHANGE -> assertTrue(
                            configurations.update(PLUGIN, 1L, "{}"));
                        case PREPARE -> {
                          return auth.prepare();
                        }
                      }
                      return null;
                    })
                .get(10, TimeUnit.SECONDS);
      } finally {
        resume.countDown();
      }
      pending.get(10, TimeUnit.SECONDS);
      assertTrue(credentials.find(PLUGIN).isEmpty());
      assertEquals(
          invalidation == Invalidation.CONFIGURATION_CHANGE ? 2L : 1L,
          configurations.find(PLUGIN).orElseThrow().version());
      assertEquals(
          PluginCredentialStatus.CONNECTED,
          auth.service
              .completeAuth(PLUGIN, newHandle == null ? auth.prepare() : newHandle, "app://new")
              .getStatus());
    }
  }

  private static void assertInvalidAuth(
      PluginManagementService service, String handle, String callback) {
    DomainException error =
        assertThrows(DomainException.class, () -> service.completeAuth(PLUGIN, handle, callback));
    assertEquals(DomainErrorCode.VALIDATION, error.code());
    assertEquals("auth attempt is unknown, expired, superseded or stale", error.getMessage());
  }

  private final class AuthFixture implements AutoCloseable {
    private final PluginAuthHandler handler = mock(PluginAuthHandler.class);
    private final PluginCredentialMaterial material =
        new PluginCredentialMaterial("CN", at(3600), at(60), "{}");
    private final PluginCatalogRuntime catalog;
    private final PluginManagementService service;

    private AuthFixture() {
      when(handler.loginUrl("CN")).thenReturn("https://login.example/cn");
      when(handler.complete(anyString())).thenReturn(material);
      Plugin plugin = mock(Plugin.class);
      when(plugin.descriptor()).thenReturn(new PluginDescriptor(PLUGIN, "Fixture", "1.0"));
      when(plugin.authenticationSpec()).thenReturn(new DeepLink(List.of("CN")));
      when(plugin.authHandler()).thenReturn(Optional.of(handler));
      when(plugin.configurationSchema()).thenReturn(TestPluginFixtures.EMPTY_SCHEMA);
      PluginRegistry registry = new PluginRegistry(List.of(plugin));
      PluginContextFactory contexts = mock(PluginContextFactory.class);
      catalog =
          new PluginCatalogRuntime(
              new PluginRegistry(List.of()),
              configurations,
              contexts::create,
              List.of(),
              snapshot -> new Object());
      Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
      PluginConfigurationService writes =
          new PluginConfigurationService(
              registry,
              configurations,
              credentials,
              attempts,
              clock,
              contexts,
              mock(PluginAgentDependencies.class),
              new DataSourceTransactionManager(dataSource),
              () -> {},
              () -> {});
      service =
          new PluginManagementService(
              registry,
              () -> catalog,
              configurations,
              credentials,
              writes,
              PluginFunctionNamesProjection.NONE,
              clock);
    }

    private String prepare() {
      return service.prepareAuth(PLUGIN, "CN").getAuthAttemptId();
    }

    @Override
    public void close() {
      catalog.close();
    }
  }

  private static Instant at(long seconds) {
    return NOW.plus(seconds, ChronoUnit.SECONDS);
  }

  private static UUID lease(String value) {
    return UUID.nameUUIDFromBytes(("plugin-credential-" + value).getBytes(StandardCharsets.UTF_8));
  }
}
