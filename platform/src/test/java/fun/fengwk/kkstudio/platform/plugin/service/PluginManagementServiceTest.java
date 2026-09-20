package fun.fengwk.kkstudio.platform.plugin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthHandler;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.credential.DatabasePluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialCodec;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialKeyLoader;
import fun.fengwk.kkstudio.platform.plugin.testing.InMemoryPluginCredentialRepository;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthKindDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthPrepareDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginDTO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Plugin 管理面用例契约测试。
 *
 * <p>测试覆盖 listPlugins、getPlugin、prepareAuth、completeAuth 与 disconnect 等管理面操作， 严格验证未安装 Plugin 抛出
 * 404、参数校验防错、白名单投影不泄漏敏感明文以及认证回调去敏安全。
 */
class PluginManagementServiceTest {

  @TempDir Path tempDir;

  private static final String PLUGIN_ID = "managed-plugin";
  private static final String NO_AUTH_PLUGIN_ID = "no-auth-plugin";
  private static final String REGION_CN = "CN";
  private static final String REGION_US = "US";
  private static final String SECRET_TOKEN_MARKER = "very-secret-callback-token-marker-654321";

  private final Instant now = Instant.parse("2026-09-21T14:00:00Z");
  private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

  private InMemoryPluginCredentialRepository repository;
  private DatabasePluginCredentialStore credentialStore;
  private StudioPluginRegistry registry;
  private PluginManagementService service;
  private TestAuthPlugin authPlugin;
  private TestNoAuthPlugin noAuthPlugin;

  @BeforeEach
  void setUp() throws IOException {
    repository = new InMemoryPluginCredentialRepository();
    PluginCredentialCodec codec = new PluginCredentialCodec();

    Path keyFile = tempDir.resolve("mgmt-test.key");
    Files.write(keyFile, "12345678901234567890123456789012".getBytes(StandardCharsets.UTF_8));
    Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
    PluginCredentialKeyLoader keyLoader =
        new PluginCredentialKeyLoader(keyFile.toAbsolutePath().toString());

    credentialStore = new DatabasePluginCredentialStore(repository, codec, keyLoader, clock);

    authPlugin = new TestAuthPlugin();
    noAuthPlugin = new TestNoAuthPlugin();

    registry = new StudioPluginRegistry(List.of(authPlugin, noAuthPlugin));
    service = new PluginManagementService(registry, credentialStore);
  }

  /** listPlugins() 顺序与 registry 一致；每个 PluginDTO 绝不泄漏敏感密钥或回调参数（通过与 toString 及各字段校验）。 */
  @Test
  void listPluginsOrderMatchesRegistryAndExcludesSecretMarkers() {
    // 写入一条含有敏感 token 的凭据
    credentialStore.save(
        PLUGIN_ID,
        new PluginCredentialMaterial(
            REGION_CN,
            now.plusSeconds(3600),
            now.plusSeconds(1800),
            "{\"token\":\"" + SECRET_TOKEN_MARKER + "\"}"));

    List<PluginDTO> dtos = service.listPlugins();
    assertEquals(2, dtos.size());

    // 顺序与 registry.plugins() 一致
    List<String> expectedIds =
        registry.plugins().stream().map(p -> p.descriptor().pluginId()).toList();
    assertEquals(
        expectedIds,
        dtos.stream().map(PluginDTO::getPluginId).toList(),
        "listPlugins() order must match registry.plugins()");

    // 检查每个 DTO 的所有字段及其 toString()，确保绝对不含明文秘密标记
    for (PluginDTO dto : dtos) {
      assertFalse(
          dto.toString().contains(SECRET_TOKEN_MARKER), "DTO toString() must not leak secret");
      if (dto.getPluginId().equals(PLUGIN_ID)) {
        assertEquals(PluginCredentialStatus.CONNECTED.name(), dto.getStatus());
        assertEquals(REGION_CN, dto.getRegion());
        assertNotNull(dto.getExpiresAt());
      }
    }
  }

