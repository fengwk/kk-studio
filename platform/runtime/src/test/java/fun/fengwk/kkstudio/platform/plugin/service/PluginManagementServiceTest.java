package fun.fengwk.kkstudio.platform.plugin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fun.fengwk.kkstudio.canvas.CanvasJson;
import fun.fengwk.kkstudio.canvas.CanvasResourceKind;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionDefinition;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionReferencePolicy;
import fun.fengwk.kkstudio.harness.contributor.api.HarnessContributor;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthHandler;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheck;
import fun.fengwk.kkstudio.platform.plugin.PluginConnectionCheckResult;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogRuntime;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginAuthAttemptRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginAuthAttemptRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.plugin.testing.TestPluginFixtures;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthKindDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginAuthPrepareDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginConnectionCheckDTO;
import fun.fengwk.kkstudio.share.ai.plugin.PluginDTO;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** {@link PluginManagementService} 的安全投影、认证协议与连接检查。 */
class PluginManagementServiceTest {

  private static final String STATIC_ID = "static.plugin";
  private static final String DEEPLINK_ID = "deeplink.plugin";
  private static final String NONE_ID = "none.plugin";
  private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

  private final PluginConfigurationRepository configurations =
      mock(PluginConfigurationRepository.class);
  private final PluginCredentialRepository credentials = mock(PluginCredentialRepository.class);
  private final PluginConfigurationService configurationService =
      mock(PluginConfigurationService.class);
  private final PluginAuthAttemptRepository authAttempts = mock(PluginAuthAttemptRepository.class);
  private final PluginAuthHandler authHandler = mock(PluginAuthHandler.class);
  private final PluginConnectionCheck connectionCheck = mock(PluginConnectionCheck.class);
  private PluginCatalogRuntime runtime;
  private PluginManagementService service;

