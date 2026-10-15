package fun.fengwk.kkstudio.platform.plugin.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogManifest;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRecord;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.plugin.testing.InMemoryPluginCredentialRepository;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 数据库凭据 Store 读取契约与状态矩阵测试。
 *
 * <p>写入只有唯一入口 {@code PluginConfigurationCommitService}，其真实 PG 回归在 web 集成测试；本类用内存仓储直接植入权威行，覆盖
 * resolve / scoped resolve / projection / rejectUsed 的行为矩阵：密钥缺失或密文损坏时 fail-closed、错误消息不泄露敏感载荷、已过期的
 * lease 不误判为 refreshing、REFRESH_FAILED 在有效期内仍可读取旧凭据、旧引用（代际或上下文不复存在）绝不拿到新秘密、静态密钥没有本地过期语义。
 */
class DatabasePluginCredentialStoreTest {

  @TempDir Path tempDir;

  private static final String PLUGIN_ID = "test.plugin";
  private static final String REGION = "CN";
  private static final String SECRET_TOKEN = "super-secret-token-marker-98765";
  private static final String PAYLOAD = "{\"token\":\"" + SECRET_TOKEN + "\"}";
  private static final String STATIC_PAYLOAD = "{\"apiKey\":\"" + SECRET_TOKEN + "\"}";
  private static final UUID GENERATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID OTHER_GENERATION =
      UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String CONTEXT =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String OTHER_CONTEXT =
      "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

  private final Instant now = Instant.parse("2026-09-21T10:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  private final PluginCredentialCodec codec = new PluginCredentialCodec();

  private InMemoryPluginCredentialRepository repository;
  private TestCatalogRepository catalog;
  private PluginCredentialKeyLoader keyLoader;
  private DatabasePluginCredentialStore store;
  private DatabasePluginCredentialStore storeWithoutKey;

  @BeforeEach
  void setUp() throws IOException {
    repository = new InMemoryPluginCredentialRepository();
    catalog = new TestCatalogRepository();

    Path keyFile = tempDir.resolve("valid-master.key");
    Files.write(keyFile, keyBytes());
    Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));