  /** authKind 映射：提供认证能力的 Plugin 映射为 DeepLink 并包含 regions 候选；无认证能力的 Plugin 为 null。 */
  @Test
  void authKindMappingMatchesCapability() {
    PluginDTO authDTO = service.getPlugin(PLUGIN_ID);
    assertInstanceOf(PluginAuthKindDTO.DeepLink.class, authDTO.getAuthKind());
    PluginAuthKindDTO.DeepLink deepLink = (PluginAuthKindDTO.DeepLink) authDTO.getAuthKind();
    assertEquals(List.of(REGION_CN, REGION_US), deepLink.regionCandidates());

    PluginDTO noAuthDTO = service.getPlugin(NO_AUTH_PLUGIN_ID);
    assertNull(noAuthDTO.getAuthKind(), "Plugin without auth capability must have null authKind");
  }

  /** getPlugin() 查询未安装 Plugin 时抛出 AiResourceNotFoundException。 */
  @Test
  void getPluginThrowsNotFoundForUninstalledPlugin() {
    assertThrows(AiResourceNotFoundException.class, () -> service.getPlugin("uninstalled-plugin"));
  }

  /**
   * prepareAuth() 校验：未知 region、无 authHandler、loginUrl 为空/空白均抛出 AiValidationException； 合法输入返回包含
   * loginUrl 的 DTO。
   */
  @Test
  void prepareAuthValidationAndSuccess() {
    // 1. 未安装 plugin
    assertThrows(
        AiResourceNotFoundException.class, () -> service.prepareAuth("unknown", REGION_CN));

    // 2. 无 authHandler
    assertThrows(
        AiValidationException.class, () -> service.prepareAuth(NO_AUTH_PLUGIN_ID, REGION_CN));

    // 3. 未知 region
    assertThrows(
        AiValidationException.class, () -> service.prepareAuth(PLUGIN_ID, "UNKNOWN_REGION"));

    // 4. handler 返回空白 loginUrl
    authPlugin.setLoginUrlSupplier(region -> "   ");
    assertThrows(AiValidationException.class, () -> service.prepareAuth(PLUGIN_ID, REGION_CN));

    // 5. handler 抛出异常
    authPlugin.setLoginUrlSupplier(
        region -> {
          throw new RuntimeException("upstream network failure");
        });
    assertThrows(AiValidationException.class, () -> service.prepareAuth(PLUGIN_ID, REGION_CN));

    // 6. 合法返回
    authPlugin.setLoginUrlSupplier(region -> "https://auth.example.com/login?region=" + region);
    PluginAuthPrepareDTO prepareDTO = service.prepareAuth(PLUGIN_ID, REGION_CN);
    assertNotNull(prepareDTO);
    assertEquals("https://auth.example.com/login?region=CN", prepareDTO.getLoginUrl());
  }

