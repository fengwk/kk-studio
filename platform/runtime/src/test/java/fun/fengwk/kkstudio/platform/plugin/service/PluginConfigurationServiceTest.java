package fun.fengwk.kkstudio.platform.plugin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.common.schema.StringSchema;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthHandler;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialSnapshot;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialStatus;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialUnavailableException;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialStore;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginConfigurationRow;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRepository;
import fun.fengwk.kkstudio.platform.plugin.persistence.PluginCredentialRow;
import fun.fengwk.kkstudio.platform.plugin.testing.InMemoryTransactionManager;
import fun.fengwk.kkstudio.platform.plugin.testing.TestPluginFixtures;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** {@link PluginConfigurationService} 的配置 CAS、秘密补丁、候选校验与提交后唤醒。 */
class PluginConfigurationServiceTest {

  private static final String STATIC_ID = "static.plugin";
  private static final String DEEPLINK_ID = "deeplink.plugin";
  private static final String VALUES_A = "{\"endpoint\":\"https://a.example\"}";
  private static final String VALUES_B = "{\"endpoint\":\"https://b.example\"}";

  private final PluginConfigurationRepository configurations =
      mock(PluginConfigurationRepository.class);
  private final PluginCredentialRepository credentials = mock(PluginCredentialRepository.class);
  private final PluginAgentDependencies agentDependencies = mock(PluginAgentDependencies.class);
  private final AtomicInteger catalogWakes = new AtomicInteger();
  private final AtomicInteger credentialWakes = new AtomicInteger();
  private final StaticKeyPlugin staticPlugin = new StaticKeyPlugin();
  private PluginConfigurationService service;

  @BeforeEach
  void setUp() {
    service =
        new PluginConfigurationService(
            new StudioPluginRegistry(List.of(staticPlugin, new DeepLinkPlugin())),
            configurations,
            credentials,
            new PluginContextFactory(
                mock(PluginCredentialStore.class), () -> null, () -> null, () -> null),
            agentDependencies,
            new InMemoryTransactionManager(),
            catalogWakes::incrementAndGet,
            credentialWakes::incrementAndGet);
    when(configurations.find(STATIC_ID))
        .thenReturn(Optional.of(new PluginConfigurationRow(STATIC_ID, 3L, VALUES_A)));
    when(configurations.update(anyString(), anyLong(), anyString())).thenReturn(true);
    when(credentials.find(anyString())).thenReturn(Optional.empty());
  }

  /** 只改普通值：CAS 写入、发出通知，只唤醒目录刷新；configure 见到候选修订。 */
  @Test
  void savesOrdinaryValuesWithoutTouchingCredentials() {
    assertEquals(4L, service.configure(STATIC_ID, 3L, VALUES_A, Map.of()));

    verify(agentDependencies).requireDeclaredAgents(STATIC_ID, 4L, VALUES_A);
    verify(configurations).update(STATIC_ID, 3L, VALUES_A);
    verify(configurations).notifyChanged(STATIC_ID);
    verify(credentials, never()).delete(anyString());
    assertEquals(4L, staticPlugin.lastConfig.revision());
    assertEquals(1, catalogWakes.get());
    assertEquals(0, credentialWakes.get());
  }

  /** SET 写入只含声明字段的新载荷，候选凭据先交给 configure 校验；CLEAR 最后一个字段删除凭据。 */
  @Test
  void secretPatchWritesOrRemovesTheCredential() {
    service.configure(STATIC_ID, 3L, VALUES_A, Map.of("apiKey", PluginSecretPatch.set("k1")));
    verify(credentials).upsertConnected(STATIC_ID, "{\"apiKey\":\"k1\"}", null, null, null);
    assertEquals("{\"apiKey\":\"k1\"}", staticPlugin.lastCandidate.payloadJson());
    assertEquals(1, credentialWakes.get());

    when(credentials.find(STATIC_ID)).thenReturn(Optional.of(credential("{\"apiKey\":\"k1\"}")));
    service.configure(STATIC_ID, 3L, VALUES_A, Map.of("apiKey", PluginSecretPatch.clear()));
    verify(credentials).delete(STATIC_ID);
    assertNull(staticPlugin.lastCandidate);
  }

  /** KEEP 保留已有声明字段，SET 只覆盖被点名的字段；载荷中未声明的内容被丢弃。 */
  @Test
  void keepRetainsDeclaredFields() {
    when(credentials.find(STATIC_ID))
        .thenReturn(Optional.of(credential("{\"apiKey\":\"k1\",\"stray\":\"x\"}")));

    service.configure(
        STATIC_ID,
        3L,
        VALUES_A,
        Map.of("apiKey", PluginSecretPatch.keep(), "region", PluginSecretPatch.set("eu")));

    verify(credentials)
        .upsertConnected(STATIC_ID, "{\"apiKey\":\"k1\",\"region\":\"eu\"}", null, null, null);
  }