    keyLoader = new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString());
    store = new DatabasePluginCredentialStore(repository, catalog, codec, keyLoader, clock);
    // 解析只有绑定引用一条入口：默认把 PLUGIN_ID 的权威坐标设为 GENERATION/CONTEXT。
    publishManifest(PLUGIN_ID, GENERATION, CONTEXT);

    PluginCredentialKeyLoader missingKeyLoader =
        new PluginCredentialKeyLoader(
            tempDir.resolve("non-existent.key").toAbsolutePath().toString());
    storeWithoutKey =
        new DatabasePluginCredentialStore(repository, catalog, codec, missingKeyLoader, clock);
  }

  /** 读取路径不受写入入口变化影响：植入的交互行必须能解出同一载荷，且密文中没有明文标记。 */
  @Test
  void resolvesTheSeededInteractiveRowWithoutLeakingPlainText() {
    PluginCredentialRow row = seedInteractive(GENERATION, CONTEXT, 3600, 1800, PAYLOAD);

    PluginCredentialSnapshot snapshot = resolveCurrent();

    assertEquals(PLUGIN_ID, snapshot.pluginId());
    assertEquals(REGION, snapshot.region());
    assertEquals(PAYLOAD, snapshot.payloadJson());
    assertEquals(GENERATION, snapshot.authGeneration());
    assertEquals(CONTEXT, snapshot.contextFingerprint());
    assertEquals(row.expiresAt(), snapshot.expiresAt());
    assertEquals(0L, snapshot.version());
    assertFalse(
        indexOf(row.encryptedPayload(), SECRET_TOKEN.getBytes(StandardCharsets.UTF_8)) >= 0,
        "encrypted_payload must not contain plaintext secret token");
  }

  /** 静态密钥形态：没有 region、过期与刷新时刻，读取必须成功且不伪造本地过期。 */
  @Test
  void resolvesStaticKeyRowWithoutForgingExpiry() {
    PluginCredentialRow row =
        repositoryRow(PLUGIN_ID, GENERATION, CONTEXT, STATIC_PAYLOAD, null, null, null);
    repository.setDirect(row);

    PluginCredentialSnapshot snapshot = resolveCurrent();

    assertEquals(STATIC_PAYLOAD, snapshot.payloadJson());
    assertNull(snapshot.region());
    assertNull(snapshot.expiresAt());
    assertFalse(snapshot.interactive());
    assertNull(row.nextRefreshAt());
  }

  /** 密文绑定代际：把密文搬到「引用与行都为新代际」的元数据下必须 GCM 认证失败，绝不返回旧明文。 */
  @Test
  void changedGenerationMetadataCannotAuthenticateTheOldCiphertext() {
    publishManifest(PLUGIN_ID, OTHER_GENERATION, CONTEXT);
    repository.setDirect(
        new PluginCredentialRow(
            PLUGIN_ID,
            OTHER_GENERATION,
            CONTEXT,
            seedInteractive(GENERATION, CONTEXT, 3600, 1800, PAYLOAD).encryptedPayload(),
            REGION,
            now.plusSeconds(3600),
            now.plusSeconds(1800),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            null,
            null,
            0L,
            now,
            now));

    assertEquals(PluginCredentialStatus.KEY_UNAVAILABLE, store.projection(PLUGIN_ID).status());
    PluginCredentialUnavailableException error =
        assertThrows(
            PluginCredentialUnavailableException.class,
            () ->
                store.resolve(new PluginCredentialReference(PLUGIN_ID, OTHER_GENERATION, CONTEXT)));
    assertEquals(PluginCredentialUnavailableReason.KEY_UNAVAILABLE, error.reason());
    assertFalse(error.getMessage().contains(SECRET_TOKEN));
  }

  /** 密文同样绑定认证上下文：只改 context fingerprint 也必须认证失败，绝不返回部分明文。 */
  @Test
  void changedContextMetadataCannotAuthenticateTheOldCiphertext() {
    PluginCredentialRow seeded = seedInteractive(GENERATION, CONTEXT, 3600, 1800, PAYLOAD);
    publishManifest(PLUGIN_ID, GENERATION, OTHER_CONTEXT);
    repository.setDirect(
        new PluginCredentialRow(
            PLUGIN_ID,
            GENERATION,
            OTHER_CONTEXT,
            seeded.encryptedPayload(),
            REGION,
            seeded.expiresAt(),
            seeded.nextRefreshAt(),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            null,
            null,
            0L,
            now,
            now));

    PluginCredentialUnavailableException error =
        assertThrows(
            PluginCredentialUnavailableException.class,
            () ->
                store.resolve(new PluginCredentialReference(PLUGIN_ID, GENERATION, OTHER_CONTEXT)));
    assertEquals(PluginCredentialUnavailableReason.KEY_UNAVAILABLE, error.reason());
    assertFalse(error.getMessage().contains(SECRET_TOKEN));
  }

  /** resolve() 状态矩阵与不泄露明文 payload 断言： 无行、无密钥、密文篡改、REAUTH_REQUIRED、REFRESH_UNCERTAIN、已过期。 */
  @Test
  void resolveStatusMatrixEnforcesSecurityAndCorrectReasons() {
    // 1. 无行 -> NOT_CONNECTED
    PluginCredentialUnavailableException ex1 =
        assertThrows(
            PluginCredentialUnavailableException.class,
            () ->
                store.resolve(
                    new PluginCredentialReference("unknown.plugin", GENERATION, CONTEXT)));
    assertEquals(PluginCredentialUnavailableReason.NOT_CONNECTED, ex1.reason());
    assertFalse(ex1.getMessage().contains(SECRET_TOKEN));

    // 先植入一条合法凭据
    PluginCredentialRow row = seedInteractive(GENERATION, CONTEXT, 3600, 1800, PAYLOAD);

    // 2. 主密钥不可用 -> KEY_UNAVAILABLE
    PluginCredentialUnavailableException ex2 =
        assertThrows(
            PluginCredentialUnavailableException.class,
            () -> storeWithoutKey.resolve(currentReference()));
    assertEquals(PluginCredentialUnavailableReason.KEY_UNAVAILABLE, ex2.reason());
    assertFalse(ex2.getMessage().contains(SECRET_TOKEN));

    // 3. 密文被篡改（直接改 repository 内部 payload） -> KEY_UNAVAILABLE
    byte[] tampered = row.encryptedPayload();
    tampered[15] = (byte) (tampered[15] ^ 0xFF);
    repository.setDirect(withPayload(row, tampered));

    PluginCredentialUnavailableException ex3 =
        assertThrows(PluginCredentialUnavailableException.class, () -> resolveCurrent());
    assertEquals(PluginCredentialUnavailableReason.KEY_UNAVAILABLE, ex3.reason());
    assertFalse(ex3.getMessage().contains(SECRET_TOKEN));

    // 恢复正常密文
    PluginCredentialRow originalRow = seedInteractive(GENERATION, CONTEXT, 3600, 1800, PAYLOAD);

    // 4. REAUTH_REQUIRED -> REAUTH_REQUIRED
    repository.setDirect(
        withStatus(originalRow, PluginCredentialStatus.REAUTH_REQUIRED, "auth rejected"));
    PluginCredentialUnavailableException ex4 =
        assertThrows(PluginCredentialUnavailableException.class, () -> resolveCurrent());
    assertEquals(PluginCredentialUnavailableReason.REAUTH_REQUIRED, ex4.reason());
    assertFalse(ex4.getMessage().contains(SECRET_TOKEN));

    // 5. REFRESH_UNCERTAIN -> REFRESH_UNCERTAIN
    repository.setDirect(
        withStatus(originalRow, PluginCredentialStatus.REFRESH_UNCERTAIN, "refresh uncertain"));
    PluginCredentialUnavailableException ex5 =
        assertThrows(PluginCredentialUnavailableException.class, () -> resolveCurrent());
    assertEquals(PluginCredentialUnavailableReason.REFRESH_UNCERTAIN, ex5.reason());
    assertFalse(ex5.getMessage().contains(SECRET_TOKEN));

    // 6. CONNECTED 但已过期 -> EXPIRED
    repository.setDirect(
        new PluginCredentialRow(
            originalRow.pluginId(),
            originalRow.authGeneration(),
            originalRow.contextFingerprint(),
            originalRow.encryptedPayload(),
            originalRow.region(),
            now.minusSeconds(10), // expiresAt 已过
            originalRow.nextRefreshAt(),
            PluginCredentialStatus.CONNECTED,
            null,
            null,
            null,
            null,
            originalRow.version(),
            originalRow.createTime(),
            originalRow.updateTime()));
    PluginCredentialUnavailableException ex6 =
        assertThrows(PluginCredentialUnavailableException.class, () -> resolveCurrent());
    assertEquals(PluginCredentialUnavailableReason.EXPIRED, ex6.reason());
    assertFalse(ex6.getMessage().contains(SECRET_TOKEN));

    // 7. CONNECTED 在期限内 -> 成功解密并返回快照
    seedInteractive(GENERATION, CONTEXT, 3600, 1800, PAYLOAD);
    PluginCredentialSnapshot snapshot = resolveCurrent();
    assertEquals(PLUGIN_ID, snapshot.pluginId());
    assertEquals(REGION, snapshot.region());
    assertEquals(PAYLOAD, snapshot.payloadJson());

    // 8. REFRESH_FAILED 且未过期 -> 仍然返回 snapshot（可证明未发出的失败不废弃旧凭据）
    repository.setDirect(
        withStatus(
            repository.getDirect(PLUGIN_ID),
            PluginCredentialStatus.REFRESH_FAILED,
            "network temporarily unreachable"));
    PluginCredentialSnapshot failedSnapshot = resolveCurrent();
    assertEquals(PLUGIN_ID, failedSnapshot.pluginId());
    assertEquals(REGION, failedSnapshot.region());
    assertEquals(PAYLOAD, failedSnapshot.payloadJson());
  }

  /**
   * 刷新互斥机制：行处于 leased 且 refresh_lease_until 在未来时抛出 AUTH_REFRESHING； 若 lease 已过期（<= now），则不再视为
   * refreshing，按状态正常判定。
   */
  @Test
  void resolveRefreshMutexHandlesActiveAndExpiredLeases() {
    PluginCredentialRow row = seedInteractive(GENERATION, CONTEXT, 3600, 1800, PAYLOAD);

    repository.setDirect(withLease(row, "lease-token-123", now.plusSeconds(120)));
    PluginCredentialUnavailableException activeEx =
        assertThrows(PluginCredentialUnavailableException.class, () -> resolveCurrent());
    assertEquals(PluginCredentialUnavailableReason.AUTH_REFRESHING, activeEx.reason());
    assertFalse(activeEx.getMessage().contains(SECRET_TOKEN));

    repository.setDirect(withLease(row, "lease-token-123", now.minusSeconds(1)));
    PluginCredentialSnapshot snapshot = resolveCurrent();
    assertEquals(PAYLOAD, snapshot.payloadJson(), "expired lease must not block resolve");
  }

  /**
   * projection() 契约： 无行返回 NOT_CONNECTED；主密钥不可用或密文无法解密时返回 KEY_UNAVAILABLE，且不泄露 region / expiresAt
   * 等元数据。
   */
  @Test
  void projectionHidesMetadataWhenKeyUnavailableOrPayloadUndecryptable() {
    // 1. 无行
    PluginCredentialProjection notConnectedProj = store.projection("absent");
    assertEquals(PluginCredentialStatus.NOT_CONNECTED, notConnectedProj.status());

    // 2. 正常行
    PluginCredentialRow row = seedInteractive(GENERATION, CONTEXT, 3600, 1800, PAYLOAD);
    PluginCredentialProjection normalProj = store.projection(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.CONNECTED, normalProj.status());
    assertEquals(REGION, normalProj.region());
    assertNotNull(normalProj.expiresAt());

    // 3. 主密钥不可用
    PluginCredentialProjection keyUnavailableProj = storeWithoutKey.projection(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.KEY_UNAVAILABLE, keyUnavailableProj.status());
    assertNull(keyUnavailableProj.region(), "must not leak region when key is unavailable");
    assertNull(keyUnavailableProj.expiresAt(), "must not leak expiresAt when key is unavailable");

    // 4. 密文损坏不可解
    byte[] corrupted = row.encryptedPayload();
    corrupted[10] = (byte) (corrupted[10] ^ 0xAA);
    repository.setDirect(withPayload(row, corrupted));

    PluginCredentialProjection corruptedProj = store.projection(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.KEY_UNAVAILABLE, corruptedProj.status());
    assertNull(corruptedProj.region(), "must not leak region when ciphertext cannot be decrypted");
    assertNull(
        corruptedProj.expiresAt(), "must not leak expiresAt when ciphertext cannot be decrypted");
  }

  /** scoped resolve 只在引用仍等于权威 manifest 与当前行时成功；行被删除后必须 fail closed。 */
  @Test
  void scopedResolveRequiresTheReferenceToStayCurrent() {
    seedInteractive(GENERATION, CONTEXT, 3600, 1800, PAYLOAD);
    publishManifest(PLUGIN_ID, GENERATION, CONTEXT);
    PluginCredentialReference reference =
        new PluginCredentialReference(PLUGIN_ID, GENERATION, CONTEXT);

    assertEquals(PAYLOAD, store.resolve(reference).payloadJson());

    // 行被删除（断连）后同一引用不再解析。
    repository.delete(PLUGIN_ID, GENERATION, CONTEXT, 0L);
    PluginCredentialUnavailableException missing =
        assertThrows(PluginCredentialUnavailableException.class, () -> store.resolve(reference));
    assertEquals(PluginCredentialUnavailableReason.NOT_CONNECTED, missing.reason());
    assertFalse(missing.getMessage().contains(SECRET_TOKEN));
  }

  /** 旧引用的两种漂移都必须被拒：manifest 代际前进，或行已换成新上下文/新代际。 */
  @Test
  void scopedResolveRejectsStaleGenerationAndContext() {
    seedInteractive(GENERATION, CONTEXT, 3600, 1800, PAYLOAD);
    publishManifest(PLUGIN_ID, GENERATION, CONTEXT);

    // 1. manifest 已前进到新代际（例如刚刚重新登录）：旧引用在权威坐标层面即失效。
    publishManifest(PLUGIN_ID, OTHER_GENERATION, CONTEXT);
    PluginCredentialUnavailableException staleGeneration =
        assertThrows(
            PluginCredentialUnavailableException.class,
            () -> store.resolve(new PluginCredentialReference(PLUGIN_ID, GENERATION, CONTEXT)));
    assertEquals(PluginCredentialUnavailableReason.NOT_CONNECTED, staleGeneration.reason());
    assertFalse(staleGeneration.getMessage().contains(SECRET_TOKEN));

    // 2. 上下文指纹变化（配置目标字段变化）同样让旧引用失效。
    publishManifest(PLUGIN_ID, OTHER_GENERATION, OTHER_CONTEXT);
    PluginCredentialUnavailableException staleContext =
        assertThrows(
            PluginCredentialUnavailableException.class,
            () ->
                store.resolve(new PluginCredentialReference(PLUGIN_ID, OTHER_GENERATION, CONTEXT)));
    assertEquals(PluginCredentialUnavailableReason.NOT_CONNECTED, staleContext.reason());

    // 3. 引用与 manifest 一致、但行仍是旧代际：也不允许解析出旧秘密。
    PluginCredentialUnavailableException staleRow =
        assertThrows(
            PluginCredentialUnavailableException.class,
            () ->
                store.resolve(
                    new PluginCredentialReference(PLUGIN_ID, OTHER_GENERATION, OTHER_CONTEXT)));
    assertEquals(PluginCredentialUnavailableReason.NOT_CONNECTED, staleRow.reason());
    assertFalse(staleRow.getMessage().contains(SECRET_TOKEN));
  }

  /** 权威 catalog 不可用时（行缺失或读取抛错）scoped resolve 必须失败关闭，绝不退回无围栏读取。 */
  @Test
  void scopedResolveFailsClosedWhenCatalogIsUnavailable() {
    seedInteractive(GENERATION, CONTEXT, 3600, 1800, PAYLOAD);
    PluginCredentialReference reference =
        new PluginCredentialReference(PLUGIN_ID, GENERATION, CONTEXT);

    catalog.catalog = null;
    PluginCredentialUnavailableException missingCatalog =
        assertThrows(PluginCredentialUnavailableException.class, () -> store.resolve(reference));
    assertEquals(PluginCredentialUnavailableReason.KEY_UNAVAILABLE, missingCatalog.reason());

    catalog.readFailure = new IllegalStateException("catalog exploded");
    PluginCredentialUnavailableException failedCatalog =
        assertThrows(PluginCredentialUnavailableException.class, () -> store.resolve(reference));
    assertEquals(PluginCredentialUnavailableReason.KEY_UNAVAILABLE, failedCatalog.reason());
    assertFalse(failedCatalog.getMessage().contains(SECRET_TOKEN));
    // 唯一入口始终先核对权威坐标：catalog 不可用时不存在任何绕过围栏的读取。
    PluginCredentialUnavailableException stillUnavailable =
        assertThrows(PluginCredentialUnavailableException.class, () -> store.resolve(reference));
    assertEquals(PluginCredentialUnavailableReason.KEY_UNAVAILABLE, stillUnavailable.reason());
  }

  /** 断连后凭据行被删除，绑定解析变为 NOT_CONNECTED，projection() 也回到 NOT_CONNECTED。 */
  @Test
  void removedCredentialRowMakesResolveReturnNotConnected() {
    PluginCredentialRow row = seedInteractive(GENERATION, CONTEXT, 3600, 1800, PAYLOAD);
    assertTrue(repository.delete(PLUGIN_ID, GENERATION, CONTEXT, row.version()));

    PluginCredentialUnavailableException ex =
        assertThrows(PluginCredentialUnavailableException.class, () -> resolveCurrent());
    assertEquals(PluginCredentialUnavailableReason.NOT_CONNECTED, ex.reason());
    assertEquals(PluginCredentialStatus.NOT_CONNECTED, store.projection(PLUGIN_ID).status());
  }

  /** 调用期认证拒绝只作废本次已用凭据：version 与原始密文都指向当前行时，置为 {@code REAUTH_REQUIRED} 并清空在途 lease， 错误摘要受有界去敏约束。 */
  @Test
  void rejectUsedMarksOnlyTheMatchingCredential() {
    seedInteractive(GENERATION, CONTEXT, 3600, 1800, PAYLOAD);
    PluginCredentialSnapshot snapshot = resolveCurrent();
    assertEquals(0L, snapshot.version());

    assertTrue(store.rejectUsed(snapshot, "provider rejected the access token"));

    PluginCredentialRow row = repository.getDirect(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.REAUTH_REQUIRED, row.status());
    assertNull(row.refreshLeaseToken());
    assertNull(row.refreshLeaseUntil());
    assertEquals(1L, row.version());
    assertEquals("provider rejected the access token", row.lastRefreshError());
    assertFalse(row.lastRefreshError().contains(SECRET_TOKEN));
  }

  /** 并发重新登录写入的新 version 与新密文都不会被调用期认证拒绝误杀；但 version 前进而密文未变（例如在途刷新领取）时仍必须命中， 因为被拒绝的确实是同一条凭据。 */
  @Test
  void rejectUsedNeverKillsAConcurrentReloginButMatchesUnchangedCiphertext() {
    seedInteractive(GENERATION, CONTEXT, 3600, 1800, PAYLOAD);
    PluginCredentialSnapshot usedCredential = resolveCurrent();

    // 并发重新登录：整体替换载荷与新代际，并推进 version；权威坐标随之前进。
    seedInteractive(OTHER_GENERATION, CONTEXT, 7200, 3600, "{\"token\":\"fresh-login\"}");
    publishManifest(PLUGIN_ID, OTHER_GENERATION, CONTEXT);
    PluginCredentialRow afterRelogin = repository.getDirect(PLUGIN_ID);
    assertEquals(1L, afterRelogin.version());

    assertFalse(
        store.rejectUsed(usedCredential, "stale token rejected"),
        "a concurrent re-login must not be invalidated by the old credential's rejection");
    assertEquals(PluginCredentialStatus.CONNECTED, repository.getDirect(PLUGIN_ID).status());

    // version 前进但密文不变（其它节点推进版本）：仍应命中并作废这条确实被拒绝的凭据。
    PluginCredentialSnapshot currentSnapshot = resolveCurrent();
    assertEquals(1L, currentSnapshot.version());
    repository.stealVersion(PLUGIN_ID);
    PluginCredentialRow advanced = repository.getDirect(PLUGIN_ID);
    assertEquals(2L, advanced.version());
    assertTrue(
        Arrays.equals(currentSnapshot.encryptedPayload(), advanced.encryptedPayload()),
        "stealing a version must not change the ciphertext");

    assertTrue(
        store.rejectUsed(currentSnapshot, "provider rejected the access token"),
        "an unchanged ciphertext must still match even when the version has advanced");
    PluginCredentialRow rejected = repository.getDirect(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.REAUTH_REQUIRED, rejected.status());
    assertNull(rejected.refreshLeaseToken());
  }

  /**
   * 关闭断连重建 ABA：植入 A -> 快照 A（version 0）-> 删除 -> 植入 B（version 0）；A 的调用期拒绝不能作废 B，B 的状态、 载荷与代际都保持不变。
   */
  @Test
  void rejectUsedCannotInvalidateARecreatedCredentialAtTheSameVersion() {
    seedInteractive(GENERATION, CONTEXT, 3600, 1800, PAYLOAD);
    PluginCredentialSnapshot snapshotA = resolveCurrent();
    assertEquals(0L, snapshotA.version());

    repository.delete(PLUGIN_ID, GENERATION, CONTEXT, 0L);
    publishManifest(PLUGIN_ID, OTHER_GENERATION, CONTEXT);
    repository.setDirect(
        repositoryRow(
            PLUGIN_ID,
            OTHER_GENERATION,
            CONTEXT,
            "{\"token\":\"recreated\"}",
            REGION,
            now.plusSeconds(7200),
            now.plusSeconds(3600)));

    PluginCredentialRow rowB = repository.getDirect(PLUGIN_ID);
    assertEquals(0L, rowB.version(), "recreate restarts the row version at zero");
    assertNotEquals(
        snapshotA.authGeneration(),
        rowB.authGeneration(),
        "a recreate must advance the auth generation even at the same version");

    assertFalse(
        store.rejectUsed(snapshotA, "stale credential rejected"),
        "the old generation's rejection must not invalidate the recreated row");

    PluginCredentialRow untouchedB = repository.getDirect(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.CONNECTED, untouchedB.status());
    assertEquals(0L, untouchedB.version());
    assertEquals(rowB.authGeneration(), untouchedB.authGeneration());
    assertTrue(Arrays.equals(rowB.encryptedPayload(), untouchedB.encryptedPayload()));

    PluginCredentialSnapshot snapshotB = resolveCurrent();
    assertEquals("{\"token\":\"recreated\"}", snapshotB.payloadJson());
    assertEquals(rowB.authGeneration(), snapshotB.authGeneration());
  }

  /** 同一 pluginId 每次写入新代际且行的 version 与载荷一起更新：读取必须命中最新代际。 */
  @Test
  void latestGenerationWinsForTheBoundResolve() {
    seedInteractive(GENERATION, CONTEXT, 3600, 1800, PAYLOAD);
    UUID firstGeneration = repository.getDirect(PLUGIN_ID).authGeneration();

    seedInteractive(OTHER_GENERATION, CONTEXT, 3600, 1800, PAYLOAD);
    publishManifest(PLUGIN_ID, OTHER_GENERATION, CONTEXT);
    UUID secondGeneration = repository.getDirect(PLUGIN_ID).authGeneration();

    assertNotEquals(firstGeneration, secondGeneration);
    assertEquals(secondGeneration, resolveCurrent().authGeneration());
    assertEquals(1L, repository.getDirect(PLUGIN_ID).version());
  }

  // --- 内存权威仓储的植入辅助 ---

  private byte[] keyBytes() {
    return "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8);
  }

  private SecretKey key() {
    return new SecretKeySpec(keyBytes(), "AES");
  }

  /**
   * 植入一行交互凭据（region/expiresAt/nextRefreshAt 同时存在），返回落库后的行。已有行时走整体替换并推进 version，与提交服务的写入形态一致 （首次
   * insert，之后 replace）。
   */
  private PluginCredentialRow seedInteractive(
      UUID generation,
      String context,
      long expiresInSeconds,
      long refreshInSeconds,
      String payloadJson) {
    PluginCredentialRow next =
        repositoryRow(
            PLUGIN_ID,
            generation,
            context,
            payloadJson,
            REGION,
            now.plusSeconds(expiresInSeconds),
            now.plusSeconds(refreshInSeconds));
    PluginCredentialRow existing = repository.getDirect(PLUGIN_ID);
    if (existing == null) {
      repository.insert(next);
    } else {
      repository.replace(
          next, existing.authGeneration(), existing.contextFingerprint(), existing.version());
    }
    return repository.getDirect(PLUGIN_ID);
  }

  private PluginCredentialRow repositoryRow(
      String pluginId,
      UUID generation,
      String context,
      String payloadJson,
      String region,
      Instant expiresAt,
      Instant nextRefreshAt) {
    return new PluginCredentialRow(
        pluginId,
        generation,
        context,
        codec.encrypt(key(), pluginId, generation, context, region, payloadJson),
        region,
        expiresAt,
        nextRefreshAt,
        PluginCredentialStatus.CONNECTED,
        null,
        null,
        null,
        null,
        0L,
        now,
        now);
  }

  private PluginCredentialRow withPayload(PluginCredentialRow row, byte[] payload) {
    return new PluginCredentialRow(
        row.pluginId(),
        row.authGeneration(),
        row.contextFingerprint(),
        payload,
        row.region(),
        row.expiresAt(),
        row.nextRefreshAt(),
        row.status(),
        row.lastRefreshedAt(),
        row.lastRefreshError(),
        row.refreshLeaseToken(),
        row.refreshLeaseUntil(),
        row.version(),
        row.createTime(),
        row.updateTime());
  }

  private PluginCredentialRow withStatus(
      PluginCredentialRow row, PluginCredentialStatus status, String error) {
    return new PluginCredentialRow(
        row.pluginId(),
        row.authGeneration(),
        row.contextFingerprint(),
        row.encryptedPayload(),
        row.region(),
        row.expiresAt(),
        row.nextRefreshAt(),
        status,
        row.lastRefreshedAt(),
        error,
        null,
        null,
        row.version(),
        row.createTime(),
        row.updateTime());
  }

  private PluginCredentialRow withLease(PluginCredentialRow row, String token, Instant until) {
    return new PluginCredentialRow(
        row.pluginId(),
        row.authGeneration(),
        row.contextFingerprint(),
        row.encryptedPayload(),
        row.region(),
        row.expiresAt(),
        row.nextRefreshAt(),
        row.status(),
        row.lastRefreshedAt(),
        row.lastRefreshError(),
        token,
        until,
        row.version(),
        row.createTime(),
        row.updateTime());
  }

  /** 权威 manifest 中 PLUGIN_ID 当前的冻结引用。 */
  private PluginCredentialReference currentReference() {
    PluginCatalogManifest.Entry entry = catalog.readCatalog().manifest().plugins().get(PLUGIN_ID);
    return new PluginCredentialReference(
        PLUGIN_ID, entry.authGeneration(), entry.contextFingerprint());
  }

  /** 按当前权威坐标做绑定解析（唯一入口）。 */
  private PluginCredentialSnapshot resolveCurrent() {
    return store.resolve(currentReference());
  }

  private void publishManifest(String pluginId, UUID generation, String context) {
    catalog.catalog =
        new PluginCatalogRecord(
            1L,
            new PluginCatalogManifest(
                Map.of(
                    pluginId,
                    new PluginCatalogManifest.Entry(
                        1L, "1.0", "a".repeat(64), "b".repeat(64), generation, context)),
                "c".repeat(64)));
  }

  private static int indexOf(byte[] source, byte[] target) {
    if (target.length == 0) {
      return 0;
    }
    outer:
    for (int i = 0; i <= source.length - target.length; i++) {
      for (int j = 0; j < target.length; j++) {
        if (source[i + j] != target[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  /** 测试用权威 catalog 替身：只服务 scoped resolve 的引用核对，可注入缺失与读取失败。 */
  private static final class TestCatalogRepository implements PluginConfigurationRepository {

    private PluginCatalogRecord catalog;
    private RuntimeException readFailure;

    @Override
    public PluginCatalogRecord readCatalog() {
      if (readFailure != null) {
        throw readFailure;
      }
      return catalog;
    }

    @Override
    public Optional<PluginCatalogRecord> readCatalog(long epoch) {
      throw new UnsupportedOperationException("the credential store must never read the catalog");
    }

    @Override
    public Optional<PluginConfigurationRow> readConfiguration(String pluginId, long revision) {
      return Optional.empty();
    }

    @Override
    public boolean commit(
        long expectedEpoch,
        List<PluginConfigurationRow> changedConfigurations,
        PluginCatalogManifest manifest) {
      throw new UnsupportedOperationException("the credential store must never write the catalog");
    }
  }
}