  /**
   * completeAuth() 校验与去敏：空白 callbackUrl 抛异常；handler 抛异常时异常消息与 toString 绝不泄漏回调原文； handler 返回非法
   * region 时不写 store；合法完成时写 store 并返回更新后的投影。
   */
  @Test
  void completeAuthValidationAndSanitization() {
    // 1. callbackUrl 为 null 或空白
    assertThrows(AiValidationException.class, () -> service.completeAuth(PLUGIN_ID, null));
    assertThrows(AiValidationException.class, () -> service.completeAuth(PLUGIN_ID, "   "));

    // 2. handler 抛异常：断言异常消息与 toString() 不包含回调原文中的敏感参数
    String sensitiveCallback =
        "https://example.com/callback?code=abc&secret_token=" + SECRET_TOKEN_MARKER;
    authPlugin.setCompleteHandler(
        url -> {
          throw new RuntimeException("failed callback parsing");
        });

    AiValidationException ex =
        assertThrows(
            AiValidationException.class, () -> service.completeAuth(PLUGIN_ID, sensitiveCallback));

    assertFalse(
        ex.getMessage().contains(SECRET_TOKEN_MARKER),
        "error message must not contain callback token");
    assertFalse(
        ex.toString().contains(SECRET_TOKEN_MARKER),
        "error toString() must not contain callback token");

    // 3. handler 返回 region 不在 descriptor 声明内（如 "FR"）
    authPlugin.setCompleteHandler(
        url ->
            new PluginCredentialMaterial(
                "FR", now.plusSeconds(3600), now.plusSeconds(1800), "{\"token\":\"valid\"}"));

    assertThrows(
        AiValidationException.class,
        () -> service.completeAuth(PLUGIN_ID, "https://callback.com/ok"));
    assertEquals(
        PluginCredentialStatus.NOT_CONNECTED,
        credentialStore.projection(PLUGIN_ID).status(),
        "store must not be written when region is invalid");

    // 4. 合法完成认证
    authPlugin.setCompleteHandler(
        url ->
            new PluginCredentialMaterial(
                REGION_CN,
                now.plusSeconds(3600),
                now.plusSeconds(1800),
                "{\"token\":\"valid-complete-token\"}"));

    PluginDTO result = service.completeAuth(PLUGIN_ID, "https://callback.com/valid");
    assertEquals(PluginCredentialStatus.CONNECTED.name(), result.getStatus());
    assertEquals(REGION_CN, result.getRegion());
    assertEquals(PluginCredentialStatus.CONNECTED, credentialStore.projection(PLUGIN_ID).status());
  }

  /** disconnect() 契约：已连接行删除且幂等（连续调用不报错）；未安装 pluginId 抛出 AiResourceNotFoundException。 */
  @Test
  void disconnectIdempotencyAndNotFound() {
    // 先连接
    credentialStore.save(
        PLUGIN_ID,
        new PluginCredentialMaterial(
            REGION_CN, now.plusSeconds(3600), now.plusSeconds(1800), "{\"token\":\"abc\"}"));
    assertEquals(PluginCredentialStatus.CONNECTED, credentialStore.projection(PLUGIN_ID).status());

    // 首次 disconnect
    service.disconnect(PLUGIN_ID);
    assertEquals(
        PluginCredentialStatus.NOT_CONNECTED, credentialStore.projection(PLUGIN_ID).status());

    // 幂等：再次 disconnect 依然成功
    service.disconnect(PLUGIN_ID);
    assertEquals(
        PluginCredentialStatus.NOT_CONNECTED, credentialStore.projection(PLUGIN_ID).status());

    // 未安装 pluginId 抛 404
    assertThrows(AiResourceNotFoundException.class, () -> service.disconnect("uninstalled-plugin"));
  }

  // --- 测试辅助桩对象 ---

  private static class TestAuthPlugin implements StudioPlugin {
    private final PluginDescriptor descriptor =
        new PluginDescriptor(PLUGIN_ID, "Auth Plugin", "1.0", List.of(REGION_CN, REGION_US));

    private Function<String, String> loginUrlSupplier =
        region -> "https://login.example.com/" + region;

    private Function<String, PluginCredentialMaterial> completeHandler = url -> null;

    void setLoginUrlSupplier(Function<String, String> loginUrlSupplier) {
      this.loginUrlSupplier = loginUrlSupplier;
    }

    void setCompleteHandler(Function<String, PluginCredentialMaterial> completeHandler) {
      this.completeHandler = completeHandler;
    }

    @Override
    public PluginDescriptor descriptor() {
      return descriptor;
    }

    @Override
    public Optional<PluginAuthHandler> authHandler() {
      return Optional.of(
          new PluginAuthHandler() {
            @Override
            public String loginUrl(String region) {
              return loginUrlSupplier.apply(region);
            }

            @Override
            public PluginCredentialMaterial complete(String callbackUrl) {
              return completeHandler.apply(callbackUrl);
            }
          });
    }
  }

  private static class TestNoAuthPlugin implements StudioPlugin {
    private final PluginDescriptor descriptor =
        new PluginDescriptor(NO_AUTH_PLUGIN_ID, "No Auth Plugin", "1.0", List.of());

    @Override
    public PluginDescriptor descriptor() {
      return descriptor;
    }
  }
}
