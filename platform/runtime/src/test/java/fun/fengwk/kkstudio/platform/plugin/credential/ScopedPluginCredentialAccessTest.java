package fun.fengwk.kkstudio.platform.plugin.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ScopedPluginCredentialAccess} 的作用域契约测试：resolve 只解析绑定的 pluginId 与其冻结 {@link
 * PluginCredentialReference}，rejectUsed 先拒绝跨插件或跨引用的快照再委托权威 store，跨插件/跨引用拒绝绝不写入。
 */
class ScopedPluginCredentialAccessTest {

  private static final String PLUGIN_A = "scoped.plugin-a";
  private static final String PLUGIN_B = "scoped.plugin-b";
  private static final String REGION = "CN";
  private static final UUID GENERATION_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID GENERATION_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String CONTEXT_A = "a1".repeat(32);
  private static final String CONTEXT_B = "b2".repeat(32);

  @TempDir Path tempDir;

  private final Instant now = Instant.parse("2026-10-01T00:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  private final PluginCredentialCodec codec = new PluginCredentialCodec();

  private InMemoryPluginCredentialRepository repository;
  private TestCatalogRepository catalog;
  private DatabasePluginCredentialStore store;

  @BeforeEach
  void setUp() throws IOException {
    repository = new InMemoryPluginCredentialRepository();
    catalog = new TestCatalogRepository();
    Path keyFile = tempDir.resolve("scoped-test.key");
    Files.write(keyFile, "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8));
    Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
    PluginCredentialKeyLoader keyLoader =
        new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString());
    store = new DatabasePluginCredentialStore(repository, catalog, codec, keyLoader, clock);
  }

  /** resolve() 只解析绑定的 pluginId 与冻结引用，不暴露 store 也不接受任意 id。 */
  @Test
  void resolveReturnsBoundPluginCredential() {
    seed(PLUGIN_A, GENERATION_A, CONTEXT_A, "{\"token\":\"a\"}");
    publish(PLUGIN_A, GENERATION_A, CONTEXT_A);

    ScopedPluginCredentialAccess access =
        new ScopedPluginCredentialAccess(
            PLUGIN_A, new PluginCredentialReference(PLUGIN_A, GENERATION_A, CONTEXT_A), store);
    PluginCredentialSnapshot snapshot = access.resolve();

    assertEquals(PLUGIN_A, snapshot.pluginId());
    assertEquals("{\"token\":\"a\"}", snapshot.payloadJson());
    assertEquals(GENERATION_A, snapshot.authGeneration());
  }

  /** 无凭据时 resolve() 延续 store 的 fail-closed 语义。 */
  @Test
  void resolveFailsClosedWhenNotConnected() {
    publish(PLUGIN_A, GENERATION_A, CONTEXT_A);
    ScopedPluginCredentialAccess access =
        new ScopedPluginCredentialAccess(
            PLUGIN_A, new PluginCredentialReference(PLUGIN_A, GENERATION_A, CONTEXT_A), store);

    PluginCredentialUnavailableException error =
        assertThrows(PluginCredentialUnavailableException.class, access::resolve);
    assertEquals(PluginCredentialUnavailableReason.NOT_CONNECTED, error.reason());
  }

  /** 引用与 pluginId 不一致（构造期就非法）必须直接拒绝，绝不留下能读别的插件的视图。 */
  @Test
  void rejectsReferenceThatBelongsToAnotherPlugin() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ScopedPluginCredentialAccess(
                PLUGIN_A, new PluginCredentialReference(PLUGIN_B, GENERATION_B, CONTEXT_B), store));
  }

  /** 权威 manifest 已前进（重新登录或配置目标变化）时，旧引用必须 fail closed。 */
  @Test
  void resolveFailsClosedWhenTheAuthorityHasMovedOn() {
    seed(PLUGIN_A, GENERATION_A, CONTEXT_A, "{\"token\":\"a\"}");
    publish(PLUGIN_A, GENERATION_B, CONTEXT_B);

    ScopedPluginCredentialAccess access =
        new ScopedPluginCredentialAccess(
            PLUGIN_A, new PluginCredentialReference(PLUGIN_A, GENERATION_A, CONTEXT_A), store);

    PluginCredentialUnavailableException error =
        assertThrows(PluginCredentialUnavailableException.class, access::resolve);
    assertEquals(PluginCredentialUnavailableReason.NOT_CONNECTED, error.reason());
    assertTrue(!error.getMessage().contains("token"));
  }

  /** 跨插件 rejectUsed 直接拒绝，且绝不把其它插件的凭据置为 REAUTH_REQUIRED。 */
  @Test
  void rejectUsedRejectsForeignSnapshotWithoutWriting() {
    seed(PLUGIN_A, GENERATION_A, CONTEXT_A, "{\"token\":\"a\"}");
    seed(PLUGIN_B, GENERATION_B, CONTEXT_B, "{\"token\":\"b\"}");
    publish(PLUGIN_A, GENERATION_A, CONTEXT_A);
    publish(PLUGIN_B, GENERATION_B, CONTEXT_B);
    PluginCredentialSnapshot snapshotA =
        store.resolve(new PluginCredentialReference(PLUGIN_A, GENERATION_A, CONTEXT_A));

    ScopedPluginCredentialAccess accessB =
        new ScopedPluginCredentialAccess(
            PLUGIN_B, new PluginCredentialReference(PLUGIN_B, GENERATION_B, CONTEXT_B), store);
    assertThrows(
        IllegalArgumentException.class, () -> accessB.rejectUsed(snapshotA, "provider rejected"));

    assertEquals(
        PluginCredentialStatus.CONNECTED,
        store.projection(PLUGIN_A).status(),
        "foreign snapshot must not be invalidated");
  }

  /** 同插件但引用不同（旧代际快照）的 rejectUsed 也必须直接拒绝，不能作废当前凭据。 */
  @Test
  void rejectUsedRejectsSnapshotOfAnotherReferenceOfTheSamePlugin() {
    seed(PLUGIN_A, GENERATION_A, CONTEXT_A, "{\"token\":\"a\"}");
    publish(PLUGIN_A, GENERATION_A, CONTEXT_A);
    PluginCredentialSnapshot snapshot =
        store.resolve(new PluginCredentialReference(PLUGIN_A, GENERATION_A, CONTEXT_A));

    ScopedPluginCredentialAccess staleAccess =
        new ScopedPluginCredentialAccess(
            PLUGIN_A, new PluginCredentialReference(PLUGIN_A, GENERATION_B, CONTEXT_A), store);
    assertThrows(
        IllegalArgumentException.class,
        () -> staleAccess.rejectUsed(snapshot, "provider rejected"));

    assertEquals(PluginCredentialStatus.CONNECTED, store.projection(PLUGIN_A).status());
  }

  /** 本插件本引用的快照才委托权威 store，并把凭据置为 REAUTH_REQUIRED。 */
  @Test
  void rejectUsedInvalidatesOwnCredential() {
    seed(PLUGIN_A, GENERATION_A, CONTEXT_A, "{\"token\":\"a\"}");
    publish(PLUGIN_A, GENERATION_A, CONTEXT_A);
    ScopedPluginCredentialAccess access =
        new ScopedPluginCredentialAccess(
            PLUGIN_A, new PluginCredentialReference(PLUGIN_A, GENERATION_A, CONTEXT_A), store);
    PluginCredentialSnapshot snapshot = access.resolve();

    assertTrue(access.rejectUsed(snapshot, "provider rejected"));
    assertEquals(PluginCredentialStatus.REAUTH_REQUIRED, store.projection(PLUGIN_A).status());
  }

  private void seed(String pluginId, UUID generation, String context, String payloadJson) {
    repository.setDirect(
        new PluginCredentialRow(
            pluginId,
            generation,
            context,
            codec.encrypt(key(), pluginId, generation, context, REGION, payloadJson),
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
  }

  private static SecretKey key() {
    return new SecretKeySpec(
        "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8), "AES");
  }

  private void publish(String pluginId, UUID generation, String context) {
    Map<String, PluginCatalogManifest.Entry> plugins = new LinkedHashMap<>(catalog.plugins());
    plugins.put(
        pluginId,
        new PluginCatalogManifest.Entry(
            1L, "1.0", "a".repeat(64), "b".repeat(64), generation, context));
    catalog.catalog =
        new PluginCatalogRecord(1L, new PluginCatalogManifest(plugins, "c".repeat(64)));
  }

  /** 测试用权威 catalog 替身：只服务 scoped resolve 的引用核对。 */
  private static final class TestCatalogRepository implements PluginConfigurationRepository {

    private PluginCatalogRecord catalog =
        new PluginCatalogRecord(0L, PluginCatalogManifest.uninitialized());

    Map<String, PluginCatalogManifest.Entry> plugins() {
      return catalog.manifest().plugins();
    }

    @Override
    public PluginCatalogRecord readCatalog() {
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
