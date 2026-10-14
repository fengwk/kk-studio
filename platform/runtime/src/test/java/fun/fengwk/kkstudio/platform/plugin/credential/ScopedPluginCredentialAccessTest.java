package fun.fengwk.kkstudio.platform.plugin.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableReason;
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
 * {@link ScopedPluginCredentialAccess} 的作用域契约测试：resolve 只解析绑定 pluginId，rejectUsed 先拒绝跨插件快照再委托权威
 * store，跨插件拒绝绝不写入。
 */
class ScopedPluginCredentialAccessTest {

  private static final String PLUGIN_A = "scoped.plugin-a";
  private static final String PLUGIN_B = "scoped.plugin-b";
  private static final String REGION = "CN";

  @TempDir Path tempDir;

  private final Instant now = Instant.parse("2026-10-01T00:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

  private DatabasePluginCredentialStore store;

  @BeforeEach
  void setUp() throws IOException {
    InMemoryPluginCredentialRepository repository = new InMemoryPluginCredentialRepository();
    PluginCredentialCodec codec = new PluginCredentialCodec();
    Path keyFile = tempDir.resolve("scoped-test.key");
    Files.write(keyFile, "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8));
    Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
    PluginCredentialKeyLoader keyLoader =
        new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString());
    store = new DatabasePluginCredentialStore(repository, codec, keyLoader, clock);
  }

  /** resolve() 只解析绑定的 pluginId，不暴露 store 也不接受任意 id。 */
  @Test
  void resolveReturnsBoundPluginCredential() {
    store.save(PLUGIN_A, material("{\"token\":\"a\"}"));

    ScopedPluginCredentialAccess access = new ScopedPluginCredentialAccess(PLUGIN_A, store);
    PluginCredentialSnapshot snapshot = access.resolve();

    assertEquals(PLUGIN_A, snapshot.pluginId());
    assertEquals("{\"token\":\"a\"}", snapshot.payloadJson());
  }

  /** 无凭据时 resolve() 延续 store 的 fail-closed 语义。 */
  @Test
  void resolveFailsClosedWhenNotConnected() {
    ScopedPluginCredentialAccess access = new ScopedPluginCredentialAccess(PLUGIN_A, store);

    PluginCredentialUnavailableException error =
        assertThrows(PluginCredentialUnavailableException.class, access::resolve);
    assertEquals(PluginCredentialUnavailableReason.NOT_CONNECTED, error.reason());
  }

  /** 跨插件 rejectUsed 直接拒绝，且绝不把其它插件的凭据置为 REAUTH_REQUIRED。 */
  @Test
  void rejectUsedRejectsForeignSnapshotWithoutWriting() {
    store.save(PLUGIN_A, material("{\"token\":\"a\"}"));
    store.save(PLUGIN_B, material("{\"token\":\"b\"}"));
    PluginCredentialSnapshot snapshotA = store.resolve(PLUGIN_A);

    ScopedPluginCredentialAccess accessB = new ScopedPluginCredentialAccess(PLUGIN_B, store);
    assertThrows(
        IllegalArgumentException.class, () -> accessB.rejectUsed(snapshotA, "provider rejected"));

    assertEquals(
        PluginCredentialStatus.CONNECTED,
        store.projection(PLUGIN_A).status(),
        "foreign snapshot must not be invalidated");
  }

  /** 本插件快照的 rejectUsed 委托权威 store 并把本插件凭据置为 REAUTH_REQUIRED。 */
  @Test
  void rejectUsedInvalidatesOwnCredential() {
    store.save(PLUGIN_A, material("{\"token\":\"a\"}"));
    ScopedPluginCredentialAccess access = new ScopedPluginCredentialAccess(PLUGIN_A, store);
    PluginCredentialSnapshot snapshot = access.resolve();

    assertTrue(access.rejectUsed(snapshot, "provider rejected"));
    assertEquals(PluginCredentialStatus.REAUTH_REQUIRED, store.projection(PLUGIN_A).status());
  }

  private PluginCredentialMaterial material(String payloadJson) {
    return new PluginCredentialMaterial(
        REGION, now.plusSeconds(3600), now.plusSeconds(1800), payloadJson);
  }
}
