package fun.fengwk.kkstudio.platform.plugin.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
import fun.fengwk.kkstudio.platform.plugin.PluginKeyUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.plugin.testing.InMemoryPluginCredentialRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 数据库凭据 Store 契约与状态矩阵测试。
 *
 * <p>验证 save / resolve / projection / delete 的行为矩阵，确保密钥缺失或密文损坏时 fail-closed、 错误消息不泄露敏感载荷、已过期的 lease
 * 不误判为 refreshing、REFRESH_FAILED 在有效期内仍可读取旧凭据。
 */
class DatabasePluginCredentialStoreTest {

  @TempDir Path tempDir;

  private static final String PLUGIN_ID = "test-plugin";
  private static final String REGION = "CN";
  private static final String SECRET_TOKEN = "super-secret-token-marker-98765";
  private static final String PAYLOAD = "{\"token\":\"" + SECRET_TOKEN + "\"}";

  private final Instant now = Instant.parse("2026-09-21T10:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  private final PluginCredentialCodec codec = new PluginCredentialCodec();

  private InMemoryPluginCredentialRepository repository;
  private PluginCredentialKeyLoader keyLoader;
  private DatabasePluginCredentialStore store;
  private DatabasePluginCredentialStore storeWithoutKey;

  @BeforeEach
  void setUp() throws IOException {
    repository = new InMemoryPluginCredentialRepository();

    Path keyFile = tempDir.resolve("valid-master.key");
    byte[] keyBytes = "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8);
    Files.write(keyFile, keyBytes);
    Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));

