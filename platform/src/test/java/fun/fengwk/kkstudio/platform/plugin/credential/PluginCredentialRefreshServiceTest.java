package fun.fengwk.kkstudio.platform.plugin.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.platform.plugin.PluginAuthRejectedException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialRefresher;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginProperties;
import fun.fengwk.kkstudio.platform.plugin.PluginRenewalNotSentException;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.plugin.testing.InMemoryPluginCredentialRepository;

import javax.crypto.SecretKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Plugin 凭据自动刷新服务测试。
 *
 * <p>覆盖刷新编排的核心契约：只领取已安装 Plugin 的行、刷新成功原子替换密文与时间、认证拒绝转为 REAUTH_REQUIRED、
 * 未发出异常（PluginRenewalNotSentException）延迟重试、未知异常收敛为 REFRESH_UNCERTAIN 并永久阻断、 过期 lease 自动收敛、CAS
 * 围栏防并发覆写、有界去敏错误摘要以及批次上限语义。
 */
class PluginCredentialRefreshServiceTest {

  @TempDir Path tempDir;

  private static final String PLUGIN_ID = "test-plugin";
  private static final String REGION = "CN";
  private static final String OLD_PAYLOAD = "{\"token\":\"old-token-12345\"}";
  private static final String NEW_PAYLOAD = "{\"token\":\"new-token-67890\"}";

  private final Instant baseTime = Instant.parse("2026-09-21T12:00:00Z");
  private MutableClock clock;
  private InMemoryPluginCredentialRepository repository;
  private PluginCredentialCodec codec;
  private PluginCredentialKeyLoader keyLoader;
  private PluginProperties properties;

