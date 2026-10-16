package fun.fengwk.kkstudio.web.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;
import fun.fengwk.kkstudio.platform.plugin.StudioPluginRegistry;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogBootstrap;
import fun.fengwk.kkstudio.platform.plugin.catalog.PluginCatalogSnapshot;
import fun.fengwk.kkstudio.web.upgrade.OfflineHostProbes.CountingDataSource;
import fun.fengwk.kkstudio.web.upgrade.OfflineHostProbes.LocalHttpProbe;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@link LegacySystemSettings} 第二阶段的定向回归：用当前六节 settings 现场打开真实的 runtime-only 四 JAR 装配，把旧
 * integrations 普通值投影为每个实际已安装 Plugin 的配置，并由现有 {@link OfflinePluginCatalog#prepareInitial} 真正
 * configure 完成完整业务校验。
 *
 * <p>全程零数据库连接（计数 DataSource）与零 HTTP（本地回环探针经真实全局代理），失败只带受控 stage / pluginId，且不跳过或吞掉任何异常。
 */
class LegacySystemSettingsProjectionTest {

  private static final List<String> FROZEN_PLUGIN_IDS =
      List.of("comfyui", "media", "minimax-mavis", "opencli");

  private static final String DEFAULTS = "legacy-integrations-defaults.json";
  private static final String ENABLED = "legacy-integrations-enabled.json";

  /** 缺失/多出任何 id 都要 fail-closed 的合成第五个安装身份；注册表构造只调用 {@link StudioPlugin#descriptor()}。 */
  private static final StudioPlugin EXTRA_PLUGIN =
      new StudioPlugin() {
        @Override
        public PluginDescriptor descriptor() {
          return new PluginDescriptor("synthetic-extra", "Synthetic", "1.0.0");
        }

        @Override
        public PluginConfigSchema configurationSchema() {
          throw new UnsupportedOperationException(
              "profile rejection must happen before schema use");
        }

        @Override
        public PluginAuthSpec authenticationSpec() {
          throw new UnsupportedOperationException("profile rejection must happen before auth use");
        }

        @Override
        public PluginInstance configure(PluginConfig config, PluginContext context) {
          throw new UnsupportedOperationException("profile rejection must happen before configure");
        }
      };

  /** 完整旧默认：comfyui / opencli 都停用，H3 停用且 endpoint / agent 为 null 时保留目标新默认。 */
  @Test
  void projectsTheLegacyDefaultsIntoTheFrozenProfile() throws IOException {
    CountingDataSource dataSource = new CountingDataSource();
    try (LocalHttpProbe probe = LocalHttpProbe.start()) {
      ObjectNode source =
          readObject(LegacySystemSettingsTest.fixture("legacy-settings-defaults-canonical.json"));
      ((ObjectNode) source.get("network"))
          .put("proxyUrl", probe.proxyUrl())
          .put("noProxyHosts", "");
      LegacySystemSettings legacy = LegacySystemSettings.parse(JsonValues.write(source));
      try (OfflinePluginCatalog catalog =
          OfflinePluginCatalog.open(dataSource, legacy.settings())) {
        StudioPluginRegistry registry = catalog.registry();
        assertEquals(FROZEN_PLUGIN_IDS, registry.pluginIds());

        Map<String, String> values = legacy.pluginValues(registry, false, false);
        assertEquals(FROZEN_PLUGIN_IDS, List.copyOf(values.keySet()));

        ObjectNode comfyui = readObject(values.get("comfyui"));
        assertFalse(comfyui.get("enabled").booleanValue());
        assertEquals("NONE", comfyui.get("authMode").stringValue());
        assertEquals(10_000L, comfyui.get("connectTimeoutMillis").longValue());
        assertEquals(30_000L, comfyui.get("requestTimeoutMillis").longValue());
        assertEquals(2_000L, comfyui.get("pollIntervalMillis").longValue());
        assertEquals(1_800_000L, comfyui.get("maxWaitMillis").longValue());
        assertEquals(600_000L, comfyui.get("promptMaxWaitMillis").longValue());
        // 旧 H3 停用且 nullable 为 null：保留目标新默认（是禁用的新配置，不是历史绑定）。
        assertEquals("http://127.0.0.1:8188", comfyui.get("baseUrl").stringValue());
        assertEquals("default-assistant", comfyui.get("promptAgentName").stringValue());
        assertTrue(workflowIds(comfyui).contains(OfflineInitialCatalogValues.H3_WORKFLOW_ID));

        assertEquals("{\"enabled\":true}", values.get("media"));
        assertEquals("{\"enabled\":true}", values.get("minimax-mavis"));

        ObjectNode opencli = readObject(values.get("opencli"));
        assertFalse(opencli.get("enabled").booleanValue());
        assertEquals(5_000L, opencli.get("connectTimeoutMillis").longValue());
        assertEquals(120_000L, opencli.get("requestTimeoutMillis").longValue());
        assertEquals(130_000L, opencli.get("longPollTimeoutMillis").longValue());
        assertEquals(16_384, opencli.get("streamBufferBytes").intValue());
        assertEquals(524_288, opencli.get("maxJsonResponseBytes").intValue());
        assertEquals(4_096, opencli.get("maxErrorResponseBytes").intValue());
        assertEquals(65_535, opencli.get("maxOutputChars").intValue());
        assertFalse(
            opencli.has("baseUrl"), "null baseUrl must follow the optional target semantics");
        ObjectNode gptImage2 = (ObjectNode) opencli.get("gptImage2");
        assertFalse(gptImage2.get("paidEnabled").booleanValue());
        assertEquals(900, gptImage2.get("askTimeoutSeconds").intValue());
        assertEquals(960_000L, gptImage2.get("hubExecutionTimeoutMillis").longValue());
        assertEquals(1_200_000L, gptImage2.get("maxWaitMillis").longValue());
        ObjectNode seedance = (ObjectNode) opencli.get("seedance");
        assertFalse(seedance.get("enabled").booleanValue());
        assertEquals(0, seedance.get("retry").intValue());
        assertEquals(600_000L, seedance.get("hubExecutionTimeoutMillis").longValue());
        assertEquals(30_000L, seedance.get("statusPollIntervalMillis").longValue());
        assertEquals(1_800_000L, seedance.get("maxWaitMillis").longValue());
        assertFalse(
            seedance.has("workspaceId"),
            "null workspaceId must follow the optional target semantics");

        // 输出不可变且重复调用无污染：不修改源 integrations，也不在两次调用间共享可变状态。
        assertThrows(UnsupportedOperationException.class, () -> values.put("comfyui", "{}"));
        assertEquals(values, legacy.pluginValues(registry, false, false));
        assertSame(legacy.settings(), legacy.settings());

        PluginCatalogBootstrap.PreparedInitial prepared =
            catalog.prepareInitial(
                values, OfflineInitialCatalogValues.noConfiguredSecretFields(registry));
        try {
          PluginCatalogSnapshot snapshot = prepared.snapshot();
          assertEquals(1L, snapshot.epoch());
          assertEquals(4, prepared.configurationRows().size());
          assertEquals(Set.copyOf(FROZEN_PLUGIN_IDS), snapshot.capabilityFingerprints().keySet());
        } finally {
          prepared.snapshot().close();
        }
        assertEquals(0, dataSource.openedConnections(), "conversion must not open a connection");
      }
      assertEquals(0, probe.connections(), "conversion must not issue any HTTP request");
    }
  }

  /** 非默认 H3 与 OpenCLI 各项值 / 单位原样保留，启用时 auth 明确为 BEARER，并由真实 configure 冻结出启用的能力。 */
  @Test
  void preservesEveryNonDefaultValueUnitAndAuthBranch() throws IOException {
    CountingDataSource dataSource = new CountingDataSource();
    try (LocalHttpProbe probe = LocalHttpProbe.start()) {
      LegacySystemSettings legacy = parse(ENABLED, probe.proxyUrl());
      try (OfflinePluginCatalog catalog =
          OfflinePluginCatalog.open(dataSource, legacy.settings())) {
        StudioPluginRegistry registry = catalog.registry();
        Map<String, String> values = legacy.pluginValues(registry, true, true);

        ObjectNode comfyui = readObject(values.get("comfyui"));
        assertTrue(comfyui.get("enabled").booleanValue());
        assertEquals("BEARER", comfyui.get("authMode").stringValue());
        assertEquals(15_000L, comfyui.get("connectTimeoutMillis").longValue());
        assertEquals(44_000L, comfyui.get("requestTimeoutMillis").longValue());
        assertEquals(3_000L, comfyui.get("pollIntervalMillis").longValue());
        assertEquals(2_400_000L, comfyui.get("maxWaitMillis").longValue());
        assertEquals(900_000L, comfyui.get("promptMaxWaitMillis").longValue());
        assertEquals("https://legacy-comfy.example:9000", comfyui.get("baseUrl").stringValue());
        assertEquals("legacy-h3-agent", comfyui.get("promptAgentName").stringValue());
        assertTrue(workflowIds(comfyui).contains(OfflineInitialCatalogValues.H3_WORKFLOW_ID));

        ObjectNode opencli = readObject(values.get("opencli"));
        assertTrue(opencli.get("enabled").booleanValue());
        assertEquals("https://legacy-hub.example:8443", opencli.get("baseUrl").stringValue());
        assertEquals(4_321L, opencli.get("connectTimeoutMillis").longValue());
        assertEquals(234_567L, opencli.get("requestTimeoutMillis").longValue());
        assertEquals(200_000L, opencli.get("longPollTimeoutMillis").longValue());
        assertEquals(32_768, opencli.get("streamBufferBytes").intValue());
        assertEquals(1_048_576, opencli.get("maxJsonResponseBytes").intValue());
        assertEquals(8_192, opencli.get("maxErrorResponseBytes").intValue());
        assertEquals(123_456, opencli.get("maxOutputChars").intValue());
        ObjectNode gptImage2 = (ObjectNode) opencli.get("gptImage2");
        assertTrue(gptImage2.get("paidEnabled").booleanValue());
        assertEquals(1_500, gptImage2.get("askTimeoutSeconds").intValue());
        assertEquals(1_600_000L, gptImage2.get("hubExecutionTimeoutMillis").longValue());
        assertEquals(2_400_000L, gptImage2.get("maxWaitMillis").longValue());
        ObjectNode seedance = (ObjectNode) opencli.get("seedance");
        assertTrue(seedance.get("enabled").booleanValue());
        assertEquals("legacy-seedance-workspace", seedance.get("workspaceId").stringValue());
        assertEquals(4, seedance.get("retry").intValue());
        assertEquals(777_000L, seedance.get("hubExecutionTimeoutMillis").longValue());
        assertEquals(45_000L, seedance.get("statusPollIntervalMillis").longValue());
        assertEquals(3_600_000L, seedance.get("maxWaitMillis").longValue());

        PluginCatalogBootstrap.PreparedInitial prepared =
            catalog.prepareInitial(values, secretFields(registry, true, true));
        try {
          PluginCatalogSnapshot snapshot = prepared.snapshot();
          assertEquals(1L, snapshot.epoch());
          assertEquals(4, prepared.configurationRows().size());
          assertEquals(
              Set.of(
                  "image.crop",
                  OfflineInitialCatalogValues.H3_WORKFLOW_ID,
                  "gpt-image-2",
                  "seedance2.0",
                  "seedance2.0fast",
                  "seedance2.0_vip",
                  "seedance2.0fast_vip"),
              OfflineInitialCatalogValues.enabledCanvasFunctions(snapshot));
        } finally {
          prepared.snapshot().close();
        }
        assertEquals(0, dataSource.openedConnections());
      }
      assertEquals(0, probe.connections());
    }
  }

  /** 父开关关闭而子开关开启：opencli.enabled 只取旧 hub.enabled，子对象启用状态不被反推或覆盖。 */
  @Test
  void keepsChildFlagsWhenTheParentSwitchIsOff() throws IOException {
    CountingDataSource dataSource = new CountingDataSource();
    try (LocalHttpProbe probe = LocalHttpProbe.start()) {
      ObjectNode integrations =
          (ObjectNode) JsonValues.readTree(LegacySystemSettingsTest.fixture(DEFAULTS));
      ObjectNode seedance = (ObjectNode) integrations.get("seedance");
      seedance.put("enabled", true);
      seedance.put("workspaceId", "legacy-child-workspace");
      ((ObjectNode) integrations.get("gptImage2")).put("paidEnabled", true);
      LegacySystemSettings legacy =
          LegacySystemSettings.parse(legacyJsonWithProxy(integrations, probe.proxyUrl()));

      try (OfflinePluginCatalog catalog =
          OfflinePluginCatalog.open(dataSource, legacy.settings())) {
        StudioPluginRegistry registry = catalog.registry();
        Map<String, String> values = legacy.pluginValues(registry, false, false);

        ObjectNode opencli = readObject(values.get("opencli"));
        assertFalse(
            opencli.get("enabled").booleanValue(), "enabled must follow the legacy hub switch");
        assertTrue(((ObjectNode) opencli.get("gptImage2")).get("paidEnabled").booleanValue());
        ObjectNode seedanceValues = (ObjectNode) opencli.get("seedance");
        assertTrue(seedanceValues.get("enabled").booleanValue());
        assertEquals("legacy-child-workspace", seedanceValues.get("workspaceId").stringValue());

        // authMode 按 hasComfyBearer 明确取值：即便 H3 停用，提供 bearer 时也是 BEARER。
        assertEquals(
            "BEARER",
            readObject(legacy.pluginValues(registry, true, false).get("comfyui"))
                .get("authMode")
                .stringValue());

        PluginCatalogBootstrap.PreparedInitial prepared =
            catalog.prepareInitial(
                values, OfflineInitialCatalogValues.noConfiguredSecretFields(registry));
        try {
          assertEquals(1L, prepared.snapshot().epoch());
        } finally {
          prepared.snapshot().close();
        }
        assertEquals(0, dataSource.openedConnections());
      }
      assertEquals(0, probe.connections());
    }
  }

  /** 启用旧集成但缺显式材料时固定拒绝：绝不编造旧侧不存在的新 Key，也不静默 disabled。 */
  @Test
  void rejectsEnabledIntegrationsWithoutExplicitMaterialPresence() throws IOException {
    CountingDataSource dataSource = new CountingDataSource();
    try (LocalHttpProbe probe = LocalHttpProbe.start()) {
      LegacySystemSettings legacy = parse(ENABLED, probe.proxyUrl());
      try (OfflinePluginCatalog catalog =
          OfflinePluginCatalog.open(dataSource, legacy.settings())) {
        StudioPluginRegistry registry = catalog.registry();

        IllegalArgumentException comfyui =
            assertThrows(
                IllegalArgumentException.class, () -> legacy.pluginValues(registry, false, true));
        assertEquals(LegacySystemSettings.COMFYUI_BEARER_REQUIRED, comfyui.getMessage());
        assertNull(comfyui.getCause());

        IllegalArgumentException opencli =
            assertThrows(
                IllegalArgumentException.class, () -> legacy.pluginValues(registry, true, false));
        assertEquals(LegacySystemSettings.OPENCLI_API_KEY_REQUIRED, opencli.getMessage());
        assertNull(opencli.getCause());

        assertEquals(0, dataSource.openedConnections());
      }
      assertEquals(0, probe.connections());
    }
  }

  /** 启用 H3 但旧端点/Agent 缺失时不得拿新默认伪装成合法旧配置。 */
  @Test
  void rejectsMissingH3IdentityInsteadOfApplyingEnabledDefaults() throws IOException {
    CountingDataSource dataSource = new CountingDataSource();
    try (LocalHttpProbe probe = LocalHttpProbe.start()) {
      try (OfflinePluginCatalog catalog =
          OfflinePluginCatalog.open(dataSource, parse(ENABLED, probe.proxyUrl()).settings())) {
        for (String field : List.of("promptAgentName", "comfyBaseUrl")) {
          for (boolean omitted : List.of(false, true)) {
            ObjectNode integrations =
                (ObjectNode) JsonValues.readTree(LegacySystemSettingsTest.fixture(ENABLED));
            ObjectNode h3 = (ObjectNode) integrations.get("minimaxH3");
            if (omitted) {
              h3.remove(field);
            } else {
              h3.putNull(field);
            }
            IllegalArgumentException error =
                assertThrows(
                    IllegalArgumentException.class,
                    () ->
                        LegacySystemSettings.parse(
                                legacyJsonWithProxy(integrations, probe.proxyUrl()))
                            .pluginValues(catalog.registry(), true, true));
            assertEquals(LegacySystemSettings.MINIMAX_H3_BLOCK, error.getMessage());
            assertNull(error.getCause());
          }
        }
        assertEquals(0, dataSource.openedConnections());
      }
      assertEquals(0, probe.connections());
    }
  }

  /** 安装集合必须恰为冻结四插件 profile：缺、多或空都 fail-closed，不新增安装 authority。 */
  @Test
  void rejectsAnInstallSetThatIsNotTheFrozenProfile() throws IOException {
    CountingDataSource dataSource = new CountingDataSource();
    try (LocalHttpProbe probe = LocalHttpProbe.start()) {
      LegacySystemSettings legacy = parse(DEFAULTS, probe.proxyUrl());
      try (OfflinePluginCatalog catalog =
          OfflinePluginCatalog.open(dataSource, legacy.settings())) {
        StudioPluginRegistry registry = catalog.registry();

        assertRejectedByProfile(legacy, new StudioPluginRegistry(List.of()));
        List<StudioPlugin> missing = new ArrayList<>();
        for (StudioPlugin plugin : registry.plugins()) {
          if (!"opencli".equals(plugin.descriptor().pluginId())) {
            missing.add(plugin);
          }
        }
        assertRejectedByProfile(legacy, new StudioPluginRegistry(missing));
        List<StudioPlugin> extra =
            Stream.concat(registry.plugins().stream(), Stream.of(EXTRA_PLUGIN)).toList();
        assertRejectedByProfile(legacy, new StudioPluginRegistry(extra));

        assertEquals(0, dataSource.openedConnections());
      }
      assertEquals(0, probe.connections());
    }
  }

  /** 坏 URL 与越界预算在真实 configure 明确拒绝，绝不 clamp / strip / fallback，且仍然零 IO。 */
  @Test
  void rejectsBadUrlsAndOutOfRangeBudgetsAtTheRealConfigure() throws IOException {
    CountingDataSource dataSource = new CountingDataSource();
    try (LocalHttpProbe probe = LocalHttpProbe.start()) {
      try (OfflinePluginCatalog catalog =
          OfflinePluginCatalog.open(dataSource, parse(ENABLED, probe.proxyUrl()).settings())) {
        StudioPluginRegistry registry = catalog.registry();

        ObjectNode badUrl =
            (ObjectNode) JsonValues.readTree(LegacySystemSettingsTest.fixture(ENABLED));
        ((ObjectNode) badUrl.get("minimaxH3")).put("comfyBaseUrl", "ftp://legacy-comfy");
        assertConfigureRejected(catalog, registry, probe, badUrl, "comfyui");

        ObjectNode outOfRange =
            (ObjectNode) JsonValues.readTree(LegacySystemSettingsTest.fixture(ENABLED));
        ((ObjectNode) outOfRange.get("minimaxH3")).put("comfyPollIntervalMillis", 120_000L);
        assertConfigureRejected(catalog, registry, probe, outOfRange, "comfyui");

        // 旧 Hub 的 URI 校验没有端口范围检查，目标拒绝时不能自动改成默认端口。
        for (int port : List.of(0, 70_000)) {
          ObjectNode badPort =
              (ObjectNode) JsonValues.readTree(LegacySystemSettingsTest.fixture(ENABLED));
          ((ObjectNode) badPort.get("openCliHub"))
              .put("baseUrl", "https://legacy-hub.example:" + port);
          assertConfigureRejected(catalog, registry, probe, badPort, "opencli");
        }

        assertEquals(0, dataSource.openedConnections());
      }
      assertEquals(0, probe.connections());
    }
  }

  private static void assertConfigureRejected(
      OfflinePluginCatalog catalog,
      StudioPluginRegistry registry,
      LocalHttpProbe probe,
      ObjectNode integrations,
      String expectedPluginId) {
    LegacySystemSettings legacy =
        LegacySystemSettings.parse(legacyJsonWithProxy(integrations, probe.proxyUrl()));
    Map<String, String> values = legacy.pluginValues(registry, true, true);
    IllegalStateException rejection =
        assertThrows(
            IllegalStateException.class,
            () -> catalog.prepareInitial(values, secretFields(registry, true, true)));
    assertEquals(
        "Plugin catalog snapshot build failed: stage=configure pluginId=" + expectedPluginId,
        rejection.getMessage());
    assertNull(rejection.getCause());
    assertEquals(0, probe.connections(), "rejection must not issue any HTTP request");
  }

  private static void assertRejectedByProfile(
      LegacySystemSettings legacy, StudioPluginRegistry registry) {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> legacy.pluginValues(registry, false, false));
    assertEquals(LegacySystemSettings.PLUGIN_PROFILE, error.getMessage());
    assertNull(error.getCause());
  }

  private static String legacyJsonWithProxy(ObjectNode integrations, String proxyUrl) {
    ObjectNode root =
        (ObjectNode)
            JsonValues.readTree(
                LegacySystemSettingsTest.legacyJsonWithIntegrations(
                    JsonValues.write(integrations)));
    ((ObjectNode) root.get("network")).put("proxyUrl", proxyUrl).put("noProxyHosts", "");
    return JsonValues.write(root);
  }

  private static LegacySystemSettings parse(String integrationsFixture, String proxyUrl) {
    return LegacySystemSettings.parse(
        legacyJsonWithProxy(
            (ObjectNode) JsonValues.readTree(LegacySystemSettingsTest.fixture(integrationsFixture)),
            proxyUrl));
  }

  private static Map<String, List<String>> secretFields(
      StudioPluginRegistry registry, boolean comfyBearer, boolean opencliApiKey) {
    Map<String, List<String>> fields = new LinkedHashMap<>();
    for (StudioPlugin plugin : registry.plugins()) {
      String pluginId = plugin.descriptor().pluginId();
      if ("comfyui".equals(pluginId) && comfyBearer) {
        fields.put(pluginId, List.of("apiKey"));
      } else if ("opencli".equals(pluginId) && opencliApiKey) {
        fields.put(pluginId, List.of("apiKey"));
      } else {
        fields.put(pluginId, List.of());
      }
    }
    return Map.copyOf(fields);
  }

  private static Set<String> workflowIds(ObjectNode comfyui) {
    Set<String> ids = new LinkedHashSet<>();
    for (JsonNode workflow : comfyui.get("workflows")) {
      ids.add(workflow.get("id").stringValue());
    }
    return ids;
  }

  private static ObjectNode readObject(String json) {
    return (ObjectNode) JsonValues.readTree(json);
  }
}
