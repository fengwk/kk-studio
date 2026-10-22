package fun.fengwk.kkstudio.plugin.comfyui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.canvas.function.CanvasFunctionAdapter;
import fun.fengwk.kkstudio.common.json.JsonValues;
import fun.fengwk.kkstudio.common.schema.ArraySchema;
import fun.fengwk.kkstudio.common.schema.ObjectSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.credential.PluginCredentialAccess;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptResources;
import fun.fengwk.kkstudio.platform.plugin.prompt.PluginPromptTasks;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 安装期契约：闭合 schema、唯一默认值来源、秘密与认证声明、requiredAgentNames 与实例装配边界。 */
class ComfyuiPluginTest {

  private final ComfyuiPlugin plugin = new ComfyuiPlugin();
  private final PluginConfigSchema schema = plugin.configurationSchema();

  @Test
  void defaultsAreTheOnlySourceAndStayDisabledWithTheH3Preset() {
    ComfyuiSettings settings = ComfyuiSettings.parse(schema.defaultsJson());

    assertFalse(settings.enabled());
    assertEquals("http://127.0.0.1:8188", settings.baseUrl());
    assertEquals(ComfyuiSettings.AuthMode.NONE, settings.authMode());
    assertEquals(10_000L, settings.connectTimeoutMillis());
    assertEquals(30_000L, settings.requestTimeoutMillis());
    assertEquals(2_000L, settings.pollIntervalMillis());
    assertEquals(1_800_000L, settings.maxWaitMillis());
    assertEquals("default-assistant", settings.promptAgentName());
    assertEquals(600_000L, settings.promptMaxWaitMillis());
    assertEquals(1, settings.workflows().size());

    ComfyuiWorkflow h3 = settings.workflows().get(0);
    assertEquals("minimax-h3-ref2va", h3.id());
    assertEquals(ComfyuiWorkflow.Strategy.H3, h3.strategy());
    assertEquals(1, h3.outputs().size());
    assertNull(h3.outputs().get(0).name());
    assertTrue(h3.mediaBindings().isEmpty());
    // 预置 selector 声明的节点就是构图使用的 SaveVideo 节点（默认 92）。
    assertEquals("92", h3.outputs().get(0).nodeId());
    assertEquals(Set.of(), plugin.requiredAgentNames(new PluginConfig(0, schema.defaultsJson())));
  }

  @Test
  void declaresOnlyTheApiKeySecretAndBaseUrlAuthModeAsAuthContext() {
    PluginAuthSpec authSpec = plugin.authenticationSpec();
    PluginAuthSpec.StaticKey staticKey = assertInstanceOf(PluginAuthSpec.StaticKey.class, authSpec);

    assertEquals("apiKey", staticKey.secretField());
    assertEquals(Set.of("baseUrl", "authMode"), staticKey.contextFields());
    assertEquals(Set.of("apiKey"), schema.secrets().keySet());
    assertEquals("comfyui", plugin.descriptor().pluginId());
  }

  @Test
  void requiredAgentNamesFollowEnabledH3WorkflowsOnly() {
    PluginConfig enabledH3 =
        new PluginConfig(
            1,
            ComfyuiWorkflowFixtures.settingsJson(true, ComfyuiWorkflowFixtures.h3WorkflowsArray()));
    assertEquals(Set.of("default-assistant"), plugin.requiredAgentNames(enabledH3));

    PluginConfig enabledDirect =
        new PluginConfig(
            2,
            ComfyuiWorkflowFixtures.settingsJson(
                true,
                ComfyuiWorkflowFixtures.singleWorkflowArray(
                    ComfyuiWorkflowFixtures.directWorkflow("direct-only"))));
    assertEquals(Set.of(), plugin.requiredAgentNames(enabledDirect));

    PluginConfig disabledH3 =
        new PluginConfig(
            3,
            ComfyuiWorkflowFixtures.settingsJson(
                false, ComfyuiWorkflowFixtures.h3WorkflowsArray()));
    assertEquals(Set.of(), plugin.requiredAgentNames(disabledH3));

    PluginConfig enabledEmptyWorkflows =
        new PluginConfig(4, ComfyuiWorkflowFixtures.settingsJson(true, "[]"));
    assertEquals(Set.of(), plugin.requiredAgentNames(enabledEmptyWorkflows));
  }

