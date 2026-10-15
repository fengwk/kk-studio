package fun.fengwk.kkstudio.plugin.comfyui;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.schema.ArraySchema;
import fun.fengwk.kkstudio.harness.common.schema.BooleanSchema;
import fun.fengwk.kkstudio.harness.common.schema.EnumSchema;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.common.schema.IntegerSchema;
import fun.fengwk.kkstudio.harness.common.schema.ObjectSchema;
import fun.fengwk.kkstudio.harness.common.schema.StringSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginAuthSpec;
import fun.fengwk.kkstudio.platform.plugin.PluginConfig;
import fun.fengwk.kkstudio.platform.plugin.PluginConfigSchema;
import fun.fengwk.kkstudio.platform.plugin.PluginContext;
import fun.fengwk.kkstudio.platform.plugin.PluginDescriptor;
import fun.fengwk.kkstudio.platform.plugin.PluginInstance;
import fun.fengwk.kkstudio.platform.plugin.StudioPlugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ComfyUI 的 Studio Plugin 安装期定义：安装身份、受控配置、静态密钥认证与运行期实例装配。
 *
 * <p>受控配置是唯一事实来源：连接与认证、Prompt 预算以及冻结工作流集合都来自 schema 声明的默认值与普通配置修订，秘密只有 {@code apiKey}。启动期只静态冻结
 * descriptor 与 schema，不联网也不解析凭据；运行期实例由 {@link #configure(PluginConfig, PluginContext)} 装配并归本 Plugin
 * 所有。
 */
public final class ComfyuiPlugin implements StudioPlugin {

  public static final String PLUGIN_ID = "comfyui";
  public static final String PLUGIN_NAME = "ComfyUI";
  public static final String PLUGIN_VERSION = "1.0.0";

  public static final PluginDescriptor DESCRIPTOR =
      new PluginDescriptor(PLUGIN_ID, PLUGIN_NAME, PLUGIN_VERSION);

  /** 隐藏 baseUrl/authMode 变化必须推进认证代际：两者共同决定认证目标与是否需要 Bearer。 */
  private static final PluginAuthSpec AUTHENTICATION_SPEC =
      new PluginAuthSpec.StaticKey("apiKey", Set.of("baseUrl", "authMode"));

  /** 唯一秘密：本地静态 Bearer 凭据；其它运行参数都是普通配置。 */
  private static final Map<String, String> SECRETS = Map.of("apiKey", "ComfyUI bearer token");

  /** 认证模式候选只在一处声明，避免 schema 与运行期枚举漂移。 */
  private static final List<String> AUTH_MODES =
      List.of(ComfyuiSettings.AuthMode.NONE.name(), ComfyuiSettings.AuthMode.BEARER.name());

  private static final String DEFAULTS_JSON = createDefaultsJson();

  private static final InputSchema VALUES_SCHEMA = createValuesSchema();

  public static final PluginConfigSchema CONFIGURATION_SCHEMA =
      new PluginConfigSchema(VALUES_SCHEMA, DEFAULTS_JSON, SECRETS);

  static {
    CONFIGURATION_SCHEMA.requireDeclaredSecrets(AUTHENTICATION_SPEC);
  }

  private final ObjectMapper mapper;

  public ComfyuiPlugin() {
    this(JsonMapper.builder().build());
  }

  public ComfyuiPlugin(ObjectMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public PluginDescriptor descriptor() {
    return DESCRIPTOR;
  }

  @Override
  public PluginConfigSchema configurationSchema() {
    return CONFIGURATION_SCHEMA;
  }

  @Override
  public PluginAuthSpec authenticationSpec() {
    return AUTHENTICATION_SPEC;
  }

  @Override
  public PluginInstance configure(PluginConfig config, PluginContext context) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(context, "context");
    ComfyuiSettings settings =
        ComfyuiSettings.parse(CONFIGURATION_SCHEMA.resolveValues(config.valuesJson()));
    return ComfyuiInstance.create(settings, mapper, context);
  }

  /** 只有启用且配置中存在 H3 策略工作流时才依赖 Prompt Agent；DIRECT 工作流不按字段路径猜测依赖。 */
  @Override
  public Set<String> requiredAgentNames(PluginConfig config) {
    Objects.requireNonNull(config, "config");
    ComfyuiSettings settings =
        ComfyuiSettings.parse(CONFIGURATION_SCHEMA.resolveValues(config.valuesJson()));
    if (!settings.enabled()) {
      return Set.of();
    }
    boolean h3 =
        settings.workflows().stream()
            .anyMatch(workflow -> workflow.strategy() == ComfyuiWorkflow.Strategy.H3);
    return h3 ? Set.of(settings.promptAgentName()) : Set.of();
  }

  /** 默认值唯一来源：合法配置默认不启用，启用后默认贡献完整 H3 预置工作流。 */
  private static String createDefaultsJson() {
    ObjectMapper mapper = JsonMapper.builder().build();
    ObjectNode root = mapper.createObjectNode();
    root.put("enabled", false);
    root.put("baseUrl", "http://127.0.0.1:8188");
    root.put("authMode", "NONE");
    root.put("connectTimeoutMillis", 10_000L);
    root.put("requestTimeoutMillis", 30_000L);
    root.put("pollIntervalMillis", 2_000L);
    root.put("maxWaitMillis", 1_800_000L);
    root.put("promptAgentName", "default-assistant");
    root.put("promptMaxWaitMillis", 600_000L);

    ArrayNode workflows = root.putArray("workflows");
    ObjectNode h3 = workflows.addObject();
    h3.put("id", H3WorkflowBuilder.WORKFLOW_ID);
    h3.put("description", H3WorkflowBuilder.DESCRIPTION);
    h3.put("strategy", "H3");
    h3.put("workflowJson", H3WorkflowBuilder.defaultTemplateJson());
    h3.put("argsSchemaJson", H3WorkflowBuilder.ARGS_SCHEMA_JSON);
    ArrayNode parameterBindings = h3.putArray("parameterBindings");
    for (ComfyuiWorkflow.ParameterBinding binding :
        H3WorkflowBuilder.intrinsicParameterBindings()) {
      ObjectNode item = parameterBindings.addObject();
      item.put("argument", binding.argument());
      item.put("nodeId", binding.nodeId());
      item.put("input", binding.input());
    }
    h3.putArray("mediaBindings");
    ComfyuiWorkflow.OutputSelector selector = H3WorkflowBuilder.defaultOutput();
    ObjectNode output = h3.putArray("outputs").addObject();
    output.put("kind", selector.kind().name());
    output.put("nodeId", selector.nodeId());
    output.put("field", selector.field().jsonName());
    output.put("index", selector.index());
    ComfyuiWorkflow.ReferencePolicy policy = H3WorkflowBuilder.referencePolicy();
    ObjectNode referencePolicy = h3.putObject("referencePolicy");
    ArrayNode allowedKinds = referencePolicy.putArray("allowedKinds");
    policy.allowedKinds().stream().map(Enum::name).sorted().forEach(allowedKinds::add);
    referencePolicy.put("maxCount", policy.maxCount());
    ArrayNode maxCountByKind = referencePolicy.putArray("maxCountByKind");
    policy.maxCountByKind().entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              ObjectNode item = maxCountByKind.addObject();
              item.put("kind", entry.getKey().name());
              item.put("maxCount", entry.getValue());
            });
    return mapper.writeValueAsString(root);
  }

  private static InputSchema createValuesSchema() {
    return new InputSchema(
        "ComfyUI configuration",
        Map.ofEntries(
            Map.entry(
                "enabled",
                new BooleanSchema("whether the ComfyUI plugin contributes its workflows")),
            Map.entry("baseUrl", new StringSchema("ComfyUI HTTP origin base URL")),
            Map.entry("authMode", new EnumSchema("ComfyUI authentication mode", AUTH_MODES)),
            Map.entry(
                "connectTimeoutMillis", new IntegerSchema("HTTP connect timeout in milliseconds")),
            Map.entry(
                "requestTimeoutMillis", new IntegerSchema("HTTP request timeout in milliseconds")),
            Map.entry(
                "pollIntervalMillis", new IntegerSchema("history poll interval in milliseconds")),
            Map.entry("maxWaitMillis", new IntegerSchema("maximum history wait in milliseconds")),
            Map.entry(
                "promptAgentName", new StringSchema("Prompt Agent used for H3 prompt enhancement")),
            Map.entry(
                "promptMaxWaitMillis",
                new IntegerSchema("H3 prompt enhancement timeout in milliseconds")),
            Map.entry(
                "workflows",
                new ArraySchema("frozen ComfyUI workflow definitions", workflowSchema()))),
        Set.of(
            "enabled",
            "baseUrl",
            "authMode",
            "connectTimeoutMillis",
            "requestTimeoutMillis",
            "pollIntervalMillis",
            "maxWaitMillis",
            "promptAgentName",
            "promptMaxWaitMillis",
            "workflows"),
        false);
  }

  private static ObjectSchema workflowSchema() {
    return new ObjectSchema(
        "frozen ComfyUI workflow",
        Map.ofEntries(
            Map.entry("id", new StringSchema("Canvas Function name of this workflow")),
            Map.entry("description", new StringSchema("Canvas Function description")),
            Map.entry("strategy", new EnumSchema("execution strategy", List.of("DIRECT", "H3"))),
            Map.entry("workflowJson", new StringSchema("strict ComfyUI API workflow JSON")),
            Map.entry(
                "argsSchemaJson", new StringSchema("strict Canvas Function args schema JSON")),
            Map.entry(
                "parameterBindings",
                new ArraySchema(
                    "declared argument to node/input bindings", parameterBindingSchema())),
            Map.entry(
                "mediaBindings",
                new ArraySchema(
                    "frozen reference index to node/input bindings", mediaBindingSchema())),
            Map.entry("outputs", new ArraySchema("frozen output selectors", outputSchema())),
            Map.entry("referencePolicy", referencePolicySchema())),
        Set.of(
            "id",
            "description",
            "strategy",
            "workflowJson",
            "argsSchemaJson",
            "parameterBindings",
            "mediaBindings",
            "outputs",
            "referencePolicy"),
        false);
  }

  private static ObjectSchema parameterBindingSchema() {
    return new ObjectSchema(
        "declared argument binding",
        Map.of(
            "argument", new StringSchema("declared top-level argsSchema argument"),
            "nodeId", new StringSchema("existing graph node id"),
            "input", new StringSchema("existing node input name")),
        Set.of("argument", "nodeId", "input"),
        false);
  }

  private static ObjectSchema mediaBindingSchema() {
    return new ObjectSchema(
        "frozen reference index binding",
        Map.of(
            "referenceIndex", new IntegerSchema("manifest order index of the frozen reference"),
            "nodeId", new StringSchema("existing graph node id"),
            "input", new StringSchema("existing node input name")),
        Set.of("referenceIndex", "nodeId", "input"),
        false);
  }

  private static ObjectSchema outputSchema() {
    return new ObjectSchema(
        "frozen output selector",
        Map.of(
            "name", new StringSchema("optional output name; required when several slots exist"),
            "kind", new EnumSchema("resource kind", List.of("IMAGE", "VIDEO", "AUDIO")),
            "nodeId", new StringSchema("existing graph node id"),
            "field", new EnumSchema("history output field", List.of("images", "videos", "audio")),
            "index", new IntegerSchema("index inside the history output array")),
        Set.of("kind", "nodeId", "field", "index"),
        false);
  }

  private static ObjectSchema referencePolicySchema() {
    return new ObjectSchema(
        "reference policy",
        Map.of(
            "allowedKinds",
            new ArraySchema(
                "allowed reference kinds",
                new EnumSchema("resource kind", List.of("IMAGE", "VIDEO", "AUDIO"))),
            "maxCount",
            new IntegerSchema("maximum number of references"),
            "maxCountByKind",
            new ArraySchema("per kind reference limits", maxCountByKindSchema())),
        Set.of("allowedKinds", "maxCount", "maxCountByKind"),
        false);
  }

  private static ObjectSchema maxCountByKindSchema() {
    return new ObjectSchema(
        "per kind reference limit",
        Map.of(
            "kind", new EnumSchema("resource kind", List.of("IMAGE", "VIDEO", "AUDIO")),
            "maxCount", new IntegerSchema("maximum number of references of this kind")),
        Set.of("kind", "maxCount"),
        false);
  }
}