  @BeforeEach
  void setUp() {
    StudioPluginRegistry registry =
        new StudioPluginRegistry(
            List.of(new StaticKeyPlugin(), new DeepLinkPlugin(), new NonePlugin()));
    stored(STATIC_ID, 2L);
    stored(DEEPLINK_ID, 1L);
    stored(NONE_ID, 1L);
    when(credentials.find(anyString())).thenReturn(Optional.empty());
    runtime =
        new PluginCatalogRuntime(
            registry,
            configurations,
            pluginId ->
                new PluginContext(
                    mock(PluginCredentialAccess.class),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty()),
            List.of(),
            List.of());
    service =
        new PluginManagementService(
            registry,
            () -> runtime,
            configurations,
            credentials,
            configurationService,
            authAttempts,
            Duration.ofMinutes(10),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    runtime.close();
  }

  /** 投影只暴露秘密是否已录入；desired 取自数据库、applied 取自本节点快照。 */
  @Test
  void projectsInstalledPluginsWithoutSecrets() {
    when(credentials.find(STATIC_ID))
        .thenReturn(
            Optional.of(
                new PluginCredentialRow(
                    STATIC_ID,
                    "{\"apiKey\":\"secret-value\"}",
                    null,
                    null,
                    null,
                    PluginCredentialStatus.CONNECTED,
                    null,
                    null,
                    null,
                    null,
                    1L)));

    List<PluginDTO> plugins = service.listPlugins();

    assertEquals(
        List.of(DEEPLINK_ID, NONE_ID, STATIC_ID),
        plugins.stream().map(PluginDTO::getPluginId).toList());
    PluginDTO staticPlugin = service.getPlugin(STATIC_ID);
    assertEquals(new PluginAuthKindDTO.StaticKey("apiKey"), staticPlugin.getAuthKind());
    assertEquals("CONNECTED", staticPlugin.getStatus());
    assertTrue(staticPlugin.getConfiguration().getSecrets().get("apiKey").isConfigured());
    assertFalse(staticPlugin.toString().contains("secret-value"));
    assertEquals("2", staticPlugin.getDesiredRevision());
    assertEquals("2", staticPlugin.getAppliedRevision());
    assertNull(staticPlugin.getApplicationError());
    assertTrue(staticPlugin.isConnectionCheckSupported());
    assertEquals(List.of("static.render"), staticPlugin.getCanvasFunctionNames());

    PluginDTO deepLink = service.getPlugin(DEEPLINK_ID);
    assertEquals(new PluginAuthKindDTO.DeepLink(List.of("CN", "US")), deepLink.getAuthKind());
    assertEquals("NOT_CONNECTED", deepLink.getStatus());
    assertNull(service.getPlugin(NONE_ID).getAuthKind());
    assertThrows(AiResourceNotFoundException.class, () -> service.getPlugin("ghost"));
  }

  /** 静态密钥字段未录入时显示未连接；已保存但本节点尚未应用时给出待应用说明。 */
  @Test
  void reportsMissingKeysAndPendingApplication() {
    when(credentials.find(STATIC_ID))
        .thenReturn(
            Optional.of(
                new PluginCredentialRow(
                    STATIC_ID,
                    "{\"region\":\"eu\"}",
                    null,
                    null,
                    null,
                    PluginCredentialStatus.CONNECTED,
                    null,
                    null,
                    null,
                    null,
                    1L)));
    stored(STATIC_ID, 3L);

    PluginDTO dto = service.updateConfiguration(STATIC_ID, 2L, "{}", Map.of());

    verify(configurationService).configure(STATIC_ID, 2L, "{}", Map.of());
    assertEquals("NOT_CONNECTED", dto.getStatus());
    assertEquals("3", dto.getDesiredRevision());
    assertEquals("2", dto.getAppliedRevision());
    assertEquals(
        "configuration is saved but this node has not applied it yet", dto.getApplicationError());
  }

  /** prepare 只保存 attempt 摘要与当时的配置修订，返回一次性句柄与登录地址。 */
  @Test
  void prepareAuthStoresAHashedAttempt() {
    when(authHandler.loginUrl("CN")).thenReturn("https://login.example/cn");

    PluginAuthPrepareDTO prepared = service.prepareAuth(DEEPLINK_ID, "CN");

    assertEquals("https://login.example/cn", prepared.getLoginUrl());
    ArgumentCaptor<PluginAuthAttemptRow> attempt =
        ArgumentCaptor.forClass(PluginAuthAttemptRow.class);
    verify(authAttempts).replace(attempt.capture());
    assertEquals(PluginAuthNonce.hash(prepared.getAuthAttemptId()), attempt.getValue().nonceHash());
    assertEquals(1L, attempt.getValue().configRevision());
    assertEquals(NOW.plus(Duration.ofMinutes(10)), attempt.getValue().expiresAt());
  }

  /** 非交互 Plugin、未声明 region、插件拒绝 region 或不给登录地址都拒绝 prepare。 */
  @Test
  void prepareAuthRejectsInvalidRequests() {
    assertThrows(AiValidationException.class, () -> service.prepareAuth(STATIC_ID, "CN"));
    assertThrows(AiValidationException.class, () -> service.prepareAuth(DEEPLINK_ID, "EU"));
    when(authHandler.loginUrl("CN")).thenThrow(new IllegalStateException("no"));
    assertThrows(AiValidationException.class, () -> service.prepareAuth(DEEPLINK_ID, "CN"));
    when(authHandler.loginUrl("US")).thenReturn(" ");
    assertThrows(AiValidationException.class, () -> service.prepareAuth(DEEPLINK_ID, "US"));
    verify(authAttempts, never()).replace(any());
  }

  /** complete 原子消费 attempt，并把同 region、时限可用的材料交给唯一写入口。 */
  @Test
  void completeAuthWritesTheMaterial() {
    String handle = PluginAuthNonce.issue();
    attempt(handle, "CN");
    PluginCredentialMaterial material = material("CN", NOW.plusSeconds(3600));
    when(authHandler.complete("app://callback")).thenReturn(material);

    service.completeAuth(DEEPLINK_ID, handle, "app://callback");

    verify(configurationService).completeAuth(DEEPLINK_ID, material);
  }

  /** 非法句柄、空回调、未知 attempt、插件拒绝、region 不符或时限不可用都拒绝且不写凭据。 */
  @Test
  void completeAuthRejectsInvalidCallbacks() {
    String handle = PluginAuthNonce.issue();
    assertThrows(
        AiValidationException.class, () -> service.completeAuth(DEEPLINK_ID, "bad", "app://cb"));
    assertThrows(AiValidationException.class, () -> service.completeAuth(DEEPLINK_ID, handle, " "));
    when(authAttempts.consume(eq(DEEPLINK_ID), anyString(), eq(NOW))).thenReturn(Optional.empty());
    assertThrows(
        AiValidationException.class, () -> service.completeAuth(DEEPLINK_ID, handle, "app://cb"));

    attempt(handle, "CN");
    when(authHandler.complete("app://cb")).thenThrow(new IllegalStateException("bad callback"));
    assertThrows(
        AiValidationException.class, () -> service.completeAuth(DEEPLINK_ID, handle, "app://cb"));
    for (PluginCredentialMaterial material :
        List.of(material("US", NOW.plusSeconds(3600)), material("CN", NOW))) {
      attempt(handle, "CN");
      when(authHandler.complete("app://other")).thenReturn(material);
      assertThrows(
          AiValidationException.class,
          () -> service.completeAuth(DEEPLINK_ID, handle, "app://other"));
    }
    verify(configurationService, never()).completeAuth(anyString(), any());
  }

  @Test
  void disconnectDelegatesToTheWriteEntry() {
    service.disconnect(DEEPLINK_ID);

    verify(configurationService).disconnect(DEEPLINK_ID);
  }

  /** 连接检查要求请求修订等于已应用修订，插件未声明能力时拒绝。 */
  @Test
  void checkConnectionRunsAgainstTheAppliedInstance() {
    when(connectionCheck.check())
        .thenReturn(
            new PluginConnectionCheckResult(
                PluginConnectionCheckResult.Reachability.REACHABLE,
                PluginConnectionCheckResult.Authentication.ACCEPTED,
                PluginConnectionCheckResult.BrowserSession.UNKNOWN));

    PluginConnectionCheckDTO result = service.checkConnection(STATIC_ID, 2L);

    assertEquals("2", result.getAppliedRevision());
    assertEquals(NOW, result.getCheckedAt());
    assertEquals(PluginConnectionCheckDTO.Reachability.REACHABLE, result.getReachability());
    assertThrows(AiVersionConflictException.class, () -> service.checkConnection(STATIC_ID, 1L));
    assertThrows(AiValidationException.class, () -> service.checkConnection(NONE_ID, 1L));
  }

  private void stored(String pluginId, long revision) {
    when(configurations.find(pluginId))
        .thenReturn(Optional.of(new PluginConfigurationRow(pluginId, revision, "{}")));
    when(configurations.findAll())
        .thenReturn(
            List.of(
                new PluginConfigurationRow(STATIC_ID, 2L, "{}"),
                new PluginConfigurationRow(DEEPLINK_ID, 1L, "{}"),
                new PluginConfigurationRow(NONE_ID, 1L, "{}")));
  }

  private void attempt(String handle, String region) {
    when(authAttempts.consume(DEEPLINK_ID, PluginAuthNonce.hash(handle), NOW))
        .thenReturn(
            Optional.of(
                new PluginAuthAttemptRow(
                    DEEPLINK_ID, PluginAuthNonce.hash(handle), 1L, region, NOW.plusSeconds(60))));
  }

  private static PluginCredentialMaterial material(String region, Instant expiresAt) {
    return new PluginCredentialMaterial(region, expiresAt, NOW, "{\"token\":\"t\"}");
  }

  private abstract static class BasePlugin implements StudioPlugin {

    private final String pluginId;

    BasePlugin(String pluginId) {
      this.pluginId = pluginId;
    }

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor(pluginId, pluginId, "1.0");
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return TestPluginFixtures.EMPTY_SCHEMA;
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      return TestPluginFixtures.emptyInstance();
    }
  }

