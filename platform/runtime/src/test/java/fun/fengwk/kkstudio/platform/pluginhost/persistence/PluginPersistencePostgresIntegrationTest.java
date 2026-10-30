package fun.fengwk.kkstudio.platform.pluginhost.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.Driver;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import fun.fengwk.kkstudio.platform.harness.persistence.postgresql.PostgresSchemaSupport;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** 插件配置、凭据与认证 attempt 三个仓储在真实 PostgreSQL 上的 SQL 契约。 */
class PluginPersistencePostgresIntegrationTest {

  private static final String PLUGIN = "alpha.plugin";
  private static final String OTHER = "beta.plugin";
  private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
  private static final Duration LEASE = Duration.ofMinutes(2);
  private static final String HASH_1 = "1".repeat(64);
  private static final String HASH_2 = "2".repeat(64);
  private static final String HASH_3 = "3".repeat(64);

  private static JdbcClient jdbc;

  private PluginConfigurationRepository configurations;
  private PluginCredentialRepository credentials;
  private PluginAuthAttemptRepository attempts;

  @BeforeAll
  static void connect() {
    jdbc =
        JdbcClient.create(
            new SimpleDriverDataSource(
                new Driver(),
                PostgresSchemaSupport.POSTGRES.getJdbcUrl(),
                PostgresSchemaSupport.POSTGRES.getUsername(),
                PostgresSchemaSupport.POSTGRES.getPassword()));
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
    assertEquals(2, configurations.findAll().size());
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

    assertTrue(credentials.rejectUsed(PLUGIN, first.version(), "revoked"));
    assertFalse(credentials.rejectUsed(PLUGIN, first.version(), "revoked"));
    PluginCredentialRow rejected = credentials.find(PLUGIN).orElseThrow();
    assertEquals(PluginCredentialStatus.REAUTH_REQUIRED, rejected.status());
    assertEquals("revoked", rejected.lastRefreshError());

    credentials.upsertConnected(PLUGIN, "{\"key\":\"k\"}", null, null, null);
    PluginCredentialRow replaced = credentials.find(PLUGIN).orElseThrow();
    assertEquals(PluginCredentialStatus.CONNECTED, replaced.status());
    assertNull(replaced.lastRefreshError());
    assertNull(replaced.expiresAt());
    assertEquals(rejected.version() + 1, replaced.version());

    credentials.delete(PLUGIN);
    assertTrue(credentials.find(PLUGIN).isEmpty());
  }

  /** 领取只取列出的到期行并签发租约；终结与释放都要求同一租约与版本。 */
  @Test
  void refreshLeaseLifecycle() {
    credentials.upsertConnected(PLUGIN, "{\"token\":\"a\"}", "CN", at(60), at(-1));
    credentials.upsertConnected(OTHER, "{\"token\":\"b\"}", "CN", at(60), at(-2));
    assertEquals(Optional.of(at(-1)), credentials.earliestRefreshAt(List.of(PLUGIN)));

    PluginCredentialRow claimed =
        credentials.claimDue(List.of(PLUGIN), LEASE, "lease-1").orElseThrow();
    assertEquals("lease-1", claimed.refreshLeaseToken());
    assertTrue(credentials.claimDue(List.of(PLUGIN), LEASE, "lease-2").isEmpty());
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
        credentials.claimDue(List.of(OTHER), LEASE, "lease-3").orElseThrow();
    assertTrue(
        credentials.finalizeFailure(
            other, PluginCredentialStatus.REFRESH_FAILED, at(3600), "offline"));
    assertEquals(
        PluginCredentialStatus.REFRESH_FAILED, credentials.find(OTHER).orElseThrow().status());

    jdbc.sql("update plugin_credential set next_refresh_at = ? where plugin_id = ?")
        .params(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), OTHER)
        .update();
    PluginCredentialRow again =
        credentials.claimDue(List.of(OTHER), LEASE, "lease-4").orElseThrow();
    assertTrue(credentials.releaseLease(again));
    assertFalse(credentials.releaseLease(again));
  }

  /** 过期租约收敛为结果未知，之后不再被领取。 */
  @Test
  void expiredLeasesConvergeToUncertain() {
    credentials.upsertConnected(PLUGIN, "{\"token\":\"a\"}", "CN", at(60), at(-1));
    PluginCredentialRow claimed =
        credentials.claimDue(List.of(PLUGIN), LEASE, "lease").orElseThrow();

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
    assertTrue(credentials.claimDue(List.of(PLUGIN), LEASE, "next").isEmpty());
  }

  /** attempt 只在摘要命中、未过期且配置修订未变时被消费一次；新 attempt 覆盖旧 attempt。 */
  @Test
  void authAttemptIsConsumedOnceAgainstTheCurrentRevision() {
    attempts.replace(new PluginAuthAttemptRow(PLUGIN, HASH_1, 1L, "CN", at(60)));
    attempts.replace(new PluginAuthAttemptRow(PLUGIN, HASH_2, 1L, "CN", at(60)));

    assertTrue(attempts.consume(PLUGIN, HASH_1, NOW).isEmpty());
    assertTrue(attempts.consume(PLUGIN, HASH_2, at(60)).isEmpty());
    assertEquals(
        new PluginAuthAttemptRow(PLUGIN, HASH_2, 1L, "CN", at(60)),
        attempts.consume(PLUGIN, HASH_2, NOW).orElseThrow());
    assertTrue(attempts.consume(PLUGIN, HASH_2, NOW).isEmpty());

    attempts.replace(new PluginAuthAttemptRow(PLUGIN, HASH_3, 1L, "CN", at(60)));
    assertTrue(configurations.update(PLUGIN, 1L, "{\"enabled\":false}"));
    assertTrue(attempts.consume(PLUGIN, HASH_3, NOW).isEmpty());
  }

  private static Instant at(long seconds) {
    return NOW.plus(seconds, ChronoUnit.SECONDS);
  }
}