  @BeforeEach
  void setUp() throws IOException {
    clock = new MutableClock(baseTime);
    repository = new InMemoryPluginCredentialRepository();
    codec = new PluginCredentialCodec();

    Path keyFile = tempDir.resolve("master-refresh.key");
    byte[] keyBytes = "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8);
    Files.write(keyFile, keyBytes);
    Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));

    keyLoader = new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString());
    properties = new PluginProperties();
    properties.getRefresh().setPollDelay(Duration.ofMinutes(10));
    properties.getRefresh().setLeaseDuration(Duration.ofMinutes(2));
  }

  private PluginCredentialRow createRow(
      String pluginId,
      String region,
      String payload,
      PluginCredentialStatus status,
      Instant expiresAt,
      Instant nextRefreshAt) {
    SecretKey key = keyLoader.load().orElseThrow();
    byte[] encrypted = codec.encrypt(key, pluginId, region, payload);
    return new PluginCredentialRow(
        pluginId,
        encrypted,
        region,
        expiresAt,
        nextRefreshAt,
        status,
        null,
        null,
        null,
        null,
        0L,
        clock.instant(),
        clock.instant());
  }

  private TestStudioPlugin createPlugin(
      String pluginId, String region, PluginCredentialRefresher refresher) {
    PluginDescriptor descriptor =
        new PluginDescriptor(pluginId, "Plugin " + pluginId, "1.0", List.of(region));
    return new TestStudioPlugin(descriptor, refresher);
  }

  /** 只领取已安装 Plugin 的行：未安装 Plugin 的到期行不被 claim，后续仍保持 dormant（status/version/lease 不变）。 */
  @Test
  void dormantRowsOfUninstalledPluginsAreNotClaimed() {
    PluginCredentialRow dormantRow =
        createRow(
            "uninstalled-plugin",
            REGION,
            OLD_PAYLOAD,
            PluginCredentialStatus.CONNECTED,
            clock.instant().plusSeconds(3600),
            clock.instant().minusSeconds(10));
    repository.setDirect(dormantRow);

    TestStudioPlugin installedPlugin =
        createPlugin(
            PLUGIN_ID,
            REGION,
            snapshot ->
                new PluginCredentialMaterial(
                    REGION,
                    clock.instant().plusSeconds(3600),
                    clock.instant().plusSeconds(1800),
                    NEW_PAYLOAD));
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(installedPlugin));

    PluginCredentialRefreshService service =
        new PluginCredentialRefreshService(
            registry, repository, codec, keyLoader, properties, clock);

    int claimed = service.refreshOnce();
    assertEquals(0, claimed, "uninstalled plugin row must not be claimed");

    PluginCredentialRow current = repository.getDirect("uninstalled-plugin");
    assertEquals(PluginCredentialStatus.CONNECTED, current.status());
    assertEquals(0L, current.version());
    assertNull(current.refreshLeaseToken());
  }

  /**
   * 成功流程：claim 后 refresher 只被调用一次，行被 finalizeSuccess 终结（新密文可解出新材料、 status=CONNECTED、lease
   * 清空、version 前进、last_refreshed_at/next_refresh_at/replaced payload 正确）； 下一次扫描由于 next_refresh_at
   * 在未来而不再被 claim。
   */
  @Test
  void refreshSuccessReplacesPayloadAdvancesVersionAndClearsLease() {
    AtomicInteger callCount = new AtomicInteger(0);
    Instant newExpiresAt = clock.instant().plusSeconds(7200);
    Instant newNextRefresh = clock.instant().plusSeconds(3600);

    TestStudioPlugin plugin =
        createPlugin(
            PLUGIN_ID,
            REGION,
            snapshot -> {
              callCount.incrementAndGet();
              assertEquals(OLD_PAYLOAD, snapshot.payloadJson());
              return new PluginCredentialMaterial(
                  REGION, newExpiresAt, newNextRefresh, NEW_PAYLOAD);
            });
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(plugin));

    PluginCredentialRow initialRow =
        createRow(
            PLUGIN_ID,
            REGION,
            OLD_PAYLOAD,
            PluginCredentialStatus.CONNECTED,
            clock.instant().plusSeconds(3600),
            clock.instant().minusSeconds(10));
    repository.setDirect(initialRow);

    PluginCredentialRefreshService service =
        new PluginCredentialRefreshService(
            registry, repository, codec, keyLoader, properties, clock);

    int claimed = service.refreshOnce();
    assertEquals(1, claimed);
    assertEquals(1, callCount.get(), "refresher must be called exactly once");

    PluginCredentialRow updated = repository.getDirect(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.CONNECTED, updated.status());
    assertNull(updated.refreshLeaseToken());
    assertNull(updated.refreshLeaseUntil());
    assertEquals(
        2L, updated.version(), "version advances from claim (+1) and finalizeSuccess (+1)");
    assertEquals(clock.instant(), updated.lastRefreshedAt());
    assertEquals(newNextRefresh, updated.nextRefreshAt());
    assertNull(updated.lastRefreshError());

    // 验证新密文解密出新载荷
    SecretKey key = keyLoader.load().orElseThrow();
    String decrypted = codec.decrypt(key, PLUGIN_ID, REGION, updated.encryptedPayload());
    assertEquals(NEW_PAYLOAD, decrypted);

    // 下一次扫描该行不再被 claim
    int secondScanClaimed = service.refreshOnce();
    assertEquals(
        0,
        secondScanClaimed,
        "due row must not be claimed again when next_refresh_at is in future");
    assertEquals(1, callCount.get(), "refresher must not be called again");
  }

  /**
   * 认证拒绝（PluginAuthRejectedException）：转为 REAUTH_REQUIRED，next_refresh_at 保持原值， lease
   * 清空，错误摘要来自异常消息且去敏有界。
   */
  @Test
  void authRejectionTransitionsToReauthRequiredAndPreservesNextRefreshAt() {
    Instant originalRefreshAt = clock.instant().minusSeconds(10);
    PluginCredentialRow initialRow =
        createRow(
            PLUGIN_ID,
            REGION,
            OLD_PAYLOAD,
            PluginCredentialStatus.CONNECTED,
            clock.instant().plusSeconds(3600),
            originalRefreshAt);
    repository.setDirect(initialRow);

    TestStudioPlugin plugin =
        createPlugin(
            PLUGIN_ID,
            REGION,
            snapshot -> {
              throw new PluginAuthRejectedException(
                  "token_revoked: refresh token expired\r\nserver rejected");
            });
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(plugin));

    PluginCredentialRefreshService service =
        new PluginCredentialRefreshService(
            registry, repository, codec, keyLoader, properties, clock);

    int claimed = service.refreshOnce();
    assertEquals(1, claimed);

    PluginCredentialRow row = repository.getDirect(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.REAUTH_REQUIRED, row.status());
    assertNull(row.refreshLeaseToken());
    assertEquals(originalRefreshAt, row.nextRefreshAt(), "next_refresh_at must be preserved");
    assertTrue(
        row.lastRefreshError().contains("token_revoked: refresh token expired  server rejected"));
  }

  /**
   * 可证明未发出（PluginRenewalNotSentException）：收敛为 REFRESH_FAILED， next_refresh_at = now + pollDelay；当
   * pollDelay 到达后再次扫描会重试并调用 refresher。
   */
  @Test
  void definitelyNotSentTransitionsToRefreshFailedAndRetriesAfterPollDelay() {
    AtomicInteger callCount = new AtomicInteger(0);
    PluginCredentialRow initialRow =
        createRow(
            PLUGIN_ID,
            REGION,
            OLD_PAYLOAD,
            PluginCredentialStatus.CONNECTED,
            clock.instant().plusSeconds(3600),
            clock.instant().minusSeconds(10));
    repository.setDirect(initialRow);

    TestStudioPlugin plugin =
        createPlugin(
            PLUGIN_ID,
            REGION,
            snapshot -> {
              int count = callCount.incrementAndGet();
              if (count == 1) {
                throw new PluginRenewalNotSentException(
                    "local signature failed before network connect");
              }
              return new PluginCredentialMaterial(
                  REGION,
                  clock.instant().plusSeconds(3600),
                  clock.instant().plusSeconds(1800),
                  NEW_PAYLOAD);
            });
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(plugin));

    PluginCredentialRefreshService service =
        new PluginCredentialRefreshService(
            registry, repository, codec, keyLoader, properties, clock);

    // 第一次扫描：抛出 PluginRenewalNotSentException
    int claimed1 = service.refreshOnce();
    assertEquals(1, claimed1);
    assertEquals(1, callCount.get());

    PluginCredentialRow rowAfterFail = repository.getDirect(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.REFRESH_FAILED, rowAfterFail.status());
    assertNull(rowAfterFail.refreshLeaseToken());
    assertEquals(
        clock.instant().plus(properties.getRefresh().getPollDelay()), rowAfterFail.nextRefreshAt());
    assertTrue(
        rowAfterFail.lastRefreshError().contains("local signature failed before network connect"));

    // 时间尚未到达 pollDelay 时再次扫描，不被 claim
    assertEquals(0, service.refreshOnce());
    assertEquals(1, callCount.get());

    // 推进时钟到达 pollDelay
    clock.advance(properties.getRefresh().getPollDelay());

    // 第二次扫描：应重试并成功
    int claimed2 = service.refreshOnce();
    assertEquals(1, claimed2);
    assertEquals(2, callCount.get());

    PluginCredentialRow rowAfterRetry = repository.getDirect(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.CONNECTED, rowAfterRetry.status());
    assertNull(rowAfterRetry.lastRefreshError());
  }

  /** 结果未知（普通 RuntimeException）：转为 REFRESH_UNCERTAIN，且此后永不再 claim、refresher 永不再被调用。 */
  @Test
  void uncertainOutcomeTransitionsToRefreshUncertainAndNeverClaimsAgain() {
    AtomicInteger callCount = new AtomicInteger(0);
    PluginCredentialRow initialRow =
        createRow(
            PLUGIN_ID,
            REGION,
            OLD_PAYLOAD,
            PluginCredentialStatus.CONNECTED,
            clock.instant().plusSeconds(3600),
            clock.instant().minusSeconds(10));
    repository.setDirect(initialRow);

    TestStudioPlugin plugin =
        createPlugin(
            PLUGIN_ID,
            REGION,
            snapshot -> {
              callCount.incrementAndGet();
              throw new RuntimeException("connection reset by peer mid-stream");
            });
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(plugin));

    PluginCredentialRefreshService service =
        new PluginCredentialRefreshService(
            registry, repository, codec, keyLoader, properties, clock);

    int claimed = service.refreshOnce();
    assertEquals(1, claimed);
    assertEquals(1, callCount.get());

    PluginCredentialRow row = repository.getDirect(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.REFRESH_UNCERTAIN, row.status());
    assertNull(row.refreshLeaseToken());
    assertTrue(row.lastRefreshError().contains("connection reset by peer mid-stream"));

    // 推进时钟多次扫描，确认永不再 claim 和调用 refresher
    for (int i = 0; i < 5; i++) {
      clock.advance(Duration.ofDays(10));
      assertEquals(0, service.refreshOnce());
    }
    assertEquals(
        1, callCount.get(), "refresher must never be called again after REFRESH_UNCERTAIN");
  }

  /**
   * 刷新返回非法凭据材料（material 为 null、region 不一致或 expiresAt 不晚于 now）： 必须按「已收到但不可用」收敛为 {@code
   * REFRESH_FAILED} + 有界延迟重试，且**绝不替换**旧凭据。
   *
   * <p>这不是结果未知（响应是确定的），也不应永久阻断（可能是服务端瞬时异常）：因此既不能写成 {@code REFRESH_UNCERTAIN}，也不能让旧密文被覆盖。
   * 重试仍然只用旧凭据，若旧 token 已失效则下一次会以认证拒绝收敛为 {@code REAUTH_REQUIRED}，仍然 fail closed。
   */
  @Test
  void invalidRenewalMaterialConvergesToBoundedRetryWithoutReplacingPayload() {
    PluginCredentialRow initialRow =
        createRow(
            PLUGIN_ID,
            REGION,
            OLD_PAYLOAD,
            PluginCredentialStatus.CONNECTED,
            clock.instant().plusSeconds(3600),
            clock.instant().minusSeconds(10));
    repository.setDirect(initialRow);

    // 场景 1: material 为 null，requireUsable 抛出 IllegalArgumentException
    TestStudioPlugin nullMaterialPlugin = createPlugin(PLUGIN_ID, REGION, snapshot -> null);
    StudioPluginRegistry registry1 = new StudioPluginRegistry(List.of(nullMaterialPlugin));
    PluginCredentialRefreshService service1 =
        new PluginCredentialRefreshService(
            registry1, repository, codec, keyLoader, properties, clock);

    service1.refreshOnce();
    PluginCredentialRow updated1 = repository.getDirect(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.REFRESH_FAILED, updated1.status());
    assertEquals(
        "plugin credential renewal returned an unusable credential", updated1.lastRefreshError());
    assertEquals(
        clock.instant().plus(properties.getRefresh().getPollDelay()),
        updated1.nextRefreshAt(),
        "不可用结果必须落在有界延迟重试窗口，而不是永久阻断");
    assertNull(updated1.refreshLeaseToken(), "终结后必须清空 lease");

    // 验证旧密文未被替换，依然可以解出 OLD_PAYLOAD
    SecretKey key = keyLoader.load().orElseThrow();
    assertEquals(OLD_PAYLOAD, codec.decrypt(key, PLUGIN_ID, REGION, updated1.encryptedPayload()));

    // 场景 2: region 不一致，验证旧密文绝对没有被替换
    repository.setDirect(initialRow);
    TestStudioPlugin wrongRegionPlugin =
        createPlugin(
            PLUGIN_ID,
            REGION,
            snapshot ->
                new PluginCredentialMaterial(
                    "US",
                    clock.instant().plusSeconds(3600),
                    clock.instant().plusSeconds(1800),
                    NEW_PAYLOAD));
    StudioPluginRegistry registry2 = new StudioPluginRegistry(List.of(wrongRegionPlugin));
    PluginCredentialRefreshService service2 =
        new PluginCredentialRefreshService(
            registry2, repository, codec, keyLoader, properties, clock);

    service2.refreshOnce();
    PluginCredentialRow updated2 = repository.getDirect(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.REFRESH_FAILED, updated2.status());
    assertEquals(REGION, updated2.region(), "不可用材料绝不能改变行的 region 元数据");
    assertEquals(OLD_PAYLOAD, codec.decrypt(key, PLUGIN_ID, REGION, updated2.encryptedPayload()));

    // 场景 3: expiresAt 不晚于 now
    repository.setDirect(initialRow);
    TestStudioPlugin expiredMaterialPlugin =
        createPlugin(
            PLUGIN_ID,
            REGION,
            snapshot ->
                new PluginCredentialMaterial(
                    REGION,
                    clock.instant().minusSeconds(1),
                    clock.instant().plusSeconds(1800),
                    NEW_PAYLOAD));
    StudioPluginRegistry registry3 = new StudioPluginRegistry(List.of(expiredMaterialPlugin));
    PluginCredentialRefreshService service3 =
        new PluginCredentialRefreshService(
            registry3, repository, codec, keyLoader, properties, clock);

    service3.refreshOnce();
    PluginCredentialRow updated3 = repository.getDirect(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.REFRESH_FAILED, updated3.status());
    assertEquals(OLD_PAYLOAD, codec.decrypt(key, PLUGIN_ID, REGION, updated3.encryptedPayload()));

    // 延迟到达后必须真的重试一次（有界重试而不是静默丢弃），且仍然不替换旧凭据。
    clock.advance(properties.getRefresh().getPollDelay());
    service3.refreshOnce();
    PluginCredentialRow retried = repository.getDirect(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.REFRESH_FAILED, retried.status());
    assertEquals(OLD_PAYLOAD, codec.decrypt(key, PLUGIN_ID, REGION, retried.encryptedPayload()));
  }

  /** 主密钥不可用：只 releaseLease，status 不变、lease 清空、refresher 未被调用； 随后主密钥恢复可用时，同一行可被重新 claim 并成功刷新。 */
  @Test
  void keyUnavailableReleasesLeaseWithoutChangingStatusAndCanRetryWhenKeyAvailable()
      throws IOException {
    Path dynamicKeyFile = tempDir.resolve("dynamic-toggle.key");
    // 初始状态：文件不存在
    PluginCredentialKeyLoader dynamicKeyLoader =
        new PluginCredentialKeyLoader(dynamicKeyFile.toAbsolutePath().toString());

    AtomicInteger callCount = new AtomicInteger(0);
    TestStudioPlugin plugin =
        createPlugin(
            PLUGIN_ID,
            REGION,
            snapshot -> {
              callCount.incrementAndGet();
              return new PluginCredentialMaterial(
                  REGION,
                  clock.instant().plusSeconds(3600),
                  clock.instant().plusSeconds(1800),
                  NEW_PAYLOAD);
            });
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(plugin));

    PluginCredentialRow initialRow =
        createRow(
            PLUGIN_ID,
            REGION,
            OLD_PAYLOAD,
            PluginCredentialStatus.CONNECTED,
            clock.instant().plusSeconds(3600),
            clock.instant().minusSeconds(10));
    repository.setDirect(initialRow);

    PluginCredentialRefreshService service =
        new PluginCredentialRefreshService(
            registry, repository, codec, dynamicKeyLoader, properties, clock);

    // 首次扫描：密钥文件尚不存在
    int claimed = service.refreshOnce();
    assertEquals(1, claimed);
    assertEquals(0, callCount.get(), "refresher must not be called when key is unavailable");

    PluginCredentialRow rowAfterRelease = repository.getDirect(PLUGIN_ID);
    assertEquals(
        PluginCredentialStatus.CONNECTED, rowAfterRelease.status(), "status must remain unchanged");
    assertNull(rowAfterRelease.refreshLeaseToken(), "lease must be released");
    assertEquals(2L, rowAfterRelease.version(), "claim (+1) then releaseLease (+1)");

    // 写入合法密钥文件
    byte[] keyBytes = "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8);
    Files.write(dynamicKeyFile, keyBytes);
    Files.setPosixFilePermissions(dynamicKeyFile, PosixFilePermissions.fromString("rw-------"));

    // 再次扫描：密钥就绪，应能正常重新 claim 并成功刷新
    int claimedSecond = service.refreshOnce();
    assertEquals(1, claimedSecond);
    assertEquals(1, callCount.get(), "refresher must be called after key becomes available");

    PluginCredentialRow rowAfterSuccess = repository.getDirect(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.CONNECTED, rowAfterSuccess.status());
    assertNull(rowAfterSuccess.refreshLeaseToken());
  }

  /** 行密文不可认证（被篡改）：直接终结为 REAUTH_REQUIRED，refresher 绝不被调用。 */
  @Test
  void corruptedRowPayloadTransitionsToReauthRequiredWithoutCallingRefresher() {
    AtomicInteger callCount = new AtomicInteger(0);
    TestStudioPlugin plugin =
        createPlugin(
            PLUGIN_ID,
            REGION,
            snapshot -> {
              callCount.incrementAndGet();
              return new PluginCredentialMaterial(
                  REGION,
                  clock.instant().plusSeconds(3600),
                  clock.instant().plusSeconds(1800),
                  NEW_PAYLOAD);
            });
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(plugin));

    PluginCredentialRow initialRow =
        createRow(
            PLUGIN_ID,
            REGION,
            OLD_PAYLOAD,
            PluginCredentialStatus.CONNECTED,
            clock.instant().plusSeconds(3600),
            clock.instant().minusSeconds(10));

    byte[] tampered = initialRow.encryptedPayload();
    tampered[12] = (byte) (tampered[12] ^ 0x55);
    repository.setDirect(
        new PluginCredentialRow(
            initialRow.pluginId(),
            tampered,
            initialRow.region(),
            initialRow.expiresAt(),
            initialRow.nextRefreshAt(),
            initialRow.status(),
            null,
            null,
            null,
            null,
            0L,
            clock.instant(),
            clock.instant()));

    PluginCredentialRefreshService service =
        new PluginCredentialRefreshService(
            registry, repository, codec, keyLoader, properties, clock);

    service.refreshOnce();
    assertEquals(0, callCount.get(), "refresher must not be called when ciphertext is corrupted");

    PluginCredentialRow row = repository.getDirect(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.REAUTH_REQUIRED, row.status());
    assertNull(row.refreshLeaseToken());
    assertEquals(
        "plugin credential payload cannot be authenticated; re-authenticate the plugin",
        row.lastRefreshError());
  }

  /**
   * 过期 in-flight lease：行被另一节点留下 lease_until <= now，refreshOnce() 先收敛为 REFRESH_UNCERTAIN，且本轮与后续轮次都不
   * claim 该行（不重放）。
   */
  @Test
  void expiredInFlightLeaseConvergesToRefreshUncertainAndIsNeverReclaimed() {
    AtomicInteger callCount = new AtomicInteger(0);
    TestStudioPlugin plugin =
        createPlugin(
            PLUGIN_ID,
            REGION,
            snapshot -> {
              callCount.incrementAndGet();
              return new PluginCredentialMaterial(
                  REGION,
                  clock.instant().plusSeconds(3600),
                  clock.instant().plusSeconds(1800),
                  NEW_PAYLOAD);
            });
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(plugin));

    PluginCredentialRow expiredLeaseRow =
        new PluginCredentialRow(
            PLUGIN_ID,
            createRow(
                    PLUGIN_ID,
                    REGION,
                    OLD_PAYLOAD,
                    PluginCredentialStatus.CONNECTED,
                    clock.instant().plusSeconds(3600),
                    clock.instant().minusSeconds(100))
                .encryptedPayload(),
            REGION,
            clock.instant().plusSeconds(3600),
            clock.instant().minusSeconds(100),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            "foreign-node-lease-token",
            clock.instant().minusSeconds(1), // lease 已过期
            5L,
            clock.instant().minusSeconds(500),
            clock.instant().minusSeconds(100));
    repository.setDirect(expiredLeaseRow);

    PluginCredentialRefreshService service =
        new PluginCredentialRefreshService(
            registry, repository, codec, keyLoader, properties, clock);

    int claimed = service.refreshOnce();
    assertEquals(0, claimed, "expired in-flight lease row must not be claimed in this cycle");
    assertEquals(0, callCount.get(), "refresher must not be called");

    PluginCredentialRow row = repository.getDirect(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.REFRESH_UNCERTAIN, row.status());
    assertNull(row.refreshLeaseToken());
    assertNull(row.refreshLeaseUntil());
    assertEquals(6L, row.version());
    assertEquals(
        "plugin credential refresh lease expired before a result was observed",
        row.lastRefreshError());

    // 后续轮次继续推进时间，依然不被 claim
    clock.advance(Duration.ofHours(1));
    assertEquals(0, service.refreshOnce());
    assertEquals(0, callCount.get());
  }

  /**
   * late fence：claim 之后（refresher 返回前）模拟竞争操作（偷 version 或重新登录 upsert）， finalizeSuccess 必须返回 false
   * 且不覆盖竞争者的新凭据与状态。
   */
  @Test
  void lateFenceRejectsSuccessWhenVersionStolenOrUpsertOccurred() {
    String competitorPayload = "{\"token\":\"competitor-login-token\"}";
    AtomicReference<PluginCredentialRow> competitorRow = new AtomicReference<>();

    TestStudioPlugin plugin =
        createPlugin(
            PLUGIN_ID,
            REGION,
            snapshot -> {
              // 在 refresher 内部模拟并发重新登录 upsert：推进 version 并写入新载荷
              PluginCredentialRow reLoginRow =
                  createRow(
                      PLUGIN_ID,
                      REGION,
                      competitorPayload,
                      PluginCredentialStatus.CONNECTED,
                      clock.instant().plusSeconds(7200),
                      clock.instant().plusSeconds(3600));
              repository.upsert(reLoginRow);
              competitorRow.set(repository.getDirect(PLUGIN_ID));
              return new PluginCredentialMaterial(
                  REGION,
                  clock.instant().plusSeconds(5000),
                  clock.instant().plusSeconds(2500),
                  NEW_PAYLOAD);
            });
    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(plugin));

    PluginCredentialRow initialRow =
        createRow(
            PLUGIN_ID,
            REGION,
            OLD_PAYLOAD,
            PluginCredentialStatus.CONNECTED,
            clock.instant().plusSeconds(3600),
            clock.instant().minusSeconds(10));
    repository.setDirect(initialRow);

    PluginCredentialRefreshService service =
        new PluginCredentialRefreshService(
            registry, repository, codec, keyLoader, properties, clock);

    int claimed = service.refreshOnce();
    assertEquals(1, claimed);

    // 校验 repository 维持并发重新登录的数据，未被迟到的 refresh 结果覆盖
    PluginCredentialRow finalRow = repository.getDirect(PLUGIN_ID);
    assertEquals(
        competitorRow.get().version(),
        finalRow.version(),
        "version must not be altered by stale finalizer");
    SecretKey key = keyLoader.load().orElseThrow();
    String decrypted = codec.decrypt(key, PLUGIN_ID, REGION, finalRow.encryptedPayload());
    assertEquals(competitorPayload, decrypted, "payload must belong to the competitor re-login");
  }

  /** registry 为空时 refreshOnce() 返回 0，且绝不触碰 repository。 */
  @Test
  void refreshOnceWithEmptyRegistryDoesNotTouchRepository() {
    StudioPluginRegistry emptyRegistry = new StudioPluginRegistry(List.of());
    PluginCredentialRefreshService service =
        new PluginCredentialRefreshService(
            emptyRegistry, repository, codec, keyLoader, properties, clock);

    assertEquals(0, service.refreshOnce());
    assertEquals(0, repository.size());
  }

  /** 单行刷新失败不阻塞同一批次其它行的刷新。 */
  @Test
  void singleRowFailureDoesNotBlockOtherRowsInSameBatch() {
    String pluginA = "plugin-a";
    String pluginB = "plugin-b";

    TestStudioPlugin pA =
        createPlugin(
            pluginA,
            REGION,
            snapshot -> {
              throw new RuntimeException("plugin A unexpected error");
            });
    TestStudioPlugin pB =
        createPlugin(
            pluginB,
            REGION,
            snapshot ->
                new PluginCredentialMaterial(
                    REGION,
                    clock.instant().plusSeconds(3600),
                    clock.instant().plusSeconds(1800),
                    NEW_PAYLOAD));

    StudioPluginRegistry registry = new StudioPluginRegistry(List.of(pA, pB));

    repository.setDirect(
        createRow(
            pluginA,
            REGION,
            OLD_PAYLOAD,
            PluginCredentialStatus.CONNECTED,
            clock.instant().plusSeconds(3600),
            clock.instant().minusSeconds(10)));
    repository.setDirect(
        createRow(
            pluginB,
            REGION,
            OLD_PAYLOAD,
            PluginCredentialStatus.CONNECTED,
            clock.instant().plusSeconds(3600),
            clock.instant().minusSeconds(10)));

    PluginCredentialRefreshService service =
        new PluginCredentialRefreshService(
            registry, repository, codec, keyLoader, properties, clock);

    int claimed = service.refreshOnce();
    assertEquals(2, claimed);

    PluginCredentialRow rowA = repository.getDirect(pluginA);
    PluginCredentialRow rowB = repository.getDirect(pluginB);

    assertEquals(PluginCredentialStatus.REFRESH_UNCERTAIN, rowA.status());
    assertEquals(PluginCredentialStatus.CONNECTED, rowB.status());
    SecretKey key = keyLoader.load().orElseThrow();
    assertEquals(NEW_PAYLOAD, codec.decrypt(key, pluginB, REGION, rowB.encryptedPayload()));
  }

  /** boundedError() 去敏错误摘要契约：4096 字节上限、换行转空格、空/空白回退为 fallback。 */
  @Test
  void boundedErrorEnforcesLengthLimitAndSanitization() {
    assertEquals("fallback", PluginCredentialRefreshService.boundedError(null, "fallback"));
    assertEquals("fallback", PluginCredentialRefreshService.boundedError("", "fallback"));
    assertEquals("fallback", PluginCredentialRefreshService.boundedError("   \n\r  ", "fallback"));

    String withNewlines = "line1\nline2\r\nline3\rline4";
    assertEquals(
        "line1 line2  line3 line4",
        PluginCredentialRefreshService.boundedError(withNewlines, "fb"));

    // 超过 4096 字节的长文本截断
    String longMessage = "A".repeat(5000);
    String bounded = PluginCredentialRefreshService.boundedError(longMessage, "fb");
    assertEquals(4096, bounded.getBytes(StandardCharsets.UTF_8).length);
    assertEquals("A".repeat(4096), bounded);

    // 多字节 UTF-8 字符（汉字占 3 字节）边界截断安全，不得产生乱码
    String chinese = "中".repeat(2000); // 6000 字节
    String boundedChinese = PluginCredentialRefreshService.boundedError(chinese, "fb");
    assertTrue(boundedChinese.getBytes(StandardCharsets.UTF_8).length <= 4096);
    assertFalse(boundedChinese.isEmpty());
  }

  /** MAX_CLAIM_BATCH 语义：多于 16 行到期时单次扫描只 claim 16 行，下一次扫描继续处理剩余行。 */
  @Test
  void maxClaimBatchRespectsSixteenLimit() {
    List<StudioPlugin> plugins = new ArrayList<>();
    for (int i = 1; i <= 20; i++) {
      String pid = String.format("plugin-%02d", i);
      plugins.add(
          createPlugin(
              pid,
              REGION,
              snapshot ->
                  new PluginCredentialMaterial(
                      REGION,
                      clock.instant().plusSeconds(3600),
                      clock.instant().plusSeconds(1800),
                      NEW_PAYLOAD)));
      repository.setDirect(
          createRow(
              pid,
              REGION,
              OLD_PAYLOAD,
              PluginCredentialStatus.CONNECTED,
              clock.instant().plusSeconds(3600),
              clock.instant().minusSeconds(100 - i)));
    }

    StudioPluginRegistry registry = new StudioPluginRegistry(plugins);
    PluginCredentialRefreshService service =
        new PluginCredentialRefreshService(
            registry, repository, codec, keyLoader, properties, clock);

    // 第一次扫描：正好 claim 16 行
    int claimedBatch1 = service.refreshOnce();
    assertEquals(PluginCredentialRefreshService.MAX_CLAIM_BATCH, claimedBatch1);
    assertEquals(16, claimedBatch1);

    // 第二次扫描：claim 剩余的 4 行
    int claimedBatch2 = service.refreshOnce();
    assertEquals(4, claimedBatch2);

    // 第三次扫描：没有剩余到期行
    assertEquals(0, service.refreshOnce());
  }

  private static class TestStudioPlugin implements StudioPlugin {
    private final PluginDescriptor descriptor;
    private final PluginCredentialRefresher refresher;

    TestStudioPlugin(PluginDescriptor descriptor, PluginCredentialRefresher refresher) {
      this.descriptor = descriptor;
      this.refresher = refresher;
    }

    @Override
    public PluginDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public Optional<PluginCredentialRefresher> refresher() {
      return Optional.ofNullable(refresher);
    }
  }

  private static class MutableClock extends Clock {
    private Instant current;
    private final ZoneId zone = ZoneOffset.UTC;

    MutableClock(Instant initial) {
      this.current = initial;
    }

    void advance(Duration duration) {
      this.current = current.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return current;
    }
  }
}