    keyLoader = new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString());
    store = new DatabasePluginCredentialStore(repository, codec, keyLoader, clock);

    PluginCredentialKeyLoader missingKeyLoader =
        new PluginCredentialKeyLoader(
            tempDir.resolve("non-existent.key").toAbsolutePath().toString());
    storeWithoutKey = new DatabasePluginCredentialStore(repository, codec, missingKeyLoader, clock);
  }

  private PluginCredentialMaterial validMaterial() {
    return new PluginCredentialMaterial(
        REGION,
        now.plusSeconds(3600), // expiresAt: now + 1h
        now.plusSeconds(1800), // nextRefreshAt: now + 30m
        PAYLOAD);
  }

  /** save() 写入行：status=CONNECTED、lease 为空、version=0，密文中不含明文敏感标记，返回的投影只含状态与时间。 */
  @Test
  void saveWritesConnectedRowWithRedactedEncryptedPayloadAndCleanLease() {
    PluginCredentialMaterial material = validMaterial();
    PluginCredentialProjection projection = store.save(PLUGIN_ID, material);

    assertEquals(PluginCredentialStatus.CONNECTED, projection.status());
    assertEquals(REGION, projection.region());
    assertEquals(material.expiresAt(), projection.expiresAt());
    assertEquals(material.nextRefreshAt(), projection.nextRefreshAt());
    assertNull(projection.lastRefreshedAt());
    assertNull(projection.lastRefreshError());

    PluginCredentialRow row = repository.getDirect(PLUGIN_ID);
    assertNotNull(row, "row must be saved to repository");
    assertEquals(PluginCredentialStatus.CONNECTED, row.status());
    assertNull(row.refreshLeaseToken());
    assertNull(row.refreshLeaseUntil());
    assertEquals(0L, row.version());

    byte[] plainBytes = SECRET_TOKEN.getBytes(StandardCharsets.UTF_8);
    assertFalse(
        indexOf(row.encryptedPayload(), plainBytes) >= 0,
        "encrypted_payload must not contain plaintext secret token");
  }

  /** save() 拒绝非法入参：expiresAt 不晚于 now、nextRefreshAt 早于 now、material 为 null。 */
  @Test
  void saveRejectsInvalidParameters() {
    // expiresAt 等于当前时刻或早于当前时刻
    PluginCredentialMaterial expiredNow =
        new PluginCredentialMaterial(REGION, now, now.plusSeconds(60), PAYLOAD);
    assertThrows(IllegalArgumentException.class, () -> store.save(PLUGIN_ID, expiredNow));

    PluginCredentialMaterial expiredPast =
        new PluginCredentialMaterial(REGION, now.minusSeconds(1), now.plusSeconds(60), PAYLOAD);
    assertThrows(IllegalArgumentException.class, () -> store.save(PLUGIN_ID, expiredPast));

    // nextRefreshAt 早于当前时刻
    PluginCredentialMaterial pastRefresh =
        new PluginCredentialMaterial(REGION, now.plusSeconds(3600), now.minusSeconds(1), PAYLOAD);
    assertThrows(IllegalArgumentException.class, () -> store.save(PLUGIN_ID, pastRefresh));

    // material 为 null
    assertThrows(NullPointerException.class, () -> store.save(PLUGIN_ID, null));
  }

  /** 主密钥不可用时 save() 抛出 PluginKeyUnavailableException，且不得写入 repository。 */
  @Test
  void saveRejectsWhenKeyIsUnavailableAndDoesNotWriteToRepository() {
    PluginCredentialMaterial material = validMaterial();
    assertThrows(
        PluginKeyUnavailableException.class, () -> storeWithoutKey.save(PLUGIN_ID, material));

    assertNull(repository.getDirect(PLUGIN_ID), "repository must remain unmodified on key failure");
  }

  /** resolve() 状态矩阵与不泄露明文 payload 断言： 无行、无密钥、密文篡改、REAUTH_REQUIRED、REFRESH_UNCERTAIN、已过期。 */
  @Test
  void resolveStatusMatrixEnforcesSecurityAndCorrectReasons() {
    // 1. 无行 -> NOT_CONNECTED
    PluginCredentialUnavailableException ex1 =
        assertThrows(
            PluginCredentialUnavailableException.class, () -> store.resolve("unknown-plugin"));
    assertEquals(PluginCredentialUnavailableReason.NOT_CONNECTED, ex1.reason());
    assertFalse(ex1.getMessage().contains(SECRET_TOKEN));

    // 先落库一条合法凭据
    store.save(PLUGIN_ID, validMaterial());

    // 2. 主密钥不可用 -> KEY_UNAVAILABLE
    PluginCredentialUnavailableException ex2 =
        assertThrows(
            PluginCredentialUnavailableException.class, () -> storeWithoutKey.resolve(PLUGIN_ID));
    assertEquals(PluginCredentialUnavailableReason.KEY_UNAVAILABLE, ex2.reason());
    assertFalse(ex2.getMessage().contains(SECRET_TOKEN));

    // 3. 密文被篡改（直接改 repository 内部 payload） -> KEY_UNAVAILABLE
    PluginCredentialRow row = repository.getDirect(PLUGIN_ID);
    byte[] tampered = row.encryptedPayload();
    tampered[15] = (byte) (tampered[15] ^ 0xFF);
    repository.setDirect(
        new PluginCredentialRow(
            row.pluginId(),
            tampered,
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
            row.updateTime()));

    PluginCredentialUnavailableException ex3 =
        assertThrows(PluginCredentialUnavailableException.class, () -> store.resolve(PLUGIN_ID));
    assertEquals(PluginCredentialUnavailableReason.KEY_UNAVAILABLE, ex3.reason());
    assertFalse(ex3.getMessage().contains(SECRET_TOKEN));

    // 恢复正常密文
    store.save(PLUGIN_ID, validMaterial());
    PluginCredentialRow originalRow = repository.getDirect(PLUGIN_ID);

    // 4. REAUTH_REQUIRED -> REAUTH_REQUIRED
    repository.setDirect(
        new PluginCredentialRow(
            originalRow.pluginId(),
            originalRow.encryptedPayload(),
            originalRow.region(),
            originalRow.expiresAt(),
            originalRow.nextRefreshAt(),
            PluginCredentialStatus.REAUTH_REQUIRED,
            originalRow.lastRefreshedAt(),
            "auth rejected",
            null,
            null,
            originalRow.version(),
            originalRow.createTime(),
            originalRow.updateTime()));
    PluginCredentialUnavailableException ex4 =
        assertThrows(PluginCredentialUnavailableException.class, () -> store.resolve(PLUGIN_ID));
    assertEquals(PluginCredentialUnavailableReason.REAUTH_REQUIRED, ex4.reason());
    assertFalse(ex4.getMessage().contains(SECRET_TOKEN));

    // 5. REFRESH_UNCERTAIN -> REFRESH_UNCERTAIN
    repository.setDirect(
        new PluginCredentialRow(
            originalRow.pluginId(),
            originalRow.encryptedPayload(),
            originalRow.region(),
            originalRow.expiresAt(),
            originalRow.nextRefreshAt(),
            PluginCredentialStatus.REFRESH_UNCERTAIN,
            originalRow.lastRefreshedAt(),
            "refresh uncertain",
            null,
            null,
            originalRow.version(),
            originalRow.createTime(),
            originalRow.updateTime()));
    PluginCredentialUnavailableException ex5 =
        assertThrows(PluginCredentialUnavailableException.class, () -> store.resolve(PLUGIN_ID));
    assertEquals(PluginCredentialUnavailableReason.REFRESH_UNCERTAIN, ex5.reason());
    assertFalse(ex5.getMessage().contains(SECRET_TOKEN));

    // 6. CONNECTED 但已过期 -> EXPIRED
    repository.setDirect(
        new PluginCredentialRow(
            originalRow.pluginId(),
            originalRow.encryptedPayload(),
            originalRow.region(),
            now.minusSeconds(10), // expiresAt 已过
            originalRow.nextRefreshAt(),
            PluginCredentialStatus.CONNECTED,
            originalRow.lastRefreshedAt(),
            null,
            null,
            null,
            originalRow.version(),
            originalRow.createTime(),
            originalRow.updateTime()));
    PluginCredentialUnavailableException ex6 =
        assertThrows(PluginCredentialUnavailableException.class, () -> store.resolve(PLUGIN_ID));
    assertEquals(PluginCredentialUnavailableReason.EXPIRED, ex6.reason());
    assertFalse(ex6.getMessage().contains(SECRET_TOKEN));

    // 7. CONNECTED 在期限内 -> 成功解密并返回快照
    store.save(PLUGIN_ID, validMaterial());
    PluginCredentialSnapshot snapshot = store.resolve(PLUGIN_ID);
    assertEquals(PLUGIN_ID, snapshot.pluginId());
    assertEquals(REGION, snapshot.region());
    assertEquals(PAYLOAD, snapshot.payloadJson());

    // 8. REFRESH_FAILED 且未过期 -> 仍然返回 snapshot（可证明未发出的失败不废弃旧凭据）
    PluginCredentialRow freshRow = repository.getDirect(PLUGIN_ID);
    repository.setDirect(
        new PluginCredentialRow(
            freshRow.pluginId(),
            freshRow.encryptedPayload(),
            freshRow.region(),
            freshRow.expiresAt(),
            freshRow.nextRefreshAt(),
            PluginCredentialStatus.REFRESH_FAILED,
            freshRow.lastRefreshedAt(),
            "network temporarily unreachable",
            null,
            null,
            freshRow.version(),
            freshRow.createTime(),
            freshRow.updateTime()));
    PluginCredentialSnapshot failedSnapshot = store.resolve(PLUGIN_ID);
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
    store.save(PLUGIN_ID, validMaterial());
    PluginCredentialRow row = repository.getDirect(PLUGIN_ID);

    // active lease 在未来
    repository.setDirect(
        new PluginCredentialRow(
            row.pluginId(),
            row.encryptedPayload(),
            row.region(),
            row.expiresAt(),
            row.nextRefreshAt(),
            row.status(),
            row.lastRefreshedAt(),
            row.lastRefreshError(),
            "lease-token-123",
            now.plusSeconds(120),
            row.version(),
            row.createTime(),
            row.updateTime()));

    PluginCredentialUnavailableException activeEx =
        assertThrows(PluginCredentialUnavailableException.class, () -> store.resolve(PLUGIN_ID));
    assertEquals(PluginCredentialUnavailableReason.AUTH_REFRESHING, activeEx.reason());
    assertFalse(activeEx.getMessage().contains(SECRET_TOKEN));

    // lease 已过期（<= now）
    repository.setDirect(
        new PluginCredentialRow(
            row.pluginId(),
            row.encryptedPayload(),
            row.region(),
            row.expiresAt(),
            row.nextRefreshAt(),
            row.status(),
            row.lastRefreshedAt(),
            row.lastRefreshError(),
            "lease-token-123",
            now.minusSeconds(1),
            row.version(),
            row.createTime(),
            row.updateTime()));

    PluginCredentialSnapshot snapshot = store.resolve(PLUGIN_ID);
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
    store.save(PLUGIN_ID, validMaterial());
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
    PluginCredentialRow row = repository.getDirect(PLUGIN_ID);
    byte[] corrupted = row.encryptedPayload();
    corrupted[10] = (byte) (corrupted[10] ^ 0xAA);
    repository.setDirect(
        new PluginCredentialRow(
            row.pluginId(),
            corrupted,
            row.region(),
            row.expiresAt(),
            row.nextRefreshAt(),
            row.status(),
            row.lastRefreshedAt(),
            row.lastRefreshError(),
            null,
            null,
            row.version(),
            row.createTime(),
            row.updateTime()));

    PluginCredentialProjection corruptedProj = store.projection(PLUGIN_ID);
    assertEquals(PluginCredentialStatus.KEY_UNAVAILABLE, corruptedProj.status());
    assertNull(corruptedProj.region(), "must not leak region when ciphertext cannot be decrypted");
    assertNull(
        corruptedProj.expiresAt(), "must not leak expiresAt when ciphertext cannot be decrypted");
  }

  /** delete() 后凭据行被删除，resolve() 变为 NOT_CONNECTED，projection() 也回到 NOT_CONNECTED。 */
  @Test
  void deleteRemovesCredentialRowAndResolveReturnsNotConnected() {
    store.save(PLUGIN_ID, validMaterial());
    store.delete(PLUGIN_ID);

    PluginCredentialUnavailableException ex =
        assertThrows(PluginCredentialUnavailableException.class, () -> store.resolve(PLUGIN_ID));
    assertEquals(PluginCredentialUnavailableReason.NOT_CONNECTED, ex.reason());
    assertEquals(PluginCredentialStatus.NOT_CONNECTED, store.projection(PLUGIN_ID).status());
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
}