  private final class StaticKeyPlugin extends BasePlugin {

    StaticKeyPlugin() {
      super(STATIC_ID);
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return TestPluginFixtures.emptySchemaWithSecrets(
          Map.of("apiKey", "API key", "region", "Region"));
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.StaticKey("apiKey", Set.of());
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      CanvasFunctionAdapter adapter = mock(CanvasFunctionAdapter.class);
      when(adapter.enabled()).thenReturn(true);
      when(adapter.functions())
          .thenReturn(
              List.of(
                  CanvasFunctionDefinition.of(
                      "static.render",
                      "Render",
                      CanvasJson.parseObject(
                          "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"),
                      CanvasResourceKind.IMAGE,
                      new CanvasFunctionReferencePolicy(
                          Set.of(CanvasResourceKind.IMAGE), 1, Map.of()))));
      return new PluginInstance() {
        @Override
        public List<HarnessContributor> harnessContributors() {
          return List.of();
        }

        @Override
        public List<CanvasFunctionAdapter> canvasAdapters() {
          return List.of(adapter);
        }

        @Override
        public Optional<PluginConnectionCheck> connectionCheck() {
          return Optional.of(connectionCheck);
        }

        @Override
        public void close() {}
      };
    }
  }

  private final class DeepLinkPlugin extends BasePlugin {

    DeepLinkPlugin() {
      super(DEEPLINK_ID);
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.DeepLink(List.of("CN", "US"));
    }

    @Override
    public Optional<PluginAuthHandler> authHandler() {
      return Optional.of(authHandler);
    }
  }

  private static final class NonePlugin extends BasePlugin {

    NonePlugin() {
      super(NONE_ID);
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.None();
    }
  }
}