  /** 目标地址变化而未重新录入密钥时删除旧凭据；同时录入则只保留新值。 */
  @Test
  void contextChangeDropsTheOldSecret() {
    when(credentials.find(STATIC_ID))
        .thenReturn(Optional.of(credential("{\"apiKey\":\"k1\",\"region\":\"eu\"}")));

    service.configure(STATIC_ID, 3L, VALUES_B, Map.of());
    verify(credentials).delete(STATIC_ID);

    service.configure(STATIC_ID, 3L, VALUES_B, Map.of("apiKey", PluginSecretPatch.set("k2")));
    verify(credentials).upsertConnected(STATIC_ID, "{\"apiKey\":\"k2\"}", null, null, null);
  }

  /** 修订不匹配（读取时或 CAS 时）都是版本冲突，不发出通知。 */
  @Test
  void staleRevisionIsAConflict() {
    assertThrows(
        AiVersionConflictException.class, () -> service.configure(STATIC_ID, 2L, VALUES_A, null));

    when(configurations.update(STATIC_ID, 3L, VALUES_A)).thenReturn(false);
    assertThrows(
        AiVersionConflictException.class,
        () -> service.configure(STATIC_ID, 3L, VALUES_A, Map.of()));
    verify(configurations, never()).notifyChanged(anyString());
    assertEquals(0, catalogWakes.get());
  }

  /** 未安装 Plugin、非法值、未声明的秘密字段、交互凭据补丁与插件拒绝都在写入前失败。 */
  @Test
  void rejectsInvalidRequestsBeforeWriting() {
    assertThrows(
        AiResourceNotFoundException.class, () -> service.configure("ghost", 1L, "{}", Map.of()));
    assertThrows(
        AiValidationException.class,
        () -> service.configure(STATIC_ID, 3L, "{\"endpoint\":1}", Map.of()));
    assertThrows(
        AiValidationException.class,
        () ->
            service.configure(
                STATIC_ID, 3L, VALUES_A, Map.of("other", PluginSecretPatch.set("x"))));
    assertThrows(
        AiValidationException.class,
        () ->
            service.configure(DEEPLINK_ID, 1L, "{}", Map.of("token", PluginSecretPatch.set("x"))));
    staticPlugin.reject = true;
    assertThrows(
        AiValidationException.class, () -> service.configure(STATIC_ID, 3L, VALUES_A, Map.of()));
    verify(configurations, never()).update(anyString(), anyLong(), anyString());
  }

  /** 登录完成写入交互凭据，断连删除凭据，二者都唤醒目录与刷新调度。 */
  @Test
  void completeAuthAndDisconnectRewriteTheCredential() {
    PluginCredentialMaterial material =
        new PluginCredentialMaterial(
            "CN",
            Instant.parse("2026-10-02T00:00:00Z"),
            Instant.parse("2026-10-01T12:00:00Z"),
            "{\"token\":\"t\"}");

    service.completeAuth(DEEPLINK_ID, material);
    service.disconnect(DEEPLINK_ID);

    verify(credentials)
        .upsertConnected(
            DEEPLINK_ID, "{\"token\":\"t\"}", "CN", material.expiresAt(), material.nextRefreshAt());
    verify(credentials).delete(DEEPLINK_ID);
    assertEquals(2, catalogWakes.get());
    assertEquals(2, credentialWakes.get());
    assertThrows(AiResourceNotFoundException.class, () -> service.disconnect("ghost"));
  }

  private static PluginCredentialRow credential(String payloadJson) {
    return new PluginCredentialRow(
        STATIC_ID,
        payloadJson,
        null,
        null,
        null,
        PluginCredentialStatus.CONNECTED,
        null,
        null,
        null,
        null,
        1L);
  }

  /** 以 endpoint 为认证目标的静态密钥 Plugin，记录 configure 看到的候选配置与凭据。 */
  private static final class StaticKeyPlugin implements StudioPlugin {

    private PluginConfig lastConfig;
    private PluginCredentialSnapshot lastCandidate;
    private boolean reject;

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor(STATIC_ID, "Static", "1.0");
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return new PluginConfigSchema(
          new InputSchema(
              "config", Map.of("endpoint", new StringSchema("endpoint")), Set.of(), false),
          VALUES_A,
          Map.of("apiKey", "API key", "region", "Region"));
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.StaticKey("apiKey", Set.of("endpoint"));
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      if (reject) {
        throw new IllegalArgumentException("rejected");
      }
      lastConfig = config;
      try {
        lastCandidate = context.credentialAccess().resolve();
      } catch (PluginCredentialUnavailableException notConnected) {
        lastCandidate = null;
      }
      return TestPluginFixtures.emptyInstance();
    }
  }

  private static final class DeepLinkPlugin implements StudioPlugin {

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor(DEEPLINK_ID, "DeepLink", "1.0");
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return TestPluginFixtures.emptySchemaWithSecrets(Map.of("token", "token"));
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return new PluginAuthSpec.DeepLink(List.of("CN"));
    }

    @Override
    public Optional<PluginAuthHandler> authHandler() {
      return Optional.of(mock(PluginAuthHandler.class));
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      return TestPluginFixtures.emptyInstance();
    }
  }
}
