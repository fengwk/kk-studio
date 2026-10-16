package fun.fengwk.kkstudio.platform.plugin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthHandler;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialMaterial;
import fun.fengwk.kkstudio.platform.plugin.PluginCredentialRefresher;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.testing.TestPluginFixtures;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 初始静态凭据材料构造（{@link PluginConfigurationCommitService#prepareInitialStaticMaterial}）的 focused 契约测试。
 *
 * <p>只断言纯准备步骤本身：它复用普通提交路径已声明的秘密字段校验、{@code SET} 值语义与整体 64 KiB 有界写出，产出三项路由元数据全为 {@code null}
 * 的静态材料，且完全不触碰 Plugin 运行期、认证处理器与刷新能力。
 */
class PluginConfigurationCommitServiceInitialStaticMaterialTest {

  private static final String API_KEY = "static-api-key-13579";
  private static final String API_KEY_DESCRIPTION = "static API key";

  @Test
  void singleFieldMaterialPreservesFieldAndValueByteForByte() {
    RecordingPlugin plugin = staticPlugin(Map.of("apiKey", API_KEY_DESCRIPTION));

    PluginCredentialMaterial material = prepare(plugin, Map.of("apiKey", API_KEY));

    assertEquals("{\"apiKey\":\"" + API_KEY + "\"}", material.payloadJson());
    JsonNode payload = JsonValues.readTree(material.payloadJson());
    assertEquals(1, payload.size());
    assertEquals(API_KEY, payload.get("apiKey").textValue());
  }

  @Test
  void materialCarriesNoRouteMetadata() {
    PluginCredentialMaterial material =
        prepare(staticPlugin(Map.of("apiKey", API_KEY_DESCRIPTION)), Map.of("apiKey", API_KEY));

    assertNull(material.region());
    assertNull(material.expiresAt());
    assertNull(material.nextRefreshAt());
    assertFalse(material.interactive());
  }

  @Test
  void multipleFieldsPreserveEveryDeclaredFieldAndValue() {
    RecordingPlugin plugin = staticPlugin(Map.of("apiKey", "a", "apiSecret", "b", "apiToken", "c"));
    Map<String, String> provided = new LinkedHashMap<>();
    provided.put("apiToken", "token-value-3");
    provided.put("apiKey", "key-value-1");
    // 多字节值必须原样保留，不被归一化或截断。
    provided.put("apiSecret", "秘密-值-2");

    JsonNode payload = JsonValues.readTree(prepare(plugin, provided).payloadJson());

    assertEquals(3, payload.size());
    assertEquals("token-value-3", payload.get("apiToken").textValue());
    assertEquals("key-value-1", payload.get("apiKey").textValue());
    assertEquals("秘密-值-2", payload.get("apiSecret").textValue());
  }

  @Test
  void auxiliaryOnlyMaterialInjectsNoDefaultMainKey() {
    RecordingPlugin plugin = staticPluginWithAuxiliarySecrets();
    Map<String, String> provided = new LinkedHashMap<>();
    provided.put("apiSecret", "aux-secret-2");
    provided.put("apiToken", "aux-token-3");

    JsonNode payload = JsonValues.readTree(prepare(plugin, provided).payloadJson());

    assertEquals(2, payload.size());
    assertEquals("aux-secret-2", payload.get("apiSecret").textValue());
    assertEquals("aux-token-3", payload.get("apiToken").textValue());
    assertFalse(payload.has("apiKey"), "preparation must not invent the main key");
  }

  @Test
  void prepareNeverConfiguresThePluginOrTouchesAuthSurfaces() {
    RecordingPlugin plugin = staticPlugin(Map.of("apiKey", API_KEY_DESCRIPTION));

    PluginCredentialMaterial material = prepare(plugin, Map.of("apiKey", API_KEY));

    assertNotNull(material);
    assertEquals(0, plugin.configureCalls(), "prepare must not build a plugin instance");
    assertEquals(0, plugin.authHandlerCalls(), "prepare must not query interactive auth");
    assertEquals(0, plugin.refresherCalls(), "prepare must not query credential refresh");
  }

  @Test
  void rejectsUnauthenticatedAndInteractivePluginsWithFixedError() {
    RecordingPlugin noAuth =
        new RecordingPlugin(
            "no.auth.plugin",
            new PluginAuthSpec.None(),
            Map.of("apiKey", API_KEY_DESCRIPTION),
            null);
    AiValidationException noAuthError =
        assertThrows(AiValidationException.class, () -> prepare(noAuth, Map.of("apiKey", API_KEY)));
    assertEquals(
        "initial static credential requires a static key authentication spec",
        noAuthError.getMessage());
    assertFalse(noAuthError.getMessage().contains(API_KEY));

    RecordingPlugin deepLink =
        new RecordingPlugin(
            "deep.link.plugin",
            new PluginAuthSpec.DeepLink(List.of("CN")),
            Map.of("token", "interactive token"),
            interactiveAuthHandler());
    AiValidationException deepLinkError =
        assertThrows(
            AiValidationException.class, () -> prepare(deepLink, Map.of("token", API_KEY)));
    assertEquals(
        "initial static credential requires a static key authentication spec",
        deepLinkError.getMessage());
    assertEquals(0, deepLink.configureCalls());
  }

  @Test
  void rejectsEmptySecretValues() {
    RecordingPlugin plugin = staticPlugin(Map.of("apiKey", API_KEY_DESCRIPTION));

    AiValidationException error =
        assertThrows(AiValidationException.class, () -> prepare(plugin, Map.of()));

    assertEquals(
        "initial static credential requires at least one secret value", error.getMessage());
  }

  @Test
  void rejectsNullArgumentsWithFixedParameterNames() {
    RecordingPlugin plugin = staticPlugin(Map.of("apiKey", API_KEY_DESCRIPTION));

    NullPointerException pluginError =
        assertThrows(
            NullPointerException.class,
            () -> PluginConfigurationCommitService.prepareInitialStaticMaterial(null, Map.of()));
    assertEquals("plugin", pluginError.getMessage());

    NullPointerException valuesError =
        assertThrows(
            NullPointerException.class,
            () -> PluginConfigurationCommitService.prepareInitialStaticMaterial(plugin, null));
    assertEquals("secretValues", valuesError.getMessage());
  }

  @Test
  void rejectsUnknownDeclaredFieldsWithoutEchoingTheFieldName() {
    RecordingPlugin plugin = staticPlugin(Map.of("apiKey", API_KEY_DESCRIPTION));
    String unknownField = "undeclared-secret-field";

    AiValidationException error =
        assertThrows(
            AiValidationException.class, () -> prepare(plugin, Map.of(unknownField, API_KEY)));

    assertEquals("secret patch must name a declared secret field", error.getMessage());
    assertFalse(error.getMessage().contains(unknownField));
    assertFalse(error.getMessage().contains(API_KEY));
  }

  @Test
  void rejectsBlankPaddedMaskedAndOversizedValues() {
    RecordingPlugin plugin = staticPlugin(Map.of("apiKey", API_KEY_DESCRIPTION));

    for (String value : List.of("", "   ", " padded", "padded ", "***", "•••")) {
      assertThrows(IllegalArgumentException.class, () -> prepare(plugin, Map.of("apiKey", value)));
    }
    Map<String, String> nullValue = new LinkedHashMap<>();
    nullValue.put("apiKey", null);
    assertThrows(IllegalArgumentException.class, () -> prepare(plugin, nullValue));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            prepare(plugin, Map.of("apiKey", "x".repeat(PluginSecretPatch.MAX_SECRET_CHARS + 1))));

    String multiByte = "汉".repeat(PluginSecretPatch.MAX_SECRET_CHARS / 3 + 1);
    assertTrue(
        multiByte.getBytes(StandardCharsets.UTF_8).length > PluginSecretPatch.MAX_SECRET_CHARS);
    assertThrows(
        IllegalArgumentException.class, () -> prepare(plugin, Map.of("apiKey", multiByte)));
  }

  @Test
  void rejectsMultiFieldPayloadOverTheCombinedBudget() {
    RecordingPlugin plugin = staticPluginWithAuxiliarySecrets();
    // 长度为 1 字节的控制字符在 JSON 转义后展开为 6 字节，因此每个值都恰好落在单字段上限内，
    // 只有三个字段组合出的整体载荷才超过 64 KiB：命中的必须是既有的整体有界写出，而不是单字段校验。
    String perFieldValue = "\u0001".repeat(PluginSecretPatch.MAX_SECRET_CHARS);
    assertEquals(PluginSecretPatch.MAX_SECRET_CHARS, perFieldValue.length());
    assertEquals(
        PluginSecretPatch.MAX_SECRET_CHARS, perFieldValue.getBytes(StandardCharsets.UTF_8).length);
    Map<String, String> oversized = new LinkedHashMap<>();
    oversized.put("apiKey", perFieldValue);
    oversized.put("apiSecret", perFieldValue);
    oversized.put("apiToken", perFieldValue);

    AiValidationException error =
        assertThrows(AiValidationException.class, () -> prepare(plugin, oversized));

    assertEquals("secret patch payload must not exceed the credential budget", error.getMessage());
  }

  @Test
  void acceptsMultiFieldPayloadThatFitsTheCombinedBudget() {
    RecordingPlugin plugin = staticPluginWithAuxiliarySecrets();
    String value = "v".repeat(3000);
    Map<String, String> provided = new LinkedHashMap<>();
    provided.put("apiKey", value);
    provided.put("apiSecret", value);
    provided.put("apiToken", value);

    JsonNode payload = JsonValues.readTree(prepare(plugin, provided).payloadJson());

    assertEquals(3, payload.size());
    assertEquals(value, payload.get("apiKey").textValue());
  }

  private static PluginCredentialMaterial prepare(
      StudioPlugin plugin, Map<String, String> secretValues) {
    return PluginConfigurationCommitService.prepareInitialStaticMaterial(plugin, secretValues);
  }

  private static RecordingPlugin staticPlugin(Map<String, String> secrets) {
    return new RecordingPlugin(
        "static.key.plugin", new PluginAuthSpec.StaticKey("apiKey", Set.of()), secrets, null);
  }

  /** 主密钥与两个辅助秘密字段：同时覆盖「仅录入辅助字段」与多字段组合预算。 */
  private static RecordingPlugin staticPluginWithAuxiliarySecrets() {
    return staticPlugin(
        Map.of(
            "apiKey", API_KEY_DESCRIPTION,
            "apiSecret", "static API secret",
            "apiToken", "static API token"));
  }

  private static PluginAuthHandler interactiveAuthHandler() {
    return new PluginAuthHandler() {
      @Override
      public String loginUrl(String region) {
        return "https://login.example.com/" + region;
      }

      @Override
      public PluginCredentialMaterial complete(String callbackUrl) {
        throw new AssertionError("interactive login completion must not run during preparation");
      }
    };
  }

  /** 忠实最小 Plugin 替身：任何 configure/auth 面被触碰都会被计数，configure 还会直接失败。 */
  private static final class RecordingPlugin implements StudioPlugin {

    private final String pluginId;
    private final PluginAuthSpec authSpec;
    private final PluginConfigSchema schema;
    private final PluginAuthHandler authHandler;
    private int configureCalls;
    private int authHandlerCalls;
    private int refresherCalls;

    private RecordingPlugin(
        String pluginId,
        PluginAuthSpec authSpec,
        Map<String, String> secrets,
        PluginAuthHandler authHandler) {
      this.pluginId = pluginId;
      this.authSpec = authSpec;
      this.schema = TestPluginFixtures.emptySchemaWithSecrets(secrets);
      this.authHandler = authHandler;
    }

    @Override
    public PluginDescriptor descriptor() {
      return new PluginDescriptor(pluginId, "Recording Plugin", "1.0");
    }

    @Override
    public PluginConfigSchema configurationSchema() {
      return schema;
    }

    @Override
    public PluginAuthSpec authenticationSpec() {
      return authSpec;
    }

    @Override
    public PluginInstance configure(PluginConfig config, PluginContext context) {
      configureCalls++;
      throw new AssertionError("preparation must not build a plugin instance");
    }

    @Override
    public Optional<PluginAuthHandler> authHandler() {
      authHandlerCalls++;
      return Optional.ofNullable(authHandler);
    }

    @Override
    public Optional<PluginCredentialRefresher> refresher() {
      refresherCalls++;
      return Optional.empty();
    }

    private int configureCalls() {
      return configureCalls;
    }

    private int authHandlerCalls() {
      return authHandlerCalls;
    }

    private int refresherCalls() {
      return refresherCalls;
    }
  }
}