  @Test
  void resolveValuesRejectsUnknownFieldsAndNonClosedNestedObjects() {
    assertThrows(
        IllegalArgumentException.class,
        () -> schema.resolveValues("{\"enabled\":false,\"unknown\":1}"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            schema.resolveValues(
                ComfyuiWorkflowFixtures.settingsJson(
                    true,
                    ComfyuiWorkflowFixtures.singleWorkflowArray(
                        "{\"id\":\"a-b-c\",\"description\":\"d\",\"strategy\":\"DIRECT\","
                            + "\"workflowJson\":\"{}\",\"argsSchemaJson\":\"{}\","
                            + "\"parameterBindings\":[],\"mediaBindings\":[],\"outputs\":[],"
                            + "\"referencePolicy\":"
                            + ComfyuiWorkflowFixtures.IMAGE_POLICY
                            + ",\"extra\":1}"))));
    assertThrows(
        IllegalArgumentException.class, () -> schema.resolveValues("{\"authMode\":\"DIGEST\"}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> schema.resolveValues("{\"connectTimeoutMillis\":\"10000\"}"));
  }

  @Test
  void nestedWorkflowSchemaStaysClosed() {
    ArraySchema workflows =
        assertInstanceOf(ArraySchema.class, schema.valuesSchema().properties().get("workflows"));
    ObjectSchema workflow = assertInstanceOf(ObjectSchema.class, workflows.items());
    assertFalse(workflow.additionalProperties());
    ObjectSchema referencePolicy =
        assertInstanceOf(ObjectSchema.class, workflow.properties().get("referencePolicy"));
    assertFalse(referencePolicy.additionalProperties());
    ObjectSchema binding =
        assertInstanceOf(
            ObjectSchema.class,
            assertInstanceOf(ArraySchema.class, workflow.properties().get("parameterBindings"))
                .items());
    assertFalse(binding.additionalProperties());
  }

  @Test
  void disabledConfigurationContributesNothing() {
    PluginInstance instance =
        plugin.configure(
            pluginConfig(false, ComfyuiWorkflowFixtures.h3WorkflowsArray()), context());
    assertEquals(List.of(), instance.canvasAdapters());
    assertEquals(List.of(), instance.harnessContributors());
    instance.close();
    instance.close();
  }

  /** 停用且工作流为空时不装配任何运行期实例，因此绝不出网、也绝不解析凭据（即使 authMode 是 BEARER）。 */
  @Test
  void disabledConfigurationNeverResolvesCredentials() {
    ObjectNode defaults = (ObjectNode) JsonValues.readTree(ComfyuiWorkflowFixtures.defaultsJson());
    defaults.put("enabled", false);
    defaults.put("authMode", "BEARER");
    PluginCredentialAccess access = mock(PluginCredentialAccess.class);
    when(access.resolve())
        .thenThrow(new AssertionError("credentials must not be resolved while disabled"));
    PluginContext context =
        new PluginContext(access, Optional.empty(), Optional.empty(), Optional.empty());

    PluginInstance instance =
        plugin.configure(new PluginConfig(1L, JsonValues.write(defaults)), context);

    assertEquals(List.of(), instance.canvasAdapters());
    instance.close();
  }

  /** 停用不是"以后再校验"：非法工作流定义（如 args schema 非法）在配置期就被拒绝。 */
  @Test
  void disabledConfigurationStillRejectsIllegalWorkflowDefinitions() {
    PluginConfig config =
        pluginConfig(
            false,
            ComfyuiWorkflowFixtures.singleWorkflowArray(
                ComfyuiWorkflowFixtures.workflow(
                    "direct-args",
                    "DIRECT",
                    ComfyuiWorkflowFixtures.DIRECT_GRAPH,
                    "{\"type\":\"object\",\"additionalProperties\":false,"
                        + "\"properties\":{\"prompt\":{\"type\":\"uri\"}}}",
                    "[]",
                    "[]",
                    ComfyuiWorkflowFixtures.DIRECT_OUTPUTS,
                    ComfyuiWorkflowFixtures.IMAGE_POLICY)));
    assertThrows(IllegalArgumentException.class, () -> plugin.configure(config, context()));
  }

  @Test
  void enabledConfigurationWithNoWorkflowsContributesNothing() {
    PluginInstance instance = plugin.configure(pluginConfig(true, "[]"), context());
    assertEquals(List.of(), instance.canvasAdapters());
    instance.close();
  }

  @Test
  void enabledDirectWorkflowContributesOneOwnedAdapterWithoutPromptPorts() {
    PluginConfig config =
        pluginConfig(
            true,
            ComfyuiWorkflowFixtures.singleWorkflowArray(
                ComfyuiWorkflowFixtures.directWorkflow("direct-flow")));
    PluginInstance instance = plugin.configure(config, context());

    List<CanvasFunctionAdapter> adapters = instance.canvasAdapters();
    assertEquals(1, adapters.size());
    assertEquals(
        List.of("direct-flow"), adapters.get(0).functions().stream().map(f -> f.name()).toList());
    assertTrue(adapters.get(0).enabled());
    assertNull(adapters.get(0).unavailableReason());
    assertEquals(List.of(), instance.harnessContributors());
    instance.close();
  }

  /** H3 策略只能在绑定 Prompt 任务与媒体端口的部署里装配：未绑定即确定性失败，绝不静默降级。 */
  @Test
  void enabledH3WorkflowWithoutPromptPortsFailsDeterministically() {
    PluginConfig config = pluginConfig(true, ComfyuiWorkflowFixtures.h3WorkflowsArray());
    assertThrows(IllegalStateException.class, () -> plugin.configure(config, context()));
    assertThrows(
        IllegalStateException.class,
        () ->
            plugin.configure(
                config, context(Optional.of(mock(PluginPromptTasks.class)), Optional.empty())));
    assertThrows(
        IllegalStateException.class,
        () ->
            plugin.configure(
                config, context(Optional.empty(), Optional.of(mock(PluginPromptResources.class)))));
  }

  /** 绑定 Prompt 端口后 H3 预置装配出唯一 adapter；实例 close 幂等且自有客户端被释放。 */
  @Test
  void enabledH3WorkflowWithPromptPortsAssemblesOneOwnedAdapter() {
    PluginConfig config = pluginConfig(true, ComfyuiWorkflowFixtures.h3WorkflowsArray());
    PluginInstance instance =
        plugin.configure(
            config,
            context(
                Optional.of(mock(PluginPromptTasks.class)),
                Optional.of(mock(PluginPromptResources.class))));

    List<CanvasFunctionAdapter> adapters = instance.canvasAdapters();
    assertEquals(1, adapters.size());
    assertEquals(
        List.of(H3WorkflowBuilder.WORKFLOW_ID),
        adapters.get(0).functions().stream().map(f -> f.name()).toList());
    assertTrue(adapters.get(0).enabled());
    assertEquals(List.of(), instance.harnessContributors());
    instance.close();
    instance.close();
  }

  /** 自动配置只注册一个安装定义，不发布静态 adapter 或宿主内置 client Bean。 */
  @Test
  void autoConfigurationRegistersOnlyTheStudioPluginInstallDefinition() {
    ComfyuiAutoConfiguration configuration = new ComfyuiAutoConfiguration();
    assertInstanceOf(ComfyuiPlugin.class, configuration.comfyuiPlugin());
  }

  private PluginConfig pluginConfig(boolean enabled, String workflowsArrayJson) {
    return new PluginConfig(1L, ComfyuiWorkflowFixtures.settingsJson(enabled, workflowsArrayJson));
  }

  private static PluginContext context() {
    return context(Optional.empty(), Optional.empty());
  }

  private static PluginContext context(
      Optional<PluginPromptTasks> promptTasks, Optional<PluginPromptResources> promptResources) {
    return new PluginContext(
        mock(PluginCredentialAccess.class), Optional.empty(), promptTasks, promptResources);
  }
}
